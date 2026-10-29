# SashaBridge

SashaBridge conecta un teléfono Android con una PC Windows por USB. Hace estas cosas:

- Envía el audio del teléfono a la PC, mediante scrcpy.
- Publica la canción que suena en el teléfono en el panel multimedia de Windows (SMTC): título, artista, carátula y estado de reproducción.
- Permite controlar el reproductor del teléfono con las teclas multimedia y el panel de Windows.
- Con un teléfono con S Pen (como el Galaxy S22 Ultra):
  - **Anotar:** una captura de la PC se abre en el teléfono, se marca con el lápiz y vuelve al portapapeles de la PC.
  - **Tableta:** el teléfono funciona como una tableta de dibujo con presión para Windows.

La app se llama así por Sasha, una perrita salchicha: aparece en la pantalla del teléfono y muestra el estado del puente.

## Documentación

| Documento | Contenido |
|---|---|
| `INSTALACION.md` | Requisitos, preparación y traslado del proyecto a otra PC |
| `docs/arquitectura.md` | Piezas de cada lado, hilos, flujos y decisiones de diseño |
| `docs/protocolo-v2.md` | Contrato entre el teléfono y la PC |
| `docs/verificacion.md` | Pruebas automáticas y manuales, escenarios de aceptación y resultado de la última regresión |
| `docs/diagnostico-fase0.md` | Diagnóstico de la versión original, con la evidencia de cada defecto |

## Estructura

| Ruta | Contenido |
|---|---|
| `sashabridge-android/` | App Android (Kotlin): servicio de escucha de notificaciones, servidor WebSocket y las pantallas del S Pen |
| `sashabridge-pc/` | Cliente Windows (Rust): túnel adb, scrcpy, integración con SMTC, portapapeles, lápiz sintético y notificaciones. En `assets/`, el icono de Sasha de las notificaciones |
| `protocol/fixtures/` | Mensajes de ejemplo del protocolo v2, compartidos por los tests de Kotlin y de Rust (ver `docs/protocolo-v2.md`) |
| `tools/` | `setup.ps1` (preparación completa), `fetch-scrcpy.ps1`, `smtc-inspect.ps1` (inspección de las sesiones multimedia de Windows), `sasha_sprite.py` (dibujos de Sasha en la app) y `sasha_icon.py` (icono de la app y, con `--pc-icon`, el de las notificaciones de la PC) |
| `docs/` | Documentación técnica (ver la tabla anterior) y material de la versión original (`docs/legacy/`) |

## Requisitos

- Windows 10 1809 o posterior.
- Rust estable (`cargo`).
- Android Studio o, como mínimo, el JDK 21 que incluye (JBR) y el SDK de Android con API 37.
- Teléfono Android 10 (API 29) o posterior, con la depuración USB activada.

## Preparación

Un solo comando descarga todas las dependencias (scrcpy, crates de Rust y librerías de Android), compila los dos proyectos e instala la app en el teléfono conectado:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\setup.ps1 -InstallApp
```

Sin `-InstallApp` no hace falta el teléfono. El proceso completo, incluidos los requisitos, los ajustes de batería del teléfono y cómo llevar el proyecto a otra PC y subirlo a GitHub, está en `INSTALACION.md`.

## Uso

En el teléfono, la app SashaBridge muestra si el puente está encendido, si hay una PC conectada y qué canción suena, con controles para pasar de canción, pausar, cambiar los modos aleatorio y repetir, mover la posición y ver la cola de la app que suena. En horizontal muestra el estado y el reproductor en dos columnas. Su botón principal, en vertical, enciende o apaga el puente. Si falta el acceso a notificaciones, o si Android puede congelar la app en segundo plano, la pantalla lo indica con un acceso directo al ajuste correspondiente.

En la PC:

```powershell
cd sashabridge-pc
cargo run
```

- **Reconexión:** el cliente se conecta solo cuando el teléfono está disponible. Si se desconecta el USB o se reinicia la app, reintenta cada 1, 2, 4 y después 5 segundos, y vuelve a lanzar scrcpy cuando hace falta.
- **Cierre:** con `Ctrl+C`, o cerrando la ventana. En cualquier caso Windows termina scrcpy junto con el cliente.
- **Una sola instancia:** si ya hay un cliente abierto, el segundo termina con un aviso.
- **scrcpy:** el cliente lo busca, en este orden, en la carpeta de la variable de entorno `SASHABRIDGE_SCRCPY_DIR`, en una carpeta `scrcpy\` junto al ejecutable y, en compilaciones de desarrollo, en `sashabridge-pc\vendor\scrcpy\`. No depende del directorio desde el que se ejecute.
- **Registro:** cada línea indica el tiempo transcurrido desde el arranque. Para ver el detalle de cada paso:

  ```powershell
  $env:RUST_LOG = "debug"; cargo run
  ```

- **Carátulas:** se guardan en `%TEMP%\sashabridge\covers\`. Solo se conserva la actual y la carpeta se vacía en cada arranque.
- **Notificaciones:** al arrancar, el cliente registra su nombre e icono para las notificaciones de Windows en `HKCU\Software\Classes\AppUserModelId\io.github.elkelvinx.SashaBridge`, con el icono en `%LOCALAPPDATA%\SashaBridge\`. Si Windows las rechaza, el aviso es un sonido.

### Anotar

1. En el teléfono, pulsa **Anotar**. La pantalla queda esperando una captura.
2. En la PC, recorta con `Win+Shift+S`. La captura aparece en el teléfono. Con `Ctrl+Alt+A` se abre el recorte y la captura se envía aunque la pantalla Anotar esté cerrada; entonces llega una notificación al teléfono.
3. Marca la captura con el S Pen:
   - lápiz con presión, resaltador, línea recta, rectángulo y flecha, en 5 colores y 3 grosores;
   - texto, en 4 tamaños; tocar una nota existente con la herramienta Texto la edita;
   - **Mover** arrastra cualquier marca;
   - el botón lateral, o el extremo borrador, borra marcas enteras;
   - con dos dedos se hace zoom y desplazamiento; doble toque vuelve a ajustar la captura.
4. Pulsa **"+" → Enviar a la PC**. La imagen anotada queda en el portapapeles (`Ctrl+V` en Paint, Teams, correo...), se guarda en `Imágenes\SashaBridge\` y aparece una notificación; al tocarla se abre la imagen.

### Tableta

1. En el teléfono, pulsa **Tableta**. La pantalla se pone en horizontal con un recuadro de la forma del monitor.
2. Dibuja en el recuadro mirando el monitor:
   - acercar el lápiz sin tocar mueve el cursor;
   - la punta dibuja con presión;
   - el botón lateral con un toque es un clic derecho;
   - el extremo borrador borra en las apps que lo admiten;
   - los dedos no hacen nada.
3. El botón del monitor (columna izquierda) pasa al siguiente monitor, si hay varios. Debajo se ve la latencia por USB.

Funciona en las apps que aceptan un lápiz Windows Ink: Paint, OneNote, Krita (con la entrada de tableta en *Windows Ink*) o Affinity (con *Windows Ink* activado en sus preferencias). Windows Ink es la plataforma de lápiz de Windows: las apps que la usan reciben la presión, la inclinación y los botones del lápiz. Algunas apps de dibujo solo aceptan WinTab, el sistema de los controladores de Wacom, y esas no ven la Tableta.

## Problemas conocidos

| Síntoma | Causa | Solución |
|---|---|---|
| Un flyout de terceros (FluentFlyout, ModernFlyouts) no aparece al pulsar las teclas multimedia, aunque la música sí responde | La ventana activa se ejecuta como administrador. Windows impide que un programa normal vea las teclas pulsadas en ella | Abrir esa aplicación sin privilegios de administrador. El flyout nativo de Windows no tiene este problema |
| Las teclas multimedia de la PC controlan otra app de música (Spotify para PC, YouTube Music en el navegador) en lugar del teléfono | Windows envía las teclas a una sola sesión multimedia, normalmente la que empezó a sonar más recientemente. Al pausar esa sesión, la siguiente pulsación va a otra que siga sonando | Comportamiento de Windows. Pausar o cerrar la otra app |
| El flyout no muestra el botón de modo aleatorio | SashaBridge publica el modo aleatorio en la sesión de Windows, pero FluentFlyout no ofrece ese botón (tampoco para otras apps) y el flyout nativo de Windows tampoco | Ninguna por ahora: depende del flyout |
| La app del teléfono deja de enviar datos o de obedecer comandos durante un rato | El ahorro de batería de Samsung congela la app en segundo plano | Batería *Sin restricciones* para SashaBridge (ver `INSTALACION.md`, sección 4.5) |
| Una app de dibujo no reacciona a la Tableta | La app solo acepta tabletas WinTab, o no tiene activada la entrada Windows Ink | Activar *Windows Ink* en las preferencias de la app, si lo ofrece |
| Excalidraw, en el navegador, no responde a la Tableta | Por diagnosticar (en Paint funciona) | Pendiente |
| El gesto "atrás" se activa al dibujar pegado al borde | Navegación por gestos de Android | SashaBridge aparta el gesto donde está el lápiz; como respaldo, One UI ofrece *Bloquear gestos con S Pen* en Ajustes > Pantalla > Barra de navegación |
