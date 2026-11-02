# Arquitectura de SashaBridge

Este documento describe cómo está construido SashaBridge en la versión 2.2: las piezas de cada lado, los hilos en los que trabaja cada una, los flujos principales y las decisiones de diseño con su motivo. El contrato entre los dos lados está en `docs/protocolo-v2.md`, y la forma de comprobar todo lo que se describe aquí, en `docs/verificacion.md`.

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
| `media/ActiveControllerPolicy` | Elige el reproductor: primero el que suena, después el que cambió de estado más recientemente y por último el orden del sistema. Mientras se oculta un vídeo, solo puede elegir el reproductor de música fijado. Lógica pura. |
| `media/MediaSnapshot`, `PlaybackPositionCalculator` | Estado inmutable del reproductor y extrapolación de la posición al momento del envío. |
| `media/AlbumArtEncoder` | Prepara dos copias de la carátula fuera del hilo principal: un JPEG de 300 px como máximo para la PC, del que sale su `artId`, y la imagen tal como la da el reproductor (hasta 1080 px) para la pantalla del teléfono, que nunca viaja. |
| `media/MetadataNormalizer`, `ShuffleControl` | Separan el contexto que Spotify añade al artista y leen o cambian el modo aleatorio mediante las acciones propias del reproductor. |
| `media/RepeatControl`, `LikeControl`, `ActionNames` | Leen y pulsan "repetir" y "me gusta" con las acciones propias del reproductor, enviando los datos extra de cada acción como el panel multimedia de Android. YouTube Music no cambia el identificador de sus acciones: indica el modo en el nombre, en el idioma del teléfono, y `ActionNames` lo interpreta en español y en inglés. Spotify ignora la acción de quitar de "Tus me gusta" desde fuera de su app, también desde el panel de Android: solo se puede añadir. |
| `media/MediaCommandExecutor` | Ejecuta las órdenes de la PC y de la pantalla sobre el reproductor elegido. Sin reproductor, envía la tecla multimedia equivalente. `HIDE` oculta el vídeo y `UNHIDE` vuelve a él (ver la sección 4). |
| `media/SharpArtLoader` | Con Wi-Fi, descarga una carátula más grande que la del reproductor para la pantalla: Spotify da 640 px por la sesión y tiene 2000 px en su servidor; YouTube Music da 506 px y Google sirve hasta el original. `ThumbnailSources.sharpUrl` cambia el tamaño en la dirección, y `MediaSessionTracker` vuelve a publicar el estado con la imagen nueva, con el mismo `artId`. |
| `media/PlayerScreen` | Abre una app de música o de vídeo en la pantalla que publica su sesión, con el permiso que Android 14+ exige para hacerlo mientras SashaBridge está delante. La usan el icono de la tarjeta y la vuelta al vídeo. |
| `settings/SettingsRepository`, `SettingsActivity` | Ajustes del usuario (apariencia, forma de ocultar y si la pantalla negra cubre también el vertical, volver al vídeo, pantalla encendida en horizontal, guardar el historial) como `StateFlow` persistidos, y su pantalla, solo en vertical. |
| `settings/PreviewSection`, `BackdropPreview` | Las vistas previas desplegables de los ajustes: una fila que despliega o pliega el ejemplo, que solo se dibuja abierto y recuerda su estado, y la tarjeta de ejemplo del fondo, con la carátula de la canción que suena, de la última escuchada o una dibujada. |
| `settings/Appearance`, `SashaBridgeApp` | Tema claro u oscuro para toda la app (`AppCompatDelegate.setDefaultNightMode`, aplicado al arrancar el proceso) y color de acento: una superposición de tema (`ThemeOverlay.SashaBridge.Accent.*`) que cada pantalla aplica antes de inflar su diseño. |
| `media/HiddenPlayer` | La última canción del historial como reproductor en pausa, para mostrarla mientras se oculta un vídeo si su app ya no tiene sesión. Lógica pura. |
| `media/ThumbnailStore`, `ThumbnailSources` | Carátulas pequeñas (192 px) de la cola y del historial, en memoria y como WebP en la caché. Del historial, solo las de las apps que no dan la dirección web de su carátula van a los archivos de la app, y se borran cuando ninguna canción las nombra. |
| `history/HistoryRecorder`, `UsageMeter` | `HistoryRecorder` decide cuándo una canción cuenta como escuchada: 30 s sonando, o la mitad si es más corta. Si la misma canción vuelve a empezar (repetir una), es otra escucha. Lo que no tiene título no se graba. `UsageMeter` mide cuánto suena cada app, de música o de vídeo; las pausas no cuentan. Lógica pura. |
| `history/AppKinds`, `SongKeys` | `AppKinds` clasifica cada app como música, vídeo u otra (primero las apps conocidas, después la categoría que la app declara a Android), y una pista de YouTube Music sin álbum como vídeo. `SongKeys` da a cada canción una identidad estable: el identificador de Spotify, o el título y el artista normalizados. Lógica pura. |
| `history/HistoryDatabase`, `HistoryTables`, `HistoryDao`, `HistoryMigrations` | La base `history.db` con Room, versión 2: tablas `songs`, `plays`, `song_months` y `app_days` (ver la sección 2.4), sus consultas y la migración desde la versión 1. El esquema que exporta Room está en `app/schemas/`. |
| `history/HistoryRepository` | Guarda cada escucha y el tiempo de cada app. Da la lista de los últimos 30 días, las estadísticas de un periodo y la última canción (para ocultar un vídeo). Borra las escuchas viejas. |
| `history/HistoryActivity`, `HistoryListPage`, `PlayerLinks` | La pantalla del historial, con dos pestañas: la lista por días, con búsqueda y las repeticiones seguidas en una fila ("×N"), y Estadísticas. Cada día se pliega tocando su encabezado, que muestra cuántas escuchas tiene; mientras se busca, todos se ven abiertos. Tocar una canción la abre en su app (`PlayerLinks`). |
| `stats/StatsPage`, `DayBarsView`, `DayTooltip` | La pestaña Estadísticas: periodo (7 días, 30 días, este mes o todo), totales, música de cada uno de los últimos 30 días, tiempo por app y las canciones y artistas más escuchados. Tocar una barra del gráfico muestra su día y su tiempo exacto en una burbuja, dibujada en la capa superpuesta de la tarjeta para no ocupar sitio. |
| `ui/FrameTicker` | Reloj común de la pantalla (`Choreographer`, un paso cada 66 ms): mueve a Sasha, la onda de la barra y el tiempo transcurrido. |
| `bridge/BridgeRepository` | Única fuente de verdad del proceso: `enabled` (persistido), `status` del servidor, `media` y `listenerConnected`. La leen el servicio y la pantalla. |
| `server/BridgeServer` | Servidor WebSocket (Ktor 3, motor CIO) en `127.0.0.1:8080`. Arranque y parada serializados, reintento si el puerto está ocupado. |
| `server/ClientSession` | Conversación con una PC: saludo, estado inicial, cambios y órdenes. |
| `protocol/` | Tipos del protocolo v2 (`PhoneMessage`, `PcMessage`), su codificación (`ProtocolCodec`) y la traducción de estados a mensajes de una sesión (`MediaStateEncoder`). |
| `ui/BridgePhase`, `ui/PlayerCard`, `MainActivity` | Pantalla: estado del puente, avisos de permisos y batería, Sasha, la tarjeta del reproductor con sus controles y la barra de progreso, y la pantalla negra de la forma de ocultar "Pantalla negra". |
| `server/PenSession` | Conversación con una PC en la ruta `/pen`: estado de las dos pantallas del S Pen, capturas, anotaciones, monitor de la tableta, latencia y las muestras del lápiz en frames binarios. |
| `pen/AnnotationRepository`, `pen/TabletRepository` | Estado compartido entre `PenSession` y cada pantalla del S Pen. |
| `pen/AnnotateActivity`, `pen/InkCanvasView` | Modo Anotar: la captura (de la PC o compartida desde otra app), las herramientas (lápiz con presión, resaltador, línea, rectángulo, flecha, texto con tamaño, insertar imagen, Mover y Cambiar tamaño), deshacer y rehacer, y el menú "+" para enviar o descartar. |
| `pen/DragSession`, `pen/InkShapes` | Lógica pura, con tests: el arrastre de Mover y Cambiar tamaño, y la geometría de las marcas (límites, toque, mover y escalar). |
| `pen/InkShapes`, `pen/InkRenderer` | Marcas inmutables en píxeles de la captura, su geometría (borrar, mover, recuadro) y su dibujo, el mismo en pantalla y en la imagen exportada. |
| `pen/TabletActivity`, `pen/TabletSurfaceView` | Modo Tableta: área con la proporción del monitor y conversión de cada evento del S Pen en muestras. |
| `pen/EdgeGuard`, `pen/ImmediatePen` | Apartan el gesto "atrás" de los bordes donde está el lápiz, y piden a Android los eventos del lápiz sin agruparlos por fotograma. |
| `pen/CaptureNotifier` | Notificación de una captura que llega con la pantalla Anotar cerrada. |

### 2.1 Hilos

| Trabajo | Hilo o dispatcher |
|---|---|
| Ciclo de vida del servicio y de la actividad, callbacks de las sesiones multimedia, construcción de los estados | Hilo principal |
| Codificación de carátulas, y decodificación y codificación de las capturas de Anotar | `Dispatchers.Default` |
| Consultas del historial (Room, con `setQueryCoroutineContext`) y carátulas pequeñas (disco y descargas) | `Dispatchers.IO` |
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

La pantalla también ofrece funciones que el protocolo no lleva a la PC: **repetir**, mediante la acción propia del reproductor (`RepeatControl`), la **cola de reproducción** que publica cada app (`PlayerQueue`, en una hoja inferior con la carátula de cada canción, desde la que se salta a cualquiera) y el **historial**. Repetir y saltar en la cola viajan como `PlayerCommand`, el tipo de las órdenes de la pantalla. Cómo indica cada app el modo de repetir:
- **Spotify:** igual que el aleatorio, en el identificador de su acción: `TURN_REPEAT_ALL_ON` (sin repetir), `TURN_REPEAT_ONE_ON` (todas) y `TURN_REPEAT_OFF` (una).
- **YouTube Music:** en el nombre de `loop_mode_action`: "Repetición desactivada", "Repetición activada" (todas) y "Repetir uno" (una).
- **Echo Music:** permite pulsar repetir, pero no revela el modo.

"Me gusta" sí llega a la PC: el estado viaja en el campo `liked` de `media.state`, y la orden `TOGGLE_LIKE` sale del corazón de la tarjeta o del atajo `Ctrl+Alt+L`.

**Cambio de canción.** La tarjeta no cambia de canción hasta tener la carátula nueva. En cuanto empieza el cambio (un botón, o el primer estado de otra pista), la carátula actual se desliza y se frena mientras espera; cuando llega la imagen, sale del todo y entra la nueva desde el otro lado. Si la pista no trae carátula en 1,5 s, se muestra sin ella; si un salto no cambia nada en 3 s, la tarjeta vuelve a su sitio. La carátula sale hacia el lado de la canción nueva: con un botón, según el botón; si el cambio viene de la propia app o de la cola, según la posición de las dos canciones en la cola. Spotify envía la imagen entre 0,5 y 1,4 s después del título, pero publica su dirección `content://` (`ALBUM_ART_URI`) junto con el título, y `AlbumArtEncoder` la lee de ahí al momento.

**Orientaciones.** `layout/` y `layout-land/` comparten las tarjetas `card_status`, `card_player` y `card_battery`, con los mismos identificadores, así que `PlayerCard` no distingue la orientación. En horizontal la pantalla se divide en dos columnas y el botón de apagar el puente no se muestra, para que girar el teléfono y usarlo como pantalla de "Sonando" no permita apagarlo por accidente.

**Horizontal como adorno.** En horizontal la pantalla está pensada para quedarse encendida junto a la PC, así que ahorra espacio y energía:
- **Espacio:** sin barra de estado (la tarjeta de Estado muestra la batería y la hora), sin el pie de conexión, y la carátula ocupa el alto de su fila.
- **Pantalla encendida solo cargando:** `FLAG_KEEP_SCREEN_ON` mientras el teléfono carga; sin cargar, se apaga cuando Android diga.
- **Protección OLED:** cada minuto el contenido se mueve hasta 3 dp, en un ciclo de 8 posiciones.
- **Pocos fotogramas:**
  - Sasha, la onda de la barra y el tiempo avanzan juntos con `FrameTicker`, unos 15 pasos por segundo, en lugar de a la frecuencia de la pantalla.
  - La app pide a la pantalla el modo de 60 Hz.
  - El desenfoque de la carátula se calcula una vez por canción (`Backdrop`) en lugar de en la GPU en cada fotograma.
  - El título no usa marquesina.

  Medido en el S22 Ultra con música: de unos 120 fotogramas por segundo a unos 29. Sasha se dibuja con píxeles enteros y sin filtrado, así que se ve nítida.

Sasha, la perrita salchicha que da nombre a la app, acompaña el estado. Todo lo suyo (poses, animaciones, reloj de arena y tragos de agua) está en `ui/SashaView`, de modo que un sprite nuevo solo toca `tools/sasha_sprite.py` y esa clase:

| Estado | Animación |
|---|---|
| PC conectada | Galopa mientras una pelota de tenis rebota hacia adelante y regresa. Cada uno o dos minutos, al azar, se detiene unos segundos a tomar agua de un balde |
| Pantallas del S Pen | Con su boina de pintor, moviendo la cola |
| Esperando a la PC | Mueve la cola junto a un reloj de arena que se da la vuelta |
| Puente apagado | Duerme |
| Sin acceso o error | Quieta, mirando al frente |

El icono de la app, una Sasha en pixel art con más detalle sobre un fondo vino, lo genera `tools/sasha_icon.py` como icono adaptativo con capa monocroma para los iconos temáticos de Android. Los fotogramas son `VectorDrawable` generados por `tools/sasha_sprite.py` a partir de un dibujo en pixel art. Para cambiarlos se edita el script y se vuelve a ejecutar; los XML generados no se editan a mano.

### 2.4 Historial y estadísticas

El historial vive en `history.db`, una base SQLite que la app abre con **Room**. Room no es otra base de datos: es una capa sobre el SQLite del teléfono, con el mismo archivo y el mismo espacio. Lo que aporta está en el código:
- comprueba cada consulta al compilar;
- convierte las filas en objetos de Kotlin;
- exporta cada versión del esquema a `app/schemas/`;
- y permite observar una consulta como un `Flow`.

| Tabla | Qué guarda | Cuánto tiempo |
|---|---|---|
| `songs` | Cada canción una vez: título, artista, álbum, si es canción o vídeo, y dónde está su carátula | Siempre |
| `plays` | Una fila ligera por escucha: la canción y el momento | 30 días |
| `song_months` | Escuchas de cada canción por mes, sumadas en cada escucha | Siempre |
| `app_days` | Tiempo que sonó cada app cada día, vídeos incluidos | Siempre |

- **Quién escribe:**
  - `MusicListenerService` pasa cada estado del reproductor a `HistoryRecorder` (las canciones) y a `UsageMeter` (el tiempo por app).
  - Cada 10 s les pasa también el tiempo transcurrido, porque una canción que sigue sonando no envía estados nuevos.
  - Si el usuario desactiva el historial en los ajustes, no se guarda ninguna de las dos cosas.
- **Qué es una canción:** la identidad la da `SongKeys`.
  - En Spotify es el identificador de la pista (`spotify:track:...`).
  - En las demás apps son el título y el artista normalizados (Unicode NFKC, minúsculas y espacios simples).
  - Una canción que suena varias veces seguidas suma escuchas, y la lista las muestra en una fila con "×N".
- **Música o vídeo:** `AppKinds` clasifica cada app.
  - Primero mira una lista de apps conocidas; si no está, usa la categoría que la app declara a Android.
  - De una app de vídeo o de un navegador solo se guarda el tiempo, nunca los títulos.
  - En YouTube Music, una pista sin álbum es un vídeo (una subida de un fan, un videoclip), y la pantalla lo indica.
- **Carátulas:**
  - Spotify y YouTube Music dan una dirección web de cada carátula. La canción solo guarda esa dirección (`art_url`).
  - La imagen pasa a la caché de `ThumbnailStore`. Android puede vaciarla, y entonces se descarga de nuevo cuando hace falta.
  - Solo de las apps que no dan dirección se guarda una copia de 192 px en los archivos de la app (`cover_key`).
  - Una canción que ya tiene dirección deja de guardar copia. Las canciones migradas de la versión 1 no tenían dirección, así que sus copias se borran a medida que vuelven a sonar.
- **Retención:**
  - Cuando el servicio se conecta, `prune()` borra las escuchas de más de 30 días y las carátulas propias que ya no nombra ninguna canción.
  - Las canciones, los meses y los días se quedan, así que las estadísticas abarcan todo desde el principio.
  - SQLite no encoge el archivo al borrar filas: guarda el espacio para las siguientes. Si el espacio libre llega a 256 KB, `VACUUM` lo devuelve al teléfono.
- **Estadísticas:**
  - Los periodos de 7 y 30 días se cuentan con las escuchas.
  - "Este mes" y "Todo" se cuentan con los resúmenes mensuales, porque pueden abarcar más que los 30 días de escuchas.
  - El tiempo por app y el gráfico de días salen de `app_days`.
- **Migración de la versión 1 a la 2:**
  - La versión 1 era una sola tabla, `plays`, escrita con `SQLiteOpenHelper` y con los textos repetidos en cada fila.
  - `HistoryMigrations` la convierte en Kotlin y no solo en SQL: las claves de las canciones tienen que ser las mismas que `SongKeys` da a las escuchas nuevas, y la función `lower()` de SQLite solo conoce las 26 letras del inglés.
  - Las escuchas sin título (un navegador) se descartan. Las demás conservan su momento, y cada canción, sus textos más recientes y su carátula.
  - Room la ejecuta dentro de una transacción: si falla, el archivo queda como estaba.
  - Un test comprueba que las tablas que crea la migración son idénticas al esquema que exporta Room.

## 3. Cliente de PC

Crate `sashabridge_pc`, en `sashabridge-pc/src/`.

| Módulo | Responsabilidad |
|---|---|
| `main` | Arranque, orden de los componentes y cierre con `Ctrl+C` o al cerrar la consola. |
| `config` | Puerto, tiempos (espera de 1 a 5 s, tiempo máximo de conexión de 5 s) y búsqueda de los binarios de scrcpy. |
| `process` | `InstanceLock` (mutex con nombre: un solo cliente) y `DeviceLink`: arranque del servidor adb, túnel `adb forward`, scrcpy dentro de un Job Object, y `show_app`, que trae SashaBridge al frente del teléfono con `adb shell am start`. |
| `connection` | Bucle de conexión con espera exponencial y sesión WebSocket: saludo, mensajes del teléfono y envío de órdenes. |
| `protocol` | Tipos del protocolo v2, decodificación de mensajes del teléfono y codificación de órdenes. |
| `now_playing` | Convierte los mensajes en lo que debe mostrar Windows (`MediaView`) y decide qué carátula corresponde a cada estado. |
| `cover_cache` | Archivos de carátula en `%TEMP%\sashabridge\covers\`: uno por `artId`, y solo se conserva el actual. |
| `media_controls` | Sesión SMTC propia sobre la API WinRT: metadatos, carátula, línea de tiempo, estado, botón de aleatorio y botones de Windows convertidos en `PcCommand`. |
| `hidden_window` | Ventana Win32 invisible con su bucle de mensajes. SMTC la necesita en aplicaciones de escritorio. También escucha el portapapeles (capturas nuevas) y los atajos: `Ctrl+Alt+A` abre el recorte de Windows, `Ctrl+Alt+L` da "me gusta" y `Ctrl+Alt+O` oculta el vídeo del teléfono. |
| `osd` | Aviso propio en la esquina inferior derecha de la pantalla, que confirma un "me gusta": una ventana opaca, siempre encima y sin foco, que se desvanece a los 2 s. |
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

**Me gusta desde la PC.** `Ctrl+Alt+L` envía `TOGGLE_LIKE` y el teléfono pulsa la acción de "me gusta" del reproductor. Cuando el siguiente `media.state` trae el `liked` cambiado, la PC lo confirma con el aviso de `osd`. Si el reproductor no tiene esa acción, la PC solo lo registra.

**Ocultar un vídeo.**
1. `Ctrl+Alt+O` envía `HIDE` y, a continuación, la PC ejecuta `adb shell am start` para traer SashaBridge al frente. Con la forma "Pantalla negra", `MainActivity` cubre todo de negro, oculta las barras y baja el brillo al mínimo; tocar dos veces descubre SashaBridge.
2. El teléfono busca la última canción del historial y pausa todos los reproductores que suenan.
3. Mientras dure, `MediaSessionTracker` solo sigue a la app de esa canción. Si ya no tiene sesión, publica la canción del historial en pausa (`HiddenPlayer`), con la carátula tal como la dio el reproductor si sigue guardada, o la pequeña del historial. El vídeo nunca se publica.
4. Sin sesión de la app de música, reproducir abre la canción en su app (`PlayerLinks`); las demás órdenes se ignoran, porque una tecla multimedia iría al vídeo.
5. Termina cuando un reproductor vuelve a sonar, pasado un segundo: el vídeo, a punto de pausarse, aún puede informar de que suena.

**Volver al vídeo.**
1. Mientras hay un vídeo oculto, el teléfono envía `hide.status` con `hidden: true`, salvo que el usuario haya desactivado la vuelta en los ajustes. La PC lo guarda en `NowPlaying`.
2. Con eso, `connection` convierte la siguiente pulsación de `Ctrl+Alt+O` en `UNHIDE` y no ejecuta `am start`.
3. Con la pantalla encendida y desbloqueada, el teléfono termina la ocultación, abre la app del vídeo en la pantalla de su sesión (`PlayerScreen`) y reanuda lo que había pausado. Como una app de vídeo puede ignorar la orden hasta que su pantalla está delante, la repite a los 0,6 y a los 1,5 s si aún no suena. Una nueva ocultación cancela esas repeticiones; antes, una tercera pulsación rápida dejaba el vídeo sonando detrás de SashaBridge.
   - El vídeo al que se vuelve es el reproductor que se mostraba al ocultar, si no es de música, aunque estuviera en pausa. Uno que el usuario había pausado se abre en pausa; antes, sin nada que reanudar, la pulsación no hacía nada.
4. El teléfono envía `hide.status` con `hidden: false`, y el atajo vuelve a ocultar.

**Desconexiones.** Si el teléfono cierra la sesión o la app muere, la PC marca la sesión de Windows como detenida y vuelve al bucle de reintentos. Mientras espera, su consumo de CPU es prácticamente nulo.

**Anotar.**
1. Al abrirse la pantalla Anotar, el teléfono envía `annotate.status` con `armed: true`.
2. El usuario recorta con `Win+Shift+S`; la ventana invisible ve la imagen nueva en el portapapeles y la PC la envía como `annotate.request`. Con `Ctrl+Alt+A` se envía siempre; si la pantalla está cerrada, el teléfono muestra una notificación.
3. El usuario anota y pulsa "+" → "Enviar a la PC". El PNG vuelve como `annotate.result`.
4. La PC lo pone en el portapapeles (`CF_DIB` y `PNG`), lo guarda en `Imágenes\SashaBridge` y muestra una notificación con la imagen; al tocarla se abre el archivo.

El recorte de Windows escribe el portapapeles más de una vez por captura. La PC espera 300 ms sin cambios antes de leerlo y no reenvía una imagen idéntica en 10 s; el teléfono, además, descarta una captura repetida de la que acaba de abrir.

**Imagen compartida desde el teléfono.** Otra app comparte una imagen con SashaBridge (`ACTION_SEND`): se abre en Anotar como si fuera una captura, y al enviarla viaja como `annotate.shared`, sin `requestId`. Espera en una cola de `AnnotationRepository` hasta que una sesión `/pen` la toma, así que con la PC desconectada se envía al conectarse. La PC la trata igual que una anotación.

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
| Mover y Cambiar tamaño toleran que el lápiz pierda el contacto | Con presión suave, el S Pen se levanta un instante varias veces durante un arrastre (cortes medidos de 60 a 200 ms). Si vuelve a tocar la misma marca en menos de 300 ms, el arrastre sigue, en un solo paso de deshacer; al soltar, la marca nunca vuelve atrás. |
| El icono de la app abre la pantalla que publica su sesión | Cada reproductor indica en su `MediaSession` qué pantalla abrir; Android 14+ exige que quien la lanza lo autorice (`setPendingIntentBackgroundActivityStartMode`). Si no hay, se abre la app. |
| Eventos del lápiz sin agrupar por fotograma (`requestUnbufferedDispatch`) | Android agrupa la entrada una vez por fotograma, y la pantalla baja su frecuencia cuando casi nada se mueve; sin esto, las muestras llegarían tarde y a ráfagas. |
| Notificaciones con una identidad registrada en `HKCU\Software\Classes\AppUserModelId` | Un programa sin instalador no tiene acceso directo en el menú Inicio; el registro le da nombre e icono propios. Si Windows rechaza la notificación, suena un aviso. |
| Aviso propio (`osd`) para "me gusta" en lugar de una notificación | Con una app a pantalla completa, el asistente de concentración de Windows guarda las notificaciones sin mostrarlas. Una versión translúcida (`WS_EX_LAYERED`) no llegaba a dibujarse en esta PC; la ventana es opaca. |
| La PC abre SashaBridge con adb al ocultar un vídeo | Android no deja que una app en segundo plano abra su pantalla, y el servicio de notificaciones no está exento. La PC ya usa adb para el túnel, y adb puede abrir cualquier app. |
| Al volver, el teléfono abre el vídeo con la pantalla de su sesión, no la PC con adb | La pantalla que publica la sesión es la del vídeo que sonaba. Con adb solo se puede abrir la pantalla de inicio de la app, que en YouTube podría poner una página nueva encima del vídeo. SashaBridge está delante, así que Android le deja abrirla. |
| La PC sabe si toca ocultar o volver por `hide.status`, no por su cuenta | Solo el teléfono sabe si la ocultación sigue: termina sola si algo suena, o si la app se reinicia. Un estado propio de la PC se desincronizaría. |
| Volver no reanuda nada con la pantalla apagada o bloqueada | El vídeo sonaría desde un teléfono que nadie mira. Desbloqueado, SashaBridge (o la pantalla negra) sigue delante y el atajo funciona. |
| Al ocultar se muestra la última canción de música en pausa, sin reanudarla | Lo pidió el usuario (opción B): la pantalla debe parecer música sin que suene nada de golpe. La opción A, fundido a negro como si se apagara la pantalla, queda para una pantalla de ajustes. |
| El acento como atributos de tema (`colorPrimary`, `sashaAccent`, `sashaAccentPlayed`, `sashaAccentSoft`) | Las vistas y los dibujos leen el color del tema en lugar de un color fijo, así que cambiar de acento es aplicar otra superposición y rehacer la pantalla. Cada acento tiene tonos más claros para el tema oscuro. |
| Colores más vivos en el fondo desenfocado | Al desenfocar, los colores se mezclan hacia el gris, y con carátulas de colores suaves el fondo casi no se notaba bajo el velo. Ahora se refuerzan (`Backdrop.saturate`, ×1,45) y el velo es algo más fino. |
| Desenfoque e intensidad del fondo ajustables, de 0 a 100 | El desenfoque elige el radio (`Backdrop.blurRadius`, de 0 a 9 píxeles sobre una copia de 48 px). La intensidad elige la opacidad del velo (`Backdrop.veilAlpha`, del 100 al 50 % del velo más fuerte, que guarda el dibujo). Así se cambia sin rehacer la pantalla. |
| Un color primario nuevo redibuja los ajustes en el sitio | Rehacer la actividad (`recreate`) tardaba en mostrar la marca del color elegido. Ahora se aplica la superposición al tema y se vuelve a inflar el contenido, conservando el desplazamiento. |
| Las vistas previas usan carátulas del teléfono | La app no lleva carátulas de discos: tienen derechos de autor. La vista previa usa la de la canción que suena o la de la última escuchada. |
| Room sobre SQLite para el historial | Es el mismo SQLite, con el mismo archivo y el mismo espacio. A cambio, las consultas se comprueban al compilar, las filas llegan como objetos, el esquema de cada versión queda exportado con su migración probada, y la pantalla observa los cambios como un `Flow`. |
| Canciones y escuchas en tablas separadas | La versión 1 repetía el título, el artista, el álbum y la carátula en cada escucha. Ahora cada canción se guarda una vez y cada escucha es una fila de tres números. |
| Un resumen mensual en lugar de un archivo comprimido | Comprimir las escuchas viejas (ZIP o RAR) ahorraría espacio, pero no se podrían consultar sin descomprimirlas. Una fila por canción y mes ocupa poco y responde directamente a "este mes" y "todo". |
| La carátula como dirección web, no como imagen | Guardar cada carátula ocupaba unos 8 KB por canción. Spotify y YouTube Music dan una dirección estable de la imagen; si la caché se vacía, se descarga otra vez. Solo se guarda una copia propia de las apps que no dan dirección. |
| Lista de 30 días; estadísticas para siempre | Lo decidió el usuario. La lista sirve para encontrar algo escuchado hace poco, y los resúmenes por mes y por día crecen muy despacio. |
| De las apps de vídeo, solo el tiempo | Los títulos de los vídeos no son canciones y llenarían el historial; el tiempo sí sirve para comparar cuánto suena cada app. Lo decidió el usuario. |
| La pantalla usa la imagen del reproductor, no el JPEG de la PC | La carátula de la tarjeta mide unos 760 px en horizontal, y el JPEG de 300 px ampliado se veía borroso y con bloques. La PC sigue recibiendo 300 px, que bastan para el panel de Windows, así que el protocolo no cambia. |
| La carátula grande, solo con Wi-Fi y con el mismo `artId` | La de Spotify ocupa medio megabyte por canción. Con el mismo `artId`, la PC no recibe nada nuevo; la tarjeta cambia la imagen en el sitio, sin animación. |
| `SquareImageView` no se mide por la imagen | La pantalla horizontal se desplaza: primero se mide sin alto, y la carátula pedía el tamaño de su imagen. Con la de 1000 px la tarjeta se salía de la pantalla. Ahora, sin alto ni ancho dados, no pide espacio, y el `ScrollView` la mide otra vez con el que queda. |
| `VACUUM` solo con 256 KB libres o más | `VACUUM` reescribe el archivo entero. Hacerlo en cada limpieza gastaría escrituras para devolver unos pocos KB. |

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
- **Idiomas** (2.4): español, inglés y chino mandarín, con un selector en los ajustes.
- **Letras sincronizadas** (2.4), con traducción; antes, una prueba de que la fuente las tiene.
