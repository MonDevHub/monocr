# ADR 0003: Contractual Parity and Perimeter Security

## Status
Accepted

## Context
The MonOCR feedback service exposes endpoints for mobile character contributions and error reporting. To maintain production stability, the service requires machine-readable API specifications to ensure client-server parity and mitigation of unauthorized access to linguistic asset ingestion pipelines.

## Decision
Implementation of a dual-layer documentation and security strategy:
1. **Machine-Readable Specification**: A formal `openapi.yaml` contract for design-time enforcement.
2. **Authenticated Interactive UI**: A Swagger UI served via the Go binary, restricted behind the same **X-API-Key** middleware used for production endpoints.

## Rationale
- **Contractual Parity**: Automated generation of documentation ensures that mobile clients (Android/iOS) adhere to the standardized JSON schema, reducing integration entropy.
- **Access Perimeter**: The feedback service is a restricted-access utility. X-API-Key enforcement establishes a basic security perimeter suitable for native mobile ingestion, ensuring that even documentation reflects the project's security posture.
- **Idempotency and Validation**: The specification mandates strict MIME-type and payload constraints, ensuring that only authenticated, valid linguistic assets traverse the ingestion pipeline.

## Alternatives Considered
- **OIDC/OAuth2**: Rejected as disproportionate for a specialized, community-driven ingestion utility; X-API-Key provides sufficient isolation for the current deployment phase.

## Audit Constraints
- **Key Rotation**: Infrastructure procedures must exist for secure credential rotation.
- **Schema Drift**: Any structural changes to the Go ingestion logic must be propagated atomically to the `openapi.yaml` contract.
