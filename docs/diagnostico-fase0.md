# Diagnóstico de la Fase 0: línea base

- **Fecha:** 2026-10-27
- **Código evaluado:** línea base sin modificar (`_snapshots/1_AudioBridge/00-baseline.zip`), instalada y ejecutada antes del cambio de nombre a SashaBridge.
- **Objetivo:** confirmar o descartar con evidencia los defectos R1-R7 y A1-A10 del plan antes de modificar el código.

---

## 1. Entorno de prueba

| Elemento | Valor |
|---|---|
| Teléfono | Samsung Galaxy S22 Ultra (SM-S908U1), Android 16, API 36 |
| App | `com.example.minibridgeserver`, compilada desde la línea base e instalada con `adb install -r` |
| PC | Windows 10 Pro 19045, 12 núcleos lógicos |
| Cliente PC | `minibridge_pc`, perfil `dev` |
| Herramientas | `adb` (incluido en scrcpy 4.0), `logcat` filtrado por la etiqueta `MiniBridge`, observador WebSocket propio (PowerShell, `System.Net.WebSockets.ClientWebSocket`), `dumpsys media_session`, `dumpsys activity broadcasts`, `/proc/net/tcp6` |
| Sesiones multimedia activas | Spotify (reproduciendo) y YouTube (en pausa) |

**Nota de lectura:** el reloj del teléfono va unos 0,85 s por delante del de la PC. Las marcas de tiempo de `logcat` (teléfono) y del observador (PC) no se pueden comparar directamente.

---

## 2. Resultados por escenario

| ID | Resultado | Evidencia |
|---|---|---|
| E1 | **Falla.** Confirma R2. | Observador conectado durante 8 s con Spotify reproduciendo: no llegó ningún mensaje. |
| E2 | **Falla.** Confirma R3, R5 y A12. | Un único `NEXT` generó 17 mensajes en 380 ms. El título llegó en dos etapas ("The Night and the Lie" y después "The Night and the Lie - 夜と嘘"). Ningún mensaje incluyó `albumArt`. Las posiciones dentro de la ráfaga no son monótonas (652, 660, 651, 655). |
| E3 | **Falla.** Confirma R1. | La pausa enviada desde el teléfono a las 13:36:02.460 no produjo ningún mensaje. El estado `isPlaying=false` llegó a las 13:36:07.310, 4,85 s después, arrastrado por la reanudación posterior y repetido en 15 mensajes idénticos. El `SEEK` enviado por WebSocket sí se reflejó en 186 ms. |
| E4 | **No reproducido.** R4 | Con YouTube en pausa, los dos `PLAY_PAUSE` se dirigieron a `com.spotify.music`, tanto con Spotify reproduciendo como en pausa (según `logcat`). Los metadatos enviados también fueron siempre los de Spotify. |
| E5 | **No reproducible** (A4) mientras exista A11. | Se hicieron 3 rondas de 6 pulsaciones seguidas. El servidor nunca se detuvo porque el broadcast del botón no llega al servicio (ver A11). |
| E6 | **Falla.** Confirma R7. | Al reinstalar la app, el servidor se cerró y el cliente pasó de 0 % a 8,3 % de CPU, que equivale al 100 % de un núcleo de 12, en dos muestras de 5 s. No reconectó. El escenario se provocó reiniciando el servicio, no con el botón, por A11. |
| E7 | **No ejecutado.** | Exigía detener el servidor mientras el sistema estaba en uso. El defecto es evidente en el código (`main.rs:119`, `expect`). Se verificará en la aceptación de la Fase 1. |
| E8 | **Falla.** Confirma A2. | Tras terminar el proceso del cliente, `scrcpy.exe` (PID 12048) siguió en ejecución sin proceso padre. |
| E9 | **Falla.** Confirma A6. | El socket escucha en `::` (todas las interfaces). Se completó un handshake WebSocket desde la PC hacia `192.168.123.227:8080` por Wi-Fi, sin pasar por adb. |
| E10 | **No ejecutado.** | No había disponible una pista sin carátula. R6 se confirma por inspección del código (`main.rs:155` y `175-196`) y empeora por R3: como no llega ninguna carátula, Windows conserva la última recibida. |
| E11 | **Correcto.** A8 no reproducido. | Tras `adb install -r`, Android volvió a enlazar el servicio: aparece `SERVICIO ESPÍA INICIADO` y el puerto 8080 vuelve a estar en escucha 1,6 s después de la instalación. |

---

## 3. Estado de cada defecto

| Defecto | Estado | Comentario |
|---|---|---|
| R1 | Confirmado | E3 |
| R2 | Confirmado | E1 |
| R3 | Confirmado | En toda la sesión se enviaron 0 carátulas, con al menos tres cambios de pista, y se registraron 0 errores. Hipótesis: cuando se publica la notificación, el bitmap todavía no está en `MediaMetadata`, y `lastTrackKey` ya quedó marcado como enviado. En la Fase 3 se confirmará registrando qué claves de `MediaMetadata` (bitmap o URI) hay disponibles. |
| R4 | No reproducido | Se mantiene como riesgo con prioridad reducida. La política de selección de la Fase 3 lo cubre igualmente. |
| R5 | Confirmado parcialmente | Las posiciones de una misma ráfaga no son monótonas. Spotify sí informa la duración. |
| R6 | Confirmado por código | Ver E10. |
| R7 | Confirmado | E6 |
| A1 | Confirmado por código | Ver E7. |
| A2 | Confirmado | E8 |
| A4 | No reproducible | Está enmascarado por A11. Será verificable a partir de la Fase 5. |
| A6 | Confirmado | E9 |
| A8 | No reproducido | Android 16 volvió a enlazar el servicio tras la reinstalación. `requestRebind` se mantiene como medida defensiva. |

---

## 4. Hallazgos nuevos

### A11. El botón de la app no controla el servidor

- **Síntoma:** al pulsar el botón, la interfaz cambia a "PUENTE PAUSADO", pero el puerto 8080 sigue en escucha y el servicio no registra ningún mensaje de pausa.
- **Evidencia:** en `dumpsys activity broadcasts history`, el `BroadcastRecord` de `TOGGLE_BRIDGE` aparece con `terminalCount=0`, es decir, no se entregó a ningún receptor. Sin embargo, el receptor del servicio sí está registrado: `ReceiverList` del PID 23510 con la acción `TOGGLE_BRIDGE`.
- **Causa probable:** el broadcast es implícito (`Intent` sin `setPackage`) y va dirigido a un receptor registrado como `RECEIVER_NOT_EXPORTED`, con `targetSdk 36`.
- **Corrección:** la Fase 5 sustituye el broadcast por `BridgeRepository`, que comunica Activity y servicio dentro del mismo proceso. No se parchea en la línea base.

### A12. Ráfagas de mensajes duplicados

- **Síntoma:** cada evento produce entre 15 y 17 mensajes en menos de 400 ms, la mayoría idénticos.
- **Causa:** se emite un mensaje por cada `onNotificationPosted`, Spotify vuelve a publicar la notificación varias veces y no hay ningún filtro de duplicados.
- **Corrección:** en la Fase 3, `debounce` de 150 ms y `distinctUntilChanged` sobre `MediaSnapshot`.

### A13. Observación de datos: sufijo en el artista

En el modo aleatorio inteligente, Spotify añade "• Del Modo aleatorio inteligente" al campo `METADATA_KEY_ARTIST`. No es un defecto del puente. En la Fase 3 se decidirá si se normaliza.

---

## 5. Impacto en el plan

1. **Fase 3:** añadir un registro de diagnóstico de las claves disponibles en `MediaMetadata` para cerrar la hipótesis de R3. A12 queda cubierto con `debounce` y `distinctUntilChanged`.
2. **Fases 5 y 6:** A11 queda cubierto por `BridgeRepository`. E5 pasa a ser verificable desde la Fase 5.
3. **R4:** baja a prioridad baja; la política de selección se mantiene.
4. **E7 y E10:** se ejecutarán en la aceptación de las Fases 1 y 4 respectivamente.

---

## 6. Versiones de referencia

| Componente | Versión actual | Candidata | Observación |
|---|---|---|---|
| Android Gradle Plugin | 9.2.1 | | |
| Kotlin (incluido en AGP) | 2.2.10 | | Es la versión efectiva del compilador. |
| Gradle | 9.4.1 | | |
| JDK de compilación | 21.0.10 (JBR de Android Studio) | | |
| Rust | cargo 1.96.1 | | |
| Ktor | 2.3.11 (motor Netty) | 3.6.0 (motor CIO) | Compilado con Kotlin 2.3.21. Compatible con el compilador 2.2.10, que admite metadatos de una versión posterior. Se valida en la Fase 5. |
| kotlinx-serialization | No se usa | 1.12.0 | Compilado con Kotlin 2.4.10, **incompatible** con el compilador 2.2.10. Decisión en la Fase 2: subir el plugin de Kotlin a 2.4.x (opción preferida) o fijar la última versión de la librería compilada con Kotlin 2.2. |
| kotlinx-coroutines | Llega de forma transitiva vía Ktor | 1.11.0 | Compilado con Kotlin 2.2.20. Compatible. |
| lifecycle-runtime-ktx | No se usa | 2.11.0 | Última versión estable. |
| scrcpy | 4.0 | 4.0 | SHA-256 del paquete win64: `75dbeb5b00e6f64292f26f70900ae55ca397786bdfb0b9bbeb481a0549047457` |

---

## 7. Artefactos

- **Copia de la línea base:** `C:\Users\PC\Documents\Z - STUDIES\02_Rust\_snapshots\1_AudioBridge\00-baseline.zip`, con 143 entradas y sin artefactos de compilación.
- **Registro de `logcat` de la sesión:** `docs/evidencia/fase0-logcat.txt`.
