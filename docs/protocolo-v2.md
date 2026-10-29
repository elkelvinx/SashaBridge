# Protocolo SashaBridge v2

Contrato entre la app Android (servidor) y el cliente de Windows. Este documento es la referencia. Los fixtures de `protocol/fixtures/` son su versión ejecutable: los tests de Kotlin y de Rust los leen de esa misma carpeta, así que una diferencia entre los dos lados hace fallar los tests.

## 1. Transporte

- **Conexión:** WebSocket en `ws://127.0.0.1:8080/`. En la PC esa dirección es el extremo local del túnel `adb forward tcp:8080 tcp:8080`, que llega al teléfono por USB.
- **Rutas:** `/` lleva la música (secciones 3 a 5). `/pen` lleva el S Pen (sección 9), en una conexión aparte para que una captura o una carátula grande no retrase los trazos.
- **Mensajes:** cada mensaje viaja en un frame de texto, codificado en UTF-8, con un único objeto JSON. La única excepción son las muestras del lápiz de la ruta `/pen`, que van en frames binarios.
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
| `artist` | texto | Sí | Solo el artista, sin el contexto de reproducción. |
| `context` | texto | Sí | Contexto que el reproductor añade al artista, como "Del Modo aleatorio inteligente" en Spotify. Depende del idioma del teléfono, así que solo se muestra, no se interpreta. |
| `album` | texto | Sí | |
| `durationMs` | entero | Sí | Mayor que 0. `null` si la app no informa la duración. |
| `positionMs` | entero | No | Mayor o igual que 0. Posición en el momento de enviar el mensaje, ya extrapolada desde la última actualización del reproductor. |
| `isPlaying` | booleano | No | `true` solo en reproducción. |
| `playbackSpeed` | decimal | No | Velocidad de reproducción. `1.0` es la normal y `0.0` corresponde a una pausa. |
| `shuffle` | texto | Sí | Modo aleatorio: `OFF`, `ON` o `SMART` (aleatorio inteligente de Spotify). `null` si el puente no puede controlar el modo aleatorio de ese reproductor. |
| `artId` | texto | Sí | Carátula de la pista (ver `media.art`). `null` significa que la pista no tiene carátula. |

```json
{"type":"media.state","trackId":"8f14e45f","source":"com.spotify.music","title":"Sample Track","artist":"Sample Artist","context":"Del Modo aleatorio inteligente","album":"Sample Album","durationMs":215000,"positionMs":12345,"isPlaying":true,"playbackSpeed":1.0,"shuffle":"SMART","artId":"a1b2c3d4"}
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
| `client` | texto | No | Identificación del cliente, en el formato `nombre/versión` (`sashabridge-pc/2.0.0`). Solo informativo. |

### `cmd`

Orden para el reproductor activo.

| Campo | Tipo | Anulable | Restricciones |
|---|---|---|---|
| `action` | texto | No | `PLAY`, `PAUSE`, `TOGGLE`, `NEXT`, `PREVIOUS`, `SEEK` o `TOGGLE_SHUFFLE`. |
| `positionMs` | entero | Sí | Obligatorio y mayor o igual que 0 con `SEEK`. Con las demás acciones debe valer `null`. |

`PLAY` y `PAUSE` son órdenes explícitas: repetir `PAUSE` sobre un reproductor ya pausado no lo reanuda. `TOGGLE` existe para las teclas de reproducir/pausar que no indican el estado deseado.

`TOGGLE_SHUFFLE` equivale a pulsar el botón de aleatorio del propio reproductor, que pasa al modo siguiente. En Spotify el ciclo es `OFF` → `ON` → `SMART` → `OFF`. Solo tiene efecto si el último `media.state` trae `shuffle` distinto de `null`; el nuevo modo llega en el siguiente `media.state`.

```json
{"type":"cmd","action":"PAUSE","positionMs":null}
{"type":"cmd","action":"SEEK","positionMs":60000}
```

## 5. Reglas de orden

1. **Saludo.** Al abrir la sesión, cada extremo envía su `hello` antes que cualquier otro mensaje.
2. **Estado inicial.** Tras su `hello`, el teléfono envía el estado actual sin esperar a que cambie nada: `media.art` (si hay carátula) seguido de `media.state`, o bien `media.cleared`.
3. **Carátula antes del estado.** Un `media.art` se envía siempre antes del primer `media.state` que hace referencia a su `artId`.
4. **Solo carátulas nuevas.** El teléfono envía un `media.art` cuando el `artId` del estado es distinto del último que envió en la sesión. Los siguientes `media.state` con el mismo `artId` solo lo citan. La PC guarda únicamente la última carátula recibida, así que al volver a una pista anterior el teléfono envía su carátula otra vez.
5. **Uso de la carátula en la PC.** La PC muestra la carátula solo si el `artId` del `media.state` coincide con el de una imagen recibida. Si `artId` es `null`, quita la carátula. Así un estado nunca se muestra con la imagen de otra pista.

## 6. Versiones

- La versión solo aumenta con un cambio incompatible, como quitar un campo, cambiar su tipo o cambiar su significado.
- Añadir un campo anulable o un tipo de mensaje nuevo no cambia la versión, porque los receptores ignoran lo que no conocen. Las funciones futuras, como el S Pen, usarán tipos con prefijo propio (`pen.*`).
- Si un extremo anuncia una versión mayor que la propia, el receptor lo registra en el log y sigue procesando los mensajes que conoce.

## 7. Historia

La versión 1 era el formato de la app original: un objeto JSON sin campo `type` con el estado del reproductor, y órdenes en texto plano (`PLAY_PAUSE`, `NEXT`, `PREV` y `SEEK:<ms>`). Las dos versiones convivieron durante la refactorización, de la Fase 2 a la Fase 6, para poder actualizar el teléfono y la PC por separado. Desde la versión 2.0 de SashaBridge ningún lado la entiende: un mensaje v1 es un mensaje inválido y se descarta.

## 8. Fixtures

```
protocol/fixtures/
├── phone-to-pc/
│   ├── valid/       Mensajes v2 del teléfono que deben aceptarse
│   └── invalid/     Mensajes que deben rechazarse
├── pc-to-phone/
│   ├── valid/
│   └── invalid/
└── pen-samples/
    ├── samples.bin  Frame binario de muestras del lápiz
    └── samples.json Las mismas muestras, decodificadas
```

**Qué comprueban los tests**, en Kotlin (`./gradlew :app:testDebugUnitTest`) y en Rust (`cargo test`), con todos los archivos de cada carpeta:

- **`valid/`:** el mensaje se decodifica; al volver a codificarlo da el mismo JSON (sin contar el orden de los campos); y al decodificarlo otra vez da el mismo objeto.
- **`invalid/`:** la decodificación falla.
- **`pen-samples/`:** decodificar `samples.bin` da exactamente las muestras de `samples.json` en los dos lenguajes, y el codificador del teléfono produce esos mismos bytes. Los dos archivos se generan con `tools/pen_fixtures.py`.

Los tests recorren las carpetas al ejecutarse, de modo que un fixture nuevo queda cubierto en los dos lenguajes sin tocar el código de los tests. Una carpeta vacía hace fallar el test.

**Regla de mantenimiento:** cualquier cambio en el protocolo empieza por este documento y por los fixtures, y después se adapta el código hasta que los tests de los dos lados pasen.

## 9. Ruta `/pen`: el S Pen

Conexión aparte, en `ws://127.0.0.1:8080/pen`, para las funciones del S Pen. Empieza igual que la de música: cada extremo envía su `hello` antes que cualquier otro mensaje. Los tipos son nuevos, así que no cambian la versión del protocolo (sección 6).

### Anotar

La PC envía capturas de pantalla al teléfono; el usuario las anota con el S Pen y el teléfono devuelve la imagen anotada.

| Mensaje | Sentido | Campos |
|---|---|---|
| `annotate.status` | Teléfono → PC | `armed` (booleano): la pantalla "Anotar" está abierta y esperando capturas. |
| `annotate.request` | PC → teléfono | `requestId` (texto no vacío), `mime` (hoy `image/png`), `width` y `height` (enteros mayores que 0) y `data` (la imagen en Base64). |
| `annotate.result` | Teléfono → PC | Los mismos campos que `annotate.request`, con el `requestId` de la captura de origen y la imagen ya anotada. |
| `annotate.cancelled` | Teléfono → PC | `requestId` de la captura que el usuario descartó. |

```json
{"type":"annotate.status","armed":true}
{"type":"annotate.request","requestId":"c4f1a2","mime":"image/png","width":1280,"height":720,"data":"iVBORw0KGgo="}
{"type":"annotate.result","requestId":"c4f1a2","mime":"image/png","width":1280,"height":720,"data":"iVBORw0KGgo="}
{"type":"annotate.cancelled","requestId":"c4f1a2"}
```

**Reglas:**
1. Tras su `hello`, el teléfono envía `annotate.status`, y lo vuelve a enviar cada vez que la pantalla "Anotar" se abre o se cierra.
2. Las imágenes que el usuario copia en la PC (por ejemplo, con `Win+Shift+S`) solo se envían mientras `armed` es `true`. Una captura pedida expresamente con el atajo de SashaBridge se envía siempre; si la pantalla no está abierta, el teléfono la ofrece en una notificación, porque Android no deja abrir pantallas desde segundo plano.
3. Cada `annotate.request` termina con un `annotate.result` o un `annotate.cancelled` con su mismo `requestId`, salvo que la sesión se cierre antes.

### Tableta

El teléfono funciona como una tableta de dibujo: el S Pen mueve un lápiz sintético de Windows sobre uno de los monitores de la PC.

| Mensaje | Sentido | Campos |
|---|---|---|
| `tablet.status` | Teléfono → PC | `active` (booleano): la pantalla "Tableta" está abierta. Al pasar a `false`, la PC levanta su lápiz. |
| `tablet.target` | PC → teléfono | `monitor` (índice desde 0, menor que `count`), `count` (número de monitores, mayor que 0), `name` (el nombre que Windows da al monitor, solo para mostrarlo), y `width` y `height` (píxeles, mayores que 0). |
| `tablet.select` | Teléfono → PC | `monitor` (índice desde 0): el usuario pide dibujar en ese monitor. La PC responde con un nuevo `tablet.target`; si el índice no existe, vuelve a empezar desde el primero. |
| `tablet.ping` | Teléfono → PC | `timeMs` (entero de 0 a 2^32 - 1): tiempo de actividad del teléfono, con el mismo reloj que las muestras del lápiz. |
| `tablet.pong` | PC → teléfono | `timeMs` del `tablet.ping` al que responde, enviado en cuanto llega. |

```json
{"type":"tablet.status","active":true}
{"type":"tablet.target","monitor":0,"count":2,"name":"DISPLAY1","width":1920,"height":1080}
{"type":"tablet.select","monitor":1}
{"type":"tablet.ping","timeMs":4294967295}
{"type":"tablet.pong","timeMs":123456}
```

**Reglas:**
1. Tras su `hello`, el teléfono envía `tablet.status` y lo repite cada vez que la pantalla "Tableta" se abre o se cierra.
2. Tras su `hello`, la PC envía `tablet.target` con el monitor actual, y lo repite cada vez que el monitor cambia. El teléfono le da a su área de dibujo la misma proporción que ese monitor, para que un círculo no salga ovalado.
3. Las coordenadas de las muestras van de una esquina a otra del área de dibujo, que corresponde al monitor entero.
4. La latencia que muestra el teléfono es la mitad del tiempo entre un `tablet.ping` y su `tablet.pong`.

### Muestras del lápiz

En el modo Tableta, el teléfono envía las lecturas del S Pen en **frames binarios**. A la frecuencia del lápiz (unas 240 lecturas por segundo), JSON costaría varias veces más bytes. Todos los enteros son little-endian.

**Cabecera (4 bytes):**

| Bytes | Campo | Valor |
|---|---|---|
| 0 | Tipo de frame | `0x50` ("P"): muestras del lápiz |
| 1 | Versión del formato | `1` |
| 2-3 | Número de muestras | Entero sin signo de 16 bits |

**Cada muestra (14 bytes):**

| Bytes | Campo | Tipo | Significado |
|---|---|---|---|
| 0-3 | `timeMs` | u32 | Tiempo de actividad del teléfono en ms; da la vuelta en 2^32 y solo importan las diferencias |
| 4-5 | `x` | u16 | Posición horizontal sobre la superficie de dibujo: 0 a la izquierda, 65535 a la derecha |
| 6-7 | `y` | u16 | Posición vertical: 0 arriba, 65535 abajo |
| 8-9 | `pressure` | u16 | Presión: 0 sin presión, 65535 la máxima |
| 10 | `tiltX` | i8 | Inclinación hacia la derecha, en grados de -90 a 90 |
| 11 | `tiltY` | i8 | Inclinación hacia el usuario, en grados de -90 a 90 |
| 12 | Estado | bits | 1: la punta toca la pantalla; 2: el lápiz está al alcance (flotando o tocando); 4: botón lateral pulsado; 8: borrador |
| 13 | Reservado | u8 | `0` |

Un frame cuya longitud no sea `4 + 14 × número de muestras`, o de otro tipo o versión, es inválido y se descarta. Un frame sin muestras es válido.
