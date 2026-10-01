# MonOCR

![MonOCR Feature Graphic](assets/ocr_feature_graphic.jpg)

[English](README.md) | [မြန်မာဘာသာ](README.my.md) | [ဘာသာမန်](README.mnw.md)

MonOCR reads printed Mon script from images and PDFs and returns Unicode text, on the device.

Mon is spoken by roughly one million people across Myanmar and Thailand. [UNESCO classifies it as vulnerable](https://en.wikipedia.org/wiki/Atlas_of_the_World%27s_Languages_in_Danger). MonOCR is built and maintained by the Mon developer community.

## What is in this repository

| Path | What | Status |
| :--- | :--- | :--- |
| [`apps/web`](apps/web) | SvelteKit PWA | Live at [ocr.mondevhub.com](https://ocr.mondevhub.com); works offline once the model is cached |
| [`apps/android`](apps/android) | Jetpack Compose app | Build from source; not on Google Play |
| [`apps/ios`](apps/ios) | SwiftUI app | Build from source; not on the App Store |
| [`apps/cli`](apps/cli) | Rust CLI for batches of books, PDFs and images | Published on crates.io as `monocr-cli` |
| [`services/feedback`](services/feedback) | Go API that stores opt-in contributions | Used by the Android and iOS apps |
| [`shared`](shared) | Locales, the API contract, and the segmentation fixtures every port is tested against | |
| [`samples`](samples) | Three real documents and the CLI's unedited output | |
| [`docs`](docs) | ADRs, the OpenAPI spec, build guides | |

## Quick start

**Web.** Use [ocr.mondevhub.com](https://ocr.mondevhub.com), or run it locally with Node.js 22 and pnpm 10:

```bash
pnpm install
cd apps/web && pnpm dev
```

**Android.** The build requires a JetBrains Runtime 21, such as the one bundled with Android Studio (macOS path shown):

```bash
cd apps/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

**iOS.** Open `apps/ios/monocr-ios.xcodeproj` in Xcode 26.2 or later and run on a device.

**CLI.** Poppler is needed for PDFs only:

```bash
cargo install monocr-cli
brew install poppler            # or apt-get install poppler-utils
monocr-cli extract book.pdf -o ./out
```

Each app's README has its prerequisites, tests and specifics. [`docs/guides/mobile-build-and-test.md`](docs/guides/mobile-build-and-test.md) has the full Android and iOS build and test commands.

## How recognition works

Each app finds the text lines on the page (binarise, clear printed rules, projection profile), cuts lines wider than the model window at whitespace, scales each piece to 160 × 1024 grayscale, runs the model, and decodes the output with greedy CTC. PDFs are rasterised first, so a Zawgyi or legacy-font text layer makes no difference: two of the [samples](samples/) are exactly that.

All four apps use one model, **v3.5**:

| | |
| :--- | :--- |
| Architecture | MobileNetV3-Large + SE + 2×BiLSTM-512 + attention + CTC |
| Parameters | 11,553,437 |
| Input | Grayscale, `160px` height, static `1024px` width |
| Charset | 276 characters, 277 classes |
| Precision | FP32 |
| Published at | [`janakhpon/monocr`](https://huggingface.co/janakhpon/monocr), revision `d3d9d5e` |

Android and iOS bundle it (46.2 MB and 46.3 MB respectively). The web app and the CLI fetch it from that pinned revision.

| Platform | Format | Execution provider requested |
| :--- | :--- | :--- |
| Web | ONNX | WebGPU where the browser offers it, otherwise WASM |
| Android | ONNX | NNAPI, with CPU fallback |
| iOS | CoreML `.mlpackage` | Core ML, all compute units |
| CLI | ONNX | ONNX Runtime default (CPU) |

**v3.5 is not a drop-in for v2.** Input height went 128 to 160, output classes 316 to 277, charset 315 to 276, and the width axis from dynamic to a static 1024. The apps refuse a model that does not match rather than decode it, because a mismatch returns well-formed Mon text that is wrong. **v2** is still served at revision `a51be11` for anyone pinned to it.

## Limitations

**Held-out CER 0.0100** on 150 unseen lines in a typeface the model never trained on (95% interval 0.0056 to 0.0147), measured 2026-08-16. The [model card](https://huggingface.co/janakhpon/monocr#performance) states its limits:

- **n = 150**, so the interval is wide.
- **One typeface.** All 150 lines are Pyidaungsu, held out from training.
- **Unseen text, not an unseen renderer.** Training and test images came from the same generator, so the number says nothing yet about camera photographs of real pages.
- **Disjointness is argued, not directly verified.**

The [samples](samples/) are selected best cases, and their README says what was screened out and how unscreened material does.

No on-device latency has been measured on any platform. The execution providers above are what each app asks for: whether NNAPI or the Neural Engine actually runs the BiLSTM layers has not been checked on a device, and they are not expected to.

Imports are capped at 50 MiB on web, Android and iOS, and the mobile contribute and sync path at 20 MiB. The CLI refuses PDFs over 500 MiB or 3,000 pages. The `pip install monocr` shown on the apps' docs pages is a separate Python project, [`janakhpon/monocr`](https://github.com/janakhpon/monocr), with its own segmentation, so its page-level output will not match the apps line for line.

CI runs the web unit tests and production build, the Android unit tests, the iOS `MonOcrCore` package tests, the CLI tests and the feedback service tests on every push. It does not build the Android or iOS app, and there are no instrumented or UI tests.

## Related

- [Model card and exports](https://huggingface.co/janakhpon/monocr) on Hugging Face (ONNX and Core ML)
- [`monocr-onnx`](https://github.com/janakhpon/monocr-onnx): the Python, JavaScript, Go and Rust SDKs; the CLI is built on its Rust library
- [`monocr` on npm](https://www.npmjs.com/package/monocr): the JavaScript SDK
- [Mon Corpus Collection](https://github.com/MonDevHub/MonCorpusCollection): training dataset
- [Architecture decisions](docs/architecture/adr) and [API specs](docs/api)

## Contributing

- **Bugs**: [GitHub Issues](https://github.com/MonDevHub/monocr/issues)
- **Translations**: [Shared translation sheet](https://docs.google.com/spreadsheets/d/1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ/edit?usp=sharing)
- **Script samples**: Contribute via the Android or iOS app, or reach out directly. High-quality Mon datasets are scarce, so validated samples from the feedback flow feed into future training rounds.
- **Standards**: [Contributing Guide](.github/CONTRIBUTING.md) · [Security Policy](.github/SECURITY.md)

[Janakh Pon](https://github.com/janakhpon) · [Oung Seik Nyan](https://github.com/Oungseik) · [Rajel Da Key](https://www.facebook.com/RJOMDK10) · [MonDevHub](https://github.com/MonDevHub)

## Licence

[MIT](LICENSE)
