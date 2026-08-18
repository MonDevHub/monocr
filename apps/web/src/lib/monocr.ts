import OcrWorker from './ocr.worker?worker';
import { CONFIG, resolveRecognitionModel } from './config';

// Types
type WorkerMessageType = 'INIT' | 'RECOGNIZE';
type WorkerResponseType = 'RESULT' | 'ERROR' | 'PROGRESS';

interface WorkerMessage {
	id: string;
	type: WorkerMessageType;
	payload: unknown;
}

interface WorkerResponse {
	id: string;
	type: WorkerResponseType;
	payload: unknown;
}

export class OcrError extends Error {
	constructor(
		message: string,
		public code:
			| 'INIT_FAILED'
			| 'MODEL_LOAD_FAILED'
			| 'RECOGNITION_FAILED'
			| 'TIMEOUT'
			| 'Worker_ERROR'
			| 'RECOGNIZE_FAILED',
		public originalError?: unknown
	) {
		super(message);
		this.name = 'OcrError';
	}
}

let worker: Worker | null = null;
let initPromise: Promise<void> | null = null;
let idleTimeoutTimer: ReturnType<typeof setTimeout> | null = null;
const WORKER_IDLE_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

/** Model-download progress subscribers. See `onModelProgress`. */
type ProgressListener = (received: number, total: number) => void;
const progressListeners = new Set<ProgressListener>();

/**
 * Observe the one-time model download. Returns an unsubscribe function.
 *
 * `total` is 0 when the server sends no `content-length`, so callers must treat
 * it as unknown rather than dividing by it.
 */
export function onModelProgress(fn: ProgressListener): () => void {
	progressListeners.add(fn);
	return () => progressListeners.delete(fn);
}

// Map to store pending request resolvers
const pending = new Map<
	string,
	{ resolve: (val: unknown) => void; reject: (err: Error) => void }
>();

function clearIdleTimeout(): void {
	if (idleTimeoutTimer) {
		clearTimeout(idleTimeoutTimer);
		idleTimeoutTimer = null;
	}
}

function resetIdleTimeout(): void {
	clearIdleTimeout();
	idleTimeoutTimer = setTimeout(() => {
		console.log(`[OCR] Worker idle for ${WORKER_IDLE_TIMEOUT_MS}ms. Terminating to save memory.`);
		cleanup();
	}, WORKER_IDLE_TIMEOUT_MS);
}

function getWorker(): Worker {
	if (!worker) {
		worker = new OcrWorker();
		worker.onmessage = (e: MessageEvent<WorkerResponse>) => {
			const { id, type, payload } = e.data;

			// PROGRESS is not a completion. It must not resolve the request or delete
			// it from `pending`, but it must reset the idle timer: without that, a
			// download longer than five minutes is killed mid-flight by the very
			// timer meant to reclaim an *idle* worker.
			if (type === 'PROGRESS') {
				const { received, total } = payload as { received: number; total: number };
				progressListeners.forEach((fn) => fn(received, total));
				resetIdleTimeout();
				return;
			}

			if (pending.has(id)) {
				const { resolve, reject } = pending.get(id)!;
				pending.delete(id);
				if (type === 'ERROR') {
					reject(new OcrError(payload as string, 'Worker_ERROR'));
				} else {
					resolve(payload);
				}
			}
			resetIdleTimeout(); // Reset timer on successful worker response
		};
		worker.onerror = (e) => {
			console.error('Worker error:', e);
			for (const { reject } of pending.values()) {
				reject(new OcrError('Worker terminated unexpectedly', 'Worker_ERROR'));
			}
			pending.clear();
			if (worker) {
				worker.terminate();
				worker = null;
			}
			initPromise = null;
			clearIdleTimeout();
		};
		resetIdleTimeout(); // Start timer when worker spins up
	}
	return worker;
}

export function cleanup(): void {
	if (worker) {
		worker.terminate();
		worker = null;
	}
	initPromise = null;
	clearIdleTimeout();

	// Reject all pending
	for (const { reject } of pending.values()) {
		reject(new OcrError('Cleanup called', 'Worker_ERROR'));
	}
	pending.clear();
}

// Add cleanup on unload
if (typeof window !== 'undefined') {
	window.addEventListener('beforeunload', () => cleanup());
}

// Update request signature to accept transferables
function request<T>(
	type: WorkerMessageType,
	payload: unknown,
	transferables: Transferable[] = [],
	// Annotated `number`, not inferred: CONFIG is `as const`, so inferring from the
	// default narrows the parameter to the literal 60000 and rejects every other
	// timeout — including INIT's.
	timeoutMs: number = CONFIG.WORKER.RECOGNIZE_TIMEOUT_MS
): Promise<T> {
	const id = crypto.randomUUID();
	const w = getWorker();

	resetIdleTimeout(); // Reset timer when a new request is made

	return new Promise<T>((resolve, reject) => {
		const timer = setTimeout(() => {
			if (pending.has(id)) {
				pending.delete(id);
				reject(new OcrError('Request timed out', 'TIMEOUT'));
			}
		}, timeoutMs);

		pending.set(id, {
			resolve: (val) => {
				clearTimeout(timer);
				resolve(val as T);
			},
			reject: (err) => {
				clearTimeout(timer);
				reject(err);
			}
		});

		const msg: WorkerMessage = { id, type, payload };
		w.postMessage(msg, transferables);
	});
}

export async function initializeEngine(): Promise<void> {
	if (initPromise) {
		resetIdleTimeout();
		return initPromise;
	}

	initPromise = (async () => {
		// Initializing ONNX Runtime Worker
		try {
			// Pass model paths to worker (worker will fetch them)
			await request(
				'INIT',
				{
					modelPath: await resolveRecognitionModel(),
					charsetPath: CONFIG.MODELS.CHARSET
				},
				[],
				CONFIG.WORKER.INIT_TIMEOUT_MS
			);
			// Worker initialized
		} catch (e: unknown) {
			initPromise = null; // Allow retry
			const error =
				e instanceof OcrError ? e : new OcrError(`Initialization failed: ${e}`, 'INIT_FAILED', e);
			throw error;
		}
	})();

	return initPromise;
}

export async function recognize(imageBytes: Uint8Array): Promise<string> {
	try {
		await initializeEngine();
		// Senior tip: Use transferables to avoid copying large image buffers
		return await request<string>('RECOGNIZE', imageBytes, [imageBytes.buffer]);
	} catch (e: unknown) {
		const error =
			e instanceof OcrError ? e : new OcrError(`Recognition failed: ${e}`, 'RECOGNIZE_FAILED', e);
		throw error;
	}
}
