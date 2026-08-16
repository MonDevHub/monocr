# Security Policy

## Responsible Disclosure
If you find a security vulnerability, please report it via a private GitHub Vulnerability Report or by contacting the maintainers directly.

## Secure Configuration
- **Secrets Management**: Environment variables, including API keys and credentials, are supplied at build or run time and are not committed. The mobile clients read the feedback-service key from `BuildConfig` on Android (set in `local.properties` or the environment) and from the `SYNC_API_KEY` Info.plist key on iOS; both treat an absent key as "sync disabled" rather than an error.

  This line described an intention rather than a fact until 2026-08-16. The feedback-service key was a literal in `SyncService.swift` and `SyncWorker.kt` from 2026-04-11, in this public repository, next to the endpoint it authenticates against. It has been removed from the source, but it remains in the git history and in every build shipped in that window, so **that key must be rotated server-side** — removing it here does not un-publish it.

  A `gitleaks` job now runs on every push and scans the full history, so this is enforced rather than asserted. `apps/web/package.json` had defined a `scan:secrets` script since before the incident; nothing ran it, because the repository had no CI.
- **Service Security**: The Feedback service is protected by API key authentication. Internal documentation (Swagger) is also restricted to authenticated requests.
- **Data Protection**: Uploads are stored in Cloudflare R2 with access controls. Objects are organized using unique identifiers and date-based partitioning to prevent enumeration.

## Input Validation
Each service implements standard security checks:
- **Content Verification**: Files are verified using MIME-type sniffing (magic numbers) to ensure they match the expected format.
- **Payload Limits**: Request sizes are limited to prevent denial-of-service attacks.
- **Sanitization**: External inputs are sanitized before being used in storage operations.
