# Verificación de SashaBridge

Cómo comprobar que SashaBridge funciona: primero las pruebas automáticas, después las manuales contra Windows y por último los escenarios de aceptación con el teléfono, los de la música y los del S Pen. Al final están los resultados de la regresión de la versión 2.0 y de las pruebas de la 2.1, la 2.2, la 2.3 y la 2.4.

## 1. Pruebas automáticas

| Lado | Comando | Qué cubre |
|---|---|---|
| PC | `cargo clippy --all-targets -- -D warnings` | Análisis estático sin avisos |
| PC | `cargo test` | Fixtures del protocolo y de las muestras del lápiz, regla de la carátula (R6), caché de carátulas, espera exponencial, botones de Windows, conversión de imágenes (DIB y PNG), mapeo de la Tableta a un monitor, eventos del lápiz sintético, y el texto de las notificaciones |
| PC | `cargo doc --no-deps` | Documentación del código |
| Android | `.\gradlew.bat :app:testDebugUnitTest` | Fixtures del protocolo; aviso de vídeo oculto a la PC; desenfoque y colores del fondo de la tarjeta; elección del reproductor, también con un vídeo oculto; posición; modos aleatorio y repetir, incluidos los nombres de YouTube Music en español e inglés; me gusta; carátulas de la cola; historial con la base de datos Room real en la JVM (lista y búsqueda, clasificaciones y totales, resúmenes mensuales que sobreviven a las escuchas, tiempo por app y día, carátulas que siguen a las escuchas, migración de la versión 1 y tablas iguales al esquema de Room); identidad de las canciones y tipo de cada app; periodos y gráfico de las estadísticas; dirección de un cambio de canción según la cola; canción mostrada al ocultar un vídeo; mensajes de una sesión; estado de la pantalla; formato de los tiempos; geometría de las marcas de Anotar (flechas, borrar, mover, recuadro); y el servidor real con un cliente WebSocket que hace de PC (saludo, estado inicial, órdenes, puerto ocupado sin cierre de la app, arranque y parada rápidos, y la ruta `/pen` con capturas, monitor, latencia y muestras binarias) |
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
| M11 | Una canción de Spotify en "Tus me gusta"; el corazón de la tarjeta | A los 2 s, aviso inferior: "No se quitó de Me gusta. Spotify no deja hacerlo desde fuera. Hazlo en Spotify." El corazón sigue lleno |
| M12 | Igual que M11 con `Ctrl+Alt+L`; y con un vídeo de YouTube | El mismo aviso en la esquina de la PC, con el icono ⓘ. Con YouTube: "YouTube no ofrece este botón a otras apps" |

### Ocultar y ajustes (2.4)

| ID | Escenario | Resultado esperado |
|---|---|---|
| H1 | Un vídeo de YouTube sonando; `Ctrl+Alt+O` dos veces, con unos segundos entre ellas | La primera oculta como en M9; la segunda abre YouTube en el mismo vídeo y lo reanuda |
| H2 | Ocultar; reproducir la música en SashaBridge; `Ctrl+Alt+O` | Suena la música y el atajo vuelve a ocultar (pausa la música), no reanuda el vídeo |
| H3 | Ocultar; dejar que la pantalla se apague; `Ctrl+Alt+O`; desbloquear; `Ctrl+Alt+O` | Con la pantalla apagada no suena nada; desbloqueado, vuelve al vídeo |
| H4 | Ajustes → "Pantalla negra"; ocultar un vídeo | Negro total sin barras; dos toques muestran SashaBridge; `Ctrl+Alt+O` vuelve al vídeo |
| H5 | Ajustes → desactivar "Volver al vídeo"; `Ctrl+Alt+O` dos veces | Las dos pulsaciones ocultan; el vídeo sigue en pausa |
| H6 | Ajustes → desactivar "Mantener encendida"; horizontal y cargando | La pantalla se apaga sola como siempre |
| H7 | Ajustes → desactivar "Guardar las canciones"; escuchar 30 s; después "Borrar el historial" | La canción no entra; tras confirmar, el historial queda vacío |
| H8 | Abrir los ajustes desde el engranaje y girar el teléfono | Los ajustes siguen en vertical; en horizontal la pantalla principal no muestra el engranaje |
| H9 | "Pantalla negra" con "También en vertical" apagado; ocultar en vertical y en horizontal | Al elegir la pantalla negra se despliega la opción; en vertical se ve la música en pausa y en horizontal, negro |
| H10 | Tema claro, oscuro y como el teléfono | Toda la app cambia al momento, también la pantalla principal al volver |
| H11 | Cada color de acento, en los dos temas | Botón de reproducir, barra, modos activos, corazón, cola e interruptores en ese color, legibles |
| H12 | Barra de progreso lisa, con una canción sonando | La parte reproducida es una línea recta que no se mueve; con ondas vuelve la onda |
| H13 | Canciones con carátulas de colores suaves y oscuras | El fondo desenfocado se nota más que en la 2.3 y el texto sigue legible |
| H14 | Elegir "Pantalla negra" y "Mostrar la música en pausa" varias veces | La opción queda elegida y "También en vertical" se despliega y se pliega; la app nunca se cierra |
| H15 | Cambiar el color primario con la vista previa abierta | La marca y los colores cambian al instante, en el mismo punto de la lista; la vista previa ya muestra el color nuevo |
| H16 | Mover los dos deslizadores del fondo con su vista previa abierta y una canción sonando | La tarjeta de ejemplo, con la carátula de esa canción, cambia mientras se arrastra; al volver, la tarjeta del reproductor se ve igual |
| H17 | Plegar las dos vistas previas, salir y volver | Siguen plegadas, sin dibujarse |
| H18 | Un vídeo en pausa, puesto en pausa por ti; `Ctrl+Alt+O` dos veces | La primera muestra SashaBridge; la segunda abre el vídeo donde estaba, en pausa |
| H19 | Un vídeo sonando; `Ctrl+Alt+O` tres veces, la tercera menos de 2 s después de la segunda | La tercera oculta el vídeo y queda en pausa; no sigue sonando detrás de SashaBridge |

### Historial y estadísticas (2.4)

| ID | Escenario | Resultado esperado |
|---|---|---|
| HS1 | Instalar la versión nueva sobre una con historial; abrir Historial | La app no se cierra; están las mismas escuchas que antes, salvo las que no tenían título (un navegador). Las repeticiones seguidas salen en una fila con "×N" |
| HS2 | Una canción con "repetir una" que vuelve a empezar y suena otros 30 s | Dos escuchas: la fila muestra "×2" |
| HS3 | Estadísticas → 7 días, 30 días, Este mes y Todo | Cada periodo cambia los totales y las listas; "Todo" cuenta también lo anterior a los 30 días |
| HS4 | Ver un vídeo de YouTube unos minutos; Estadísticas → Por app | YouTube aparece como "YouTube · vídeo", con su tiempo y la barra en gris. El vídeo no entra en el historial ni en "Más escuchadas" |
| HS5 | Un vídeo en YouTube Music (sin álbum) | Entra en el historial; en "Más escuchadas" lleva "· vídeo" junto al artista |
| HS6 | "Más escuchadas" con canciones de Spotify y de YouTube Music | Cada una con su carátula; tocar una la abre en su app |
| HS7 | Por día | 30 barras, la última es hoy, con el total de los 30 días |
| HS8 | Ajustes de Android → SashaBridge → Almacenamiento → Borrar caché; abrir Historial con conexión | Las carátulas vuelven: se descargan otra vez de su dirección |
| HS9 | Ajustes → "Borrar el historial" | La lista y las estadísticas quedan vacías |
| HS10 | Como H7, viendo también un vídeo | Tampoco suma tiempo en Estadísticas |
| HS11 | Tocar una barra de "Por día"; deslizar el dedo de lado; tocar otra vez la misma barra; deslizar en vertical desde el gráfico | Una burbuja con el día ("Hoy", "Ayer" o la fecha) y el tiempo exacto sobre la barra, que se resalta; al deslizar pasa de día en día con un toque de vibración; el segundo toque la cierra; en vertical se mueve la página |
| HS12 | Historial: tocar el encabezado "Ayer"; tocarlo otra vez; buscar algo de ayer con "Ayer" plegado | Se pliega con su número de escuchas a la vista y la flecha hacia abajo; se despliega; durante la búsqueda todos los días se ven abiertos y sin flecha |

### Carátulas (2.4)

| ID | Escenario | Resultado esperado |
|---|---|---|
| C1 | Carátula de la tarjeta con Spotify y con YouTube Music, en vertical y en horizontal | Nítida, sin bloques. El registro de la app dice el tamaño recibido (`Art 640x640 from content://...` en Spotify) |
| C2 | Ocultar un vídeo cuando la app de música ya no tiene sesión | La carátula de la última canción se ve igual de nítida |
| C3 | Panel de Windows durante C1 | La carátula sigue llegando a la PC como antes |
| C4 | Con Wi-Fi, una canción nueva de Spotify y otra de YouTube Music | Al segundo, la carátula de la tarjeta pasa a la versión grande sin animación (`Sharper art 1000x1000` en Spotify). Con datos móviles no se descarga |
| C5 | La carátula grande en horizontal | La tarjeta cabe entera en la pantalla: controles, barra de progreso y botones de Anotar y Tableta a la vista |
| C6 | Tarjeta en vertical con Spotify | "Sonando", la pastilla Cola, el reloj del Historial y el icono de la app en la misma línea; reproducir, aleatorio, repetir y el corazón debajo, sin nada cortado |

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

## 8. Pruebas de los ajustes (2.4)

- **Fecha:** 2026-10-31.
- **Pruebas automáticas:** 41 tests de Rust más 5 manuales; 127 tests de Android; clippy y lint sin avisos.

| ID | Resultado | Evidencia |
|---|---|---|
| H1-H3 | **Pendiente** | La PC sigue con el cliente 2.3.0, que aún no conoce `hide.status`: la segunda pulsación vuelve a ocultar (registro de las 11:31). Hay que reiniciarlo con `cargo run` |
| H4-H13 | **Correcto** | Probado por el usuario: "todos los cambios funcionaron bien en cuanto a configuración" |
| H14 | **Correcto** | La primera versión se cerraba al elegir "Pantalla negra": buscaba la tarjeta dentro del grupo de opciones (4 cierres en el registro). Corregido; en el teléfono, la opción se guarda (`hide_style=BLACKOUT`) y "También en vertical" se despliega |
| H15 | **Correcto** | En el teléfono: 0,35 s después de tocar el azul, la marca, los interruptores, los deslizadores y la vista previa ya eran azules |
| H16 | **Correcto** | En el teléfono: con la intensidad al máximo, la tarjeta de ejemplo (la carátula de "Radio", que sonaba) se vio más viva al momento |
| H17 | **Correcto** | En el teléfono, las vistas previas plegadas siguen plegadas al volver |

## 9. Pruebas del historial con Room (2.4)

- **Fecha:** 2026-11-02.
- **Pruebas automáticas:** 151 tests de Android; lint sin avisos.
- **Copia de seguridad:** antes de instalar se copiaron `history.db` (versión 1: 209 escuchas, una de Chrome sin título) y las carátulas guardadas.
- **Espacio:** `history.db` pasó de 56 a 100 KB, sin páginas libres. Cada tabla e índice ocupa al menos una página de 4 KB, y ahora hay más. A cambio, una escucha nueva ocupa unos 50 bytes en lugar de unos 200, y de Spotify y YouTube Music ya no se guarda la imagen de la carátula. Las 152 copias migradas (1,58 MB) se borran a medida que su canción vuelve a sonar o pasa 30 días sin sonar.

| ID | Resultado | Evidencia |
|---|---|---|
| HS1 | **Correcto** | La migración se ejecutó primero en la PC, con Room, sobre la copia, y después en el teléfono. Las dos dieron lo mismo: 208 escuchas, 161 canciones (140 canciones y 21 vídeos de YouTube Music), 172 resúmenes mensuales que suman 208, y "Castle Vein" con sus 11 escuchas; integridad correcta y versión 2. Sin cierres en `logcat -b crash`. La lista se ve con sus carátulas |
| HS3 | **Correcto** | En el teléfono: con 7 días y con Todo, 208 escuchas, 161 canciones y 142 artistas; con Este mes (noviembre), 25, 21 y 17. Son los mismos números que da la base |
| HS6 | **Parcial** | "Más escuchadas" muestra las carátulas migradas y "11 veces" en Castle Vein. Falta tocar una, que la abriría y la pondría a sonar |
| HS7 | **Correcto** | 30 barras hasta hoy; con 18 min de Spotify, "Últimos 30 días: 18 min en total". La versión 1 no medía el tiempo, así que todo empieza el 2 de noviembre |
| HS11 | **Correcto** | En el teléfono: "Hoy, 18 min" sobre la barra de hoy, junto al borde y con la flecha en la barra; "Vie 23 oct, Sin música" sobre un día vacío; el segundo toque la cierra; al deslizar de lado termina en el día bajo el dedo; en vertical se mueve la página |
| C1 | **Parcial** | Spotify da la carátula a 640 x 640 (`Art 640x640 from content://...`). Ampliada, la de antes se veía borrosa y con bloques de JPEG, y la de ahora deja ver la malla del velo y las piedras de los aretes. Falta YouTube Music |
| HS4 | **Parcial** | "Por app" muestra Spotify con 18 min. Falta un vídeo de YouTube |
| HS2, HS5, HS8-HS10, C2, C3 | **Pendiente** | Hay que escuchar música en el teléfono |

## 10. Pruebas de la iteración de carátulas y ocultar (2.4)

- **Fecha:** 2026-11-02.
- **Pruebas automáticas:** 159 tests de Android y 41 de Rust; lint y clippy sin avisos.

| ID | Resultado | Evidencia |
|---|---|---|
| M1 | **Parcial** | YouTube Music: el pulgar pasa de `liked=false` a `true` (registro de las 11:24). Spotify: añadir funciona, pero quitar no. SashaBridge pulsó `CHECK_FILL` siete veces y Spotify lo ignoró, también con los datos extra de la acción; la ✓ del panel multimedia de Android tampoco lo quita. Es una limitación de Spotify (ver "Problemas conocidos" del README) |
| H1 | **Correcto** | Con el cliente nuevo de la PC: ocultó, la segunda pulsación volvió a YouTube y lo reanudó (11:24:48), y la tercera ocultó otra vez |
| HS12 | **Correcto** | En el teléfono: "HOY · 35 escuchas" se pliega y "AYER · 14 escuchas" sube debajo; tocarlo otra vez lo despliega |
| C4 | **Parcial** | Spotify: 640 x 640 de la sesión y, un segundo después, `Sharper art 1000x1000` (y 1080 x 1080 en otra canción). YouTube Music: su servidor da hasta 600 px de esa carátula; falta verlo en el teléfono |
| C5 | **Correcto** | La primera versión estiraba la tarjeta horizontal fuera de la pantalla con la carátula de 1000 px: `SquareImageView` medía el tamaño de la imagen. Corregido; con 1080 x 1080 la tarjeta cabe entera |
| C6 | **Correcto** | Captura en vertical con Spotify: todo en una línea, nada cortado |
| H18, H19 | **Correcto** | Probados por el usuario con el cliente nuevo: "aceptables" |
| M11 | **Correcto** | Con "Punkrocker" en "Tus me gusta": `Pressing CHECK_FILL`, a los 2 s `com.spotify.music ignored CHECK_FILL` y el aviso inferior con el texto esperado; el corazón siguió lleno |
| M12 | **Correcto** | Probado por el usuario tras reiniciar el cliente de la PC |

## 11. Refactor (2.4)

- **Fecha:** 2026-11-02.
- **Pruebas automáticas:** 174 tests de Android (14 nuevos: `ImageDecoding`, `DayNames`, `DisplayMode`, `TitleFitter`, `Hiding` y `OrientationLayoutsTest`); lint sin avisos.
- **Sin cambios de comportamiento**, un commit por paso:

| Paso | Qué cambió | Comprobado en el teléfono |
|---|---|---|
| 1 | Un solo decodificador de imágenes, una sola descarga, `DayNames` y `AppNames` comunes | Carátula de la tarjeta e historial con "Hoy" y sus miniaturas |
| 2 | `DisplayMode` y `R.bool.display_mode` en lugar de los `if (landscape)` | `KEEP_SCREEN_ON` cargando en horizontal, no en vertical, y otra vez al volver; sin cierres al girar |
| 3 | `PlayerCard` (832 líneas) en `CoverArt`, `CoverMotion`, `PlayerControls`, `SeekRow` y `TitleFitter` (367) | La tarjeta se ve igual. **Pendiente:** la animación al cambiar de canción, con música |
| 4 | `MediaSessionTracker` (620) en `Hiding`, `SnapshotReader` y `SharpArtCache` (442) | Lee el reproductor y su carátula; la PC se conecta |
| 5 | Portada de la última canción y `prune`, todo o nada; `BlackoutOverlay` (`MainActivity`: 557 → 414) | La limpieza del arranque corre sin errores. **Pendiente:** la portada nueva tras 30 s de música y "Pantalla negra" (H4) |

