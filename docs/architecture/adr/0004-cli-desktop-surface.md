# ADR-0004: A Rust CLI as the batch and desktop surface

- **Status:** Accepted
- **Date:** 2026-08-22
- **Supersedes:** nothing. ADR-0001's component list (Web/Wasm, Android, iOS, Go ingestion
  services) predates both the SDK bindings and any batch use, so this extends it.

## Context

MonOCR had no way to read a book. The three apps take one image or one PDF through a UI, and
the four SDK bindings in `monocr-onnx` each expose a `batch` subcommand that lists a single
directory non-recursively and filters to image extensions — so a directory of PDFs is not
addressable in any of them, nothing walks recursively, nothing streams pages, and nothing
resumes. Reading a shelf of scanned Mon books, which is the point of the corpus work, had no
tool.

Two constraints shaped the decision.

**The recogniser must not be reimplemented.** There are already five line segmenters and four
preprocessing paths in this ecosystem, and `mon_OCR/docs/LIMITATIONS.md:175-198` records the
cost: of 14 canonical constants, only 3 survive into every implementation that has them. A
sixth copy is a liability, not a feature.

**Two of the four bindings had defects that a book-scale tool would inherit.** The Go binding
resamples with `draw.CatmullRom` (bicubic), which `docs/CROSS_BINDING_PARITY.md` names as a
cause of transcription divergence because the model was trained on bilinear; and its PDF path
iterates `os.ReadDir` unsorted, so any document past nine pages comes back scrambled.

## Decision

**A Rust CLI at `apps/cli`, workspace member `@monocr/cli`, depending on the `monocr-onnx`
Rust library.**

Rust over Go on three grounds, in order of weight:

1. **Correct resampling.** Rust uses `FilterType::Triangle` (bilinear), which matches the
   training kernel. Go's bicubic does not.
2. **It can ship as a desktop artifact.** `ort` links ONNX Runtime into the binary. Go's
   `yalue/onnxruntime_go` needs a host `libonnxruntime` — the reason the Go SDK has a `runtime`
   subcommand at all — which makes single-file distribution impractical.
3. **Fewer inherited defects**, per the page-ordering bug above.

Rust is also the path to a Tauri GUI if the desktop surface grows a window.

`apps/` rather than `services/`: this is a user-facing deliverable, not a long-running process.
`services/` holds daemons (ADR-0001 calls them "Ingestion Services").

### The CLI is a thin adapter

Per se-brain `standards/delivery-surfaces.md` §1, domain logic stays in the library and the
surface only adapts a transport. Concretely, three capabilities the CLI needed were added
**upstream in `monocr-onnx/rust`, not here**:

- `tile_line` / `cut_column` — the binding squeezed wide lines into the model window, which
  degrades without bound as the line gets wider (see the measurement under Consequences).
- `MonOcrBuilder::density_threshold_ratio` — so a caller can pick a segmentation regime.
- `MonOcr::predict_single_line` — so an already-cropped line can skip segmentation.

The CLI owns only what is genuinely its own: input discovery, streaming page rasterisation,
resume, output layout, and the stream/exit-code contract.

### Page-level and image-level segmentation are separate regimes

One parameter set does not serve every input, and this is measured rather than assumed.
`mon_OCR/docs/LIMITATIONS.md:304-334`: on book pages the low gap ratio recovers 89.0% of known
5-grams against 87.1% at 0.50, but a six-line Mon poem slide returns **3 lines at the low ratio
and all 6, read correctly, at 0.50**. The ordering reverses by input class and the response is
not monotone — on one photograph 0.5 gave 5 lines, 0.7 gave 4, 1.3 gave 1.

So the CLI dispatches a mode instead of applying one set:

| Mode | For | Ratio |
|---|---|---|
| `page` | PDF renders, scans, page screenshots | 0.05, the library default — unchanged behaviour |
| `sparse` | photos, posters, slides, signage | 0.50 |
| `line` | an already-cropped line | segmentation skipped entirely |

`auto` decides on provenance and shape, never on confidence. A PDF page is known to be a page.
A standalone image is a page unless it is both shorter than `2 x 160` px **and** at least 4.0
in aspect, which is the same 4.0 the canonical `looks_like_a_line` uses. Both tests must agree:
`pdf_screenshot.png` in the fixture set is 876x277, so height alone would have called it one
line, and at aspect 3.2 it is several — reading it as one line would concatenate them.

**`sparse` is never chosen automatically.** A photograph and a scan are indistinguishable from
file metadata, and LIMITATIONS records what guessing wrong costs: on a whiteboard photo five
lines fused into one 493-px band and the recogniser returned fluent Mon that appears nowhere on
the page, at confidence 0.83. Confidence cannot separate that from a good read, so it must not
drive the choice. `monocr-cli inspect` reports the mode `auto` would pick, and the operator
overrides.

## Consequences

**Good.** A book is readable in one command. Peak memory is one page rather than one document.
A run is resumable and re-runnable. Failures are per-input records in a manifest rather than
absences. The tiling fix landed in the library, so the published Rust binding improved too.

**Costs and open items.**

- **The dependency is a path, and must become a pinned git rev.** `Cargo.toml` currently points
  at `../../../monocr-onnx/rust` so the CLI could build against uncommitted upstream work. A
  sibling-checkout path dependency is a known failure mode here: `mon-lm` records that "mon-vlm
  took an editable path install of it and that is why mon-vlm's CI cannot run at all". The CI
  job cannot be green on a fresh clone until this is a git rev.
- **`ort` must stay pinned exactly.** `ort = "2.0.0-rc.11"` range-matches `rc.13`, which
  changed `Send` bounds and does not compile against this crate. Pre-release dependencies need
  `=`, not a caret.
- **Poppler is a runtime dependency** (`pdftoppm`, `pdfinfo`). The error messages say how to
  install it. A `pdfium` crate would remove it at the cost of vendoring a large binary.
- **`--jobs` is not implemented, and the flag does not exist.** ONNX Runtime already
  parallelises within one inference across every core — the session sets no thread options, so it
  gets the default pool of one thread per physical core — which is where the headroom would have
  come from. N workers would instead mean N sessions at roughly 700 MB-1 GB for N=4, and would
  have to serialise the manifest writer, the resume state and the ordered document accumulator.
  The release baseline below is what any future implementation has to beat. An earlier draft of
  the CLI README claimed the flag was "accepted but not implemented", which was simply false.
- **Measured 2026-08-22, and it changed the framing.** `ROADMAP.md:438` required measuring on
  the ported pipeline before trusting the direction; that is now done, twice, over the same 201
  rendered lines — `mon_OCR/scripts/tiling_ab.py` for the Python arms and
  `monocr-onnx/rust/examples/tiling_ab.rs` for this binding. The result is width-dependent:
  squeezing wins at 2 tiles, parity at 3, tiling wins from 4 up and by 20-36x at 6 tiles where
  squeezing exceeds 0.83 CER. **At median 3 tiles there is no tiling advantage**, which
  contradicts the 0.1434-against-0.0795 figures cited earlier in this ADR; those came from a
  harness that was never committed and do not reproduce. Report and limits:
  `mon_OCR/eval/tiling-ab-2026-08-22.md`.

  Two consequences for this CLI. Tiling stays on by default, because its downside is bounded and
  squeezing's is not — but it is a **safety net, not an accuracy feature**, and on a real book
  page at 150 dpi it never engaged at all (one tile per line, every line). A width-conditional
  policy is the obvious follow-up, deliberately not implemented on one font at one size.

- **Build release for real work.** Measured on the same 5-page book at 150 dpi: 33.3 s release
  against 100.9 s debug, 216 ms/line against 677. The hot non-model work is per-pixel Rust, so
  `opt-level = 0` costs 3x. Peak RSS stays flat either way (215 MB at 5 pages, 221 at 20).
