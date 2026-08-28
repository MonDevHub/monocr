# MonOCR Android

MonOCR Android brings high-performance, native optical character recognition for the Mon script natively to Android devices.

For mission context, community guidelines, and cross-platform information, please refer to the **[MonOCR Root Documentation](../../README.md)**.

## Overview

MonOCR Android runs **ONNX Runtime** with NNAPI, so every character is recognised on the device. The model ships as `assets/monocr.onnx`. No image and no recognised text leaves the device: there is no network call on the recognition path.

## Key Features

- **On-Device Inference**: Powered by ONNX Runtime with hardware acceleration.
- **Privacy by Design**: Zero data collection unless users want to contribute intentionally; OCR processing is 100% local.
- **Mon Language Support**: Specialized for the Mon script (276-char charset).
- **Line Segmentation**: Horizontal projection profiling, with a Page / Sparse / Line mode so dense scans and wide-spaced photos can use different valley thresholds.
- **Printed-Rule Suppression**: Ruled paper, table borders and underlines are cleared from the binarised mask before the projection profile runs, so a printed line is not read as ink. Ported from web and iOS on 2026-08-28 against the shared fixture.
- **Line Tiling**: Lines wider than the model window are cut at whitespace instead of squeezed into it.
- **Format Support**: Handles large PDFs and high-resolution images.
- **Script Fidelity**: Integration of PyidaungSu fonts for accurate Mon/Myanmar rendering.

> [!TIP]
> File size is limited to 50MB for web and 20MB for mobile. For larger files, or to use a machine with more memory, use the CLI or the package directly: `uv add monocr` or `pip install monocr`.

## Architecture

```
Image (Bitmap)
  GreyImage.fromArgbInPlace -> BT.601 luma, in place -> GreyImage
  PageNormalizer.normalize  -> polarity + background levelling, ONCE, before segmenting
  LineSegmenter.segment     -> blur, adaptive threshold, suppressPageRules,
                               smear, projection profile -> List<LineSegment>
  LineTiler.tileSegment     -> split lines too wide for the window -> List<LineSegment>
  ImagePreprocessor         -> crop + scale to 160x1024 + normalize [-1.0, 1.0]
  MonOcrEngine              -> ONNX Runtime Session (monocr.onnx), [1, 1, 160, 1024]
  CtcDecoder                -> greedy CTC decode -> String
```

`suppressPageRules` is a step inside `LineSegmenter.segment`, not a stage of its
own. It runs after adaptive binarisation and before the morphological smear,
because the smear widens a rule into something no line kernel matches cleanly.
An unbroken run of ink spanning at least half the page in either direction is a
rule (`RULE_SPAN = 0.5`, with a 15px floor). If clearing them would remove more
than 80% of the page's ink (`RULE_MAX_INK_SHARE = 0.8`) it has found text rather
than rules, and leaves the mask untouched.

`SegmentationMode.LINE` skips `LineSegmenter.segment` altogether and treats the
image as a single band. `PAGE` and `SPARSE` differ only in the valley threshold
they pass in. Normalisation, tiling, preprocessing and inference are identical in
all three.

Tiles of one line join with no separator; distinct lines join with a newline.
Polarity is decided at page level because the projection profile treats dark
pixels as ink: deciding it per line, after segmentation, made a dark-mode
screenshot segment on the gaps between lines.

### Model Specification

| Attribute    | Specification                                           |
| ------------ | ------------------------------------------------------- |
| Architecture | MobileNetV3-Large + SE + 2×BiLSTM-512 + attention + CTC |
| Precision    | FP32 (ONNX)                                             |
| Parameters   | 11.55M                                                  |
| Input        | 160 × 1024 px (H × W)                                   |
| Asset Size   | 46.2 MB                                                 |

## Project Structure

```
apps/android/
├── app/src/main/
│   ├── java/dev/janakhpon/monocr/
│   │   ├── engine/           # OCR Core (ONNX, Preprocessing, Decoding)
│   │   ├── ui/               # Compose Screens & ViewModels
│   │   ├── data/             # Persistence & Repository layers
│   │   └── util/             # Platform utilities
│   ├── assets/               # Models & Charsets
│   └── res/font/             # Native Typography
```

## Ecosystem

MonOCR is a unified cross-platform ecosystem designed for parity and performance:

- **[MonOCR Web](https://github.com/MonDevHub/monocr-web)**: Privacy-first in-browser OCR.
- **[MonOCR Android](https://github.com/MonDevHub/monocr)**: (In this Monorepo) Native Jetpack Compose app.
- **[MonOCR iOS](https://github.com/MonDevHub/monocr)**: (In this Monorepo) Native SwiftUI app with SwiftData persistence.

## Development

### Prerequisites

- **Android Studio** — any release bundling **JetBrains Runtime 21**. Required
  even if you never open it, and `JAVA_HOME` must point at the JBR inside it:
  `gradle/gradle-daemon-jvm.properties` pins `toolchainVendor=jetbrains`,
  `toolchainVersion=21`.
- **Android SDK 36** (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`)

These replace "JDK 17+" and "Android SDK 35 (Min API 26)". A generic JDK 17
cannot satisfy a version-21 pin, and no Homebrew JDK satisfies the vendor half at
any version. Full commands: `docs/guides/mobile-build-and-test.md`.

### Tests

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
```

106 tests across eleven classes, all passing as of 2026-08-29. `JAVA_HOME` is not
optional: the toolchain pin above rejects any JDK that is not the JetBrains
Runtime, and Gradle fails at configuration time rather than falling back. The
failure reads "Unable to download toolchain", which is a *lookup* failure and not
an absence — the runtime is already on the machine.

**CI does not run this suite, so running it locally is the only gate on it.**
`.github/workflows/ci.yml` covers web, Go, the Rust CLI and the iOS `MonOcrCore`
package; the Android job is still blocked on provisioning a JetBrains Runtime 21
on the runner. Twelve of the 106 tests check `LineSegmenter`, `LineTiler` and
`PageNormalizer` against the shared fixtures in `shared/segmentation-fixtures/`,
which is the only automated check that this port still agrees with web and iOS.
`MergeFixtureTest` is the newest of the four and pins `mergeRuns`, the step that
stands between raw-profile boundary detection and a 22x garbage regression.
Skipping them is how the three ports drift apart quietly.

Run it before touching a decoder, a segmenter or the normaliser. Report and
troubleshooting: `docs/guides/mobile-build-and-test.md`.

### Getting Started

1. Open the project in Android Studio.
2. Build and run the `app` module on a device or emulator.
3. Grant camera and storage permissions when prompted.

> [!TIP]
> File size for mobile uploads is limited to 20MB. For processing larger datasets or high-resolution documents, we recommend using the Python CLI: `pip install monocr`.

4. Deploy to a physical device for optimal performance (NNAPI acceleration).

## Resources

- [Hugging Face Models](https://huggingface.co/janakhpon/monocr) (ONNX, Core ML; the TFLite export was removed at revision `a51be11`)
- [Unified SDKs](https://github.com/janakhpon/monocr-onnx) (ONNX Core)
- [MonOCR Monorepo](https://github.com/MonDevHub/monocr)

## Contributors

- [Janakh Pon](https://github.com/janakhpon)
- [Oung Seik Nyan](https://github.com/Oungseik)
- [Rajel Da Key](https://www.facebook.com/RJOMDK10)
