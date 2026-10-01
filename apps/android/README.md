# MonOCR Android

The Android app. It reads printed Mon text from camera captures, images and PDFs on the device. It
builds from source and is not on Google Play.

The model, its accuracy figures and the project's limitations are in the
[root README](../../README.md).

## How it runs

ONNX Runtime runs the bundled `assets/monocr.onnx` (46.2 MB), requesting NNAPI and falling back to
the CPU where NNAPI is unavailable. Which layers NNAPI actually takes has not been checked on a
device, and the BiLSTM layers are not expected to run on it. No image and no recognised text leaves
the device: there is no network call on the recognition path. Contributing a sample is opt-in.

Imports are capped at 50 MB, and the contribute and sync path at 20 MiB
(`SyncPolicy.MAX_REQUEST_BODY_BYTES`, matching the feedback service). For larger files, use
[`monocr-cli`](../cli/README.md), which runs the same model with no size cap. The
`pip install monocr` on the app's docs screen is a different project,
[`janakhpon/monocr`](https://github.com/janakhpon/monocr): same model, its own segmentation, so
page-level output will not match this app line for line.

```
Image (Bitmap)
  GreyImage.fromArgbInPlace -> BT.601 luma, in place -> GreyImage
  PageNormalizer.normalize  -> polarity + background levelling, ONCE, before segmenting
  LineSegmenter.segment     -> blur, adaptive threshold, suppressPageRules,
                               smear, projection profile -> List<LineSegment>
  LineTiler.tileSegment     -> split lines too wide for the window -> List<LineSegment>
  ImagePreprocessor         -> crop + scale to 160x1024 + normalize [-1.0, 1.0]
  MonOcrEngine              -> ONNX Runtime Session (monocr.onnx), [1, 1, 160, 1024]
  CtcDecoder                -> greedy CTC decode -> String
```

- **Segmentation modes.** `PAGE` and `SPARSE` differ only in the valley threshold they pass to
  `LineSegmenter.segment`, so dense scans and wide-spaced photos can each be read. `LINE` skips
  segmentation and treats the image as one band. Everything else is identical in all three.
- **Printed rules.** `suppressPageRules` runs inside `LineSegmenter.segment`, after binarisation
  and before the smear. An unbroken run of ink spanning at least half the page is a rule
  (`RULE_SPAN = 0.5`, 15px floor); if clearing rules would remove more than 80% of the ink
  (`RULE_MAX_INK_SHARE = 0.8`), it has found text, and the mask is left untouched.
- **Polarity** is decided at page level, because the projection profile treats dark pixels as ink:
  deciding it per line made a dark-mode screenshot segment on the gaps between lines.
- **Joining.** Tiles of one line join with no separator; distinct lines join with a newline.

The engine is in `app/src/main/java/dev/janakhpon/monocr/engine/`; `ui/` holds the Compose screens
and view models, `data/` persistence, and `app/src/main/assets/` the model and charset.

## Build and run

Requires Android Studio with its bundled **JetBrains Runtime 21**, and Android SDK 36
(`compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`). `JAVA_HOME` must point at that runtime even
if you never open Android Studio: `gradle/gradle-daemon-jvm.properties` pins
`toolchainVendor=jetbrains`, `toolchainVersion=21`, which no generic JDK satisfies.

```bash
cd apps/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

Or open `apps/android` in Android Studio and run the `app` module. Use a physical device to test
NNAPI; it is not available on every device or emulator, and the app falls back to the CPU.

## Test

```bash
./gradlew testDebugUnitTest
```

106 tests across eleven classes. Without `JAVA_HOME` set as above, Gradle fails at configuration
time with "Unable to download toolchain": that is a lookup failure, not a missing runtime.

CI runs this suite on every push and fails unless at least 106 tests passed. It does not build the
app, and there are no instrumented tests. Twelve of the tests check `LineSegmenter`, `LineTiler`
and `PageNormalizer` against the shared fixtures in `shared/segmentation-fixtures/`, the only
automated check that this port still agrees with web and iOS. Run the suite before touching a
decoder, the segmenter or the normaliser.

Clean builds and troubleshooting: [`docs/guides/mobile-build-and-test.md`](../../docs/guides/mobile-build-and-test.md).

## Licence

[MIT](LICENSE)
