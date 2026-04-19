# MonOCR (အင်ဂျင်ဗှ်လိခ်မန်)

![MonOCR Feature Graphic](assets/ocr_feature_graphic.jpg)

[English](README.md) | [မြန်မာဘာသာ](README.my.md) | [ဘာသာမန်](README.mnw.md)

---

## တင်ဂရင် မင်မွဲ အရေဝ်ဘာသာ (Linguistic Preservation Objectives)

MonOCR ဝွံ ဒှ်ပရဝ်ဂျေသူ open-source မနွံကဵုတင်ဂရင် သွက်ဂွံမင်မွဲအာ **အရေဝ်ဘာသာမန် (mnw)** နကဵုဒစ်ဂျေတေဝ်ရ။ [UNESCO](https://www.unesco.org/en) ဟီုလဝ် ဒှ်ဘာသာမဒးမင်မွဲမွဲရ။ ပရဝ်ဂျေသူဝွံ စကာလဝ် **zero-leak privacy** သွက်ဂွံဗှ်လိခ်မန် နကဵုအော့ဖ်လိုင်း (offline) ရ။

### တင်ဂရင် သုတေသန ကေုာံ Dataset (Research Trajectory)

လက်ရှိ inference engine (~6.6M parameters) ဝွံ ဒှ် V1 implementation မဗဒှ်လဝ် သွက်ဂွံစကာ ပ္ဍဲ Edge inference ရ။ ဟိုတ်နူ တင်ဂရင် Dataset သွက်လိခ်မန် ရှားပါးဒၟံင်ဂှ်ရ ပရဝ်ဂျေသူဝွံ စကာလဝ် **Data acquisition terminal** မွဲဒှ်တုဲ [Feedback Service](services/feedback) ဝွံ မဒှ်အဓိက သွက်ဂွံစုစည်း တင်ဂရင် Dataset ရ။ တင်ဂရင် စုစည်းလဝ်တအ်ဝွံ သွက်ဂွံဆက်ကၠောန်အာ Model မနွံကဵု စွမ်းဆောင်ရည် သၠုင်သၠုင် ပ္ဍဲဂတတေဝ်ရ။

---

### တိုက်ရိုက်အသုံးပြုရန် (Live Access)

- **Web**: [ocr.mondevhub.com](https://ocr.mondevhub.com)
- **Android**: [Google Play Store](https://play.google.com/store/apps/details?id=dev.janakhpon.monocr)
- **iOS**: [Apple App Store](https://apps.apple.com/app/monocr) (Coming Soon)

---

## ပလက်ဖောင်း နည်းပညာစနစ် (Platform Architecture)

MonOCR ဝွံ သွက်ဂွံဒှ်အဆင့်အတိုင် **architectural parity** မတူကဵု ပ္ဍဲ platform ဖအိုတ်ဂှ် စကာလဝ် တွက်ချက်မှုပုံစံ မွဲဓဝ်ရ။ သွက်ဂွံဂွံ hardware-accelerated performance ဂှ် စကာလဝ် serialization မတူကဵု ပ္ဍဲ platform ဖအိုတ်ရ-

- **Web/Android**: Standardized ONNX weights.
- **iOS**: Optimized for Apple Neural Engine via CoreML (`.mlpackage`).

### Implementation Cross-Reference

| Concern                 | Principal Implementation        | Architectural Rationale                 |
| :---------------------- | :------------------------------ | :-------------------------------------- |
| **Model (Web/Android)** | `apps/android/.../monocr.onnx`  | Deterministic cross-platform benchmarks |
| **Model (iOS)**         | `apps/ios/.../monocr.mlpackage` | ANE-optimized hardware utilization      |
| **Asset Sync**          | `shared/locales/sync.mjs`       | Multi-target linguistic idempotency     |
| **Ingestion Auth**      | `internal/auth/middleware.go`   | Perimeter security for asset ingestion  |
| **Native Execution**    | `engine/MonOcrEngine.swift`     | Hardware-bound inference logic          |

- **[Web App](apps/web)**: Browser-bound SvelteKit PWA.
- **[Android App](apps/android)**: Native Jetpack Compose (NNAPI)။
- **[iOS App](apps/ios)**: Native SwiftUI (Apple Neural Engine)။
- **[Feedback Service](services/feedback)**: Mobile-focused ingestion API (Go)။
- **[Core Assets](shared)**: ONNX models ကေုာံ Localization tools တအ်ရ။

---

## Documentation Hub (တင်ဂရင် နဲကဲတအ်)

တင်ဂရင် နဲကဲဖအိုတ်၊ Architecture decisions ကေုာံ setup guides တအဲဂှ် စုစည်းလဝ် ပ္ဍဲ **[Documentation Hub](docs)** ရ။

- **[Architecture (ADRs)](docs/architecture/adr)**: Architecture decision records။
- **[API Specifications](docs/api)**: OpenAPI contracts တအ်ရ။
- **[HuggingFace Models - ONNX, CoreML, CKPT](https://huggingface.co/janakhpon/monocr)**: Core inference assets။
- **[NPM Package](https://www.npmjs.com/package/monocr)**: Portable SDK။

---

## Community and Support (ဂၠံင်တရဴ ပရဝ်ဂျေသူ)

<sub>[Janakh Pon](https://github.com/janakhpon) • [Oung Seik Nyan](https://github.com/Oungseik) • [Rajel Da Key](https://www.facebook.com/RJOMDK10) • [MonDevHub](https://github.com/MonDevHub)</sub>

- **တင်တုံ့ပြန်**: သွက်တင်ဂရင် နဲကဲဂှ် စကာညိ [GitHub Issues](https://github.com/MonDevHub/monocr/issues) ရ။
- **ဘာသာပြန်တအ်**: ဗိုင်ရီုညိ ပ္ဍဲကဵု [ဘာသာပြန်စာရင်း](https://docs.google.com/spreadsheets/d/1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ/edit?usp=sharing) ရ။
- **Dataset ဗိုင်ရီုညိ**: ဗိုင်ပလံင်ညိ ရုပ်လိခ်မန်တအ် နူကဵု Android သာ်ဟွံသေင် iOS App ရ။
- **နဲကဲ ပါလုပ်ညိ**: ဗှ်ညိ [Contributing Guide](.github/CONTRIBUTING.md) ကေုာံ [Security Policy](.github/SECURITY.md) ရ။

> [!NOTE]
> [ဘာသာမန်](https://en.wikipedia.org/wiki/Mon_language) ဝွံ ပ္ဍဲကဵု [UNESCO](https://en.wikipedia.org/wiki/Atlas_of_the_World%27s_Languages_in_Danger) ဟီု lဝ် ဒှ်ဘာသာမဒးမင်မွဲမွဲရ။
