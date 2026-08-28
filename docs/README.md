# MonOCR Documentation Hub

Welcome to the central technical repository for the MonOCR platform. This hub is organized by concern to ensure technical clarity and long-term maintainability.

## Architecture

Details on the polyglot monorepo strategy and platform-specific implementations.

- **[Architecture Decision Records (ADRs)](architecture/adr)**: The "Why" behind our core technical choices.
- **[Platform Implementation Details](architecture/platform)**: Deep-dives into Android, iOS, and Web specific logic.
- **[Line segmentation parity](architecture/platform/line-segmentation-parity.md)**: Where the four surfaces of the segmenter disagree (web, Android, iOS, and the CLI through `monocr-onnx/rust`), and why it is recorded rather than unified.

## API & Contracts

Machine-readable specifications and integration guides.

- **[OpenAPI Specification](api/openapi.yaml)**: Formal contract for the mobile feedback service.
- **[Authenticated UI Guide](architecture/adr/0003-openapi-and-authenticated-docs.md)**: How to interact with the secure Swagger UI.

## Governance

Project health, security, and contribution standards.

- **[Contributing Guide](../.github/CONTRIBUTING.md)**: Standards for adding features and translations.
- **[Security Policy](../.github/SECURITY.md)**: Vulnerability disclosure and secret management.

## Getting Started

Onboarding for new developers.

- **[Environment Setup Guide](guides/setup.md)**: Dependencies, keys, and initial builds.
- **[Building and testing the mobile apps](guides/mobile-build-and-test.md)**: The exact Android and iOS clean-build and test commands, why both toolchains look absent when they are only mis-pathed, and what `pnpm test` caches away.
- **[Localization Sync Guide](architecture/adr/0002-unified-localization-bridge.md)**: Maintaining platform-parity for Mon charset translations.
