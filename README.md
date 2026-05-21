# MonOCR

![MonOCR Feature Graphic](assets/ocr_feature_graphic.jpg)

[English](README.md) | [မြန်မာဘာသာ](README.my.md) | [ဘာသာမန်](README.mnw.md)

---

Mon is spoken by roughly one million people across Myanmar and Thailand. [UNESCO classifies it as vulnerable](https://en.wikipedia.org/wiki/Atlas_of_the_World%27s_Languages_in_Danger) — and no OCR toolchain existed for it before this project.

MonOCR takes an image of Mon script and returns text. It runs on Web, Android, and iOS — fully offline, no data leaves the device.

Built and maintained by the Mon developer community.

---

## Live

- **Web**: [ocr.mondevhub.com](https://ocr.mondevhub.com)
- **Android**: [Google Play](https://play.google.com/store/apps/details?id=dev.janakhpon.monocr)
- **iOS**: [App Store](https://apps.apple.com/app/monocr) *(pending review)*

---

## Models

Two models are actively trained and maintained:

| | **v3.5 — Mobile** | **v4 — Server** |
| :--- | :--- | :--- |
| Purpose | On-device / edge | Server-side / documents |
| Architecture | MobileNetV3 + 2×BiLSTM(512) + CTC | Swin-T Encoder + 6-layer Transformer Decoder |
| Parameters | 11.4M | ~54M |
| Input | Grayscale, `160px` height | RGB, `224×1024px` |
| Export | ONNX FP32/FP16/INT8 · CoreML | ONNX only |
| Inference (CPU) | ~30ms/line | ~180ms/line |

The mobile model (v3.5) runs on-device across Web (WASM), Android (NNAPI), and iOS (Core ML). The server model (v4) handles complex document images with colour backgrounds and longer text sequences.

Because high-quality Mon datasets are scarce, validated samples from the app's feedback flow feed directly into future training rounds.

---

## Platform

The mobile model (v3.5) deploys to Web, Android, and iOS — each using the format that enables hardware acceleration:

| Platform | Format | Acceleration |
| :--- | :--- | :--- |
| Web | ONNX | WASM |
| Android | ONNX | NNAPI |
| iOS | CoreML `.mlpackage` | Apple Neural Engine |

- **[Web App](apps/web)** — SvelteKit PWA
- **[Android App](apps/android)** — Jetpack Compose
- **[iOS App](apps/ios)** — SwiftUI
- **[Feedback Service](services/feedback)** — Go ingestion API
- **[Shared Assets](shared)** — model weights, locales, sync scripts

---

## Resources

- **[HuggingFace](https://huggingface.co/janakhpon/monocr)** — ONNX, CoreML, and checkpoint files
- **[npm package](https://www.npmjs.com/package/monocr)** — JavaScript SDK
- **[Architecture decisions](docs/architecture/adr)** — ADRs
- **[API specs](docs/api)** — OpenAPI contracts
- **[Mon Corpus Collection](https://github.com/MonDevHub/MonCorpusCollection)** — training dataset

---

## Contributing

- **Bugs**: [GitHub Issues](https://github.com/MonDevHub/monocr/issues)
- **Translations**: [Shared translation sheet](https://docs.google.com/spreadsheets/d/1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ/edit?usp=sharing)
- **Script samples**: Contribute via the Android or iOS app, or reach out directly
- **Standards**: [Contributing Guide](.github/CONTRIBUTING.md) · [Security Policy](.github/SECURITY.md)

[Janakh Pon](https://github.com/janakhpon) · [Oung Seik Nyan](https://github.com/Oungseik) · [Rajel Da Key](https://www.facebook.com/RJOMDK10) · [MonDevHub](https://github.com/MonDevHub)
