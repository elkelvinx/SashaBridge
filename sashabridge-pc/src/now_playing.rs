//! What the Windows media overlay should show, built from the phone's messages.
//!
//! This module holds the rule that decides which album art a view gets, the fix for the
//! phantom cover (R6): the art whose identifier the state names, or none when it names none
//! or names art that was not received.

use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::Context;
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;

use crate::cover_cache::CoverCache;
use crate::protocol::v2::{MediaState, PhoneMessage, ShuffleMode};

/// Separator between the artist and the playback context, the same one Spotify uses.
const CONTEXT_SEPARATOR: &str = " \u{2022} ";

/// Everything the media overlay shows for one state of the phone's player.
#[derive(Debug, Clone, PartialEq)]
pub struct MediaView {
    /// Track title.
    pub title: Option<String>,
    /// Artist line, with the playback context appended when there is one.
    pub artist: Option<String>,
    /// Album name.
    pub album: Option<String>,
    /// Track length.
    pub duration: Option<Duration>,
    /// Playback position.
    pub position: Duration,
    /// Whether the player is playing.
    pub playing: bool,
    /// File of the album art, or `None` to show no art.
    pub cover: Option<PathBuf>,
    /// Shuffle mode, or `None` when it cannot be controlled, which hides the shuffle button.
    pub shuffle: Option<ShuffleMode>,
}

/// Change that a message from the phone causes in the media overlay.
#[derive(Debug, Clone, PartialEq)]
pub enum Display {
    /// Show this state.
    Show(MediaView),
    /// No player is active; empty the overlay.
    Clear,
}

/// State of the current track across the messages of a session.
#[derive(Debug)]
pub struct NowPlaying {
    /// Files of the album art.
    covers: CoverCache,
    /// Last state, shown again if its art arrives after it.
    last_state: Option<MediaState>,
}

impl NowPlaying {
    /// Creates the state with `covers` as the art storage.
    pub fn new(covers: CoverCache) -> Self {
        Self { covers, last_state: None }
    }

    /// Forgets what the previous session sent. The phone sends the full state again on connect.
    pub fn reset_session(&mut self) {
        self.last_state = None;
    }

    /// Applies a message other than `hello`, which the connection handles.
    ///
    /// # Errors
    ///
    /// Fails when a `media.art` is not valid base64 or cannot be written to disk.
    pub fn apply(&mut self, message: PhoneMessage) -> anyhow::Result<Option<Display>> {
        match message {
            PhoneMessage::Hello(_) => Ok(None),
            PhoneMessage::MediaArt(art) => {
                let image = STANDARD.decode(&art.data).context("invalid base64 in media.art")?;
                self.covers.store(&art.art_id, &image).context("cannot write the album art")?;
                // Art normally arrives before the state that names it; if it came later,
                // show that state again, now with its art.
                let waiting = self
                    .last_state
                    .as_ref()
                    .filter(|state| state.art_id.as_deref() == Some(art.art_id.as_str()));
                Ok(waiting.map(|state| Display::Show(self.view_of(state))))
            }
            PhoneMessage::MediaState(state) => {
                let view = self.view_of(&state);
                self.last_state = Some(state);
                Ok(Some(Display::Show(view)))
            }
            PhoneMessage::MediaCleared => {
                self.last_state = None;
                Ok(Some(Display::Clear))
            }
        }
    }

    /// Deletes the album art files that are no longer shown. Call after Windows shows a view.
    pub fn remove_stale_covers(&mut self) {
        self.covers.remove_stale();
    }

    /// Builds the view of a state.
    fn view_of(&self, state: &MediaState) -> MediaView {
        let artist = match (&state.artist, &state.context) {
            (Some(artist), Some(context)) => Some(format!("{artist}{CONTEXT_SEPARATOR}{context}")),
            (artist, context) => artist.clone().or_else(|| context.clone()),
        };
        MediaView {
            title: state.title.clone(),
            artist,
            album: state.album.clone(),
            duration: state.duration_ms.map(|ms| Duration::from_millis(ms.get())),
            position: Duration::from_millis(state.position_ms),
            playing: state.is_playing,
            cover: self.cover_for(state.art_id.as_deref()),
            shuffle: state.shuffle,
        }
    }

    /// Returns the file of the art with `art_id`, if it is the stored one.
    fn cover_for(&self, art_id: Option<&str>) -> Option<PathBuf> {
        art_id.and_then(|id| self.covers.path_for(id)).map(Path::to_path_buf)
    }
}

#[cfg(test)]
mod tests {
    //! Album art rule (R6) and view building.

    use std::env;
    use std::fs;
    use std::num::NonZeroU64;

    use super::*;
    use crate::protocol::v2::MediaArt;

    /// Creates a state whose cover directory is unique to `test`. The name is fixed, so each
    /// run reuses and empties the directory of the previous one instead of adding another.
    fn now_playing(test: &str) -> NowPlaying {
        let dir = env::temp_dir().join("sashabridge-tests").join(format!("now-playing-{test}"));
        let _ = fs::remove_dir_all(&dir);
        NowPlaying::new(CoverCache::open(dir).unwrap())
    }

    /// A state with the given art identifier.
    fn v2_state(art_id: Option<&str>) -> MediaState {
        MediaState {
            track_id: "t".to_owned(),
            source: "com.spotify.music".to_owned(),
            title: Some("Title".to_owned()),
            artist: Some("Artist".to_owned()),
            context: None,
            album: Some("Album".to_owned()),
            duration_ms: NonZeroU64::new(215_000),
            position_ms: 1_000,
            is_playing: true,
            playback_speed: 1.0,
            shuffle: Some(ShuffleMode::On),
            art_id: art_id.map(str::to_owned),
        }
    }

    /// An art message.
    fn v2_art(art_id: &str) -> PhoneMessage {
        PhoneMessage::MediaArt(MediaArt {
            art_id: art_id.to_owned(),
            mime: "image/jpeg".to_owned(),
            data: STANDARD.encode(b"jpeg"),
        })
    }

    /// The view of a display that must be a `Show`.
    fn shown(display: Option<Display>) -> MediaView {
        match display {
            Some(Display::Show(view)) => view,
            other => panic!("expected a view, got {other:?}"),
        }
    }

    /// The state shows the art it names.
    #[test]
    fn v2_state_shows_the_art_it_names() {
        let mut state = now_playing("v2-match");
        state.apply(v2_art("a")).unwrap();

        let view = shown(state.apply(PhoneMessage::MediaState(v2_state(Some("a")))).unwrap());

        assert!(view.cover.is_some());
        assert_eq!(view.shuffle, Some(ShuffleMode::On));
        assert_eq!(view.duration, Some(Duration::from_secs(215)));
    }

    /// R6: a state without art, or naming art that was not received, shows none.
    #[test]
    fn v2_state_without_matching_art_has_no_cover() {
        let mut state = now_playing("v2-r6");
        state.apply(v2_art("a")).unwrap();

        let none = shown(state.apply(PhoneMessage::MediaState(v2_state(None))).unwrap());
        let other = shown(state.apply(PhoneMessage::MediaState(v2_state(Some("b")))).unwrap());

        assert_eq!(none.cover, None);
        assert_eq!(other.cover, None);
    }

    /// Art that arrives after its state makes that state show again, now with the art.
    #[test]
    fn v2_late_art_reshows_the_state() {
        let mut state = now_playing("v2-late");
        state.apply(PhoneMessage::MediaState(v2_state(Some("a")))).unwrap();

        let view = shown(state.apply(v2_art("a")).unwrap());

        assert!(view.cover.is_some());
        assert_eq!(state.apply(v2_art("unrelated")).unwrap(), None);
    }

    /// The playback context is shown after the artist; media.cleared empties the overlay.
    #[test]
    fn v2_context_and_cleared() {
        let mut state = now_playing("v2-context");
        let mut with_context = v2_state(None);
        with_context.context = Some("Del Modo aleatorio inteligente".to_owned());

        let view = shown(state.apply(PhoneMessage::MediaState(with_context)).unwrap());

        assert_eq!(view.artist.as_deref(), Some("Artist \u{2022} Del Modo aleatorio inteligente"));
        assert_eq!(state.apply(PhoneMessage::MediaCleared).unwrap(), Some(Display::Clear));
    }
}
