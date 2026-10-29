//! Hidden Win32 window of the client.
//!
//! Desktop applications obtain the System Media Transport Controls for a window handle, and
//! clipboard notifications and global shortcuts are also delivered to a window. The client
//! creates one invisible top-level window and runs its message loop on a dedicated thread
//! for the whole life of the process.
//!
//! For the S Pen annotation mode the window:
//! - listens to the clipboard and reports every image copied by another program
//!   ([`WindowEvent::ImageCopied`]);
//! - registers Ctrl+Alt+A, which opens the Windows snipping overlay and marks the next
//!   copied image as explicitly asked for, so it is sent even when the phone is not waiting.

use std::cell::{Cell, OnceCell};
use std::ffi::c_void;
use std::io;
use std::sync::mpsc::{SyncSender, sync_channel};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::Context;
use tokio::sync::mpsc::UnboundedSender;
use tracing::{debug, info, warn};
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::DataExchange::{AddClipboardFormatListener, GetClipboardOwner};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{MOD_ALT, MOD_CONTROL, MOD_NOREPEAT, RegisterHotKey};
use windows_sys::Win32::UI::Shell::ShellExecuteW;
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, MSG, RegisterClassW, SW_SHOWNORMAL,
    WM_CLIPBOARDUPDATE, WM_HOTKEY, WNDCLASSW, WS_OVERLAPPED,
};

use crate::bitmap::Rgba;
use crate::clipboard;

/// Window class registered for the hidden window.
const CLASS_NAME: &str = "SashaBridgeMsgWnd";

/// Window title; also the name Windows shows for the SMTC session.
const WINDOW_TITLE: &str = "SashaBridge";

/// Identifier of the Ctrl+Alt+A shortcut.
const ANNOTATE_HOTKEY: i32 = 1;

/// Virtual-key code of the shortcut's letter.
const ANNOTATE_KEY: u32 = b'A' as u32;

/// How long after Ctrl+Alt+A a copied image still counts as the one asked for.
const SHORTCUT_WINDOW: Duration = Duration::from_secs(60);

/// Something the hidden window noticed, for the annotation mode.
#[derive(Debug)]
pub enum WindowEvent {
    /// Another program copied an image.
    ImageCopied {
        /// The image.
        image: Rgba,
        /// Whether it answers Ctrl+Alt+A, which sends it even if the phone is not waiting.
        forced: bool,
    },
}

/// Handle of the hidden window.
pub struct WindowHandle(HWND);

// SAFETY: a window handle is a system-wide identifier that is valid on any thread.
// The handle is only used to obtain the window's SMTC instance; messages are still
// processed on the window thread.
unsafe impl Send for WindowHandle {}

impl WindowHandle {
    /// Returns the raw handle, for `ISystemMediaTransportControlsInterop::GetForWindow`.
    pub fn as_raw(&self) -> *mut c_void {
        self.0
    }

    /// Returns the handle that owns the clipboard data the client writes.
    pub fn clipboard_owner(&self) -> ClipboardOwner {
        ClipboardOwner(self.0)
    }
}

/// The hidden window, as the owner of clipboard data written from any thread.
#[derive(Debug, Clone, Copy)]
pub struct ClipboardOwner(HWND);

// SAFETY: as for `WindowHandle`; the clipboard functions accept the owner from any thread.
unsafe impl Send for ClipboardOwner {}

impl ClipboardOwner {
    /// Returns the raw handle.
    pub fn hwnd(self) -> HWND {
        self.0
    }
}

thread_local! {
    /// Where the window thread reports its events.
    static EVENTS: OnceCell<UnboundedSender<WindowEvent>> = const { OnceCell::new() };
    /// Until when a copied image answers Ctrl+Alt+A.
    static SHORTCUT_UNTIL: Cell<Option<Instant>> = const { Cell::new(None) };
}

/// Creates the hidden window on a new thread and returns its handle.
///
/// The thread keeps pumping messages until the process exits, and reports clipboard images
/// to `events`.
///
/// # Errors
///
/// Fails when the thread cannot be started or the window cannot be created.
pub fn spawn(events: UnboundedSender<WindowEvent>) -> anyhow::Result<WindowHandle> {
    let (ready_tx, ready_rx) = sync_channel(1);
    thread::Builder::new()
        .name("hidden-window".to_owned())
        .spawn(move || run_window_thread(&ready_tx, events))
        .context("cannot start the window thread")?;
    ready_rx.recv().context("the window thread stopped before creating the window")?
}

/// Body of the window thread: creates the window, reports the result and pumps messages.
fn run_window_thread(ready: &SyncSender<anyhow::Result<WindowHandle>>, events: UnboundedSender<WindowEvent>) {
    EVENTS.with(|cell| {
        let _ = cell.set(events);
    });
    let hwnd = match create_window() {
        Ok(hwnd) => hwnd,
        Err(error) => {
            // The receiver only disappears if `spawn` already returned, so nobody is waiting.
            let _ = ready.send(Err(error));
            return;
        }
    };
    listen(hwnd);
    if ready.send(Ok(WindowHandle(hwnd))).is_err() {
        return;
    }

    // SAFETY: MSG is a plain C struct for which all-zero bytes are a valid value.
    let mut msg: MSG = unsafe { std::mem::zeroed() };
    // SAFETY: `msg` is a valid out pointer, and a null window handle retrieves the
    // messages of every window owned by this thread. GetMessageW returns 0 on WM_QUIT
    // and -1 on error; both end the loop.
    while unsafe { GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) } > 0 {
        // SAFETY: `msg` was filled by GetMessageW.
        unsafe { DispatchMessageW(&msg) };
    }
}

/// Subscribes the window to clipboard changes and registers Ctrl+Alt+A. Either failing only
/// disables that part of the annotation mode.
fn listen(hwnd: HWND) {
    // SAFETY: `hwnd` is the window just created on this thread.
    if unsafe { AddClipboardFormatListener(hwnd) } == 0 {
        warn!(error = %io::Error::last_os_error(), "cannot listen to the clipboard; copied captures will not reach the phone");
    }
    // SAFETY: as above; the shortcut belongs to this window until the process exits.
    let registered =
        unsafe { RegisterHotKey(hwnd, ANNOTATE_HOTKEY, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, ANNOTATE_KEY) };
    if registered == 0 {
        warn!(error = %io::Error::last_os_error(), "Ctrl+Alt+A is taken by another program");
    }
}

/// Registers the window class and creates the invisible window on the current thread.
///
/// # Errors
///
/// Fails when CreateWindowExW fails.
fn create_window() -> anyhow::Result<HWND> {
    let class_name = to_wide(CLASS_NAME);
    let title = to_wide(WINDOW_TITLE);

    // SAFETY: a null module name returns the handle of the current executable.
    let instance = unsafe { GetModuleHandleW(std::ptr::null()) };
    let class = WNDCLASSW {
        style: 0,
        lpfnWndProc: Some(window_proc),
        cbClsExtra: 0,
        cbWndExtra: 0,
        hInstance: instance,
        hIcon: std::ptr::null_mut(),
        hCursor: std::ptr::null_mut(),
        hbrBackground: std::ptr::null_mut(),
        lpszMenuName: std::ptr::null(),
        lpszClassName: class_name.as_ptr(),
    };

    // SAFETY: `class` and the string it points to live until the call returns. A failure
    // (for example, the class already exists) surfaces as a CreateWindowExW error below.
    unsafe { RegisterClassW(&class) };

    // SAFETY: the class name and title are NUL-terminated and outlive the call; the
    // window has no parent, menu or creation parameter.
    let hwnd = unsafe {
        CreateWindowExW(
            0,
            class_name.as_ptr(),
            title.as_ptr(),
            WS_OVERLAPPED,
            0,
            0,
            0,
            0,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        )
    };
    if hwnd.is_null() {
        return Err(io::Error::last_os_error()).context("cannot create the hidden window");
    }
    Ok(hwnd)
}

/// Window procedure of the hidden window: clipboard changes and the shortcut feed the
/// annotation mode; everything else gets the default handling.
///
/// # Safety
///
/// Called by Windows with the arguments of a message sent to the window.
unsafe extern "system" fn window_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match msg {
        WM_CLIPBOARDUPDATE => {
            on_clipboard_update(hwnd);
            0
        }
        WM_HOTKEY if wparam == ANNOTATE_HOTKEY as usize => {
            on_annotate_shortcut();
            0
        }
        // SAFETY: the arguments come unchanged from Windows.
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

/// Reports an image copied by another program; the client's own annotations are skipped.
fn on_clipboard_update(hwnd: HWND) {
    // SAFETY: a plain query.
    if unsafe { GetClipboardOwner() } == hwnd || !clipboard::has_image() {
        return;
    }
    let image = match clipboard::read_image(hwnd) {
        Ok(image) => image,
        Err(error) => {
            debug!("ignoring a copied image: {error:#}");
            return;
        }
    };
    let forced = SHORTCUT_UNTIL.with(|until| until.take().is_some_and(|deadline| Instant::now() < deadline));
    debug!(width = image.width, height = image.height, forced, "image copied");
    EVENTS.with(|cell| {
        if let Some(events) = cell.get() {
            // The receiver only stops with the process.
            let _ = events.send(WindowEvent::ImageCopied { image, forced });
        }
    });
}

/// Opens the Windows snipping overlay; the image it copies is then sent to the phone.
fn on_annotate_shortcut() {
    info!("Ctrl+Alt+A: snip the area to annotate");
    SHORTCUT_UNTIL.with(|until| until.set(Some(Instant::now() + SHORTCUT_WINDOW)));
    let verb = to_wide("open");
    let target = to_wide("ms-screenclip:");
    // SAFETY: both strings are NUL-terminated and outlive the call; no window, parameters
    // or directory are passed.
    let result = unsafe {
        ShellExecuteW(
            std::ptr::null_mut(),
            verb.as_ptr(),
            target.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            SW_SHOWNORMAL,
        )
    };
    // ShellExecuteW reports success with a value greater than 32.
    if result as usize <= 32 {
        warn!("cannot open the snipping overlay; use Win+Shift+S instead");
    }
}

/// Encodes `text` as a NUL-terminated UTF-16 string for the wide Win32 APIs.
fn to_wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}
