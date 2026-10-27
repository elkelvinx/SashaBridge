//! Wire format between the phone and the PC (protocol v1).
//!
//! v1 has no message type: every phone message is a JSON object with the current media
//! state, and every PC command is a plain text frame such as `NEXT` or `SEEK:60000`.

use std::time::Duration;

use anyhow::Context;
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use serde::Deserialize;

/// Media state reported by the phone, already decoded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaUpdate {
    /// Track title, if the player reports one.
    pub title: Option<String>,
    /// Track artist, if the player reports one.
    pub artist: Option<String>,
    /// Track length. `None` when the player reports zero or a negative value.
    pub duration: Option<Duration>,
    /// Playback position when the phone built the message. Unknown positions become zero.
    pub position: Duration,
    /// Whether the player is in the playing state.
    pub is_playing: bool,
    /// JPEG bytes of the album art. v1 only sends it when the track changes.
    pub album_art: Option<Vec<u8>>,
}

/// JSON object sent by the v1 server, before validation.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireMediaUpdate {
    /// Track title.
    title: Option<String>,
    /// Track artist.
    artist: Option<String>,
    /// Track length in milliseconds; the server sends `0` when it is unknown.
    #[serde(default)]
    duration: i64,
    /// Whether the player is in the playing state.
    #[serde(default)]
    is_playing: bool,
    /// Playback position in milliseconds; Android reports `-1` when it is unknown.
    #[serde(default)]
    position: i64,
    /// Base64 JPEG of the album art.
    album_art: Option<String>,
}

/// Parses a text frame from the phone.
///
/// # Errors
///
/// Fails when the frame is not a JSON object with the v1 fields or when `albumArt`
/// is not valid base64.
pub fn parse_phone_message(text: &str) -> anyhow::Result<MediaUpdate> {
    let wire: WireMediaUpdate = serde_json::from_str(text).context("invalid v1 media message")?;
    let album_art = wire
        .album_art
        .map(|encoded| STANDARD.decode(encoded))
        .transpose()
        .context("invalid base64 in albumArt")?;

    Ok(MediaUpdate {
        title: wire.title,
        artist: wire.artist,
        duration: positive_millis(wire.duration),
        position: positive_millis(wire.position).unwrap_or_default(),
        is_playing: wire.is_playing,
        album_art,
    })
}

/// Converts a millisecond count to a [`Duration`], treating zero and negative values as unknown.
fn positive_millis(millis: i64) -> Option<Duration> {
    u64::try_from(millis).ok().filter(|&millis| millis > 0).map(Duration::from_millis)
}

/// Command sent from the PC to the phone's active player.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PcCommand {
    /// Toggles between play and pause.
    PlayPause,
    /// Skips to the next track.
    Next,
    /// Goes back to the previous track.
    Previous,
    /// Moves the playback position.
    Seek(Duration),
}

impl PcCommand {
    /// Encodes the command as a v1 text frame.
    pub fn to_v1_text(self) -> String {
        match self {
            Self::PlayPause => "PLAY_PAUSE".to_owned(),
            Self::Next => "NEXT".to_owned(),
            Self::Previous => "PREV".to_owned(),
            Self::Seek(position) => format!("SEEK:{}", position.as_millis()),
        }
    }
}

#[cfg(test)]
mod tests {
    //! Parsing of real v1 messages and encoding of the commands the v1 server accepts.

    use super::*;

    /// A full message, as Spotify produces it on a track change.
    #[test]
    fn parses_message_with_album_art() {
        let text = r#"{"title":"The Night and the Lie","artist":"Ado","duration":215000,
            "isPlaying":true,"position":12345,"albumArt":"/9j/4A=="}"#;

        let update = parse_phone_message(text).unwrap();

        assert_eq!(update.title.as_deref(), Some("The Night and the Lie"));
        assert_eq!(update.artist.as_deref(), Some("Ado"));
        assert_eq!(update.duration, Some(Duration::from_millis(215_000)));
        assert_eq!(update.position, Duration::from_millis(12_345));
        assert!(update.is_playing);
        assert_eq!(update.album_art, Some(vec![0xFF, 0xD8, 0xFF, 0xE0]));
    }

    /// Unknown duration (`0`) and unknown position (`-1`) do not become real values.
    #[test]
    fn treats_zero_duration_and_negative_position_as_unknown() {
        let text = r#"{"title":"t","artist":"a","duration":0,"isPlaying":false,"position":-1}"#;

        let update = parse_phone_message(text).unwrap();

        assert_eq!(update.duration, None);
        assert_eq!(update.position, Duration::ZERO);
        assert_eq!(update.album_art, None);
    }

    /// Frames that are not v1 media messages are rejected instead of shown as blank tracks.
    #[test]
    fn rejects_malformed_messages() {
        assert!(parse_phone_message("NEXT").is_err());
        assert!(parse_phone_message(r#"{"title":"t","albumArt":"not base64!"}"#).is_err());
    }

    /// The text frames match the ones `MusicListenerService` recognizes.
    #[test]
    fn encodes_v1_commands() {
        assert_eq!(PcCommand::PlayPause.to_v1_text(), "PLAY_PAUSE");
        assert_eq!(PcCommand::Next.to_v1_text(), "NEXT");
        assert_eq!(PcCommand::Previous.to_v1_text(), "PREV");
        assert_eq!(PcCommand::Seek(Duration::from_secs(60)).to_v1_text(), "SEEK:60000");
    }
}
