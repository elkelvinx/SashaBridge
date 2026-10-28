//! Hidden Win32 window required by SMTC.
//!
//! Desktop applications obtain the System Media Transport Controls for a window handle,
//! so the client creates an invisible top-level window and runs its message loop on a
//! dedicated thread for the whole life of the process.

use std::ffi::c_void;
use std::io;
use std::sync::mpsc::{SyncSender, sync_channel};
use std::thread;

use anyhow::Context;
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, MSG, RegisterClassW,
    WNDCLASSW, WS_OVERLAPPED,
};

/// Window class registered for the hidden window.
const CLASS_NAME: &str = "SashaBridgeMsgWnd";

/// Window title; also the name Windows shows for the SMTC session.
const WINDOW_TITLE: &str = "SashaBridge";

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
}

/// Creates the hidden window on a new thread and returns its handle.
///
/// The thread keeps pumping messages until the process exits.
///
/// # Errors
///
/// Fails when the thread cannot be started or the window cannot be created.
pub fn spawn() -> anyhow::Result<WindowHandle> {
    let (ready_tx, ready_rx) = sync_channel(1);
    thread::Builder::new()
        .name("smtc-window".to_owned())
        .spawn(move || run_window_thread(&ready_tx))
        .context("cannot start the SMTC window thread")?;
    ready_rx
        .recv()
        .context("the SMTC window thread stopped before creating the window")?
}

/// Body of the window thread: creates the window, reports the result and pumps messages.
fn run_window_thread(ready: &SyncSender<anyhow::Result<WindowHandle>>) {
    let hwnd = match create_window() {
        Ok(hwnd) => hwnd,
        Err(error) => {
            // The receiver only disappears if `spawn` already returned, so nobody is waiting.
            let _ = ready.send(Err(error));
            return;
        }
    };
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
        return Err(io::Error::last_os_error()).context("cannot create the SMTC window");
    }
    Ok(hwnd)
}

/// Window procedure of the hidden window; every message gets the default handling.
///
/// # Safety
///
/// Called by Windows with the arguments of a message sent to the window.
unsafe extern "system" fn window_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    // SAFETY: the arguments come unchanged from Windows.
    unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) }
}

/// Encodes `text` as a NUL-terminated UTF-16 string for the wide Win32 APIs.
fn to_wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}
