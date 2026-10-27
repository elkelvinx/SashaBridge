//! External processes: the adb tunnel to the phone and the scrcpy audio mirror.
//!
//! scrcpy runs inside a Windows Job Object created with
//! `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. The client holds the only handle to the job, so
//! whenever the client ends (Ctrl+C, closing the console, a panic or Task Manager) Windows
//! closes the handle and terminates scrcpy together with the adb processes it started.
//! The adb server is started by the client itself, outside the job, so it outlives the
//! client like any other adb server.

use std::ffi::c_void;
use std::io;
use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle, RawHandle};
use std::path::PathBuf;
use std::process::Stdio;

use anyhow::{Context, bail};
use tokio::process::{Child, Command};
use tokio::time::timeout;
use tracing::{debug, info, warn};
use windows_sys::Win32::Foundation::{ERROR_ALREADY_EXISTS, INVALID_HANDLE_VALUE};
use windows_sys::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, TH32CS_SNAPTHREAD, THREADENTRY32, Thread32First, Thread32Next,
};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JobObjectExtendedLimitInformation,
    SetInformationJobObject,
};
use windows_sys::Win32::System::Threading::{
    CREATE_SUSPENDED, CreateMutexA, OpenThread, ResumeThread, THREAD_SUSPEND_RESUME,
};

use crate::config::{ADB_TIMEOUT, ToolPaths};

/// Arguments for scrcpy: audio only, without a window.
const SCRCPY_ARGS: [&str; 2] = ["--no-video", "--no-window"];

/// Name of the mutex that marks a running client, local to the Windows session.
const INSTANCE_MUTEX_NAME: &[u8] = b"Local\\SashaBridgePcClient\0";

/// Guard that keeps a second client from starting while this one runs.
///
/// Two clients would fight over the `adb forward` tunnel and the phone's audio capture.
#[derive(Debug)]
pub struct InstanceLock {
    /// Handle to the named mutex; the name is released when the handle closes.
    _mutex: OwnedHandle,
}

impl InstanceLock {
    /// Claims the client name for this process.
    ///
    /// # Errors
    ///
    /// Fails when another client is already running or the mutex cannot be created.
    pub fn acquire() -> anyhow::Result<Self> {
        // SAFETY: the name is NUL-terminated; null attributes give the default security.
        let handle = unsafe { CreateMutexA(std::ptr::null(), 0, INSTANCE_MUTEX_NAME.as_ptr()) };
        // Read right away: it tells whether the mutex already existed.
        let last_error = io::Error::last_os_error();
        if handle.is_null() {
            return Err(last_error).context("cannot create the single-instance mutex");
        }
        // SAFETY: `handle` is a valid handle that nothing else owns.
        let mutex = unsafe { OwnedHandle::from_raw_handle(handle) };
        if last_error.raw_os_error() == Some(ERROR_ALREADY_EXISTS as i32) {
            bail!("another SashaBridge client is already running; close it before starting a new one");
        }
        Ok(Self { _mutex: mutex })
    }
}

/// Owner of the tunnel and of the audio mirror for one phone.
#[derive(Debug)]
pub struct DeviceLink {
    /// adb client used for the server and the tunnel.
    adb: Adb,
    /// Audio mirror.
    scrcpy: Scrcpy,
    /// Port forwarded from the PC to the phone.
    port: u16,
}

impl DeviceLink {
    /// Creates the link. Nothing is started until [`DeviceLink::prepare`].
    ///
    /// # Errors
    ///
    /// Fails when the job object for scrcpy cannot be created.
    pub fn new(paths: &ToolPaths, port: u16) -> anyhow::Result<Self> {
        Ok(Self {
            adb: Adb { exe: paths.adb.clone() },
            scrcpy: Scrcpy {
                exe: paths.scrcpy.clone(),
                adb: paths.adb.clone(),
                job: JobObject::kill_on_close()?,
                child: None,
            },
            port,
        })
    }

    /// Gets the phone ready for a connection attempt.
    ///
    /// Starts the adb server, recreates the `adb forward` tunnel (a stale one from a
    /// previous run is removed first) and starts scrcpy if it is not running. A scrcpy
    /// failure is only logged: metadata and media keys work without audio.
    ///
    /// # Errors
    ///
    /// Fails when the adb server cannot start or the tunnel cannot be created, which
    /// usually means that the phone is not connected or not authorized.
    pub async fn prepare(&mut self) -> anyhow::Result<()> {
        self.adb.start_server().await?;
        let port = format!("tcp:{}", self.port);
        if let Err(error) = self.adb.run(&["forward", "--remove", &port]).await {
            debug!("no previous tunnel to remove: {error:#}");
        }
        self.adb.run(&["forward", &port, &port]).await?;
        debug!(port = self.port, "adb tunnel ready");
        self.ensure_audio();
        Ok(())
    }

    /// Starts scrcpy again if it has exited. Failures are logged and retried on the next call.
    pub fn ensure_audio(&mut self) {
        if let Err(error) = self.scrcpy.ensure_running() {
            warn!("audio mirror unavailable: {error:#}");
        }
    }

    /// Stops scrcpy and removes the tunnel. Errors are logged, since the client is exiting.
    pub async fn shutdown(&mut self) {
        self.scrcpy.stop().await;
        let port = format!("tcp:{}", self.port);
        if let Err(error) = self.adb.run(&["forward", "--remove", &port]).await {
            debug!("cannot remove the tunnel: {error:#}");
        }
    }
}

/// adb client.
#[derive(Debug)]
struct Adb {
    /// Path of `adb.exe`.
    exe: PathBuf,
}

impl Adb {
    /// Starts the adb server if it is not running.
    ///
    /// No output is captured: the server started by this call could inherit the pipes
    /// and keep them open, and waiting for them would never end.
    ///
    /// # Errors
    ///
    /// Fails when adb cannot be started, exceeds [`ADB_TIMEOUT`] or exits with an error.
    async fn start_server(&self) -> anyhow::Result<()> {
        self.execute(&["start-server"], Stdio::null()).await
    }

    /// Runs an adb command, including its error output in the error message.
    ///
    /// # Errors
    ///
    /// Fails when adb cannot be started, exceeds [`ADB_TIMEOUT`] or exits with an error.
    async fn run(&self, args: &[&str]) -> anyhow::Result<()> {
        self.execute(args, Stdio::piped()).await
    }

    /// Runs `adb` with `args`, sending its standard error to `stderr`.
    ///
    /// # Errors
    ///
    /// Fails when adb cannot be started, exceeds [`ADB_TIMEOUT`] or exits with an error.
    async fn execute(&self, args: &[&str], stderr: Stdio) -> anyhow::Result<()> {
        let command_line = format!("adb {}", args.join(" "));
        let child = Command::new(&self.exe)
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(stderr)
            .kill_on_drop(true)
            .spawn()
            .with_context(|| format!("cannot start {}", self.exe.display()))?;
        let output = timeout(ADB_TIMEOUT, child.wait_with_output())
            .await
            .with_context(|| format!("`{command_line}` did not finish in {ADB_TIMEOUT:?}"))?
            .with_context(|| format!("`{command_line}` could not be awaited"))?;
        if output.status.success() {
            return Ok(());
        }
        let detail = String::from_utf8_lossy(&output.stderr);
        match detail.trim() {
            "" => bail!("`{command_line}` failed ({})", output.status),
            detail => bail!("`{command_line}` failed: {detail}"),
        }
    }
}

/// scrcpy process supervisor.
#[derive(Debug)]
struct Scrcpy {
    /// Path of `scrcpy.exe`.
    exe: PathBuf,
    /// Path of the adb that scrcpy must use.
    adb: PathBuf,
    /// Job that terminates scrcpy when the client ends.
    job: JobObject,
    /// Running scrcpy process, if any.
    child: Option<Child>,
}

impl Scrcpy {
    /// Starts scrcpy unless it is already running.
    ///
    /// # Errors
    ///
    /// Fails when scrcpy cannot be started or attached to the job.
    fn ensure_running(&mut self) -> anyhow::Result<()> {
        if let Some(child) = &mut self.child {
            match child.try_wait() {
                Ok(None) => return Ok(()),
                Ok(Some(status)) => info!(%status, "scrcpy exited"),
                Err(error) => warn!(%error, "cannot query scrcpy, starting a new one"),
            }
            self.child = None;
        }
        self.spawn()
    }

    /// Starts scrcpy suspended, attaches it to the job and only then lets it run.
    ///
    /// Starting it suspended guarantees that every process scrcpy creates is already
    /// inside the job.
    ///
    /// # Errors
    ///
    /// Fails when the process cannot be created, attached or resumed. In that case the
    /// suspended process is terminated when `child` is dropped.
    fn spawn(&mut self) -> anyhow::Result<()> {
        let child = Command::new(&self.exe)
            .args(SCRCPY_ARGS)
            .env("ADB", &self.adb)
            .stdin(Stdio::null())
            .creation_flags(CREATE_SUSPENDED)
            .kill_on_drop(true)
            .spawn()
            .with_context(|| format!("cannot start {}", self.exe.display()))?;

        let pid = child.id().context("scrcpy exited before it was configured")?;
        let handle = child.raw_handle().context("scrcpy exited before it was configured")?;
        self.job.assign(handle).context("cannot attach scrcpy to the job object")?;
        resume_process(pid).context("cannot resume scrcpy")?;

        info!(pid, "scrcpy started");
        self.child = Some(child);
        Ok(())
    }

    /// Terminates scrcpy if it is running.
    async fn stop(&mut self) {
        if let Some(mut child) = self.child.take()
            && let Err(error) = child.kill().await
        {
            debug!(%error, "scrcpy was already gone");
        }
    }
}

/// Job Object whose processes are terminated when its last handle closes.
#[derive(Debug)]
struct JobObject {
    /// Handle to the job; closing it terminates every process in the job.
    handle: OwnedHandle,
}

impl JobObject {
    /// Creates an anonymous job with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`.
    ///
    /// # Errors
    ///
    /// Fails when the job cannot be created or configured.
    fn kill_on_close() -> anyhow::Result<Self> {
        // SAFETY: null attributes and a null name create an anonymous job with default security.
        let raw = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
        if raw.is_null() {
            return Err(io::Error::last_os_error()).context("cannot create the job object");
        }
        // SAFETY: `raw` is a valid handle that nothing else owns.
        let job = Self { handle: unsafe { OwnedHandle::from_raw_handle(raw) } };

        // SAFETY: the struct is plain data for which all-zero bytes mean "no limits".
        let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { std::mem::zeroed() };
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        // SAFETY: the pointer and size describe `limits`, which matches the information class.
        let ok = unsafe {
            SetInformationJobObject(
                job.handle.as_raw_handle(),
                JobObjectExtendedLimitInformation,
                (&raw const limits).cast::<c_void>(),
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        };
        if ok == 0 {
            return Err(io::Error::last_os_error()).context("cannot configure the job object");
        }
        Ok(job)
    }

    /// Adds a process to the job.
    ///
    /// # Errors
    ///
    /// Fails when Windows rejects the assignment.
    fn assign(&self, process: RawHandle) -> io::Result<()> {
        // SAFETY: both handles are valid for the duration of the call.
        let ok = unsafe { AssignProcessToJobObject(self.handle.as_raw_handle(), process) };
        if ok == 0 { Err(io::Error::last_os_error()) } else { Ok(()) }
    }
}

/// Resumes every thread of a process created with `CREATE_SUSPENDED`.
///
/// # Errors
///
/// Fails when the threads cannot be listed or resumed, or when the process has none.
fn resume_process(pid: u32) -> io::Result<()> {
    // SAFETY: TH32CS_SNAPTHREAD lists the threads of every process; the id is ignored.
    let raw = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0) };
    if raw == INVALID_HANDLE_VALUE {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `raw` is a valid snapshot handle that nothing else owns.
    let snapshot = unsafe { OwnedHandle::from_raw_handle(raw) };

    // SAFETY: THREADENTRY32 is plain data; the API requires `dwSize` to be set.
    let mut entry: THREADENTRY32 = unsafe { std::mem::zeroed() };
    entry.dwSize = size_of::<THREADENTRY32>() as u32;

    let mut resumed = 0;
    // SAFETY: `snapshot` is valid and `entry` is a valid, initialized out pointer.
    let mut found = unsafe { Thread32First(snapshot.as_raw_handle(), &mut entry) } != 0;
    while found {
        if entry.th32OwnerProcessID == pid {
            // SAFETY: plain call; a null result is handled below.
            let thread = unsafe { OpenThread(THREAD_SUSPEND_RESUME, 0, entry.th32ThreadID) };
            if thread.is_null() {
                return Err(io::Error::last_os_error());
            }
            // SAFETY: `thread` is a valid handle that nothing else owns.
            let thread = unsafe { OwnedHandle::from_raw_handle(thread) };
            // SAFETY: the handle has THREAD_SUSPEND_RESUME access.
            if unsafe { ResumeThread(thread.as_raw_handle()) } == u32::MAX {
                return Err(io::Error::last_os_error());
            }
            resumed += 1;
        }
        // SAFETY: same as Thread32First.
        found = unsafe { Thread32Next(snapshot.as_raw_handle(), &mut entry) } != 0;
    }

    if resumed == 0 {
        return Err(io::Error::other(format!("process {pid} has no threads to resume")));
    }
    Ok(())
}
