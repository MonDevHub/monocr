# MonOCR iOS

MonOCR iOS provides high-performance, native optical character recognition for the Mon script natively on Apple devices. 

For mission context, community guidelines, and cross-platform information, please refer to the **[MonOCR Root Documentation](../../README.md)**.

## Overview

MonOCR iOS runs **Core ML** and the Vision framework, so every character is recognised on the device. The model ships as `monocr.mlpackage` and Core ML places it on the Neural Engine where the hardware allows. No image and no recognised text leaves the device: there is no network call on the recognition path.

## Key Features

- **On-Device Inference**: Optimized Core ML execution with ANE acceleration.
- **Privacy by Design**: Zero data collection; OCR processing is 100% local.
- **Mon Language Support**: Specialized for the Mon script (276-char charset).
- **Line Segmentation**: Horizontal projection profiling, with a Page / Sparse / Line mode so dense scans and wide-spaced photos can use different thresholds.
- **Line Tiling**: Lines wider than the model window are cut at whitespace instead of squeezed into it. Squeezing degrades sharply as a line gets wider: 0.21 CER at four model windows against tiling's 0.06, and above 0.83 by six, while tiling costs a fraction of a point on narrower lines. Measured over 201 rendered lines, 2026-08-22, in the sibling repository `janakhpon/mon_OCR` at `eval/tiling-ab-2026-08-22.md`.
- **Modern UI**: 100% SwiftUI with native animations and light/dark theme support.
- **Format Support**: Handles high-resolution images and multi-page PDFs.
- **Script Fidelity**: Integration of PyidaungSu fonts for correct Mon/Myanmar rendering.

> [!TIP]
> File size is limited to 50MB for web and 20MB for mobile. For larger files, or to use a machine with more memory, use the CLI or the package directly: `uv add monocr` or `pip install monocr`.

## Architecture

```
Image (UIImage)
  GreyImage.upright  -> orientation-corrected 8-bit grey buffer
  PageNormalizer     -> polarity + background levelling, ONCE, before segmenting
  LineSegmenter      -> horizontal projection profile -> [LineSegment]
  LineTiler          -> split lines too wide for the window -> [LineSegment]
  ImagePreprocessor  -> scale to 160x1024 + normalize [-1.0, 1.0]
  MonOcrEngine       -> Core ML Prediction (monocr.mlpackage)
  CtcDecoder         -> greedy CTC decode -> String
```

Tiles of one line join with no separator; distinct lines join with a newline.
Polarity is decided at page level because the projection profile treats dark
pixels as ink: deciding it per line, after segmentation, made a dark-mode
screenshot segment on the gaps between lines.

The engine refuses to load a model whose input height or class count disagrees
with this build (`assertModelContract`), because that mismatch produces
well-formed, wrong Mon text rather than an error.

### Model Specification

| Attribute    | Specification                  |
| ------------ | ------------------------------ |
| Architecture | MobileNetV3-Large + SE + 2×BiLSTM-512 + attention + CTC |
| Precision    | FP32 (Core ML)                 |
| Parameters   | 11.55M                          |
| Input        | 160 × 1024 (H × W), both static         |
| Asset Size   | 46.2 MB                        |

## Project Structure

```
apps/ios/
├── monocr-ios/
│   ├── engine/           # OCR Core (Core ML, Preprocessing, Decoding)
│   ├── ui/               # SwiftUI Views & ViewModels
│   ├── persistence/      # SwiftData models & History
│   ├── resources/        # Models, Fonts, & Assets
│   └── util/             # Platform & PDF utilities
├── MonOcrCore/           # Swift package: the platform-free logic, and its tests
├── Scripts/              # swift-test.sh
└── monocr-ios.xcodeproj  # Xcode Project
```

## Ecosystem

MonOCR is a unified cross-platform ecosystem designed for parity and performance:

- **[MonOCR Web](https://github.com/MonDevHub/monocr-web)**: Privacy-first in-browser OCR.
- **[MonOCR Android](https://github.com/MonDevHub/monocr)**: (In this Monorepo) Native Jetpack Compose app.
- **[MonOCR iOS](https://github.com/MonDevHub/monocr)**: (In this Monorepo) Native SwiftUI app.

## Development

### Prerequisites

- **Xcode 26.2+** to build the app target — `project.pbxproj` sets
  `IPHONEOS_DEPLOYMENT_TARGET = 26.2`, which needs that SDK.
- **Xcode 16+** for `MonOcrCore` alone — `Package.swift:1` declares
  `swift-tools-version: 6.0`.
- **iOS 16.0+** is the package's own floor (`Package.swift:20`), but the app
  target's deployment target is higher; the two are not the same number.

These replace "Xcode 15.0+ / Swift 5.9+", which no longer build either target.

### Tests

`pnpm test`, or `sh Scripts/swift-test.sh` directly. It runs the `MonOcrCore`
Swift package with SwiftPM, which needs neither Xcode nor a simulator.

`MonOcrCore/Sources/MonOcrCore/` is **relative symlinks** into `monocr-ios/`, not
copies. The app target is a synchronized group over `monocr-ios/`, so the files
have to live there; symlinking them into a package makes them testable without
touching `monocr-ios.xcodeproj` and without a second copy that can drift. Adding a
file to the package means adding a symlink, not moving anything.

Only the platform-free half is in there — `GreyImage`, `PageNormalizer`,
`LineSegmenter`, `LineTiler`, `CtcDecoder`, `LogitsLayout` and the small value
types. Anything that imports UIKit, SwiftUI or Core ML still has no test, because
running it needs a simulator.

> [!IMPORTANT]
> `xcodebuild test` on this project used to report success against a scheme that
> is not checked in and a target with no tests in it. `Scripts/swift-test.sh`
> therefore fails when a run reports no test count, rather than trusting an exit
> code of 0. It also passes the `-F` and `-rpath` flags SwiftPM needs to find
> `Testing.framework` in Apple's Command Line Tools; those cannot live in
> `Package.swift`, which is explained at the top of that file.

The `ld: warning: building for macOS-13.0, but linking with dylib ... built for
newer version 14.0` line on every test run is `Testing.framework`'s deployment
target against the package's. It affects nothing the app ships.

### Getting Started

1.  Clone the repository and open `monocr-ios.xcodeproj` in **Xcode**.
2.  Ensure `monocr.mlpackage` is correctly referenced in the app target.
3.  Build and run on a physical device for optimal performance (ANE acceleration).
4.  Use the **Document Picker** or **Camera** to begin character recognition.

## Resources

- [Hugging Face Models](https://huggingface.co/janakhpon/monocr) (ONNX, Core ML; the TFLite export was removed at revision `a51be11`)
- [Unified SDKs](https://github.com/janakhpon/monocr-onnx) (ONNX Core)
- [MonOCR Monorepo](https://github.com/MonDevHub/monocr)

## Contributors

- [Janakh Pon](https://github.com/janakhpon)
- [Oung Seik Nyan](https://github.com/Oungseik)
- [Rajel Da Key](https://www.facebook.com/RJOMDK10)

## License

MIT
