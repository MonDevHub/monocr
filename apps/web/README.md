# MonOCR Web

MonOCR Web provides high-performance, privacy-first optical character recognition for the Mon script directly in the browser.

For mission context, community guidelines, and cross-platform information, please refer to the **[MonOCR Root Documentation](../../README.md)**.

## Overview

By implementing a **browser-bound execution model**, all OCR processing is restricted to the client-side environment. This architecture leverages **ONNX Runtime Web** and a specialized **Wasm** backend to ensure zero-latency inference and absolute data privacy—guaranteeing that linguistic assets never traverse the network.

## Key Features

- **On-Device Inference**: Runs entirely in the browser via WebAssembly (Wasm).
- **Privacy by Design**: Zero data collection; OCR processing is 100% local.
- **Optional Cloud Sync**: Secure, opt-in synchronization for contributing corrected scans to the open-source Mon language dataset.
- **High Performance**: Optimized MobileNetV3 + BiLSTM OCR engine (11.55M parameters).
- **Format Support**: Handles PDFs and images up to 50MB.
- **Script Specialized**: Purpose-built for Mon script recognition, with supplementary support for Burmese and English.

> [!TIP]
> File size is limited to 50MB for web and 20MB for mobile. For processing larger files or leveraging more powerful hardware, please use the CLI or package directly via `uv add monocr` or `pip install monocr`.

## Architecture

```
Image (Canvas/Blob)
  LineSegmenter     -> horizontal projection profile -> List<LineSegment>
  ImagePreprocessor  -> grayscale + normalize [-1.0, 1.0]
  MonOcrEngine      -> ONNX Runtime Web (monocr.onnx)
  CtcDecoder        -> greedy CTC decode -> String
```

### Model Specification

| Attribute    | Specification                  |
| ------------ | ------------------------------ |
| Architecture | MobileNetV3 + BiLSTM-384 + CTC |
| Precision    | FP32 (ONNX)                    |
| Parameters   | 11.55M                          |
| Input        | 160 × 1024 (H × W), both static         |
| Asset Size   | 46.2 MB (downloaded once)      |

## Project Structure

```
apps/web/
├── src/
│   ├── lib/
│   │   ├── engine/           # OCR Pipeline (ONNX/Wasm)
│   │   ├── components/       # Svelte UI Components
│   │   └── utils/            # Image & PDF Processing
│   └── routes/               # Application Pages
├── static/
│   ├── wasm/                 # ONNX Runtime Wasm Binaries
│   └── fonts/                # Mon/Myanmar Unicode Fonts
├── scripts/                  # Build & Asset Management
└── playwright/               # E2E Testing Suite
```

## Ecosystem

MonOCR is a unified cross-platform ecosystem designed for parity and performance:

- **[MonOCR Web](https://ocr.mondevhub.com)**: (This Repository) Privacy-first in-browser OCR.
- **[MonOCR Android](https://github.com/MonDevHub/ocr-android)**: Native Jetpack Compose app with Material 3.
- **[MonOCR iOS](https://github.com/MonDevHub/ocr-ios)**: Native SwiftUI app with SwiftData persistence.

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
so fetching from `main` — or from a different revision — gives you a
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
