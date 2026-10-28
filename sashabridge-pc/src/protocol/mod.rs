//! Wire formats between the phone and the PC.
//!
//! [`v1`] is the format of the phone app before phase 5 and [`v2`] the typed protocol
//! specified in `docs/protocolo-v2.md`. Both coexist until phase 7:
//! - the PC tells incoming messages apart by the `type` field that every v2 message carries;
//! - the PC sends v2 commands only after the phone has announced v2 with its `hello`, and
//!   the v1 text commands until then ([`PcCommand::encode`]).

pub mod v1;
pub mod v2;

use std::time::Duration;

use anyhow::Context;

pub use v1::MediaUpdate;

/// Text frame from the phone, decoded with the protocol version it uses.
#[derive(Debug, Clone, PartialEq)]
pub enum PhoneFrame {
    /// Message of a v1 phone app.
    V1(MediaUpdate),
    /// Typed message.
    V2(v2::PhoneMessage),
}

/// Protocol that the connected phone speaks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PhoneProtocol {
    /// No `hello` received: the phone only understands v1 text commands.
    V1,
    /// The phone sent a v2 `hello`.
    V2,
}

/// Order from the PC for the phone's active player, independent of the protocol version.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PcCommand {
    /// Starts playback.
    Play,
    /// Pauses playback.
    Pause,
    /// Skips to the next track.
    Next,
    /// Goes back to the previous track.
    Previous,
    /// Moves the playback position.
    Seek(Duration),
    /// Presses the player's shuffle button.
    ToggleShuffle,
}

impl PcCommand {
    /// Encodes the command for a phone that speaks `protocol`.
    ///
    /// v1 has a single play/pause toggle, so `Play` and `Pause` both become `PLAY_PAUSE`
    /// there (A10 is only fixed with v2), and it has no shuffle command.
    ///
    /// Returns `None` when `protocol` cannot express the command.
    pub fn encode(self, protocol: PhoneProtocol) -> Option<String> {
        match protocol {
            PhoneProtocol::V1 => self.to_v1_text(),
            // Serializing these types cannot fail: every field is a plain value.
            PhoneProtocol::V2 => serde_json::to_string(&v2::PcMessage::Cmd(self.to_v2())).ok(),
        }
    }

    /// Encodes the command as a v1 text frame, if v1 has one.
    fn to_v1_text(self) -> Option<String> {
        let text = match self {
            Self::Play | Self::Pause => "PLAY_PAUSE".to_owned(),
            Self::Next => "NEXT".to_owned(),
            Self::Previous => "PREV".to_owned(),
            Self::Seek(position) => format!("SEEK:{}", position.as_millis()),
            Self::ToggleShuffle => return None,
        };
        Some(text)
    }

    /// Converts the command to its v2 form.
    fn to_v2(self) -> v2::Command {
        match self {
            Self::Play => v2::Command::Play,
            Self::Pause => v2::Command::Pause,
            Self::Next => v2::Command::Next,
            Self::Previous => v2::Command::Previous,
            Self::Seek(position) => v2::Command::Seek {
                position_ms: u64::try_from(position.as_millis()).unwrap_or(u64::MAX),
            },
            Self::ToggleShuffle => v2::Command::ToggleShuffle,
        }
    }
}

/// Returns the `hello` the PC sends when a session opens.
///
/// A v1 phone app treats it as an unknown command and ignores it.
pub fn pc_hello() -> String {
    let hello = v2::PcMessage::Hello(v2::PcHello {
        protocol: v2::PROTOCOL_VERSION,
        client: format!("sashabridge-pc/{}", env!("CARGO_PKG_VERSION")),
    });
    // Serializing these types cannot fail: every field is a plain value.
    serde_json::to_string(&hello).unwrap_or_default()
}

/// Decodes a text frame from the phone: v2 when it is a JSON object with a `type` field,
/// v1 otherwise.
///
/// # Errors
///
/// Fails when the frame is not JSON or is not a valid message of the detected version.
pub fn decode_phone_frame(text: &str) -> anyhow::Result<PhoneFrame> {
    let value: serde_json::Value = serde_json::from_str(text).context("frame is not JSON")?;
    if value.get("type").is_some() {
        serde_json::from_value(value).map(PhoneFrame::V2).context("invalid v2 message")
    } else {
        v1::from_value(value).map(PhoneFrame::V1)
    }
}

#[cfg(test)]
mod tests {
    //! Conformance with the shared fixtures in `protocol/fixtures`.
    //!
    //! The fixtures are read when the tests run, so a new fixture is covered without
    //! changing this file. The Kotlin tests read the same directories.

    use std::fmt::Debug;
    use std::fs;
    use std::path::{Path, PathBuf};

    use serde::Serialize;
    use serde::de::DeserializeOwned;

    use super::v2::{PcMessage, PhoneMessage};
    use super::*;

    /// Returns the name and content of every JSON fixture in `dir`, sorted by name.
    ///
    /// Panics if the directory has no fixtures, so that a wrong path cannot pass silently.
    fn fixtures(dir: &str) -> Vec<(String, String)> {
        let path: PathBuf =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../protocol/fixtures").join(dir);
        let mut fixtures: Vec<(String, String)> = fs::read_dir(&path)
            .unwrap_or_else(|error| panic!("cannot list {}: {error}", path.display()))
            .map(|entry| entry.unwrap().path())
            .filter(|file| file.extension().is_some_and(|extension| extension == "json"))
            .map(|file| {
                let name = file.file_name().unwrap().to_string_lossy().into_owned();
                (name, fs::read_to_string(&file).unwrap())
            })
            .collect();
        assert!(!fixtures.is_empty(), "no fixtures in {}", path.display());
        fixtures.sort();
        fixtures
    }

    /// Checks that every fixture in `dir` decodes, re-encodes to the same JSON and decodes
    /// again to the same value.
    fn assert_round_trip<T>(dir: &str)
    where
        T: DeserializeOwned + Serialize + PartialEq + Debug,
    {
        for (name, text) in fixtures(dir) {
            let message: T = serde_json::from_str(&text)
                .unwrap_or_else(|error| panic!("{dir}/{name} must decode: {error}"));
            let encoded = serde_json::to_string(&message).unwrap();

            let original: serde_json::Value = serde_json::from_str(&text).unwrap();
            let reencoded: serde_json::Value = serde_json::from_str(&encoded).unwrap();
            assert_eq!(reencoded, original, "{dir}/{name} must re-encode to the same JSON");

            let decoded_again: T = serde_json::from_str(&encoded).unwrap();
            assert_eq!(decoded_again, message, "{dir}/{name} must survive a round trip");
        }
    }

    /// Checks that no fixture in `dir` decodes as `T`.
    fn assert_rejected<T: DeserializeOwned + Debug>(dir: &str) {
        for (name, text) in fixtures(dir) {
            let result = serde_json::from_str::<T>(&text);
            assert!(result.is_err(), "{dir}/{name} must be rejected, got {result:?}");
        }
    }

    /// Valid phone messages decode and survive a round trip.
    #[test]
    fn valid_phone_messages_round_trip() {
        assert_round_trip::<PhoneMessage>("phone-to-pc/valid");
    }

    /// Invalid phone messages are rejected.
    #[test]
    fn invalid_phone_messages_are_rejected() {
        assert_rejected::<PhoneMessage>("phone-to-pc/invalid");
    }

    /// Valid PC messages decode and survive a round trip.
    #[test]
    fn valid_pc_messages_round_trip() {
        assert_round_trip::<PcMessage>("pc-to-phone/valid");
    }

    /// Invalid PC messages are rejected.
    #[test]
    fn invalid_pc_messages_are_rejected() {
        assert_rejected::<PcMessage>("pc-to-phone/invalid");
    }

    /// Frames with a `type` field are decoded as v2 and frames without it as v1.
    #[test]
    fn frames_are_routed_by_the_type_field() {
        for (name, text) in fixtures("phone-to-pc/valid") {
            let frame = decode_phone_frame(&text).unwrap();
            assert!(matches!(frame, PhoneFrame::V2(_)), "{name} must be v2");
        }
        for (name, text) in fixtures("phone-to-pc/legacy-v1") {
            let frame = decode_phone_frame(&text).unwrap();
            assert!(matches!(frame, PhoneFrame::V1(_)), "{name} must be v1");
        }
        assert!(decode_phone_frame("NEXT").is_err());
    }

    /// Returns the content of one fixture.
    fn fixture(dir: &str, file: &str) -> String {
        fixtures(dir)
            .into_iter()
            .find(|(name, _)| name == file)
            .unwrap_or_else(|| panic!("{dir}/{file} must exist"))
            .1
    }

    /// Parses JSON text, so that comparisons ignore field order.
    fn json(text: &str) -> serde_json::Value {
        serde_json::from_str(text).unwrap()
    }

    /// The hello the PC sends announces the version this crate implements, like the fixture.
    #[test]
    fn pc_hello_announces_the_current_version() {
        let PcMessage::Hello(hello) = serde_json::from_str(&pc_hello()).unwrap() else {
            panic!("pc_hello must be a hello message");
        };
        assert_eq!(hello.protocol, v2::PROTOCOL_VERSION);
        assert!(hello.client.starts_with("sashabridge-pc/"));

        let PcMessage::Hello(fixture_hello) =
            serde_json::from_str(&fixture("pc-to-phone/valid", "hello.json")).unwrap()
        else {
            panic!("hello.json must be a hello message");
        };
        assert_eq!(fixture_hello.protocol, v2::PROTOCOL_VERSION);
    }

    /// Against a v2 phone, the commands are exactly the v2 fixtures; PLAY and PAUSE are separate (A10).
    #[test]
    fn v2_commands_match_the_fixtures() {
        let cases = [
            (PcCommand::Play, "cmd-play.json"),
            (PcCommand::Pause, "cmd-pause.json"),
            (PcCommand::Next, "cmd-next.json"),
            (PcCommand::Previous, "cmd-previous.json"),
            (PcCommand::Seek(Duration::from_secs(60)), "cmd-seek.json"),
            (PcCommand::ToggleShuffle, "cmd-toggle-shuffle.json"),
        ];
        for (command, file) in cases {
            let encoded = command.encode(PhoneProtocol::V2).unwrap();
            assert_eq!(json(&encoded), json(&fixture("pc-to-phone/valid", file)), "{command:?}");
        }
    }

    /// Against a v1 phone, the commands are the text frames it recognizes, and shuffle is not sent.
    #[test]
    fn v1_commands_are_text_frames() {
        let encode = |command: PcCommand| command.encode(PhoneProtocol::V1);
        assert_eq!(encode(PcCommand::Play).as_deref(), Some("PLAY_PAUSE"));
        assert_eq!(encode(PcCommand::Pause).as_deref(), Some("PLAY_PAUSE"));
        assert_eq!(encode(PcCommand::Next).as_deref(), Some("NEXT"));
        assert_eq!(encode(PcCommand::Previous).as_deref(), Some("PREV"));
        assert_eq!(encode(PcCommand::Seek(Duration::from_secs(60))).as_deref(), Some("SEEK:60000"));
        assert_eq!(encode(PcCommand::ToggleShuffle), None);
    }
}
