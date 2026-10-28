# Arquitectura de SashaBridge

Este documento describe cómo está construido SashaBridge en la versión 2.0: las piezas de cada lado, los hilos en los que trabaja cada una, los flujos principales y las decisiones de diseño con su motivo. El contrato entre los dos lados está en `docs/protocolo-v2.md`, y la forma de comprobar todo lo que se describe aquí, en `docs/verificacion.md`.

## 1. Visión general

```
 TELÉFONO (Android, Kotlin)                                PC (Windows, Rust)
 ┌───────────────────────────────────────────┐            ┌────────────────────────────────────────┐
 │ Reproductores (Spotify, YouTube, ...)     │            │                                        │
 │        │ MediaSession                     │            │  connection ──► NowPlaying ──► SMTC ──► flyout de Windows
 │        ▼                                  │            │      ▲              │ carátulas         │
 │ MediaSessionTracker ──► BridgeRepository  │  WebSocket │      │              ▼                   │
 │                           │      ▲       │  protocolo │      │          CoverCache (%TEMP%)     │
 │                           ▼      │       │     v2     │      │                                  │
 │                 BridgeServer ─ ClientSession ◄────────┼──────┘◄── PcCommand ◄── botones SMTC   │
 │                 127.0.0.1:8080            │  adb       │                                        │
 │ MediaCommandExecutor ◄── órdenes          │  forward   │  DeviceLink: adb + scrcpy (Job Object) │
 │ MainActivity (Sasha) ◄── BridgeRepository │  (USB)     │                                        │
 └───────────────────────────────────────────┘            └────────────────────────────────────────┘
                 audio del teléfono ─────────── scrcpy (USB) ───────────► altavoces de la PC
```

- **El audio** no pasa por SashaBridge: lo transporta scrcpy, que el cliente de PC lanza y vigila.
- **Los datos** (qué suena y las órdenes) viajan por un WebSocket. El teléfono es el servidor y escucha solo en su interfaz local. La PC llega a él por el túnel `adb forward tcp:8080 tcp:8080`, que va por el cable USB.
- **El estado manda, no los eventos.** El teléfono mantiene el estado completo del reproductor activo y lo envía entero a cada PC que se conecta y en cada cambio. Un mensaje perdido no deja la PC desincronizada, porque el siguiente la corrige.

## 2. App Android

Paquete `io.github.elkelvinx.sashabridge`, en `sashabridge-android/app/src/main/java/`.

| Paquete o clase | Responsabilidad |
|---|---|
| `MusicListenerService` | Servicio de escucha de notificaciones. Android lo enlaza cuando el usuario concede el acceso a notificaciones, y ese enlace es lo que permite leer las sesiones multimedia de otras apps. Solo coordina: arranca y detiene el resto. |
| `media/MediaSessionTracker` | Observa todas las sesiones activas (`OnActiveSessionsChangedListener` y un `MediaController.Callback` por reproductor) y publica un `StateFlow<MediaSnapshot?>` con el reproductor elegido. Agrupa las ráfagas de cambios. |
| `media/ActiveControllerPolicy` | Elige el reproductor: primero el que suena, después el que cambió de estado más recientemente y por último el orden del sistema. Lógica pura. |
| `media/MediaSnapshot`, `PlaybackPositionCalculator` | Estado inmutable del reproductor y extrapolación de la posición al momento del envío. |
| `media/AlbumArtEncoder` | Escala la carátula a 300 px como máximo, la codifica en JPEG fuera del hilo principal y calcula su `artId` a partir de los bytes. |
| `media/MetadataNormalizer`, `ShuffleControl` | Separan el contexto que Spotify añade al artista y leen o cambian el modo aleatorio mediante las acciones propias del reproductor. |
| `media/MediaCommandExecutor` | Ejecuta las órdenes de la PC sobre el reproductor elegido. Sin reproductor, envía la tecla multimedia equivalente. |
| `bridge/BridgeRepository` | Única fuente de verdad del proceso: `enabled` (persistido), `status` del servidor, `media` y `listenerConnected`. La leen el servicio y la pantalla. |
| `server/BridgeServer` | Servidor WebSocket (Ktor 3, motor CIO) en `127.0.0.1:8080`. Arranque y parada serializados, reintento si el puerto está ocupado. |
| `server/ClientSession` | Conversación con una PC: saludo, estado inicial, cambios y órdenes. |
| `protocol/` | Tipos del protocolo v2 (`PhoneMessage`, `PcMessage`), su codificación (`ProtocolCodec`) y la traducción de estados a mensajes de una sesión (`MediaStateEncoder`). |
| `ui/BridgePhase`, `ui/PlayerCard`, `MainActivity` | Pantalla: estado del puente, avisos de permisos y batería, Sasha, y la tarjeta del reproductor con sus controles y la barra de progreso. |

### 2.1 Hilos

| Trabajo | Hilo o dispatcher |
|---|---|
| Ciclo de vida del servicio y de la actividad, callbacks de las sesiones multimedia, construcción de los estados | Hilo principal |
| Codificación de carátulas | `Dispatchers.Default` |
| Arranque y parada del servidor, `bind` del puerto | `Dispatchers.IO` |
| Sesiones WebSocket y ejecución de las órdenes de la PC | Dispatcher de Ktor |

Todo lo compartido entre hilos es inmutable (`MediaSnapshot`, mensajes del protocolo) o se publica mediante `StateFlow`.

### 2.2 Ciclo de vida

1. Android crea `MusicListenerService` y, cuando el acceso a notificaciones está concedido, llama a `onListenerConnected`.
2. En ese momento se arranca `MediaSessionTracker`, su estado se copia a `BridgeRepository.media` y se observa `BridgeRepository.enabled`: mientras valga `true`, `BridgeServer` escucha; si pasa a `false`, se detiene y cierra las sesiones abiertas.
3. En `onListenerDisconnected` y en `onDestroy` se libera todo y se pide `requestRebind`. Si el usuario retiró el permiso, Android ignora la petición.

El puerto es un recurso del proceso, no de la instancia del servicio. Tras **Forzar detención**, Android puede destruir el servicio y crear otro en el mismo proceso casi a la vez. Por eso `start` y `stop` comparten un candado de proceso: la parada de la instancia vieja ocupa su lugar en la cola antes de que la nueva pida el puerto, y la nueva lo obtiene ya libre.

### 2.3 Pantalla

`MainActivity` observa `BridgeRepository` mientras está visible (`repeatOnLifecycle(STARTED)`). `BridgePhase` deriva lo que se muestra, por este orden de prioridad: sin acceso a notificaciones, puente apagado, esperando a Android, iniciando, esperando a la PC, PC conectada o error. El botón solo escribe `enabled`, así que la pantalla no puede contradecir al servicio.

La tarjeta "Sonando" (`PlayerCard`) controla el reproductor desde el propio teléfono. Sus botones publican órdenes en `BridgeRepository.commands`, y el servicio las ejecuta con el mismo `MediaCommandExecutor` que las de la PC, esté el puente encendido o apagado. La barra de progreso avanza sola entre estados, extrapolando la posición con el reloj del teléfono. Al soltarla envía `SEEK` y mantiene la posición pedida hasta que el reproductor confirma la nueva.

Sasha, la perrita salchicha que da nombre a la app, acompaña el estado:

| Estado | Animación |
|---|---|
| PC conectada | Corre detrás de una pelota de tenis |
| Esperando a la PC | Mueve la cola junto a un reloj de arena que se da la vuelta |
| Puente apagado | Duerme |
| Sin acceso o error | Quieta, mirando al frente |

El icono de la app, una Sasha en pixel art con más detalle sobre un fondo vino, lo genera `tools/sasha_icon.py` como icono adaptativo con capa monocroma para los iconos temáticos de Android. Los fotogramas son `VectorDrawable` generados por `tools/sasha_sprite.py` a partir de un dibujo en pixel art. Para cambiarlos se edita el script y se vuelve a ejecutar; los XML generados no se editan a mano.

## 3. Cliente de PC

Crate `sashabridge_pc`, en `sashabridge-pc/src/`.

| Módulo | Responsabilidad |
|---|---|
| `main` | Arranque, orden de los componentes y cierre con `Ctrl+C` o al cerrar la consola. |
| `config` | Puerto, tiempos (espera de 1 a 5 s, tiempo máximo de conexión de 5 s) y búsqueda de los binarios de scrcpy. |
| `process` | `InstanceLock` (mutex con nombre: un solo cliente) y `DeviceLink`: arranque del servidor adb, túnel `adb forward` y scrcpy dentro de un Job Object. |
| `connection` | Bucle de conexión con espera exponencial y sesión WebSocket: saludo, mensajes del teléfono y envío de órdenes. |
| `protocol` | Tipos del protocolo v2, decodificación de mensajes del teléfono y codificación de órdenes. |
| `now_playing` | Convierte los mensajes en lo que debe mostrar Windows (`MediaView`) y decide qué carátula corresponde a cada estado. |
| `cover_cache` | Archivos de carátula en `%TEMP%\sashabridge\covers\`: uno por `artId`, y solo se conserva el actual. |
| `media_controls` | Sesión SMTC propia sobre la API WinRT: metadatos, carátula, línea de tiempo, estado, botón de aleatorio y botones de Windows convertidos en `PcCommand`. |
| `smtc_window` | Ventana Win32 invisible con su bucle de mensajes, que SMTC necesita en aplicaciones de escritorio. |

### 3.1 Hilos

| Trabajo | Hilo |
|---|---|
| Conexión, sesión y SMTC (`MediaControls` vive en la tarea raíz y no necesita ser `Send`) | Tarea principal de tokio |
| Bucle de mensajes de la ventana invisible | Hilo dedicado |
| Botones de Windows | Hilos de WinRT; solo encolan un `PcCommand` en un canal acotado |

### 3.2 Ciclo de vida

1. `InstanceLock` impide abrir dos clientes.
2. `DeviceLink` arranca el servidor adb (fuera del Job Object, para que sobreviva como cualquier servidor adb), crea el túnel y lanza scrcpy suspendido. Después lo asigna al Job Object y lo reanuda. Así no hay ningún instante en que scrcpy pueda quedar huérfano.
3. `connection` conecta y, si falla, reintenta a 1, 2, 4 y 5 s. Si el túnel responde pero no hay nadie escuchando en el teléfono, lo indica con un mensaje claro. Cada 5 s comprueba que scrcpy sigue vivo y lo relanza si hace falta.
4. Al terminar, de cualquier forma (incluido el Administrador de tareas), Windows cierra el Job Object y con él scrcpy.

## 4. Flujos principales

**Conexión.** La PC abre el WebSocket y envía `hello`. El teléfono responde con su `hello` y, sin esperar a ningún cambio, envía el estado actual: `media.art` y `media.state`, o `media.cleared` si no suena nada. El estado inicial llega en unas decenas de milisegundos.

**Cambio de canción.** El reproductor emite una ráfaga de callbacks (Spotify, más de 20 por cambio). `MediaSessionTracker` publica el primero al instante y el estado final cuando pasan 150 ms sin cambios. `ClientSession` envía `media.art` solo si el `artId` es distinto del último que envió en la sesión, y siempre antes del `media.state` que lo cita. La PC muestra la carátula solo si el `artId` del estado coincide con la última imagen recibida; si no, muestra el estado sin carátula.

**Orden desde Windows.** Un botón del flyout produce un `PcCommand`, que viaja como `cmd` (`PLAY` y `PAUSE` son órdenes explícitas, no un interruptor). `MediaCommandExecutor` la aplica al mismo reproductor cuyo estado se ve en Windows, y el resultado vuelve con el siguiente `media.state`.

**Desconexiones.** Si el teléfono cierra la sesión o la app muere, la PC marca la sesión de Windows como detenida y vuelve al bucle de reintentos. Mientras espera, su consumo de CPU es prácticamente nulo.

## 5. Decisiones de diseño

| Decisión | Motivo |
|---|---|
| Leer la MediaSession en lugar de las notificaciones | Los reproductores actualizan la notificación y la sesión en momentos distintos, y una pausa o un salto no siempre publican notificación (R1). |
| Estado completo en un `StateFlow` | Una PC que se conecta tarde recibe el estado sin esperar a un cambio (R2), y un mensaje perdido no desincroniza nada. |
| Carátula separada del estado, con `artId` derivado de los bytes | La imagen viaja una sola vez mientras no cambie, y la PC nunca combina un estado con la carátula de otra pista (R6). |
| Reenviar la carátula cuando cambia respecto a la última enviada | La PC guarda una sola carátula; al volver a una pista anterior hay que enviarla otra vez. |
| Servidor solo en `127.0.0.1` | `adb forward` conecta desde dentro del teléfono, así que no hace falta exponer el puerto a la red Wi-Fi (A6). |
| Ktor 3 con el motor CIO | Netty pesaba más y obligaba a fijar una versión antigua de las corrutinas. CIO es Kotlin puro. |
| El motor de Ktor cuelga de un scope propio con manejador de excepciones | Ktor hace el `bind` en una corrutina propia; sin un padre, un puerto ocupado era una excepción no capturada que cerraba la app (A5). |
| Candado de proceso para `start` y `stop` | Android puede recrear el servicio en el mismo proceso antes de que el anterior libere el puerto (A4, A5). |
| Adaptador SMTC propio en lugar de una librería | Hacía falta quitar la carátula y ofrecer el botón de aleatorio, y ninguna librería lo permitía. |
| scrcpy en un Job Object con `KILL_ON_JOB_CLOSE` | Garantiza que scrcpy termina con el cliente aunque este muera de forma abrupta (A2). |
| Aleatorio mediante las acciones propias del reproductor | La MediaSession estándar no tiene modo aleatorio. El identificador de la acción de Spotify indica el modo actual: `TURN_SHUFFLE_ON` (desactivado), `TURN_SHUFFLE_OFF` (aleatorio) y `TURN_SMART_SHUFFLE_OFF` (inteligente). |
| Mismos fixtures JSON en los tests de Kotlin y de Rust | Una diferencia de interpretación del protocolo entre los dos lados hace fallar los tests de ambos. |

## 6. Defectos corregidos

Los identificadores vienen del plan de refactorización y de `docs/diagnostico-fase0.md`. Los comentarios del código los citan entre paréntesis.

| ID | Defecto en la línea base | Corrección |
|---|---|---|
| R1 | Estado leído al publicarse una notificación | `MediaSessionTracker` |
| R2 | La PC no recibía nada hasta el siguiente cambio | `StateFlow` y estado inicial en `ClientSession` |
| R3 | La carátula se marcaba como enviada antes de existir | `AlbumArtEncoder`, `MediaStateEncoder` |
| R4 | Se usaba el primer controlador en lugar del activo | `ActiveControllerPolicy`, `MediaCommandExecutor` |
| R5 | Posición sin extrapolar y duración `0` | `PlaybackPositionCalculator` |
| R6 | Carátula de la pista anterior en una pista sin carátula | `artId` y `now_playing` |
| R7 | Bucle al 100 % de CPU al desconectarse, sin reconexión | `connection` |
| A1 | `expect` en la conexión | `connection`, sin `unwrap` ni `expect` |
| A2 | scrcpy huérfano al cerrar el cliente | Job Object en `process` |
| A3 | Carátulas acumuladas en `%TEMP%` | `cover_cache` |
| A4 | Carrera al encender y apagar rápido | Candado de proceso en `BridgeServer` |
| A5 | `BindException` cerraba la app | Scope propio del motor y reintento |
| A6 | Servidor accesible desde la red | `BridgeConfig.HOST` |
| A7 | Estado "puente activo" duplicado | `BridgeRepository` y `BridgePhase` |
| A8 | Sin `onListenerConnected` ni `requestRebind` | `MusicListenerService` |
| A9 | Carátula procesada en el hilo principal | `AlbumArtEncoder` |
| A10 | `PLAY_PAUSE` como interruptor ciego | Órdenes `PLAY` y `PAUSE` |
| A11 | El botón de la app no llegaba al servicio | `BridgeRepository` sustituye al broadcast |
| A12 | Ráfagas de mensajes duplicados | Agrupación en `MediaSessionTracker` |
| A13 | Contexto de Spotify mezclado con el artista | `MetadataNormalizer` y campo `context` |

## 7. Extensiones previstas

- **Dibujo con el S Pen.** El protocolo admite tipos nuevos con prefijo propio (`pen.*`) sin cambiar de versión, porque los receptores ignoran lo que no conocen. Irán por una ruta WebSocket separada (`/pen`), para que una carátula grande no retrase los trazos. Sasha ya tiene su boina de pintor dibujada en `tools/sasha_sprite.py`, por ahora solo en la vista previa.
- **Más reproductores con modo aleatorio.** YouTube Music ofrece una acción `shuffle_action`, pero su identificador no cambia con el modo: solo lo indica el texto, que depende del idioma. Habría que interpretarlo de otra forma.
