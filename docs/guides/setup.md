# Environment Setup Guide

This guide ensures you have a consistent development environment for the MonOCR monorepo.

## Prerequisites

- **Node.js**: **v22+**, per `apps/web/.nvmrc`, which is also what CI installs
  (`node-version-file: apps/web/.nvmrc`). This line said `v24.0.0+`, which
  contradicted the pin and would have told anyone on the version CI itself
  uses that they were unsupported.
- **pnpm**: v10+ (`packageManager: pnpm@10.25.0` in the root `package.json`)
- **Go**: **v1.26+** (For feedback service development). `services/feedback/go.mod`
  declares `go 1.26`; this line said `v1.23.0+`, which will not build it.
- **Android Studio**: any release that bundles **JetBrains Runtime 21**
  (2025.3 is known-good). Required even if you never open it: the Gradle build
  pins `toolchainVendor=jetbrains`, `toolchainVersion=21`, and the JBR inside the
  app bundle is the only JDK on a normal macOS machine that satisfies it — no
  Homebrew JDK does, at any version. This line named a 2023 codename, which
  predates the pin.
- **Xcode**: **v26.2+** for the app target, **v16+** for `MonOcrCore` alone.
  Both numbers come from the project rather than from whatever is installed:
  `monocr-ios.xcodeproj` sets `IPHONEOS_DEPLOYMENT_TARGET = 26.2`, which needs the
  iOS 26.2 SDK to build at all, and `MonOcrCore/Package.swift:1` declares
  `swift-tools-version: 6.0`, which Xcode 16 was the first to ship. This line
  previously said `v15.0+`, which no longer builds either target.

> [!IMPORTANT]
> Neither mobile app builds from a bare shell without one environment variable
> each: `JAVA_HOME` for Android, `DEVELOPER_DIR` for iOS. Without them, both errors
> read as "the toolchain is not installed" when it is.
> See **[Building and testing the mobile apps](mobile-build-and-test.md)**.

## Installation

1. Cloned the repository.
2. Run the shared installation command from the root:
   ```bash
   pnpm install
   ```

## Environment Configuration

1. Copy the template from the root:
   ```bash
   cp .env.example .env
   ```
2. Fill in the required keys for R2, Google Sheets, and API Authentication.

> [!IMPORTANT]
> `scripts/validate-env.mjs` runs before `pnpm dev` and `pnpm build` and **reports** missing keys without stopping the run — see the `strict` flag at `validate-env.mjs:31`, which only `--strict` sets and which neither `predev` nor `prebuild` passes. This line previously said "graceful exit", which described the `--strict` path that development never takes.

## Common Commands

| Command | Action |
| :--- | :--- |
| `pnpm dev:all` | Starts Web, CLI and Feedback in parallel. **Not Android or iOS** — neither declares a `dev` script, so `turbo run dev --parallel` resolves them to `<NONEXISTENT>` and skips them. This row used to name Android. |
| `pnpm build` | Builds every package. For the mobile apps this is **Debug**, not production (`./gradlew assembleDebug` and `xcodebuild -configuration Debug`), and both need the environment variables above. |
| `pnpm translate` | Synchronizes the latest locals from Google Sheets. |
| `pnpm lint:all` | Lints Web, CLI, Android and Feedback. **Not iOS** — `@monocr/ios` declares no `lint` script (nor does `@monocr/locales`). Note the Android leg (`./gradlew lint`) currently fails on 161 pre-existing lint errors, so this task does not pass today. |
