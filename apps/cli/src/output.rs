//! Writing results, and never writing half of one.
//!
//! A page file that is half-written when the process dies is worse than a
//! missing one: resume sees a file, calls the page done, and the loss is silent.
//! Every write here lands through a temp file and a rename, which is atomic
//! within a directory on both APFS and ext4. The upstream model manager uses the
//! same `.part`-then-replace shape for the same reason.

use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::Serialize;
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Serialize)]
pub struct LineRecord {
    pub text: String,
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
    /// Tiles this line was split into. 1 means it fitted the window. A page whose
    /// tile count is wildly out of line with its neighbours is a signal that
    /// segmentation fused or fragmented, which is why it is recorded per line.
    pub tiles: usize,
    /// Set when the band looks like a fused block rather than a line. Advisory:
    /// nothing is dropped on the strength of it.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub looks_fused: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct PageRecord {
    pub input: String,
    pub page: usize,
    pub mode: String,
    pub lines: Vec<LineRecord>,
    pub ms: u128,
}

#[derive(Debug, Clone, Serialize)]
pub struct FailureRecord {
    pub input: String,
    pub page: Option<usize>,
    pub error: String,
}

/// One line of the manifest. Tagged so a consumer can branch without guessing,
/// and so a failure is a first-class record rather than an absence.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ManifestEntry {
    Page(PageRecord),
    Failure(FailureRecord),
    Skipped { path: String, reason: String },
}

pub struct OutputDir {
    root: PathBuf,
    manifest: File,
}

impl OutputDir {
    pub fn create(root: &Path) -> Result<Self> {
        fs::create_dir_all(root)
            .with_context(|| format!("cannot create output directory: {}", root.display()))?;

        // Appended, not truncated: a resumed run must not erase the record of
        // what the interrupted run already did.
        let manifest = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(root.join("manifest.jsonl"))
            .with_context(|| format!("cannot open manifest in {}", root.display()))?;

        Ok(Self {
            root: root.to_path_buf(),
            manifest,
        })
    }

    pub fn page_path(&self, stem: &str, page: usize) -> PathBuf {
        // Zero-padded so a directory listing is already in reading order, which
        // is what anyone concatenating these by shell glob will rely on.
        self.root.join(stem).join(format!("page-{page:04}.txt"))
    }

    pub fn document_path(&self, stem: &str) -> PathBuf {
        self.root.join(format!("{stem}.txt"))
    }

    pub fn write_page(&self, stem: &str, page: usize, text: &str) -> Result<()> {
        let path = self.page_path(stem, page);
        let dir = path
            .parent()
            .expect("page path always has a parent directory");
        fs::create_dir_all(dir)
            .with_context(|| format!("cannot create page directory: {}", dir.display()))?;
        write_atomic(&path, text.as_bytes())
    }

    pub fn write_document(&self, stem: &str, text: &str) -> Result<()> {
        write_atomic(&self.document_path(stem), text.as_bytes())
    }

    pub fn record(&mut self, entry: &ManifestEntry) -> Result<()> {
        let file = &mut self.manifest;
        let mut line = serde_json::to_string(entry).context("cannot serialise manifest entry")?;
        line.push('\n');
        file.write_all(line.as_bytes())
            .context("cannot append to manifest")?;
        // Flushed per entry so an interrupted run leaves a truthful manifest
        // rather than whatever the buffer happened to hold.
        file.flush().context("cannot flush manifest")?;
        Ok(())
    }
}

/// Write via a temp file in the destination directory, then rename over the
/// target. Same-directory keeps the rename on one filesystem, which is what
/// makes it atomic.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let dir = path.parent().unwrap_or_else(|| Path::new("."));
    let mut tmp = tempfile::NamedTempFile::new_in(dir)
        .with_context(|| format!("cannot create a temp file in {}", dir.display()))?;
    tmp.write_all(bytes)
        .with_context(|| format!("cannot write {}", path.display()))?;
    // Durability before visibility: the rename must not expose a file whose
    // contents are still only in the page cache.
    tmp.as_file().sync_all().context("cannot fsync temp file")?;
    tmp.persist(path)
        .map_err(|e| anyhow::anyhow!("cannot move temp file into place: {e}"))?;
    Ok(())
}

/// Filesystem-safe stem for an input path. Two inputs in different directories
/// can share a file name, so the stem alone would collide and one book would
/// overwrite the other.
pub fn output_stem(path: &Path, disambiguator: Option<&str>) -> String {
    let base = path
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "input".to_string());

    let cleaned: String = base
        .chars()
        .map(|c| match c {
            'a'..='z' | 'A'..='Z' | '0'..='9' | '-' | '_' | '.' => c,
            _ => '_',
        })
        .collect();

    let cleaned = cleaned.trim_matches('.').to_string();
    let cleaned = if cleaned.is_empty() {
        "input".to_string()
    } else {
        cleaned
    };

    match disambiguator {
        Some(d) => format!("{cleaned}-{d}"),
        None => cleaned,
    }
}

/// Stems for a whole run, with same-named inputs pulled apart.
///
/// `output_stem` can disambiguate but cannot know that it needs to: a collision
/// is a property of the input list, not of any one path. That missing half is
/// what made the doc above wishful thinking — the one production caller passed
/// `None` unconditionally, so `disambiguator` was never `Some` outside a unit
/// test, and `extract a/book.pdf b/book.pdf -o out` wrote both books over one
/// `out/book.txt` and interleaved their pages into one `out/book/`. The first
/// book was simply gone, with nothing on stderr and exit 0.
pub fn assign_stems(paths: &[PathBuf]) -> Vec<String> {
    let bases: Vec<String> = paths.iter().map(|p| output_stem(p, None)).collect();

    let mut counts: HashMap<&str, usize> = HashMap::new();
    for base in &bases {
        *counts.entry(base.as_str()).or_insert(0) += 1;
    }

    // Only a name that actually collides is decorated. Tagging every stem would
    // rename the output of every single-book run that already exists, and the
    // common case is one book whose own name is the useful one.
    let mut stems: Vec<String> = paths
        .iter()
        .zip(&bases)
        .map(|(path, base)| {
            if counts.get(base.as_str()).copied().unwrap_or(0) > 1 {
                output_stem(path, Some(&path_tag(path)))
            } else {
                base.clone()
            }
        })
        .collect();

    // A tag collision, or an input literally named `book-1a2b3c4d.pdf`, would
    // silently reintroduce the overwrite this function exists to prevent, so
    // uniqueness is checked rather than assumed.
    force_unique(&mut stems);
    stems
}

/// Short stable tag for a path. Derived from the path rather than from its
/// position in the list, so re-running the same command picks the same stems
/// and `--resume` keeps writing to the files the first run wrote.
fn path_tag(path: &Path) -> String {
    let mut hasher = Sha256::new();
    hasher.update(path.to_string_lossy().as_bytes());
    // Hex, so slicing bytes and slicing characters are the same thing here.
    format!("{:x}", hasher.finalize())[..8].to_string()
}

/// Last-resort suffix for any stem still repeated after tagging.
fn force_unique(stems: &mut [String]) {
    let mut seen: HashSet<String> = HashSet::new();
    for stem in stems.iter_mut() {
        if seen.insert(stem.clone()) {
            continue;
        }
        let mut n = 2;
        while !seen.insert(format!("{stem}-{n}")) {
            n += 1;
        }
        *stem = format!("{stem}-{n}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_stem_keeps_safe_characters_and_replaces_the_rest() {
        assert_eq!(
            output_stem(Path::new("/a/Mon_E_library.pdf"), None),
            "Mon_E_library"
        );
        assert_eq!(
            output_stem(Path::new("/a/my book (1).pdf"), None),
            "my_book__1_"
        );
        assert_eq!(
            output_stem(Path::new("/a/../weird/..name.png"), None),
            "name"
        );
    }

    #[test]
    fn a_path_with_no_usable_stem_still_produces_one() {
        assert_eq!(output_stem(Path::new("/a/..."), None), "input");
        assert_eq!(output_stem(Path::new("/"), None), "input");
    }

    #[test]
    fn a_disambiguator_is_appended_so_same_named_books_do_not_collide() {
        assert_eq!(
            output_stem(Path::new("/x/book.pdf"), Some("ab12cd34")),
            "book-ab12cd34"
        );
    }

    #[test]
    fn two_books_with_the_same_name_get_different_stems() {
        let paths = vec![PathBuf::from("a/book.pdf"), PathBuf::from("b/book.pdf")];
        let stems = assign_stems(&paths);
        assert_ne!(
            stems[0], stems[1],
            "one book would overwrite the other's .txt and interleave its pages"
        );
        assert!(
            stems[0].starts_with("book-"),
            "unexpected stem: {}",
            stems[0]
        );
        assert!(
            stems[1].starts_with("book-"),
            "unexpected stem: {}",
            stems[1]
        );
    }

    #[test]
    fn a_name_that_does_not_collide_is_left_alone() {
        let paths = vec![PathBuf::from("a/book.pdf"), PathBuf::from("b/atlas.pdf")];
        assert_eq!(assign_stems(&paths), vec!["book", "atlas"]);
    }

    #[test]
    fn the_same_arguments_produce_the_same_stems() {
        // --resume depends on this: a stem that moved between runs would write a
        // second copy of the book beside the first rather than continuing it.
        let paths = vec![PathBuf::from("a/book.pdf"), PathBuf::from("b/book.pdf")];
        assert_eq!(assign_stems(&paths), assign_stems(&paths));
    }

    #[test]
    fn a_stem_that_survives_tagging_still_ends_up_unique() {
        // The tag makes this vanishingly unlikely, not impossible, and the cost
        // of it happening is the silent overwrite being fixed here.
        let mut stems = vec!["book".to_string(), "book".to_string(), "book".to_string()];
        force_unique(&mut stems);
        assert_eq!(stems, vec!["book", "book-2", "book-3"]);
    }

    #[test]
    fn pages_are_zero_padded_so_a_glob_is_already_in_order() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let out = OutputDir::create(tmp.path())?;
        assert!(out.page_path("book", 2).ends_with("book/page-0002.txt"));
        assert!(out.page_path("book", 1234).ends_with("book/page-1234.txt"));
        Ok(())
    }

    #[test]
    fn an_atomic_write_leaves_no_temp_file_behind() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let target = tmp.path().join("page.txt");
        write_atomic(&target, b"hello")?;
        assert_eq!(std::fs::read_to_string(&target)?, "hello");
        assert_eq!(std::fs::read_dir(tmp.path())?.count(), 1);
        Ok(())
    }

    #[test]
    fn an_atomic_write_replaces_rather_than_appends() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let target = tmp.path().join("page.txt");
        write_atomic(&target, b"first")?;
        write_atomic(&target, b"second")?;
        assert_eq!(std::fs::read_to_string(&target)?, "second");
        Ok(())
    }

    #[test]
    fn the_manifest_is_appended_so_a_resume_keeps_the_earlier_record() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        {
            let mut out = OutputDir::create(tmp.path())?;
            out.record(&ManifestEntry::Skipped {
                path: "a.txt".into(),
                reason: "unsupported".into(),
            })?;
        }
        {
            let mut out = OutputDir::create(tmp.path())?;
            out.record(&ManifestEntry::Skipped {
                path: "b.txt".into(),
                reason: "unsupported".into(),
            })?;
        }
        let manifest = std::fs::read_to_string(tmp.path().join("manifest.jsonl"))?;
        assert_eq!(manifest.lines().count(), 2);
        assert!(manifest.contains("a.txt") && manifest.contains("b.txt"));
        Ok(())
    }

    #[test]
    fn a_failure_is_a_record_not_an_absence() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let mut out = OutputDir::create(tmp.path())?;
        out.record(&ManifestEntry::Failure(FailureRecord {
            input: "broken.pdf".into(),
            page: Some(3),
            error: "encrypted".into(),
        }))?;
        let manifest = std::fs::read_to_string(tmp.path().join("manifest.jsonl"))?;
        assert!(manifest.contains("\"kind\":\"failure\""));
        assert!(manifest.contains("encrypted"));
        Ok(())
    }
}
