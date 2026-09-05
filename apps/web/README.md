# MonOCR Web

MonOCR Web provides high-performance, privacy-first optical character recognition for the Mon script directly in the browser.

For mission context, community guidelines, and cross-platform information, please refer to the **[MonOCR Root Documentation](../../README.md)**.

## Overview

MonOCR Web runs **ONNX Runtime Web**, picking WebGPU when the browser offers it and falling back to a single-threaded Wasm backend when it does not. Every character is recognised in the tab. The model is fetched once and kept in the Cache API; no image and no recognised text leaves the machine, because there is no network call on the recognition path.

## Key Features

- **On-Device Inference**: Runs in the browser, on WebGPU where available and WebAssembly (Wasm) otherwise.
- **Printed-Rule Suppression**: Ruled paper, table borders and underlines are cleared from the binarised mask before the projection profile runs, so a printed line is not read as ink.
- **Line Tiling**: Lines wider than the model window are cut at whitespace instead of squeezed into it.
- **Privacy by Design**: Zero data collection; OCR processing is 100% local.
- **Optional Cloud Sync**: Secure, opt-in synchronization for contributing corrected scans to the open-source Mon language dataset.
- **High Performance**: Optimized MobileNetV3 + BiLSTM OCR engine (11.55M parameters).
- **Format Support**: Handles PDFs and images up to 50MB.
- **Script Specialized**: Purpose-built for Mon script recognition, with supplementary support for Burmese and English.

> [!TIP]
> File size is limited to 50MB for web and 20MB for mobile. Neither cap applies off-device, but the two escape routes are different artifacts and the older wording ran them together:
>
> - **`cargo install monocr-cli`** — this repository's own CLI, in [`apps/cli`](../cli/README.md), published to crates.io. Same engine as this app, reading local files with no size cap. This is the one that matches what you see here.
> - **`pip install monocr`** — the sibling Python project [`janakhpon/monocr`](https://github.com/janakhpon/monocr), not part of this repository. It reads the same trained model through its own segmentation implementation, whose density threshold and minimum line height differ from this one's, so page-level output will not match line for line.

## Architecture

```
Image bytes (Uint8Array)
  createImageBitmap      -> decode, flatten transparency onto white -> ImageData
  normalizePagePolarity  -> invert ONCE per page if the background is dark
  segmentLines           -> blur, adaptive threshold, suppressPageRules,
                            smear, projection profile -> LineSegment[]
  assessCapture          -> capture-quality warnings; drops nothing
  tileLine               -> split lines too wide for the window -> LineSegment[]
  processLine            -> crop + letterbox to 160x1024 + normalize [-1.0, 1.0]
  session.run            -> ONNX Runtime Web (monocr.onnx), [1, 1, 160, 1024]
  decodePredictions      -> greedy CTC decode -> string
```

All of this runs in `ocr.worker.ts`, off the main thread.

`suppressPageRules` is a step inside `segmentLines`, not a stage of its own. It
runs after adaptive binarisation and before the morphological smear, because the
smear widens a rule into something no line kernel matches cleanly. An unbroken
run of ink spanning at least half the page in either direction is a rule
(`RULE_SPAN = 0.5`, with a 15px floor). If clearing them would remove more than
80% of the page's ink (`RULE_MAX_INK_SHARE = 0.8`) it has found text rather than
rules, and leaves the mask untouched.

**Web does no background levelling, on purpose.** The mobile ports divide out a
dilated background estimate to flatten sepia paper and grey panels; this one does
not, and `segmentation.ts:163` records the reason. It is a separate enhancement,
it is the expensive half, and it is not idempotent, so adding it here without the
memory work this file already needs would trade one silent bug for another. A
diagram showing that stage on web is describing iOS.

Polarity is the one page-level normalisation web does have, and it is decided
before segmenting: the projection profile treats dark pixels as ink, so a
per-tile decision made two tiles of one line invert differently. Tiles of one
line join with no separator; distinct lines join with a newline.

### Model Specification

| Attribute    | Specification                                           |
| ------------ | ------------------------------------------------------- |
| Architecture | MobileNetV3-Large + SE + 2×BiLSTM-512 + attention + CTC |
| Precision    | FP32 (ONNX)                                             |
| Parameters   | 11.55M                                                  |
| Input        | 160 × 1024 (H × W), both static                         |
| Asset Size   | 46.2 MB (downloaded once)                               |

## Project Structure

The engine is not a directory. It is four flat files in `src/lib/`, and tests sit
beside the file they cover as `*.test.ts` under Vitest.

```
apps/web/
├── src/
│   ├── lib/
│   │   ├── segmentation.ts   # Polarity, rule suppression, HPP segmenting, tiling
│   │   ├── monocr-onnx.ts    # ONNX Runtime Web session, preprocessing, CTC decode
│   │   ├── ocr.worker.ts     # The worker the UI talks to
│   │   ├── capture-quality.ts
│   │   ├── components/       # Svelte UI components
│   │   ├── services/, stores/, storage/, actions/
│   │   └── utils/            # Image & PDF processing
│   └── routes/               # Application pages
├── functions/                # Cloudflare Pages functions
├── static/
│   ├── wasm/                 # ONNX Runtime Wasm binaries
│   └── fonts/                # Mon/Myanmar Unicode fonts
└── scripts/                  # Build & asset management
```

## Ecosystem

MonOCR is a unified cross-platform ecosystem designed for parity and performance:

- **[MonOCR Web](https://ocr.mondevhub.com)**: (This Repository) Privacy-first in-browser OCR.
- **[MonOCR Android](../android)**: Native Jetpack Compose app with Material 3.
- **[MonOCR iOS](../ios)**: Native SwiftUI app with SwiftData persistence.

## Development

### Prerequisites

- **Node.js** 24+
- **pnpm** 11+

### 1. Setup

```bash
pnpm install
```

### 2. Prepare Assets

Copy the pre-built ONNX Runtime WASM files to the static directory:

```bash
pnpm run copy-wasm
```

### 3. The model, locally

Optional, and worth doing. Without it every reload pulls 46.2 MB from Hugging
Face; with it the model is read off disk.

```bash
curl -L -o static/monocr.onnx \
  https://huggingface.co/janakhpon/monocr/resolve/d3d9d5e/onnx/monocr.onnx
```

`static/monocr.onnx` is gitignored. `src/lib/config.ts` prefers it in development
and falls back to the pinned URL when it is missing, printing which one it chose.
Nothing changes in production, where the local branch is never taken.

The revision in that command is not decoration. `static/charset.txt` is 276
characters and the app refuses to decode against a model that does not match it,
so fetching from `main`, or from any other revision, gives you a
`ModelContractError` at load rather than wrong text. Keep it equal to
`CONFIG.MODELS.RECOGNITION`.

### 4. Local Development

```bash
pnpm dev
```

### 5. Production Build

```bash
pnpm build
```

> [!IMPORTANT]
> In production the model is fetched from Hugging Face at the pinned revision, not bundled — it is far past the edge asset limit.

## Deployment

use `/app/web` as deployment root directory.

## Resources

- [HuggingFace Models](https://huggingface.co/janakhpon/monocr) (ONNX, Core ML; the TFLite export was removed at revision `a51be11`)
- [Unified SDKs](https://github.com/janakhpon/monocr-onnx)
- [NPM Package](https://www.npmjs.com/package/monocr)
- [Help contribute to copy/translations here](https://docs.google.com/spreadsheets/d/1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ/edit?usp=sharing)

## Contributors

- [Janakh Pon](https://github.com/janakhpon)
- [Oung Seik Nyan](https://github.com/Oungseik)
- [Rajel Da Key](https://www.facebook.com/RJOMDK10)

## License

MIT
