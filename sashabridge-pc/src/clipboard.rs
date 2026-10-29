//! Images on the Windows clipboard, for the annotation mode.
//!
//! Captures are read as `CF_DIB`, which every screenshot tool provides. Annotations are
//! written both as `CF_DIB`, which Paint and most apps paste, and as the registered `PNG`
//! format, which keeps the exact pixels for apps that prefer it.

use std::io;
use std::thread::sleep;
use std::time::Duration;

use anyhow::{Context, bail};
use windows_sys::Win32::Foundation::{GlobalFree, HWND};
use windows_sys::Win32::System::DataExchange::{
    CloseClipboard, EmptyClipboard, GetClipboardData, IsClipboardFormatAvailable, OpenClipboard,
    RegisterClipboardFormatW, SetClipboardData,
};
use windows_sys::Win32::System::Memory::{GMEM_MOVEABLE, GlobalAlloc, GlobalLock, GlobalSize, GlobalUnlock};
use windows_sys::Win32::System::Ole::CF_DIB;

use crate::bitmap::{self, Rgba};

/// Attempts to open the clipboard, which another program may hold for a moment.
const OPEN_ATTEMPTS: u32 = 10;

/// Wait between attempts to open the clipboard.
const OPEN_RETRY: Duration = Duration::from_millis(20);

/// Whether the clipboard holds an image.
pub fn has_image() -> bool {
    // SAFETY: a plain query without arguments that point anywhere.
    unsafe { IsClipboardFormatAvailable(u32::from(CF_DIB)) != 0 }
}

/// Reads the image on the clipboard.
///
/// # Errors
///
/// Fails when the clipboard cannot be opened, holds no image, or holds a bitmap format that
/// [`bitmap::dib_to_rgba`] does not support.
pub fn read_image(owner: HWND) -> anyhow::Result<Rgba> {
    let _open = OpenClipboardGuard::open(owner)?;
    // SAFETY: the clipboard is open; the handle stays valid until it is closed.
    let handle = unsafe { GetClipboardData(u32::from(CF_DIB)) };
    if handle.is_null() {
        bail!("the clipboard holds no image");
    }
    // SAFETY: CF_DIB data is a global memory block; it is locked only while it is copied.
    let dib = unsafe {
        let size = GlobalSize(handle);
        let data = GlobalLock(handle).cast::<u8>();
        if data.is_null() {
            return Err(io::Error::last_os_error()).context("cannot lock the clipboard image");
        }
        let bytes = std::slice::from_raw_parts(data, size).to_vec();
        GlobalUnlock(handle);
        bytes
    };
    bitmap::dib_to_rgba(&dib)
}

/// Replaces the clipboard contents with an image, given as both PNG bytes and pixels.
///
/// # Errors
///
/// Fails when the clipboard cannot be opened or memory for the image cannot be allocated.
pub fn write_image(owner: HWND, png: &[u8], image: &Rgba) -> anyhow::Result<()> {
    let _open = OpenClipboardGuard::open(owner)?;
    // SAFETY: the clipboard is open by this thread.
    if unsafe { EmptyClipboard() } == 0 {
        return Err(io::Error::last_os_error()).context("cannot empty the clipboard");
    }
    put(u32::from(CF_DIB), &bitmap::rgba_to_dib(image))?;
    let png_format = to_wide("PNG");
    // SAFETY: the name is NUL-terminated and outlives the call.
    let png_id = unsafe { RegisterClipboardFormatW(png_format.as_ptr()) };
    if png_id != 0 {
        put(png_id, png)?;
    }
    Ok(())
}

/// Hands a copy of `bytes` to the open clipboard under `format`.
fn put(format: u32, bytes: &[u8]) -> anyhow::Result<()> {
    // SAFETY: a fresh movable block of the right size is filled while locked; on success the
    // clipboard owns it, on failure it is freed here.
    unsafe {
        let block = GlobalAlloc(GMEM_MOVEABLE, bytes.len());
        if block.is_null() {
            return Err(io::Error::last_os_error()).context("cannot allocate the clipboard image");
        }
        let data = GlobalLock(block).cast::<u8>();
        if data.is_null() {
            GlobalFree(block);
            return Err(io::Error::last_os_error()).context("cannot lock the clipboard image");
        }
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), data, bytes.len());
        GlobalUnlock(block);
        if SetClipboardData(format, block).is_null() {
            GlobalFree(block);
            return Err(io::Error::last_os_error()).context("cannot put the image on the clipboard");
        }
    }
    Ok(())
}

/// Keeps the clipboard open and closes it when dropped.
struct OpenClipboardGuard;

impl OpenClipboardGuard {
    /// Opens the clipboard for `owner`, retrying briefly while another program holds it.
    fn open(owner: HWND) -> anyhow::Result<Self> {
        for _ in 0..OPEN_ATTEMPTS {
            // SAFETY: `owner` is the client's hidden window, valid for the life of the process.
            if unsafe { OpenClipboard(owner) } != 0 {
                return Ok(Self);
            }
            sleep(OPEN_RETRY);
        }
        Err(io::Error::last_os_error()).context("the clipboard is busy")
    }
}

impl Drop for OpenClipboardGuard {
    fn drop(&mut self) {
        // SAFETY: the clipboard was opened by this guard.
        unsafe { CloseClipboard() };
    }
}

/// Encodes `text` as a NUL-terminated UTF-16 string for the wide Win32 APIs.
fn to_wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}
