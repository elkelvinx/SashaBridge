//! The `/pen` session with the phone, shared by the two S Pen modes.
//!
//! A second WebSocket session, on the `/pen` route (section 9 of `docs/protocolo-v2.md`),
//! keeps large captures and the stream of pen samples away from the music session. It
//! carries:
//! - "Anotar" ([`crate::annotate`]): the captures for the phone and the annotations back;
//! - "Tableta" ([`crate::tablet`]): the pen samples, the monitor the tablet draws on, and
//!   the round-trip pings that measure the latency.

use std::convert::Infallible;

use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc::UnboundedReceiver;
use tokio::task;
use tokio::time::{sleep, timeout};
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;
use tracing::{debug, info, warn};

use crate::annotate;
use crate::backoff::Backoff;
use crate::config::{BACKOFF_INITIAL, BACKOFF_MAX, CONNECT_TIMEOUT, bridge_url};
use crate::hidden_window::{ClipboardOwner, WindowEvent};
use crate::protocol::v2::{PcMessage, PhoneMessage};
use crate::protocol::{decode_phone_frame, pc_hello, pen_samples};
use crate::tablet::Tablet;

/// Keeps the `/pen` session open for as long as the future is polled.
///
/// Never returns; the caller stops it by dropping the future. While the phone is
/// unreachable, copied images are dropped: there is nobody to annotate them.
pub async fn run(events: &mut UnboundedReceiver<WindowEvent>, owner: ClipboardOwner, tablet: &mut Tablet) -> Infallible {
    let url = format!("{}pen", bridge_url());
    let mut backoff = Backoff::new(BACKOFF_INITIAL, BACKOFF_MAX);
    loop {
        match timeout(CONNECT_TIMEOUT, connect_async(url.as_str())).await {
            Ok(Ok((socket, _response))) => {
                backoff.reset();
                info!("S Pen route open");
                run_session(socket, events, owner, tablet).await;
                tablet.lift();
            }
            // The music session already reports why the phone is unreachable.
            Ok(Err(error)) => debug!(%error, "S Pen route not ready"),
            Err(_) => debug!("S Pen route did not answer"),
        }
        while let Ok(WindowEvent::ImageCopied { forced, .. }) = events.try_recv() {
            if forced {
                warn!("the phone is not connected; the capture was not sent");
            }
        }
        sleep(backoff.next_delay()).await;
    }
}

/// Serves one `/pen` session until it ends.
async fn run_session(
    socket: tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
    events: &mut UnboundedReceiver<WindowEvent>,
    owner: ClipboardOwner,
    tablet: &mut Tablet,
) {
    let (mut sink, mut stream) = socket.split();
    let mut greeting = vec![pc_hello()];
    greeting.extend(tablet.target().map(PcMessage::TabletTarget).and_then(|message| encode(&message)));
    for text in greeting {
        if let Err(error) = sink.send(Message::text(text)).await {
            warn!(%error, "cannot greet the phone on the S Pen route");
            return;
        }
    }
    let mut armed = false;
    loop {
        let reply = tokio::select! {
            frame = stream.next() => match frame {
                Some(Ok(Message::Binary(bytes))) => {
                    match pen_samples::decode(&bytes) {
                        Ok(samples) => tablet.draw(samples),
                        Err(error) => warn!("ignoring a pen frame: {error:#}"),
                    }
                    None
                }
                Some(Ok(Message::Text(text))) => match decode_phone_frame(&text) {
                    Ok(message) => handle(message, &mut armed, owner, tablet),
                    Err(error) => {
                        warn!("ignoring a message on the S Pen route: {error:#}");
                        None
                    }
                },
                Some(Ok(Message::Close(_))) | None => {
                    info!("S Pen route closed");
                    return;
                }
                Some(Ok(_)) => None,
                Some(Err(error)) => {
                    warn!(%error, "S Pen route lost");
                    return;
                }
            },
            Some(WindowEvent::ImageCopied { image, forced }) = events.recv() => {
                if !(armed || forced) {
                    debug!("image copied while the phone is not waiting; not sent");
                    continue;
                }
                match annotate::capture_request(image).await {
                    Ok(text) => Some(text),
                    Err(error) => {
                        warn!("cannot send the capture: {error:#}");
                        None
                    }
                }
            }
        };
        if let Some(text) = reply
            && let Err(error) = sink.send(Message::text(text)).await
        {
            warn!(%error, "cannot answer the phone, S Pen route lost");
            return;
        }
    }
}

/// Acts on a message from the phone and returns the answer to send, if any.
fn handle(message: PhoneMessage, armed: &mut bool, owner: ClipboardOwner, tablet: &mut Tablet) -> Option<String> {
    match message {
        PhoneMessage::AnnotateStatus(status) => {
            if status.armed != *armed {
                info!(waiting = status.armed, "phone annotation screen");
            }
            *armed = status.armed;
            None
        }
        PhoneMessage::AnnotateResult(result) => {
            task::spawn(annotate::receive_annotation(result, owner));
            None
        }
        PhoneMessage::AnnotateCancelled(cancelled) => {
            info!(request = cancelled.request_id.as_str(), "the capture was discarded on the phone");
            None
        }
        PhoneMessage::TabletStatus(status) => {
            info!(open = status.active, "phone tablet screen");
            if !status.active {
                tablet.lift();
            }
            None
        }
        PhoneMessage::TabletSelect(select) => tablet.select(select.monitor).and_then(|target| encode(&PcMessage::TabletTarget(target))),
        PhoneMessage::TabletPing(time) => encode(&PcMessage::TabletPong(time)),
        PhoneMessage::Hello(_) | PhoneMessage::MediaArt(_) | PhoneMessage::MediaState(_) | PhoneMessage::MediaCleared => None,
    }
}

/// Encodes a message for the phone, logging the rare failure.
fn encode(message: &PcMessage) -> Option<String> {
    serde_json::to_string(message).inspect_err(|error| warn!(%error, "cannot encode a message for the phone")).ok()
}
