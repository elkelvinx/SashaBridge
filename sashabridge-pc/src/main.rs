//! SashaBridge PC client.
//!
//! Mirrors the phone's audio with scrcpy, shows the phone's media state in the Windows
//! media overlay (SMTC) and sends the media keys back to the phone. The phone is reached
//! through an `adb forward` tunnel over USB.

#![warn(missing_docs, clippy::missing_docs_in_private_items)]

mod config;
mod connection;
mod cover_cache;
mod media_controls;
mod now_playing;
mod process;
mod protocol;
mod smtc_window;

use std::process::ExitCode;

use anyhow::Context;
use tokio::sync::mpsc;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;
use tracing_subscriber::fmt::time::uptime;

use crate::config::{BRIDGE_PORT, ToolPaths};
use crate::cover_cache::CoverCache;
use crate::media_controls::MediaControls;
use crate::now_playing::NowPlaying;
use crate::process::{DeviceLink, InstanceLock};

/// Capacity of the queue between SMTC button presses and the connection.
const COMMAND_QUEUE: usize = 16;

/// Entry point: logs any startup error and turns it into a failure exit code.
#[tokio::main]
async fn main() -> ExitCode {
    init_tracing();
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            error!("{error:#}");
            ExitCode::FAILURE
        }
    }
}

/// Sets up console logging. `RUST_LOG` overrides the default `info` level.
///
/// Each line shows the time since startup, which makes slow steps easy to spot.
fn init_tracing() {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_timer(uptime())
        .with_target(false)
        // The classic Windows console prints ANSI color codes literally.
        .with_ansi(false)
        .init();
}

/// Starts every component and keeps the bridge running until the user stops it.
///
/// # Errors
///
/// Fails when a component that the client cannot work without fails to start: the
/// single-instance lock, the scrcpy binaries, the cover directory, the SMTC session or
/// the job object.
async fn run() -> anyhow::Result<()> {
    info!("starting SashaBridge {}", env!("CARGO_PKG_VERSION"));
    let _instance = InstanceLock::acquire()?;

    let paths = ToolPaths::resolve()?;
    info!(dir = %paths.scrcpy.parent().unwrap_or(&paths.scrcpy).display(), "using scrcpy");

    let covers = CoverCache::open_default().context("cannot prepare the cover directory")?;
    let mut now_playing = NowPlaying::new(covers);
    let window = smtc_window::spawn()?;
    let (command_tx, mut command_rx) = mpsc::channel(COMMAND_QUEUE);
    let mut controls = MediaControls::new(&window, command_tx)?;
    let mut link = DeviceLink::new(&paths, BRIDGE_PORT)?;

    let reason = tokio::select! {
        never = connection::run(&mut link, &mut controls, &mut now_playing, &mut command_rx) => match never {},
        reason = shutdown_signal() => reason,
    };

    info!("{reason}, shutting down");
    link.shutdown().await;
    Ok(())
}

/// Waits for Ctrl+C or for the console window to close, and returns which one happened.
///
/// After a close event Windows ends the process within a few seconds, so the cleanup
/// that follows is best effort. The job object guarantees that scrcpy does not survive.
async fn shutdown_signal() -> &'static str {
    let ctrl_c = async {
        if let Err(error) = tokio::signal::ctrl_c().await {
            warn!(%error, "cannot listen for Ctrl+C");
            std::future::pending::<()>().await;
        }
    };
    match tokio::signal::windows::ctrl_close() {
        Ok(mut close) => tokio::select! {
            () = ctrl_c => "Ctrl+C received",
            _ = close.recv() => "console closed",
        },
        Err(error) => {
            warn!(%error, "cannot listen for the console close event");
            ctrl_c.await;
            "Ctrl+C received"
        }
    }
}
