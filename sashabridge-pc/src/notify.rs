//! Windows notifications ("toasts") of the PC client.
//!
//! A program without an installer shows toasts under an identity it registers for the
//! current user: the key `HKCU\Software\Classes\AppUserModelId\<id>`, with the name and icon
//! Windows shows on them. [`register`] writes it, with Sasha's icon, at every start, so it
//! follows the executable wherever it moves.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, bail};
use windows::Data::Xml::Dom::XmlDocument;
use windows::UI::Notifications::{ToastNotification, ToastNotificationManager};
use windows::core::HSTRING;
use windows_sys::Win32::Foundation::ERROR_SUCCESS;
use windows_sys::Win32::System::Registry::{
    HKEY, HKEY_CURRENT_USER, KEY_SET_VALUE, REG_OPTION_NON_VOLATILE, REG_SZ, RegCloseKey, RegCreateKeyExW,
    RegSetValueExW,
};

/// Identity of the client's notifications.
const APP_ID: &str = "io.github.elkelvinx.SashaBridge";

/// Name Windows shows on the notifications.
const DISPLAY_NAME: &str = "SashaBridge";

/// Sasha's icon, made by `tools/sasha_icon.py --pc-icon`.
const ICON: &[u8] = include_bytes!("../assets/sasha-icon.png");

/// Folder in `%LOCALAPPDATA%` that keeps the icon, which Windows reads from disk.
const DATA_FOLDER: &str = "SashaBridge";

/// Registers the notification identity for the current user, with Sasha's icon.
///
/// # Errors
///
/// Fails when the icon cannot be written or the registry key cannot be set; the client then
/// falls back to a sound.
pub fn register() -> anyhow::Result<()> {
    let folder = env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .context("LOCALAPPDATA is not set")?
        .join(DATA_FOLDER);
    fs::create_dir_all(&folder).with_context(|| format!("cannot create {}", folder.display()))?;
    let icon = folder.join("sasha-icon.png");
    fs::write(&icon, ICON).with_context(|| format!("cannot write {}", icon.display()))?;

    let key = RegistryKey::create(&format!(r"Software\Classes\AppUserModelId\{APP_ID}"))?;
    key.set("DisplayName", DISPLAY_NAME)?;
    key.set("IconUri", &icon.to_string_lossy())?;
    Ok(())
}

/// Announces an annotation that arrived from the phone, with a preview of it. Touching the
/// notification opens the image.
///
/// # Errors
///
/// Fails when Windows refuses the notification, for example if [`register`] failed.
pub fn annotation_ready(image: &Path) -> anyhow::Result<()> {
    let uri = file_uri(image);
    let xml = format!(
        concat!(
            r#"<toast activationType="protocol" launch="{uri}">"#,
            r#"<visual><binding template="ToastGeneric">"#,
            "<text>Anotación lista</text>",
            "<text>Ya está en el portapapeles: pégala con Ctrl+V.</text>",
            r#"<image placement="hero" src="{uri}"/>"#,
            "</binding></visual>",
            "</toast>",
        ),
        uri = escape(&uri),
    );
    show(&xml)
}

/// Shows a toast described by `xml`.
fn show(xml: &str) -> anyhow::Result<()> {
    let document = XmlDocument::new().context("cannot create the notification")?;
    document.LoadXml(&HSTRING::from(xml)).context("invalid notification")?;
    let toast = ToastNotification::CreateToastNotification(&document).context("cannot create the notification")?;
    ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(APP_ID))
        .and_then(|notifier| notifier.Show(&toast))
        .context("Windows refused the notification")
}

/// `file:///` URI of a local path, as toasts take them.
fn file_uri(path: &Path) -> String {
    format!("file:///{}", path.to_string_lossy().replace('\\', "/"))
}

/// Escapes text for an XML attribute or element.
fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

/// An open key of the current user's registry, closed when dropped.
struct RegistryKey(HKEY);

impl RegistryKey {
    /// Opens `path` under `HKEY_CURRENT_USER` for writing, creating it if needed.
    fn create(path: &str) -> anyhow::Result<Self> {
        let path = wide(path);
        let mut key: HKEY = std::ptr::null_mut();
        // SAFETY: the path is NUL-terminated and outlives the call; `key` receives the handle.
        let status = unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                path.as_ptr(),
                0,
                std::ptr::null(),
                REG_OPTION_NON_VOLATILE,
                KEY_SET_VALUE,
                std::ptr::null(),
                &mut key,
                std::ptr::null_mut(),
            )
        };
        if status != ERROR_SUCCESS {
            bail!("cannot create the notification registry key (error {status})");
        }
        Ok(Self(key))
    }

    /// Sets the text value `name` to `value`.
    fn set(&self, name: &str, value: &str) -> anyhow::Result<()> {
        let name_wide = wide(name);
        let data = wide(value);
        let bytes = u32::try_from(data.len() * 2).context("registry value too long")?;
        // SAFETY: the key is open, the name is NUL-terminated, and `data` is a NUL-terminated
        // UTF-16 string of `bytes` bytes, all alive during the call.
        let status = unsafe { RegSetValueExW(self.0, name_wide.as_ptr(), 0, REG_SZ, data.as_ptr().cast(), bytes) };
        if status != ERROR_SUCCESS {
            bail!("cannot set the registry value {name} (error {status})");
        }
        Ok(())
    }
}

impl Drop for RegistryKey {
    fn drop(&mut self) {
        // SAFETY: the key was opened by `create` and is closed once.
        unsafe { RegCloseKey(self.0) };
    }
}

/// Encodes `text` as a NUL-terminated UTF-16 string for the wide Win32 APIs.
fn wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(test)]
mod tests {
    //! Building the notification, and a manual check that Windows shows it.

    use super::*;

    /// Paths become `file:///` URIs with forward slashes.
    #[test]
    fn paths_become_file_uris() {
        assert_eq!(
            file_uri(Path::new(r"C:\Users\PC\Pictures\SashaBridge\anotacion.png")),
            "file:///C:/Users/PC/Pictures/SashaBridge/anotacion.png"
        );
    }

    /// Characters that XML reserves are escaped.
    #[test]
    fn xml_is_escaped() {
        assert_eq!(escape(r#"a&b<c>"d'"#), "a&amp;b&lt;c&gt;&quot;d&apos;");
    }

    /// Shows a real notification with Sasha's icon. Run with
    /// `cargo test notification_appears -- --ignored` and look at the screen.
    #[test]
    #[ignore = "shows a notification on the desktop"]
    fn notification_appears() {
        register().unwrap_or_else(|error| panic!("register: {error:#}"));
        let icon = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("assets").join("sasha-icon.png");
        annotation_ready(&icon).unwrap_or_else(|error| panic!("show: {error:#}"));
    }
}
