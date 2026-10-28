//! WebSocket session with the phone and the reconnection loop.
//!
//! Every way a session can end (a close frame, a read error, or the stream ending)
//! returns to the loop, which waits with exponential backoff and connects again. A
//! failed handshake, which is what `adb forward` produces when the phone server is down,
//! is just another retry.
//!
//! Each session starts with the PC's `hello`. The phone's messages are decoded in either
//! protocol version, and commands are sent in v2 once the phone has announced it with its
//! own `hello`, and as v1 text until then.

use std::convert::Infallible;
use std::time::Duration;

use anyhow::{Context, bail};
use futures_util::{SinkExt, StreamExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::time::{MissedTickBehavior, interval, sleep, timeout};
use tokio_tungstenite::tungstenite::error::ProtocolError;
use tokio_tungstenite::tungstenite::{Error, Message};
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream, connect_async};
use tracing::{debug, info, warn};

use crate::config::{AUDIO_WATCHDOG_PERIOD, BACKOFF_INITIAL, BACKOFF_MAX, CONNECT_TIMEOUT, bridge_url};
use crate::media_controls::MediaControls;
use crate::now_playing::{Display, NowPlaying};
use crate::process::DeviceLink;
use crate::protocol::v2::{PROTOCOL_VERSION, PhoneMessage};
use crate::protocol::{PcCommand, PhoneFrame, PhoneProtocol, decode_phone_frame, pc_hello};

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
) -> Infallible {
    let url = bridge_url();
    let mut backoff = Backoff::new(BACKOFF_INITIAL, BACKOFF_MAX);
    loop {
        match connect(link, &url).await {
            Ok(socket) => {
                info!("connected to the phone");
                backoff.reset();
                run_session(socket, link, controls, now_playing, commands).await;
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
    let mut protocol = PhoneProtocol::V1;
    let mut watchdog = interval(AUDIO_WATCHDOG_PERIOD);
    watchdog.set_missed_tick_behavior(MissedTickBehavior::Skip);
    watchdog.reset();

    loop {
        tokio::select! {
            frame = stream.next() => match frame {
                Some(Ok(Message::Text(text))) => handle_frame(&text, &mut protocol, controls, now_playing),
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
                let Some(text) = command.encode(protocol) else {
                    info!(?command, "the phone app does not support this command yet (protocol v1)");
                    continue;
                };
                debug!(?command, ?protocol, "sending command");
                if let Err(error) = sink.send(Message::text(text)).await {
                    warn!(%error, "cannot send the command, connection lost");
                    return;
                }
            }
            _ = watchdog.tick() => link.ensure_audio(),
        }
    }
}

/// Decodes a frame from the phone and shows its effect in the media overlay.
///
/// Malformed frames and failures to show them are logged and skipped; the session goes on.
fn handle_frame(
    text: &str,
    protocol: &mut PhoneProtocol,
    controls: &mut MediaControls,
    now_playing: &mut NowPlaying,
) {
    let display = match decode_phone_frame(text) {
        Ok(PhoneFrame::V1(update)) => now_playing.apply_v1(update).map(Some),
        Ok(PhoneFrame::V2(PhoneMessage::Hello(hello))) => {
            *protocol = PhoneProtocol::V2;
            info!(device = %hello.device, protocol = hello.protocol.get(), "the phone speaks protocol v2");
            if hello.protocol > PROTOCOL_VERSION {
                warn!("the phone uses a newer protocol; unknown messages will be ignored");
            }
            return;
        }
        Ok(PhoneFrame::V2(message)) => now_playing.apply_v2(message),
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
}

/// Exponential backoff with an upper bound.
#[derive(Debug)]
struct Backoff {
    /// First delay, used again after [`Backoff::reset`].
    initial: Duration,
    /// Maximum delay.
    max: Duration,
    /// Delay returned by the next call to [`Backoff::next_delay`].
    next: Duration,
}

impl Backoff {
    /// Creates a backoff that starts at `initial` and never exceeds `max`.
    fn new(initial: Duration, max: Duration) -> Self {
        Self { initial, max, next: initial }
    }

    /// Returns the delay that [`Backoff::next_delay`] will return, without advancing.
    fn peek(&self) -> Duration {
        self.next
    }

    /// Returns the current delay and doubles it for the next call, up to the maximum.
    fn next_delay(&mut self) -> Duration {
        let delay = self.next;
        self.next = (self.next * 2).min(self.max);
        delay
    }

    /// Goes back to the initial delay, after a successful connection.
    fn reset(&mut self) {
        self.next = self.initial;
    }
}

#[cfg(test)]
mod tests {
    //! Delays of the reconnection backoff.

    use super::*;

    /// The delay doubles from 1 s up to the 5 s cap, and a reset starts over.
    #[test]
    fn backoff_doubles_up_to_the_cap_and_resets() {
        let mut backoff = Backoff::new(Duration::from_secs(1), Duration::from_secs(5));

        let delays: Vec<u64> = (0..5).map(|_| backoff.next_delay().as_secs()).collect();
        assert_eq!(delays, [1, 2, 4, 5, 5]);

        backoff.reset();
        assert_eq!(backoff.peek(), Duration::from_secs(1));
    }
}
