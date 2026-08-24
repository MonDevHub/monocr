//! Resume, and the lock that stops two runs corrupting one output directory.
//!
//! Nothing in this ecosystem resumes today, which is fine for a directory of
//! screenshots and not fine for a 500-page book. The key is the content of the
//! input plus the settings that change the answer, so re-running after a
//! `--mode` change correctly redoes the work instead of reporting it done.

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Bytes of a large input hashed for identity. Reading a 500 MB PDF in full to
/// decide whether to skip it defeats the point of skipping; the head, the tail
/// and the length together are enough to notice a changed file in practice.
const HASH_SAMPLE_BYTES: u64 = 1 << 20;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Completed {
    pub input: String,
    pub digest: String,
    pub pages: usize,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct State {
    pub completed: Vec<Completed>,
}

impl State {
    fn path(root: &Path) -> PathBuf {
        root.join(".monocr-state.json")
    }

    pub fn load(root: &Path) -> Result<Self> {
        let path = Self::path(root);
        if !path.exists() {
            return Ok(Self::default());
        }
        let raw = fs::read_to_string(&path)
            .with_context(|| format!("cannot read state file: {}", path.display()))?;
        // A corrupt state file must not abort the run. The worst outcome of
        // discarding it is redoing work, which is safe; refusing to start is not.
        match serde_json::from_str(&raw) {
            Ok(s) => Ok(s),
            Err(e) => {
                eprintln!("warning: ignoring unreadable resume state ({e}); redoing all inputs");
                Ok(Self::default())
            }
        }
    }

    pub fn save(&self, root: &Path) -> Result<()> {
        let bytes = serde_json::to_vec_pretty(self).context("cannot serialise resume state")?;
        crate::output::write_atomic(&Self::path(root), &bytes)
    }

    pub fn is_done(&self, digest: &str) -> bool {
        self.completed.iter().any(|c| c.digest == digest)
    }

    pub fn mark_done(&mut self, input: &Path, digest: &str, pages: usize) {
        if self.is_done(digest) {
            return;
        }
        self.completed.push(Completed {
            input: input.display().to_string(),
            digest: digest.to_string(),
            pages,
        });
    }
}

/// Identity of a unit of work: the input's content plus everything that would
/// change the output for the same input.
pub fn work_digest(path: &Path, mode: &str, dpi: u32) -> Result<String> {
    let mut hasher = Sha256::new();

    let meta =
        fs::metadata(path).with_context(|| format!("cannot stat input: {}", path.display()))?;
    hasher.update(meta.len().to_le_bytes());

    let mut file =
        fs::File::open(path).with_context(|| format!("cannot open input: {}", path.display()))?;

    if meta.len() <= HASH_SAMPLE_BYTES * 2 {
        let mut buf = Vec::new();
        file.read_to_end(&mut buf)
            .with_context(|| format!("cannot read input: {}", path.display()))?;
        hasher.update(&buf);
    } else {
        use std::io::{Seek, SeekFrom};
        let mut head = vec![0u8; HASH_SAMPLE_BYTES as usize];
        file.read_exact(&mut head)
            .with_context(|| format!("cannot read head of {}", path.display()))?;
        hasher.update(&head);

        file.seek(SeekFrom::End(-(HASH_SAMPLE_BYTES as i64)))
            .with_context(|| format!("cannot seek in {}", path.display()))?;
        let mut tail = vec![0u8; HASH_SAMPLE_BYTES as usize];
        file.read_exact(&mut tail)
            .with_context(|| format!("cannot read tail of {}", path.display()))?;
        hasher.update(&tail);
    }

    // Settings that change the answer belong in the identity, or a re-run after
    // changing one of them would report the stale result as done.
    hasher.update(mode.as_bytes());
    hasher.update(dpi.to_le_bytes());
    // Bump when the pipeline itself changes in a way that invalidates outputs.
    hasher.update(b"v1");

    Ok(format!("{:x}", hasher.finalize()))
}

/// Exclusive claim on an output directory for the life of a run.
///
/// Two concurrent runs sharing an output directory interleave their state writes
/// and lose one side's record. `create_new` is the atomic primitive: it either
/// creates the file or fails, with no window between the check and the create.
pub struct DirLock {
    path: PathBuf,
}

impl DirLock {
    pub fn acquire(root: &Path) -> Result<Self> {
        fs::create_dir_all(root)
            .with_context(|| format!("cannot create output directory: {}", root.display()))?;
        let path = root.join(".monocr-lock");

        match fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
        {
            Ok(mut f) => {
                use std::io::Write;
                // Recorded so a stale lock names the process that left it.
                let _ = writeln!(f, "pid {}", std::process::id());
                Ok(Self { path })
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => bail!(
                "output directory is already in use by another run: {}\n\
                 If no other run is active, remove {} and retry.",
                root.display(),
                path.display()
            ),
            Err(e) => Err(e).with_context(|| format!("cannot lock {}", root.display())),
        }
    }
}

impl Drop for DirLock {
    fn drop(&mut self) {
        // Best effort. A lock left behind by a killed process is recoverable by
        // hand, and the error message above says how.
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write(dir: &Path, name: &str, bytes: &[u8]) -> PathBuf {
        let p = dir.join(name);
        let mut f = fs::File::create(&p).unwrap();
        f.write_all(bytes).unwrap();
        p
    }

    #[test]
    fn the_same_input_and_settings_hash_the_same() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let a = write(tmp.path(), "a.pdf", b"content");
        assert_eq!(work_digest(&a, "page", 300)?, work_digest(&a, "page", 300)?);
        Ok(())
    }

    #[test]
    fn changing_the_mode_invalidates_the_digest() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let a = write(tmp.path(), "a.pdf", b"content");
        assert_ne!(
            work_digest(&a, "page", 300)?,
            work_digest(&a, "sparse", 300)?,
            "a mode change must redo the work, not report it done"
        );
        Ok(())
    }

    #[test]
    fn changing_the_dpi_invalidates_the_digest() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let a = write(tmp.path(), "a.pdf", b"content");
        assert_ne!(work_digest(&a, "page", 300)?, work_digest(&a, "page", 200)?);
        Ok(())
    }

    #[test]
    fn changed_content_invalidates_the_digest() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let a = write(tmp.path(), "a.pdf", b"one");
        let d1 = work_digest(&a, "page", 300)?;
        let a = write(tmp.path(), "a.pdf", b"two");
        assert_ne!(d1, work_digest(&a, "page", 300)?);
        Ok(())
    }

    #[test]
    fn state_round_trips_and_reports_done() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let mut s = State::load(tmp.path())?;
        assert!(!s.is_done("abc"));
        s.mark_done(Path::new("book.pdf"), "abc", 12);
        s.save(tmp.path())?;

        let reloaded = State::load(tmp.path())?;
        assert!(reloaded.is_done("abc"));
        assert_eq!(reloaded.completed[0].pages, 12);
        Ok(())
    }

    #[test]
    fn marking_the_same_digest_twice_does_not_duplicate_it() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let mut s = State::load(tmp.path())?;
        s.mark_done(Path::new("book.pdf"), "abc", 12);
        s.mark_done(Path::new("book.pdf"), "abc", 12);
        assert_eq!(s.completed.len(), 1);
        Ok(())
    }

    #[test]
    fn a_corrupt_state_file_redoes_the_work_rather_than_aborting() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        write(tmp.path(), ".monocr-state.json", b"{not json");
        let s = State::load(tmp.path())?;
        assert!(s.completed.is_empty());
        Ok(())
    }

    #[test]
    fn a_second_run_cannot_take_the_same_output_directory() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let _held = DirLock::acquire(tmp.path())?;
        assert!(
            DirLock::acquire(tmp.path()).is_err(),
            "two runs must not share an output directory"
        );
        Ok(())
    }

    #[test]
    fn the_lock_is_released_when_the_run_ends() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        {
            let _held = DirLock::acquire(tmp.path())?;
        }
        // Reacquirable, so a normal exit does not strand the directory.
        let _again = DirLock::acquire(tmp.path())?;
        Ok(())
    }
}
