# monocr-cli

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

Needs Rust and poppler (`pdftoppm`, `pdfinfo`) for the PDF path.

```bash
brew install poppler          # macOS
apt-get install poppler-utils # Debian

cargo build --release
./target/release/monocr-cli download   # prime the model cache before a long run
```

## Page-level and image-level segmentation

One parameter set does not read both a book page and a photo of a sign. This is measured, not
assumed: `mon_OCR/docs/LIMITATIONS.md:304-334` found the low gap ratio recovering 89.0% of known
5-grams on book pages against 87.1% at 0.50, while a six-line Mon poem slide returned **3 lines
at the low ratio and all 6, read correctly, at 0.50**. The ordering reverses by input class, and
the response is not monotone.

| `--mode` | For | What it does |
|---|---|---|
| `page` | PDF renders, scans, page screenshots | Segment into lines, tile, recognise. The default behaviour |
| `sparse` | Photos, posters, slides, signage | A far more permissive line-gap threshold |
| `line` | An image that is already one cropped line | Skips segmentation; tiles and recognises |
| `auto` | Default | Decides per input, and `inspect` shows you the reasoning |

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

The tiling tests read `shared/segmentation-fixtures/tiling-cases.json`, generated from the
canonical Python implementation and shared with the web, Android and iOS ports. Corrupting it
fails all four — that coupling is the point, and it is the only thing keeping four language
ports of one algorithm in step.

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
