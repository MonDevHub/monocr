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
 * The two generations, as measured from the real artifacts on 2026-08-14.
 *
 * v2 is published at revision a51be11 and is what apps/web/static/charset.txt
 * decodes. v3.5 is mon_OCR's current export, unpublished.
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
	it('is the 315-character v2 alphabet the pinned model decodes', () => {
		// If this fails, static/charset.txt changed and every number below is stale.
		expect(CHARSET.length).toBe(315);
	});

	it('has no trailing newline, so the strip on load is a no-op today', () => {
		expect(CHARSET).toBe(CHARSET.replace(/[\r\n]+$/, ''));
	});
});

describe('the model/charset contract', () => {
	it('accepts the published v2 model against the shipped charset', () => {
		const engine = engineWith(CHARSET, V2.height, V2.classes);
		expect(() => engine.assertModelContract()).not.toThrow();
	});

	it('refuses a v3.5 model, naming both heights', () => {
		const engine = engineWith(CHARSET, V35.height, V35.classes);

		// Height is checked first: it is the one that would otherwise surface as an
		// opaque ORT shape error during warmup.
		expect(() => engine.assertModelContract()).toThrow(ModelContractError);
		expect(() => engine.assertModelContract()).toThrow(/160px/);
		expect(() => engine.assertModelContract()).toThrow(/128px/);
	});

	it('refuses on class count alone, when the heights happen to agree', () => {
		const engine = engineWith(CHARSET, V2.height, V35.classes);

		expect(() => engine.assertModelContract()).toThrow(ModelContractError);
		expect(() => engine.assertModelContract()).toThrow(/emits 277 classes/);
		expect(() => engine.assertModelContract()).toThrow(/charset has 315/);
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
		await expect(initWith([fakeSession(V35.height, V35.classes)])).rejects.toThrow(
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
			initWith([new Error('WebGPU EP unavailable'), fakeSession(V35.height, V35.classes)])
		).rejects.toThrow(ModelContractError);
		expect(createSession).toHaveBeenCalledTimes(2);
	});

	it('loads a matching model on the fallback path', async () => {
		const engine = await initWith([
			new Error('WebGPU EP unavailable'),
			fakeSession(V2.height, V2.classes)
		]);
		expect((engine as unknown as Internals).charset).toHaveLength(315);
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
		createSession.mockResolvedValueOnce(fakeSession(V2.height, V2.classes));

		// Without the strip the charset is 316 characters, which needs 317 classes,
		// and the contract check rejects a model that is in fact correct.
		await engine.initialize('/model.onnx', '/charset.txt');
		expect((engine as unknown as Internals).charset).toHaveLength(315);
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
			headReturning({ 'content-type': 'application/octet-stream', 'content-length': '26342200' })
		);
		await expect(resolveRecognitionModel()).resolves.toBe(LOCAL);
	});

	it('ignores the dev server SPA fallback, which answers 200 with HTML', async () => {
		// The failure this guards: a 200 carrying a document would otherwise be
		// handed to ONNX Runtime as if it were weights. Sized past the byte floor
		// deliberately, so only the content-type check can reject it.
		vi.stubGlobal(
			'fetch',
			headReturning({ 'content-type': 'text/html; charset=utf-8', 'content-length': '26342200' })
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
				{ 'content-type': 'application/octet-stream', 'content-length': '26342200' },
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
			'content-length': '26342200'
		});
		vi.stubGlobal('fetch', head);

		await expect(resolveRecognitionModel()).resolves.toBe(PINNED);
		expect(head).not.toHaveBeenCalled();
		vi.unstubAllEnvs();
	});

	it('pins to a revision, never to a branch', () => {
		expect(PINNED).toContain('/resolve/a51be11/');
		expect(PINNED).not.toContain('/resolve/main/');
	});
});

describe('decoding', () => {
	it('refuses a logits tensor whose class count does not match the charset', () => {
		const engine = engineWith(CHARSET, V2.height, V2.classes);
		const logits = logitsFor([1, 2, 3, 4], V35.classes);

		expect(() => engine.decodePredictions(logits, [1, 4, V35.classes])).toThrow(ModelContractError);
	});

	it('collapses CTC repeats and drops blanks', () => {
		const engine = engineWith(CHARSET, V2.height, V2.classes);
		// class 1, class 1 again (a repeat), blank, class 1 again (a new run).
		const logits = logitsFor([1, 1, 0, 1], V2.classes);

		expect(engine.decodePredictions(logits, [1, 4, V2.classes])).toBe(CHARSET[0] + CHARSET[0]);
	});

	it('maps class n to charset[n - 1], because index 0 is the CTC blank', () => {
		const engine = engineWith(CHARSET, V2.height, V2.classes);
		const logits = logitsFor([1, 2, 315], V2.classes);

		expect(engine.decodePredictions(logits, [1, 3, V2.classes])).toBe(
			CHARSET[0] + CHARSET[1] + CHARSET[314]
		);
	});
});
