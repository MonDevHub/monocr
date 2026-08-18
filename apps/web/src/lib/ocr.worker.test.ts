import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The worker's init path, which had no coverage until 2026-08-18.
 *
 * `MonOcrOnnx` is mocked with an `initialize` we control, so a second INIT can be
 * delivered while the first is still in flight — the state the real 46 MB
 * download sits in for minutes and the only state where this bug exists.
 */
const constructed = vi.fn();
let resolveInit: (() => void) | null = null;
let rejectInit: ((e: Error) => void) | null = null;

vi.mock('./monocr-onnx', () => ({
	MonOcrOnnx: class {
		constructor() {
			constructed();
		}
		initialize() {
			return new Promise<void>((res, rej) => {
				resolveInit = () => res();
				rejectInit = (e: Error) => rej(e);
			});
		}
		recognize() {
			return Promise.resolve('');
		}
	}
}));

const { handleMessage, resetForTests } = await import('./ocr.worker');

function hostSpy() {
	const posted: { id: string; type: string; payload: unknown }[] = [];
	return { posted, postMessage: (m: (typeof posted)[number]) => posted.push(m) };
}

const INIT = { modelPath: 'm.onnx', charsetPath: 'c.txt' } as never;

beforeEach(() => {
	resetForTests();
	constructed.mockClear();
	resolveInit = null;
	rejectInit = null;
});

describe('the worker init path', () => {
	it('joins an init already running instead of downloading the model twice', async () => {
		const host = hostSpy();

		// Two INITs before the first resolves. This is exactly what the retry
		// button produces after the client-side timeout rejects: the client clears
		// its own promise, but nothing cancels the fetch inside the worker.
		const first = handleMessage(host, { id: 'a', type: 'INIT', payload: INIT });
		const second = handleMessage(host, { id: 'b', type: 'INIT', payload: INIT });

		resolveInit!();
		await Promise.all([first, second]);

		// The whole point: one engine, so one 46 MB download.
		expect(constructed).toHaveBeenCalledTimes(1);

		// And both callers still get their own answer, under their own id.
		expect(
			host.posted
				.filter((m) => m.type === 'RESULT')
				.map((m) => m.id)
				.sort()
		).toEqual(['a', 'b']);
	});

	it('stays retryable after a failed init', async () => {
		const host = hostSpy();

		const failing = handleMessage(host, { id: 'a', type: 'INIT', payload: INIT });
		rejectInit!(new Error('network died'));
		await failing;

		expect(host.posted).toContainEqual({ id: 'a', type: 'ERROR', payload: 'network died' });

		// A retry must build a new engine rather than reuse the rejected promise.
		const retry = handleMessage(host, { id: 'b', type: 'INIT', payload: INIT });
		resolveInit!();
		await retry;

		expect(constructed).toHaveBeenCalledTimes(2);
		expect(host.posted).toContainEqual({ id: 'b', type: 'RESULT', payload: 'Initialized' });
	});

	it('does not report ready when init failed', async () => {
		const host = hostSpy();

		const failing = handleMessage(host, { id: 'a', type: 'INIT', payload: INIT });
		rejectInit!(new Error('boom'));
		await failing;

		// The original defect: `engine` was assigned before initialize() resolved,
		// so this RECOGNIZE would run against a session that was never created.
		await handleMessage(host, { id: 'b', type: 'RECOGNIZE', payload: new Uint8Array() as never });

		expect(host.posted).toContainEqual({
			id: 'b',
			type: 'ERROR',
			payload: 'Engine not initialized'
		});
		expect(host.posted.some((m) => m.type === 'RESULT')).toBe(false);
	});
});
