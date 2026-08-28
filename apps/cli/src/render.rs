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

    /// The PDF these tests rasterise. It lives in the sibling `monocr-onnx`
    /// checkout, which CI clones and a fresh laptop may not have.
    const FIXTURE: &str = "../../../monocr-onnx/data/pdfs/Mon_E_library.pdf";

    /// Exact `"1"`, matching mon_OCR's `os.environ.get(...) == "1"`, so a stray
    /// `REQUIRE_E2E=0` cannot read as "yes".
    fn env_is_one(name: &str) -> bool {
        std::env::var(name).as_deref() == Ok("1")
    }

    /// What a missing precondition means for the test that asked.
    #[derive(Debug, PartialEq, Eq)]
    enum Verdict {
        Run,
        Skip,
        /// Absent, and nobody said that was acceptable.
        Fail,
        /// Absent, and `REQUIRE_E2E=1` says the opt-out does not apply.
        FailRequired,
    }

    /// The policy, as a pure function of its inputs.
    ///
    /// Split from the environment lookup for the reason `config::merge` records
    /// (`main.rs:219`): the rule that decides whether coverage may be dropped is
    /// exactly the rule worth testing directly, and setting process-wide
    /// environment variables from inside a parallel test run is how a suite
    /// starts lying in a different way.
    fn verdict(met: bool, require_e2e: bool, opted_out: bool) -> Verdict {
        if met {
            Verdict::Run
        } else if require_e2e {
            // Outranks the opt-out. This is the switch mon_OCR's Makefile sets
            // and the one for this repo's CI to set, and a green run under it must
            // not be purchasable with a second environment variable.
            Verdict::FailRequired
        } else if opted_out {
            Verdict::Skip
        } else {
            Verdict::Fail
        }
    }

    /// Gate for the tests that need real poppler and a real PDF.
    ///
    /// It used to be no gate at all. Three of this file's four PDF tests printed
    /// `skipping: ...` and `return Ok(())`, so running the test binary with
    /// `PATH=/nonexistent` still reported `55 passed; 0 failed; 0 ignored` and
    /// `a_real_pdf_reports_its_page_count_and_renders_one_page` "passed" in
    /// 0.00s having asserted nothing. libtest has no runtime skip channel, so
    /// the summary was identical whether the coverage ran or evaporated — the
    /// false negative se-brain `standards/testing.md` §20 names: "N passed, M
    /// skipped" with an unnoticed M is not a pass.
    ///
    /// mon_OCR spells this guard `requires()` (`tests/e2e/test_pipeline.py:30`)
    /// and defaults to `pytest.skip`, which is honest there because pytest
    /// prints the skip count. With no such channel here the default is inverted:
    /// a missing precondition fails, and dropping the coverage is something an
    /// operator has to type.
    ///
    /// CI already knew the risk and covered only half of it: the `poppler` step
    /// in `.github/workflows/ci.yml` installs the binaries because "the
    /// renderer's tests skip themselves without them — which would make this
    /// job pass while testing nothing". Installing poppler made the skip
    /// unlikely. It did not make it visible, and it did nothing for the fixture,
    /// which comes from the sibling monocr-onnx checkout.
    fn precondition(met: bool, why: &str) -> Result<bool> {
        act_on(
            verdict(
                met,
                env_is_one("REQUIRE_E2E"),
                env_is_one("MONOCR_SKIP_E2E"),
            ),
            why,
        )
    }

    /// What to do with a verdict, split out from where the verdict comes from.
    ///
    /// `verdict` was pure and tested; `precondition` read the environment and was
    /// not. A swap of those two lookups, or one arm returning `Ok(false)` instead
    /// of failing, would have restored the invisible skip this whole change exists
    /// to remove while every test stayed green. That is the same false negative one
    /// layer out, so the mapping is testable too and the untested part is now the
    /// two `env_is_one` calls and nothing else.
    fn act_on(v: Verdict, why: &str) -> Result<bool> {
        match v {
            Verdict::Run => Ok(true),
            Verdict::Skip => {
                eprintln!("skipping (MONOCR_SKIP_E2E=1): {why}");
                Ok(false)
            }
            Verdict::FailRequired => {
                bail!("REQUIRE_E2E=1 but precondition not met: {why}")
            }
            Verdict::Fail => bail!(
                "{why}. This test rasterises a real PDF with real poppler, and a \
                 pass bought without one is a green light for code nothing ran. \
                 Install poppler: `brew install poppler` on macOS, `apt-get \
                 install poppler-utils` on Debian, and keep a monocr-onnx \
                 checkout beside this repo for the PDF. Set MONOCR_SKIP_E2E=1 to \
                 drop this coverage deliberately."
            ),
        }
    }

    /// Whether a poppler binary is runnable at all. `-v` is the cheapest
    /// invocation that proves the exec succeeded.
    async fn is_runnable(tool: &str) -> bool {
        Command::new(tool)
            .arg("-v")
            .stdin(Stdio::null())
            .output()
            .await
            .is_ok()
    }

    #[test]
    fn a_missing_precondition_fails_unless_it_was_opted_out_of() {
        // The defect, first line: this case used to be a skip, and libtest
        // reported it as a pass. Three tests in this file asserted nothing while
        // the summary read `0 failed; 0 ignored`.
        assert_eq!(verdict(false, false, false), Verdict::Fail);
        // Dropping the coverage stays possible, but only on purpose.
        assert_eq!(verdict(false, false, true), Verdict::Skip);
        // And the opt-out does not reach CI.
        assert_eq!(verdict(false, true, true), Verdict::FailRequired);
        assert_eq!(verdict(false, true, false), Verdict::FailRequired);
        // A precondition that is met runs whatever the environment says.
        assert_eq!(verdict(true, false, false), Verdict::Run);
        assert_eq!(verdict(true, false, true), Verdict::Run);
        assert_eq!(verdict(true, true, false), Verdict::Run);
        assert_eq!(verdict(true, true, true), Verdict::Run);
    }

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

        if !precondition(is_runnable("pdfinfo").await, "pdfinfo is not installed")? {
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
        let fixture = Path::new(FIXTURE);
        if !precondition(
            fixture.exists(),
            &format!("the test PDF is not present at {FIXTURE}"),
        )? {
            return Ok(());
        }
        if !precondition(is_runnable("pdftoppm").await, "pdftoppm is not installed")? {
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
        let fixture = Path::new(FIXTURE);
        if !precondition(
            fixture.exists() && is_runnable("pdftoppm").await,
            &format!("the test PDF at {FIXTURE} or pdftoppm is not present"),
        )? {
            return Ok(());
        }
        let doc = PdfDocument::open(fixture, 72).await?;
        assert!(doc.render_page(0).await.is_err());
        assert!(doc.render_page(doc.pages() + 1).await.is_err());
        Ok(())
    }

    /// Every verdict maps to the right action.
    ///
    /// Pairs with `a_missing_precondition_fails_unless_it_was_opted_out_of`, which
    /// covers `verdict`. Between them the only untested step left is reading the
    /// two environment variables.
    #[test]
    fn each_verdict_maps_to_the_right_action() {
        assert!(
            act_on(Verdict::Run, "why").expect("Run must not fail"),
            "Run means the test body executes"
        );
        assert!(
            !act_on(Verdict::Skip, "why").expect("Skip must not fail"),
            "Skip means the body is not executed, and it must not be reported as run"
        );
        assert!(
            act_on(Verdict::Fail, "why").is_err(),
            "an unmet precondition with no opt-out must FAIL, not skip silently"
        );
        assert!(
            act_on(Verdict::FailRequired, "why").is_err(),
            "REQUIRE_E2E=1 must outrank the opt-out"
        );
    }
}
