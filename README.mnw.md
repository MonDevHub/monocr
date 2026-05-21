# MonOCR (အင်ဂျင်ဗှ်လိခ်မန်)

![MonOCR Feature Graphic](assets/ocr_feature_graphic.jpg)

[English](README.md) | [မြန်မာဘာသာ](README.my.md) | [ဘာသာမန်](README.mnw.md)

---

ဘာသာမန် ဝွံ ဒုင်ကျောဝ် နကဵုလိုန်ဂၠး တဝှ်ဗ္ဒဲါ ပ္ဍဲဍုင်မန်တိုင်း ကေုာံ ဍုင်တာဲ — [UNESCO ဟီုလဝ် ဒှ်ဘာသာမဒးမင်မွဲ](https://en.wikipedia.org/wiki/Atlas_of_the_World%27s_Languages_in_Danger) — နွံကဵု ပရဝ်ဂျေသူဝွံ ဗဒှ်အဓိပ္ပါယ် ပ္ဍဲဂတဝွံ မၞိဟ်မတၟောဝ် OCR toolchain သောင်ကလး သ္ပစိုတ် လ္ပကၠောန်ဒၟံင်ရ။

MonOCR ဝွံ ဒှ်ပရဝ်ဂျေသူ မလ္ပကၠောန်ဒၟံင် ဗိုင်ရီုဒဒှ် ပ္ဍဲကဵု community မန်ရ — ဆိင်ကေတ် ရုပ်ပ္တိုန်လိခ်မန်တုဲ ကဵုဒါန် ဗီုရ (text) ကဵုဒါန်ရ။ ဒှ်ကမၠောန် Web, Android ကေုာံ iOS ရ — offline ပြဃောဗၞော်ရ၊ data တုဲဟွံထၟောဝ်ကဵု ဗော်ဂြိုပ်ဟွံမွဲရ။

---

## Live

- **Web**: [ocr.mondevhub.com](https://ocr.mondevhub.com)
- **Android**: [Google Play](https://play.google.com/store/apps/details?id=dev.janakhpon.monocr)
- **iOS**: [App Store](https://apps.apple.com/app/monocr) *(ပြန်လည်စစ်ဆေးဆဲ)*

---

## Models

Model ၜါ ဗဒှ်ကဵု train ဒၟံင် ကေုာံ မင်မွဲဒၟံင်ရ-

| | **v3.5 — Mobile** | **v4 — Server** |
| :--- | :--- | :--- |
| ရည်ရွယ်ချက် | On-device / edge | Server-side / လိခ်ဒြဟ်တ် |
| Architecture | MobileNetV3 + 2×BiLSTM(512) + CTC | Swin-T Encoder + 6-layer Transformer Decoder |
| Parameters | 11.4M | ~54M |
| Input | Grayscale, `160px` အမြင့် | RGB, `224×1024px` |
| Export | ONNX FP32/FP16/INT8 · CoreML | ONNX ဓဝ် |
| Inference (CPU) | ~30ms/line | ~180ms/line |

mobile model (v3.5) ဝွံ ဒှ်ကမၠောန် ပ္ဍဲ Web (WASM), Android (NNAPI) ကေုာံ iOS (Core ML) — on-device ရ။ server model (v4) ဝွံ ဒတဴပ္ဍဲ ပွိုင်ဒြဟ်တ် နွံကဵုဇမၞော် ကေုာံ ပ္ဍဲဒြဟ်တ် ကာလဇမၞော်ရ။

ဟိုတ်နူ dataset အရေဝ်မန် ရှားပါးဒၟံင်ဂှ်ရ validated sample တအ် နူကဵု feedback flow တုဲ ကဵုဗဒှ် training round မဂတဝ်ရ။

---

## Platform

mobile model (v3.5) ဝွံ ဒှ်ကမၠောန် ပ္ဍဲ Web, Android ကေုာံ iOS — hardware acceleration နွံကဵု format မတူကဵုမတူဂှ်ရ-

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

- **[HuggingFace](https://huggingface.co/janakhpon/monocr)** — ONNX, CoreML ကေုာံ checkpoint ဖိုင်တအ်
- **[npm package](https://www.npmjs.com/package/monocr)** — JavaScript SDK
- **[Architecture decisions](docs/architecture/adr)** — ADRs
- **[API specs](docs/api)** — OpenAPI contracts
- **[Mon Corpus Collection](https://github.com/MonDevHub/MonCorpusCollection)** — training dataset

---

## Contributing

- **တင်တုံ့ပြန်**: [GitHub Issues](https://github.com/MonDevHub/monocr/issues)
- **ဘာသာပြန်တအ်**: ဗိုင်ရီုညိ ပ္ဍဲကဵု [ဘာသာပြန်စာရင်း](https://docs.google.com/spreadsheets/d/1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ/edit?usp=sharing)
- **Script samples**: ဗိုင်ပလံင်ညိ ရုပ်လိခ်မန်တအ် နူကဵု Android သာ်ဟွံသေင် iOS App ရ
- **နဲကဲ ပါလုပ်ညိ**: ဗှ်ညိ [Contributing Guide](.github/CONTRIBUTING.md) ကေုာံ [Security Policy](.github/SECURITY.md)

[Janakh Pon](https://github.com/janakhpon) · [Oung Seik Nyan](https://github.com/Oungseik) · [Rajel Da Key](https://www.facebook.com/RJOMDK10) · [MonDevHub](https://github.com/MonDevHub)
