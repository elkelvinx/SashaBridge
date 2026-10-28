# Verificación de SashaBridge

Cómo comprobar que SashaBridge funciona: primero las pruebas automáticas, después las manuales contra Windows y por último los escenarios de aceptación con el teléfono. Al final está el resultado de la regresión de la versión 2.0.

## 1. Pruebas automáticas

| Lado | Comando | Qué cubre |
|---|---|---|
| PC | `cargo clippy --all-targets -- -D warnings` | Análisis estático sin avisos |
| PC | `cargo test` | Fixtures del protocolo, regla de la carátula (R6), caché de carátulas, espera exponencial y botones de Windows |
| PC | `cargo doc --no-deps` | Documentación del código |
| Android | `.\gradlew.bat :app:testDebugUnitTest` | Fixtures del protocolo; elección del reproductor; posición; modos aleatorio y repetir; mensajes de una sesión; estado de la pantalla; formato de los tiempos; y el servidor real con un cliente WebSocket que hace de PC (saludo, estado inicial, órdenes, puerto ocupado sin cierre de la app, arranque y parada rápidos) |
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
