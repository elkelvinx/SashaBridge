//! On-screen confirmation: a small box in the corner of the main monitor, like the volume
//! indicator, that confirms what a shortcut did (for example, a like with Ctrl+Alt+L).
//!
//! Windows notifications are not enough for this: Focus Assist turns on by itself while an
//! app is full screen or the screen is presented, and then keeps them out of sight. The box is
//! the client's own window instead: always on top, never takes the focus, lets clicks through,
//! and fades out after a moment. It is opaque: a translucent (layered) window was not drawn on
//! this PC, and the fade is the one Windows animates when it hides the box.
//!
//! Everything here runs on the hidden window's thread, which owns the box and its timers.

use std::cell::RefCell;
use std::io;

use tracing::warn;
use windows_sys::Win32::Foundation::{COLORREF, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows_sys::Win32::Graphics::Gdi::{
    BeginPaint, CLEARTYPE_QUALITY, CreateFontW, CreateRoundRectRgn, CreateSolidBrush, DEFAULT_CHARSET, DT_END_ELLIPSIS,
    DT_LEFT, DT_SINGLELINE, DT_VCENTER, DeleteObject, DrawTextW, EndPaint, FW_BOLD, FW_NORMAL, FillRect, HFONT,
    InvalidateRect, PAINTSTRUCT, SelectObject, SetBkMode, SetTextColor, SetWindowRgn, TRANSPARENT,
};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::HiDpi::GetDpiForSystem;
use windows_sys::Win32::UI::WindowsAndMessaging::{
    AW_BLEND, AW_HIDE, AnimateWindow, CreateWindowExW, DefWindowProcW, HTTRANSPARENT, HWND_TOPMOST, KillTimer,
    RegisterClassW, SPI_GETWORKAREA, SW_SHOWNOACTIVATE, SWP_NOACTIVATE, SetTimer, SetWindowPos, ShowWindow,
    SystemParametersInfoW, WM_NCHITTEST, WM_PAINT, WM_TIMER, WNDCLASSW, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW,
    WS_EX_TOPMOST, WS_POPUP,
};

/// Window class of the box.
const CLASS_NAME: &str = "SashaBridgeOsd";

/// Width of the box, in pixels at 100 % scale.
const WIDTH: i32 = 360;

/// Height of the box, in pixels at 100 % scale.
const HEIGHT: i32 = 78;

/// Distance from the box to the edges of the work area, in pixels at 100 % scale.
const MARGIN: i32 = 18;

/// Diameter of the rounded corners, in pixels at 100 % scale.
const RADIUS: i32 = 22;

/// Timer that ends the time the box stays visible.
const HOLD_TIMER: usize = 1;

/// How long the box stays before fading.
const HOLD_MS: u32 = 2200;

/// Length of the fade out.
const FADE_MS: u32 = 250;

/// Background: the app's dark wine.
const BACKGROUND: COLORREF = rgb(0x2B, 0x1A, 0x20);

/// Color of the heart.
const ACCENT: COLORREF = rgb(0xE0, 0x4F, 0x6A);

/// Color of the first line.
const HEADING: COLORREF = rgb(0xF6, 0xEC, 0xEE);

/// Color of the second line.
const DETAIL: COLORREF = rgb(0xC9, 0xB2, 0xB7);

/// Glyph of a filled heart.
const HEART: &str = "\u{2665}";

/// Glyph of an empty heart.
const EMPTY_HEART: &str = "\u{2661}";

/// What the box says.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Announcement {
    /// Whether the icon is a filled heart; otherwise an empty one.
    pub filled: bool,
    /// First line, in bold.
    pub heading: String,
    /// Second line, shortened with "..." when it does not fit.
    pub detail: String,
}

/// The box, created the first time it is needed.
struct Osd {
    /// The box's window.
    hwnd: HWND,
    /// What it shows now.
    announcement: Announcement,
    /// Scale of the main monitor, as a multiple of 100 %.
    scale: f32,
}

thread_local! {
    /// The box of this thread, the hidden window's.
    static OSD: RefCell<Option<Osd>> = const { RefCell::new(None) };
}

/// Shows `announcement`, replacing the one on screen if there is one. Hidden window's thread.
pub fn show(announcement: Announcement) {
    OSD.with(|cell| {
        let mut slot = cell.borrow_mut();
        if slot.is_none() {
            match create() {
                Ok(osd) => *slot = Some(osd),
                Err(error) => {
                    warn!(%error, "cannot create the on-screen confirmation");
                    return;
                }
            }
        }
        let Some(osd) = slot.as_mut() else { return };
        osd.announcement = announcement;
        place(osd);
        // SAFETY: `osd.hwnd` is the box, created on this thread. Setting the timer again
        // restarts it, so a new confirmation stays its full time.
        unsafe {
            InvalidateRect(osd.hwnd, std::ptr::null(), 1);
            ShowWindow(osd.hwnd, SW_SHOWNOACTIVATE);
            SetTimer(osd.hwnd, HOLD_TIMER, HOLD_MS, None);
        }
    });
}

/// Creates the hidden box.
fn create() -> io::Result<Osd> {
    let class_name = to_wide(CLASS_NAME);
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
    // SAFETY: `class` and its strings live until the call returns. A failure surfaces as a
    // CreateWindowExW error below.
    unsafe { RegisterClassW(&class) };
    // SAFETY: the class name is NUL-terminated and outlives the call; the box has no parent,
    // menu, title or creation parameter.
    let hwnd = unsafe {
        CreateWindowExW(
            WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
            class_name.as_ptr(),
            std::ptr::null(),
            WS_POPUP,
            0,
            0,
            WIDTH,
            HEIGHT,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        )
    };
    if hwnd.is_null() {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: a plain query; 96 is 100 % scale.
    let scale = unsafe { GetDpiForSystem() } as f32 / 96.0;
    Ok(Osd {
        hwnd,
        announcement: Announcement { filled: false, heading: String::new(), detail: String::new() },
        scale,
    })
}

/// Puts the box in the bottom right corner of the main monitor's work area, above the
/// taskbar wherever it is, with rounded corners.
fn place(osd: &Osd) {
    let mut area = RECT { left: 0, top: 0, right: 0, bottom: 0 };
    // SAFETY: `area` is a valid RECT that the call fills.
    unsafe { SystemParametersInfoW(SPI_GETWORKAREA, 0, (&raw mut area).cast(), 0) };
    let width = scaled(WIDTH, osd.scale);
    let height = scaled(HEIGHT, osd.scale);
    let margin = scaled(MARGIN, osd.scale);
    let radius = scaled(RADIUS, osd.scale);
    // SAFETY: `osd.hwnd` is the box; the window takes ownership of the region.
    unsafe {
        SetWindowPos(
            osd.hwnd,
            HWND_TOPMOST,
            area.right - width - margin,
            area.bottom - height - margin,
            width,
            height,
            SWP_NOACTIVATE,
        );
        SetWindowRgn(osd.hwnd, CreateRoundRectRgn(0, 0, width + 1, height + 1, radius, radius), 1);
    }
}

/// Window procedure of the box: paints it, lets clicks through and hides it on time.
///
/// # Safety
///
/// Called by Windows with the arguments of a message sent to the box.
unsafe extern "system" fn window_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match msg {
        WM_PAINT => {
            paint(hwnd);
            0
        }
        // Clicks go to whatever is under the box.
        WM_NCHITTEST => HTTRANSPARENT as LRESULT,
        WM_TIMER if wparam == HOLD_TIMER => {
            // SAFETY: `hwnd` is the box, on this thread; the fade blocks this thread for its
            // quarter of a second.
            unsafe {
                KillTimer(hwnd, HOLD_TIMER);
                AnimateWindow(hwnd, FADE_MS, AW_BLEND | AW_HIDE);
            }
            0
        }
        // SAFETY: the arguments come unchanged from Windows.
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

/// Paints the background, the heart and the two lines.
fn paint(hwnd: HWND) {
    OSD.with(|cell| {
        let slot = cell.borrow();
        // SAFETY: PAINTSTRUCT is a plain C struct for which all-zero bytes are a valid value.
        let mut paint: PAINTSTRUCT = unsafe { std::mem::zeroed() };
        // SAFETY: `hwnd` is the box and `paint` a valid out pointer; EndPaint below closes it.
        let hdc = unsafe { BeginPaint(hwnd, &mut paint) };
        if let Some(osd) = slot.as_ref() {
            let s = osd.scale;
            let width = scaled(WIDTH, s);
            let height = scaled(HEIGHT, s);
            let pad = scaled(20, s);
            let icon = scaled(36, s);
            // SAFETY: every GDI object created here is selected out and deleted before leaving.
            unsafe {
                let background = CreateSolidBrush(BACKGROUND);
                FillRect(hdc, &RECT { left: 0, top: 0, right: width, bottom: height }, background);
                DeleteObject(background);
                SetBkMode(hdc, TRANSPARENT as i32);

                let glyph = if osd.announcement.filled { HEART } else { EMPTY_HEART };
                let heart_font = font("Segoe UI Symbol", scaled(30, s), false);
                let previous = SelectObject(hdc, heart_font);
                SetTextColor(hdc, ACCENT);
                draw(hdc, glyph, RECT { left: pad, top: 0, right: pad + icon, bottom: height });

                let text_left = pad + icon + scaled(12, s);
                let heading_font = font("Segoe UI", scaled(19, s), true);
                SelectObject(hdc, heading_font);
                SetTextColor(hdc, HEADING);
                draw(
                    hdc,
                    &osd.announcement.heading,
                    RECT { left: text_left, top: scaled(12, s), right: width - pad, bottom: height / 2 + scaled(2, s) },
                );

                let detail_font = font("Segoe UI", scaled(15, s), false);
                SelectObject(hdc, detail_font);
                SetTextColor(hdc, DETAIL);
                draw(
                    hdc,
                    &osd.announcement.detail,
                    RECT { left: text_left, top: height / 2, right: width - pad, bottom: height - scaled(10, s) },
                );

                SelectObject(hdc, previous);
                DeleteObject(heart_font);
                DeleteObject(heading_font);
                DeleteObject(detail_font);
            }
        }
        // SAFETY: closes the BeginPaint above.
        unsafe { EndPaint(hwnd, &paint) };
    });
}

/// Draws one line of `text` in `rect`, vertically centered, with "..." if it is too long.
///
/// # Safety
///
/// `hdc` must be a valid device context.
unsafe fn draw(hdc: windows_sys::Win32::Graphics::Gdi::HDC, text: &str, mut rect: RECT) {
    let wide: Vec<u16> = text.encode_utf16().collect();
    let len = i32::try_from(wide.len()).unwrap_or(i32::MAX);
    // SAFETY: `wide` holds `len` characters and `rect` is a valid RECT.
    unsafe { DrawTextW(hdc, wide.as_ptr(), len, &mut rect, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS) };
}

/// Creates a font of `face`, `size` pixels tall.
///
/// # Safety
///
/// The caller deletes the font with DeleteObject.
unsafe fn font(face: &str, size: i32, bold: bool) -> HFONT {
    let face = to_wide(face);
    let weight = if bold { FW_BOLD } else { FW_NORMAL };
    // SAFETY: `face` is NUL-terminated and outlives the call; a negative height asks for the
    // character height rather than the cell height.
    unsafe {
        CreateFontW(
            -size,
            0,
            0,
            0,
            weight as i32,
            0,
            0,
            0,
            u32::from(DEFAULT_CHARSET),
            0,
            0,
            u32::from(CLEARTYPE_QUALITY),
            0,
            face.as_ptr(),
        )
    }
}

/// `value` pixels at 100 % scale, at `scale`.
fn scaled(value: i32, scale: f32) -> i32 {
    (value as f32 * scale).round() as i32
}

/// A GDI color from its red, green and blue parts.
const fn rgb(red: u8, green: u8, blue: u8) -> COLORREF {
    red as u32 | (green as u32) << 8 | (blue as u32) << 16
}

/// Encodes `text` as a NUL-terminated UTF-16 string for the wide Win32 APIs.
fn to_wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colors_are_in_gdi_order() {
        assert_eq!(rgb(0x11, 0x22, 0x33), 0x0033_2211);
    }

    #[test]
    fn sizes_follow_the_scale() {
        assert_eq!(scaled(WIDTH, 1.0), WIDTH);
        assert_eq!(scaled(WIDTH, 1.25), 450);
    }

    #[test]
    #[ignore = "shows the confirmation box on the desktop for a few seconds"]
    fn box_appears() {
        show(Announcement { filled: true, heading: "Te gusta".to_owned(), detail: "Lights Out \u{b7} VK Blanka".to_owned() });
        // Pump this thread's messages so that the box paints and fades out.
        let until = std::time::Instant::now() + std::time::Duration::from_secs(4);
        while std::time::Instant::now() < until {
            // SAFETY: MSG is a plain C struct; PeekMessageW fills it.
            let mut msg: windows_sys::Win32::UI::WindowsAndMessaging::MSG = unsafe { std::mem::zeroed() };
            // SAFETY: `msg` is a valid out pointer; PM_REMOVE takes the message off the queue.
            while unsafe {
                windows_sys::Win32::UI::WindowsAndMessaging::PeekMessageW(
                    &mut msg,
                    std::ptr::null_mut(),
                    0,
                    0,
                    windows_sys::Win32::UI::WindowsAndMessaging::PM_REMOVE,
                )
            } != 0
            {
                // SAFETY: `msg` was filled by PeekMessageW.
                unsafe { windows_sys::Win32::UI::WindowsAndMessaging::DispatchMessageW(&msg) };
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
    }
}
