import { describe, expect, it, vi } from 'vitest';

// onnxruntime-web is imported at module scope and pulls in WASM binaries. None of
// it is exercised here: these tests drive the contract check against a stubbed
// session, which is the only way to hold a v3.5 graph next to a v2 charset
// without shipping a 46 MB fixture.
vi.mock('onnxruntime-web', () => ({
	env: { wasm: {} },
	Tensor: class {},
	InferenceSession: { create: vi.fn() }
}));

const { MonOcrOnnx, ModelContractError } = await import('./monocr-onnx');

/**
 * The two generations, as measured from the real artifacts on 2026-08-14.
 *
 * v2 is published at revision a51be11 and is what apps/web/static/charset.txt
 * decodes. v3.5 is mon_OCR's current export, unpublished.
 */
const V2 = { height: 128, classes: 316, charsetLength: 315 };
const V35 = { height: 160, classes: 277, charsetLength: 276 };

function engineWith(charsetLength: number, height: number, classes: number) {
	const engine = new MonOcrOnnx();
	// The private fields are the subject. Reaching them directly beats
	// reconstructing the load path, which would need a real ONNX buffer.
	const internals = engine as unknown as {
		charset: string;
		session: unknown;
		assertModelContract(): void;
		decodePredictions(logits: Float32Array, shape: number[]): string;
	};

	internals.charset = 'x'.repeat(charsetLength);
	internals.session = {
		inputMetadata: [
			{ name: 'input', isTensor: true, type: 'float32', shape: ['batch', 1, height, 1024] }
		],
		outputMetadata: [
			{ name: 'logits', isTensor: true, type: 'float32', shape: ['batch', 'time', classes] }
		]
	};
	return internals;
}

describe('the model/charset contract', () => {
	it('accepts the published v2 model against the bundled v2 charset', () => {
		const engine = engineWith(V2.charsetLength, V2.height, V2.classes);
		expect(() => engine.assertModelContract()).not.toThrow();
	});

	it('refuses a v3.5 model against the v2 charset, and says which numbers disagree', () => {
		const engine = engineWith(V2.charsetLength, V35.height, V35.classes);

		// Height is checked first, because it is the one that would otherwise
		// surface as an opaque ORT shape error during warmup.
		expect(() => engine.assertModelContract()).toThrow(ModelContractError);
		expect(() => engine.assertModelContract()).toThrow(/160px/);
		expect(() => engine.assertModelContract()).toThrow(/128px/);
	});

	it('refuses on class count alone, when the heights happen to agree', () => {
		const engine = engineWith(V2.charsetLength, V2.height, V35.classes);

		expect(() => engine.assertModelContract()).toThrow(ModelContractError);
		expect(() => engine.assertModelContract()).toThrow(/emits 277 classes/);
		expect(() => engine.assertModelContract()).toThrow(/charset has 315/);
	});

	it('does not judge a symbolic dimension', () => {
		const engine = engineWith(V2.charsetLength, V2.height, V2.classes);
		(engine.session as { inputMetadata: { shape: unknown[] }[] }).inputMetadata[0].shape = [
			'batch',
			1,
			'height',
			'width'
		];
		expect(() => engine.assertModelContract()).not.toThrow();
	});
});

describe('decoding', () => {
	it('refuses a logits tensor whose class count does not match the charset', () => {
		const engine = engineWith(V2.charsetLength, V2.height, V2.classes);
		const logits = new Float32Array(4 * V35.classes);

		expect(() => engine.decodePredictions(logits, [1, 4, V35.classes])).toThrow(ModelContractError);
	});

	it('decodes when the counts agree', () => {
		const engine = engineWith(V2.charsetLength, V2.height, V2.classes);

		// Three timesteps: class 1, class 1 again (a CTC repeat), then blank.
		const timeSteps = 3;
		const logits = new Float32Array(timeSteps * V2.classes);
		logits[0 * V2.classes + 1] = 1;
		logits[1 * V2.classes + 1] = 1;
		logits[2 * V2.classes + 0] = 1;

		expect(engine.decodePredictions(logits, [1, timeSteps, V2.classes])).toBe('x');
	});

	it('would have decoded silently under the old bounds check', () => {
		// The regression this file exists for. `if (idx - 1 < charset.length)` is
		// true for every index a 277-class model can emit when the charset holds
		// 315 characters, so the mismatch produced well-formed wrong text and no
		// error. Asserting the throw is asserting that silence is gone.
		const engine = engineWith(V2.charsetLength, V2.height, V2.classes);
		const timeSteps = 2;
		const logits = new Float32Array(timeSteps * V35.classes);
		logits[0 * V35.classes + 276] = 1;
		logits[1 * V35.classes + 5] = 1;

		expect(() => engine.decodePredictions(logits, [1, timeSteps, V35.classes])).toThrow(
			/emits 277 classes/
		);
	});
});
