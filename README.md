# SashaBridge

SashaBridge conecta un teléfono Android con una PC Windows por USB. Hace tres cosas:

- Envía el audio del teléfono a la PC, mediante scrcpy.
- Publica la canción que suena en el teléfono en el panel multimedia de Windows (SMTC): título, artista, carátula y estado de reproducción.
- Permite controlar el reproductor del teléfono con las teclas multimedia y el panel de Windows.

> El protocolo entre el teléfono y la PC está especificado en `docs/protocolo-v2.md`. La documentación de arquitectura se completa en la Fase 7 del plan de refactorización.

## Estructura

| Ruta | Contenido |
|---|---|
| `sashabridge-android/` | App Android (Kotlin): servicio de escucha de notificaciones y servidor WebSocket |
| `sashabridge-pc/` | Cliente Windows (Rust): túnel adb, scrcpy e integración con SMTC |
| `protocol/fixtures/` | Mensajes de ejemplo del protocolo v2, compartidos por los tests de Kotlin y de Rust (ver `docs/protocolo-v2.md`) |
| `tools/` | Scripts de entorno: `setup.ps1` (preparación completa) y `fetch-scrcpy.ps1` |
| `INSTALACION.md` | Requisitos, preparación y traslado del proyecto a otra PC |
| `docs/` | Documentación técnica, diagnósticos y material antiguo (`docs/legacy/`) |

## Requisitos

- Windows 10 1809 o posterior.
- Rust estable (`cargo`).
- Android Studio o, como mínimo, el JDK 21 que incluye (JBR) y el SDK de Android con API 36.
- Teléfono Android 10 (API 29) o posterior, con la depuración USB activada.

## Preparación

Un solo comando descarga todas las dependencias (scrcpy, crates de Rust y librerías de Android), compila los dos proyectos e instala la app en el teléfono conectado:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\setup.ps1 -InstallApp
```

Sin `-InstallApp` no hace falta el teléfono. El proceso completo, incluidos los requisitos, los ajustes de batería del teléfono y cómo llevar el proyecto a otra PC y subirlo a GitHub, está en `INSTALACION.md`.

## Ejecución

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

## Problemas conocidos

| Síntoma | Causa | Solución |
|---|---|---|
| Un flyout de terceros (FluentFlyout, ModernFlyouts) no aparece al pulsar las teclas multimedia, aunque la música sí responde | La ventana activa se ejecuta como administrador. Windows impide que un programa normal vea las teclas pulsadas en ella | Abrir esa aplicación sin privilegios de administrador. El flyout nativo de Windows no tiene este problema |
| Las teclas multimedia de la PC controlan Spotify para PC en lugar del teléfono | Windows envía las teclas a la sesión multimedia principal, y Spotify para PC registra la suya | Cerrar Spotify en la PC |
| La app del teléfono deja de enviar datos o de obedecer comandos durante un rato | El ahorro de batería de Samsung congela la app en segundo plano | Batería *Sin restricciones* para SashaBridge (ver `INSTALACION.md`, sección 4.5) |
