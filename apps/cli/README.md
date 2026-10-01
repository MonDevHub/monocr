# monocr-cli

Extract Mon text from books, PDFs and images, on-device and in batch. The apps read one page at a
time through a UI; this reads a shelf.

```bash
monocr-cli extract ./books -o ./out            # every PDF and image in a directory
monocr-cli extract ./scans -o ./out -r --resume # recursive, and safe to re-run
monocr-cli extract book.pdf --json | jq        # results on stdout, progress on stderr
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
ONNX Runtime is linked into the binary, so there is no shared library to install. Two things are
not:

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
  manifest.jsonl        one record per page, plus failures and skips
  <book>.txt            the whole document
  <book>/page-0001.txt  one file per page, zero-padded so a glob is in reading order
```

Manifest records carry bounding boxes, per-line text, timing, and a `looks_fused` flag when a band
looks like a block of lines rather than one line. A failure is a **record**, not an absence: one
bad file does not end a 500-file batch, and the exit code still reflects it.

## Behaviour worth knowing

- **stdout is data, stderr is everything else.** `--json | jq` works while you still see
  progress. Exit 0 on success, 1 on failure, 130 on Ctrl-C.
- **Memory is one page, not one document.** Pages are rasterised on demand and dropped. On a
  release build a 5-page book peaked at 215 MB and a 20-page book at 221 MB.
- **Build release for real work.** On the same 5-page book at 150 dpi: **33.3 s release against
  100.9 s debug**, 216 ms/line against 677. Most of the non-model work is per-pixel Rust outside
  the ONNX kernels, so `opt-level = 0` costs more than it looks.
- **Resume keys on content plus settings.** Changing `--mode` or `--dpi` redoes the work instead
  of reporting it done.
- **Every write is atomic.** A page file is written to a temp file, fsynced and renamed, so an
  interrupted run never leaves a half-written page that resume would count as finished.
- **Two runs cannot share an output directory.** The second is refused rather than interleaving
  state.
- **Skipped files are reported.** A batch that passed over 40 files says so.

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
- **An unknown or misplaced key.** `input: {recursiv: true}` reports `unknown field \`recursiv\`,
  expected \`paths\` or \`recursive\``.
- **A bad `mode`**, naming the valid values: `segmentation.mode is "pages", expected one of auto,
  page, sparse, line`.
- **A `dpi` outside 72..=1200**, refused with the reason rather than clamped.

## Tests

```bash
cargo test
cargo clippy --all-targets -- -D warnings
```

68 tests across `config`, `discover`, `mode`, `output`, `render` and `state`. CI runs both
commands on every push and fails unless at least 68 tests passed.

The renderer's PDF tests need Poppler and a real PDF, and they **fail** rather than skip when
either is missing, so a machine without Poppler cannot get a green run over untested code.
`MONOCR_SKIP_E2E=1` drops that coverage on purpose and prints a skip line per test;
`REQUIRE_E2E=1` overrides the opt-out. `MONOCR_PDF_FIXTURE` points at the PDF and defaults to a
path in a sibling `monocr-onnx` checkout. CI sets neither switch, and points `MONOCR_PDF_FIXTURE`
at a copy of that PDF it checks out.

This crate has no tiling code and reads no segmentation fixture: tiling is tested in `monocr-onnx`,
which `cargo test` here does not compile.

## Known limits

- **Runs are serial, and there is no `--jobs` flag**; passing one is a usage error. ONNX Runtime
  already parallelises a single inference across every core, so the headroom is small, while N
  workers would mean N sessions at roughly 700 MB-1 GB for N=4 and would have to serialise the
  manifest writer, the resume state and the document accumulator.
- **`ort` is pinned with `=`.** It is a pre-release, and `"2.0.0-rc.11"` range-matches `rc.13`,
  which does not compile against this crate.
- **Tiling is a safety net, not a general accuracy win.** An A/B over 201 rendered lines
  (2026-08-22) found squeezing better at 2 tiles per line, level at 3, and tiling better from 4
  up; by 6 tiles squeezing exceeds 0.83 CER. On a real book page at 150 dpi every line fitted one
  tile, so tiling never engaged.
