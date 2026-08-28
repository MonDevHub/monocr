# Building and testing the Android and iOS apps

Both mobile apps build, run their tests, and produce installable artifacts on a
standard macOS dev machine. This guide records the exact commands, because the
non-obvious part is not the build — it is that **both toolchains look absent when
they are merely mis-pathed**, and the default error messages point away from the
fix.

That happened three separate times in one day on this project: a Rust `cargo test`
was written off as "needs Xcode", Android as "no JDK available", and iOS as
"Command Line Tools only". All three were wrong, and all three were the same
mistake — trusting a toolchain's own "not found" message instead of checking
whether the thing existed somewhere else on disk. Read the troubleshooting
sections before concluding anything is missing.

---

## Android

### The one thing you must know

`JAVA_HOME` is **mandatory**, and the JDK you need is inside Android Studio:

```bash
cd apps/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```

`gradle/gradle-daemon-jvm.properties` pins the daemon to
`toolchainVendor=jetbrains`, `toolchainVersion=21`. Only the JetBrains Runtime
bundled with Android Studio satisfies both, and Gradle's auto-detection cannot
find it on its own.

An equivalent form, if you would rather not export anything:

```bash
./gradlew test -Dorg.gradle.java.installations.paths="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### Clean build and test

```bash
cd apps/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew --stop                 # a warm daemon hides configuration problems — see below
./gradlew clean                  # removes app/build (hundreds of MB)
rm -rf .gradle build .kotlin     # optional; all three are gitignored
./gradlew test                   # 22 tests, 0 failures
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk (~137 MB)
```

**Do not clear `~/.gradle/caches`** as part of a clean. It is gigabytes of resolved
dependencies (2.1 GB here), and keeping it is what lets the clean build above succeed with
`--offline`. Clearing it forces a full re-download and breaks offline builds. It
is a dependency cache, not build output.

`./gradlew clean` removes `app/build` only. `apps/android/build/` does not exist
on this checkout at all — checked after a test run, an `assembleDebug` and a
*failed* `lint`, which is exactly when Gradle would write
`build/reports/problems/`. So the `rm -rf .gradle build .kotlin` above silently
no-ops on `build`, and that is fine; it is listed for the case where a previous
tool did create one.

There is **no root-project `clean` task** — `./gradlew clean --dry-run` reports
`:app:clean SKIPPED` and nothing else, so the name resolves into `:app`.

### What the tests cover

22 unit tests in four classes, all under `app/src/test/`:

| class | tests |
|---|---|
| `CtcDecoderTest` | 5 |
| `LineSegmenterTest` | 7 |
| `LineTilerFixtureTest` | 4 |
| `SegmentationModeTest` | 6 |

`LineTilerFixtureTest` reads `shared/segmentation-fixtures/tiling-cases.json`, so
it is a cross-platform parity test, not an Android-only one.

`test` is the debug variant only. Despite the task description "Run unit tests for
all variants", no `testReleaseUnitTest` or `testStagingUnitTest` task exists.

`app/src/androidTest/` **does not exist**, so `connectedAndroidTest` has nothing to
run even with a device attached, despite `testInstrumentationRunner` being declared.

### Android troubleshooting

**`Unable to download toolchain matching the requirements ({languageVersion=21, vendor=JetBrains, ...}) from 'null', due to: No defined toolchain download url for MAC_OS on aarch64 architecture.`**

Export `JAVA_HOME` as above. Two things about this message mislead:

- `from 'null'` means "no download URL is known", not "the network failed".
- The `foojay-resolver-convention` plugin in `settings.gradle.kts` looks like it
  should supply that URL. It cannot. Daemon-JVM selection happens in the Gradle
  *client*, before `settings.gradle.kts` is evaluated, so no download repository
  is registered yet. The plugin only serves per-task toolchains, which this build
  does not use.

**`/usr/libexec/java_home -V` says "Unable to locate a Java Runtime"**

Expected, and it does **not** mean you have no JDK. `java_home` reports only
JDKs registered under `/Library/Java/JavaVirtualMachines/`, and Homebrew's is not.

Do not read that as "symlink it and you are done". The only Homebrew JDK here is
**25, vendor Homebrew**, and the pin demands version 21 *and* vendor `jetbrains`
— so it cannot satisfy the build from any location. Gradle also looks in more
places than `java_home` does (`JAVA_HOME`, `JDK_*`, the current JVM, `~/.gradle/jdks`,
SDKMAN/asdf), which is why exporting `JAVA_HOME` works at all. Ask Gradle what it
can see rather than asking `java_home`:

```bash
./gradlew javaToolchains
```

**"It built yesterday and fails today, and I changed nothing"**

The warm-daemon trap, and it is worth understanding because it will waste an hour.
An already-running daemon is matched against the pin *by its own JVM*, with no
on-disk lookup. So a build with no `JAVA_HOME` succeeds while a daemon started
earlier by a correct invocation is still alive, then fails the moment that daemon
is stopped or expires (3h idle default). Measured:

```console
$ unset JAVA_HOME; pnpm test     # idle daemon from an earlier JBR run
BUILD SUCCESSFUL in 524ms
$ ./gradlew --stop
$ unset JAVA_HOME; pnpm test
FAILURE: Unable to download toolchain matching the requirements ...
```

Export `JAVA_HOME` permanently rather than relying on this.

**`SDK location not found`**

`local.properties` is gitignored, so a fresh clone has no SDK pointer. Either
export `ANDROID_HOME=~/Library/Android/sdk` or recreate the file. Installed here:
platforms 35/36/36.1, build-tools 34.0.0/35.0.0/36.0.0/36.1.0 — `compileSdk = 36`
is satisfied. There is no `ndk/`, which nothing in the debug path needs.

**`./gradlew lint` fails with `Lint found 161 errors, 67 warnings`**

A genuine code finding, not an environment problem, and the one script in
`apps/android/package.json` that is actually broken. The first failure is
`Theme.kt:70` — `Call requires API level 31 (current min is 24):
dynamicDarkColorScheme [NewApi]`. There is no `lint { }` block, so `abortOnError`
keeps its default `true`. Fixing it means addressing the errors, generating a
baseline with `./gradlew updateLintBaseline`, or setting `lint { abortOnError =
false }` — each a deliberate decision, none taken yet. Reports are still written
to `app/build/reports/lint-results-debug.{html,txt,xml}`.

---

## iOS

There are **two separate build surfaces** and conflating them is the main source
of confusion.

| surface | what it is | needs Xcode? | covers |
|---|---|---|---|
| `MonOcrCore` | SwiftPM package over the Foundation-only files | no | 12 files, 35 tests |
| `monocr-ios` | the app target in `monocr-ios.xcodeproj` | yes | 42 files |

### Testing the core package

```bash
cd apps/ios
sh Scripts/swift-test.sh          # Test run with 35 tests in 5 suites passed
```

**Do not export `DEVELOPER_DIR` for this command** unless you also wipe
`MonOcrCore/.build` first.

The script itself is toolchain-agnostic: its candidate loop finds
`Testing.framework` under either, and from an empty `.build` it passes under both. But `.build` holds objects compiled by whichever Swift last ran, so
switching toolchains against a warm one fails at the link step:
`clang: error: linker command failed`, and the script's own guard reports "the
run reported no test count, so it ran no tests."

This guide has now had it wrong in both directions. It first said "do not export
it", an audit called that false because the script passes under either toolchain
from clean, and the correction was published without re-testing on a checkout
that had already been built. The condition is `.build`'s state, not the
script's capability.

Do not substitute a bare `swift test` — it fails with `error: no such module
'Testing'`. The wrapper exists for two reasons documented in its own header: the
Command Line Tools put `Testing.framework` where SwiftPM does not search, so it
needs `-F` and two `-rpath` flags passed **on the command line** (settings in
`Package.swift` do not reach SwiftPM's `#if canImport(Testing)`-guarded entry-point
module, and putting them there yields a green run of *zero* tests); and it
hard-fails unless the output contains `Test run with`, which is the guard against
exactly that silent-zero-test pass.

The suites are swift-testing, not XCTest: `CaptureQualityTests`, `CtcDecoderTests`,
`LineTilingTests`, `PageNormalizerTests`, `SegmentationModeTests`.

### Building the app

```bash
cd apps/ios
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

xcodebuild -project monocr-ios.xcodeproj -scheme monocr-ios -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17,arch=arm64' \
  -derivedDataPath /tmp/monocr-ios-dd \
  build
```

`DEVELOPER_DIR` is the whole trick, and it needs no `sudo`: `xcode-select -p`
points at `/Library/Developer/CommandLineTools`, so `xcodebuild` reports
`requires Xcode, but active developer directory is ...` even with Xcode fully
installed. The environment variable overrides it for one command without changing
the machine's global state, which is reason enough to prefer it — `sudo
xcode-select -s` changes the toolchain for every tool and every other project on
the machine.

A global `sudo xcode-select -s` would also make every subsequent
`Scripts/swift-test.sh` run link against a `.build` compiled by the other Swift,
which is the failure described above — so the preference for the scoped variable
is practical, not only tidy.

### Clean build

```bash
cd apps/ios
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

rm -rf MonOcrCore/.build                 # 100s of MB; see note below
rm -rf /tmp/monocr-ios-dd                # or your -derivedDataPath
```

A bare `xcodebuild clean` is **not** worth adding to that list, and
`apps/ios/package.json`'s `clean` script is exactly that. With no
`-configuration` it targets Release, and with no `-derivedDataPath` it never
looks at the directory you built into — measured, it reports `** CLEAN
SUCCEEDED **` and leaves both DerivedData and `/tmp/monocr-ios-dd` byte-for-byte
unchanged. The `rm -rf` lines do all the real work.

Prefer an explicit `-derivedDataPath` to a throwaway directory over relying on the
shared `~/Library/Developer/Xcode/DerivedData`. That directory is ~2.4 GB here and holds
three `monocr-ios-*` trees, two of them from April; DerivedData staleness is the
classic cause of iOS build failures that survive a "clean".

`swift package clean` is **not** equivalent to `rm -rf .build` — it removes build
products but leaves `.build/{artifacts,checkouts,repositories,workspace-state.json}`.
For `MonOcrCore` this rarely matters, since it has zero dependencies.

### The Swift version split — read this before switching toolchains

This machine has two Swift compilers, and **the Command Line Tools ship the newer
one**:

| toolchain | Swift |
|---|---|
| `/Applications/Xcode.app/.../XcodeDefault.xctoolchain` | 6.2.4 |
| `/Library/Developer/CommandLineTools` | **6.3.3** |

That inversion is counter-intuitive and it has a consequence: a file that compiles
under `Scripts/swift-test.sh` may fail under `xcodebuild`, on the same SDK and the
same target. `CaptureQuality.swift` did exactly that — a five-term
`Double(...)` sum inside `withUnsafeBufferPointer` type-checked instantly under
6.3.3 and failed under 6.2.4 with *"the compiler is unable to type-check this
expression in reasonable time"*, reported against the closure's opening line
rather than the sum. It is fixed by binding each conversion to an annotated local;
the comment in that file records why, so the workaround can be removed when the
toolchains converge.

If you hit the same error in new code: annotate the locals. Raising
`-solver-expression-time-threshold` does **not** help — it is not a solver
time-limit failure.

### What the 35 tests do not cover

`MonOcrCore/Sources/MonOcrCore/` is 12 relative symlinks into
`../../../monocr-ios/`. `monocr-ios/` holds 42 Swift files, so **30 are
app-target-only and exercised by nothing**, including `ImagePreprocessor.swift`,
`MonOcrEngine.swift`, `MainViewModel.swift`, `SyncService.swift`, `PdfUtil.swift`,
and every SwiftUI view.

Worse, there is **no test target in the xcodeproj at all** — `xcodebuild -list`
shows only the `monocr-ios` target, and `xcodebuild test` fails with `Scheme
monocr-ios is not currently configured for the test action`. A green
`Scripts/swift-test.sh` therefore says nothing about the 30 files above. Treat a
change to any of them as untested until the app target gains a test target.

### iOS troubleshooting

**`xcodebuild: error: tool 'xcodebuild' requires Xcode, but active developer directory '/Library/Developer/CommandLineTools' is a command line tools instance`**

Xcode is installed; the active directory just points elsewhere. Export
`DEVELOPER_DIR` as above. Do not conclude Xcode is missing without checking
`ls /Applications/Xcode.app`.

**`the compiler is unable to type-check this expression in reasonable time`**

The version split above. Break the expression into annotated locals.

**`error: Signing for "monocr-ios" requires a development team.`**

You targeted `platform=macOS`, or a physical device. Use the simulator
destination, or pass `CODE_SIGNING_ALLOWED=NO`.

**`error: unreadable input 'iPhone]'`**

The `[` and `]` in `platform=macOS,variant=Designed for [iPad,iPhone]` break
`-destination` parsing. Use a simulator destination instead.

**A clean build hangs or fails fetching dependencies**

The xcodeproj pins `onnxruntime` 1.24.2 via **SSH**
(`git@github.com:microsoft/onnxruntime-swift-package-manager.git`) in
`monocr-ios.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`, so
a truly clean build needs working GitHub SSH auth. `MonOcrCore` itself has no
dependencies and is unaffected.

---

## What `pnpm test` will not tell you

**`turbo run test` can serve a cached PASS for both mobile apps after a Kotlin or
Swift change.** `turbo.json`'s `test` and `lint` tasks declare
`inputs: ["src/**", "internal/**", "cmd/**", "*.go", "*.ts", "*.js", "*.json"]` —
no `*.kt`, no `*.swift`, no Gradle or SwiftPM paths. Measured with
`turbo run test --dry-run=json`:

| edited file | baseline hash | after a one-line edit |
|---|---|---|
| `LineSegmenter.kt` | `27cd7f0162e8b59b` | **unchanged** |
| `ImagePreprocessor.swift` | `ab667ac354a0dc7e` | **unchanged** |
| `focus-trap.ts` (control) | `137d6b371e68db4d` | **changed** |

The three baseline hashes are reproducible on an unmodified checkout. The
control's post-edit hash is not quoted, because it is a function of the edit's
content — any two people would get different values, and only the fact that it
*moved* is the evidence.

The control is what makes this conclusive: turbo's hashing works, it just cannot
see native sources. Confirmed directly in the resolved task definition — the
input set for both mobile packages comes out as literally `['package.json']`. Until the inputs are widened, **invoke Gradle and
`Scripts/swift-test.sh` directly** when you have touched Kotlin or Swift, or pass
`--force`.

Two related gaps in the same area, and one requirement that is easy to mistake
for one:

- `apps/ios/package.json` declares no `lint` script, so `pnpm lint:all` skips
  iOS entirely — as it does `@monocr/locales`. Turbo resolves both to
  `<NONEXISTENT>` and moves on; in a filtered run it warns, in a full run it does
  not. Note this is not what stops `lint:all` being useful: the Android leg is in
  the same run and fails on the 161 lint errors above.
- Root `pnpm clean` only removes `node_modules`. It touches none of
  `apps/android/app/build`, `apps/android/.gradle`,
  `apps/ios/MonOcrCore/.build`, or DerivedData — each of which reaches hundreds of
  megabytes.
- The Gradle-invoking scripts in `apps/android/package.json` — `build`, `clean`,
  `test`, `lint` — all inherit the `JAVA_HOME` requirement and none of them sets
  it. That is a choice, not a limitation: a script is a shell command, so
  `"test": "JAVA_HOME='...' ./gradlew test"` would work. An earlier version of
  this line said `package.json` *cannot* set it, which turned a decision into a
  false impossibility. (`prebuild` is `chmod +x gradlew`, not a Gradle call.)
  `lint` is the one script broken for a non-environment reason.

## What cannot be done on this machine

Only two things, and neither is a toolchain gap:

- **Physical-device iOS builds and App Store distribution** — no development team
  or signing identity is configured.
- **Android release artifacts** — `assembleRelease`/`bundleRelease` were not
  attempted. `local.properties` carries `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` but no `RELEASE_STORE_FILE`.
  `app/build.gradle.kts` already reads that key from `local.properties` and then
  the environment, falling back to a hardcoded absolute path only third — so the
  mechanism exists and only the value is unset. Set `RELEASE_STORE_FILE` and the
  build stops being machine-specific; no code change is owed. Minification plus
  `ndk.debugSymbolLevel = "FULL"` is unverified, and the SDK has no `ndk/`
  directory.
