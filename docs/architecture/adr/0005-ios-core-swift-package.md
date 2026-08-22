# ADR-0005: A Swift package beside the Xcode project, so iOS logic can be tested

- **Status:** Accepted
- **Date:** 2026-08-22

## Context

**No iOS test had ever run.** Not "the tests were failing" — they had never executed once.
Two facts, both verified:

- `monocr-ios.xcodeproj/project.pbxproj` declares exactly **one** native target, the app, as a
  `PBXFileSystemSynchronizedRootGroup` on `path = "monocr-ios"`. The string `monocr-iosTests`
  has never appeared in that file in its entire git history, so `CtcDecoderTests.swift` — added
  in the foundational commit — belonged to no target from the day it was written.
- `apps/ios/package.json` ran `xcodebuild test -project ... -scheme monocr-ios`, naming a scheme
  that is not checked in (`xcshareddata/xcschemes` does not exist), against that target with no
  tests. It reported success.

So the repository had a green iOS test command that executed nothing, for months. That is the
same failure shape se-brain `standards/testing.md` calls a vacuous test, one level up: not a test
that cannot fail, but a *suite* that cannot run.

Two further constraints:

- **XCTest is unavailable without Xcode.** `import XCTest` fails with "no such module" under
  Command Line Tools; the framework is simply not shipped. So both existing test files were dead
  on any CI runner and any machine without a full Xcode install, independent of the target gap.
- **`Testing.framework` *is* shipped** with Command Line Tools, along with its macro plugin.

## Decision

**`apps/ios/MonOcrCore` — a Swift package whose sources are symlinks into `monocr-ios/`, tested
with Swift Testing.**

- `Sources/MonOcrCore/` holds relative symlinks to the 11 Foundation-only files (`LineTiler`,
  `GreyImage`, `PageNormalizer`, `LineSegmenter`, `CtcDecoder`, `Logger`, `LogitsLayout`,
  `ModelWindow`, `LineSegment`, `SegmentationMode`, `EngineStatus` — 977 lines).
- `Tests/MonOcrCoreTests/` holds the two former XCTest files rewritten with `import Testing`,
  plus `PageNormalizerTests`.
- `project.pbxproj` is **not touched.**

**Symlinks rather than moving the files.** The app target is a synchronized group on the
`monocr-ios` directory: anything moved out of it disappears from the app build unless the
pbxproj also gains a local package reference, ~40 declarations become `public`, and eight
platform files gain an import. Symlinks keep the same bytes inside the synchronized group, so
the app builds identically and SwiftPM compiles the same source. `@testable import` means
nothing needs to become `public`.

**A subdirectory, not `apps/ios/Package.swift`.** A manifest at `apps/ios/` would make
`File > Open` on that folder open the package instead of the project. It also stays out of the
pnpm workspace, whose glob is `apps/*`, one level deep.

**Not `path:` + `sources:` on the app directory.** That directory holds 48 non-Swift files —
`monocr.mlpackage/`, `Assets.xcassets/`, `Fonts/`, a 110 KB `Localizable.xcstrings` — plus 31
platform Swift files. Every one would need an `exclude:` entry, updated whenever a UI file is
added. The symlink directory changes only when a *pure* file is added.

### The wrapper script is load-bearing, not convenience

`apps/ios/Scripts/swift-test.sh`, and `package.json` calls it rather than `swift test` directly.
Two reasons, the second of which is the important one:

1. Command Line Tools puts `Testing.framework` in a directory SwiftPM does not search, so the
   run needs `-F`, plus `-rpath` for that directory and for the sibling `usr/lib` holding
   `lib_TestingInterop.dylib`.
2. **Those flags cannot live in `Package.swift`.** SwiftPM generates its own entry-point module
   for the test bundle, guarded by `#if canImport(Testing)`, and target `swiftSettings` do not
   reach it. Putting the flags there produces a bundle that **builds, links, runs nothing and
   exits 0** — verified, with `nm -u` on the generated runner object showing zero Testing
   symbols. That is precisely the failure this ADR exists to end, so `Package.swift` is left
   flag-free on purpose: a bare `swift test` fails loudly with "no such module 'Testing'"
   instead of passing silently.

The script therefore also **fails the run unless the output reports a test count.** An exit code
alone is what `xcodebuild test` was already trusting.

## Consequences

**Good.** 20 tests across 3 suites now execute, including the tiling parity checks against
`shared/segmentation-fixtures/tiling-cases.json` that the iOS port shares with web, Android and
Rust — iOS was the only one of the four with a written test and no runner. A `macos-latest` CI
job runs them with no simulator, no signing and no Xcode, which is why the cost objection in the
workflow header no longer applies. The `PageNormalizer` sliding-window max filter is now pinned
against a naive implementation at four kernel sizes; it was previously unverified anywhere.

**Costs and open items.**

- **`xcodebuild test` still does nothing.** Adding a unit-test bundle target and a shared scheme
  needs a machine with Xcode and is not done. Anyone opening the project should know the Swift
  Testing suite is the real one.
- **The package covers pure logic only.** `MonOcrEngine`, `ImagePreprocessor`, the view models
  and every SwiftUI view remain untested and uncompilable outside Xcode, which is why changes to
  them in this cycle are marked read-verified rather than tested.
- **Symlinks are a build-system dependency.** A checkout on a filesystem without symlink support,
  or an archive that dereferences them, silently duplicates the sources. Worth a note if Windows
  ever enters the picture.
- `apps/ios/monocr-iosTests/` was deleted rather than left beside the new copies. Two divergent
  copies of one test is the drift the shared fixture exists to prevent.
