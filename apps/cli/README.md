# monocr-cli

Extract Mon text from books, PDFs and images, on-device and in batch. The apps read one page at a
time through a UI; this reads a shelf.

```bash
monocr-cli extract ./books -o ./out            # every PDF and image in a directory
monocr-cli extract ./scans -o ./out -r --resume # recursive, and safe to re-run
monocr-cli extract book.pdf -o ./out --json | jq # a JSON summary per input on stdout
monocr-cli inspect ./books                     # what would happen, and why
monocr-cli extract ./books --dry-run           # list the work, write nothing
```

It is a thin adapter, not an OCR implementation. Segmentation, tiling, the charset contract and
the model pin all live in the [`monocr-onnx`](https://github.com/MonDevHub/monocr-onnx) Rust
library. See [ADR-0004](../../docs/architecture/adr/0004-cli-desktop-surface.md). The model and its
accuracy figures are in the [root README](../../README.md).

## Install

```bash
cargo install monocr-cli
```

Published on [crates.io](https://crates.io/crates/monocr-cli). Runs on macOS, Linux and Windows.
`cargo install` downloads a prebuilt ONNX Runtime for your target at build time and links it into
the binary, so the build needs network and a target ONNX Runtime publishes binaries for, and there
is no shared library to install afterwards. On Linux the build also needs OpenSSL headers and
`pkg-config` (`libssl-dev pkg-config` on Debian and Ubuntu). Two things are needed at run time:

**The model.** About 46 MB, fetched from the pinned Hugging Face revision on first use and cached
under `~/.monocr/models/<revision>/` (`%USERPROFILE%\.monocr\models\<revision>\` on Windows).
`extract` downloads it if the cache is cold, so the first run needs network. Run
`monocr-cli download` before a long batch, so a dropped connection does not surface halfway
through.

**Poppler**, for PDFs only. `monocr-cli` shells out to `pdftoppm` and `pdfinfo`, and both must be
on `PATH`.

```bash
brew install poppler                  # macOS
sudo apt-get install poppler-utils    # Debian, Ubuntu; other distributions call it poppler-utils or poppler
```

On Windows, use `scoop install poppler`, `choco install poppler` or
`conda install -c conda-forge poppler`, or unzip the
[prebuilt binaries](https://github.com/oschwartz10612/poppler-windows/releases) and add
`Library\bin` to `PATH`. `pdfinfo -v` must print a version in a new shell before `monocr-cli` can
read a PDF.

### Nix

From the repository root, on Linux or macOS (x86_64 or aarch64):

```bash
nix build
nix run . -- --help
nix profile add .#monocr-cli
monocr-cli download
```

The Nix package includes Poppler and links the Nix-provided ONNX Runtime. The model is still
downloaded on first use, as above. The package is defined in `modules/packages/monocr-cli.nix`;
no development shell is provided.

### From this checkout

```bash
cd apps/cli
cargo build --release
./target/release/monocr-cli download
```

## Modes

One parameter set does not read both a book page and a photo of a sign. On book pages the low gap
ratio recovered 89.0% of known 5-grams against 87.1% at 0.50, while a six-line Mon poem slide
returned **3 lines at the low ratio and all 6, read correctly, at 0.50** (ADR-0004).

| `--mode` | For                                       | What it does                                               |
| -------- | ----------------------------------------- | ---------------------------------------------------------- |
| `page`   | PDF renders, scans, page screenshots      | Segment into lines, tile, recognise                        |
| `sparse` | Photos, posters, slides, signage          | A far more permissive line-gap threshold                   |
| `line`   | An image that is already one cropped line | Skips segmentation; tiles and recognises                   |
| `auto`   | Default                                   | Decides per input, and `inspect` shows you the reasoning   |

`auto` decides on **provenance and shape, never on confidence**. A PDF page is a page. A
standalone image is treated as a page unless it is both shorter than 320 px and at least 4.0 in
aspect; both tests must agree, because the fixture set contains an 876x277 screenshot that height
alone would misread as a single line.

**`sparse` is never chosen for you.** A photo and a scan look identical from file metadata, and
guessing wrong is expensive: on one whiteboard photo, five lines fused into one band and the
recogniser returned fluent Mon that appears nowhere on the page, at confidence 0.83. Run `inspect`,
then override if you disagree.

## Output

```
out/
  manifest.jsonl        one record per page, plus failures and skips; appended on every run
  <book>.txt            the whole document
  <book>/page-0001.txt  one file per page, zero-padded so a glob is in reading order
```

Manifest records carry bounding boxes, per-line text, timing, and a `looks_fused` flag when a band
looks like a block of lines rather than one line. A failure is a **record**, not an absence: one
bad file does not end a 500-file batch, and the exit code still reflects it.

With `--json`, stdout carries one object per input that was read: `input`, `mode`, `stem`
and `pages`, the number of pages written. A PDF's object also has `expected_pages`, the
number of pages in the PDF, and `failed_pages`, the page numbers that could not be read. A
partly read PDF exits 0, so these two fields are how a pipeline tells it from a whole one. An
input that could not be read at all has no object; its failure is in `manifest.jsonl`.

## Behaviour worth knowing

- **stdout is data, stderr is everything else**, so `--json | jq` works while you still see
  progress. The exception is the first model download, which prints two status lines to stdout: run
  `monocr-cli download` first when piping. Exit 0 on success, 1 on failure, 2 on a usage error,
  130 on Ctrl-C; [Exit codes](#exit-codes) says what counts as a failure.
- **Memory is one page, not one document.** Pages are rasterised on demand and dropped. On a
  release build a 5-page book peaked at 215 MB and a 20-page book at 221 MB.
- **Build release for real work.** On the same 5-page book at 150 dpi: **33.3 s release against
  100.9 s debug**, 216 ms/line against 677. Most of the non-model work is per-pixel Rust outside
  the ONNX kernels, so `opt-level = 0` costs more than it looks.
- **Resume keys on content plus settings.** Changing `--mode` or `--dpi` redoes the work instead
  of reporting it done.
- **Results are written atomically.** Page files, the document file and the resume state go to
  a temp file, are fsynced and renamed, so an interrupted run never leaves a half-written page
  that resume would count as finished. `manifest.jsonl` is the exception: it is appended to and
  never truncated, so re-running into the same directory adds another set of records.
- **Two runs cannot share an output directory.** The second is refused rather than interleaving
  state.
- **Skipped files are reported.** A batch that passed over 40 files says so.

## Exit codes

| Code | When |
| ---- | ---- |
| 0 | Every input was read, including an input with no text on it and a PDF with some unreadable pages |
| 1 | At least one input could not be read at all, or the run could not start |
| 2 | A usage error: an unknown flag, a missing argument or a flag value that does not parse |
| 130 | Stopped with Ctrl-C |

An input "could not be read at all" when the file cannot be opened or decoded, or when it is
a PDF and none of its pages could be rendered and recognised. The batch carries on past it; the
exit code reports it at the end, and the input has a `failure` record in `manifest.jsonl`. "The
run could not start" covers a bad config file, no supported inputs, a locked output
directory and a model that cannot be loaded.

A PDF with some pages that could not be read is not a failure. Each failed page is named on
stderr and has a `failure` record with its page number, the other pages are written, and a
warning at the end of the run counts the inputs that were only partly read. The input is not
recorded as finished, so `--resume` reads it again.

An input with no text on it is a result, not an error. When every page of an input was read
and none of them has text, the CLI says `no text found` on stderr and the input does not count
as a failure. A page has no text when the model returned no lines, or only lines that are empty
or whitespace; a blank image read in line mode is one of these, since line mode always returns
one line. A blank page in a PDF with text on other pages is not reported, and a PDF with some
pages that could not be read gets the warning above instead.

The CLI can only report what the `monocr` library returns. Version 0.4 fails a whole page when
any line on it cannot be recognised, so one bad line costs its page rather than only itself.

## Configuration

Every option is a flag, and every option is also a key in one sectioned YAML file, validated on
load. A book extraction is a fixed set of choices you want to repeat and review, so it belongs in
a file under version control.

```bash
cp monocr.example.yaml monocr.yaml   # then edit
monocr-cli extract                   # reads ./monocr.yaml
monocr-cli extract --config ci.yaml  # or name another file
```

[`monocr.example.yaml`](monocr.example.yaml) documents every key and is the reference.

| section        | keys                                        |
| :------------- | :------------------------------------------ |
| `input`        | `paths`, `recursive`                        |
| `output`       | `path`, `json`                              |
| `segmentation` | `mode`: `auto`, `page`, `sparse` or `line`  |
| `render`       | `dpi`                                       |
| `run`          | `resume`, `dry_run`                         |

Every section is optional, and **an empty file behaves exactly like no file**.

### The merge rule

**The file is the baseline; a flag is the exception.**

- `--output`, `--mode` and `--dpi` **override** the file.
- `paths` given as positional arguments **replace** `input.paths` rather than adding to it, so
  `monocr-cli extract one.pdf` reads one.pdf and nothing else.
- `--recursive`, `--resume`, `--json` and `--dry-run` are switches, and **a switch can turn a
  setting on but never off.** `clap` reports the same `false` whether a switch was omitted or
  meant to disable something, so switches are OR-ed with the file. A switch that could silently
  cancel a file setting would make `--dry-run` unsafe to add out of habit. **To turn one off, edit
  the file.**

### What is not configurable

Nothing that changes what the model sees. The input height, the width, the normalisation and the
pinned model revision are one contract with the exported graph; when part of it moves without the
rest, the model returns well-formed Mon text that is wrong.

### Errors it refuses rather than absorbs

- **A `--config` path that does not exist.** You named a file, so running with defaults would
  ignore every setting you meant to apply. A _missing_ `monocr.yaml` is fine.
- **An unknown or misplaced key.** `input: {recursiv: true}` reports
  ``unknown field `recursiv`, expected `paths` or `recursive` ``.
- **A bad `mode`**, naming the valid values: `segmentation.mode is "pages", expected one of auto,
  page, sparse, line`.
- **A `render.dpi` outside 72..=1200**, refused with the reason rather than clamped. The `--dpi`
  flag is not range-checked.

## Tests

```bash
cargo test
cargo clippy --all-targets -- -D warnings
```

88 tests: 87 unit tests across `config`, `discover`, `extract_tests`, `mode`, `outcome`, `output`,
`render` and `state`, and one in `tests/unreadable_input.rs` that runs the built binary on files it
cannot open. They cover config loading and validation, input classification, the exit-code rules,
the per-page and per-input wiring that feeds those rules, output and PDF rendering. None loads the
model: `extract_tests` runs the wiring against a fake reader that succeeds, fails or reads blank per
page. CI runs both commands on every push and fails unless at least 88 tests passed.

The renderer's PDF tests need Poppler and a real PDF, and they **fail** rather than skip when
either is missing, so a machine without Poppler cannot get a green run over untested code.
`MONOCR_SKIP_E2E=1` drops that coverage on purpose and prints a skip line per test;
`REQUIRE_E2E=1` overrides the opt-out. `MONOCR_PDF_FIXTURE` points at the PDF and defaults to a
path in a sibling `monocr-onnx` checkout. CI sets neither switch, and points `MONOCR_PDF_FIXTURE`
at a copy of that PDF it checks out.

This crate has no tiling code and reads no segmentation fixture: tiling is tested in `monocr-onnx`,
which `cargo test` here does not compile.

## Known limits

- **PDFs over 500 MiB or 3,000 pages are refused.** PDFs render at 300 dpi unless `--dpi` or
  `render.dpi` says otherwise.
- **Runs are serial, and there is no `--jobs` flag**; passing one is a usage error. ONNX Runtime
  already parallelises a single inference across every core, so the headroom is small, while N
  workers would mean N sessions at roughly 700 MB-1 GB for N=4 and would have to serialise the
  manifest writer, the resume state and the document accumulator.
- **`ort` is pinned with `=`.** It is a pre-release, and `"2.0.0-rc.11"` range-matches `rc.13`,
  which does not compile against this crate.
- **Tiling is a safety net, not a general accuracy win.** Measured on 201 rendered lines:
  squeezing wins at 2 tiles, level at 3, tiling wins from 4; by 6 tiles squeezing exceeds 0.83
  CER. On a real book page at 150 dpi every line fitted one
  tile, so tiling never engaged.
