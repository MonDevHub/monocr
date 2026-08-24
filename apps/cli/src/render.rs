//! Rasterising a PDF one page at a time.
//!
//! Every existing path in this ecosystem materialises the whole document first —
//! `convert_from_path` returns a list of every page, and the `pdftoppm` callers
//! write every page into a temp directory before any OCR starts. At 300 DPI a
//! 500-page book is tens of gigabytes of bitmap, so "it worked on a 4-page
//! sample" is not evidence it works on a book.
//!
//! This renders page N on demand and drops it before page N+1, so peak memory is
//! one page regardless of length. `pdftoppm` is invoked with an argv vector and
//! never a shell string: the JS binding interpolates the path into an `exec`
//! string, so a filename containing a quote breaks it.

use std::path::{Path, PathBuf};
use std::process::Stdio;

use anyhow::{bail, Context, Result};
use tokio::process::Command;

/// Refuse a file larger than this rather than discovering the problem in swap.
/// Carried over from `pdf2audio`, which guards the same shape of input.
const MAX_PDF_BYTES: u64 = 500 * 1024 * 1024;

/// A document longer than this is more likely a mistake than a book.
const MAX_PDF_PAGES: usize = 3000;

const DEFAULT_DPI: u32 = 300;

pub fn default_dpi() -> u32 {
    DEFAULT_DPI
}

/// A PDF opened for page-by-page rendering.
#[derive(Debug)]
pub struct PdfDocument {
    path: PathBuf,
    pages: usize,
    dpi: u32,
    // Held so the directory outlives every page rendered into it.
    scratch: tempfile::TempDir,
}

impl PdfDocument {
    pub async fn open(path: &Path, dpi: u32) -> Result<Self> {
        let meta = std::fs::metadata(path)
            .with_context(|| format!("cannot stat PDF: {}", path.display()))?;

        if meta.len() == 0 {
            bail!("PDF is empty: {}", path.display());
        }
        if meta.len() > MAX_PDF_BYTES {
            bail!(
                "PDF is {} bytes, over the {} byte limit: {}",
                meta.len(),
                MAX_PDF_BYTES,
                path.display()
            );
        }

        let pages = page_count(path).await?;
        if pages == 0 {
            bail!("PDF reports zero pages: {}", path.display());
        }
        if pages > MAX_PDF_PAGES {
            bail!(
                "PDF has {pages} pages, over the {MAX_PDF_PAGES} page limit: {}",
                path.display()
            );
        }

        let scratch = tempfile::tempdir().context("cannot create a scratch directory")?;

        Ok(Self {
            path: path.to_path_buf(),
            pages,
            dpi,
            scratch,
        })
    }

    pub fn pages(&self) -> usize {
        self.pages
    }

    /// Render one page and hand back the path to it.
    ///
    /// `page` is 1-based, matching what `pdftoppm` and every page number a user
    /// will type. The returned file is deleted by `RenderedPage`'s drop, so the
    /// caller cannot accumulate a document's worth of bitmaps by holding them.
    pub async fn render_page(&self, page: usize) -> Result<RenderedPage> {
        if page == 0 || page > self.pages {
            bail!("page {page} is outside 1..={}", self.pages);
        }

        let prefix = self.scratch.path().join(format!("p{page}"));
        let status = Command::new("pdftoppm")
            .arg("-f")
            .arg(page.to_string())
            .arg("-l")
            .arg(page.to_string())
            .arg("-r")
            .arg(self.dpi.to_string())
            .arg("-png")
            // Grey directly: the recogniser wants one channel, and asking
            // poppler for it avoids a conversion and three quarters of the bytes.
            .arg("-gray")
            .arg(&self.path)
            .arg(&prefix)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .output()
            .await
            .context(
                "cannot run pdftoppm. Install poppler: `brew install poppler` on macOS, \
                 `apt-get install poppler-utils` on Debian",
            )?;

        if !status.status.success() {
            let err = String::from_utf8_lossy(&status.stderr);
            bail!(
                "pdftoppm failed on page {page} of {}: {}",
                self.path.display(),
                err.trim()
            );
        }

        // pdftoppm appends its own zero-padded page suffix, and the width of that
        // padding depends on the page count. Rather than predict it, find the one
        // file this invocation produced.
        let produced = find_single_output(self.scratch.path(), &format!("p{page}-"))?;

        Ok(RenderedPage { path: produced })
    }
}

/// One rendered page on disk, deleted when dropped.
pub struct RenderedPage {
    path: PathBuf,
}

impl RenderedPage {
    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for RenderedPage {
    fn drop(&mut self) {
        // The whole point is that a page does not outlive its use. Failure to
        // unlink is not worth failing a run over; the scratch dir also goes.
        let _ = std::fs::remove_file(&self.path);
    }
}

async fn page_count(path: &Path) -> Result<usize> {
    let out = Command::new("pdfinfo")
        .arg(path)
        .stdin(Stdio::null())
        .output()
        .await
        .context(
            "cannot run pdfinfo. Install poppler: `brew install poppler` on macOS, \
             `apt-get install poppler-utils` on Debian",
        )?;

    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        // An encrypted or malformed file lands here. The message carries
        // poppler's own reason, which is more useful than a generic failure.
        bail!(
            "cannot read PDF {}: {}",
            path.display(),
            err.trim().lines().next().unwrap_or("unreadable")
        );
    }

    let text = String::from_utf8_lossy(&out.stdout);
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("Pages:") {
            return rest
                .trim()
                .parse::<usize>()
                .with_context(|| format!("cannot parse page count from {line:?}"));
        }
    }
    bail!("pdfinfo did not report a page count for {}", path.display())
}

fn find_single_output(dir: &Path, prefix: &str) -> Result<PathBuf> {
    let mut hits: Vec<PathBuf> = std::fs::read_dir(dir)
        .with_context(|| format!("cannot list {}", dir.display()))?
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| {
            p.file_name()
                .and_then(|n| n.to_str())
                .map(|n| n.starts_with(prefix))
                .unwrap_or(false)
        })
        .collect();

    match hits.len() {
        1 => Ok(hits.remove(0)),
        0 => bail!("pdftoppm reported success but produced no file for {prefix}"),
        n => bail!("pdftoppm produced {n} files for {prefix}, expected 1"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn an_empty_file_is_rejected_before_poppler_is_asked() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let p = tmp.path().join("empty.pdf");
        std::fs::write(&p, b"")?;

        let err = PdfDocument::open(&p, DEFAULT_DPI).await.unwrap_err();
        assert!(err.to_string().contains("empty"), "unexpected error: {err}");
        Ok(())
    }

    #[tokio::test]
    async fn a_file_that_is_not_a_pdf_fails_with_popplers_reason() -> Result<()> {
        let tmp = tempfile::tempdir()?;
        let p = tmp.path().join("not-a.pdf");
        std::fs::write(&p, b"this is plain text, not a PDF")?;

        // Requires poppler; skip rather than fail if the environment lacks it.
        if Command::new("pdfinfo").arg("-v").output().await.is_err() {
            eprintln!("skipping: pdfinfo not installed");
            return Ok(());
        }

        let err = PdfDocument::open(&p, DEFAULT_DPI).await.unwrap_err();
        assert!(
            err.to_string().contains("cannot read PDF"),
            "unexpected error: {err}"
        );
        Ok(())
    }

    #[tokio::test]
    async fn a_real_pdf_reports_its_page_count_and_renders_one_page() -> Result<()> {
        let fixture = Path::new("../../../monocr-onnx/data/pdfs/Mon_E_library.pdf");
        if !fixture.exists() {
            eprintln!("skipping: fixture not present at {}", fixture.display());
            return Ok(());
        }
        if Command::new("pdftoppm").arg("-v").output().await.is_err() {
            eprintln!("skipping: pdftoppm not installed");
            return Ok(());
        }

        let doc = PdfDocument::open(fixture, 72).await?;
        assert!(doc.pages() > 0);

        let page = doc.render_page(1).await?;
        let rendered = page.path().to_path_buf();
        assert!(rendered.exists(), "page 1 was not written");
        assert!(std::fs::metadata(&rendered)?.len() > 0);

        drop(page);
        // A page must not outlive its use, or a book accumulates on disk.
        assert!(!rendered.exists(), "rendered page was not cleaned up");
        Ok(())
    }

    #[tokio::test]
    async fn a_page_outside_the_document_is_an_error() -> Result<()> {
        let fixture = Path::new("../../../monocr-onnx/data/pdfs/Mon_E_library.pdf");
        if !fixture.exists() || Command::new("pdftoppm").arg("-v").output().await.is_err() {
            eprintln!("skipping: fixture or poppler not present");
            return Ok(());
        }
        let doc = PdfDocument::open(fixture, 72).await?;
        assert!(doc.render_page(0).await.is_err());
        assert!(doc.render_page(doc.pages() + 1).await.is_err());
        Ok(())
    }
}
