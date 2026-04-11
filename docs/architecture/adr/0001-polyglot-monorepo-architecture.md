# ADR 0001: Polyglot Monorepo Architecture

## Status
Accepted

## Context
The MonOCR platform implements a cross-functional strategy involving:
- Client-side Web (Wasm/ONNX)
- Native Android (Kotlin/ONNX)
- Native iOS (Swift/CoreML)
- Ingestion Services (Go)

These components maintain a shared dependency on the mathematical model architecture, linguistic mapping, and infrastructure credentials.

## Decision
Adoption of a polyglot monorepo orchestrated via `pnpm` and `Turbo`.

## Rationale
- **Inference Parity via Logic Extraction**: While the mathematical weights source is unified, the monorepo enables the management of platform-specific binary serialization (ONNX/CoreML) to ensure hardware-accelerated execution on both NNAPI and the Apple Neural Engine.
- **Asset Atomicity**: Ensures that model updates and linguistic parity are synchronized in a single atomic commit across all delivery targets.
- **Execution Orchestration**: `Turbo` provides a standardized interface for heterogeneous build pipelines without requiring platform-specific toolchain modifications.
- **Repository Discoverability**: Consolidates technical documentation and engineering standards to ensure high metadata density for contributors.

## Alternatives Considered
- **Multi-repo with Submodules**: Rejected due to high overhead in cross-platform asset synchronization and the risk of "Submodule Hell" during rapid model versioning.
- **Language-Specific Monorepos**: Rejected for failing to address the primary requirement of cross-platform parity for Mon script assets.

## Scientific Constraints
- **Payload Density**: Repo size metrics must be monitored as binary ONNX assets evolve.
- **CI/CD Filtering**: Requires precise workspace filtering to prevent redundant build cycles for platform-independent changes.
