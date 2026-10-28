//! Binary frames of S Pen samples, sent by the phone on the `/pen` route in tablet mode.
//!
//! The specification is the "Muestras del lápiz" section of `docs/protocolo-v2.md`. JSON
//! would cost several times the bytes at the pen's ~240 samples per second, so each sample is
//! 14 little-endian bytes after a 4-byte header.

use anyhow::{bail, ensure};

/// First byte of a frame of pen samples.
const KIND: u8 = 0x50;

/// Format version in the second byte.
const VERSION: u8 = 1;

/// Size of the frame header: kind, version and sample count.
const HEADER_LEN: usize = 4;

/// Size of one sample.
const SAMPLE_LEN: usize = 14;

/// Bits of [`PenSample`]'s flags byte.
mod flag {
    /// The tip touches the screen.
    pub const IN_CONTACT: u8 = 1;
    /// The pen is within hover range, touching or not.
    pub const IN_RANGE: u8 = 2;
    /// The side button is pressed.
    pub const BARREL: u8 = 4;
    /// The pen erases.
    pub const ERASER: u8 = 8;
}

/// One reading of the S Pen.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code, reason = "read by the tablet mode, phase 3 of SashaBridge 2.1")]
pub struct PenSample {
    /// Phone uptime in milliseconds, wrapping at `u32::MAX`; only differences matter.
    pub time_ms: u32,
    /// Horizontal position over the drawing surface, from 0 (left) to 65535 (right).
    pub x: u16,
    /// Vertical position over the drawing surface, from 0 (top) to 65535 (bottom).
    pub y: u16,
    /// Pressure from 0 (none) to 65535 (full).
    pub pressure: u16,
    /// Tilt towards the right, in degrees from -90 to 90.
    pub tilt_x: i8,
    /// Tilt towards the user, in degrees from -90 to 90.
    pub tilt_y: i8,
    /// Whether the tip touches the screen.
    pub in_contact: bool,
    /// Whether the pen is within hover range.
    pub in_range: bool,
    /// Whether the side button is pressed.
    pub barrel: bool,
    /// Whether the pen erases.
    pub eraser: bool,
}

/// Decodes a binary frame of pen samples.
///
/// # Errors
///
/// Fails when the frame is not a pen-sample frame of a known version, or when its length
/// does not match the sample count.
#[allow(dead_code, reason = "used by the tablet mode, phase 3 of SashaBridge 2.1")]
pub fn decode(frame: &[u8]) -> anyhow::Result<Vec<PenSample>> {
    ensure!(frame.len() >= HEADER_LEN, "pen frame too short: {} bytes", frame.len());
    if frame[0] != KIND {
        bail!("not a pen-sample frame: kind {:#04x}", frame[0]);
    }
    ensure!(frame[1] == VERSION, "unknown pen-sample format version {}", frame[1]);
    let count = usize::from(u16::from_le_bytes([frame[2], frame[3]]));
    let body = &frame[HEADER_LEN..];
    ensure!(
        body.len() == count * SAMPLE_LEN,
        "pen frame announces {count} samples but carries {} bytes",
        body.len()
    );
    Ok(body.chunks_exact(SAMPLE_LEN).map(sample).collect())
}

/// Reads one sample from its 14 bytes.
fn sample(bytes: &[u8]) -> PenSample {
    let u16_at = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]);
    let flags = bytes[12];
    PenSample {
        time_ms: u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
        x: u16_at(4),
        y: u16_at(6),
        pressure: u16_at(8),
        tilt_x: i8::from_le_bytes([bytes[10]]),
        tilt_y: i8::from_le_bytes([bytes[11]]),
        in_contact: flags & flag::IN_CONTACT != 0,
        in_range: flags & flag::IN_RANGE != 0,
        barrel: flags & flag::BARREL != 0,
        eraser: flags & flag::ERASER != 0,
    }
}

#[cfg(test)]
mod tests {
    //! Decoding of the shared binary fixture.

    use std::fs;
    use std::path::Path;

    use serde::Deserialize;

    use super::*;

    /// One sample as written in `samples.json`.
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Expected {
        /// See [`PenSample::time_ms`].
        time_ms: u32,
        /// See [`PenSample::x`].
        x: u16,
        /// See [`PenSample::y`].
        y: u16,
        /// See [`PenSample::pressure`].
        pressure: u16,
        /// See [`PenSample::tilt_x`].
        tilt_x: i8,
        /// See [`PenSample::tilt_y`].
        tilt_y: i8,
        /// See [`PenSample::in_contact`].
        in_contact: bool,
        /// See [`PenSample::in_range`].
        in_range: bool,
        /// See [`PenSample::barrel`].
        barrel: bool,
        /// See [`PenSample::eraser`].
        eraser: bool,
    }

    /// Contents of `samples.json`.
    #[derive(Deserialize)]
    struct ExpectedFrame {
        /// The samples in order.
        samples: Vec<Expected>,
    }

    /// Reads a file of `protocol/fixtures/pen-samples`.
    fn fixture(name: &str) -> Vec<u8> {
        let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("../protocol/fixtures/pen-samples").join(name);
        fs::read(&path).unwrap_or_else(|error| panic!("cannot read {}: {error}", path.display()))
    }

    /// The binary fixture decodes to exactly the samples its JSON describes.
    #[test]
    fn decodes_the_shared_fixture() {
        let expected: ExpectedFrame = serde_json::from_slice(&fixture("samples.json")).unwrap();
        let expected: Vec<PenSample> = expected
            .samples
            .into_iter()
            .map(|e| PenSample {
                time_ms: e.time_ms,
                x: e.x,
                y: e.y,
                pressure: e.pressure,
                tilt_x: e.tilt_x,
                tilt_y: e.tilt_y,
                in_contact: e.in_contact,
                in_range: e.in_range,
                barrel: e.barrel,
                eraser: e.eraser,
            })
            .collect();

        assert_eq!(decode(&fixture("samples.bin")).unwrap(), expected);
    }

    /// Frames of another kind, another version or the wrong length are rejected.
    #[test]
    fn rejects_malformed_frames() {
        let frame = fixture("samples.bin");
        assert!(decode(&frame[..3]).is_err(), "too short");
        assert!(decode(&[[0x51].as_slice(), &frame[1..]].concat()).is_err(), "other kind");
        assert!(decode(&[[KIND, 2].as_slice(), &frame[2..]].concat()).is_err(), "other version");
        assert!(decode(&frame[..frame.len() - 1]).is_err(), "truncated sample");
        assert_eq!(decode(&[KIND, VERSION, 0, 0]).unwrap(), Vec::new(), "an empty frame is valid");
    }
}
