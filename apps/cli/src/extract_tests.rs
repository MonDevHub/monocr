//! The per-page and per-input wiring of `extract`, run against a fake reader.
//!
//! `outcome.rs` tests the rules as functions of counts. These test that the
//! counts reach them: each mutation listed below left the whole suite passing
//! before this file existed, because the code it changed only ran with a loaded
//! model.
//!
//! - the partial-read counter never incremented, so a partly read book ended
//!   the run without its closing warning;
//! - the all-pages-failed bail disabled, so an empty book was written and the
//!   run exited 0;
//! - `Progress.failed` forced to 0, so a book with a failed page was not
//!   counted as partly read;
//! - a failed PDF page not tallied, or propagated with `?`, so one bad page
//!   either vanished from the verdict or failed the whole book.

use std::collections::HashMap;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use super::*;

/// What the fake returns for one page.
#[derive(Clone, Copy)]
enum Page {
    Text(&'static str),
    /// What line mode returns for a blank image: one line, with no text in it.
    Blank,
    /// What page mode returns when the segmenter finds nothing.
    NoLines,
    Fails,
}

impl Page {
    fn read(self, page: usize) -> Result<(Vec<monocr_onnx::LineResult>, u32)> {
        let line = |text: &str| monocr_onnx::LineResult {
            text: text.to_string(),
            bbox: monocr_onnx::BBox {
                x: 0,
                y: 0,
                w: 800,
                h: 40,
            },
        };
        match self {
            Page::Text(t) => Ok((vec![line(t)], 1000)),
            Page::Blank => Ok((vec![line("")], 1000)),
            Page::NoLines => Ok((Vec::new(), 1000)),
            Page::Fails => Err(anyhow::anyhow!("fake failure on page {page}")),
        }
    }
}

/// A reader that reads each input as scripted. An image reads its first page.
#[derive(Default)]
struct Fake {
    script: HashMap<PathBuf, Vec<Page>>,
}

impl Fake {
    fn pages_of(&self, path: &Path) -> Vec<Page> {
        self.script
            .get(path)
            .unwrap_or_else(|| panic!("no script for {}", path.display()))
            .clone()
    }
}

impl Reader for Fake {
    type Pdf = Vec<Page>;

    async fn read_image(
        &mut self,
        path: &Path,
        _segments: bool,
    ) -> Result<(Vec<monocr_onnx::LineResult>, u32)> {
        self.pages_of(path)[0].read(1)
    }

    async fn open_pdf(&mut self, path: &Path, _dpi: u32) -> Result<Vec<Page>> {
        Ok(self.pages_of(path))
    }

    fn pages(pdf: &Self::Pdf) -> usize {
        pdf.len()
    }

    async fn read_pdf_page(
        &mut self,
        pdf: &Self::Pdf,
        page: usize,
    ) -> Result<(Vec<monocr_onnx::LineResult>, u32)> {
        pdf[page - 1].read(page)
    }
}

impl Sessions for Fake {
    type Reader = Fake;

    async fn session(&mut self, _mode: Mode) -> Result<&mut Fake> {
        Ok(self)
    }
}

/// Inputs on disk in a temp directory, so the content digest can be taken, and
/// a fake scripted to read them. A name ending in `.pdf` is a PDF.
fn inputs(dir: &Path, spec: &[(&str, Vec<Page>)]) -> (Vec<Input>, Fake) {
    let mut fake = Fake::default();
    let inputs = spec
        .iter()
        .map(|(name, pages)| {
            let path = dir.join(name);
            // Distinct bytes, so two inputs never share a digest.
            std::fs::write(&path, name.as_bytes()).unwrap();
            fake.script.insert(path.clone(), pages.clone());
            let kind = if name.ends_with(".pdf") {
                InputKind::Pdf
            } else {
                InputKind::Image
            };
            Input { path, kind }
        })
        .collect();
    (inputs, fake)
}

fn args(json: bool) -> ExtractArgs {
    ExtractArgs {
        paths: Vec::new(),
        output: None,
        recursive: false,
        mode: ModeArg::Auto,
        resume: false,
        json,
        dry_run: false,
        dpi: 150,
        interrupted: Arc::new(AtomicBool::new(false)),
    }
}

fn manifest(out_root: &Path) -> Vec<serde_json::Value> {
    std::fs::read_to_string(out_root.join("manifest.jsonl"))
        .unwrap()
        .lines()
        .map(|l| serde_json::from_str(l).unwrap())
        .collect()
}

/// What `process_one` did with one input.
struct One {
    _dir: tempfile::TempDir,
    out_root: PathBuf,
    result: Result<Progress>,
    stdout: String,
    stderr: String,
}

async fn process(name: &str, pages: Vec<Page>, mode: Mode, json: bool) -> One {
    let dir = tempfile::tempdir().unwrap();
    let (inputs, mut fake) = inputs(dir.path(), &[(name, pages)]);
    let out_root = dir.path().join("out");
    let mut out = OutputDir::create(&out_root).unwrap();
    let decision = mode::Decision {
        mode,
        reason: String::new(),
    };
    let (mut stdout, mut stderr) = (Vec::new(), Vec::new());
    let mut console = Console {
        out: &mut stdout,
        err: &mut stderr,
    };
    let result = process_one(
        &mut fake,
        &inputs[0],
        "book",
        &decision,
        &args(json),
        &mut out,
        &mut console,
    )
    .await;
    One {
        _dir: dir,
        out_root,
        result,
        stdout: String::from_utf8(stdout).unwrap(),
        stderr: String::from_utf8(stderr).unwrap(),
    }
}

#[tokio::test]
async fn a_pdf_with_one_unreadable_page_keeps_the_others_and_counts_the_failure() {
    let pages = vec![Page::Text("one"), Page::Fails, Page::Text("three")];
    let run = process("book.pdf", pages, Mode::Page, false).await;
    let stderr = &run.stderr;

    let Ok(progress) = run.result else {
        panic!("one bad page failed the whole book\n{stderr}");
    };
    assert_eq!(
        (progress.done, progress.failed, progress.expected),
        (2, 1, 3)
    );

    let out = OutputDir::create(&run.out_root).unwrap();
    assert!(out.page_path("book", 1).exists());
    assert!(!out.page_path("book", 2).exists());
    assert!(out.page_path("book", 3).exists());
    assert_eq!(
        std::fs::read_to_string(out.document_path("book")).unwrap(),
        "one\n\nthree"
    );
    assert_eq!(run.stdout, "one\n\nthree\n");

    let failures: Vec<_> = manifest(&run.out_root)
        .into_iter()
        .filter(|r| r["kind"] == "failure")
        .collect();
    assert_eq!(failures.len(), 1, "{failures:?}");
    assert_eq!(failures[0]["page"], 2);

    assert!(stderr.contains("page 2 could not be read"), "{stderr}");
    assert!(stderr.contains("warning: 1 of 3 page(s)"), "{stderr}");
    assert!(!stderr.contains("no text found"), "{stderr}");
}

#[tokio::test]
async fn a_pdf_where_every_page_failed_is_a_failure_and_writes_no_book() {
    let run = process(
        "book.pdf",
        vec![Page::Fails, Page::Fails],
        Mode::Page,
        false,
    )
    .await;

    let Err(e) = run.result else {
        panic!("a book with no page read was reported as read");
    };
    let msg = format!("{e:#}");
    assert!(msg.contains("none of the 2 page(s) could be read"), "{msg}");
    assert!(msg.contains("fake failure on page 1"), "{msg}");

    let out = OutputDir::create(&run.out_root).unwrap();
    assert!(
        !out.document_path("book").exists(),
        "an empty book.txt reads as a book with no text in it"
    );
    assert_eq!(run.stdout, "", "stdout carries results only");
}

#[tokio::test]
async fn a_blank_image_read_in_line_mode_is_reported_as_no_text() {
    // Line mode returns one line whatever the image holds, so counting lines
    // called every blank image text.
    let run = process("line.png", vec![Page::Blank], Mode::Line, false).await;
    assert!(run.result.is_ok());
    assert!(
        run.stderr.contains("no text found on 1 page(s)"),
        "{}",
        run.stderr
    );
}

#[tokio::test]
async fn a_pdf_whose_pages_read_as_nothing_is_reported_as_no_text() {
    let pages = vec![Page::Text(" \t"), Page::NoLines, Page::Blank];
    let run = process("book.pdf", pages, Mode::Page, false).await;
    assert!(run.result.is_ok());
    assert!(
        run.stderr.contains("no text found on 3 page(s)"),
        "{}",
        run.stderr
    );
}

/// What `read_inputs` made of a whole run.
struct Batch {
    _dir: tempfile::TempDir,
    inputs: Vec<Input>,
    out_root: PathBuf,
    end: outcome::RunEnd,
    state: state::State,
}

async fn batch(spec: &[(&str, Vec<Page>)]) -> Batch {
    let dir = tempfile::tempdir().unwrap();
    let (inputs, mut fake) = inputs(dir.path(), spec);
    let out_root = dir.path().join("out");
    let mut out = OutputDir::create(&out_root).unwrap();
    let mut st = state::State::default();
    let (mut stdout, mut stderr) = (Vec::new(), Vec::new());
    let mut console = Console {
        out: &mut stdout,
        err: &mut stderr,
    };
    let end = read_inputs(
        &inputs,
        &args(false),
        &mut fake,
        &mut st,
        &out_root,
        &mut out,
        &mut console,
    )
    .await
    .unwrap();
    Batch {
        _dir: dir,
        inputs,
        out_root,
        end,
        state: st,
    }
}

impl Batch {
    fn is_done(&self, n: usize) -> bool {
        let digest = state::work_digest(&self.inputs[n].path, "page", 150).unwrap();
        let stems = output::assign_stems(
            &self
                .inputs
                .iter()
                .map(|i| i.path.clone())
                .collect::<Vec<_>>(),
        );
        self.state.is_done(&digest, &stems[n])
    }
}

#[tokio::test]
async fn a_partly_read_pdf_ends_the_run_with_a_warning_and_is_read_again_on_resume() {
    let run = batch(&[
        ("book.pdf", vec![Page::Text("one"), Page::Fails]),
        ("page.png", vec![Page::Text("x")]),
    ])
    .await;

    match &run.end {
        outcome::RunEnd::Success { warning: Some(w) } => {
            assert!(w.contains("1 of 2 input(s) were only partly read"), "{w}");
        }
        other => panic!("expected success with a warning, got {other:?}"),
    }
    assert!(!run.is_done(0), "a book with a failed page is not finished");
    assert!(run.is_done(1));
}

#[tokio::test]
async fn a_pdf_with_no_page_read_fails_the_run_but_not_the_inputs_after_it() {
    let run = batch(&[
        ("book.pdf", vec![Page::Fails, Page::Fails]),
        ("page.png", vec![Page::Text("x")]),
    ])
    .await;

    assert_eq!(
        run.end,
        outcome::RunEnd::Failure("1 of 2 input(s) failed; see manifest.jsonl".to_string())
    );
    assert!(
        run.is_done(1),
        "the input after the failed one was still read"
    );

    let records = manifest(&run.out_root);
    let whole_input: Vec<_> = records
        .iter()
        .filter(|r| r["kind"] == "failure" && r["page"].is_null())
        .collect();
    assert_eq!(whole_input.len(), 1, "{records:?}");
    assert!(whole_input[0]["input"]
        .as_str()
        .unwrap()
        .ends_with("book.pdf"));
}

#[tokio::test]
async fn a_clean_run_ends_without_a_warning() {
    let run = batch(&[
        ("book.pdf", vec![Page::Text("one"), Page::Blank]),
        ("page.png", vec![Page::NoLines]),
    ])
    .await;
    assert_eq!(run.end, outcome::RunEnd::Success { warning: None });
    assert!(run.is_done(0) && run.is_done(1));
}
