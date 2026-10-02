# MonOCR iOS

The iOS app. It reads printed Mon text from camera captures, images and PDFs on the device. It
builds from source and is not on the App Store.

The model, its accuracy figures and the project's limitations are in the
[root README](../../README.md).

## How it runs

Core ML runs the bundled `monocr.mlpackage` (46.3 MB), with all compute units requested.
Recognition is this project's own pipeline end to end; Vision does no text recognition here. Which
layers run on the Neural Engine has not been checked on a device, and the BiLSTM layers are not
expected to. No image and no recognised text leaves the device: there is no network call on the
recognition path. Contributing a sample is opt-in.

Imports are capped at 50 MiB, and PDFs on the contribute and feedback screens at 20 MiB. For larger files, use
[`monocr-cli`](../cli/README.md), which runs the same model and takes PDFs up to 500 MiB. The
`pip install monocr` on the app's docs screen is a different project,
[`janakhpon/monocr`](https://github.com/janakhpon/monocr): same model, its own segmentation, so
page-level output will not match this app line for line.

```
Image (UIImage)
  GreyImage.upright  -> orientation-corrected 8-bit grey buffer
  PageNormalizer     -> polarity + background levelling, ONCE, before segmenting
  LineSegmenter      -> blur, adaptive threshold, suppressPageRules,
                        smear, projection profile -> [LineSegment]
  LineTiler          -> split lines too wide for the window -> [LineSegment]
  ImagePreprocessor  -> scale to 160x1024 + normalize [-1.0, 1.0]
  MonOcrEngine       -> Core ML Prediction (monocr.mlpackage), [1, 1, 160, 1024]
  CtcDecoder         -> greedy CTC decode -> String
```

- **Model contract.** The engine refuses to load a model whose input height or class count
  disagrees with this build (`assertModelContract`), because that mismatch produces well-formed,
  wrong Mon text rather than an error.
- **Segmentation modes.** `.page` and `.sparse` differ only in the valley threshold they pass to
  `LineSegmenter.segment`; `.line` skips segmentation and treats the image as one band.
- **Printed rules.** `suppressPageRules` runs inside `LineSegmenter.segment`, after binarisation
  and before the smear. An unbroken run of ink spanning at least half the page is a rule
  (`ruleSpan = 0.5`, 15px floor); if clearing rules would remove more than 80% of the ink
  (`ruleMaxInkShare = 0.8`), it has found text, and the mask is left untouched.
- **Tiling.** Lines wider than the model window are cut at whitespace rather than squeezed.
  Measured on 201 rendered lines: squeezing wins at 2 tiles, level at 3, tiling wins from 4; by
  6 tiles squeezing exceeds 0.83 CER. See
  [ADR-0004](../../docs/architecture/adr/0004-cli-desktop-surface.md).
- **Polarity** is decided at page level, because the projection profile treats dark pixels as ink:
  deciding it per line made a dark-mode screenshot segment on the gaps between lines.
- **Joining.** Tiles of one line join with no separator; distinct lines join with a newline.

`monocr-ios/` is flat: every Swift file sits at its top level, beside `Assets.xcassets/`, `Fonts/`
and `monocr.mlpackage/`. The app target is a `PBXFileSystemSynchronizedRootGroup` over that
directory, so moving a file into a subfolder means editing `monocr-ios.xcodeproj`.

## Build and run

Requires Xcode 26.2 or later, and a device on iOS 26.2 or later: `project.pbxproj` sets
`IPHONEOS_DEPLOYMENT_TARGET = 26.2`.

1. Open `apps/ios/monocr-ios.xcodeproj` in Xcode.
2. Signing is Automatic with no team set, so choose your own team under Signing & Capabilities.
3. Build and run the `monocr-ios` scheme on a physical device.

The project resolves `onnxruntime-swift-package-manager` over an SSH URL
(`git@github.com:microsoft/...`), so Xcode needs GitHub SSH access to resolve packages.

The command-line build, with the `DEVELOPER_DIR` it needs on a machine whose active developer
directory is the Command Line Tools, is in
[`docs/guides/mobile-build-and-test.md`](../../docs/guides/mobile-build-and-test.md).

## Test

```bash
cd apps/ios
sh Scripts/swift-test.sh      # or: pnpm test
```

This runs `MonOcrCore`, a Swift package over the platform-free half of the app: `GreyImage`,
`PageNormalizer`, `LineSegmenter`, `LineTiler`, `CtcDecoder`, `LogitsLayout` and the small value
types. It needs a Swift 6 toolchain (`swift-tools-version: 6.0`); the Command Line Tools are
enough, and neither Xcode nor a simulator is required. Anything that imports UIKit, SwiftUI or
Core ML has no test, because running it needs a simulator.

- `MonOcrCore/Sources/MonOcrCore/` holds 15 relative symlinks into `monocr-ios/`, not copies, so
  the package tests the app's own files without a second copy that can drift. Adding a file to the
  package means adding a symlink.
- Use the script, not a bare `swift test`. It passes the `-F` and `-rpath` flags SwiftPM needs to
  find `Testing.framework` in Apple's Command Line Tools, which cannot live in `Package.swift`
  (explained at the top of that file), and it fails when a run reports no test count rather than
  trusting an exit code of 0.
- The `ld: warning: building for macOS-13.0, but linking with dylib ... built for newer version
  14.0` line on every run is `Testing.framework`'s deployment target against the package's. It
  affects nothing the app ships.

CI runs this package on every push. It does not build the app.

## Known gap: most of the app's own strings are not translated

`Localizable.xcstrings` holds 207 entries with Mon and Burmese translations, and **16 of the 24
`NSLocalizedString` literals in the Swift sources are not among them**. Those 16 fall back to their
English key, for the two languages this app exists to serve. They include every segmentation mode
label and description (`SegmentationMode.swift`), both accuracy warnings (`ResultCardView.swift`),
most of the engine's error messages (`MonOcrEngine.swift`) and the multi-page failure notice
(`MainViewModel.swift`). A further 29 of the 207 entries are not translated into both languages.

The entries need real translations, and inventing them would be worse than the gap. Re-derive the
counts before quoting them: extract the literals from `apps/ios/monocr-ios/**/*.swift` and compare
them against the catalogue's `strings` keys.

## Licence

MIT
