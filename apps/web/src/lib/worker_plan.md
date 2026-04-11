# Web Worker Implementation

## Goal

Offload the heavy `ocr-engine` Wasm execution to a Web Worker to prevent UI freezing during model loading and inference.

## Changes

1.  **`src/lib/ocr.worker.ts`**:
    - Imports `init, { MonOcr }` from `ocr-engine`.
    - Handles messages: `{ type: 'init', payload: { model, charset } }` and `{ type: 'recognize', payload: imageBytes }`.
    - Posts back results or errors.

2.  **`src/lib/monocr.ts`**:
    - Refactor to instantiate `ocr.worker.ts`.
    - Expose `initializeEngine()` and `recognize(bytes)` as async methods that wrapp the worker messages in Promises (request/response pattern).

3.  **`src/routes/+page.svelte`**:
    - No major changes needed if `monocr.ts` interface remains similar (async).
    - Just need to ensure `engine.recognize()` is awaited (it already is).

## Protocol

- **Request**: `{ id: string, type: 'INIT' | 'RECOGNIZE', payload: any }`
- **Response**: `{ id: string, type: 'RESULT' | 'ERROR', payload: any }`
