//! Batch Mon OCR over books, PDFs and images.
//!
//! The stream contract, per se-brain `standards/cli-design.md` §2-§4: **stdout
//! carries results and nothing else**; progress, warnings and errors go to
//! stderr. That is what lets `monocr-cli extract book.pdf --json | jq` work while
//! the operator still sees progress. Exit 0 on success, 1 on failure, 130 on
//! Ctrl-C. Colour and progress switch off when stdout is not a TTY, and
//! `NO_COLOR` is honoured.
//!
//! This is a delivery surface, not an OCR implementation. Segmentation, tiling,
//! the model pin and the charset contract live in the `monocr-onnx` library; a
//! sixth copy of that logic here is exactly what se-brain
//! `standards/delivery-surfaces.md` §1 exists to prevent.

mod config;
mod discover;
mod mode;
mod output;
mod render;
mod state;

use std::io::{IsTerminal, Write};
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Instant;

use anyhow::{Context, Result};
use clap::{Parser, Subcommand, ValueEnum};

use discover::{Discovery, Input, InputKind};
use mode::Mode;
use output::{FailureRecord, LineRecord, ManifestEntry, OutputDir, PageRecord};

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

    // One session per distinct mode, built on first use and reused after.
    //
    // Three of the five existing CLIs rebuild the ORT session per file while a
    // session-reusing call sits unused beside them; that is the cost this
    // avoids. A session is keyed by mode because the density ratio is fixed at
    // build time, and a mixed run must not silently apply one mode's ratio to
    // another mode's input.
    let mut sessions: Vec<(Mode, monocr_onnx::MonOcr)> = Vec::new();

    let mut failures = 0usize;
    let total = found.inputs.len();

    for (n, input) in found.inputs.iter().enumerate() {
        if args.interrupted.load(std::sync::atomic::Ordering::SeqCst) {
            eprintln!("interrupted after {n} of {total}");
            break;
        }

        let decision = args
            .mode
            .resolve(input.kind, &input.path, image_dimensions(input));
        let digest = state::work_digest(&input.path, &decision.mode.to_string(), args.dpi)?;

        if args.resume && st.is_done(&digest) {
            eprintln!("[{}/{}] skip (done) {}", n + 1, total, input.path.display());
            continue;
        }

        eprintln!(
            "[{}/{}] {} ({})",
            n + 1,
            total,
            input.path.display(),
            decision.mode
        );

        let ocr = session_for(&mut sessions, decision.mode).await?;

        match process_one(ocr, input, &decision, &args, &mut out).await {
            Ok(pages) => {
                st.mark_done(&input.path, &digest, pages);
                // Saved per input so an interrupt loses at most the input in
                // flight, not the whole run's record.
                st.save(&out_root)?;
            }
            Err(e) => {
                // One bad file must not end a 500-file batch. It is recorded,
                // reported, and the exit code reflects it at the end.
                failures += 1;
                eprintln!("  failed: {e:#}");
                out.record(&ManifestEntry::Failure(FailureRecord {
                    input: input.path.display().to_string(),
                    page: None,
                    error: format!("{e:#}"),
                }))?;
            }
        }
    }

    if failures > 0 {
        anyhow::bail!("{failures} of {total} input(s) failed; see manifest.jsonl");
    }
    Ok(())
}

/// Get the session for a mode, building it on first use.
///
/// Held in a Vec rather than a map: there are three modes at most, and a linear
/// scan over three entries is not worth a hash.
async fn session_for(
    sessions: &mut Vec<(Mode, monocr_onnx::MonOcr)>,
    mode: Mode,
) -> Result<&mut monocr_onnx::MonOcr> {
    if let Some(i) = sessions.iter().position(|(m, _)| *m == mode) {
        return Ok(&mut sessions[i].1);
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

    sessions.push((mode, ocr));
    let last = sessions.len() - 1;
    Ok(&mut sessions[last].1)
}

async fn process_one(
    ocr: &mut monocr_onnx::MonOcr,
    input: &Input,
    decision: &mode::Decision,
    args: &ExtractArgs,
    out: &mut OutputDir,
) -> Result<usize> {
    let stem = output::output_stem(&input.path, None);
    let mut document = String::new();
    let mut pages_done = 0usize;

    match input.kind {
        InputKind::Image => {
            let started = Instant::now();

            // Line mode must not go through the page segmenter. A projection
            // profile over something that is already one line finds no gap to
            // cut at and fragments it instead of reading it, so the library has
            // a separate entry point that tiles without segmenting.
            let lines = if decision.mode.segments() {
                ocr.predict_page(&input.path)
                    .await
                    .with_context(|| format!("cannot read {}", input.path.display()))?
            } else {
                let line = ocr
                    .predict_single_line(&input.path)
                    .await
                    .with_context(|| format!("cannot read {}", input.path.display()))?;
                vec![line]
            };

            let (text, records) = collect(&lines, page_height_of(&input.path));

            out.write_page(&stem, 1, &text)?;
            document.push_str(&text);
            out.record(&ManifestEntry::Page(PageRecord {
                input: input.path.display().to_string(),
                page: 1,
                mode: decision.mode.to_string(),
                lines: records,
                ms: started.elapsed().as_millis(),
            }))?;
            pages_done = 1;
        }

        InputKind::Pdf => {
            let doc = render::PdfDocument::open(&input.path, args.dpi).await?;
            eprintln!("  {} page(s)", doc.pages());

            for page in 1..=doc.pages() {
                if args.interrupted.load(std::sync::atomic::Ordering::SeqCst) {
                    break;
                }
                let started = Instant::now();

                // Rendered, read, then dropped before the next page is touched:
                // peak memory is one page, not one book.
                let rendered = doc.render_page(page).await?;
                let height = page_height_of(rendered.path());
                let lines = ocr
                    .predict_page(rendered.path())
                    .await
                    .with_context(|| format!("cannot read page {page}"))?;
                drop(rendered);

                let (text, records) = collect(&lines, height);
                out.write_page(&stem, page, &text)?;
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

    out.write_document(&stem, &document)?;

    if args.json {
        let mut stdout = std::io::stdout().lock();
        writeln!(
            stdout,
            "{}",
            serde_json::json!({
                "input": input.path.display().to_string(),
                "mode": decision.mode.to_string(),
                "pages": pages_done,
                "stem": stem,
            })
        )?;
    } else {
        // The result, and only the result, on stdout.
        let mut stdout = std::io::stdout().lock();
        writeln!(stdout, "{document}")?;
    }

    Ok(pages_done)
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
