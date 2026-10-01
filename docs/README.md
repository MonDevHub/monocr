# MonOCR docs

Technical documentation for this repository. Start with the [root README](../README.md).

## Guides

- [Environment setup](guides/setup.md): toolchain versions, keys and first builds.
- [Building and testing the mobile apps](guides/mobile-build-and-test.md): the exact Android and
  iOS clean-build and test commands, why both toolchains can look absent when they are only
  mis-pathed, and what `pnpm test` caches away.

## Architecture

- [Architecture decision records](architecture/adr): the reasons behind the core technical choices,
  including the [polyglot monorepo](architecture/adr/0001-polyglot-monorepo-architecture.md), the
  [localisation bridge](architecture/adr/0002-unified-localization-bridge.md) and the
  [CLI as the batch and desktop surface](architecture/adr/0004-cli-desktop-surface.md).
- [Android architecture](architecture/platform/android.md).
- [Line segmentation parity](architecture/platform/line-segmentation-parity.md): where the four
  implementations of the segmenter disagree (web, Android, iOS, and the CLI through the
  `monocr-onnx` Rust library), and why that is recorded rather than unified.

## API

- [OpenAPI specification](api/openapi.yaml) for the feedback service.
- [ADR-0003](architecture/adr/0003-openapi-and-authenticated-docs.md): how the authenticated
  Swagger UI is served.

## Governance

- [Contributing guide](../.github/CONTRIBUTING.md): standards for features and translations.
- [Security policy](../.github/SECURITY.md): vulnerability disclosure and secret management.
