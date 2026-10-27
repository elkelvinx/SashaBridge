//! Wire formats between the phone and the PC.
//!
//! [`v1`] is the format of the current phone app and [`v2`] the typed protocol specified
//! in `docs/protocolo-v2.md`. Both coexist until phase 7; the PC tells them apart by the
//! `type` field that every v2 message carries.

pub mod v1;
pub mod v2;

use anyhow::Context;

pub use v1::{MediaUpdate, PcCommand, parse_phone_message};

/// Text frame from the phone, decoded with the protocol version it uses.
#[allow(dead_code, reason = "used by the connection from phase 4 on")]
#[derive(Debug, Clone, PartialEq)]
pub enum PhoneFrame {
    /// Message of the current phone app.
    V1(MediaUpdate),
    /// Typed message.
    V2(v2::PhoneMessage),
}

/// Decodes a text frame from the phone: v2 when it is a JSON object with a `type` field,
/// v1 otherwise.
///
/// # Errors
///
/// Fails when the frame is not JSON or is not a valid message of the detected version.
#[allow(dead_code, reason = "used by the connection from phase 4 on")]
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

    /// The hello fixtures announce the version this crate implements.
    #[test]
    fn hello_fixtures_use_the_current_version() {
        let (_, text) = fixtures("pc-to-phone/valid")
            .into_iter()
            .find(|(name, _)| name == "hello.json")
            .unwrap();
        let PcMessage::Hello(hello) = serde_json::from_str(&text).unwrap() else {
            panic!("hello.json must be a hello message");
        };
        assert_eq!(hello.protocol.get(), v2::PROTOCOL_VERSION);
    }
}
