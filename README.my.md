# မွန် OCR (MonOCR)

![MonOCR Feature Graphic](assets/ocr_feature_graphic.jpg)

[English](README.md) | [မြန်မာဘာသာ](README.my.md) | [ဘာသာမန်](README.mnw.md)

---

မွန်ဘာသာစကားကို လူဦးရေ တစ်သန်းခန့်က မြန်မာနိုင်ငံနှင့် ထိုင်းနိုင်ငံတို့တွင် ပြောဆိုသုံးစွဲသည်။ [UNESCO မှ ထိန်းသိမ်းရန်လိုအပ်သောဘာသာ](https://en.wikipedia.org/wiki/Atlas_of_the_World%27s_Languages_in_Danger) အဖြစ် သတ်မှတ်ထားပြီး ဤပရောဂျက်မတိုင်မီ မွန်ဘာသာစကားအတွက် OCR ကိရိယာဆောင်ရွက်မှုမရှိသေးပါ။

MonOCR သည် မွန်အက္ခရာ ပုံရိပ်ကိုယူ၍ စာသားထုတ်ပေးသည်။ Web၊ Android နှင့် iOS တို့တွင် အလုပ်လုပ်သည် — အပြည့်အဝ offline၊ ဒေတာသည် စက်မှထွက်ခွာမည်မဟုတ်ပါ။

မွန်ဖွံ့ဖြိုးရေး လူ့အဖွဲ့အစည်းမှ တည်ဆောက်ထိန်းသိမ်းသည်။

---

## Live

- **Web**: [ocr.mondevhub.com](https://ocr.mondevhub.com)
- **Android**: Google Play
- **iOS**: App Store *(ပြန်လည်စစ်ဆေးဆဲ)*

---

## Model

App သုံးခုစလုံးသည် model တစ်ခုတည်းကို သုံးသည်၊ ၎င်းမှာ **v3.5** ဖြစ်သည်-

| | |
| :--- | :--- |
| Architecture | MobileNetV3-Large + SE + 2×BiLSTM-512 + attention + CTC |
| Parameters | 11.55M |
| Input | Grayscale, `160px` အမြင့် |
| Charset | စာလုံး 276 လုံး |
| Precision | FP32 |
| ထုတ်ဝေထားရာ | [`janakhpon/monocr`](https://huggingface.co/janakhpon/monocr), revision `d3d9d5e` |

Android နှင့် iOS သည် ၎င်းကို bundle လုပ်ထားသည် (46.2 MB နှင့် 46.3 MB အသီးသီး)။ Web app သည် pin ထားသော revision မှ ဒေါင်းလုဒ်လုပ်သည်။ app အလိုက်အသေးစိတ်ကို [apps/android](apps/android)၊ [apps/ios](apps/ios) နှင့် [apps/web](apps/web) တွင် ကြည့်ပါ။

**v3.5 သည် v2 ၏ ဗားရှင်းအသစ်မဟုတ်ဘဲ contract အသစ်တစ်မျိုးဖြစ်သည်။** input အမြင့်သည် 128 မှ 160 သို့၊ output class အရေအတွက်သည် 316 မှ 277 သို့၊ စာလုံးအရေအတွက်သည် 315 မှ 276 သို့ ပြောင်းလဲပြီး graph ၏ width axis သည် dynamic မှ static 1024 သို့ ပြောင်းသွားသည်။ cache ထဲတွင် v2 artifact ကျန်နေသေးပါက ထိုကဲ့သို့ မကိုက်ညီမှုမျိုးသည် ပုံစံမှန်သော်လည်း မှားယွင်းနေသည့် မွန်စာသားကို ပြန်ပေးသောကြောင့် ၎င်းကို decode မလုပ်ဘဲ ငြင်းဆိုသည်။ **v2** ကို ၎င်းအပေါ် pin ထားသူများအတွက် revision `a51be11` တွင် ဆက်လက်ဝန်ဆောင်မှုပေးထားဆဲဖြစ်သည်။

**v4** server model ကို 2026-08-05 တွင် `mon_OCR` ADR-0011 အရ archive လုပ်ခဲ့သည်။ ၎င်းကို convergence အထိ လေ့ကျင့်ခဲ့ခြင်းမရှိသဖြင့် archive လုပ်ခြင်းမှာ အရည်အသွေးတိုင်းတာမှုအရမဟုတ်ဘဲ ဒုတိယလမ်းကြောင်းကို ဆက်လက်ထိန်းသိမ်းမည်/မထိန်းသိမ်းဆိုသည့် ဆုံးဖြတ်ချက်ဖြစ်သည်။ ၎င်းကို ထိန်းသိမ်းထားခြင်းမရှိပါ။

မည်သည့် platform အတွက်မျှ device latency ကိန်းဂဏန်း မရှိပါ။ ထိုကဲ့သို့ ကိန်းဂဏန်းများသည် 2026-08-15 အထိ ဤနေရာတွင် ပါရှိခဲ့ပြီး၊ architecture ခန့်မှန်းချက်များသာဖြစ်ကာ hardware ပေါ်တွင် တိုင်းတာခဲ့ခြင်း မရှိပါ။

မွန်ဘာသာ dataset အရည်အသွေးမြင့်များ ရှားပါးသောကြောင့် application ၏ feedback flow မှ validated sample များသည် နောင်လေ့ကျင့်ရေးဆောင်ရွက်မှုများထဲသို့ တိုက်ရိုက်ဝင်ရောက်သည်။

---

## Platform

Model သည် Web၊ Android နှင့် iOS တို့သို့ တပ်ဆင်သည် — hardware acceleration ဖွင့်ပေးသောပုံစံကို အသီးသီးအသုံးပြုသည်-

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

- **[HuggingFace](https://huggingface.co/janakhpon/monocr)** — ONNX, CoreML နှင့် checkpoint ဖိုင်များ
- **[npm package](https://www.npmjs.com/package/monocr)** — JavaScript SDK
- **[Architecture decisions](docs/architecture/adr)** — ADRs
- **[API specs](docs/api)** — OpenAPI contracts
- **[Mon Corpus Collection](https://github.com/MonDevHub/MonCorpusCollection)** — training dataset

---

## Contributing

- **Bugs**: [GitHub Issues](https://github.com/MonDevHub/monocr/issues)
- **ဘာသာပြန်ချက်များ**: [Shared translation sheet](https://docs.google.com/spreadsheets/d/1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ/edit?usp=sharing)
- **Script samples**: Android သို့မဟုတ် iOS app မှတစ်ဆင့် ပါဝင်ကူညီပါ သို့မဟုတ် တိုက်ရိုက်ဆက်သွယ်ပါ
- **Standards**: [Contributing Guide](.github/CONTRIBUTING.md) · [Security Policy](.github/SECURITY.md)

[Janakh Pon](https://github.com/janakhpon) · [Oung Seik Nyan](https://github.com/Oungseik) · [Rajel Da Key](https://www.facebook.com/RJOMDK10) · [MonDevHub](https://github.com/MonDevHub)
