//! S Pen "Anotar" mode: captures go to the phone, annotations come back.
//!
//! A second WebSocket session, on the `/pen` route (section 9 of `docs/protocolo-v2.md`),
//! carries the captures, so a large image never delays the music session. The session:
//! - remembers whether the phone's annotation screen is waiting (`annotate.status`);
//! - sends the images the user copies while it waits, and the one asked for with Ctrl+Alt+A
//!   always ([`WindowEvent::ImageCopied`]);
//! - puts each annotation it receives on the clipboard and saves it in
//!   `Pictures\SashaBridge`.

use std::convert::Infallible;
use std::fs;
use std::io;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use anyhow::Context;
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc::UnboundedReceiver;
use tokio::task;
use tokio::time::{sleep, timeout};
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;
use tracing::{debug, info, warn};
use windows_sys::Win32::System::Com::CoTaskMemFree;
use windows_sys::Win32::System::Diagnostics::Debug::MessageBeep;
use windows_sys::Win32::System::SystemInformation::GetLocalTime;
use windows_sys::Win32::UI::Shell::{FOLDERID_Pictures, SHGetKnownFolderPath};
use windows_sys::Win32::UI::WindowsAndMessaging::MB_OK;

use crate::backoff::Backoff;
use crate::bitmap::{self, Rgba};
use crate::clipboard;
use crate::config::{BACKOFF_INITIAL, BACKOFF_MAX, CONNECT_TIMEOUT, bridge_url};
use crate::hidden_window::{ClipboardOwner, WindowEvent};
use crate::protocol::v2::{AnnotateImage, PcMessage, PhoneMessage, RequestId};
use crate::protocol::{decode_phone_frame, pc_hello};

/// Folder inside Pictures where annotations are saved.
const SAVE_FOLDER: &str = "SashaBridge";

/// Media type of captures and annotations.
const PNG_MIME: &str = "image/png";

/// Keeps the `/pen` session open for as long as the future is polled.
///
/// Never returns; the caller stops it by dropping the future. While the phone is
/// unreachable, copied images are dropped: there is nobody to annotate them.
pub async fn run(events: &mut UnboundedReceiver<WindowEvent>, owner: ClipboardOwner) -> Infallible {
    let url = format!("{}pen", bridge_url());
    let mut backoff = Backoff::new(BACKOFF_INITIAL, BACKOFF_MAX);
    loop {
        match timeout(CONNECT_TIMEOUT, connect_async(url.as_str())).await {
            Ok(Ok((socket, _response))) => {
                backoff.reset();
                info!("S Pen route open");
                run_session(socket, events, owner).await;
            }
            // The music session already reports why the phone is unreachable.
            Ok(Err(error)) => debug!(%error, "S Pen route not ready"),
            Err(_) => debug!("S Pen route did not answer"),
        }
        while let Ok(WindowEvent::ImageCopied { forced, .. }) = events.try_recv() {
            if forced {
                warn!("the phone is not connected; the capture was not sent");
            }
        }
        sleep(backoff.next_delay()).await;
    }
}

/// Serves one `/pen` session until it ends.
async fn run_session(
    socket: tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
    events: &mut UnboundedReceiver<WindowEvent>,
    owner: ClipboardOwner,
) {
    let (mut sink, mut stream) = socket.split();
    if let Err(error) = sink.send(Message::text(pc_hello())).await {
        warn!(%error, "cannot greet the phone on the S Pen route");
        return;
    }
    let mut armed = false;
    loop {
        tokio::select! {
            frame = stream.next() => match frame {
                Some(Ok(Message::Text(text))) => match decode_phone_frame(&text) {
                    Ok(PhoneMessage::AnnotateStatus(status)) => {
                        if status.armed != armed {
                            info!(waiting = status.armed, "phone annotation screen");
                        }
                        armed = status.armed;
                    }
                    Ok(PhoneMessage::AnnotateResult(result)) => {
                        task::spawn(receive_annotation(result, owner));
                    }
                    Ok(PhoneMessage::AnnotateCancelled(cancelled)) => {
                        info!(request = cancelled.request_id.as_str(), "the capture was discarded on the phone");
                    }
                    Ok(_) => {}
                    Err(error) => warn!("ignoring a message on the S Pen route: {error:#}"),
                },
                Some(Ok(Message::Close(_))) | None => {
                    info!("S Pen route closed");
                    return;
                }
                Some(Ok(_)) => {}
                Some(Err(error)) => {
                    warn!(%error, "S Pen route lost");
                    return;
                }
            },
            Some(WindowEvent::ImageCopied { image, forced }) = events.recv() => {
                if !(armed || forced) {
                    debug!("image copied while the phone is not waiting; not sent");
                    continue;
                }
                match capture_request(image).await {
                    Ok(text) => {
                        if let Err(error) = sink.send(Message::text(text)).await {
                            warn!(%error, "cannot send the capture, S Pen route lost");
                            return;
                        }
                    }
                    Err(error) => warn!("cannot send the capture: {error:#}"),
                }
            }
        }
    }
}

/// Encodes a capture as an `annotate.request`, off the async threads.
async fn capture_request(image: Rgba) -> anyhow::Result<String> {
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
async fn receive_annotation(result: AnnotateImage, owner: ClipboardOwner) {
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
