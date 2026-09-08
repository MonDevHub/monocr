# monocr-cli

[![crates.io](https://img.shields.io/crates/v/monocr-cli.svg)](https://crates.io/crates/monocr-cli)

Extract Mon text from books, PDFs and images, on-device and in batch.

This is the batch and desktop surface for MonOCR. The apps read one page at a time through a
UI; this reads a shelf.

```bash
monocr-cli extract ./books -o ./out            # every PDF and image in a directory
monocr-cli extract ./scans -o ./out -r --resume # recursive, and safe to re-run
monocr-cli extract book.pdf --json | jq        # results on stdout, progress on stderr
monocr-cli inspect ./books                     # what would happen, and why
monocr-cli extract ./books --dry-run           # list the work, write nothing
```

## What it is not

It is not an OCR implementation. Segmentation, tiling, the charset contract and the model pin
all live in the [`monocr-onnx`](https://github.com/MonDevHub/monocr-onnx) Rust library, and this
binary is a thin adapter over it. There were already five line segmenters in this ecosystem
before it; a sixth would be a liability. See [ADR-0004](../../docs/architecture/adr/0004-cli-desktop-surface.md).

## Install

```bash
cargo install monocr-cli
```

Runs on macOS, Linux and Windows.

### Nix

From the repository root, on Linux or macOS (x86_64 or aarch64):

```bash
nix build
nix run . -- --help
nix profile add .#monocr-cli
monocr-cli download
```

The Nix package includes Poppler for PDFs and links the Nix-provided ONNX
Runtime. The model is downloaded from Hugging Face on first use and cached in
your home directory as described below. No development shell is provided.

The flake uses flake-parts and imports every Nix module under `modules/` with
import-tree. The CLI package is defined in `modules/packages/monocr-cli.nix`.

### Runtime requirements

**For Cargo installations, ONNX Runtime is inside the binary.** `ort` fetches a prebuilt runtime for your
target at build time and links it in, so there is no shared library to install
and no path to configure. The Go binding in `monocr-onnx` does need one; this
does not.

Two things are not, and one of them is only for PDFs.

**The model.** About 55 MB, About 55 MB, fetched from the pinned Hugging Face revision
on first use and cached under `~/.monocr/models/<revision>/`
(`%USERPROFILE%\.monocr\models\<revision>\` on Windows). `extract` downloads
it if the cache is cold, so the first run needs network. `monocr-cli download`
does it up front, which is worth doing before a long batch so a dropped
connection does not surface halfway through a shelf.

**poppler**, for the PDF path only — images work without it. `monocr-cli` shells out to `pdftoppm` and `pdfinfo`, and both must be on
`PATH`.

### macOS

```bash
brew install poppler
```

### Linux

```bash
sudo apt-get install poppler-utils   # Debian, Ubuntu
```

Other distributions package the same binaries; the package is usually called
`poppler-utils` or `poppler`.

### Windows

`pdftoppm` and `pdfinfo` are not shipped with Windows and there is no single
official installer. Any of these works — pick whichever fits how you already
install things:

```powershell
scoop install poppler
choco install poppler
conda install -c conda-forge poppler
```

Or download the prebuilt binaries from
[oschwartz10612/poppler-windows](https://github.com/oschwartz10612/poppler-windows/releases),
unzip, and add the `Library\bin` directory to `PATH`. That release is what
`pdf2image`'s own documentation points Windows users at.

Whichever route: `pdfinfo -v` must print a version in a new shell before
`monocr-cli` can read a PDF. If it does not, `PATH` is the problem.

### From this checkout

```bash
cargo build --release
./target/release/monocr-cli download
```

## Page-level and image-level segmentation

One parameter set does not read both a book page and a photo of a sign. This is measured, not
assumed: `mon_OCR/docs/LIMITATIONS.md:304-334` found the low gap ratio recovering 89.0% of known
5-grams on book pages against 87.1% at 0.50, while a six-line Mon poem slide returned **3 lines
at the low ratio and all 6, read correctly, at 0.50**. The ordering reverses by input class, and
the response is not monotone.

| `--mode` | For                                       | What it does                                               |
| -------- | ----------------------------------------- | ---------------------------------------------------------- |
| `page`   | PDF renders, scans, page screenshots      | Segment into lines, tile, recognise. The default behaviour |
| `sparse` | Photos, posters, slides, signage          | A far more permissive line-gap threshold                   |
| `line`   | An image that is already one cropped line | Skips segmentation; tiles and recognises                   |
| `auto`   | Default                                   | Decides per input, and `inspect` shows you the reasoning   |

`auto` decides on **provenance and shape, never on confidence**. A PDF page is known to be a
page. A standalone image is treated as a page unless it is both shorter than 320 px and at least
4.0 in aspect — both tests must agree, because the fixture set contains an 876x277 screenshot
that height alone would misread as a single line.

**`sparse` is never chosen for you.** A photo and a scan look identical from file metadata, and
guessing wrong is expensive: LIMITATIONS records a whiteboard photo where five lines fused into
one band and the recogniser returned fluent Mon that appears nowhere on the page, at confidence
0.83. Confidence cannot detect that, so it does not drive the choice. Run `inspect`, then
override if you disagree.

## Output

```
out/
  manifest.jsonl        one record per page, plus failures and skips
  <book>.txt            the whole document
  <book>/page-0001.txt  one file per page, zero-padded so a glob is in reading order
```

Manifest records carry real bounding boxes, per-line text, timing, and a `looks_fused` flag when
a band looks like a block of lines rather than one line. A failure is a **record**, not an
absence — one bad file does not end a 500-file batch, and the exit code still reflects it.

## Behaviour worth knowing

- **stdout is data, stderr is everything else.** `--json | jq` works while you still see
  progress. Exit 0 on success, 1 on failure, 130 on Ctrl-C.
- **Memory is one page, not one document.** Pages are rasterised on demand and dropped. Measured
  on a release build: a 5-page book peaked at 215 MB and a 20-page book at 221 MB, so 4x the
  pages cost 3% more memory.
- **Build release for real work.** Measured on the same 5-page book at 150 dpi: **33.3 s release
  against 100.9 s debug**, a 3.0x difference, and 216 ms/line against 677. Most of the non-model
  work is per-pixel Rust (the segmenter's projection loop, `image::resize`, the tensor writes),
  and none of it is inside the ONNX kernels, so `opt-level = 0` costs more than it looks.
- **Resume keys on content plus settings.** Changing `--mode` or `--dpi` correctly redoes the
  work instead of reporting it done.
- **Every write is atomic.** A page file is written to a temp file, fsynced, and renamed, so an
  interrupted run never leaves a half-written page that resume would count as finished.
- **Two runs cannot share an output directory.** The second is refused with a message rather
  than interleaving state.
- **Skipped files are reported.** A batch that passed over 40 files says so.

## Tests

```bash
cargo test
cargo clippy --all-targets -- -D warnings
```

The renderer's PDF tests need real poppler and a real PDF, and they **fail**
rather than skipping when either is missing. That is deliberate: they used to
return early and the suite still reported `0 ignored`, so a machine without
poppler got a green run over code nothing had executed.

`MONOCR_SKIP_E2E=1` drops that coverage on purpose and prints a loud skip line
per test; `REQUIRE_E2E=1` overrides the opt-out again, which is what CI sets.
`MONOCR_PDF_FIXTURE` points at the PDF, and defaults to a path in a sibling
`monocr-onnx` checkout — CI sets it to one it checked out, because that sibling
path resolves on a developer machine and nowhere else.

The tiling tests read `shared/segmentation-fixtures/tiling-cases.json`, generated by
`shared/segmentation-fixtures/generate.py` from `monocr_onnx.segmenter.tile_line` and shared
with the web, Android and iOS ports. Corrupting it fails those three, plus the Rust tests
that live in `monocr-onnx` and `mon_OCR`'s `tests/test_tiling_fixture.py` — that coupling is
the point, and it is the only thing keeping the ports of one algorithm in step.

**This CLI is not one of them.** Corrected 2026-08-26: this paragraph used to claim
corrupting the fixture "fails all four", counting this crate among them. `apps/cli` has no
`tests/` directory, reads no fixture, and contains no tiling arithmetic — it calls
`monocr_onnx::MonOcr::predict_page`, and the tiling tests for that live in the `monocr-onnx`
crate, which `cargo test` here does not compile. This crate has 55 tests of its own, across
`config`, `discover`, `mode`, `output`, `render` and `state`. They cover config loading and
validation, input classification, output and PDF rendering. None covers tiling, because there
is none here to cover. Corrected 2026-08-28: that count read 37 until `config`'s 18 tests were
added to it.

## Configuration

Every option is a flag, and every option is also a config file key. Flags suit a
one-off run; a book extraction is a fixed set of choices you want to repeat and
review, so it belongs in a file under version control rather than a shell line
reconstructed from history. `pdf2audio` solves the same problem the same way, and
this follows its shape: one sectioned YAML file, commented with the reason for
each value, validated on load.

```bash
cp monocr.example.yaml monocr.yaml   # then edit
monocr-cli extract                   # reads ./monocr.yaml
monocr-cli extract --config ci.yaml  # or name another file
```

`monocr.example.yaml` is the reference: it documents every key, and it is the file
to read rather than this table.

| section        | keys                                        |
| :------------- | :------------------------------------------ |
| `input`        | `paths`, `recursive`                        |
| `output`       | `path`, `json`                              |
| `segmentation` | `mode` — `auto`, `page`, `sparse` or `line` |
| `render`       | `dpi`                                       |
| `run`          | `resume`, `dry_run`                         |

Every section is optional, and **an empty file behaves exactly like no file** — so
adding one to a repository cannot change how the tool runs until you put something
in it.

### The merge rule

**The file is the baseline; a flag is the exception.**

- `--output`, `--mode` and `--dpi` **override** the file. They can express "unset",
  so absent means "use the file's value".
- `paths` given as positional arguments **replace** `input.paths` rather than
  adding to it. `monocr-cli extract one.pdf` reads one.pdf and nothing else, so a
  configured batch can be overridden for a single run without editing it.
- `--recursive`, `--resume`, `--json` and `--dry-run` are switches, and **a switch
  can turn a setting on but never off.**

That last one is deliberate rather than an oversight, and it is the only asymmetry
here. A switch cannot say "false": `clap` reports the same `false` whether it was
omitted or you meant to disable something. The cases where you reach for these
flags are "also do a dry run this time" and "also descend today", so they are
OR-ed with the file. A switch that could silently cancel a file setting would make
`--dry-run` unsafe to add to a command line out of habit. **To turn one off, edit
the file.**

### What is not configurable

Nothing that changes what the model sees. The input height, the width, the
normalisation and the pinned model revision are one contract with the exported
graph — `mon_OCR/docs/CHARSET.md` records what happened the last time part of that
contract moved without the rest. They are not options, and this file does not make
them look like options.

### Errors it refuses rather than absorbs

- **A `--config` path that does not exist** is an error. You named a file; running
  with defaults instead would silently ignore every setting you meant to apply. A
  _missing_ `monocr.yaml` is fine — the tool works with no config at all.
- **An unknown or misplaced key** is an error, not an ignored line.
  `input: {recursiv: true}` reports `unknown field \`recursiv\`, expected \`paths\`
  or \`recursive\``. A key under the wrong section would otherwise read as
  configured and do nothing.
- **A bad `mode`** names the valid values: `segmentation.mode is "pages", expected
one of auto, page, sparse, line`.
- **A `dpi` outside 72..=1200** is refused with the reason, not clamped.

## Known limits

- **Runs are serial, and there is no `--jobs` flag.** An earlier version of this file claimed the
  flag was "accepted but not implemented", which was simply wrong — passing it is a usage error.
  It is not implemented on purpose: ONNX Runtime already parallelises within a single inference
  across every core, so the headroom is small, while N workers would mean N sessions at roughly
  700 MB-1 GB for N=4 and would have to serialise the manifest writer, the resume state and the
  document accumulator. The release baseline above is what any future `--jobs` has to beat.
- The `Cargo.toml` dependency on `monocr-onnx` is a **path** to a sibling checkout and must
  become a pinned git rev before CI can pass on a fresh clone.
- `ort` is pinned with `=` deliberately. It is a pre-release, and `"2.0.0-rc.11"` range-matches
  `rc.13`, which does not compile against this crate.
- **Tiling is a safety net, not a general accuracy win.** `mon_OCR/eval/tiling-ab-2026-08-22.md`
  measures it: squeezing is mildly better up to 3 tiles per line, and tiling is 3.7x to 24x
  better from 4 tiles up, where squeezing pushes CER above 0.9. On a real book page at 150 dpi
  this CLI produced one tile per line for every line, so tiling never engaged — its value is on
  unusually wide input. That measurement also does not reproduce the ordering quoted in
  `mon_OCR/docs/ROADMAP.md:438`; the report says so and explains why.
