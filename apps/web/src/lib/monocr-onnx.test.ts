import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { beforeEach, describe, expect, it, vi } from 'vitest';

// onnxruntime-web is imported at module scope and pulls in WASM binaries. None of
// it is exercised here: these tests drive the contract check against a stubbed
// session, which is the only way to hold a v3.5 graph next to a v2 charset
// without shipping a 46 MB fixture.
const createSession = vi.fn();
vi.mock('onnxruntime-web', () => ({
	env: { wasm: {} },
	Tensor: class {
		constructor(
			public type: string,
			public data: unknown,
			public dims: number[]
		) {}
	},
	InferenceSession: {
		create: (...args: unknown[]) => createSession(...args)
	}
}));

const { MonOcrOnnx, ModelContractError } = await import('./monocr-onnx');
const { CONFIG, resolveRecognitionModel } = await import('./config');

/**
 * The two generations, as measured from the real artifacts.
 *
 * v3.5 is published at revision d3d9d5e and is what apps/web/static/charset.txt
 * decodes. v2 is the previous network, still served at a51be11 and still what
 * an un-updated cache may hold — which is why it must be refused rather than
 * quietly accepted.
 */
const V2 = { height: 128, classes: 316 };
const V35 = { height: 160, classes: 277 };

/**
 * The charset is read off the shipped file rather than hardcoded. A test that
 * duplicates the count cannot notice the file changing underneath it, which is
 * the exact drift this whole check exists to catch.
 */
const CHARSET = readFileSync(
	fileURLToPath(new URL('../../static/charset.txt', import.meta.url)),
	'utf-8'
).replace(/[\r\n]+$/, '');

function tensorMeta(name: string, shape: (number | string)[]) {
	return { name, isTensor: true as const, type: 'float32' as const, shape };
}

function fakeSession(height: number | string, classes: number | string) {
	return {
		inputNames: ['input'],
		inputMetadata: [tensorMeta('input', ['batch', 1, height, 1024])],
		outputMetadata: [tensorMeta('logits', ['batch', 'time', classes])],
		run: vi.fn().mockResolvedValue({})
	};
}

type Internals = {
	charset: string;
	session: unknown;
	TARGET_HEIGHT: number;
	assertModelContract(): void;
	decodePredictions(logits: Float32Array, shape: number[]): string;
};

function engineWith(charset: string, height: number | string, classes: number | string) {
	const engine = new MonOcrOnnx();
	// The private fields are the subject. Reaching them directly beats
	// reconstructing the load path, which would need a real ONNX buffer.
	const internals = engine as unknown as Internals;
	internals.charset = charset;
	internals.session = fakeSession(height, classes);
	return internals;
}

/** Feed one class index per timestep, as argmax-able logits. */
function logitsFor(indices: number[], numClasses: number) {
	const out = new Float32Array(indices.length * numClasses);
	indices.forEach((idx, t) => {
		out[t * numClasses + idx] = 1;
	});
	return out;
}

// `initialize` guards its cache eviction with `'caches' in self`, a browser
// global. Point `self` at the node global without defining `caches`, so the
// guard evaluates false and the load path runs with the network fetch stubbed.
vi.stubGlobal('self', globalThis);

beforeEach(() => {
	createSession.mockReset();
	vi.restoreAllMocks();
});

describe('the shipped charset', () => {
	it('is the 276-character v3.5 alphabet the pinned model decodes', () => {
		// If this fails, static/charset.txt changed and every number below is stale.
		expect(CHARSET.length).toBe(276);
	});

	it('has no trailing newline, so the strip on load is a no-op today', () => {
		expect(CHARSET).toBe(CHARSET.replace(/[\r\n]+$/, ''));
	});
});

describe('the model/charset contract', () => {
	it('accepts the published v3.5 model against the shipped charset', () => {
		const engine = engineWith(CHARSET, V35.height, V35.classes);
		expect(() => engine.assertModelContract()).not.toThrow();
	});

	it('refuses a stale v2 model, naming both heights', () => {
		const engine = engineWith(CHARSET, V2.height, V2.classes);

		// Height is checked first: it is the one that would otherwise surface as an
		// opaque ORT shape error during warmup. A cached v2 artifact against the
		// v3.5 charset is the realistic way this happens.
		expect(() => engine.assertModelContract()).toThrow(ModelContractError);
		expect(() => engine.assertModelContract()).toThrow(/128px/);
		expect(() => engine.assertModelContract()).toThrow(/160px/);
	});

	it('refuses on class count alone, when the heights happen to agree', () => {
		const engine = engineWith(CHARSET, V35.height, V2.classes);

		expect(() => engine.assertModelContract()).toThrow(ModelContractError);
		expect(() => engine.assertModelContract()).toThrow(/emits 316 classes/);
		expect(() => engine.assertModelContract()).toThrow(/charset has 276/);
	});

	it('warns rather than passing silently when a dimension is symbolic', () => {
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
		const engine = engineWith(CHARSET, 'height', 'classes');

		expect(() => engine.assertModelContract()).not.toThrow();

		// An unverifiable contract is not a verified one. Silence here is the
		// failure mode; the warning is the assertion.
		const said = warn.mock.calls.map((c) => String(c[0])).join('\n');
		expect(said).toMatch(/input height is symbolic/);
		expect(said).toMatch(/output class axis is symbolic/);
	});
});

describe('initialize', () => {
	/** Drive the real load path with a stubbed ORT session. */
	async function initWith(sessions: unknown[]) {
		const engine = new MonOcrOnnx();
		vi.spyOn(
			engine as unknown as { fetchAsset(u: string): Promise<Uint8Array> },
			'fetchAsset'
		).mockImplementation(async (url: string) =>
			url.endsWith('charset.txt') ? new TextEncoder().encode(CHARSET) : new Uint8Array([1, 2, 3])
		);
		sessions.forEach((s) =>
			s instanceof Error
				? createSession.mockRejectedValueOnce(s)
				: createSession.mockResolvedValueOnce(s)
		);
		await engine.initialize('/model.onnx', '/charset.txt');
		return engine;
	}

	it('checks the contract on the primary session', async () => {
		await expect(initWith([fakeSession(V2.height, V2.classes)])).rejects.toThrow(
			ModelContractError
		);
		// One create, not two: the mismatch must not be retried as an EP problem.
		expect(createSession).toHaveBeenCalledTimes(1);
	});

	it('checks the contract on the WASM fallback session too', async () => {
		// The primary create fails the way a WebGPU driver does, so the fallback
		// session is the one that would serve every request. It was unchecked until
		// 2026-08-15.
		await expect(
			initWith([new Error('WebGPU EP unavailable'), fakeSession(V2.height, V2.classes)])
		).rejects.toThrow(ModelContractError);
		expect(createSession).toHaveBeenCalledTimes(2);
	});

	it('loads a matching model on the fallback path', async () => {
		const engine = await initWith([
			new Error('WebGPU EP unavailable'),
			fakeSession(V35.height, V35.classes)
		]);
		expect((engine as unknown as Internals).charset).toHaveLength(276);
	});

	it('strips a trailing newline from the charset file', async () => {
		const engine = new MonOcrOnnx();
		vi.spyOn(
			engine as unknown as { fetchAsset(u: string): Promise<Uint8Array> },
			'fetchAsset'
		).mockImplementation(async (url: string) =>
			url.endsWith('charset.txt')
				? new TextEncoder().encode(CHARSET + '\n')
				: new Uint8Array([1, 2, 3])
		);
		createSession.mockResolvedValueOnce(fakeSession(V35.height, V35.classes));

		// Without the strip the charset is 277 characters, which needs 278 classes,
		// and the contract check rejects a model that is in fact correct.
		await engine.initialize('/model.onnx', '/charset.txt');
		expect((engine as unknown as Internals).charset).toHaveLength(276);
	});
});

describe('resolveRecognitionModel', () => {
	const PINNED = CONFIG.MODELS.RECOGNITION;
	const LOCAL = CONFIG.MODELS.RECOGNITION_LOCAL;

	function headReturning(headers: Record<string, string>, ok = true) {
		return vi.fn().mockResolvedValue({ ok, headers: new Headers(headers) });
	}

	it('uses the local file when one is really there', async () => {
		vi.stubGlobal(
			'fetch',
			headReturning({ 'content-type': 'application/octet-stream', 'content-length': '46247040' })
		);
		await expect(resolveRecognitionModel()).resolves.toBe(LOCAL);
	});

	it('ignores the dev server SPA fallback, which answers 200 with HTML', async () => {
		// The failure this guards: a 200 carrying a document would otherwise be
		// handed to ONNX Runtime as if it were weights. Sized past the byte floor
		// deliberately, so only the content-type check can reject it.
		vi.stubGlobal(
			'fetch',
			headReturning({ 'content-type': 'text/html; charset=utf-8', 'content-length': '46247040' })
		);
		await expect(resolveRecognitionModel()).resolves.toBe(PINNED);
	});

	it('ignores a file too small to be weights', async () => {
		vi.stubGlobal(
			'fetch',
			headReturning({ 'content-type': 'application/octet-stream', 'content-length': '512' })
		);
		await expect(resolveRecognitionModel()).resolves.toBe(PINNED);
	});

	it('ignores a non-2xx response even when it carries a plausible body', async () => {
		// A proxy or SW answering 404 with an error page of the right size and
		// type. Only res.ok separates this from a real model.
		vi.stubGlobal(
			'fetch',
			headReturning(
				{ 'content-type': 'application/octet-stream', 'content-length': '46247040' },
				false
			)
		);
		await expect(resolveRecognitionModel()).resolves.toBe(PINNED);
	});

	it('falls back to the pinned revision when the fetch throws', async () => {
		vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));
		await expect(resolveRecognitionModel()).resolves.toBe(PINNED);
	});

	it('never takes the local branch in production', async () => {
		// A production build that picked up a local file would ship a model
		// nobody reviewed. The guard is the DEV check, so stub the flag rather
		// than trusting it.
		vi.stubEnv('DEV', false);
		const head = headReturning({
			'content-type': 'application/octet-stream',
			'content-length': '46247040'
		});
		vi.stubGlobal('fetch', head);

		await expect(resolveRecognitionModel()).resolves.toBe(PINNED);
		expect(head).not.toHaveBeenCalled();
		vi.unstubAllEnvs();
	});

	it('pins to a revision, never to a branch', () => {
		expect(PINNED).toContain('/resolve/d3d9d5e/');
		expect(PINNED).not.toContain('/resolve/main/');
	});
});

describe('decoding', () => {
	it('refuses a logits tensor whose class count does not match the charset', () => {
		const engine = engineWith(CHARSET, V35.height, V35.classes);
		const logits = logitsFor([1, 2, 3, 4], V2.classes);

		expect(() => engine.decodePredictions(logits, [1, 4, V2.classes])).toThrow(ModelContractError);
	});

	it('collapses CTC repeats and drops blanks', () => {
		const engine = engineWith(CHARSET, V35.height, V35.classes);
		// class 1, class 1 again (a repeat), blank, class 1 again (a new run).
		const logits = logitsFor([1, 1, 0, 1], V35.classes);

		expect(engine.decodePredictions(logits, [1, 4, V35.classes])).toBe(CHARSET[0] + CHARSET[0]);
	});

	it('maps class n to charset[n - 1], because index 0 is the CTC blank', () => {
		const engine = engineWith(CHARSET, V35.height, V35.classes);
		const logits = logitsFor([1, 2, 276], V35.classes);

		expect(engine.decodePredictions(logits, [1, 3, V35.classes])).toBe(
			CHARSET[0] + CHARSET[1] + CHARSET[275]
		);
	});
});

/**
 * The init budget is a correctness property, not a preference.
 *
 * These ran red before 2026-08-18, when one 60,000 ms timeout covered both
 * initialisation and recognition. Initialisation downloads the model, so that
 * budget silently demanded a minimum connection speed: below it the request
 * rejected, the download kept running unobserved, and the 5-minute idle timer
 * killed the worker before anything reached the cache. Every reload started
 * over, so the app never worked at all under roughly 6.2 Mbps.
 */
describe('the model-download budget', () => {
	// Measured against the pinned revision d3d9d5e, not estimated:
	//   curl -sI .../monocr.onnx -> content-length: 46247040
	const MODEL_BYTES = 46_247_040;
	const bitsPerSecondNeeded = (bytes: number, ms: number) => (bytes * 8) / (ms / 1000);

	it('covers the pinned model on a slow mobile connection', () => {
		const required = bitsPerSecondNeeded(MODEL_BYTES, CONFIG.WORKER.INIT_TIMEOUT_MS);

		// 0.5 Mbps. Anything stricter than this is a bandwidth floor wearing a
		// timeout's clothing, and the people this is built for are on mobile
		// networks in Myanmar and Thailand.
		expect(required).toBeLessThan(500_000);
	});

	it('does not reuse the recognition budget, which the model cannot fit in', () => {
		expect(CONFIG.WORKER.INIT_TIMEOUT_MS).toBeGreaterThan(CONFIG.WORKER.RECOGNIZE_TIMEOUT_MS);

		// The positive control: state the failure the split exists to prevent, so
		// that collapsing the two values back into one fails here rather than in
		// the hands of a user on a 1 Mbps link.
		const requiredUnderOldBudget = bitsPerSecondNeeded(
			MODEL_BYTES,
			CONFIG.WORKER.RECOGNIZE_TIMEOUT_MS
		);
		expect(requiredUnderOldBudget).toBeGreaterThan(6_000_000);
	});

	it('cannot outlive the idle timer without progress keeping it alive', () => {
		// monocr.ts terminates an idle worker after 5 minutes. INIT is deliberately
		// allowed to exceed that, which is only safe because PROGRESS messages reset
		// the timer on every chunk. If someone removes that reset, this comment is
		// the record of why the download starts dying at five minutes again.
		const WORKER_IDLE_TIMEOUT_MS = 5 * 60 * 1000;
		expect(CONFIG.WORKER.INIT_TIMEOUT_MS).toBeGreaterThan(WORKER_IDLE_TIMEOUT_MS);
	});
});

/**
 * Streaming the model body, added 2026-08-18 alongside the timeout split.
 *
 * The download is the slowest and least observable thing the app does, and it
 * runs on the connections least able to afford a mistake, so the buffer
 * arithmetic is worth pinning down.
 */
describe('reading the model body', () => {
	type Reader = {
		readWithProgress: (
			r: Response,
			cb?: (received: number, total: number) => void
		) => Promise<Uint8Array>;
	};

	/** A Response whose body arrives in fixed-size chunks. */
	function streamed(bytes: Uint8Array, chunk: number, declaredLength?: number) {
		let i = 0;
		const body = {
			getReader: () => ({
				read: async () => {
					if (i >= bytes.length) return { done: true, value: undefined };
					const slice = bytes.subarray(i, Math.min(i + chunk, bytes.length));
					i += chunk;
					return { done: false, value: slice };
				}
			})
		};
		return {
			body,
			headers: { get: () => String(declaredLength ?? bytes.length) }
		} as unknown as Response;
	}

	const reader = () => new MonOcrOnnx() as unknown as Reader;
	const payload = (n: number) => Uint8Array.from({ length: n }, (_, i) => i % 251);

	it('returns the bytes intact when the declared length is right', async () => {
		const bytes = payload(5000);

		const got = await reader().readWithProgress(streamed(bytes, 512), () => {});

		expect(got.length).toBe(5000);
		expect(Array.from(got)).toEqual(Array.from(bytes));
	});

	it('refuses a body shorter than content-length instead of returning it', async () => {
		// A truncated download decodes as a corrupt model. Failing here names the
		// cause; failing inside InferenceSession.create does not.
		const bytes = payload(3000);

		await expect(reader().readWithProgress(streamed(bytes, 512, 5000), () => {})).rejects.toThrow(
			/Incomplete download: got 3000 of 5000/
		);
	});

	it('keeps the overflow when a body is longer than content-length', async () => {
		const bytes = payload(5000);

		const got = await reader().readWithProgress(streamed(bytes, 512, 3000), () => {});

		expect(got.length).toBe(5000);
		expect(Array.from(got)).toEqual(Array.from(bytes));
	});

	it('throttles progress instead of reporting every chunk', async () => {
		const bytes = payload(200_000);
		const seen: number[] = [];

		// 400 chunks. Unthrottled this reports 400 times, each one a postMessage,
		// a Svelte state update and a timer reset.
		await reader().readWithProgress(streamed(bytes, 500), (received) => seen.push(received));

		expect(seen.length).toBeLessThan(400);
		// The last report must be the true total, or the bar stops short of 100%.
		expect(seen.at(-1)).toBe(200_000);
	});
});
