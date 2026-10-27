//! Adapter between the Windows media overlay (SMTC, through souvlaki) and the bridge.
//!
//! Phone updates become SMTC metadata and playback state; SMTC button presses become
//! [`PcCommand`]s on a channel read by the connection.

use anyhow::Context;
use souvlaki::{
    MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig,
};
use tokio::sync::mpsc;
use tracing::{debug, info, warn};

use crate::cover_cache::CoverStore;
use crate::protocol::{MediaUpdate, PcCommand};
use crate::smtc_window::WindowHandle;

/// Title, artist and playing flag of the last update that was logged at info level.
type LoggedState = (Option<String>, Option<String>, bool);

/// SMTC session of the client.
///
/// souvlaki's controls are not `Send`, so the adapter lives on the root task.
pub struct MediaControlsAdapter {
    /// souvlaki controls bound to the hidden window.
    controls: MediaControls,
    /// Files of the album art shown by Windows.
    covers: CoverStore,
    /// Last state logged at info level, to keep repeated updates at debug level.
    last_logged: Option<LoggedState>,
}

impl MediaControlsAdapter {
    /// Creates the SMTC session for `window` and forwards its button presses to `commands`.
    ///
    /// Presses are dropped, with a warning, if the queue is full.
    ///
    /// # Errors
    ///
    /// Fails when Windows refuses to create the SMTC session or to register the handler.
    pub fn new(
        window: &WindowHandle,
        covers: CoverStore,
        commands: mpsc::Sender<PcCommand>,
    ) -> anyhow::Result<Self> {
        let mut controls = MediaControls::new(PlatformConfig {
            dbus_name: "sashabridge",
            display_name: "SashaBridge",
            hwnd: Some(window.as_raw()),
        })
        .context("cannot create the SMTC session")?;

        controls
            .attach(move |event| {
                let Some(command) = command_for_event(&event) else {
                    debug!(?event, "ignored SMTC event");
                    return;
                };
                debug!(?command, "SMTC button pressed");
                if let Err(error) = commands.try_send(command) {
                    warn!(%error, ?command, "command dropped");
                }
            })
            .context("cannot register the SMTC event handler")?;

        Ok(Self { controls, covers, last_logged: None })
    }

    /// Shows `update` in the Windows media overlay.
    ///
    /// A message without album art keeps the last cover received, as v1 only sends the
    /// cover when the track changes.
    ///
    /// # Errors
    ///
    /// Fails when the cover cannot be written or Windows rejects the new state.
    pub fn apply(&mut self, update: MediaUpdate) -> anyhow::Result<()> {
        self.log_update(&update);

        if let Some(jpeg) = &update.album_art {
            self.covers.store(jpeg).context("cannot write the album art")?;
            debug!(bytes = jpeg.len(), "album art stored");
        }
        let cover_url = self.covers.current_url();

        self.controls
            .set_metadata(MediaMetadata {
                title: update.title.as_deref(),
                artist: update.artist.as_deref(),
                album: None,
                duration: update.duration,
                cover_url: cover_url.as_deref(),
            })
            .context("cannot update the SMTC metadata")?;

        let progress = Some(MediaPosition(update.position));
        let playback = if update.is_playing {
            MediaPlayback::Playing { progress }
        } else {
            MediaPlayback::Paused { progress }
        };
        self.controls.set_playback(playback).context("cannot update the SMTC playback state")?;

        self.covers.remove_stale();
        Ok(())
    }

    /// Marks playback as stopped, so that Windows does not show a stale track as playing
    /// while the phone is unreachable.
    pub fn mark_disconnected(&mut self) {
        self.last_logged = None;
        if let Err(error) = self.controls.set_playback(MediaPlayback::Stopped) {
            warn!(%error, "cannot mark the SMTC session as stopped");
        }
    }

    /// Logs track and play-state changes at info level and repeated updates at debug level.
    ///
    /// The v1 server sends bursts of identical messages, so logging all of them at info
    /// level would flood the console.
    fn log_update(&mut self, update: &MediaUpdate) {
        let state = (update.title.clone(), update.artist.clone(), update.is_playing);
        if self.last_logged.as_ref() == Some(&state) {
            debug!(position = ?update.position, has_art = update.album_art.is_some(), "media update");
            return;
        }
        info!(
            title = update.title.as_deref().unwrap_or("-"),
            artist = update.artist.as_deref().unwrap_or("-"),
            playing = update.is_playing,
            has_art = update.album_art.is_some(),
            "now playing"
        );
        self.last_logged = Some(state);
    }
}

/// Translates an SMTC button press into a phone command, if it has one.
fn command_for_event(event: &MediaControlEvent) -> Option<PcCommand> {
    match event {
        // v1 only has a toggle; PLAY and PAUSE are separated in protocol v2.
        MediaControlEvent::Play | MediaControlEvent::Pause | MediaControlEvent::Toggle => {
            Some(PcCommand::PlayPause)
        }
        MediaControlEvent::Next => Some(PcCommand::Next),
        MediaControlEvent::Previous => Some(PcCommand::Previous),
        MediaControlEvent::SetPosition(MediaPosition(position)) => Some(PcCommand::Seek(*position)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    //! Mapping of SMTC buttons to phone commands.

    use std::time::Duration;

    use super::*;

    /// Every button the phone understands maps to its v1 command; the rest are ignored.
    #[test]
    fn maps_smtc_events_to_commands() {
        for event in [MediaControlEvent::Play, MediaControlEvent::Pause, MediaControlEvent::Toggle] {
            assert_eq!(command_for_event(&event), Some(PcCommand::PlayPause));
        }
        assert_eq!(command_for_event(&MediaControlEvent::Next), Some(PcCommand::Next));
        assert_eq!(command_for_event(&MediaControlEvent::Previous), Some(PcCommand::Previous));
        assert_eq!(
            command_for_event(&MediaControlEvent::SetPosition(MediaPosition(Duration::from_secs(42)))),
            Some(PcCommand::Seek(Duration::from_secs(42)))
        );
        assert_eq!(command_for_event(&MediaControlEvent::Stop), None);
    }
}
