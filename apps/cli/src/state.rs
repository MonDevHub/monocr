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
    /// Pages actually written for this input.
    pub pages: usize,
    /// Pages the input turned out to have. `None` marks a record written by a
    /// build that did not track it, which therefore cannot be read as complete.
    #[serde(default)]
    pub expected_pages: Option<usize>,
    /// The output stem this record's work was written under.
    ///
    /// Recorded because the digest is deliberately content-keyed and does NOT
    /// include the path, so two byte-identical books at different paths share a
    /// digest. Keyed on the digest alone, `--resume` skipped the second one as
    /// already done and its output was never written: the same silent loss that
    /// `output::assign_stems` exists to prevent, one flag away. Two identical
    /// books get different stems, so they now get different records.
    ///
    /// A renamed book is redone, and that is correct rather than a regression:
    /// the stem changed, so the previous run's output is not at the name this run
    /// will write. `None` is a record from a build that did not track it and
    /// cannot vouch for any particular output.
    #[serde(default)]
    pub stem: Option<String>,
}

impl Completed {
    /// Whether every page of the input was written.
    ///
    /// "Done" is a comparison, and this record used to carry only one side of
    /// it. Ctrl-C at page 3 of a 500-page book left `pages: 3` with nothing to
    /// measure it against, `is_done` looked at the digest alone, and the next
    /// `--resume` printed `skip (done)` and exited 0 with pages 3-500 never
    /// read. `output.rs:4` already named the shape of that failure — "resume
    /// sees a file, calls the page done, and the loss is silent" — and the state
    /// layer was committing it one level up, over a whole book rather than a
    /// page.
    fn is_complete(&self) -> bool {
        match self.expected_pages {
            // `expected > 0` is checked here rather than trusted from the caller.
            // `Some(0)` satisfied `pages >= expected` and read as complete, and the
            // only thing preventing it was a bail on zero-page PDFs in `render.rs`
            // — an invariant enforced in another module, with nothing pinning it.
            // A new input kind would have silently reverted this whole fix.
            Some(expected) => expected > 0 && self.pages >= expected,
            // A pre-fix state file cannot distinguish a finished book from an
            // interrupted one, so it is redone. `load` below settles the same
            // trade the same way for a corrupt file: redoing work is safe, and
            // reporting unfinished work as done is the failure being fixed.
            None => false,
        }
    }
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

    /// Whether this exact unit of work is already finished.
    ///
    /// Completeness is enforced here, on the single read, rather than trusted at
    /// every call site that writes a record. The interrupted-book defect was one
    /// writer recording a partial run as final; a reader that checks cannot be
    /// undone by the next writer that forgets.
    pub fn is_done(&self, digest: &str, stem: &str) -> bool {
        self.completed
            .iter()
            .any(|c| c.digest == digest && c.stem.as_deref() == Some(stem) && c.is_complete())
    }

    /// Record how far a run got with one input.
    ///
    /// Takes the expected page count as well as the finished one. An interrupted
    /// input is still recorded — the pages it did write are real and worth
    /// reporting — but `is_done` will not skip it, which is the difference
    /// between a resumable run and a book silently abandoned at page 3.
    pub fn record_progress(
        &mut self,
        input: &Path,
        digest: &str,
        stem: &str,
        pages: usize,
        expected: usize,
    ) {
        if let Some(existing) = self
            .completed
            .iter_mut()
            .find(|c| c.digest == digest && c.stem.as_deref() == Some(stem))
        {
            // A resumed run that finishes a previously interrupted input has to
            // overwrite the partial record, not skip it as already present: the
            // old `mark_done` returned early on a digest it had seen, so the
            // book would have stayed incomplete for as long as the directory did.
            existing.input = input.display().to_string();
            // The high-water mark, not the latest attempt. Overwriting regressed a
            // record: a run that reached page 400 of 500 and was interrupted, then
            // resumed and interrupted again at page 50, came back reading `pages:
            // 50` while 400 pages sat on disk, and the operator was told so.
            existing.pages = existing.pages.max(pages);
            existing.expected_pages = Some(expected);
            return;
        }
        self.completed.push(Completed {
            input: input.display().to_string(),
            digest: digest.to_string(),
            pages,
            expected_pages: Some(expected),
            stem: Some(stem.to_string()),
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
        assert!(!s.is_done("abc", "book"));
        s.record_progress(Path::new("book.pdf"), "abc", "book", 12, 12);
        s.save(tmp.path())?;

        let reloaded = State::load(tmp.path())?;
        assert!(reloaded.is_done("abc", "book"));
        assert_eq!(reloaded.completed[0].pages, 12);
        Ok(())
    }

    #[test]
    fn recording_the_same_digest_twice_does_not_duplicate_it() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let mut s = State::load(tmp.path())?;
        s.record_progress(Path::new("book.pdf"), "abc", "book", 12, 12);
        s.record_progress(Path::new("book.pdf"), "abc", "book", 12, 12);
        assert_eq!(s.completed.len(), 1);
        Ok(())
    }

    #[test]
    fn an_interrupted_input_is_not_reported_done() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let mut s = State::load(tmp.path())?;
        // Ctrl-C at page 3 of a 500-page book. This is the run that used to be
        // recorded as final and skipped forever after: pages 3-500 were never
        // read and the re-run printed `skip (done)` and exited 0.
        s.record_progress(Path::new("book.pdf"), "abc", "book", 3, 500);
        assert!(
            !s.is_done("abc", "book"),
            "3 of 500 pages is not a finished book, and --resume must redo it"
        );
        s.save(tmp.path())?;
        assert!(
            !State::load(tmp.path())?.is_done("abc", "book"),
            "the partial record must survive a reload still reading as unfinished"
        );
        Ok(())
    }

    #[test]
    fn finishing_an_interrupted_input_replaces_its_partial_record() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let mut s = State::load(tmp.path())?;
        s.record_progress(Path::new("book.pdf"), "abc", "book", 3, 500);
        s.record_progress(Path::new("book.pdf"), "abc", "book", 500, 500);
        assert_eq!(s.completed.len(), 1, "the partial record must be replaced");
        assert_eq!(s.completed[0].pages, 500);
        assert!(
            s.is_done("abc", "book"),
            "a resumed run that finished the book must stop redoing it"
        );
        Ok(())
    }

    #[test]
    fn a_record_that_cannot_prove_completeness_is_redone() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        // Shape written by the build that had the defect: a page count with
        // nothing to compare it against. It could be a finished book or a book
        // abandoned at page 12, and the whole point is that it cannot tell you.
        write(
            tmp.path(),
            ".monocr-state.json",
            br#"{"completed":[{"input":"book.pdf","digest":"abc","pages":12}]}"#,
        );
        let s = State::load(tmp.path())?;
        assert_eq!(s.completed.len(), 1, "the old record must still parse");
        assert!(
            !s.is_done("abc", "book"),
            "an unverifiable record must be redone, not trusted"
        );
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

    /// Two byte-identical books at different paths must both be produced.
    ///
    /// The digest is content-keyed and does not include the path, so these share
    /// one. Keyed on the digest alone, `--resume` reported the second already done
    /// and its output was never written — the exact silent loss `assign_stems`
    /// exists to prevent, reachable through one flag. This is the case the
    /// collision fix advertised and did not cover.
    #[test]
    fn two_identical_books_under_different_stems_are_both_done_separately() {
        let mut s = State::default();
        s.record_progress(
            Path::new("a/book.pdf"),
            "same-digest",
            "book-aaaaaaaa",
            500,
            500,
        );

        assert!(
            s.is_done("same-digest", "book-aaaaaaaa"),
            "the first book was finished and must be skipped on resume"
        );
        assert!(
            !s.is_done("same-digest", "book-bbbbbbbb"),
            "the second book shares a digest but writes different files, so it is not done"
        );

        s.record_progress(
            Path::new("b/book.pdf"),
            "same-digest",
            "book-bbbbbbbb",
            500,
            500,
        );
        assert!(s.is_done("same-digest", "book-bbbbbbbb"));
        assert_eq!(
            s.completed.len(),
            2,
            "two inputs must not collapse into one record; the first's path was being overwritten"
        );
    }

    /// A completed record must not be regressed by a later interrupted run.
    ///
    /// `pages` was assigned rather than max'd, so a run that reached 400 of 500 and
    /// was interrupted, then resumed and interrupted again at 50, came back reading
    /// `pages: 50` while 400 pages sat on disk. In the collision case above it was
    /// worse: a finished book could be flipped back to incomplete by an identical
    /// one being interrupted.
    #[test]
    fn a_later_shorter_run_does_not_regress_the_recorded_progress() {
        let mut s = State::default();
        s.record_progress(Path::new("book.pdf"), "abc", "book", 400, 500);
        s.record_progress(Path::new("book.pdf"), "abc", "book", 50, 500);

        let record = s
            .completed
            .iter()
            .find(|c| c.digest == "abc")
            .expect("the record must still exist");
        assert_eq!(
            record.pages, 400,
            "progress is a high-water mark; reporting 50 tells the operator 350 pages vanished"
        );

        // And a finished record survives a shorter attempt.
        s.record_progress(Path::new("book.pdf"), "abc", "book", 500, 500);
        s.record_progress(Path::new("book.pdf"), "abc", "book", 3, 500);
        assert!(
            s.is_done("abc", "book"),
            "a finished book must not be flipped back to incomplete"
        );
    }

    /// An input with no pages is not a finished input.
    ///
    /// `pages >= expected` made `Some(0)` complete, and the only thing preventing
    /// it was a bail on zero-page PDFs in another module. An invariant the whole
    /// completeness fix turns on should not live somewhere else with nothing
    /// pinning it.
    #[test]
    fn a_zero_page_expectation_is_never_complete() {
        let mut s = State::default();
        s.record_progress(Path::new("empty.pdf"), "abc", "empty", 0, 0);
        assert!(
            !s.is_done("abc", "empty"),
            "nothing was written, so nothing is done"
        );
    }

    /// A record from a build that did not track the stem cannot vouch for output.
    #[test]
    fn a_record_without_a_stem_is_redone() {
        let mut s = State::default();
        s.completed.push(Completed {
            input: "book.pdf".to_string(),
            digest: "abc".to_string(),
            pages: 500,
            expected_pages: Some(500),
            stem: None,
        });
        assert!(
            !s.is_done("abc", "book"),
            "it may have been written under any name, so it proves nothing about this one"
        );
    }
}
