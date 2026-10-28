//! Album art files handed to Windows.
//!
//! SMTC loads the thumbnail from a file, so every cover received from the phone is written
//! to disk under a name derived from its art identifier. The cache keeps one cover, the
//! current one, and answers which file belongs to an identifier. A state whose identifier
//! does not match gets no cover, so a track is never shown with the art of another one (R6).
//!
//! Replaced covers are deleted once Windows shows the new one; the directory is emptied at
//! startup.

use std::env;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use tracing::debug;

/// Directory of the cover files, created inside `%TEMP%`.
const COVERS_SUBDIR: [&str; 2] = ["sashabridge", "covers"];

/// A cover written to disk.
#[derive(Debug)]
struct Cover {
    /// Identifier of the art, as received from the phone or derived from the bytes.
    art_id: String,
    /// File that holds the image.
    path: PathBuf,
}

/// Owner of the cover files of this run.
#[derive(Debug)]
pub struct CoverCache {
    /// Directory that holds the cover files.
    dir: PathBuf,
    /// The cover most recently stored.
    current: Option<Cover>,
    /// Files that were replaced and still have to be deleted.
    stale: Vec<PathBuf>,
}

impl CoverCache {
    /// Opens the cache in `%TEMP%\sashabridge\covers`, deleting covers left by previous runs.
    ///
    /// # Errors
    ///
    /// Fails when the directory cannot be created or listed.
    pub fn open_default() -> io::Result<Self> {
        let dir = COVERS_SUBDIR.iter().fold(env::temp_dir(), |dir, part| dir.join(part));
        Self::open(dir)
    }

    /// Opens the cache in `dir`, deleting the files it contains.
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
        Ok(Self { dir, current: None, stale: Vec::new() })
    }

    /// Stores the cover of `art_id` and makes it the current one. Storing the current cover
    /// again does nothing.
    ///
    /// The replaced cover is kept until [`CoverCache::remove_stale`], so that Windows can
    /// keep showing it until the new one is published.
    ///
    /// # Errors
    ///
    /// Fails when the file cannot be written; the current cover does not change.
    pub fn store(&mut self, art_id: &str, image: &[u8]) -> io::Result<()> {
        if self.current.as_ref().is_some_and(|cover| cover.art_id == art_id) {
            return Ok(());
        }
        // Hashing keeps the file name valid whatever characters the identifier has.
        let path = self.dir.join(format!("cover_{:016x}.jpg", fnv1a(art_id.as_bytes())));
        fs::write(&path, image)?;
        let replaced = self.current.replace(Cover { art_id: art_id.to_owned(), path: path.clone() });
        if let Some(previous) = replaced
            && previous.path != path
        {
            self.stale.push(previous.path);
        }
        Ok(())
    }

    /// Returns the file of the cover with `art_id`, if it is the current one.
    pub fn path_for(&self, art_id: &str) -> Option<&Path> {
        self.current
            .as_ref()
            .filter(|cover| cover.art_id == art_id)
            .map(|cover| cover.path.as_path())
    }

    /// Deletes the replaced covers. Files still in use are retried on the next call.
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

/// 64-bit FNV-1a hash: small, stable across runs and good enough for file names.
fn fnv1a(bytes: &[u8]) -> u64 {
    const OFFSET_BASIS: u64 = 0xcbf2_9ce4_8422_2325;
    const PRIME: u64 = 0x0000_0100_0000_01b3;
    bytes.iter().fold(OFFSET_BASIS, |hash, &byte| (hash ^ u64::from(byte)).wrapping_mul(PRIME))
}

#[cfg(test)]
mod tests {
    //! File lifecycle of the cache, on a private directory per test.

    use super::*;

    /// Creates an empty directory unique to `test` and this process.
    fn test_dir(test: &str) -> PathBuf {
        let dir = env::temp_dir()
            .join("sashabridge-tests")
            .join(format!("{test}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    /// Lists the files in `dir`.
    fn files(dir: &Path) -> Vec<PathBuf> {
        fs::read_dir(dir).unwrap().map(|entry| entry.unwrap().path()).collect()
    }

    /// Covers from a previous run are deleted when the cache opens.
    #[test]
    fn open_deletes_previous_covers() {
        let dir = test_dir("open");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("cover_7.jpg"), b"old").unwrap();

        let cache = CoverCache::open(dir.clone()).unwrap();

        assert!(files(&dir).is_empty());
        assert_eq!(cache.path_for("any"), None);
        fs::remove_dir_all(dir).unwrap();
    }

    /// Only the identifier of the current cover has a file (R6).
    #[test]
    fn only_the_current_identifier_has_a_file() {
        let dir = test_dir("identifiers");
        let mut cache = CoverCache::open(dir.clone()).unwrap();

        cache.store("a", b"first").unwrap();
        cache.store("b", b"second").unwrap();

        assert_eq!(cache.path_for("a"), None);
        let path = cache.path_for("b").unwrap().to_path_buf();
        assert_eq!(fs::read(&path).unwrap(), b"second");
        fs::remove_dir_all(dir).unwrap();
    }

    /// Replaced covers stay on disk until they are removed, and then only the current one remains.
    #[test]
    fn replaced_covers_are_removed_later() {
        let dir = test_dir("rotate");
        let mut cache = CoverCache::open(dir.clone()).unwrap();

        cache.store("a", b"first").unwrap();
        cache.store("b", b"second").unwrap();
        assert_eq!(files(&dir).len(), 2);

        cache.remove_stale();

        assert_eq!(files(&dir), vec![cache.path_for("b").unwrap().to_path_buf()]);
        fs::remove_dir_all(dir).unwrap();
    }

    /// Storing the current cover again does not rewrite it or mark it as stale.
    #[test]
    fn storing_the_same_cover_again_is_a_no_op() {
        let dir = test_dir("same");
        let mut cache = CoverCache::open(dir.clone()).unwrap();

        cache.store("a", b"image").unwrap();
        cache.store("a", b"image").unwrap();
        cache.remove_stale();

        assert_eq!(files(&dir).len(), 1);
        assert!(cache.path_for("a").is_some());
        fs::remove_dir_all(dir).unwrap();
    }

    /// The hash matches the published FNV-1a test vectors.
    #[test]
    fn fnv1a_matches_reference_values() {
        assert_eq!(fnv1a(b""), 0xcbf2_9ce4_8422_2325);
        assert_eq!(fnv1a(b"a"), 0xaf63_dc4c_8601_ec8c);
    }
}
