//! Album art files handed to Windows.
//!
//! SMTC loads the thumbnail from a file path, so every cover received from the phone is
//! written to disk. Each cover gets a new file instead of overwriting the previous one,
//! which Windows may still be reading. Old files are deleted once the new cover has been
//! published, and the whole directory is emptied at startup.

use std::env;
use std::fs;
use std::io;
use std::path::PathBuf;

use tracing::debug;

/// Directory of the cover files, created inside `%TEMP%`.
const COVERS_SUBDIR: [&str; 2] = ["sashabridge", "covers"];

/// Owner of the cover files of this run.
#[derive(Debug)]
pub struct CoverStore {
    /// Directory that holds the cover files.
    dir: PathBuf,
    /// Number of covers written so far; used to name the files.
    written: u64,
    /// File of the cover currently shown by Windows.
    current: Option<PathBuf>,
    /// Files that are no longer shown and still have to be deleted.
    stale: Vec<PathBuf>,
}

impl CoverStore {
    /// Opens the store in `%TEMP%\sashabridge\covers`, deleting covers left by previous runs.
    ///
    /// # Errors
    ///
    /// Fails when the directory cannot be created.
    pub fn open_default() -> io::Result<Self> {
        let dir = COVERS_SUBDIR.iter().fold(env::temp_dir(), |dir, part| dir.join(part));
        Self::open(dir)
    }

    /// Opens the store in `dir`, deleting the files it contains.
    ///
    /// Files that cannot be deleted, for example because another program has them open,
    /// are left in place.
    ///
    /// # Errors
    ///
    /// Fails when the directory cannot be created or listed.
    pub fn open(dir: PathBuf) -> io::Result<Self> {
        fs::create_dir_all(&dir)?;
        for entry in fs::read_dir(&dir)? {
            let path = entry?.path();
            if let Err(error) = fs::remove_file(&path) {
                debug!(path = %path.display(), %error, "cannot delete an old cover");
            }
        }
        Ok(Self { dir, written: 0, current: None, stale: Vec::new() })
    }

    /// Writes a new cover and makes it the current one.
    ///
    /// The previous cover is kept until [`CoverStore::remove_stale`] is called, so that
    /// Windows can keep reading it until the new one is published.
    ///
    /// # Errors
    ///
    /// Fails when the file cannot be written; the current cover does not change.
    pub fn store(&mut self, jpeg: &[u8]) -> io::Result<()> {
        self.written += 1;
        let path = self.dir.join(format!("cover_{}.jpg", self.written));
        fs::write(&path, jpeg)?;
        if let Some(previous) = self.current.replace(path) {
            self.stale.push(previous);
        }
        Ok(())
    }

    /// Returns the `file://` URL of the current cover, in the form souvlaki expects.
    pub fn current_url(&self) -> Option<String> {
        self.current.as_ref().map(|path| format!("file://{}", path.display()))
    }

    /// Deletes the covers that are no longer shown. Files still in use are retried on the next call.
    pub fn remove_stale(&mut self) {
        self.stale.retain(|path| match fs::remove_file(path) {
            Ok(()) => false,
            Err(error) if error.kind() == io::ErrorKind::NotFound => false,
            Err(error) => {
                debug!(path = %path.display(), %error, "cover still in use, will retry");
                true
            }
        });
    }
}

#[cfg(test)]
mod tests {
    //! File lifecycle of the store, on a private directory per test.

    use super::*;

    /// Creates an empty directory unique to `test` and this process.
    fn test_dir(test: &str) -> PathBuf {
        let dir = env::temp_dir()
            .join("sashabridge-tests")
            .join(format!("{test}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    /// Covers from a previous run are deleted when the store opens.
    #[test]
    fn open_deletes_previous_covers() {
        let dir = test_dir("open");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("cover_7.jpg"), b"old").unwrap();

        let store = CoverStore::open(dir.clone()).unwrap();

        assert_eq!(fs::read_dir(&dir).unwrap().count(), 0);
        assert_eq!(store.current_url(), None);
        fs::remove_dir_all(dir).unwrap();
    }

    /// Only the current cover remains on disk after the stale ones are removed.
    #[test]
    fn keeps_only_the_current_cover() {
        let dir = test_dir("rotate");
        let mut store = CoverStore::open(dir.clone()).unwrap();

        store.store(b"first").unwrap();
        store.store(b"second").unwrap();
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 2);

        store.remove_stale();

        let remaining: Vec<PathBuf> =
            fs::read_dir(&dir).unwrap().map(|entry| entry.unwrap().path()).collect();
        assert_eq!(remaining, vec![dir.join("cover_2.jpg")]);
        assert_eq!(fs::read(&remaining[0]).unwrap(), b"second");
        assert_eq!(
            store.current_url(),
            Some(format!("file://{}", dir.join("cover_2.jpg").display()))
        );
        fs::remove_dir_all(dir).unwrap();
    }
}
