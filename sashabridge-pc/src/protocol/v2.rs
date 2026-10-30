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
    /// Whether the phone's annotation screen is open and waiting for captures.
    #[serde(rename = "annotate.status")]
    AnnotateStatus(AnnotateStatus),
    /// A capture annotated on the phone.
    #[serde(rename = "annotate.result")]
    AnnotateResult(AnnotateImage),
    /// The user discarded a capture on the phone.
    #[serde(rename = "annotate.cancelled")]
    AnnotateCancelled(AnnotateCancelled),
    /// An image the phone sends on its own, such as one shared to SashaBridge.
    #[serde(rename = "annotate.shared")]
    AnnotateShared(SharedImage),
    /// Whether the phone's tablet screen is open.
    #[serde(rename = "tablet.status")]
    TabletStatus(TabletStatus),
    /// The user asked to map the tablet to another monitor.
    #[serde(rename = "tablet.select")]
    TabletSelect(TabletSelect),
    /// The phone measures the round trip; answered with [`PcMessage::TabletPong`].
    #[serde(rename = "tablet.ping")]
    TabletPing(TabletTime),
}

/// State of the phone's tablet screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TabletStatus {
    /// Whether the screen is open; when it closes, the PC lifts its pen.
    pub active: bool,
}

/// Monitor the user chose for the tablet.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TabletSelect {
    /// Index of the monitor, from 0, as in [`TabletTarget::monitor`].
    pub monitor: u32,
}

/// Time of a round-trip measurement: phone uptime in milliseconds, wrapping at `u32::MAX`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TabletTime {
    /// Phone uptime when the ping was sent.
    pub time_ms: u32,
}

/// State of the phone's annotation screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnnotateStatus {
    /// Whether the screen is open, so that captures copied on the PC are sent on their own.
    pub armed: bool,
}

/// An image to annotate, or the annotated result.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnnotateImage {
    /// Identifier the PC gave the capture; the result carries the same one.
    pub request_id: RequestId,
    /// Media type of the image, currently always `image/png`.
    pub mime: String,
    /// Width of the image in pixels.
    pub width: NonZeroU32,
    /// Height of the image in pixels.
    pub height: NonZeroU32,
    /// Image bytes in standard base64.
    pub data: String,
}

/// An image from the phone that answers no capture, for the clipboard.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedImage {
    /// Media type of the image, currently always `image/png`.
    pub mime: String,
    /// Width of the image in pixels.
    pub width: NonZeroU32,
    /// Height of the image in pixels.
    pub height: NonZeroU32,
    /// Image bytes in standard base64.
    pub data: String,
}

/// A capture the user discarded.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnnotateCancelled {
    /// Identifier of the discarded capture.
    pub request_id: RequestId,
}

/// Identifier of a capture: any text that is not empty.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct RequestId(String);

impl RequestId {
    /// Creates the identifier of the `number`-th capture of this run, `capture-<number>`.
    pub fn numbered(number: u64) -> Self {
        Self(format!("capture-{number}"))
    }

    /// Returns the identifier as text.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl TryFrom<String> for RequestId {
    type Error = &'static str;

    /// Accepts any text except the empty one.
    fn try_from(value: String) -> Result<Self, Self::Error> {
        if value.is_empty() { Err("requestId must not be empty") } else { Ok(Self(value)) }
    }
}

impl From<RequestId> for String {
    /// Returns the identifier as text.
    fn from(id: RequestId) -> Self {
        id.0
    }
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
    /// A capture for the phone to annotate.
    #[serde(rename = "annotate.request")]
    AnnotateRequest(AnnotateImage),
    /// The monitor the tablet draws on.
    #[serde(rename = "tablet.target")]
    TabletTarget(TabletTarget),
    /// Answer to a [`PhoneMessage::TabletPing`], with its time.
    #[serde(rename = "tablet.pong")]
    TabletPong(TabletTime),
}

/// The monitor the tablet draws on, so the phone gives its drawing area the same shape.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "WireTabletTarget", into = "WireTabletTarget")]
pub struct TabletTarget {
    /// Index of the monitor, from 0; always below `count`.
    monitor: u32,
    /// Number of monitors.
    count: NonZeroU32,
    /// Name Windows gives the monitor, for display.
    name: String,
    /// Width of the monitor in pixels.
    width: NonZeroU32,
    /// Height of the monitor in pixels.
    height: NonZeroU32,
}

impl TabletTarget {
    /// Describes monitor `monitor` of `count`, named `name`, of `width` x `height` pixels.
    ///
    /// # Errors
    ///
    /// Fails when `monitor` is not below `count`.
    pub fn new(monitor: u32, count: NonZeroU32, name: String, width: NonZeroU32, height: NonZeroU32) -> Result<Self, String> {
        WireTabletTarget { monitor, count, name, width, height }.try_into()
    }
}

/// Wire form of [`TabletTarget`], before the index is checked against the count.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WireTabletTarget {
    /// Index of the monitor, from 0.
    monitor: u32,
    /// Number of monitors.
    count: NonZeroU32,
    /// Name Windows gives the monitor, for display.
    name: String,
    /// Width of the monitor in pixels.
    width: NonZeroU32,
    /// Height of the monitor in pixels.
    height: NonZeroU32,
}

impl TryFrom<WireTabletTarget> for TabletTarget {
    type Error = String;

    /// Accepts the target only if its monitor exists.
    fn try_from(wire: WireTabletTarget) -> Result<Self, Self::Error> {
        if wire.monitor >= wire.count.get() {
            return Err(format!("monitor {} is not below count {}", wire.monitor, wire.count));
        }
        let WireTabletTarget { monitor, count, name, width, height } = wire;
        Ok(Self { monitor, count, name, width, height })
    }
}

impl From<TabletTarget> for WireTabletTarget {
    /// Writes the target's fields as they are.
    fn from(target: TabletTarget) -> Self {
        let TabletTarget { monitor, count, name, width, height } = target;
        Self { monitor, count, name, width, height }
    }
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
