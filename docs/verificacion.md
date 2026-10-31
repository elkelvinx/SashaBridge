# Verificación de SashaBridge

Cómo comprobar que SashaBridge funciona: primero las pruebas automáticas, después las manuales contra Windows y por último los escenarios de aceptación con el teléfono, los de la música y los del S Pen. Al final están los resultados de la regresión de la versión 2.0 y de las pruebas de la 2.1, la 2.2 y la 2.3.

## 1. Pruebas automáticas

| Lado | Comando | Qué cubre |
|---|---|---|
| PC | `cargo clippy --all-targets -- -D warnings` | Análisis estático sin avisos |
| PC | `cargo test` | Fixtures del protocolo y de las muestras del lápiz, regla de la carátula (R6), caché de carátulas, espera exponencial, botones de Windows, conversión de imágenes (DIB y PNG), mapeo de la Tableta a un monitor, eventos del lápiz sintético, y el texto de las notificaciones |
| PC | `cargo doc --no-deps` | Documentación del código |
| Android | `.\gradlew.bat :app:testDebugUnitTest` | Fixtures del protocolo; elección del reproductor, también con un vídeo oculto; posición; modos aleatorio y repetir, incluidos los nombres de YouTube Music en español e inglés; me gusta; carátulas de la cola; historial; dirección de un cambio de canción según la cola; canción mostrada al ocultar un vídeo; mensajes de una sesión; estado de la pantalla; formato de los tiempos; geometría de las marcas de Anotar (flechas, borrar, mover, recuadro); y el servidor real con un cliente WebSocket que hace de PC (saludo, estado inicial, órdenes, puerto ocupado sin cierre de la app, arranque y parada rápidos, y la ruta `/pen` con capturas, monitor, latencia y muestras binarias) |
| Android | `.\gradlew.bat :app:lintDebug` | Análisis estático de la app |

Gradle necesita `JAVA_HOME` apuntando al JDK de Android Studio, por ejemplo `C:\Program Files\Android\Android Studio\jbr`.

Los tests de los dos lenguajes leen los mismos archivos de `protocol/fixtures/`, así que un fixture nuevo queda cubierto en ambos sin tocar código.

## 2. Pruebas manuales contra Windows

Publican una sesión multimedia real en la PC y la leen como lo haría un flyout. Hay que ejecutarlas con el cliente cerrado, porque crean su propia sesión:

```powershell
cd sashabridge-pc
cargo test -- --ignored
```

| Test | Comprueba |
|---|---|
| `windows_sees_cover_and_shuffle_changes` | Windows ve aparecer y desaparecer la carátula y el botón de aleatorio |
| `windows_shuffle_request_becomes_a_command` | Una pulsación del botón de aleatorio llega al cliente como orden |
| `pointer_devices` | Lista los dispositivos de puntero que ve Windows, incluido el lápiz sintético, con el área del escritorio que cubre cada uno |
| `notification_appears` | Registra la identidad de las notificaciones y muestra una con el icono de Sasha. Se comprueba mirando la pantalla, o en el historial: `[Windows.UI.Notifications.ToastNotificationManager]::History.GetHistory('io.github.elkelvinx.SashaBridge')` |

Con el cliente abierto, `tools/smtc-inspect.ps1` muestra cada sesión multimedia tal como la ve Windows: título, artista, carátula, estado, posición y botón de aleatorio. Con `-ToggleShuffle` pulsa el aleatorio de SashaBridge, útil porque ni el flyout de Windows ni FluentFlyout tienen ese botón. Necesita Windows PowerShell 5.1:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\smtc-inspect.ps1 [-ToggleShuffle]
```

## 3. Escenarios de aceptación

Herramientas útiles:

- `adb logcat -s SashaBridge` muestra el registro del teléfono.
- `$env:RUST_LOG = "debug"` activa el detalle del cliente de PC.
- `adb shell cmd media_session dispatch <play|pause|next|previous>` pulsa las teclas multimedia del teléfono sin tocarlo.

Se usa el `adb` de `sashabridge-pc\vendor\scrcpy\`, para que su versión coincida con la de scrcpy.

| ID | Escenario | Resultado esperado |
|---|---|---|
| E1 | Conectar la PC con una canción ya sonando | Estado y carátula completos en menos de 1 s |
| E2 | Cambiar de canción varias veces y volver a una anterior | Carátula correcta en todos los cambios, también al volver |
| E3 | Pausa o reproducción desde el teléfono | Se refleja en Windows; el puente añade menos de 300 ms |
| E4 | YouTube en pausa y Spotify sonando; pausa desde Windows | Los datos y las órdenes siguen a la app que suena |
| E5 | Encender y apagar el puente 5 veces seguidas | Sin cierre de la app y con estado consistente |
| E6 | Forzar la detención de la app con la PC conectada | La PC se reconecta sola y su CPU queda en reposo |
| E7 | La PC espera con el teléfono sin servidor | Reintenta a 1, 2, 4 y 5 s, sin pánico |
| E8 | Cerrar el cliente de forma abrupta | No queda ningún `scrcpy.exe` en ejecución |
| E9 | Conectarse a `<IP-Wi-Fi-del-teléfono>:8080` | Conexión rechazada |
| E10 | Pista sin carátula después de otra con carátula | Windows la muestra sin carátula |
| E11 | Reinstalar el APK | El servicio vuelve a enlazarse sin tocar los permisos |

### S Pen

| ID | Escenario | Resultado esperado |
|---|---|---|
| S1 | Anotar abierta; recortar en la PC con `Win+Shift+S` | La captura aparece en el teléfono en un par de segundos |
| S2 | Anotar cerrada; `Ctrl+Alt+A` y recortar | Llega una notificación al teléfono; al tocarla se abre la captura |
| S3 | Dibujar con cada herramienta, borrar con el botón lateral, mover marcas, deshacer | Cada acción se ve al instante y deshacer recorre los pasos en orden |
| S4 | Empezar un trazo pegado a un borde lateral; forzar el gesto atrás con el dedo | El trazo dibuja; el gesto abre el menú "+" en lugar de salir |
| S5 | "+" → Enviar a la PC | La imagen queda en el portapapeles y en `Imágenes\SashaBridge`, con notificación de Windows |
| S6 | Tableta: flotar, dibujar en Paint, botón lateral | El cursor sigue al lápiz, el trazo tiene grosor variable, el botón da clic derecho |
| S7 | Tableta con dos monitores; botón del monitor | El área cambia de proporción y el lápiz pasa al otro monitor |
| S8 | Música sonando mientras se usa Anotar o Tableta | Audio, panel de Windows y teclas multimedia siguen igual (E1 a E4) |
| S9 | Recortar con `Win+Shift+S` y con `Ctrl+Alt+A`; descartar | La captura se abre una sola vez; descartar una vez basta |
| S10 | Mover un recuadro distancias cortas, despacio y con poca presión | Queda justo donde se levanta el lápiz; deshacer lo devuelve entero |
| S11 | Mantener pulsado Mover → Cambiar tamaño; estirar un recuadro y un texto | El menú se abre hacia arriba; el recuadro se estira con la esquina opuesta fija; el texto crece de letra |
| S12 | Insertar imagen; Compartir → SashaBridge desde la Galería, con y sin PC | La foto aparece encima, centrada y seleccionada; la compartida llega al portapapeles, al momento o al conectar la PC |
| S13 | Tocar el icono de la app en la tarjeta del reproductor | Se abre esa app; "atrás" vuelve a SashaBridge |

### Música (2.3)

| ID | Escenario | Resultado esperado |
|---|---|---|
| M1 | Corazón de la tarjeta con Spotify y con YouTube Music | La canción entra o sale de "Me gusta" en la app; el corazón cambia de estado |
| M2 | `Ctrl+Alt+L` en la PC, también con una app a pantalla completa | Igual que M1, con el aviso en la esquina inferior derecha |
| M3 | Abrir la cola con Spotify y con YouTube Music | Cada canción con su carátula; tocar una salta a ella |
| M4 | Escuchar una canción 30 s; abrir Historial; buscar; tocar una canción | Aparece una vez, bajo "Hoy" y con carátula; la búsqueda la encuentra; tocarla la abre en su app. Un vídeo de YouTube no entra |
| M5 | Pasar de canción desde la propia app, hacia adelante y hacia atrás, y desde la cola | La carátula sale hacia el lado correcto, una sola vez por cambio |
| M6 | Pantalla en horizontal con la PC conectada | Sasha nítida, galope y onda sin saltos; la pantalla funciona a 60 Hz |
| M7 | Repetir en YouTube Music, tres pulsaciones | Sin repetir (icono neutro), todas (vino) y una (vino con "1"); el registro muestra `repeat=ALL` y `repeat=ONE` |
| M8 | Spotify con "repetir una"; dejar que la canción termine sola | La misma canción vuelve a empezar |
| M9 | Un vídeo de YouTube sonando; `Ctrl+Alt+O` en la PC | El vídeo se pausa; SashaBridge pasa al frente con la última canción de música en pausa, también en el panel de Windows; Spotify no arranca |
| M10 | Después de M9, reproducir en SashaBridge; después volver a YouTube y reproducir | Suena la música; con el vídeo, la tarjeta y Windows vuelven a seguirlo |

## 4. Regresión de la versión 2.0

- **Fecha:** 2026-10-28.
- **Teléfono:** Samsung Galaxy S22 Ultra (SM-S908U1), Android 16.
- **PC:** Windows 10 Pro 19045.
- **Pruebas automáticas:** 18 tests de Rust más 2 manuales contra Windows; 56 tests de Android; clippy y lint sin ningún aviso.

| ID | Resultado | Evidencia |
|---|---|---|
| E1 | **Correcto** | El cliente se conectó a los 0,19 s de arrancar y Windows mostraba la canción con carátula y modo aleatorio a los 0,21 s. |
| E2 | **Correcto** | Cuatro cambios con `next`: cada pista con su propio `artId` y carátula en Windows. Durante unos 0,6 s por cambio se ve la pista sin carátula, porque Spotify envía la imagen después del título; nunca aparece la de la pista anterior. Al volver de "Forces" a "awake", el teléfono reenvió la carátula de "awake" y Windows la mostró. |
| E3 | **Correcto** | Medido en el teléfono, de que Spotify cambia de estado a que SashaBridge lo publica: 26 ms. El transporte a la PC añade unos 15 ms. El tiempo total desde la tecla depende sobre todo de Spotify, que al pausar hace un fundido de unos 400 ms. |
| E4 | **Correcto** | Con YouTube en pausa y Spotify sonando, la pausa pedida desde Windows llegó como `PAUSE` a `com.spotify.music`. |
| E5 | **Correcto** | 10 pulsaciones rápidas del botón: 5 arranques y 5 paradas del servidor, sin errores; la PC se reconectó al final. |
| E6 | **Correcto** | Tras `am force-stop`, Android volvió a crear el servicio y la PC se reconectó en 1,3 s. CPU del cliente mientras tanto: 0,00 %. |
| E7 | **Correcto** | Con el puente apagado 12 s, el cliente reintentó a 1, 2, 4 y 5 s con un mensaje claro y se reconectó al encenderlo. CPU: 0,02 %. |
| E8 | **Correcto** | Después de terminar el cliente a la fuerza no quedó ningún `scrcpy.exe`. |
| E9 | **Correcto** | El servidor escucha solo en `127.0.0.1:8080`. Desde la PC el teléfono responde al ping por Wi-Fi, pero la conexión TCP al puerto 8080 se rechaza. |
| E10 | **Correcto** | Verificado en el dispositivo en la Fase 4 con una llamada grabada, y visible en E2: el estado intermedio sin carátula de cada cambio se muestra sin carátula. Cubierto también por los tests de `now_playing`. |
| E11 | **Correcto** | Tras `adb install -r`, Android volvió a enlazar el servicio y la PC se reconectó, sin tocar los permisos. |

Además se comprobó el ciclo completo del modo aleatorio desde Windows (`smtc-inspect.ps1 -ToggleShuffle`): desactivado, aleatorio, inteligente y otra vez desactivado.

### Pantalla del teléfono

Comprobado en el mismo dispositivo después del rediseño de la tarjeta "Sonando":

| Comprobación | Resultado |
|---|---|
| Botones de la pantalla | Reproducir, pausar, anterior, siguiente, aleatorio y mover la barra llegan al reproductor activo. |
| Repetir | Tres pulsaciones recorren el ciclo de Spotify: `TURN_REPEAT_ALL_ON` (sin repetir), `TURN_REPEAT_ONE_ON` (todas) y `TURN_REPEAT_OFF` (una); el icono muestra cada modo. |
| Cambio de canción | Con "siguiente", la carátula anterior se desliza mientras llega la nueva (0,75 s en la prueba) y la canción nueva aparece ya con su carátula, sin pasar por el icono de espera. |
| Cola | Muestra la cola de la app que suena y salta a la canción tocada. |
| Horizontal | Dos columnas sin desplazamiento: estado y Sasha a la izquierda, reproductor completo a la derecha; el botón de apagar el puente solo aparece en vertical. |
| Modo claro | Vertical y horizontal legibles, con el velo claro sobre la carátula desenfocada. |

## 5. Pruebas de la versión 2.1 (S Pen)

- **Fechas:** 2026-10-28 y 2026-10-29.
- **Mismo teléfono y PC** que la regresión 2.0. La PC tiene dos monitores de 1920×1080: el principal, y otro a su izquierda y 61 px más arriba, que en Windows empieza en (-1920, -61).
- **Pruebas automáticas:** 33 tests de Rust más 4 manuales contra Windows; 71 tests de Android; clippy y lint sin ningún aviso.

| ID | Resultado | Evidencia |
|---|---|---|
| S1 | **Correcto** | Probado por el usuario. |
| S2 | **Correcto** | Probado por el usuario. |
| S3 | **Correcto** | Probado por el usuario, incluida la herramienta Mover. |
| S4 | **Correcto** | Probado por el usuario tras añadir la franja de exclusión y el menú "+". |
| S5 | **Correcto** | Probado por el usuario con `Ctrl+V` en Paint. La notificación se comprobó aparte con `notification_appears`: Windows 10 la entregó al centro de actividades. |
| S6 | **Correcto** | Latencia por USB de 6 a 13 ms. Con la entrega de eventos sin agrupar (`requestUnbufferedDispatch`), Paint responde rápido y con presión. Excalidraw, en el navegador, dibuja con presión, pero suaviza y reconoce las formas, y eso se nota como retraso; no depende del puente. |
| S7 | **Correcto** | El lápiz caía en el otro monitor y, en el de la izquierda, fuera del escritorio: Windows mide los puntos inyectados desde la esquina del escritorio virtual, no desde el monitor principal. Corregido; el usuario dibujó en los dos monitores. La prueba manual `pointer_devices` muestra el área que Windows asigna al lápiz sintético. |
| S8 | **Correcto** | La música, el panel de Windows y las teclas multimedia siguen igual mientras se usan Anotar y Tableta. |

### Pantalla principal en horizontal como adorno

Medido con `adb shell dumpsys gfxinfo io.github.elkelvinx.sashabridge` (contador puesto a cero y leído 20 s después), con Spotify sonando y la PC conectada:

| Versión | Fotogramas en 20 s | Por segundo |
|---|---|---|
| 2.1 | 2441 | ~120: la app redibujaba en cada refresco de la pantalla |
| Después de la optimización | 585 | ~29: los 20 pasos de la onda y los ~9 cuadros de Sasha |

## 6. Pruebas de la versión 2.2 (Anotar)

- **Fecha:** 2026-10-30.
- **Pruebas automáticas:** 36 tests de Rust más 4 manuales; 92 tests de Android; clippy y lint sin avisos.
- **Lápiz simulado:** `adb shell input stylus swipe` y `input stylus tap` envían toques de tipo lápiz, útiles para probar sin el S Pen.

| ID | Resultado | Evidencia |
|---|---|---|
| S9 | **Correcto** | En la PC, dos copias de la misma imagen a 80 ms se leyeron una vez, y la misma imagen 2 s después se ignoró; una distinta pasó. Probado por el usuario con los dos atajos. |
| S10 | **Correcto** | Un registro temporal mostró el corte de contacto: cuatro toques en 0,6 s con pausas de 60 a 200 ms durante un solo arrastre. Probado por el usuario después de la corrección. |
| S11 | **Correcto** | Probado en el teléfono con el lápiz simulado: menú hacia arriba, recuadro estirado, nota "Hola" agrandada y deshecha. |
| S12 | **Correcto** | Probado en el teléfono: inserción centrada y redimensionada; Compartir desde la Galería (SashaBridge aparece en **Más**). El usuario comprobó la llegada a la PC. |
| S13 | **Correcto** | Spotify se abrió desde su sesión, en su inicio con la barra de "Sonando". |

## 7. Pruebas de la versión 2.3 (música)

- **Fechas:** 2026-10-30 y 2026-10-31.
- **Pruebas automáticas:** 40 tests de Rust más 5 manuales; 122 tests de Android; clippy y lint sin avisos.

| ID | Resultado | Evidencia |
|---|---|---|
| M1 | **Parcial** | Spotify: el corazón muestra y cambia el estado (`canLike` y `liked` en el registro). YouTube Music: falta comprobarlo en el teléfono. |
| M2 | **Correcto** | Probado por el usuario. La notificación de Windows no se veía con una app a pantalla completa (asistente de concentración); el aviso propio de la esquina sí, comprobado con una captura de pantalla. |
| M3 | **Correcto** | Probado por el usuario con las dos apps. La cola de Spotify, 42 carátulas, ocupa 476 KB en disco. |
| M4 | **Correcto** | Probado por el usuario. Una canción guardada tres veces seguidas quedó en una sola entrada. |
| M5 | **Correcto** | Comprobado con ráfagas de capturas de pantalla en los dos sentidos, y por el usuario. |
| M6 | **Correcto** | La pantalla funciona a 60 Hz (SurfaceFlinger); en pausa, 316 fotogramas en 20 s. Confirmado por el usuario. |
| M7 | **Correcto** | Probado por el usuario; el registro muestra `repeat=ALL` y `repeat=ONE`. |
| M8 | **Pendiente** | Spotify cambia de modo con cada pulsación (comprobado en el registro). Falta ver que la canción se repite al terminar sola. |
| M9 | **Correcto** | Con el cliente 2.3.0, `Ctrl+Alt+O` pausó `com.google.android.youtube` y SashaBridge pasó al frente con la última canción de YouTube Music en pausa. Probado por el usuario. |
| M10 | **Correcto** | `PLAY` llegó a YouTube Music y el modo oculto terminó 80 ms después. Al volver al vídeo, todo siguió al vídeo. Probado por el usuario. |
