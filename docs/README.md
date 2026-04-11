# MonOCR Documentation Hub

Welcome to the central technical repository for the MonOCR platform. This hub is organized by concern to ensure technical clarity and long-term maintainability.

## Architecture
Details on the polyglot monorepo strategy and platform-specific implementations.

- **[Architecture Decision Records (ADRs)](architecture/adr)**: The "Why" behind our core technical choices.
- **[Platform Implementation Details](architecture/platform)**: Deep-dives into Android, iOS, and Web specific logic.

## API & Contracts
Machine-readable specifications and integration guides.

- **[OpenAPI Specification](api/openapi.yaml)**: Formal contract for the mobile feedback service.
- **[Authenticated UI Guide](architecture/adr/0003-openapi-and-authenticated-docs.md)**: How to interact with the secure Swagger UI.

## Governance
Project health, security, and contribution standards.

- **[Contributing Guide](../CONTRIBUTING.md)**: Standards for adding features and translations.
- **[Security Policy](../SECURITY.md)**: Vulnerability disclosure and secret management.

## Getting Started
Onboarding for new developers.

- **[Environment Setup Guide](guides/setup.md)**: Dependencies, keys, and initial builds.
- **[Localization Sync Guide](architecture/adr/0002-unified-localization-bridge.md)**: Maintaining platform-parity for Mon charset translations.
