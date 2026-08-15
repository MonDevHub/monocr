# MonOCR iOS

MonOCR iOS provides high-performance, native optical character recognition for the Mon script natively on Apple devices. 

For mission context, community guidelines, and cross-platform information, please refer to the **[MonOCR Root Documentation](../../README.md)**.

## Overview

By leveraging a **Neural Engine optimized execution** model, MonOCR iOS utilizes **Core ML** and the Vision framework to perform all character recognition on-device. This architecture is purpose-built for the Apple Neural Engine (ANE), ensuring high-throughput, low-latency performance while maintaining an absolute privacy model—guaranteeing that linguistic assets remain within the localized secure environment.

## Key Features

- **On-Device Inference**: Optimized Core ML execution with ANE acceleration.
- **Privacy by Design**: Zero data collection; OCR processing is 100% local.
- **Mon Language Support**: Specialized for the Mon script (315-char charset).
- **Line Segmentation**: Automatic horizontal projection profiling for accurate text extraction.
- **Modern UI**: 100% SwiftUI with native animations and light/dark theme support.
- **Format Support**: Handles high-resolution images and multi-page PDFs.
- **Script Fidelity**: Integration of PyidaungSu fonts for correct Mon/Myanmar rendering.

> [!TIP]
> File size is limited to 50MB for web and 20MB for mobile. For processing larger files or leveraging more powerful hardware, please use the CLI or package directly via `uv add monocr` or `pip install monocr`.

## Architecture

```
Image (UIImage)
  LineSegmenter     -> horizontal projection profile -> List<LineSegment>
  ImagePreprocessor  -> grayscale + normalize [-1.0, 1.0]
  MonOcrEngine      -> Core ML Prediction (monocr.mlpackage)
  CtcDecoder        -> greedy CTC decode -> String
```

### Model Specification

| Attribute    | Specification                  |
| ------------ | ------------------------------ |
| Architecture | MobileNetV3 + BiLSTM-384 + CTC |
| Precision    | FP32 (Core ML)                 |
| Parameters   | ~6.6M                          |
| Input        | 128 × Variable (H × W)         |
| Asset Size   | 24.3 MB                        |

## Project Structure

```
apps/ios/
├── monocr-ios/
│   ├── engine/           # OCR Core (Core ML, Preprocessing, Decoding)
│   ├── ui/               # SwiftUI Views & ViewModels
│   ├── persistence/      # SwiftData models & History
│   ├── resources/        # Models, Fonts, & Assets
│   └── util/             # Platform & PDF utilities
├── monocr-iosTests/      # Unit & Performance Tests
└── monocr-ios.xcodeproj  # Xcode Project
```

## Ecosystem

MonOCR is a unified cross-platform ecosystem designed for parity and performance:

- **[MonOCR Web](https://github.com/MonDevHub/monocr-web)**: Privacy-first in-browser OCR.
- **[MonOCR Android](https://github.com/MonDevHub/monocr)**: (In this Monorepo) Native Jetpack Compose app.
- **[MonOCR iOS](https://github.com/MonDevHub/monocr)**: (In this Monorepo) Native SwiftUI app.

## Development

### Prerequisites

- **Xcode 15.0+**
- **iOS 16.0+**
- **Swift 5.9+**

### Getting Started

1.  Clone the repository and open `monocr-ios.xcodeproj` in **Xcode**.
2.  Ensure `monocr.mlpackage` is correctly referenced in the app target.
3.  Build and run on a physical device for optimal performance (ANE acceleration).
4.  Use the **Document Picker** or **Camera** to begin character recognition.

## Resources

- [Hugging Face Models](https://huggingface.co/janakhpon/monocr) (ONNX, TFLite, Core ML)
- [Unified SDKs](https://github.com/janakhpon/monocr-onnx) (ONNX Core)
- [MonOCR Monorepo](https://github.com/MonDevHub/monocr)

## Contributors

- [Janakh Pon](https://github.com/janakhpon)
- [Oung Seik Nyan](https://github.com/Oungseik)
- [Rajel Da Key](https://www.facebook.com/RJOMDK10)

## License

MIT
