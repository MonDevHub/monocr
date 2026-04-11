# MonOCR

![MonOCR Feature Graphic](assets/ocr_feature_graphic.jpg)

[English](README.md) | [မြန်မာဘာသာ](README.my.md) | [ဘာသာမန်](README.mnw.md)

---

## Linguistic Preservation Objectives

MonOCR is an open-source technical framework dedicated to the digital preservation of the **Mon language (mnw)**. Classified by [UNESCO](https://www.unesco.org/en) as a vulnerable script, Mon lacks standardized inclusion in global OCR toolchains.

This project establishes a **zero-leak privacy** foundation for character recognition, enabling offline digitization of historical and community-sourced manuscripts.

### Research Trajectory & Dataset Growth

The current inference engine (~6.6M parameters) is a V1 implementation optimized for low-latency edge execution. Given the historical scarcity of high-quality Mon-Burmese datasets, this platform acts as a **data acquisition terminal**. The integrated [Feedback Service](services/feedback) enables the collection and auditing of community-sourced manuscripts, which will directly inform the training of future, higher-capacity recognition models.

---

## Live Access

- **Web**: [ocr.mondevhub.com](https://ocr.mondevhub.com)
- **Android**: [Google Play Store](https://play.google.com/store/apps/details?id=dev.janakhpon.monocr)
- **iOS**: [Apple App Store](https://apps.apple.com/app/monocr) (Review Pending)

---

## Platform Architecture

MonOCR maintains absolute **architectural parity** across all targets. While the underlying mathematical model is unified, it is delivered via platform-optimized serialization to maximize hardware-accelerated performance:

- **Web/Android**: Standardized via universal ONNX weights.
- **iOS**: Optimized for Apple Neural Engine via CoreML (`.mlpackage`).

### Implementation Cross-Reference

| Concern                 | Principal Implementation        | Architectural Rationale                 |
| :---------------------- | :------------------------------ | :-------------------------------------- |
| **Model (Web/Android)** | `apps/android/.../monocr.onnx`  | Deterministic cross-platform benchmarks |
| **Model (iOS)**         | `apps/ios/.../monocr.mlpackage` | ANE-optimized hardware utilization      |
| **Asset Sync**          | `shared/locales/sync.mjs`       | Multi-target linguistic idempotency     |
| **Ingestion Auth**      | `internal/auth/middleware.go`   | Perimeter security for asset ingestion  |
| **Native Execution**    | `engine/MonOcrEngine.swift`     | Hardware-bound inference logic          |

### System Specifications

| Attribute               | Specification              | Rationale                                              |
| :---------------------- | :------------------------- | :----------------------------------------------------- |
| **Model Architecture**  | MobileNetV3 + BiLSTM + CTC | Optimal accuracy-to-latency ratio for edge inference   |
| **Parameter Count**     | ~6.6M                      | Balanced for browser-bound execution limits            |
| **Asset footprint**     | ~25MB (FP32)               | Optimized for delivery via edge CDNs                   |
| **Inference Precision** | FP32 / ANE-Optimized       | Maximizing character fidelity in low-resource contexts |

- **[Web App](apps/web)**: Browser-bound SvelteKit PWA.
- **[Android App](apps/android)**: Native Jetpack Compose (NNAPI).
- **[iOS App](apps/ios)**: Native SwiftUI (Apple Neural Engine).
- **[Feedback Service](services/feedback)**: Mobile-focused ingestion API (Go).
- **[Core Assets](shared)**: Shared assets and synchronization logic.

---

## Documentation Hub

All technical documentation, architectural decisions, and setup guides are centralized in the **[Documentation Hub](docs)**.

- **[Architecture (ADRs)](docs/architecture/adr)**: Logical decision records.
- **[API Specifications](docs/api)**: OpenAPI contracts.
- **[HuggingFace Models - ONNX, CoreML, CKPT](https://huggingface.co/janakhpon/monocr)**: Core inference assets.
- **[NPM Package](https://www.npmjs.com/package/monocr)**: Portable SDK.

---

## Community and Support

MonOCR is a collaborative linguistic preservation project maintained by [Janakh Pon](https://github.com/janakhpon), [Oung Seik Nyan](https://github.com/Oungseik), and the MonDevHub community.

- **Feedback**: Report technical bugs via [GitHub Issues](https://github.com/MonDevHub/monocr/issues).
- **Linguistic Assets**: Audit our [shared translation sheet](https://docs.google.com/spreadsheets/d/1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ/edit?usp=sharing).
- **Dataset Acquisition**: Contribute script samples via our Android or iOS applications or reach out directly.
- **Technical Standards**: Review our [Contributing Guide](.github/CONTRIBUTING.md) and [Security Policy](.github/SECURITY.md).

> [!NOTE]
> The [Mon language](https://en.wikipedia.org/wiki/Mon_language) is classified as a "vulnerable" language in [UNESCO's Atlas of the World’s Languages in Danger](https://en.wikipedia.org/wiki/Atlas_of_the_World%27s_Languages_in_Danger).
