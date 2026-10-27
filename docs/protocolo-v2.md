# Protocolo SashaBridge v2

Contrato entre la app Android (servidor) y el cliente de Windows. Este documento es la referencia. Los fixtures de `protocol/fixtures/` son su versión ejecutable: los tests de Kotlin y de Rust los leen de esa misma carpeta, así que una diferencia entre los dos lados hace fallar los tests.

## 1. Transporte

- **Conexión:** WebSocket en `ws://127.0.0.1:8080/`. En la PC esa dirección es el extremo local del túnel `adb forward tcp:8080 tcp:8080`, que llega al teléfono por USB.
- **Mensajes:** cada mensaje viaja en un frame de texto, codificado en UTF-8, con un único objeto JSON. No se usan frames binarios.
- **Sesión:** una conexión es una sesión. Lo que el teléfono haya enviado a una sesión anterior no cuenta para la siguiente.

## 2. Formato común

| Regla | Detalle |
|---|---|
| Campo `type` | Obligatorio en todos los mensajes. Su valor identifica el mensaje: `hello`, `media.state`, `cmd`, etc. |
| Nombres de campo | En camelCase. |
| Tiempos | Enteros en milisegundos. El nombre del campo termina en `Ms`. |
| Campos anulables | El emisor los envía **siempre**, con `null` cuando el valor no se conoce o no aplica. El receptor trata un campo anulable ausente como `null`. |
| Campos desconocidos | El receptor los ignora. Así se pueden añadir campos opcionales sin cambiar de versión. |
| Mensaje inválido o `type` desconocido | El receptor lo registra en el log y lo descarta. La sesión continúa. |

Un mensaje es inválido si le falta un campo obligatorio, si un campo tiene un tipo distinto del especificado o si viola una de las restricciones de las tablas siguientes.

## 3. Mensajes del teléfono a la PC

### `hello`

Primer mensaje de cada sesión.

| Campo | Tipo | Anulable | Restricciones |
|---|---|---|---|
| `protocol` | entero | No | Versión del protocolo del emisor, mayor o igual que 1. Hoy, `2`. |
| `device` | texto | No | Modelo del teléfono (`Build.MODEL`). Solo informativo. |

```json
{"type":"hello","protocol":2,"device":"SM-S908U1"}
```

### `media.state`

Estado completo del reproductor activo. Cada mensaje sustituye al anterior: no hay actualizaciones parciales.

| Campo | Tipo | Anulable | Restricciones |
|---|---|---|---|
| `trackId` | texto | No | Identificador opaco de la pista. Cambia cuando cambia la pista. |
| `source` | texto | No | Paquete Android de la app que reproduce (`com.spotify.music`). |
| `title` | texto | Sí | |
| `artist` | texto | Sí | |
| `album` | texto | Sí | |
| `durationMs` | entero | Sí | Mayor que 0. `null` si la app no informa la duración. |
| `positionMs` | entero | No | Mayor o igual que 0. Posición en el momento de enviar el mensaje, ya extrapolada desde la última actualización del reproductor. |
| `isPlaying` | booleano | No | `true` solo en reproducción. |
| `playbackSpeed` | decimal | No | Velocidad de reproducción. `1.0` es la normal y `0.0` corresponde a una pausa. |
| `artId` | texto | Sí | Carátula de la pista (ver `media.art`). `null` significa que la pista no tiene carátula. |

```json
{"type":"media.state","trackId":"8f14e45f","source":"com.spotify.music","title":"Sample Track","artist":"Sample Artist","album":"Sample Album","durationMs":215000,"positionMs":12345,"isPlaying":true,"playbackSpeed":1.0,"artId":"a1b2c3d4"}
```

### `media.art`

Imagen de una carátula.

| Campo | Tipo | Anulable | Restricciones |
|---|---|---|---|
| `artId` | texto | No | Identificador opaco de la imagen. Dos `media.art` con el mismo `artId` contienen los mismos bytes. |
| `mime` | texto | No | Tipo de la imagen. Hoy siempre `image/jpeg`. |
| `data` | texto | No | Bytes de la imagen en Base64 estándar (RFC 4648), sin saltos de línea. |

```json
{"type":"media.art","artId":"a1b2c3d4","mime":"image/jpeg","data":"/9j/4AAQSkZJRg=="}
```

### `media.cleared`

No hay ningún reproductor activo. La PC debe vaciar el panel multimedia.

```json
{"type":"media.cleared"}
```

## 4. Mensajes de la PC al teléfono

### `hello`

Primer mensaje de cada sesión.

| Campo | Tipo | Anulable | Restricciones |
|---|---|---|---|
| `protocol` | entero | No | Versión del protocolo del emisor, mayor o igual que 1. Hoy, `2`. |
| `client` | texto | No | Identificación del cliente, en el formato `nombre/versión` (`sashabridge-pc/0.1.0`). Solo informativo. |

### `cmd`

Orden para el reproductor activo.

| Campo | Tipo | Anulable | Restricciones |
|---|---|---|---|
| `action` | texto | No | `PLAY`, `PAUSE`, `TOGGLE`, `NEXT`, `PREVIOUS` o `SEEK`. |
| `positionMs` | entero | Sí | Obligatorio y mayor o igual que 0 con `SEEK`. Con las demás acciones debe valer `null`. |

`PLAY` y `PAUSE` son órdenes explícitas: repetir `PAUSE` sobre un reproductor ya pausado no lo reanuda. `TOGGLE` existe para las teclas de reproducir/pausar que no indican el estado deseado.

```json
{"type":"cmd","action":"PAUSE","positionMs":null}
{"type":"cmd","action":"SEEK","positionMs":60000}
```

## 5. Reglas de orden

1. **Saludo.** Al abrir la sesión, cada extremo envía su `hello` antes que cualquier otro mensaje.
2. **Estado inicial.** Tras su `hello`, el teléfono envía el estado actual sin esperar a que cambie nada: `media.art` (si hay carátula) seguido de `media.state`, o bien `media.cleared`.
3. **Carátula antes del estado.** Un `media.art` se envía siempre antes del primer `media.state` que hace referencia a su `artId`.
4. **Una carátula por sesión.** El teléfono envía cada `artId` una sola vez por sesión. Los siguientes `media.state` de la misma pista solo lo citan.
5. **Uso de la carátula en la PC.** La PC muestra la carátula solo si el `artId` del `media.state` coincide con el de una imagen recibida. Si `artId` es `null`, quita la carátula. Así un estado nunca se muestra con la imagen de otra pista.

## 6. Versiones

- La versión solo aumenta con un cambio incompatible, como quitar un campo, cambiar su tipo o cambiar su significado.
- Añadir un campo anulable o un tipo de mensaje nuevo no cambia la versión, porque los receptores ignoran lo que no conocen. Las funciones futuras, como el S Pen, usarán tipos con prefijo propio (`pen.*`).
- Si un extremo anuncia una versión mayor que la propia, el receptor lo registra en el log y sigue procesando los mensajes que conoce.

## 7. Convivencia con v1

La versión 1 es el formato que usa hoy la app. No tiene campo `type`:

- **Teléfono a PC:** un objeto JSON con `title`, `artist`, `duration`, `isPlaying`, `position` y, cuando cambia la pista, `albumArt` en Base64. Los tiempos van en milisegundos, y la app envía `0` cuando no conoce la duración y `-1` cuando no conoce la posición.
- **PC a teléfono:** texto plano: `PLAY_PAUSE`, `NEXT`, `PREV` o `SEEK:<ms>`.

**Cómo se distinguen:**
- **En la PC:** un objeto JSON con `type` es v2; sin `type` es v1.
- **En el teléfono:** un frame que no es JSON se interpreta como comando v1, con `LegacyCommandParser`.
- **Formato de los comandos:** la PC solo envía comandos v2 después de recibir el `hello` del teléfono. Hasta entonces usa el texto de v1, que los dos servidores entienden.

**Calendario de la transición:**

| Al cerrar la fase | El teléfono envía | La PC acepta | Comandos de la PC |
|---|---|---|---|
| 2 (contrato) | v1 | v1 | v1 |
| 4 (cliente v2) | v1 | v1 y v2 | v1, o v2 si recibe `hello` |
| 5 (servidor v2) | v2, y acepta comandos v1 | v1 y v2 | v2 |
| 7 (cierre) | v2 | v2 | v2 |

En la Fase 7 se retira todo el código de v1 de los dos lados.

## 8. Fixtures

```
protocol/fixtures/
├── phone-to-pc/
│   ├── valid/       Mensajes v2 del teléfono que deben aceptarse
│   ├── invalid/     Mensajes que deben rechazarse
│   └── legacy-v1/   Mensajes v1 que la PC acepta durante la transición
└── pc-to-phone/
    ├── valid/
    └── invalid/
```

**Qué comprueban los tests**, en Kotlin (`./gradlew :app:testDebugUnitTest`) y en Rust (`cargo test`), con todos los archivos de cada carpeta:

- **`valid/`:** el mensaje se decodifica; al volver a codificarlo da el mismo JSON (sin contar el orden de los campos); y al decodificarlo otra vez da el mismo objeto.
- **`invalid/`:** la decodificación falla.
- **`legacy-v1/`** (solo Rust): el mensaje se reconoce como v1.

Los tests recorren las carpetas al ejecutarse, de modo que un fixture nuevo queda cubierto en los dos lenguajes sin tocar el código de los tests. Una carpeta vacía hace fallar el test.

**Regla de mantenimiento:** cualquier cambio en el protocolo empieza por este documento y por los fixtures, y después se adapta el código hasta que los tests de los dos lados pasen.
