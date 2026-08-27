# ADR 0002: Unified Localization Bridge

## Status
Accepted

## Context
MonOCR targets three distinct delivery environments with heterogeneous localization requirements:
- Web: JSON-based key-value pairs.
- Android: XML resources (`strings.xml`).
- iOS: Property-list strings (`.xcstrings`).

Maintaining linguistic parity for the Mon charset across three toolchains introduces high entropy and synchronization risks.

## Decision
Implementation of a unified Node.js synchronization engine (`shared/locales/sync.mjs`) to manage linguistic assets from a centralized source of truth.

## Rationale
- **Deterministic Parity**: Guarantees that every character mapping and UI label is identical across all execution environments.
- **Linguistic Accessibility**: Decouples the technical build process from linguistic asset management, allowing domain experts to audit the strings via the Google Sheets API without modifying source code.
- **Native Efficiency**: Applications consume standard native formats; the bridge does not introduce runtime overhead or non-native abstractions.

## Alternatives Considered
- **Commercial Localization SaaS**: Rejected due to cost constraints and lack of specialized support for endangered Mon-Burmese Unicode character sets.
- **JSON-to-Native Pre-processors**: Rejected for failing to account for platform-specific XML/Plist meta-labels required for system-level localization.

## Linguistic Constraints
- **Charset Cardinality**: The Mon-Burmese script requires precise Unicode rendering; the sync engine must audit character sanitization (e.g., escape sequences) for Android and iOS compatibility.
- **Asset Integrity**: Built-time dependency on the Google Sheets API requires deterministic fallback mechanisms (local caching).

---

## Correction, 2026-08-27: the character count here was the model's, not the script's

The Context above read "the 315+ character Mon charset". 315 was the output alphabet of the v2
recogniser, current when this ADR was written and superseded by v3.5's 276 — it was never a count of
the Mon script, and it was never a count this bridge had any say over. The number is removed rather
than updated, because restating any count here would only give it a second place to go stale: the
sync engine reads a `translations` sheet and writes UI strings, and touches no charset asset. The
Rationale's "audit the charset" is corrected to "audit the strings" for the same reason. Charset
parity across the three apps is held by CI instead — `model-consistency` / "The three bundled
charsets are byte-identical" — which compares the files rather than a sentence about them.

The decision this ADR records is unchanged.
