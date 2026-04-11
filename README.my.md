# မွန် OCR (MonOCR)

![MonOCR Feature Graphic](assets/ocr_feature_graphic.jpg)

[English](README.md) | [မြန်မာဘာသာ](README.my.md) | [ဘာသာမန်](README.mnw.md)

---

## ဘာသာစကားထိန်းသိမ်းခြင်းဆိုင်ရာ ရည်မှန်းချက်များ (Linguistic Preservation Objectives)

MonOCR သည် **မွန်ဘာသာစကား (mnw)** ကို ဒစ်ဂျစ်တယ်စနစ်ဖြင့် ထိန်းမ်းရန် ရည်ရွယ်သည့် open-source နည်းပညာမူဘောင်တစ်ခုဖြစ်သည်။ [UNESCO](https://www.unesco.org/en) မှ အသုံးပြုသူနည်းလာသော ဘာသာစကားအဖြစ် သတ်မှတ်ထားသော မွန်ဘာသာစကားအတွက် ဤပရောဂျက်သည် **zero-leak privacy** နည်းပညာကို အခြေခံ၍ အော့ဖ်လိုင်း (offline) စာသားဖတ်ယူမှုစနစ်ကို တည်ဆောက်ထားပါသည်။

### သုတေသန လုပ်ငန်းစဉ်နှင့် အချက်အလက် စုဆောင်းခြင်း (Research Trajectory)

လက်ရှိ အသုံးပြုထားသော inference engine (~6.6M parameters) သည် low-latency ဖြင့် အလုပ်လုပ်နိုင်ရန် အကောင်းဆုံးဖြစ်အောင် ပြုပြင်ထားသော V1 အဆင့်သာ ဖြစ်ပါသည်။ မွန်ဘာသာစကားအတွက် အရည်အသွေးမြင့် ဒစ်ဂျစ်တယ် datasets များ ရှားပါးနေသေးသည့်အတွက် ဤပလက်ဖောင်းသည် **အချက်အလက် စုဆောင်းရေး (data acquisition)** အတွက်ပါ ရည်ရွယ်ပါသည်။ [Feedback Service](services/feedback) မှတစ်ဆင့် စုဆောင်းရရှိလာသော အချက်အလက်များကို အသုံးပြု၍ နောင်တွင် ပိုမိုစွမ်းဆောင်ရည်မြင့်မားသော OCR model များကို ဆက်လက် တည်ဆောက်သွားမည် ဖြစ်ပါသည်။

---

### တိုက်ရိုက်အသုံးပြုရန် (Live Access)

- **Web**: [ocr.mondevhub.com](https://ocr.mondevhub.com)
- **Android**: [Google Play Store](https://play.google.com/store/apps/details?id=dev.janakhpon.monocr)
- **iOS**: [Apple App Store](https://apps.apple.com/app/monocr) (Coming Soon)

---

## ပလက်ဖောင်း နည်းပညာစနစ် (Platform Architecture)

MonOCR သည် ပလက်ဖောင်းအားလုံးတွင် တူညီသော **architectural parity** ရရှိရန် တွက်ချက်မှုပုံစံတစ်ခုတည်းကိုသာ အသုံးပြုထားသည်။ သို့သော် ဟာ့ဒ်ဝဲစွမ်းဆောင်ရည် အပြည့်အဝရရှိစေရန်အတွက် ပလက်ဖောင်းအလိုက် အကောင်းဆုံးဖြစ်အောင် ပြောင်းလဲထားသော စနစ်များကို အသုံးပြုထားပါသည်-

- **Web/Android**: Standardized ONNX weights.
- **iOS**: Apple Neural Engine အတွက် CoreML (`.mlpackage`).

### Implementation Cross-Reference

| Concern                 | Principal Implementation        | Architectural Rationale                 |
| :---------------------- | :------------------------------ | :-------------------------------------- |
| **Model (Web/Android)** | `apps/android/.../monocr.onnx`  | Deterministic cross-platform benchmarks |
| **Model (iOS)**         | `apps/ios/.../monocr.mlpackage` | ANE-optimized hardware utilization      |
| **Asset Sync**          | `shared/locales/sync.mjs`       | Multi-target linguistic idempotency     |
| **Ingestion Auth**      | `internal/auth/middleware.go`   | Perimeter security for asset ingestion  |
| **Native Execution**    | `engine/MonOcrEngine.swift`     | Hardware-bound inference logic          |

- **[Web App](apps/web)**: Browser အခြေပြု SvelteKit PWA.
- **[Android App](apps/android)**: Native Jetpack Compose (NNAPI).
- **[iOS App](apps/ios)**: Native SwiftUI (Apple Neural Engine).
- **[Feedback Service](services/feedback)**: Mobile-focused ingestion API (Go).
- **[Core Assets](shared)**: အသုံးပြုရန် ONNX model များနှင့် Localization tools များ။

---

## နည်းပညာဆိုင်ရာ စာရွက်စာတမ်းများ (Documentation Hub)

နည်းပညာဆိုင်ရာ စာရွက်စာတမ်းများ၊ ဗိသုကာစနစ် ဆုံးဖြတ်ချက်များနှင့် တပ်ဆင်နည်းလမ်းညွှန်များကို **[Documentation Hub](docs)** တွင် စုစည်းထားသည်။

- **[ဗိသုကာစနစ် (ADRs)](docs/architecture/adr)**: နည်းပညာဆိုင်ရာ ဆုံးဖြတ်ချက်မှတ်တမ်းများ။
- **[API Specifications](docs/api)**: OpenAPI ဆိုင်ရာ အချက်အလက်များ။
- **[HuggingFace Models - ONNX, CoreML, CKPT](https://huggingface.co/janakhpon/monocr)**: OCR ရယူနိုင်သော model များ။
- **[NPM Package](https://www.npmjs.com/package/monocr)**: Portable SDK.

---

## လူမှုကွန်ရက်နှင့် အကူအညီများ (Community and Support)

MonOCR သည် မွန်စာမူများကို ထိန်းသိမ်းရန်အတွက် [Janakh Pon](https://github.com/janakhpon)၊ [Oung Seik Nyan](https://github.com/Oungseik) နှင့် MonDevHub အဖွဲ့ဝင်များမှ ပူးပေါင်းလုပ်ဆောင်နေသော ပရောဂျက်တစ်ခုဖြစ်သည်။

- **အကြံပြုချက်များ**: [GitHub Issues](https://github.com/MonDevHub/monocr/issues) တွင် အသိပေးနိုင်ပါသည်။
- **ဘာသာပြန်ချက်များ**: ကျွန်ုပ်တို့၏ [ဘာသာပြန်စာရင်း](https://docs.google.com/spreadsheets/d/1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ/edit?usp=sharing) တွင် ပါဝင်ကူညီနိုင်ပါသည်။
- **အချက်အလက်များ (Datasets)**: Android သို့မဟုတ် iOS အက်ပလီကေးရှင်းများမှတစ်ဆင့် ပေးပို့နိုင်ပါသည်။
- **နည်းပညာစံနှုန်းများ**: [Contributing Guide](.github/CONTRIBUTING.md) နှင့် [Security Policy](.github/SECURITY.md) တို့ကို ဖတ်ရှုနိုင်ပါသည်။

> [!NOTE]
> [မွန်ဘာသာစကား](https://en.wikipedia.org/wiki/Mon_language) ကို [UNESCO ၏ ကမ္ဘာ့အန္တရာယ်ရှိဘာသာစကားများ](https://en.wikipedia.org/wiki/Atlas_of_the_World%27s_Languages_in_Danger) တွင် "ထိန်းသိမ်းရန်လိုအပ်သော" ဘာသာစကားအဖြစ် သတ်မှတ်ထားသည်။
