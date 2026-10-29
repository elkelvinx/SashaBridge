//! S Pen "Tableta" mode: the phone becomes a drawing tablet for Windows.
//!
//! The phone sends its pen samples on the `/pen` route. A thread of this module replays them
//! through a synthetic Windows pen (`CreateSyntheticPointerDevice`), which apps such as Paint,
//! Whiteboard or Affinity see as a real Windows Ink pen: pressure, tilt, hovering, the side
//! button and the eraser. The phone's drawing area maps onto one monitor, which the phone can
//! switch ([`Tablet::select`]).
//!
//! Windows places injected pen points from the top-left corner of the virtual screen (all
//! monitors together), not from the primary monitor: with a monitor to the left or above the
//! primary one, that corner has negative coordinates and every point is shifted by it.

use std::mem::{size_of, zeroed};
use std::num::NonZeroU32;
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread;

use anyhow::Context;
use tracing::{info, warn};
use windows_sys::Win32::Foundation::{BOOL, LPARAM, POINT, RECT};
use windows_sys::Win32::Graphics::Gdi::{EnumDisplayMonitors, GetMonitorInfoW, HDC, HMONITOR, MONITORINFOEXW};
use windows_sys::Win32::UI::Controls::{
    CreateSyntheticPointerDevice, DestroySyntheticPointerDevice, HSYNTHETICPOINTERDEVICE, POINTER_FEEDBACK_DEFAULT,
    POINTER_TYPE_INFO, POINTER_TYPE_INFO_0,
};
use windows_sys::Win32::UI::HiDpi::{DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, SetProcessDpiAwarenessContext};
use windows_sys::Win32::UI::Input::Pointer::{
    InjectSyntheticPointerInput, POINTER_FLAG_DOWN, POINTER_FLAG_FIRSTBUTTON, POINTER_FLAG_INCONTACT,
    POINTER_FLAG_INRANGE, POINTER_FLAG_PRIMARY, POINTER_FLAG_UP, POINTER_FLAG_UPDATE, POINTER_FLAGS, POINTER_INFO,
    POINTER_PEN_INFO,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    GetSystemMetrics, MONITORINFOF_PRIMARY, SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, PEN_FLAG_BARREL, PEN_FLAG_ERASER, PEN_FLAG_INVERTED, PEN_FLAG_NONE, PEN_MASK_PRESSURE,
    PEN_MASK_TILT_X, PEN_MASK_TILT_Y, PT_PEN,
};

use crate::protocol::pen_samples::PenSample;
use crate::protocol::v2::TabletTarget;

/// Largest pressure Windows pens report.
const MAX_PRESSURE: u32 = 1024;

/// Largest coordinate and pressure of a pen sample.
const SAMPLE_MAX: u32 = u16::MAX as u32;

/// Makes the process work in physical pixels on every monitor, as the synthetic pen does.
///
/// Call it before creating any window. Without it, Windows scales the coordinates of a
/// process it thinks is unaware of DPI, and the pen would land off target on scaled
/// monitors.
pub fn use_physical_pixels() {
    // SAFETY: a plain request with a predefined context; it fails harmlessly if the process
    // already chose one.
    if unsafe { SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) } == 0 {
        warn!("cannot switch to per-monitor DPI awareness; the tablet may miss on scaled monitors");
    }
}

/// The tablet: the monitor it draws on and the thread that drives the synthetic pen.
///
/// Used from the async tasks; the pen itself lives on its own thread, because injection
/// blocks for a moment and must not stall the connection.
pub struct Tablet {
    /// Orders for the pen thread; sending fails only if the thread could not start its pen.
    pen: Sender<PenOrder>,
    /// Monitors found at the last refresh, the primary one first.
    monitors: Vec<Monitor>,
    /// Index in `monitors` of the one the tablet draws on.
    selected: usize,
}

impl Tablet {
    /// Starts the pen thread.
    ///
    /// # Errors
    ///
    /// Fails when the thread cannot be created.
    pub fn spawn() -> anyhow::Result<Self> {
        let (pen, orders) = mpsc::channel();
        thread::Builder::new()
            .name("tablet-pen".to_owned())
            .spawn(move || drive(&orders))
            .context("cannot start the tablet thread")?;
        Ok(Self { pen, monitors: Vec::new(), selected: 0 })
    }

    /// Looks for the monitors again and returns the one the tablet draws on, to tell the phone.
    ///
    /// Returns `None` when Windows reports no monitor.
    pub fn target(&mut self) -> Option<TabletTarget> {
        self.monitors = monitors();
        if self.selected >= self.monitors.len() {
            self.selected = 0;
        }
        let monitor = self.monitors.get(self.selected)?;
        self.order(PenOrder::Area(monitor.area));
        let count = NonZeroU32::new(u32::try_from(self.monitors.len()).ok()?)?;
        let index = u32::try_from(self.selected).ok()?;
        match TabletTarget::new(index, count, monitor.name.clone(), monitor.area.width, monitor.area.height) {
            Ok(target) => Some(target),
            Err(error) => {
                warn!(%error, "cannot describe the tablet's monitor");
                None
            }
        }
    }

    /// Moves the tablet to monitor `index` (wrapping past the last one) and returns it, to tell
    /// the phone. The pen is lifted first, so no stroke jumps between monitors.
    pub fn select(&mut self, index: u32) -> Option<TabletTarget> {
        self.lift();
        let count = monitors().len().max(1);
        self.selected = usize::try_from(index).map_or(0, |index| index % count);
        let target = self.target()?;
        info!(monitor = %target_name(&self.monitors, self.selected), "tablet moved to another monitor");
        Some(target)
    }

    /// Replays a frame of samples with the pen.
    pub fn draw(&self, samples: Vec<PenSample>) {
        self.order(PenOrder::Samples(samples));
    }

    /// Takes the pen off the screen and out of range, for example when the tablet screen
    /// closes or the phone goes away.
    pub fn lift(&self) {
        self.order(PenOrder::Lift);
    }

    /// Sends `order` to the pen thread, which only stops if its pen could not be created.
    fn order(&self, order: PenOrder) {
        // The thread already logged why it has no pen.
        let _ = self.pen.send(order);
    }
}

/// Name of monitor `index`, for the log.
fn target_name(monitors: &[Monitor], index: usize) -> &str {
    monitors.get(index).map_or("?", |monitor| monitor.name.as_str())
}

/// Orders for the pen thread.
#[derive(Debug)]
enum PenOrder {
    /// Map the drawing area onto this part of the desktop.
    Area(Area),
    /// Replay these samples, in order.
    Samples(Vec<PenSample>),
    /// Lift the pen and take it out of range.
    Lift,
}

/// Runs the synthetic pen until the [`Tablet`] is dropped. Runs on the `tablet-pen` thread.
fn drive(orders: &Receiver<PenOrder>) {
    let Some(mut pen) = SyntheticPen::create() else {
        return;
    };
    for order in orders {
        match order {
            PenOrder::Area(area) => {
                pen.release();
                pen.area = Some(area);
                pen.origin = virtual_screen_origin();
            }
            PenOrder::Samples(samples) => samples.iter().for_each(|sample| pen.replay(sample)),
            PenOrder::Lift => pen.release(),
        }
    }
}

/// A Windows synthetic pen and the last state it reported. Lives on the `tablet-pen` thread.
struct SyntheticPen {
    /// The device Windows created.
    device: HSYNTHETICPOINTERDEVICE,
    /// Part of the desktop the drawing area maps onto; nothing is injected until it is known.
    area: Option<Area>,
    /// Top-left corner of the virtual screen, which injected points are measured from.
    origin: POINT,
    /// Range and contact of the last injected state.
    state: PenState,
    /// Where the pen was last, to lift it in the same place.
    last_point: POINT,
    /// Counter that tells Windows which injections belong to the same input frame.
    frame: u32,
    /// Whether an injection failure was already logged, so a broken pen does not flood the log.
    failure_logged: bool,
}

impl SyntheticPen {
    /// Creates the synthetic pen, or logs why Windows refused it (it needs Windows 10 1809).
    fn create() -> Option<Self> {
        // SAFETY: a plain request for a device of one pen with the default visual feedback.
        let device = unsafe { CreateSyntheticPointerDevice(PT_PEN, 1, POINTER_FEEDBACK_DEFAULT) };
        if device.is_null() {
            warn!(
                error = %std::io::Error::last_os_error(),
                "Windows cannot create a synthetic pen (it needs Windows 10 1809 or later); the tablet is off"
            );
            return None;
        }
        Some(Self {
            device,
            area: None,
            origin: virtual_screen_origin(),
            state: PenState::default(),
            last_point: POINT { x: 0, y: 0 },
            frame: 0,
            failure_logged: false,
        })
    }

    /// Replays one sample: the pointer events that take the pen from its last state to it.
    fn replay(&mut self, sample: &PenSample) {
        let Some(area) = self.area else {
            return;
        };
        let now = PenState::of(sample);
        let point = relative_to(area.pixel(sample.x, sample.y), self.origin);
        for flags in steps(self.state, now) {
            self.inject(flags, point, Some(sample));
        }
        self.state = now;
        self.last_point = point;
    }

    /// Lifts the pen where it is and takes it out of range.
    fn release(&mut self) {
        for flags in steps(self.state, PenState::default()) {
            self.inject(flags, self.last_point, None);
        }
        self.state = PenState::default();
    }

    /// Injects one pen event at `point`, with the pressure, tilt and buttons of `sample`.
    fn inject(&mut self, flags: POINTER_FLAGS, point: POINT, sample: Option<&PenSample>) {
        self.frame = self.frame.wrapping_add(1);
        let info = POINTER_TYPE_INFO {
            r#type: PT_PEN,
            Anonymous: POINTER_TYPE_INFO_0 { penInfo: pen_info(flags, point, self.frame, sample) },
        };
        // SAFETY: the device is alive until `drop`, and `info` is one valid pen record.
        if unsafe { InjectSyntheticPointerInput(self.device, &info, 1) } == 0 && !self.failure_logged {
            self.failure_logged = true;
            warn!(error = %std::io::Error::last_os_error(), "Windows rejected the tablet's pen input");
        }
    }
}

impl Drop for SyntheticPen {
    fn drop(&mut self) {
        self.release();
        // SAFETY: the device was created by `create` and is destroyed once.
        unsafe { DestroySyntheticPointerDevice(self.device) };
    }
}

/// Top-left corner of the virtual screen, in physical pixels; negative when a monitor sits to
/// the left of or above the primary one.
fn virtual_screen_origin() -> POINT {
    // SAFETY: plain queries without arguments that point anywhere.
    unsafe { POINT { x: GetSystemMetrics(SM_XVIRTUALSCREEN), y: GetSystemMetrics(SM_YVIRTUALSCREEN) } }
}

/// `point`, a desktop position, measured from `origin`, the top-left corner of the virtual
/// screen, as Windows reads injected pen points.
fn relative_to(point: POINT, origin: POINT) -> POINT {
    POINT { x: point.x - origin.x, y: point.y - origin.y }
}

/// Builds the pen record of one injection.
fn pen_info(flags: POINTER_FLAGS, point: POINT, frame: u32, sample: Option<&PenSample>) -> POINTER_PEN_INFO {
    // SAFETY: POINTER_PEN_INFO is a plain C struct; all zeros is its empty value.
    let mut info: POINTER_PEN_INFO = unsafe { zeroed() };
    info.pointerInfo = POINTER_INFO {
        pointerType: PT_PEN,
        frameId: frame,
        pointerFlags: flags,
        ptPixelLocation: point,
        // SAFETY: as above, the rest of POINTER_INFO stays empty; Windows fills the time.
        ..unsafe { zeroed() }
    };
    info.penMask = PEN_MASK_PRESSURE | PEN_MASK_TILT_X | PEN_MASK_TILT_Y;
    if let Some(sample) = sample {
        info.penFlags = pen_flags(sample);
        info.pressure = if sample.in_contact { pressure(sample.pressure) } else { 0 };
        info.tiltX = i32::from(sample.tilt_x);
        info.tiltY = i32::from(sample.tilt_y);
    }
    info
}

/// Windows pen flags for the buttons of a sample: the side button, and the eraser end, which
/// Windows calls "inverted" and which erases while it touches.
fn pen_flags(sample: &PenSample) -> u32 {
    let mut flags = PEN_FLAG_NONE;
    if sample.barrel {
        flags |= PEN_FLAG_BARREL;
    }
    if sample.eraser {
        flags |= PEN_FLAG_INVERTED;
        if sample.in_contact {
            flags |= PEN_FLAG_ERASER;
        }
    }
    flags
}

/// Converts a sample pressure (0 to 65535) to a Windows one (0 to 1024).
fn pressure(raw: u16) -> u32 {
    (u32::from(raw) * MAX_PRESSURE + SAMPLE_MAX / 2) / SAMPLE_MAX
}

/// Whether the pen is in hover range and whether it touches the screen.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct PenState {
    /// The pen is within hover range, touching or not.
    in_range: bool,
    /// The tip touches the screen.
    in_contact: bool,
}

impl PenState {
    /// The state a sample describes; touching implies being in range.
    fn of(sample: &PenSample) -> Self {
        Self { in_range: sample.in_range || sample.in_contact, in_contact: sample.in_contact }
    }
}

/// Pointer flags of the events that take the pen from `before` to `now`, in order.
///
/// Hovering is an update in range; touching down, moving while touching and lifting are
/// down, update and up events in contact; leaving the range is an update without the
/// in-range flag. Coming down from out of range hovers first, and lifting out of range lifts
/// first, as a real pen does.
fn steps(before: PenState, now: PenState) -> Vec<POINTER_FLAGS> {
    let hover = POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE | POINTER_FLAG_UPDATE;
    let touching = POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON;
    let leave = POINTER_FLAG_PRIMARY | POINTER_FLAG_UPDATE;
    match (before.in_contact, now.in_contact) {
        (false, true) if !before.in_range => vec![hover, touching | POINTER_FLAG_DOWN],
        (false, true) => vec![touching | POINTER_FLAG_DOWN],
        (true, true) => vec![touching | POINTER_FLAG_UPDATE],
        (true, false) if now.in_range => vec![POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE | POINTER_FLAG_UP],
        (true, false) => vec![POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE | POINTER_FLAG_UP, leave],
        (false, false) if now.in_range => vec![hover],
        (false, false) if before.in_range => vec![leave],
        (false, false) => Vec::new(),
    }
}

/// A rectangle of the desktop, in physical pixels.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Area {
    /// Left edge.
    left: i32,
    /// Top edge.
    top: i32,
    /// Width.
    width: NonZeroU32,
    /// Height.
    height: NonZeroU32,
}

impl Area {
    /// The area of a Windows rectangle, or `None` if it is empty.
    fn of(rect: &RECT) -> Option<Self> {
        let width = NonZeroU32::new(u32::try_from(rect.right.checked_sub(rect.left)?).ok()?)?;
        let height = NonZeroU32::new(u32::try_from(rect.bottom.checked_sub(rect.top)?).ok()?)?;
        Some(Self { left: rect.left, top: rect.top, width, height })
    }

    /// The pixel that sample coordinates (0 to 65535 on each axis) map to: the corners of the
    /// drawing area are the corner pixels of the area.
    fn pixel(&self, x: u16, y: u16) -> POINT {
        POINT { x: self.left + scale(x, self.width), y: self.top + scale(y, self.height) }
    }
}

/// Maps 0..=65535 onto the pixels 0..=size-1, rounding to the nearest one.
fn scale(value: u16, size: NonZeroU32) -> i32 {
    let last = u64::from(size.get() - 1);
    let pixel = (u64::from(value) * last + u64::from(SAMPLE_MAX) / 2) / u64::from(SAMPLE_MAX);
    // At most `size - 1`, which came from an i32 difference.
    i32::try_from(pixel).unwrap_or(i32::MAX)
}

/// A monitor the tablet can draw on.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Monitor {
    /// Name Windows gives it, such as `DISPLAY1`.
    name: String,
    /// Where it is on the desktop.
    area: Area,
    /// Whether it is the primary monitor.
    primary: bool,
}

/// Lists the monitors: the primary one first, then from left to right.
fn monitors() -> Vec<Monitor> {
    let mut found: Vec<Monitor> = Vec::new();
    // SAFETY: the callback only runs during the call and receives `found` back as its data.
    unsafe {
        EnumDisplayMonitors(std::ptr::null_mut(), std::ptr::null(), Some(collect_monitor), std::ptr::from_mut(&mut found) as LPARAM);
    }
    found.sort_by_key(|monitor| (!monitor.primary, monitor.area.left, monitor.area.top));
    found
}

/// Adds one monitor to the list passed as `data`. Called by `EnumDisplayMonitors`.
unsafe extern "system" fn collect_monitor(monitor: HMONITOR, _hdc: HDC, _clip: *mut RECT, data: LPARAM) -> BOOL {
    // SAFETY: `data` is the `Vec<Monitor>` that `monitors` passed, alive during the call.
    let found = unsafe { &mut *(data as *mut Vec<Monitor>) };
    // SAFETY: MONITORINFOEXW is a plain C struct; its size is set before the call.
    let mut info: MONITORINFOEXW = unsafe { zeroed() };
    info.monitorInfo.cbSize = u32::try_from(size_of::<MONITORINFOEXW>()).unwrap_or(0);
    // SAFETY: `monitor` comes from Windows and `info` is large enough for the extended record.
    if unsafe { GetMonitorInfoW(monitor, std::ptr::from_mut(&mut info).cast()) } != 0
        && let Some(area) = Area::of(&info.monitorInfo.rcMonitor)
    {
        let len = info.szDevice.iter().position(|&c| c == 0).unwrap_or(info.szDevice.len());
        let name = String::from_utf16_lossy(&info.szDevice[..len]);
        found.push(Monitor {
            name: name.trim_start_matches(r"\\.\").to_owned(),
            area,
            primary: info.monitorInfo.dwFlags & MONITORINFOF_PRIMARY != 0,
        });
    }
    1
}

#[cfg(test)]
mod tests {
    //! Mapping onto a monitor and the pointer events of each pen movement.

    use super::*;

    /// A 1920 x 1080 monitor to the right of the primary one.
    fn second_monitor() -> Area {
        Area::of(&RECT { left: 1920, top: 0, right: 3840, bottom: 1080 }).unwrap_or_else(|| panic!("valid area"))
    }

    /// The corners of the drawing area land on the corner pixels, and the middle in the middle.
    #[test]
    fn samples_map_onto_the_monitor() {
        let area = second_monitor();

        assert_eq!((area.pixel(0, 0).x, area.pixel(0, 0).y), (1920, 0));
        assert_eq!((area.pixel(u16::MAX, u16::MAX).x, area.pixel(u16::MAX, u16::MAX).y), (3839, 1079));
        let middle = area.pixel(32768, 32768);
        assert_eq!((middle.x, middle.y), (1920 + 960, 540));
    }

    /// An empty or inverted rectangle is no area.
    #[test]
    fn empty_rectangles_are_rejected() {
        assert_eq!(Area::of(&RECT { left: 0, top: 0, right: 0, bottom: 1080 }), None);
        assert_eq!(Area::of(&RECT { left: 10, top: 0, right: 0, bottom: 1080 }), None);
    }

    /// Pressure spans Windows' 0 to 1024.
    #[test]
    fn pressure_spans_the_windows_range() {
        assert_eq!(pressure(0), 0);
        assert_eq!(pressure(u16::MAX), MAX_PRESSURE);
        assert_eq!(pressure(32768), 512);
    }

    /// A stroke from out of range: hover, touch down, draw, lift, leave.
    #[test]
    fn a_stroke_produces_the_pen_events_in_order() {
        let away = PenState::default();
        let hovering = PenState { in_range: true, in_contact: false };
        let touching = PenState { in_range: true, in_contact: true };

        assert_eq!(steps(away, hovering), vec![POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE | POINTER_FLAG_UPDATE]);
        let down = steps(hovering, touching);
        assert_eq!(down.len(), 1);
        assert_ne!(down[0] & POINTER_FLAG_DOWN, 0);
        assert_ne!(down[0] & POINTER_FLAG_INCONTACT, 0);
        let drawing = steps(touching, touching);
        assert_ne!(drawing[0] & POINTER_FLAG_UPDATE, 0);
        assert_ne!(drawing[0] & POINTER_FLAG_INCONTACT, 0);
        let up = steps(touching, hovering);
        assert_eq!(up, vec![POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE | POINTER_FLAG_UP]);
        let leave = steps(hovering, away);
        assert_eq!(leave, vec![POINTER_FLAG_PRIMARY | POINTER_FLAG_UPDATE]);
        assert!(steps(away, away).is_empty());
    }

    /// Touching down straight from out of range hovers first; lifting straight out of range
    /// lifts first.
    #[test]
    fn jumps_get_the_missing_step() {
        let away = PenState::default();
        let touching = PenState { in_range: true, in_contact: true };

        let down = steps(away, touching);
        assert_eq!(down.len(), 2);
        assert_eq!(down[0] & POINTER_FLAG_INCONTACT, 0);
        assert_ne!(down[1] & POINTER_FLAG_DOWN, 0);
        let up = steps(touching, away);
        assert_eq!(up.len(), 2);
        assert_ne!(up[0] & POINTER_FLAG_UP, 0);
        assert_eq!(up[1] & POINTER_FLAG_INRANGE, 0);
    }

    /// The eraser end is "inverted" while hovering and erases only while touching.
    #[test]
    fn the_eraser_erases_only_while_touching() {
        let sample = |in_contact| PenSample {
            time_ms: 0,
            x: 0,
            y: 0,
            pressure: 0,
            tilt_x: 0,
            tilt_y: 0,
            in_contact,
            in_range: true,
            barrel: false,
            eraser: true,
        };

        assert_eq!(pen_flags(&sample(false)), PEN_FLAG_INVERTED);
        assert_eq!(pen_flags(&sample(true)), PEN_FLAG_INVERTED | PEN_FLAG_ERASER);
    }

    /// Lists the pointer devices Windows knows, with the screen area each one maps to. Run
    /// with `cargo test pointer_devices -- --ignored --nocapture`.
    #[test]
    #[ignore = "diagnostic output only"]
    fn pointer_devices() {
        use windows_sys::Win32::UI::Controls::POINTER_DEVICE_INFO;
        use windows_sys::Win32::UI::Input::Pointer::{GetPointerDeviceRects, GetPointerDevices};

        use_physical_pixels();
        let _pen = SyntheticPen::create().unwrap_or_else(|| panic!("no synthetic pen"));
        let mut count = 0u32;
        // SAFETY: a null buffer asks only for the count.
        unsafe { GetPointerDevices(&mut count, std::ptr::null_mut()) };
        // SAFETY: plain C structs, filled by the call below.
        let mut devices: Vec<POINTER_DEVICE_INFO> = (0..count).map(|_| unsafe { zeroed() }).collect();
        // SAFETY: the buffer holds `count` records.
        unsafe { GetPointerDevices(&mut count, devices.as_mut_ptr()) };
        for device in &devices[..count as usize] {
            let mut pointer_rect = RECT { left: 0, top: 0, right: 0, bottom: 0 };
            let mut display_rect = pointer_rect;
            // SAFETY: the handle comes from Windows and both rectangles receive values.
            unsafe { GetPointerDeviceRects(device.device, &mut pointer_rect, &mut display_rect) };
            let len = device.productString.iter().position(|&c| c == 0).unwrap_or(0);
            println!(
                "type={} monitor={:?} name={:?} device=({}, {})-({}, {}) display=({}, {})-({}, {})",
                device.pointerDeviceType,
                device.monitor,
                String::from_utf16_lossy(&device.productString[..len]),
                pointer_rect.left,
                pointer_rect.top,
                pointer_rect.right,
                pointer_rect.bottom,
                display_rect.left,
                display_rect.top,
                display_rect.right,
                display_rect.bottom,
            );
        }
    }

    /// With a monitor to the left of and above the primary one, the primary monitor's corner is
    /// that far into the virtual screen, and the left monitor starts at its corner.
    #[test]
    fn points_are_measured_from_the_virtual_screen() {
        let origin = POINT { x: -1920, y: -61 };

        let primary = relative_to(POINT { x: 0, y: 0 }, origin);
        assert_eq!((primary.x, primary.y), (1920, 61));
        let left = relative_to(POINT { x: -1920, y: -61 }, origin);
        assert_eq!((left.x, left.y), (0, 0));
    }
}
