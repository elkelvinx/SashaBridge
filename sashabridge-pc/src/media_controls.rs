//! Windows media overlay (System Media Transport Controls) of the client.
//!
//! The client publishes the phone's player as an SMTC session bound to its hidden window,
//! so that the Windows flyout, or a third-party one, shows the track and its buttons control
//! the phone. It uses the WinRT API directly instead of a wrapper crate because the bridge
//! needs two things the wrappers do not offer: removing the album art (R6) and the shuffle
//! button.
//!
//! Button presses arrive on WinRT threads and are forwarded as [`PcCommand`]s on a channel.

use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::Context;
use tokio::sync::mpsc;
use tracing::{debug, info, warn};
use windows::Foundation::{TimeSpan, TypedEventHandler};
use windows::Media::{
    MediaPlaybackStatus, MediaPlaybackType, PlaybackPositionChangeRequestedEventArgs,
    ShuffleEnabledChangeRequestedEventArgs, SystemMediaTransportControls,
    SystemMediaTransportControlsButton, SystemMediaTransportControlsButtonPressedEventArgs,
    SystemMediaTransportControlsDisplayUpdater, SystemMediaTransportControlsTimelineProperties,
};
use windows::Storage::StorageFile;
use windows::Storage::Streams::RandomAccessStreamReference;
use windows::Win32::Foundation::HWND;
use windows::Win32::System::WinRT::ISystemMediaTransportControlsInterop;
use windows::core::{HSTRING, Ref};

use crate::now_playing::MediaView;
use crate::protocol::PcCommand;
use crate::protocol::v2::ShuffleMode;
use crate::hidden_window::WindowHandle;

/// Title, artist and playing flag of the last view logged at info level.
type LoggedState = (Option<String>, Option<String>, bool);

/// SMTC session of the client. Lives on the root task.
pub struct MediaControls {
    /// The SMTC instance of the hidden window.
    controls: SystemMediaTransportControls,
    /// Metadata of the session.
    updater: SystemMediaTransportControlsDisplayUpdater,
    /// Where button presses go.
    commands: mpsc::Sender<PcCommand>,
    /// Registration of the shuffle handler, present while the player supports shuffle.
    shuffle_handler: Option<i64>,
    /// Album art file currently set, to reload the thumbnail only when it changes.
    shown_cover: Option<PathBuf>,
    /// Last state logged at info level, to keep repeated updates at debug level.
    last_logged: Option<LoggedState>,
}

impl MediaControls {
    /// Creates the SMTC session for `window` and forwards its button presses to `commands`.
    ///
    /// Presses are dropped, with a warning, if the queue is full.
    ///
    /// # Errors
    ///
    /// Fails when Windows refuses to create the session or to register the handlers.
    pub fn new(window: &WindowHandle, commands: mpsc::Sender<PcCommand>) -> anyhow::Result<Self> {
        let interop = windows::core::factory::<
            SystemMediaTransportControls,
            ISystemMediaTransportControlsInterop,
        >()
        .context("cannot reach the SMTC interop factory")?;
        // SAFETY: the handle belongs to the hidden window, which lives until the process exits.
        let controls: SystemMediaTransportControls =
            unsafe { interop.GetForWindow(HWND(window.as_raw())) }
                .context("cannot create the SMTC session")?;

        let updater = controls.DisplayUpdater()?;
        updater.SetType(MediaPlaybackType::Music)?;
        controls.SetIsEnabled(true)?;
        controls.SetIsPlayEnabled(true)?;
        controls.SetIsPauseEnabled(true)?;
        controls.SetIsNextEnabled(true)?;
        controls.SetIsPreviousEnabled(true)?;

        let sender = commands.clone();
        controls
            .ButtonPressed(&TypedEventHandler::<
                SystemMediaTransportControls,
                SystemMediaTransportControlsButtonPressedEventArgs,
            >::new(move |_, args: Ref<SystemMediaTransportControlsButtonPressedEventArgs>| {
                if let Some(command) = command_for_button(args.ok()?.Button()?) {
                    forward(&sender, command);
                }
                Ok(())
            }))
            .context("cannot register the SMTC button handler")?;

        let sender = commands.clone();
        controls
            .PlaybackPositionChangeRequested(&TypedEventHandler::<
                SystemMediaTransportControls,
                PlaybackPositionChangeRequestedEventArgs,
            >::new(move |_, args: Ref<PlaybackPositionChangeRequestedEventArgs>| {
                let position = Duration::from(args.ok()?.RequestedPlaybackPosition()?);
                forward(&sender, PcCommand::Seek(position));
                Ok(())
            }))
            .context("cannot register the SMTC seek handler")?;

        Ok(Self {
            controls,
            updater,
            commands,
            shuffle_handler: None,
            shown_cover: None,
            last_logged: None,
        })
    }

    /// Shows `view` in the media overlay.
    ///
    /// # Errors
    ///
    /// Fails when Windows rejects the metadata, the timeline or the playback state. A cover
    /// that cannot be loaded is only logged, and the rest of the view is still shown.
    pub fn show(&mut self, view: &MediaView) -> anyhow::Result<()> {
        self.log(view);

        let music = self.updater.MusicProperties()?;
        // Empty strings clear the fields, so a missing value never shows the previous track's.
        music.SetTitle(&HSTRING::from(view.title.as_deref().unwrap_or_default()))?;
        music.SetArtist(&HSTRING::from(view.artist.as_deref().unwrap_or_default()))?;
        music.SetAlbumTitle(&HSTRING::from(view.album.as_deref().unwrap_or_default()))?;
        if self.shown_cover != view.cover {
            match self.set_cover(view.cover.as_deref()) {
                Ok(()) => self.shown_cover.clone_from(&view.cover),
                Err(error) => warn!(%error, "cannot update the album art"),
            }
        }
        self.updater.Update().context("cannot update the SMTC metadata")?;

        let end = TimeSpan::from(view.duration.unwrap_or_default());
        let timeline = SystemMediaTransportControlsTimelineProperties::new()?;
        timeline.SetStartTime(TimeSpan::default())?;
        timeline.SetMinSeekTime(TimeSpan::default())?;
        timeline.SetEndTime(end)?;
        timeline.SetMaxSeekTime(end)?;
        timeline.SetPosition(TimeSpan::from(view.position))?;
        self.controls
            .UpdateTimelineProperties(&timeline)
            .context("cannot update the SMTC timeline")?;

        let status = if view.playing { MediaPlaybackStatus::Playing } else { MediaPlaybackStatus::Paused };
        self.controls.SetPlaybackStatus(status).context("cannot update the SMTC playback state")?;
        self.set_shuffle(view.shuffle).context("cannot update the SMTC shuffle state")
    }

    /// Empties the overlay because no player is active on the phone.
    pub fn clear(&mut self) {
        self.last_logged = None;
        self.shown_cover = None;
        let result = self
            .updater
            .ClearAll()
            .and_then(|()| self.updater.SetType(MediaPlaybackType::Music))
            .and_then(|()| self.updater.Update())
            .and_then(|()| self.controls.SetPlaybackStatus(MediaPlaybackStatus::Closed));
        if let Err(error) = result {
            warn!(%error, "cannot clear the SMTC session");
        }
        if let Err(error) = self.set_shuffle(None) {
            warn!(%error, "cannot hide the SMTC shuffle button");
        }
        info!("no player active on the phone");
    }

    /// Marks playback as stopped, so that Windows does not show a stale track as playing
    /// while the phone is unreachable.
    pub fn mark_disconnected(&mut self) {
        self.last_logged = None;
        if let Err(error) = self.controls.SetPlaybackStatus(MediaPlaybackStatus::Stopped) {
            warn!(%error, "cannot mark the SMTC session as stopped");
        }
    }

    /// Sets the album art, or removes it when `cover` is `None`.
    ///
    /// # Errors
    ///
    /// Fails when the file cannot be opened or Windows rejects the thumbnail.
    fn set_cover(&self, cover: Option<&Path>) -> windows::core::Result<()> {
        match cover {
            Some(path) => self.updater.SetThumbnail(&thumbnail_from_file(path)?),
            None => self.updater.SetThumbnail(None::<&RandomAccessStreamReference>),
        }
    }

    /// Shows the shuffle button with the player's mode, or hides it when the mode is unknown.
    ///
    /// Windows offers the button only to sessions that handle shuffle requests, so the handler
    /// is registered while the player supports shuffle and removed otherwise. A press asks
    /// the phone to toggle; the new mode arrives with the next state.
    ///
    /// # Errors
    ///
    /// Fails when the handler cannot be registered or removed or the state cannot be set.
    fn set_shuffle(&mut self, shuffle: Option<ShuffleMode>) -> windows::core::Result<()> {
        match shuffle {
            Some(mode) => {
                if self.shuffle_handler.is_none() {
                    let sender = self.commands.clone();
                    let token = self.controls.ShuffleEnabledChangeRequested(&TypedEventHandler::<
                        SystemMediaTransportControls,
                        ShuffleEnabledChangeRequestedEventArgs,
                    >::new(
                        move |_, _| {
                            forward(&sender, PcCommand::ToggleShuffle);
                            Ok(())
                        },
                    ))?;
                    self.shuffle_handler = Some(token);
                }
                self.controls.SetShuffleEnabled(mode != ShuffleMode::Off)
            }
            None => {
                if let Some(token) = self.shuffle_handler.take() {
                    self.controls.RemoveShuffleEnabledChangeRequested(token)?;
                }
                self.controls.SetShuffleEnabled(false)
            }
        }
    }

    /// Logs track and play-state changes at info level and repeated updates at debug level.
    ///
    /// The phone sends several messages per track (position updates, late art), so logging
    /// all of them at info level would flood the console.
    fn log(&mut self, view: &MediaView) {
        let state = (view.title.clone(), view.artist.clone(), view.playing);
        if self.last_logged.as_ref() == Some(&state) {
            debug!(position = ?view.position, cover = view.cover.is_some(), "media update");
            return;
        }
        info!(
            title = view.title.as_deref().unwrap_or("-"),
            artist = view.artist.as_deref().unwrap_or("-"),
            playing = view.playing,
            cover = view.cover.is_some(),
            shuffle = ?view.shuffle,
            "now playing"
        );
        self.last_logged = Some(state);
    }
}

/// Opens an image file as an SMTC thumbnail.
///
/// # Errors
///
/// Fails when the file does not exist or cannot be opened.
fn thumbnail_from_file(path: &Path) -> windows::core::Result<RandomAccessStreamReference> {
    let file = StorageFile::GetFileFromPathAsync(&HSTRING::from(path.as_os_str()))?.join()?;
    RandomAccessStreamReference::CreateFromFile(&file)
}

/// Queues a command for the connection. Runs on WinRT threads.
///
/// Logged at info level so the console shows every play, pause or skip that Windows asks for
/// (media keys, the flyout), which tells them apart from changes made on the phone.
fn forward(commands: &mpsc::Sender<PcCommand>, command: PcCommand) {
    info!(?command, "Windows asked the phone");
    if let Err(error) = commands.try_send(command) {
        warn!(%error, ?command, "command dropped");
    }
}

/// Translates an SMTC button into a phone command, if it has one.
///
/// Windows already resolves the play/pause key into `Play` or `Pause` from the playback
/// state, so the phone receives the explicit action (A10).
fn command_for_button(button: SystemMediaTransportControlsButton) -> Option<PcCommand> {
    match button {
        SystemMediaTransportControlsButton::Play => Some(PcCommand::Play),
        SystemMediaTransportControlsButton::Pause => Some(PcCommand::Pause),
        SystemMediaTransportControlsButton::Next => Some(PcCommand::Next),
        SystemMediaTransportControlsButton::Previous => Some(PcCommand::Previous),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    //! Mapping of SMTC buttons to phone commands, and a manual check against Windows itself.

    use std::thread::sleep;
    use std::time::Instant;

    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSession, GlobalSystemMediaTransportControlsSessionManager,
    };

    use super::*;

    /// Title of the probe session, used to find it among the PC's sessions.
    const PROBE_TITLE: &str = "SashaBridge SMTC probe";

    /// A JPEG that every Windows installation has.
    const SYSTEM_JPEG: &str = r"C:\Windows\Web\Wallpaper\Windows\img0.jpg";

    /// Polls `check` for up to three seconds, since Windows updates sessions asynchronously.
    fn eventually(mut check: impl FnMut() -> bool) -> bool {
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(3) {
            if check() {
                return true;
            }
            sleep(Duration::from_millis(100));
        }
        false
    }

    /// Finds the probe session as a third-party flyout sees it.
    fn probe_session() -> Option<GlobalSystemMediaTransportControlsSession> {
        let manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().ok()?.join().ok()?;
        let sessions = manager.GetSessions().ok()?;
        (0..sessions.Size().ok()?).filter_map(|index| sessions.GetAt(index).ok()).find(|session| {
            session
                .TryGetMediaPropertiesAsync()
                .and_then(|operation| operation.join())
                .and_then(|properties| properties.Title())
                .is_ok_and(|title| title == PROBE_TITLE)
        })
    }

    /// Whether the session currently has album art.
    fn has_cover(session: &GlobalSystemMediaTransportControlsSession) -> bool {
        session
            .TryGetMediaPropertiesAsync()
            .and_then(|operation| operation.join())
            .is_ok_and(|properties| properties.Thumbnail().is_ok())
    }

    /// Whether the session offers the shuffle button, and whether shuffle is active.
    fn shuffle_state(session: &GlobalSystemMediaTransportControlsSession) -> (bool, Option<bool>) {
        let Ok(info) = session.GetPlaybackInfo() else { return (false, None) };
        let enabled = info.Controls().and_then(|controls| controls.IsShuffleEnabled()).unwrap_or(false);
        let active = info.IsShuffleActive().and_then(|value| value.Value()).ok();
        (enabled, active)
    }

    /// Windows sees the art and the shuffle button, and both go away when the view drops them.
    #[test]
    #[ignore = "publishes a real media session on this PC; run with `cargo test -- --ignored`"]
    fn windows_sees_cover_and_shuffle_changes() {
        let (commands, _receiver) = mpsc::channel(4);
        let window = crate::hidden_window::spawn(mpsc::unbounded_channel().0, commands.clone()).unwrap();
        let mut controls = MediaControls::new(&window, commands).unwrap();
        let view = MediaView {
            title: Some(PROBE_TITLE.to_owned()),
            artist: Some("Test".to_owned()),
            album: None,
            duration: Some(Duration::from_secs(100)),
            position: Duration::from_secs(10),
            playing: true,
            cover: Some(PathBuf::from(SYSTEM_JPEG)),
            shuffle: Some(ShuffleMode::On),
        };

        controls.show(&view).unwrap();
        let mut session = None;
        assert!(eventually(|| { session = probe_session(); session.is_some() }), "probe session not found");
        let session = session.unwrap();
        assert!(eventually(|| has_cover(&session)), "Windows must see the album art");
        assert!(eventually(|| shuffle_state(&session) == (true, Some(true))), "shuffle must be offered and on");

        controls.show(&MediaView { cover: None, shuffle: None, ..view }).unwrap();
        assert!(eventually(|| !has_cover(&session)), "the album art must be removed (R6)");
        assert!(eventually(|| !shuffle_state(&session).0), "the shuffle button must be hidden");

        controls.clear();
    }

    /// A flyout's shuffle request reaches the client as a toggle command.
    #[test]
    #[ignore = "publishes a real media session on this PC; run with `cargo test -- --ignored`"]
    fn windows_shuffle_request_becomes_a_command() {
        let (commands, mut receiver) = mpsc::channel(4);
        let window = crate::hidden_window::spawn(mpsc::unbounded_channel().0, commands.clone()).unwrap();
        let mut controls = MediaControls::new(&window, commands).unwrap();
        controls
            .show(&MediaView {
                title: Some(PROBE_TITLE.to_owned()),
                artist: Some("Test".to_owned()),
                album: None,
                duration: Some(Duration::from_secs(100)),
                position: Duration::from_secs(10),
                playing: true,
                cover: None,
                shuffle: Some(ShuffleMode::On),
            })
            .unwrap();
        let mut session = None;
        assert!(eventually(|| { session = probe_session(); session.is_some() }), "probe session not found");
        let session = session.unwrap();
        assert!(eventually(|| shuffle_state(&session) == (true, Some(true))), "shuffle must be offered and on");

        let accepted = session.TryChangeShuffleActiveAsync(false).unwrap().join().unwrap();

        assert!(accepted, "Windows must accept the request");
        assert!(
            eventually(|| receiver.try_recv() == Ok(PcCommand::ToggleShuffle)),
            "the request must become a command"
        );
        controls.clear();
    }

    /// Every button the phone understands maps to its command; the rest are ignored.
    #[test]
    fn maps_smtc_buttons_to_commands() {
        assert_eq!(command_for_button(SystemMediaTransportControlsButton::Play), Some(PcCommand::Play));
        assert_eq!(command_for_button(SystemMediaTransportControlsButton::Pause), Some(PcCommand::Pause));
        assert_eq!(command_for_button(SystemMediaTransportControlsButton::Next), Some(PcCommand::Next));
        assert_eq!(
            command_for_button(SystemMediaTransportControlsButton::Previous),
            Some(PcCommand::Previous)
        );
        assert_eq!(command_for_button(SystemMediaTransportControlsButton::Stop), None);
        assert_eq!(command_for_button(SystemMediaTransportControlsButton::FastForward), None);
    }
}
