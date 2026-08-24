//! Turning what the user typed into an ordered list of work.
//!
//! Every existing OCR CLI in this ecosystem takes exactly one directory, lists
//! it non-recursively, and filters to image extensions with a case-sensitive
//! comparison — so `PAGE.JPG` is skipped in silence and a directory of books is
//! not addressable at all. This module is the part that had to be written.

use std::collections::BTreeSet;
use std::ffi::OsStr;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use walkdir::WalkDir;

/// Raster formats the recogniser can read. Compared lowercased: a file named
/// `PAGE.JPG` is a page, and dropping it without a word is how a batch quietly
/// under-reports.
const IMAGE_EXTENSIONS: &[&str] = &["png", "jpg", "jpeg", "bmp", "tif", "tiff", "webp"];

const PDF_EXTENSION: &str = "pdf";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputKind {
    Image,
    Pdf,
}

#[derive(Debug, Clone)]
pub struct Input {
    pub path: PathBuf,
    pub kind: InputKind,
}

/// What was found, and what was deliberately passed over.
///
/// `skipped` exists because a silent skip reads as "there was nothing there".
/// The caller reports it; a batch that ignored 40 files must say so.
#[derive(Debug, Default)]
pub struct Discovery {
    pub inputs: Vec<Input>,
    pub skipped: Vec<(PathBuf, String)>,
}

fn classify(path: &Path) -> Option<InputKind> {
    let ext = path.extension()?.to_str()?.to_ascii_lowercase();
    if ext == PDF_EXTENSION {
        Some(InputKind::Pdf)
    } else if IMAGE_EXTENSIONS.contains(&ext.as_str()) {
        Some(InputKind::Image)
    } else {
        None
    }
}

/// Split a name into text and number runs so `page_2` sorts before `page_10`.
///
/// Lexicographic order puts `page-10` before `page-2`, which silently scrambles
/// a book. The Go binding has this defect today on its PDF page list.
fn natural_key(name: &str) -> Vec<NaturalPart> {
    let mut parts = Vec::new();
    let mut chars = name.chars().peekable();

    while let Some(&c) = chars.peek() {
        if c.is_ascii_digit() {
            let mut n = String::new();
            while let Some(&d) = chars.peek() {
                if !d.is_ascii_digit() {
                    break;
                }
                n.push(d);
                chars.next();
            }
            // A run of digits can exceed u64 in a pathological name; fall back
            // to comparing it as text rather than panicking or truncating.
            match n.parse::<u128>() {
                Ok(v) => parts.push(NaturalPart::Number(v)),
                Err(_) => parts.push(NaturalPart::Text(n)),
            }
        } else {
            let mut s = String::new();
            while let Some(&d) = chars.peek() {
                if d.is_ascii_digit() {
                    break;
                }
                s.push(d.to_ascii_lowercase());
                chars.next();
            }
            parts.push(NaturalPart::Text(s));
        }
    }
    parts
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum NaturalPart {
    // Numbers before text so a numeric run never compares against a word.
    Number(u128),
    Text(String),
}

/// Resolve the user's paths into ordered work.
///
/// Accepts any mix of files and directories. Directories are listed one level
/// deep unless `recursive`. Order is natural by full path, so a run over two
/// books is reproducible and a page never overtakes its predecessor.
pub fn discover(paths: &[PathBuf], recursive: bool) -> Result<Discovery> {
    if paths.is_empty() {
        bail!("no input paths given");
    }

    let mut found: Vec<Input> = Vec::new();
    let mut skipped: Vec<(PathBuf, String)> = Vec::new();
    // Two arguments can name the same file, directly and via a directory.
    // Reading a page twice would double it in the output.
    let mut seen: BTreeSet<PathBuf> = BTreeSet::new();

    for path in paths {
        let meta = std::fs::metadata(path)
            .with_context(|| format!("cannot read input path: {}", path.display()))?;

        if meta.is_file() {
            // An explicitly named file with an unknown extension is a mistake
            // worth reporting, not a file to pass over: the user asked for it.
            match classify(path) {
                Some(kind) => push(&mut found, &mut seen, path, kind),
                None => bail!(
                    "unsupported file type: {} (expected a PDF or one of: {})",
                    path.display(),
                    IMAGE_EXTENSIONS.join(", ")
                ),
            }
            continue;
        }

        if !meta.is_dir() {
            bail!("not a file or directory: {}", path.display());
        }

        let max_depth = if recursive { usize::MAX } else { 1 };
        for entry in WalkDir::new(path)
            .max_depth(max_depth)
            .follow_links(false)
            .sort_by_file_name()
        {
            let entry = match entry {
                Ok(e) => e,
                Err(e) => {
                    // One unreadable entry must not abort a 500-file batch.
                    let at = e.path().unwrap_or(path).to_path_buf();
                    skipped.push((at, format!("unreadable: {e}")));
                    continue;
                }
            };
            if !entry.file_type().is_file() {
                continue;
            }
            let p = entry.path();
            if is_hidden(p) {
                continue;
            }
            match classify(p) {
                Some(kind) => push(&mut found, &mut seen, p, kind),
                None => skipped.push((p.to_path_buf(), "unsupported extension".to_string())),
            }
        }
    }

    found.sort_by_key(|i| natural_key(&i.path.to_string_lossy()));

    Ok(Discovery {
        inputs: found,
        skipped,
    })
}

fn push(out: &mut Vec<Input>, seen: &mut BTreeSet<PathBuf>, path: &Path, kind: InputKind) {
    // Canonicalise for the identity check only. The reported path stays as the
    // user wrote it, because that is what they will recognise in an error.
    let key = std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    if seen.insert(key) {
        out.push(Input {
            path: path.to_path_buf(),
            kind,
        });
    }
}

/// Skip dotfiles. macOS scatters `.DS_Store` through any directory the Finder
/// has opened, and `._`-prefixed AppleDouble files sit beside real images on
/// FAT volumes; neither is a page.
fn is_hidden(path: &Path) -> bool {
    path.file_name()
        .and_then(OsStr::to_str)
        .map(|n| n.starts_with('.'))
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn natural_order_puts_page_2_before_page_10() {
        let mut names = vec!["page-10.png", "page-2.png", "page-1.png"];
        names.sort_by_key(|n| natural_key(n));
        assert_eq!(names, vec!["page-1.png", "page-2.png", "page-10.png"]);
    }

    #[test]
    fn natural_order_is_case_insensitive_on_the_text_runs() {
        let mut names = vec!["B_2.png", "a_10.png", "a_2.png"];
        names.sort_by_key(|n| natural_key(n));
        assert_eq!(names, vec!["a_2.png", "a_10.png", "B_2.png"]);
    }

    #[test]
    fn a_digit_run_too_long_for_u128_still_orders_without_panicking() {
        let huge = "9".repeat(60);
        let a = format!("p{huge}.png");
        let b = "p2.png".to_string();
        let mut names = [a.as_str(), b.as_str()];
        // The assertion is that this does not panic and is a total order.
        names.sort_by_key(|n| natural_key(n));
        assert_eq!(names.len(), 2);
    }

    #[test]
    fn extensions_are_matched_case_insensitively() {
        assert_eq!(classify(Path::new("a/PAGE.JPG")), Some(InputKind::Image));
        assert_eq!(classify(Path::new("a/scan.PnG")), Some(InputKind::Image));
        assert_eq!(classify(Path::new("a/book.PDF")), Some(InputKind::Pdf));
        assert_eq!(classify(Path::new("a/notes.txt")), None);
        assert_eq!(classify(Path::new("a/no-extension")), None);
    }

    #[test]
    fn an_empty_path_list_is_an_error_not_an_empty_run() {
        assert!(discover(&[], false).is_err());
    }

    #[test]
    fn dotfiles_are_not_pages() {
        assert!(is_hidden(Path::new("dir/.DS_Store")));
        assert!(is_hidden(Path::new("dir/._page.png")));
        assert!(!is_hidden(Path::new("dir/page.png")));
    }
}
