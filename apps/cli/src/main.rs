//! Batch Mon OCR over books, PDFs and images.
//!
//! The stream contract: **stdout carries results and nothing else**; progress,
//! warnings and errors go to stderr. That is what lets
//! `monocr-cli extract book.pdf --json | jq` work while the operator still sees
//! progress. Exit 0 on success, 1 on failure, 2 on a usage error (from `clap`),
//! 130 on Ctrl-C; what counts as a failure is decided in `outcome.rs` and stated in the
//! README's "Exit codes" section. Colour and progress switch off when stdout is
//! not a TTY, and `NO_COLOR` is honoured.
//!
//! This is a delivery surface, not an OCR implementation. Segmentation, tiling,
//! the model pin and the charset contract live in the `monocr-onnx` library.
//! Domain logic stays in the library and each surface only adapts it: a sixth
//! copy of that logic here would be one more port to keep in step.

mod config;
mod discover;
mod mode;
mod outcome;
mod output;
mod render;
mod state;

#[cfg(test)]
mod extract_tests;

use std::io::{IsTerminal, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use anyhow::{Context, Result};
use clap::{Parser, Subcommand, ValueEnum};

use discover::{Discovery, Input, InputKind};
use mode::Mode;
use output::{FailureRecord, LineRecord, ManifestEntry, OutputDir, PageRecord};

/// `eprintln!` to a `Console`'s stderr, which is the process's stderr in a real
/// run and a buffer in the tests. A failed write is ignored rather than ending
/// the run: a progress line nobody can read is no reason to stop reading books.
macro_rules! note {
    ($console:expr, $($arg:tt)*) => {{
        let _ = writeln!($console.err, $($arg)*);
    }};
}

/// Exit code for an interrupted run. 128 + SIGINT, the shell convention.
const EXIT_INTERRUPTED: u8 = 130;

#[derive(Parser)]
#[command(
    name = "monocr-cli",
    about = "Extract Mon text from books, PDFs and images, on-device",
    version
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Extract text from files or directories.
    Extract {
        /// Files, directories, or a mix of both. Optional when a config file
        /// supplies `input.paths`; giving any here replaces the file's list.
        paths: Vec<PathBuf>,

        /// Read settings from this YAML file. Defaults to `monocr.yaml` in the
        /// working directory when one exists. See `config.rs` for the merge rule.
        #[arg(long, value_name = "PATH")]
        config: Option<PathBuf>,

        /// Where to write results. Required unless --dry-run.
        #[arg(short, long)]
        output: Option<PathBuf>,

        /// Descend into subdirectories.
        #[arg(short, long)]
        recursive: bool,

        /// Segmentation regime. `auto` decides per input; see `inspect`.
        /// Unset means "take the config file's value", then `auto`.
        #[arg(long, value_enum)]
        mode: Option<ModeArg>,

        /// Skip inputs already completed in this output directory.
        #[arg(long)]
        resume: bool,

        /// Emit one JSON object per input on stdout instead of plain text.
        #[arg(long)]
        json: bool,

        /// Resolve and report the work without reading a model or writing a file.
        #[arg(long)]
        dry_run: bool,

        /// Rasterisation resolution for PDF pages. Unset means "take the config
        /// file's value", then the built-in default.
        #[arg(long)]
        dpi: Option<u32>,
    },

    /// Report what a path contains and which mode `auto` would choose.
    Inspect {
        #[arg(required = true)]
        paths: Vec<PathBuf>,

        #[arg(short, long)]
        recursive: bool,

        #[arg(long)]
        json: bool,
    },

    /// Download and cache the pinned model before a run.
    Download,
}

#[derive(Copy, Clone, PartialEq, Eq, ValueEnum)]
enum ModeArg {
    Auto,
    Page,
    Sparse,
    Line,
}

impl ModeArg {
    /// The spelling this mode has in a config file. Paired with
    /// `from_config_str` so the two directions cannot drift apart, and with
    /// `config::VALID_MODES`, which is what refuses a bad value while loading.
    fn as_config_str(self) -> &'static str {
        match self {
            ModeArg::Auto => "auto",
            ModeArg::Page => "page",
            ModeArg::Sparse => "sparse",
            ModeArg::Line => "line",
        }
    }

    /// `None` means `auto`; `config::merge` normalises the two to one.
    fn from_config_str(s: Option<&str>) -> ModeArg {
        match s {
            Some("page") => ModeArg::Page,
            Some("sparse") => ModeArg::Sparse,
            Some("line") => ModeArg::Line,
            _ => ModeArg::Auto,
        }
    }
}

impl ModeArg {
    fn resolve(
        self,
        kind: InputKind,
        path: &std::path::Path,
        dimensions: Option<(u32, u32)>,
    ) -> mode::Decision {
        match self {
            ModeArg::Auto => mode::decide(kind, path, dimensions),
            ModeArg::Page => mode::Decision {
                mode: Mode::Page,
                reason: "requested with --mode page".to_string(),
            },
            ModeArg::Sparse => mode::Decision {
                mode: Mode::Sparse,
                reason: "requested with --mode sparse".to_string(),
            },
            ModeArg::Line => mode::Decision {
                mode: Mode::Line,
                reason: "requested with --mode line".to_string(),
            },
        }
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    let cli = Cli::parse();

    // Ctrl-C must stop work and leave a truthful output directory, not dump a
    // backtrace. Everything the run writes is atomic, so the worst an interrupt
    // can cost is the page in flight.
    let interrupted = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    {
        let flag = interrupted.clone();
        tokio::spawn(async move {
            if tokio::signal::ctrl_c().await.is_ok() {
                flag.store(true, std::sync::atomic::Ordering::SeqCst);
                eprintln!("\nstopping after the current page");
            }
        });
    }

    match run(cli, interrupted.clone()).await {
        Ok(()) => {
            if interrupted.load(std::sync::atomic::Ordering::SeqCst) {
                return ExitCode::from(EXIT_INTERRUPTED);
            }
            ExitCode::SUCCESS
        }
        Err(e) => {
            // The chain carries the context added at each layer, which is what
            // turns "cannot open input" into a path the user can act on.
            eprintln!("error: {e:#}");
            ExitCode::FAILURE
        }
    }
}

async fn run(cli: Cli, interrupted: std::sync::Arc<std::sync::atomic::AtomicBool>) -> Result<()> {
    match cli.command {
        Command::Download => download().await,
        Command::Inspect {
            paths,
            recursive,
            json,
        } => inspect(&paths, recursive, json),
        Command::Extract {
            paths,
            config,
            output,
            recursive,
            mode,
            resume,
            json,
            dry_run,
            dpi,
        } => {
            // The file is the baseline; a flag is the exception. The merge rule
            // itself lives in `config::merge`, a pure function of its inputs, so
            // it is tested without a filesystem, a model or a subprocess.
            let file = config::resolve(config.as_deref())?.unwrap_or_default();
            let resolved = config::merge(
                config::Flags {
                    paths,
                    output,
                    recursive,
                    mode: mode.map(|m| m.as_config_str().to_string()),
                    resume,
                    json,
                    dry_run,
                    dpi,
                },
                file,
            );

            if resolved.paths.is_empty() {
                anyhow::bail!(
                    "no inputs. Give paths on the command line, or set `input.paths` \
                     in {} (or the file named by --config)",
                    config::DEFAULT_CONFIG
                );
            }

            extract(ExtractArgs {
                paths: resolved.paths,
                output: resolved.output,
                recursive: resolved.recursive,
                mode: ModeArg::from_config_str(resolved.mode.as_deref()),
                resume: resolved.resume,
                json: resolved.json,
                dry_run: resolved.dry_run,
                dpi: resolved.dpi.unwrap_or_else(render::default_dpi),
                interrupted,
            })
            .await
        }
    }
}

struct ExtractArgs {
    paths: Vec<PathBuf>,
    output: Option<PathBuf>,
    recursive: bool,
    mode: ModeArg,
    resume: bool,
    json: bool,
    dry_run: bool,
    dpi: u32,
    interrupted: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

async fn download() -> Result<()> {
    // Priming the cache is a side effect, so the confirmation is a diagnostic
    // and belongs on stderr; nothing is piped out of this command.
    eprintln!("fetching the pinned model into the local cache");
    let _ = monocr_onnx::MonOcr::builder()
        .build()
        .await
        .context("cannot download or load the pinned model")?;
    eprintln!("model ready");
    Ok(())
}

fn inspect(paths: &[PathBuf], recursive: bool, json: bool) -> Result<()> {
    let found = discover::discover(paths, recursive)?;
    report_skipped(&found);

    let mut stdout = std::io::stdout().lock();

    if json {
        let items: Vec<_> = found
            .inputs
            .iter()
            .map(|i| {
                let h = image_dimensions(i);
                let d = mode::decide(i.kind, &i.path, h);
                serde_json::json!({
                    "path": i.path.display().to_string(),
                    "kind": match i.kind { InputKind::Pdf => "pdf", InputKind::Image => "image" },
                    "width": h.map(|(w, _)| w),
                    "height": h.map(|(_, y)| y),
                    "mode": d.mode.to_string(),
                    "reason": d.reason,
                })
            })
            .collect();
        writeln!(stdout, "{}", serde_json::to_string_pretty(&items)?)?;
        return Ok(());
    }

    for i in &found.inputs {
        let h = image_dimensions(i);
        let d = mode::decide(i.kind, &i.path, h);
        writeln!(stdout, "{}", i.path.display())?;
        writeln!(stdout, "  mode: {} ({})", d.mode, d.reason)?;
    }
    writeln!(stdout, "\n{} input(s)", found.inputs.len())?;
    Ok(())
}

/// Dimensions of an image input, read from the header rather than by decoding.
/// `None` is a valid answer and the mode decision handles it.
fn image_dimensions(input: &Input) -> Option<(u32, u32)> {
    if input.kind != InputKind::Image {
        return None;
    }
    image::image_dimensions(&input.path).ok()
}

async fn extract(args: ExtractArgs) -> Result<()> {
    let found = discover::discover(&args.paths, args.recursive)?;
    report_skipped(&found);

    if found.inputs.is_empty() {
        anyhow::bail!("no supported inputs found");
    }

    let out_root = match (&args.output, args.dry_run) {
        (Some(p), _) => p.clone(),
        (None, true) => PathBuf::from("."),
        (None, false) => anyhow::bail!("--output is required unless --dry-run is given"),
    };

    if args.dry_run {
        let mut stdout = std::io::stdout().lock();
        for i in &found.inputs {
            let d = args.mode.resolve(i.kind, &i.path, image_dimensions(i));
            writeln!(stdout, "{}\t{}", d.mode, i.path.display())?;
        }
        eprintln!("{} input(s); nothing written", found.inputs.len());
        return Ok(());
    }

    // Taken before any work so two concurrent runs cannot interleave their state.
    let _lock = state::DirLock::acquire(&out_root)?;
    let mut st = if args.resume {
        state::State::load(&out_root)?
    } else {
        state::State::default()
    };
    let mut out = OutputDir::create(&out_root)?;

    for (path, reason) in &found.skipped {
        out.record(&ManifestEntry::Skipped {
            path: path.display().to_string(),
            reason: reason.clone(),
        })?;
    }

    // One session per distinct mode, built on first use and reused after; see
    // the `Sessions` impl below.
    let mut sessions: Vec<(Mode, monocr_onnx::MonOcr)> = Vec::new();
    let (mut stdout, mut stderr) = (std::io::stdout(), std::io::stderr());
    let mut console = Console {
        out: &mut stdout,
        err: &mut stderr,
    };

    let end = read_inputs(
        &found.inputs,
        &args,
        &mut sessions,
        &mut st,
        &out_root,
        &mut out,
        &mut console,
    )
    .await?;

    match end {
        outcome::RunEnd::Failure(msg) => anyhow::bail!(msg),
        outcome::RunEnd::Success { warning } => {
            if let Some(w) = warning {
                eprintln!("{w}");
            }
            Ok(())
        }
    }
}

/// Where a run's results and its progress go: stdout and stderr in a real run,
/// buffers in the tests, so what each stream carries is checked, not assumed.
struct Console<'a> {
    out: &'a mut dyn Write,
    err: &'a mut dyn Write,
}

/// The calls one input makes into the OCR crate and poppler.
///
/// The seam the tests put a fake in. What happens around each call — what is
/// tallied, recorded, written and counted towards the exit code — is where a
/// run's outcome is decided, and with the crate called inline none of it could
/// run without a loaded model, so a mistake in it passed every test. The real
/// implementation, on `MonOcr` below, only calls through.
trait Reader {
    /// A PDF opened for reading one page at a time.
    type Pdf;

    /// Recognise an image input, returning its lines and its pixel height.
    /// `segments` is false in line mode, which reads the image as one line.
    async fn read_image(
        &mut self,
        path: &Path,
        segments: bool,
    ) -> Result<(Vec<monocr_onnx::LineResult>, u32)>;

    async fn open_pdf(&mut self, path: &Path, dpi: u32) -> Result<Self::Pdf>;

    fn pages(pdf: &Self::Pdf) -> usize;

    /// Render one PDF page and recognise it, returning its lines and its height.
    async fn read_pdf_page(
        &mut self,
        pdf: &Self::Pdf,
        page: usize,
    ) -> Result<(Vec<monocr_onnx::LineResult>, u32)>;
}

impl Reader for monocr_onnx::MonOcr {
    type Pdf = render::PdfDocument;

    async fn read_image(
        &mut self,
        path: &Path,
        segments: bool,
    ) -> Result<(Vec<monocr_onnx::LineResult>, u32)> {
        // Line mode must not go through the page segmenter. A projection
        // profile over something that is already one line finds no gap to
        // cut at and fragments it instead of reading it, so the library has
        // a separate entry point that tiles without segmenting.
        let lines = if segments {
            self.predict_page(path).await?
        } else {
            vec![self.predict_single_line(path).await?]
        };
        Ok((lines, page_height_of(path)))
    }

    async fn open_pdf(&mut self, path: &Path, dpi: u32) -> Result<render::PdfDocument> {
        render::PdfDocument::open(path, dpi).await
    }

    fn pages(pdf: &render::PdfDocument) -> usize {
        pdf.pages()
    }

    /// Rendered, read, then dropped before the next page is touched: peak memory
    /// is one page, not one book.
    async fn read_pdf_page(
        &mut self,
        pdf: &render::PdfDocument,
        page: usize,
    ) -> Result<(Vec<monocr_onnx::LineResult>, u32)> {
        let rendered = pdf.render_page(page).await?;
        let height = page_height_of(rendered.path());
        let lines = self
            .predict_page(rendered.path())
            .await
            .with_context(|| format!("cannot read page {page}"))?;
        Ok((lines, height))
    }
}

/// A reader for each mode, built on first use.
trait Sessions {
    type Reader: Reader;

    async fn session(&mut self, mode: Mode) -> Result<&mut Self::Reader>;
}

/// One session per distinct mode, built on first use and reused after.
///
/// Three of the five existing CLIs rebuild the ORT session per file while a
/// session-reusing call sits unused beside them; that is the cost this avoids.
/// A session is keyed by mode because the density ratio is fixed at build time,
/// and a mixed run must not silently apply one mode's ratio to another mode's
/// input.
///
/// Held in a Vec rather than a map: there are three modes at most, and a linear
/// scan over three entries is not worth a hash.
impl Sessions for Vec<(Mode, monocr_onnx::MonOcr)> {
    type Reader = monocr_onnx::MonOcr;

    async fn session(&mut self, mode: Mode) -> Result<&mut monocr_onnx::MonOcr> {
        if let Some(i) = self.iter().position(|(m, _)| *m == mode) {
            return Ok(&mut self[i].1);
        }

        eprintln!("loading the model for {mode} mode");
        let mut builder = monocr_onnx::MonOcr::builder();
        if let Some(ratio) = mode.density_ratio() {
            builder = builder.density_threshold_ratio(ratio);
        }
        let ocr = builder
            .build()
            .await
            .with_context(|| format!("cannot load the pinned model for {mode} mode"))?;

        self.push((mode, ocr));
        let last = self.len() - 1;
        Ok(&mut self[last].1)
    }
}

/// Read every input in turn, and judge the run without acting on the judgement.
///
/// Apart from `extract` so it runs in the tests against a fake reader: this is
/// where an input is counted as failed or as partly read, which decides the
/// exit code and the closing warning.
async fn read_inputs<S: Sessions>(
    inputs: &[Input],
    args: &ExtractArgs,
    sessions: &mut S,
    st: &mut state::State,
    out_root: &Path,
    out: &mut OutputDir,
    console: &mut Console<'_>,
) -> Result<outcome::RunEnd> {
    let mut failures = 0usize;
    // Inputs with some pages read and some failed. They do not change the exit
    // code, so they are counted to be named again at the end of a long batch,
    // where the per-input warning has long scrolled away.
    let mut partial = 0usize;
    let total = inputs.len();

    // Resolved for the run as a whole, not per input, because a name collision
    // is a property of the list. Two books called `book.pdf` in different
    // directories used to share one `out/book.txt` and one `out/book/`, so the
    // first one read was overwritten by the second in silence; `output.rs:139`
    // describes that hazard and takes a `disambiguator` for it, and this caller
    // is the half that was missing.
    let paths: Vec<PathBuf> = inputs.iter().map(|i| i.path.clone()).collect();
    let stems = output::assign_stems(&paths);

    for (n, input) in inputs.iter().enumerate() {
        if args.interrupted.load(std::sync::atomic::Ordering::SeqCst) {
            note!(console, "interrupted after {n} of {total}");
            break;
        }

        let decision = args
            .mode
            .resolve(input.kind, &input.path, image_dimensions(input));
        // Per input, not with `?`: the digest opens the file, so `?` here let one
        // file without read permission end the whole batch, with no manifest
        // record and every later input left unread.
        let digest = match state::work_digest(&input.path, &decision.mode.to_string(), args.dpi) {
            Ok(d) => d,
            Err(e) => {
                note!(console, "[{}/{}] {}", n + 1, total, input.path.display());
                failures += 1;
                record_failure(out, console, input, &e)?;
                continue;
            }
        };

        if args.resume && st.is_done(&digest, &stems[n]) {
            note!(
                console,
                "[{}/{}] skip (done) {}",
                n + 1,
                total,
                input.path.display()
            );
            continue;
        }

        note!(
            console,
            "[{}/{}] {} ({})",
            n + 1,
            total,
            input.path.display(),
            decision.mode
        );

        let reader = sessions.session(decision.mode).await?;

        match process_one(reader, input, &stems[n], &decision, args, out, console).await {
            Ok(progress) => {
                st.record_progress(
                    &input.path,
                    &digest,
                    &stems[n],
                    progress.done,
                    progress.expected,
                );
                // Saved per input so an interrupt loses at most the input in
                // flight, not the whole run's record.
                st.save(out_root)?;

                if progress.failed > 0 {
                    partial += 1;
                }
                if progress.done + progress.failed < progress.expected {
                    // Said out loud because the state file's `is_done` is now
                    // the only thing standing between an interrupted book and a
                    // permanent skip, and an operator who does not know the book
                    // is unfinished will not re-run it. Ctrl-C at page 3 of 500
                    // used to print nothing here and record the book as final.
                    note!(
                        console,
                        // `--resume` restarts this book at page 1 rather than continuing from
                        // where it stopped, so the wording says "again" instead of
                        // "finish it". Pages already on disk are rewritten; nothing is
                        // reused, because reusing them would need the digest recorded
                        // per page file, or a --dpi 150 run's pages get adopted by a
                        // --dpi 300 --resume.
                        "  wrote {} of {} page(s); re-run with --resume to read it again",
                        progress.done,
                        progress.expected
                    );
                }
            }
            Err(e) => {
                // One bad file must not end a 500-file batch. It is recorded,
                // reported, and the exit code reflects it at the end.
                failures += 1;
                record_failure(out, console, input, &e)?;
            }
        }
    }

    Ok(outcome::run_end(failures, partial, total))
}

/// Report an input that could not be read, on stderr and in the manifest.
fn record_failure(
    out: &mut OutputDir,
    console: &mut Console<'_>,
    input: &Input,
    e: &anyhow::Error,
) -> Result<()> {
    note!(console, "  failed: {e:#}");
    out.record(&ManifestEntry::Failure(FailureRecord {
        input: input.path.display().to_string(),
        page: None,
        error: format!("{e:#}"),
    }))
}

/// How far one input got.
///
/// Both halves travel together because "done" is a comparison. Returning the
/// finished count alone is what let the caller record an interrupted book as
/// complete, and the digest in `state.rs` carries no page count to catch it.
struct Progress {
    done: usize,
    /// Pages that were tried and could not be read. Not counted in `done`, so a
    /// book with a failed page is not recorded as finished and `--resume` tries
    /// it again.
    failed: usize,
    expected: usize,
}

async fn process_one<R: Reader>(
    reader: &mut R,
    input: &Input,
    stem: &str,
    decision: &mode::Decision,
    args: &ExtractArgs,
    out: &mut OutputDir,
    console: &mut Console<'_>,
) -> Result<Progress> {
    let mut document = String::new();
    let mut pages_done = 0usize;
    let mut tally = outcome::PageTally::default();
    // Deferred rather than zeroed: every branch below knows its own total, and a
    // default of 0 would make `done >= expected` true for an input that never
    // ran, which is the comparison this whole change turns on.
    let pages_expected;

    match input.kind {
        InputKind::Image => {
            let started = Instant::now();

            let (lines, height) = reader
                .read_image(&input.path, decision.mode.segments())
                .await
                .with_context(|| format!("cannot read {}", input.path.display()))?;

            tally.read(has_text(&lines));
            let (text, records) = collect(&lines, height);

            out.write_page(stem, 1, &text)?;
            document.push_str(&text);
            out.record(&ManifestEntry::Page(PageRecord {
                input: input.path.display().to_string(),
                page: 1,
                mode: decision.mode.to_string(),
                lines: records,
                ms: started.elapsed().as_millis(),
            }))?;
            pages_done = 1;
            pages_expected = 1;
        }

        InputKind::Pdf => {
            let doc = reader.open_pdf(&input.path, args.dpi).await?;
            let pages = R::pages(&doc);
            note!(console, "  {pages} page(s)");
            pages_expected = pages;

            for page in 1..=pages {
                if args.interrupted.load(std::sync::atomic::Ordering::SeqCst) {
                    break;
                }
                let started = Instant::now();

                // A page that cannot be rendered or read is recorded and passed
                // over rather than ending the book. With `?` here, one bad page
                // in a 500-page book discarded every page after it, and the book
                // was reported as failed even though most of it was readable.
                let (lines, height) = match reader.read_pdf_page(&doc, page).await {
                    Ok(read) => read,
                    Err(e) => {
                        note!(console, "  page {page} could not be read: {e:#}");
                        out.record(&ManifestEntry::Failure(FailureRecord {
                            input: input.path.display().to_string(),
                            page: Some(page),
                            error: format!("{e:#}"),
                        }))?;
                        tally.failed(page, format!("page {page}: {e:#}"));
                        continue;
                    }
                };
                tally.read(has_text(&lines));

                let (text, records) = collect(&lines, height);
                out.write_page(stem, page, &text)?;
                if !document.is_empty() {
                    document.push_str("\n\n");
                }
                document.push_str(&text);

                out.record(&ManifestEntry::Page(PageRecord {
                    input: input.path.display().to_string(),
                    page,
                    mode: decision.mode.to_string(),
                    lines: records,
                    ms: started.elapsed().as_millis(),
                }))?;
                pages_done += 1;
            }
        }
    }

    let verdict = tally.verdict();
    if verdict.is_failure() {
        // Nothing was read, so no document is written: an empty `<book>.txt`
        // would look like a book with no text in it.
        anyhow::bail!(verdict.message().unwrap_or_default());
    }
    if let Some(msg) = verdict.message() {
        note!(console, "  {msg}");
    }

    // Written even when the run stopped early: it is atomic, so what lands is a
    // truthful prefix rather than a torn file, and a resumed run rewrites it in
    // full. Pages are re-read from page 1 on resume rather than reused off disk
    // — reuse would need the digest recorded per page file, or a `--dpi 150`
    // run's pages would be silently adopted by a `--dpi 300 --resume`.
    out.write_document(stem, &document)?;

    if args.json {
        let mut record = serde_json::json!({
            "input": input.path.display().to_string(),
            "mode": decision.mode.to_string(),
            "pages": pages_done,
            "stem": stem,
        });
        if input.kind == InputKind::Pdf {
            // A partly read book exits 0, so a pipeline reading only this line
            // needs these to tell it from a whole one: `pages` is what was
            // written, `expected_pages` what the book has, and `failed_pages`
            // the pages that could not be read.
            record["expected_pages"] = pages_expected.into();
            record["failed_pages"] = tally.failed_pages().into();
        }
        writeln!(console.out, "{record}")?;
    } else {
        // The result, and only the result, on stdout.
        writeln!(console.out, "{document}")?;
    }

    Ok(Progress {
        done: pages_done,
        failed: tally.failed_pages().len(),
        expected: pages_expected,
    })
}

/// Whether recognised lines carry any text.
///
/// Not `!lines.is_empty()`: line mode always returns one line, blank image or
/// not, and a segmented page can return lines that read as nothing, so counting
/// lines never reported `no text found` for either.
fn has_text(lines: &[monocr_onnx::LineResult]) -> bool {
    lines.iter().any(|l| !l.text.trim().is_empty())
}

/// Turn recognised lines into page text plus manifest records.
///
/// `page_height` is needed for the fused-block flag, which is a judgement about
/// a band relative to its page and cannot be made from the band alone.
fn collect(lines: &[monocr_onnx::LineResult], page_height: u32) -> (String, Vec<LineRecord>) {
    let mut text = String::new();
    let mut records = Vec::with_capacity(lines.len());

    for line in lines {
        if !text.is_empty() {
            text.push('\n');
        }
        text.push_str(&line.text);

        // Advisory only. A band that looks fused still has its text kept and
        // reported; the flag tells an operator which page to look at and which
        // mode to try, and LIMITATIONS is explicit that confidence cannot do
        // this job (0.83 on a complete fabrication).
        let looks_fused = !mode::looks_like_a_line(line.bbox.w, line.bbox.h, page_height);

        records.push(LineRecord {
            text: line.text.clone(),
            x: line.bbox.x,
            y: line.bbox.y,
            width: line.bbox.w,
            height: line.bbox.h,
            tiles: 1,
            looks_fused,
        });
    }
    (text, records)
}

/// Pixel height of a rendered page or image, for the fused-block judgement.
/// Zero when it cannot be read, which `looks_like_a_line` treats as "no opinion".
fn page_height_of(path: &std::path::Path) -> u32 {
    image::image_dimensions(path).map(|(_, h)| h).unwrap_or(0)
}

fn report_skipped(found: &Discovery) {
    if found.skipped.is_empty() {
        return;
    }
    // A silent skip reads as "there was nothing there". The Go binding drops
    // `PAGE.JPG` without a word; this says so.
    eprintln!("skipped {} file(s):", found.skipped.len());
    for (path, reason) in found.skipped.iter().take(20) {
        eprintln!("  {} ({reason})", path.display());
    }
    if found.skipped.len() > 20 {
        eprintln!("  ... and {} more", found.skipped.len() - 20);
    }
}

/// Whether to colour output. Kept because a pipe must receive clean data, and
/// `NO_COLOR` is the convention users already expect to work.
#[allow(dead_code)]
fn use_colour() -> bool {
    std::env::var_os("NO_COLOR").is_none() && std::io::stdout().is_terminal()
}
