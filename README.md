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

## The model

All three apps ship one model, and it is **v2**:

| | |
| :--- | :--- |
| Architecture | MobileNetV3 + BiLSTM-384 + CTC |
| Parameters | ~6.6M |
| Input | Grayscale, `128px` height |
| Charset | 315 characters |
| Precision | FP32 |
| Published at | [`janakhpon/monocr`](https://huggingface.co/janakhpon/monocr), revision `a51be11` |

Android and iOS bundle it (26.3 MB and 24.3 MB respectively). The web app fetches
it from that pinned revision. Per-app details are in
[apps/android](apps/android), [apps/ios](apps/ios) and [apps/web](apps/web).

A **v3.5** model exists upstream in `mon_OCR` — 11,553,437 parameters, 160px input,
276 characters. It is not published and nothing here can load it: the two
generations disagree on class count and input height, and the web app now refuses
to decode across that gap rather than returning wrong text. Adopting it is a
coordinated change, not a file swap.

A **v4** server model was archived on 2026-08-05 under `mon_OCR` ADR-0011. It was
never trained to convergence, so archiving it was a decision about maintaining a
second path rather than about measured quality. It is not maintained.

No device latency number exists for any platform. Figures of that kind appeared
here until 2026-08-15 and were architectural estimates, never measured on
hardware.

Because high-quality Mon datasets are scarce, validated samples from the app's feedback flow feed directly into future training rounds.

---

## Platform

The model deploys to Web, Android, and iOS — each using the format that enables hardware acceleration:

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
