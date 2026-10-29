# Arquitectura de SashaBridge

Este documento describe cómo está construido SashaBridge en la versión 2.1: las piezas de cada lado, los hilos en los que trabaja cada una, los flujos principales y las decisiones de diseño con su motivo. El contrato entre los dos lados está en `docs/protocolo-v2.md`, y la forma de comprobar todo lo que se describe aquí, en `docs/verificacion.md`.

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
- **El S Pen** usa una segunda conexión WebSocket por el mismo túnel, en la ruta `/pen`, para que una captura grande o el flujo de muestras del lápiz no retrasen la música. Tiene dos modos:
  - **Anotar:** la PC envía al teléfono una captura de pantalla; el usuario la marca con el S Pen y la imagen anotada vuelve al portapapeles de la PC.
  - **Tableta:** el teléfono funciona como una tableta de dibujo; el S Pen mueve un lápiz sintético de Windows sobre un monitor de la PC.

```
 TELÉFONO                                                   PC
 AnnotateActivity ─ InkCanvasView ─┐                ┌─ annotate ── portapapeles, Imágenes\SashaBridge, notificación
                AnnotationRepository ┤ PenSession  │
                                     ├── /pen ─────┤ pen_route
                   TabletRepository ┤             │
 TabletActivity ─ TabletSurfaceView ─┘                └─ tablet ─── lápiz sintético de Windows (hilo tablet-pen)
                                                         hidden_window: Win+Shift+S / Ctrl+Alt+A ──► annotate
```

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
| `server/PenSession` | Conversación con una PC en la ruta `/pen`: estado de las dos pantallas del S Pen, capturas, anotaciones, monitor de la tableta, latencia y las muestras del lápiz en frames binarios. |
| `pen/AnnotationRepository`, `pen/TabletRepository` | Estado compartido entre `PenSession` y cada pantalla del S Pen. |
| `pen/AnnotateActivity`, `pen/InkCanvasView` | Modo Anotar: la captura, las herramientas (lápiz con presión, resaltador, línea, rectángulo, flecha, texto con tamaño y Mover), deshacer y rehacer, y el menú "+" para enviar o descartar. |
| `pen/InkShapes`, `pen/InkRenderer` | Marcas inmutables en píxeles de la captura, su geometría (borrar, mover, recuadro) y su dibujo, el mismo en pantalla y en la imagen exportada. |
| `pen/TabletActivity`, `pen/TabletSurfaceView` | Modo Tableta: área con la proporción del monitor y conversión de cada evento del S Pen en muestras. |
| `pen/EdgeGuard`, `pen/ImmediatePen` | Apartan el gesto "atrás" de los bordes donde está el lápiz, y piden a Android los eventos del lápiz sin agruparlos por fotograma. |
| `pen/CaptureNotifier` | Notificación de una captura que llega con la pantalla Anotar cerrada. |

### 2.1 Hilos

| Trabajo | Hilo o dispatcher |
|---|---|
| Ciclo de vida del servicio y de la actividad, callbacks de las sesiones multimedia, construcción de los estados | Hilo principal |
| Codificación de carátulas, y decodificación y codificación de las capturas de Anotar | `Dispatchers.Default` |
| Eventos del S Pen (dibujo, flotar) y su envío a `TabletRepository` | Hilo principal |
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

La tarjeta "Sonando" (`PlayerCard`) controla el reproductor desde el propio teléfono. Cada `MediaSnapshot` que recibe es una *instantánea*: una foto inmutable del reproductor en un momento, con la posición y la hora a la que el reproductor la informó, de modo que la posición actual se calcula sin esperar a otra actualización. Sus botones publican órdenes en `BridgeRepository.commands`, y el servicio las ejecuta con el mismo `MediaCommandExecutor` que las de la PC, esté el puente encendido o apagado. La barra de progreso avanza sola entre estados, extrapolando la posición con el reloj del teléfono. Al soltarla envía `SEEK` y mantiene la posición pedida hasta que el reproductor confirma la nueva. La tarjeta tiene de fondo la carátula muy desenfocada, bajo un velo que mantiene el texto legible. Muestra el icono real de la app que suena, tomado del sistema, y la parte reproducida de la barra es una onda (`WavyProgressDrawable`) que solo se anima mientras suena y la pantalla es visible.

La pantalla también ofrece dos funciones que el protocolo todavía no lleva a la PC: **repetir**, mediante la acción propia del reproductor (`RepeatControl`), y la **cola de reproducción** que publica cada app (`PlayerQueue`, en una hoja inferior desde la que se salta a cualquier canción). Ambas viajan como `PlayerCommand`, el tipo de las órdenes de la pantalla. Spotify publica el modo de repetir igual que el aleatorio, en el identificador de su acción: `TURN_REPEAT_ALL_ON` (sin repetir), `TURN_REPEAT_ONE_ON` (todas) y `TURN_REPEAT_OFF` (una). Echo Music permite pulsar repetir, pero no revela el modo, y la app oficial de YouTube Music no ofrece la acción.

**Cambio de canción.** La tarjeta no cambia de canción hasta tener la carátula nueva. En cuanto empieza el cambio (un botón, o el primer estado de otra pista), la carátula actual se desliza y se frena mientras espera; cuando llega la imagen, sale del todo y entra la nueva desde el otro lado. Si la pista no trae carátula en 1,5 s, se muestra sin ella; si un salto no cambia nada en 3 s, la tarjeta vuelve a su sitio.

**Orientaciones.** `layout/` y `layout-land/` comparten las tarjetas `card_status`, `card_player` y `card_battery`, con los mismos identificadores, así que `MainActivity` y `PlayerCard` no distinguen la orientación. En horizontal la pantalla se divide en dos columnas y el botón de apagar el puente no se muestra, para que girar el teléfono y usarlo como pantalla de "Sonando" no permita apagarlo por accidente.

Sasha, la perrita salchicha que da nombre a la app, acompaña el estado:

| Estado | Animación |
|---|---|
| PC conectada | Galopa mientras una pelota de tenis rebota hacia adelante y regresa. Cada uno o dos minutos, al azar, se detiene unos segundos a tomar agua de un balde |
| Pantallas del S Pen | Con su boina de pintor, moviendo la cola |
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
| `hidden_window` | Ventana Win32 invisible con su bucle de mensajes. SMTC la necesita en aplicaciones de escritorio, y también escucha el portapapeles (capturas nuevas) y el atajo `Ctrl+Alt+A`, que abre el recorte de Windows. |
| `backoff` | Espera exponencial de las dos conexiones. |
| `pen_route` | La sesión `/pen`: la mantiene abierta y reparte sus mensajes entre Anotar y Tableta. |
| `annotate` | Codifica las capturas y recibe las anotaciones: portapapeles, archivo en `Imágenes\SashaBridge` y notificación. |
| `clipboard`, `bitmap` | Imágenes del portapapeles (`CF_DIB` y `PNG`) y conversión entre DIB, píxeles y PNG. |
| `tablet` | Lápiz sintético de Windows, monitores y mapeo de las muestras a píxeles. |
| `notify` | Notificaciones de Windows con la identidad y el icono de SashaBridge. |

### 3.1 Hilos

| Trabajo | Hilo |
|---|---|
| Conexión, sesión y SMTC (`MediaControls` vive en la tarea raíz y no necesita ser `Send`) | Tarea principal de tokio |
| Bucle de mensajes de la ventana invisible | Hilo dedicado |
| Botones de Windows | Hilos de WinRT; solo encolan un `PcCommand` en un canal acotado |
| Sesión `/pen` | Tarea principal de tokio, junto a la de música |
| Codificar capturas, recibir anotaciones (portapapeles, archivo, notificación) | Hilos de `spawn_blocking` |
| Lápiz sintético | Hilo dedicado `tablet-pen`, que recibe las muestras por un canal; la inyección no frena la conexión |

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

**Anotar.**
1. Al abrirse la pantalla Anotar, el teléfono envía `annotate.status` con `armed: true`.
2. El usuario recorta con `Win+Shift+S`; la ventana invisible ve la imagen nueva en el portapapeles y la PC la envía como `annotate.request`. Con `Ctrl+Alt+A` se envía siempre; si la pantalla está cerrada, el teléfono muestra una notificación.
3. El usuario anota y pulsa "+" → "Enviar a la PC". El PNG vuelve como `annotate.result`.
4. La PC lo pone en el portapapeles (`CF_DIB` y `PNG`), lo guarda en `Imágenes\SashaBridge` y muestra una notificación con la imagen; al tocarla se abre el archivo.

**Tableta.**
1. Tras el `hello`, la PC envía `tablet.target` con el monitor actual; el teléfono le da al área de dibujo su proporción.
2. Cada evento del S Pen (con sus puntos históricos) se convierte en muestras y viaja en un frame binario.
3. En la PC, el hilo `tablet-pen` traduce cada cambio de estado (fuera de alcance, flotando, tocando) en eventos de un lápiz sintético y los inyecta en Windows.
4. El teléfono envía un `tablet.ping` por segundo y muestra la mitad de la ida y vuelta como latencia.
5. Al cerrar la pantalla, o si se corta la sesión, la PC levanta el lápiz.

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
| Ruta `/pen` aparte de la de música | Una captura de varios cientos de KB o el flujo continuo de muestras del lápiz no deben retrasar los cambios de canción. |
| Muestras del lápiz en frames binarios de 14 bytes | A unas 240 lecturas por segundo, JSON costaría varias veces más bytes. |
| Lápiz sintético de Windows (`CreateSyntheticPointerDevice`) en lugar de un controlador | Las apps lo ven como un lápiz Windows Ink de verdad, con presión, flotar, botón lateral y borrador, sin instalar nada. Requiere Windows 10 1809. |
| Proceso con DPI por monitor (v2) | El lápiz trabaja en píxeles físicos; sin esto, Windows escalaría las coordenadas en monitores con escala. |
| Solo el S Pen dibuja; los dedos hacen zoom o nada | La palma apoyada en la pantalla no debe dejar marcas. |
| Franja de exclusión del gesto "atrás" que sigue al lápiz | Android solo permite reservar 200 dp de cada borde; una franja móvil protege justo donde está el lápiz. Si el gesto se cuela en Anotar, abre el menú "+" en lugar de salir. |
| Eventos del lápiz sin agrupar por fotograma (`requestUnbufferedDispatch`) | Android agrupa la entrada una vez por fotograma, y la pantalla baja su frecuencia cuando casi nada se mueve; sin esto, las muestras llegarían tarde y a ráfagas. |
| Notificaciones con una identidad registrada en `HKCU\Software\Classes\AppUserModelId` | Un programa sin instalador no tiene acceso directo en el menú Inicio; el registro le da nombre e icono propios. Si Windows rechaza la notificación, suena un aviso. |

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

- **Tableta mapeada a una ventana.** Hoy el área de dibujo corresponde a un monitor entero. Mapearla a una sola ventana daría más precisión en apps pequeñas.
- **Ver la pantalla de la PC en el teléfono** mientras se dibuja en modo Tableta. Exige enviar vídeo de la PC al teléfono.
- **Más reproductores con modo aleatorio.** YouTube Music ofrece una acción `shuffle_action`, pero su identificador no cambia con el modo: solo lo indica el texto, que depende del idioma. Habría que interpretarlo de otra forma.
