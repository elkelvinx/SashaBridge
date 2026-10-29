//! Conversions between the image formats of the annotation mode.
//!
//! The Windows clipboard holds captures as a DIB (a `BITMAPINFOHEADER` followed by the
//! pixels, usually bottom-up BGR or BGRA rows), while the protocol carries PNG. Everything
//! here is pure, so it is tested without a clipboard.

use std::io::Cursor;

use anyhow::{Context, bail, ensure};

/// An image as 8-bit RGBA pixels, top row first.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rgba {
    /// Width in pixels.
    pub width: u32,
    /// Height in pixels.
    pub height: u32,
    /// `width * height * 4` bytes: red, green, blue and alpha of each pixel.
    pub pixels: Vec<u8>,
}

/// Size of a `BITMAPINFOHEADER`.
const INFO_HEADER_LEN: usize = 40;

/// `biCompression` of uncompressed pixels.
const BI_RGB: u32 = 0;

/// `biCompression` of uncompressed pixels with color masks after the header.
const BI_BITFIELDS: u32 = 3;

/// Reads a packed DIB, as the clipboard's `CF_DIB` format holds it.
///
/// Supports the uncompressed 24- and 32-bit images that screenshots produce. In a 32-bit
/// `BI_RGB` image the fourth byte is not alpha, so the result is opaque.
///
/// # Errors
///
/// Fails on a truncated DIB, or on a format other than uncompressed 24 or 32 bits.
pub fn dib_to_rgba(dib: &[u8]) -> anyhow::Result<Rgba> {
    ensure!(dib.len() >= INFO_HEADER_LEN, "DIB too short");
    let u32_at = |at: usize| u32::from_le_bytes([dib[at], dib[at + 1], dib[at + 2], dib[at + 3]]);
    let header_len = u32_at(0) as usize;
    let width = i32::from_le_bytes(u32_at(4).to_le_bytes());
    let height = i32::from_le_bytes(u32_at(8).to_le_bytes());
    let bits = u16::from_le_bytes([dib[14], dib[15]]);
    let compression = u32_at(16);
    let colors_used = u32_at(32);
    ensure!(header_len >= INFO_HEADER_LEN, "unknown DIB header of {header_len} bytes");
    ensure!(width > 0 && height != 0, "empty DIB");
    if !matches!((bits, compression), (24, BI_RGB) | (32, BI_RGB) | (32, BI_BITFIELDS)) {
        bail!("unsupported DIB: {bits} bits, compression {compression}");
    }

    // A plain BITMAPINFOHEADER keeps the three color masks of BI_BITFIELDS after it; the
    // larger headers include them. Images of 24 or 32 bits normally have no palette.
    let masks = if compression == BI_BITFIELDS && header_len == INFO_HEADER_LEN { 12 } else { 0 };
    let offset = header_len + masks + colors_used as usize * 4;
    let width = width.unsigned_abs();
    let bottom_up = height > 0;
    let height = height.unsigned_abs();
    let bytes_per_pixel = usize::from(bits / 8);
    let stride = (width as usize * usize::from(bits)).div_ceil(32) * 4;
    ensure!(dib.len() >= offset + stride * height as usize, "DIB pixels truncated");

    let mut pixels = Vec::with_capacity(width as usize * height as usize * 4);
    for row in 0..height as usize {
        let source_row = if bottom_up { height as usize - 1 - row } else { row };
        let start = offset + source_row * stride;
        for pixel in dib[start..start + width as usize * bytes_per_pixel].chunks_exact(bytes_per_pixel) {
            pixels.extend_from_slice(&[pixel[2], pixel[1], pixel[0], u8::MAX]);
        }
    }
    Ok(Rgba { width, height, pixels })
}

/// Writes `image` as a packed 32-bit bottom-up DIB, the most widely read clipboard bitmap.
pub fn rgba_to_dib(image: &Rgba) -> Vec<u8> {
    let width = image.width as usize;
    let height = image.height as usize;
    let mut dib = Vec::with_capacity(INFO_HEADER_LEN + width * height * 4);
    dib.extend_from_slice(&(INFO_HEADER_LEN as u32).to_le_bytes());
    dib.extend_from_slice(&(image.width as i32).to_le_bytes());
    dib.extend_from_slice(&(image.height as i32).to_le_bytes());
    dib.extend_from_slice(&1u16.to_le_bytes()); // planes
    dib.extend_from_slice(&32u16.to_le_bytes()); // bits per pixel
    dib.extend_from_slice(&BI_RGB.to_le_bytes());
    dib.extend_from_slice(&((width * height * 4) as u32).to_le_bytes());
    dib.extend_from_slice(&[0; 16]); // resolution and palette: unused
    for row in (0..height).rev() {
        for pixel in image.pixels[row * width * 4..(row + 1) * width * 4].chunks_exact(4) {
            dib.extend_from_slice(&[pixel[2], pixel[1], pixel[0], pixel[3]]);
        }
    }
    dib
}

/// Encodes `image` as PNG.
///
/// # Errors
///
/// Fails when the PNG encoder rejects the image, which only happens for a pixel buffer of
/// the wrong size.
pub fn encode_png(image: &Rgba) -> anyhow::Result<Vec<u8>> {
    let mut png = Vec::new();
    let mut encoder = png::Encoder::new(&mut png, image.width, image.height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header().context("cannot start the PNG")?;
    writer.write_image_data(&image.pixels).context("cannot write the PNG")?;
    writer.finish().context("cannot finish the PNG")?;
    Ok(png)
}

/// Decodes an 8-bit RGB or RGBA PNG, as the phone produces.
///
/// # Errors
///
/// Fails when the bytes are not such a PNG.
pub fn decode_png(png: &[u8]) -> anyhow::Result<Rgba> {
    let mut decoder = png::Decoder::new(Cursor::new(png));
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut reader = decoder.read_info().context("not a PNG")?;
    let size = reader.output_buffer_size().context("PNG too large")?;
    let mut buffer = vec![0; size];
    let info = reader.next_frame(&mut buffer).context("cannot read the PNG")?;
    buffer.truncate(info.buffer_size());
    let pixels = match info.color_type {
        png::ColorType::Rgba => buffer,
        png::ColorType::Rgb => buffer.chunks_exact(3).flat_map(|p| [p[0], p[1], p[2], u8::MAX]).collect(),
        png::ColorType::GrayscaleAlpha => buffer.chunks_exact(2).flat_map(|p| [p[0], p[0], p[0], p[1]]).collect(),
        png::ColorType::Grayscale => buffer.iter().flat_map(|&g| [g, g, g, u8::MAX]).collect(),
        png::ColorType::Indexed => bail!("indexed PNG left unexpanded"),
    };
    Ok(Rgba { width: info.width, height: info.height, pixels })
}

#[cfg(test)]
mod tests {
    //! Round trips between the formats, with tiny images whose every pixel is known.

    use super::*;

    /// A 3×2 image with a distinct color in each pixel.
    fn sample() -> Rgba {
        #[rustfmt::skip]
        let pixels = vec![
            255, 0, 0, 255,   0, 255, 0, 255,   0, 0, 255, 255,
            10, 20, 30, 255,  40, 50, 60, 255,  70, 80, 90, 255,
        ];
        Rgba { width: 3, height: 2, pixels }
    }

    /// An image survives a trip through a DIB, rows flipped back to top-first.
    #[test]
    fn dib_round_trip() {
        assert_eq!(dib_to_rgba(&rgba_to_dib(&sample())).unwrap(), sample());
    }

    /// A 24-bit bottom-up DIB with padded rows, as screenshots produce, reads correctly.
    #[test]
    fn reads_24_bit_padded_rows() {
        let mut dib = rgba_to_dib(&sample())[..INFO_HEADER_LEN].to_vec();
        dib[14] = 24;
        // Rows of 3 pixels × 3 bytes = 9 bytes, padded to 12; the bottom row comes first.
        dib.extend_from_slice(&[30, 20, 10, 60, 50, 40, 90, 80, 70, 0, 0, 0]);
        dib.extend_from_slice(&[0, 0, 255, 0, 255, 0, 255, 0, 0, 0, 0, 0]);

        assert_eq!(dib_to_rgba(&dib).unwrap(), sample());
    }

    /// An image survives a trip through PNG.
    #[test]
    fn png_round_trip() {
        assert_eq!(decode_png(&encode_png(&sample()).unwrap()).unwrap(), sample());
    }

    /// Truncated or unsupported DIBs are rejected instead of read past their end.
    #[test]
    fn rejects_bad_dibs() {
        let dib = rgba_to_dib(&sample());
        assert!(dib_to_rgba(&dib[..20]).is_err());
        assert!(dib_to_rgba(&dib[..dib.len() - 1]).is_err());
        let mut eight_bit = dib.clone();
        eight_bit[14] = 8;
        assert!(dib_to_rgba(&eight_bit).is_err());
    }
}
