import { MonOcrOnnx } from './monocr-onnx';

/** Minimal shape of what the worker posts back. Exported for the tests. */
export interface WorkerHost {
	postMessage: (msg: { id: string; type: string; payload: unknown }) => void;
}

let engine: MonOcrOnnx | null = null;

/**
 * The init already running, if any.
 *
 * Tracking only the finished `engine` is not enough. It is null for the whole
 * download, so a second INIT arriving mid-flight passed the `if (!engine)` guard
 * and started a second MonOcrOnnx — two concurrent 46 MB fetches on one worker,
 * double the peak memory, both racing to write the same cache key.
 *
 * That is reachable: the client rejects on INIT_TIMEOUT_MS and clears its own
 * promise, but nothing cancels the fetch already in the worker, so Retry posts a
 * fresh INIT into a worker that never stopped. Holding the promise makes a retry
 * join the download in progress instead of starting a rival one, which is also
 * why no AbortController is needed — the bytes are already arriving.
 */
let initInFlight: Promise<MonOcrOnnx> | null = null;

/**
 * Handle one message. Separated from `self.onmessage` so it can be imported
 * under vitest's node environment, where `self` does not exist.
 */
export async function handleMessage(
	host: WorkerHost,
	data: { id: string; type: string; payload: never }
): Promise<void> {
	const { id, type, payload } = data;

	try {
		switch (type) {
			case 'INIT': {
				if (!engine) {
					if (!initInFlight) {
						const { modelPath, charsetPath } = payload as unknown as {
							modelPath: string;
							charsetPath: string;
						};
						initInFlight = (async () => {
							const candidate = new MonOcrOnnx();
							await candidate.initialize(modelPath, charsetPath, (received, total) => {
								host.postMessage({ id, type: 'PROGRESS', payload: { received, total } });
							});
							return candidate;
						})();
						// Clear on failure so a genuine error stays retryable, and do it
						// without marking the promise handled — the await below still sees
						// the rejection and reports it under this message's id.
						initInFlight.catch(() => {
							initInFlight = null;
						});
					}

					// Assign only once initialize() resolves. Assigning first left a
					// non-null engine behind after a failure, so the next INIT
					// short-circuited and reported 'Initialized' for a session that was
					// never created; the UI went ready and the first scan died on
					// "Model not initialized".
					engine = await initInFlight;
					initInFlight = null;
				}
				host.postMessage({ id, type: 'RESULT', payload: 'Initialized' });
				break;
			}

			case 'RECOGNIZE': {
				if (!engine) throw new Error('Engine not initialized');
				const imageBytes = payload as Uint8Array;
				const text = await engine.recognize(imageBytes);
				host.postMessage({ id, type: 'RESULT', payload: text });
				break;
			}

			default:
				throw new Error(`Unknown message type: ${type}`);
		}
	} catch (err: unknown) {
		const errorMessage = err instanceof Error ? err.message : String(err);
		host.postMessage({ id, type: 'ERROR', payload: errorMessage });
	}
}

/** Test seam: reset module state between cases. */
export function resetForTests(): void {
	engine = null;
	initInFlight = null;
}

if (typeof self !== 'undefined' && 'onmessage' in self) {
	self.onmessage = (e: MessageEvent) => handleMessage(self as WorkerHost, e.data);
}
