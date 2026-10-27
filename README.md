# SashaBridge

SashaBridge conecta un teléfono Android con una PC Windows por USB. Hace tres cosas:

- Envía el audio del teléfono a la PC, mediante scrcpy.
- Publica la canción que suena en el teléfono en el panel multimedia de Windows (SMTC): título, artista, carátula y estado de reproducción.
- Permite controlar el reproductor del teléfono con las teclas multimedia y el panel de Windows.

> Este documento cubre la preparación del entorno. La documentación de arquitectura y del protocolo se completa en la Fase 7 del plan de refactorización (`docs/`).

## Estructura

| Ruta | Contenido |
|---|---|
| `sashabridge-android/` | App Android (Kotlin): servicio de escucha de notificaciones y servidor WebSocket |
| `sashabridge-pc/` | Cliente Windows (Rust): túnel adb, scrcpy e integración con SMTC |
| `tools/` | Scripts de entorno |
| `docs/` | Documentación técnica, diagnósticos y material antiguo (`docs/legacy/`) |

## Requisitos

- Windows 10 1809 o posterior.
- Rust estable (`cargo`).
- Android Studio o, como mínimo, el JDK 21 que incluye (JBR) y el SDK de Android con API 36.
- Teléfono Android 10 (API 29) o posterior, con la depuración USB activada.

## Preparación

1. **Descargar scrcpy.** No se incluye en el control de versiones:

   ```powershell
   .\tools\fetch-scrcpy.ps1
   ```

   El script descarga la versión fijada desde las releases oficiales de `Genymobile/scrcpy`, comprueba su SHA-256 y la deja en `sashabridge-pc\vendor\scrcpy\`. Si los binarios ya están presentes, no hace nada; con `-Force` los reemplaza.

2. **Compilar el cliente de la PC:**

   ```powershell
   cd sashabridge-pc
   cargo build
   ```

3. **Compilar e instalar la app Android:**

   ```powershell
   cd sashabridge-android
   .\gradlew :app:installDebug
   ```

4. **Conceder el acceso a notificaciones** a SashaBridge, en *Ajustes > Notificaciones > Acceso a notificaciones*, o por adb:

   ```powershell
   adb shell cmd notification allow_listener io.github.elkelvinx.sashabridge/io.github.elkelvinx.sashabridge.MusicListenerService
   ```

5. **Excluir la app de la optimización de batería.** En Samsung: *Ajustes > Batería > Límites de uso en segundo plano*.

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
