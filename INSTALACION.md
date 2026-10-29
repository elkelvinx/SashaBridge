# Instalación y traslado a otra PC

Guía para llevar el proyecto a una PC nueva, subirlo a GitHub y dejarlo funcionando. Para el uso diario, ver `README.md`.

## 1. Qué guarda git y qué no

Git guarda solo lo que no se puede regenerar. Todo lo demás se excluye en `.gitignore` y se reconstruye con un comando (sección 3).

**Fuera de git:**

| Ruta | Qué es | Por qué queda fuera | Cómo se recupera |
|---|---|---|---|
| `sashabridge-pc/target/` | Compilación de Rust | Ocupa cientos de MB y se genera al compilar | `cargo build` |
| `sashabridge-android/build/`, `app/build/` | Compilación de Android (incluye el APK) | Igual que la anterior | `gradlew :app:assembleDebug` |
| `sashabridge-android/.gradle/`, `.kotlin/` | Cachés de Gradle y Kotlin | Datos temporales de esta máquina | Se recrean solas |
| `sashabridge-pc/vendor/` | Binarios de scrcpy (`scrcpy.exe`, `adb.exe` y DLL) | Son programas de terceros. El script los descarga de la release oficial y comprueba su huella SHA-256, así que no hace falta guardarlos | `tools/fetch-scrcpy.ps1` |
| `local.properties` | Ruta del SDK de Android en esta PC | Cambia en cada máquina | Android Studio o `tools/setup.ps1` lo crean |
| `.idea/`, `*.iml`, `.vscode/` | Estado del editor (pestañas abiertas, etc.) | Es preferencia personal | El editor lo recrea |
| `*.rar` | Respaldos comprimidos | Pesan cientos de MB y GitHub rechaza archivos de más de 100 MB | No se necesitan |

**Dentro de git, aunque parezcan generados:**

| Archivo | Por qué se guarda |
|---|---|
| `sashabridge-pc/Cargo.lock` | Fija la versión exacta de cada crate. Así la otra PC compila con las mismas versiones que esta. |
| `sashabridge-android/gradlew`, `gradlew.bat` y `gradle/wrapper/` | El *wrapper* descarga la versión de Gradle que necesita el proyecto. No hace falta instalar Gradle. |
| `sashabridge-android/gradle/libs.versions.toml` | Versiones de las librerías y plugins de Android. |

Para comprobar qué ignora git en tu carpeta: `git status --ignored`.

## 2. ¿Hay un `npm install`?

No: el proyecto no usa Node. Cada parte descarga sus dependencias de una forma distinta:

| Dependencia | Quién la descarga | Cuándo |
|---|---|---|
| Crates de Rust | `cargo`, según `Cargo.toml` y `Cargo.lock` | Automáticamente, en el primer `cargo build` |
| Gradle, compilador de Kotlin y librerías de Android | El wrapper de Gradle, según `libs.versions.toml` y `build.gradle.kts` | Automáticamente, en el primer `gradlew` |
| scrcpy | `tools/fetch-scrcpy.ps1` (no tiene gestor de paquetes) | Hay que ejecutarlo |

`tools/setup.ps1` hace las tres cosas con un solo comando, y es lo más parecido a `npm install` (sección 4.4).

## 3. Sacar el proyecto de esta PC

### 3.1 Confirmar que todo está guardado

```powershell
git status
```

Debe decir `nothing to commit, working tree clean`. Si hay cambios, guárdalos antes:

```powershell
git add -A
git commit -m "Describe aquí el cambio"
```

### 3.2 Opción recomendada: un bundle de git

Un *bundle* es un único archivo con todo el historial, las ramas y las etiquetas. Hoy ocupa unos 200 KB, frente a los cientos de MB de la carpeta completa, y no hay que decidir qué incluir: lleva exactamente lo que está en git.

```powershell
git bundle create ..\sashabridge.bundle --all
git bundle verify ..\sashabridge.bundle
```

Copia `sashabridge.bundle` a la otra PC (USB, correo o nube).

### 3.3 Alternativa: comprimir la carpeta

Si prefieres un `.rar`, incluye la carpeta `1_AudioBridge` **con la carpeta oculta `.git`**, que es la que contiene el historial, y excluye lo siguiente:

- `1_AudioBridge.rar` y cualquier otro `.rar`
- `sashabridge-pc\target\` y `sashabridge-pc\vendor\`
- `sashabridge-android\build\`, `sashabridge-android\app\build\`, `sashabridge-android\.gradle\`, `sashabridge-android\.kotlin\` y `sashabridge-android\.idea\`
- `sashabridge-android\local.properties`

Si se te olvida excluir algo no se rompe nada: git lo sigue ignorando. Solo ocupa más.

## 4. En la PC nueva

### 4.1 Instalar los requisitos

| Programa | Para qué | Notas |
|---|---|---|
| [Git for Windows](https://git-scm.com/download/win) | Control de versiones | Opciones por defecto |
| [Rust](https://rustup.rs) (`rustup-init.exe`) | Compilar el cliente de PC | Si lo pide, instala las *Visual Studio C++ Build Tools*: Rust las necesita en Windows para enlazar |
| [Android Studio](https://developer.android.com/studio) | JDK 21 y SDK de Android | Ábrelo una vez para que instale el SDK y, en *SDK Manager*, marca **Android API 37** |
| Driver USB de Samsung | Que `adb` vea el teléfono | Solo si `adb devices` no muestra el teléfono |

En el teléfono: *Opciones de desarrollador > Depuración USB* activada y, al conectarlo, aceptar la huella de la PC.

### 4.2 Recuperar el repositorio

**Desde el bundle:**

```powershell
cd C:\ruta\donde\quieras\el\proyecto
git clone C:\ruta\de\sashabridge.bundle 1_AudioBridge
cd 1_AudioBridge
```

**Desde el `.rar`:** descomprímelo y entra en la carpeta `1_AudioBridge`.

Después, configura tu identidad. La identidad de cada repositorio no viaja en el bundle:

```powershell
git config user.name "elkelvinx"
git config user.email "elkelvin484@gmail.com"
```

Sin `--global` solo afecta a este repositorio. En tu PC personal puedes añadir `--global` para que sea la identidad por defecto.

### 4.3 Subir a GitHub

1. En GitHub, crea un repositorio **vacío**, sin README, sin `.gitignore` y sin licencia. Si GitHub crea un primer commit, el historial ya no encaja.
2. Conecta el repositorio local y sube todo, incluidas las etiquetas de las fases:

   ```powershell
   # Si clonaste desde el bundle, "origin" ya existe y apunta al archivo: se cambia.
   git remote set-url origin https://github.com/elkelvinx/NOMBRE-DEL-REPO.git
   # Si descomprimiste el .rar, "origin" no existe todavía: se crea.
   # git remote add origin https://github.com/elkelvinx/NOMBRE-DEL-REPO.git

   git push -u origin main --tags
   ```

   La primera vez Git abre el navegador para iniciar sesión en GitHub.

### 4.4 Descargar las dependencias y compilar

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\setup.ps1
```

El script:

1. Comprueba que estén instalados git, cargo, el JDK y el SDK de Android.
2. Descarga scrcpy.
3. Compila el cliente de PC, con lo que cargo descarga los crates.
4. Crea `local.properties` si no existe y compila la app, con lo que Gradle descarga sus dependencias.

La primera vez tarda varios minutos. Si lo ejecutas otra vez, no vuelve a descargar lo que ya está.

`-ExecutionPolicy Bypass` permite ejecutar el script aunque Windows tenga bloqueados los scripts de PowerShell, que es la configuración por defecto, y solo para este comando.

### 4.5 Instalar la app en el teléfono

Con el teléfono conectado por USB:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\setup.ps1 -InstallApp
```

Instala el APK y concede el acceso a notificaciones. Después, en el teléfono:

- *Ajustes > Aplicaciones > SashaBridge > Batería*: **Sin restricciones**.
- *Ajustes > Batería > Límites de uso en segundo plano > Apps que nunca entran en suspensión*: añade **SashaBridge**.

Sin estos dos ajustes, Samsung congela la app en segundo plano y deja de enviar datos y de obedecer comandos hasta que algo la despierta. Mientras el primero falte, la app lo avisa en su pantalla con un botón que abre sus ajustes.

### 4.6 Ejecutar

```powershell
cd sashabridge-pc
cargo run
```

## 5. Git en el día a día

| Para | Comando |
|---|---|
| Ver qué cambió | `git status` y `git diff` |
| Guardar cambios | `git add -A` y después `git commit -m "mensaje"` |
| Subirlos a GitHub | `git push` |
| Marcar el cierre de una fase | `git tag -a fase-3 -m "Fase 3: ..."` y después `git push --tags` |
| Ver la historia | `git log --oneline --decorate` |
| Ver qué cambió en una fase | `git diff fase-1 fase-2 --stat` (o sin `--stat` para ver el detalle) |
| Ver un archivo como estaba en una fase | `git show fase-0:sashabridge-pc/src/main.rs` |
