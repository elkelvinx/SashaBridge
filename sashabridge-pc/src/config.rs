//! Static configuration: bridge port, timings and the location of the scrcpy binaries.

use std::env;
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, bail};

/// TCP port of the phone's WebSocket server, forwarded over USB by `adb forward`.
pub const BRIDGE_PORT: u16 = 8080;

/// Delay before the first reconnection attempt. It doubles after each failure.
pub const BACKOFF_INITIAL: Duration = Duration::from_secs(1);

/// Upper bound of the reconnection delay.
pub const BACKOFF_MAX: Duration = Duration::from_secs(5);

/// Maximum time for the TCP connection plus the WebSocket handshake.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// Maximum time for a single `adb` invocation.
///
/// `adb` can block, for example while the phone shows the USB debugging prompt.
pub const ADB_TIMEOUT: Duration = Duration::from_secs(10);

/// How often a connected session checks that scrcpy is still running.
pub const AUDIO_WATCHDOG_PERIOD: Duration = Duration::from_secs(5);

/// Environment variable that overrides the directory of the scrcpy binaries.
pub const SCRCPY_DIR_ENV: &str = "SASHABRIDGE_SCRCPY_DIR";

/// Returns the WebSocket URL of the phone server, reachable through the `adb forward` tunnel.
pub fn bridge_url() -> String {
    format!("ws://127.0.0.1:{BRIDGE_PORT}/")
}

/// Absolute paths of the external executables.
#[derive(Debug, Clone)]
pub struct ToolPaths {
    /// `adb.exe` shipped with scrcpy. The client and scrcpy use the same one so that
    /// they never restart each other's adb server over a version mismatch.
    pub adb: PathBuf,
    /// `scrcpy.exe`, which mirrors the phone's audio.
    pub scrcpy: PathBuf,
}

impl ToolPaths {
    /// Locates the scrcpy distribution, independently of the working directory.
    ///
    /// Candidates, in order:
    /// 1. the directory named by `SASHABRIDGE_SCRCPY_DIR`, if the variable is set;
    /// 2. a `scrcpy` folder next to the executable;
    /// 3. in debug builds, `vendor/scrcpy` inside the crate.
    ///
    /// # Errors
    ///
    /// Fails when the variable points to a directory without the binaries, when the
    /// path of the running executable cannot be resolved, or when no candidate
    /// contains both `scrcpy.exe` and `adb.exe`.
    pub fn resolve() -> anyhow::Result<Self> {
        if let Some(dir) = env::var_os(SCRCPY_DIR_ENV) {
            let dir = PathBuf::from(dir);
            return Self::from_dir(&dir)
                .with_context(|| format!("{SCRCPY_DIR_ENV} points to {}", dir.display()));
        }

        let exe = env::current_exe().context("cannot resolve the path of the executable")?;
        let mut candidates = Vec::new();
        if let Some(exe_dir) = exe.parent() {
            candidates.push(exe_dir.join("scrcpy"));
        }
        if cfg!(debug_assertions) {
            candidates.push(Path::new(env!("CARGO_MANIFEST_DIR")).join("vendor").join("scrcpy"));
        }

        candidates
            .iter()
            .find_map(|dir| Self::from_dir(dir).ok())
            .with_context(|| {
                let searched: Vec<String> =
                    candidates.iter().map(|dir| dir.display().to_string()).collect();
                format!(
                    "scrcpy not found (searched: {}). Run tools/fetch-scrcpy.ps1 or set {SCRCPY_DIR_ENV}",
                    searched.join(", ")
                )
            })
    }

    /// Builds the paths for `dir`, checking that both executables exist.
    ///
    /// # Errors
    ///
    /// Fails when `adb.exe` or `scrcpy.exe` is missing from `dir`.
    fn from_dir(dir: &Path) -> anyhow::Result<Self> {
        let paths = Self { adb: dir.join("adb.exe"), scrcpy: dir.join("scrcpy.exe") };
        for file in [&paths.adb, &paths.scrcpy] {
            if !file.is_file() {
                bail!("missing {}", file.display());
            }
        }
        Ok(paths)
    }
}
