//! Protocol v2: typed JSON messages with a mandatory `type` field.
//!
//! The specification is `docs/protocolo-v2.md`. The types mirror the wire format one to
//! one; constraints that the types cannot express (for example, `positionMs` only with
//! `SEEK`) are checked while decoding, so an invalid message never becomes a value.

use std::num::{NonZeroU32, NonZeroU64};

use serde::{Deserialize, Serialize};

/// Protocol version implemented by this module, announced in `hello`.
pub const PROTOCOL_VERSION: NonZeroU32 = match NonZeroU32::new(2) {
    Some(version) => version,
    None => unreachable!(),
};

/// Message from the phone to the PC.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum PhoneMessage {
    /// First message of every session.
    #[serde(rename = "hello")]
    Hello(PhoneHello),
    /// Image of an album art, referenced by [`MediaState::art_id`].
    #[serde(rename = "media.art")]
    MediaArt(MediaArt),
    /// Full state of the active player.
    #[serde(rename = "media.state")]
    MediaState(MediaState),
    /// No player is active.
    #[serde(rename = "media.cleared")]
    MediaCleared,
}

/// Greeting sent by the phone.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PhoneHello {
    /// Protocol version of the phone app.
    pub protocol: NonZeroU32,
    /// Phone model, for logging only.
    pub device: String,
}

/// Album art image.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaArt {
    /// Opaque identifier; equal identifiers always carry equal bytes.
    pub art_id: String,
    /// Media type of the image, currently always `image/jpeg`.
    pub mime: String,
    /// Image bytes in standard base64.
    pub data: String,
}

/// Full state of the active player. Each message replaces the previous one.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaState {
    /// Opaque identifier that changes when the track changes.
    pub track_id: String,
    /// Android package of the player, such as `com.spotify.music`.
    pub source: String,
    /// Track title.
    pub title: Option<String>,
    /// Track artist, without the playback context.
    pub artist: Option<String>,
    /// Context the player attaches to the artist, such as "Del Modo aleatorio inteligente".
    /// It depends on the phone's language, so it is only displayed.
    pub context: Option<String>,
    /// Album name.
    pub album: Option<String>,
    /// Track length; `None` when the player does not report it.
    pub duration_ms: Option<NonZeroU64>,
    /// Playback position when the message was sent.
    pub position_ms: u64,
    /// Whether the player is in the playing state.
    pub is_playing: bool,
    /// Playback speed: `1.0` is normal speed and `0.0` a pause.
    pub playback_speed: f32,
    /// Shuffle mode; `None` when the bridge cannot control shuffle for this player.
    pub shuffle: Option<ShuffleMode>,
    /// Album art of the track; `None` means that the track has none.
    pub art_id: Option<String>,
}

/// Values of [`MediaState::shuffle`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ShuffleMode {
    /// Tracks play in order.
    Off,
    /// Tracks play in random order.
    On,
    /// Spotify's smart shuffle: random order with recommended tracks mixed in.
    Smart,
}

/// Message from the PC to the phone.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum PcMessage {
    /// First message of every session.
    #[serde(rename = "hello")]
    Hello(PcHello),
    /// Order for the active player.
    #[serde(rename = "cmd")]
    Cmd(Command),
}

/// Greeting sent by the PC.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PcHello {
    /// Protocol version of the client.
    pub protocol: NonZeroU32,
    /// Client identification as `name/version`, for logging only.
    pub client: String,
}

/// Order for the active player.
///
/// On the wire it is an `action` plus a `positionMs` that is only set for `SEEK`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "WireCommand", into = "WireCommand")]
pub enum Command {
    /// Starts playback; does nothing if already playing.
    Play,
    /// Pauses playback; does nothing if already paused.
    Pause,
    /// Switches between playing and paused.
    Toggle,
    /// Skips to the next track.
    Next,
    /// Goes back to the previous track.
    Previous,
    /// Moves the playback position.
    Seek {
        /// Target position in milliseconds.
        position_ms: u64,
    },
    /// Presses the player's own shuffle button, which moves to its next shuffle mode.
    ToggleShuffle,
}

/// Wire form of [`Command`], before the action and the position are checked together.
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireCommand {
    /// Requested action.
    action: CommandAction,
    /// Target position; only present with [`CommandAction::Seek`].
    position_ms: Option<u64>,
}

/// Values of the `action` field.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CommandAction {
    /// See [`Command::Play`].
    Play,
    /// See [`Command::Pause`].
    Pause,
    /// See [`Command::Toggle`].
    Toggle,
    /// See [`Command::Next`].
    Next,
    /// See [`Command::Previous`].
    Previous,
    /// See [`Command::Seek`].
    Seek,
    /// See [`Command::ToggleShuffle`].
    ToggleShuffle,
}

impl TryFrom<WireCommand> for Command {
    type Error = String;

    /// Combines `action` and `positionMs`, rejecting a `SEEK` without position and a
    /// position on any other action.
    fn try_from(wire: WireCommand) -> Result<Self, Self::Error> {
        let command = match (wire.action, wire.position_ms) {
            (CommandAction::Seek, Some(position_ms)) => Self::Seek { position_ms },
            (CommandAction::Seek, None) => return Err("SEEK requires positionMs".to_owned()),
            (action, Some(_)) => return Err(format!("{action:?} does not accept positionMs")),
            (CommandAction::Play, None) => Self::Play,
            (CommandAction::Pause, None) => Self::Pause,
            (CommandAction::Toggle, None) => Self::Toggle,
            (CommandAction::Next, None) => Self::Next,
            (CommandAction::Previous, None) => Self::Previous,
            (CommandAction::ToggleShuffle, None) => Self::ToggleShuffle,
        };
        Ok(command)
    }
}

impl From<Command> for WireCommand {
    /// Splits a command into its wire fields; `positionMs` is `null` except for `SEEK`.
    fn from(command: Command) -> Self {
        let (action, position_ms) = match command {
            Command::Play => (CommandAction::Play, None),
            Command::Pause => (CommandAction::Pause, None),
            Command::Toggle => (CommandAction::Toggle, None),
            Command::Next => (CommandAction::Next, None),
            Command::Previous => (CommandAction::Previous, None),
            Command::Seek { position_ms } => (CommandAction::Seek, Some(position_ms)),
            Command::ToggleShuffle => (CommandAction::ToggleShuffle, None),
        };
        Self { action, position_ms }
    }
}
