//! WebSocket session with the phone and the reconnection loop.
//!
//! Every way a session can end (a close frame, a read error, or the stream ending)
//! returns to the loop, which waits with exponential backoff and connects again. A
//! failed handshake, which is what `adb forward` produces when the phone server is down,
//! is just another retry.
//!
//! Each session starts with the PC's `hello`, followed by the phone's; after that the phone
//! sends its state and the PC its commands, all in protocol v2.

use std::convert::Infallible;
use std::time::Instant;

use anyhow::{Context, bail};
use futures_util::{SinkExt, StreamExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::time::{MissedTickBehavior, interval, sleep, timeout};
use tokio_tungstenite::tungstenite::error::ProtocolError;
use tokio_tungstenite::tungstenite::{Error, Message};
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream, connect_async};
use tracing::{debug, info, warn};

use crate::backoff::Backoff;
use crate::config::{AUDIO_WATCHDOG_PERIOD, BACKOFF_INITIAL, BACKOFF_MAX, CONNECT_TIMEOUT, bridge_url};
use crate::media_controls::MediaControls;
use crate::hidden_window::Announcer;
use crate::now_playing::{Display, NowPlaying};
use crate::osd::Announcement;
use crate::process::DeviceLink;
use crate::protocol::v2::{PROTOCOL_VERSION, PhoneMessage};
use crate::protocol::{PcCommand, decode_phone_frame, pc_hello};

/// WebSocket connection to the phone.
type PhoneSocket = WebSocketStream<MaybeTlsStream<TcpStream>>;

/// Keeps the client connected to the phone for as long as the future is polled.
///
/// Never returns; the caller stops it by dropping the future.
pub async fn run(
    link: &mut DeviceLink,
    controls: &mut MediaControls,
    now_playing: &mut NowPlaying,
    commands: &mut mpsc::Receiver<PcCommand>,
    announcer: Announcer,
) -> Infallible {
    let url = bridge_url();
    let mut backoff = Backoff::new(BACKOFF_INITIAL, BACKOFF_MAX);
    loop {
        match connect(link, &url).await {
            Ok(socket) => {
                info!("connected to the phone");
                backoff.reset();
                run_session(socket, link, controls, now_playing, commands, announcer).await;
                controls.mark_disconnected();
            }
            Err(error) => {
                let delay = backoff.peek();
                warn!("phone not ready, retrying in {}s: {error:#}", delay.as_secs());
            }
        }
        sleep(backoff.next_delay()).await;
    }
}

/// Prepares the tunnel and opens the WebSocket.
///
/// # Errors
///
/// Fails when the tunnel cannot be created, the connection is refused or the handshake
/// fails or exceeds [`CONNECT_TIMEOUT`].
async fn connect(link: &mut DeviceLink, url: &str) -> anyhow::Result<PhoneSocket> {
    link.prepare().await?;
    debug!(url, "connecting");
    let result = timeout(CONNECT_TIMEOUT, connect_async(url))
        .await
        .with_context(|| format!("no answer from {url} in {CONNECT_TIMEOUT:?}"))?;
    match result {
        Ok((socket, _response)) => Ok(socket),
        // adb accepts the connection on the PC side and closes it when nothing listens
        // on the phone, so the handshake ends before the server's reply.
        Err(Error::Protocol(ProtocolError::HandshakeIncomplete)) => bail!(
            "the USB tunnel works but the SashaBridge app is not listening on the phone \
             (check that the bridge is on and that notification access is granted)"
        ),
        Err(error) => Err(error).with_context(|| format!("cannot open {url}")),
    }
}

/// Relays phone updates to SMTC and SMTC commands to the phone until the session ends.
async fn run_session(
    socket: PhoneSocket,
    link: &mut DeviceLink,
    controls: &mut MediaControls,
    now_playing: &mut NowPlaying,
    commands: &mut mpsc::Receiver<PcCommand>,
    announcer: Announcer,
) {
    // Buttons pressed while disconnected must not reach the phone later.
    while let Ok(command) = commands.try_recv() {
        debug!(?command, "discarding a command queued while disconnected");
    }
    now_playing.reset_session();

    let (mut sink, mut stream) = socket.split();
    if let Err(error) = sink.send(Message::text(pc_hello())).await {
        warn!(%error, "cannot greet the phone, connection lost");
        return;
    }
    let mut watchdog = interval(AUDIO_WATCHDOG_PERIOD);
    watchdog.set_missed_tick_behavior(MissedTickBehavior::Skip);
    watchdog.reset();

    loop {
        tokio::select! {
            frame = stream.next() => match frame {
                Some(Ok(Message::Text(text))) => handle_frame(&text, controls, now_playing, announcer),
                Some(Ok(Message::Close(frame))) => {
                    info!(?frame, "the phone closed the connection");
                    return;
                }
                // Pings are answered by tungstenite; the protocol uses no binary frames.
                Some(Ok(_)) => {}
                Some(Err(error)) => {
                    warn!(%error, "connection lost");
                    return;
                }
                None => {
                    info!("connection closed");
                    return;
                }
            },
            Some(command) = commands.recv() => {
                // The hide shortcut goes back to the video while the phone hides one.
                let command = if command == PcCommand::Hide && now_playing.phone_hides() {
                    PcCommand::Unhide
                } else {
                    command
                };
                debug!(?command, "sending command");
                if command == PcCommand::ToggleLike && !now_playing.request_like(Instant::now()) {
                    info!("the player on the phone has no like button the bridge can press");
                }
                if let Err(error) = sink.send(Message::text(command.encode())).await {
                    warn!(%error, "cannot send the command, connection lost");
                    return;
                }
                // The phone pauses the video itself, but only adb can bring SashaBridge to
                // the front: Android keeps an app in the background from opening itself. To go
                // back, SashaBridge is already in front and opens the video on its own.
                if command == PcCommand::Hide {
                    link.show_app();
                }
            }
            _ = watchdog.tick() => link.ensure_audio(),
        }
    }
}

/// Decodes a frame from the phone and shows its effect in the media overlay.
///
/// Malformed frames and failures to show them are logged and skipped; the session goes on.
fn handle_frame(text: &str, controls: &mut MediaControls, now_playing: &mut NowPlaying, announcer: Announcer) {
    let display = match decode_phone_frame(text) {
        Ok(PhoneMessage::Hello(hello)) => {
            info!(device = %hello.device, protocol = hello.protocol.get(), "the phone greeted");
            if hello.protocol > PROTOCOL_VERSION {
                warn!("the phone uses a newer protocol; unknown messages will be ignored");
            }
            return;
        }
        Ok(message) => now_playing.apply(message),
        Err(error) => {
            warn!("ignoring a message from the phone: {error:#}");
            return;
        }
    };
    match display {
        Ok(Some(Display::Show(view))) => match controls.show(&view) {
            Ok(()) => now_playing.remove_stale_covers(),
            Err(error) => warn!("cannot show the update: {error:#}"),
        },
        Ok(Some(Display::Clear)) => controls.clear(),
        Ok(None) => {}
        Err(error) => warn!("cannot apply the message: {error:#}"),
    }
    if let Some(notice) = now_playing.take_like_notice() {
        info!(liked = notice.liked, track = %notice.track, "the phone confirmed the like");
        announcer.announce(Announcement {
            filled: notice.liked,
            heading: if notice.liked { "Te gusta" } else { "Quitada de Me gusta" }.to_owned(),
            detail: notice.track,
        });
    }
}
