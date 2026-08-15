# MonOCR Android

MonOCR Android brings high-performance, native optical character recognition for the Mon script natively to Android devices.

For mission context, community guidelines, and cross-platform information, please refer to the **[MonOCR Root Documentation](../../README.md)**.

## Overview

By implementing a **hardware-accelerated native inference** model, MonOCR Android leverages **ONNX Runtime** with NNAPI integration to perform all character recognition on-device. This ensures high-throughput, low-latency performance while maintaining a zero-leak privacy model—guaranteeing that linguistic data remains within the localized secure environment.

## Key Features

- **On-Device Inference**: Powered by ONNX Runtime with hardware acceleration.
- **Privacy by Design**: Zero data collection unless users want to contribute intentionally; OCR processing is 100% local.
- **Mon Language Support**: Specialized for the Mon script (276-char charset).
- **Line Segmentation**: Automatic horizontal projection profiling for complex layouts.
- **Format Support**: Handles large PDFs and high-resolution images.
- **Script Fidelity**: Integration of PyidaungSu fonts for accurate Mon/Myanmar rendering.

> [!TIP]
> File size is limited to 50MB for web and 20MB for mobile. For processing larger files or leveraging more powerful hardware, please use the CLI or package directly via `uv add monocr` or `pip install monocr`.

## Architecture

```
Image (Bitmap)
  LineSegmenter     -> horizontal projection profile -> List<LineSegment>
  ImagePreprocessor  -> crop + scale + normalize [-1.0, 1.0]
  MonOcrEngine      -> ONNX Runtime Session (monocr.onnx)
  CtcDecoder        -> greedy CTC decode -> String
```

### Model Specification

| Attribute    | Specification                  |
| ------------ | ------------------------------ |
| Architecture | MobileNetV3 + BiLSTM-384 + CTC |
| Precision    | FP32 (ONNX)                    |
| Parameters   | 11.55M                          |
| Input        | 160 × 1024 px (H × W)          |
| Asset Size   | 46.2 MB                        |

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

- **Android Studio** (Latest stable version)
- **JDK 17+**
- **Android SDK 35** (Min API 26)

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
