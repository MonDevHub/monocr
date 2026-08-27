# Environment Setup Guide

This guide ensures you have a consistent development environment for the MonOCR monorepo.

## Prerequisites

- **Node.js**: v24.0.0+ (LTS recommended)
- **pnpm**: v10.0.0+ (Workspace support required)
- **Go**: v1.23.0+ (For feedback service development)
- **Android Studio**: Latest Jellyfish+ (For Android development). Required even
  if you never open it: the Gradle build is pinned to the JetBrains Runtime 21
  bundled inside it, and no Homebrew JDK satisfies that pin.
- **Xcode**: v26.0+ (For iOS development). The `v15.0+` this line used to claim is
  understated — `MonOcrCore` needs Swift 5.9+, but the app target is built against
  the current Xcode and the two ship different Swift versions.

> [!IMPORTANT]
> Neither mobile app builds from a bare shell without one environment variable
> each — `JAVA_HOME` for Android, `DEVELOPER_DIR` for iOS — and the errors you get
> without them both read as "the toolchain is not installed" when it is.
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
> The hangar will automatically validate your `.env` during `pnpm dev` or `pnpm build`. Missing keys will result in a graceful exit with a descriptive error.

## Common Commands

| Command | Action |
| :--- | :--- |
| `pnpm dev:all` | Starts Web, Android, and Feedback services in parallel. |
| `pnpm build` | Builds all production-ready artifacts across all engines. |
| `pnpm translate` | Synchronizes the latest locals from Google Sheets. |
| `pnpm lint:all` | Executes a unified lint pass across all languages. |
