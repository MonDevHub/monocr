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

## Model

App ပိ ဂှ် သုင်စောဲဒၟံင် model မွဲဓဝ်ရ၊ ဂှ်ဒှ် **v2** ရ-

| | |
| :--- | :--- |
| Architecture | MobileNetV3 + BiLSTM-384 + CTC |
| Parameters | ~6.6M |
| Input | Grayscale, `128px` အမြင့် |
| Charset | အက္ခရ် 315 |
| Precision | FP32 |
| ပတိတ်လဝ်ပ္ဍဲ | [`janakhpon/monocr`](https://huggingface.co/janakhpon/monocr), revision `a51be11` |

Android ကေုာံ iOS ဂှ် bundle လဝ်ရ (26.3 MB ကေုာံ 24.2 MB)။ Web app ဂှ် နူကဵု revision မပင်လဝ်ဂှ် ဒါန်လုဒ်ရ။ အသေအဓော် app နကဵုမွဲမွဲဂှ် ရံင်ကေတ်ပ္ဍဲ [apps/android](apps/android), [apps/ios](apps/ios) ကေုာံ [apps/web](apps/web) ညိ။

Model **v3.5** ဂှ် နွံပ္ဍဲ `mon_OCR` — parameters 11.5M, input 160px, အက္ခရ် 276။ ဟွံမွဲကဵုပတိတ်ဏီ၊ နူဗွဲမဏံ မွဲမွဲဟီု load ဟွံဂွံရ။ generation ၜါဂှ် ဟွံတုပ်ရေင်သကအ် ပ္ဍဲ class ဗီုပြင် ကေုာံ input အမြင့်ဂှ်ရ၊ web app ဂှ် လၟုဟ် ဟွံပတိတ်ကဵု လိခ်ဒးဟွံမွဲ — ကလေင်ငြင်ဆိုရ။ ပြံင်စဵုကဵု v3.5 ဂှ် ဟွံဒှ် ပြံင်ဖိုင်မွဲဓဝ်၊ ဒှ်ကမၠောန်မဒးဗဒှ်ကဵုတုပ်ပ္ဍဲအလုံအိုတ်ရ။

Model **v4** server ဂှ် ပ္ဍဲ 2026-08-05 နကဵု `mon_OCR` ADR-0011 တုဲ archive လဝ်ရ၊ train ဟွံတုဲဏီ။ ဟွံမင်မွဲရ။

Platform မွဲမွဲအတိုင် device latency ဂၞန် ဟွံမွဲရ။ ဂၞန်ဗီုဂှ် နွံဒၟံင်ပ္ဍဲဏံ စဵုကဵု 2026-08-15၊ ဂှ်ဒှ် architecture ခန့်မှန်းချက်ဓဝ်၊ ပ္ဍဲ hardware ဟွံဒှ်ချူလဝ်ရ။

ဟိုတ်နူ dataset အရေဝ်မန် ရှားပါးဒၟံင်ဂှ်ရ validated sample တအ် နူကဵု feedback flow တုဲ ကဵုဗဒှ် training round မဂတဝ်ရ။

---

## Platform

Model ဝွံ ဒှ်ကမၠောန် ပ္ဍဲ Web, Android ကေုာံ iOS — hardware acceleration နွံကဵု format မတူကဵုမတူဂှ်ရ-

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
