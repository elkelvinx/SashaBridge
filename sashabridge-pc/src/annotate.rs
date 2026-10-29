//! S Pen "Anotar" mode: captures go to the phone, annotations come back.
//!
//! The `/pen` session ([`crate::pen_route`]) sends the images the user copies while the
//! phone's annotation screen waits, and the one asked for with Ctrl+Alt+A always
//! ([`crate::hidden_window::WindowEvent::ImageCopied`]), encoded here. Each annotation it
//! receives goes to the clipboard and to `Pictures\SashaBridge`.

use std::fs;
use std::io;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use anyhow::Context;
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use tokio::task;
use tracing::{info, warn};
use windows_sys::Win32::System::Com::CoTaskMemFree;
use windows_sys::Win32::System::Diagnostics::Debug::MessageBeep;
use windows_sys::Win32::System::SystemInformation::GetLocalTime;
use windows_sys::Win32::UI::Shell::{FOLDERID_Pictures, SHGetKnownFolderPath};
use windows_sys::Win32::UI::WindowsAndMessaging::MB_OK;

use crate::bitmap::{self, Rgba};
use crate::clipboard;
use crate::hidden_window::ClipboardOwner;
use crate::protocol::v2::{AnnotateImage, PcMessage, RequestId};

/// Folder inside Pictures where annotations are saved.
const SAVE_FOLDER: &str = "SashaBridge";

/// Media type of captures and annotations.
const PNG_MIME: &str = "image/png";

/// Encodes a capture as an `annotate.request`, off the async threads.
pub async fn capture_request(image: Rgba) -> anyhow::Result<String> {
    task::spawn_blocking(move || {
        let png = bitmap::encode_png(&image)?;
        let request = PcMessage::AnnotateRequest(AnnotateImage {
            request_id: next_request_id(),
            mime: PNG_MIME.to_owned(),
            width: image.width.try_into().context("empty capture")?,
            height: image.height.try_into().context("empty capture")?,
            data: STANDARD.encode(&png),
        });
        info!(width = image.width, height = image.height, bytes = png.len(), "capture sent to the phone");
        serde_json::to_string(&request).context("cannot encode the capture")
    })
    .await
    .context("the capture encoder stopped")?
}

/// Puts an annotation on the clipboard, saves it, and chimes.
pub async fn receive_annotation(result: AnnotateImage, owner: ClipboardOwner) {
    let outcome = task::spawn_blocking(move || -> anyhow::Result<PathBuf> {
        let png = STANDARD.decode(&result.data).context("invalid base64 in annotate.result")?;
        let image = bitmap::decode_png(&png)?;
        clipboard::write_image(owner.hwnd(), &png, &image)?;
        let path = save(&png)?;
        // SAFETY: a plain request to play the default sound.
        unsafe { MessageBeep(MB_OK) };
        Ok(path)
    })
    .await;
    match outcome {
        Ok(Ok(path)) => info!(file = %path.display(), "annotation copied to the clipboard and saved"),
        Ok(Err(error)) => warn!("cannot use the annotation: {error:#}"),
        Err(error) => warn!(%error, "the annotation handler stopped"),
    }
}

/// Saves an annotation as `Pictures\SashaBridge\anotacion-<date>-<time>.png`.
///
/// # Errors
///
/// Fails when the Pictures folder cannot be found or the file cannot be written.
fn save(png: &[u8]) -> anyhow::Result<PathBuf> {
    let folder = pictures_folder()?.join(SAVE_FOLDER);
    fs::create_dir_all(&folder).with_context(|| format!("cannot create {}", folder.display()))?;
    let path = folder.join(format!("anotacion-{}.png", local_timestamp()));
    fs::write(&path, png).with_context(|| format!("cannot write {}", path.display()))?;
    Ok(path)
}

/// Returns the user's Pictures folder, wherever it was moved (OneDrive, another drive).
fn pictures_folder() -> anyhow::Result<PathBuf> {
    let mut raw = std::ptr::null_mut();
    // SAFETY: the folder id is a valid constant, no token is given, and `raw` receives a
    // string that is freed below.
    let result = unsafe { SHGetKnownFolderPath(&FOLDERID_Pictures, 0, std::ptr::null_mut(), &mut raw) };
    if result < 0 || raw.is_null() {
        return Err(io::Error::from_raw_os_error(result)).context("cannot find the Pictures folder");
    }
    // SAFETY: the shell returned a NUL-terminated wide string, read and then freed once.
    let path = unsafe {
        let len = (0..).take_while(|&i| *raw.add(i) != 0).count();
        let path = String::from_utf16_lossy(std::slice::from_raw_parts(raw, len));
        CoTaskMemFree(raw.cast());
        path
    };
    Ok(PathBuf::from(path))
}

/// Local date and time as `YYYY-MM-DD-HHMMSS`, for file names.
fn local_timestamp() -> String {
    // SAFETY: SYSTEMTIME is a plain C struct, filled by the call.
    let now = unsafe {
        let mut now = std::mem::zeroed();
        GetLocalTime(&mut now);
        now
    };
    format!(
        "{:04}-{:02}-{:02}-{:02}{:02}{:02}",
        now.wYear, now.wMonth, now.wDay, now.wHour, now.wMinute, now.wSecond
    )
}

/// Returns a new identifier for a capture, unique within this run.
fn next_request_id() -> RequestId {
    static NEXT: AtomicU64 = AtomicU64::new(1);
    RequestId::numbered(NEXT.fetch_add(1, Ordering::Relaxed))
}
