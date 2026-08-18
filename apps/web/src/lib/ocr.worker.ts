import { MonOcrOnnx } from './monocr-onnx';

let engine: MonOcrOnnx | null = null;

self.onmessage = async (e: MessageEvent) => {
	const { id, type, payload } = e.data;

	try {
		switch (type) {
			case 'INIT':
				if (!engine) {
					// Assign only after initialize() resolves. Assigning first meant a
					// failed init left a non-null `engine` behind, so the next INIT took
					// the `if (!engine)` short-circuit and reported 'Initialized' against
					// a session that was never created — the UI went ready and the first
					// scan died on "Model not initialized". Harmless while nothing could
					// retry; a retry button makes it reachable.
					const pending = new MonOcrOnnx();
					const { modelPath, charsetPath } = payload;
					await pending.initialize(modelPath, charsetPath, (received, total) => {
						self.postMessage({ id, type: 'PROGRESS', payload: { received, total } });
					});
					engine = pending;
				}
				self.postMessage({ id, type: 'RESULT', payload: 'Initialized' });
				break;

			case 'RECOGNIZE': {
				if (!engine) throw new Error('Engine not initialized');
				const imageBytes = payload as Uint8Array;
				const text = await engine.recognize(imageBytes);
				self.postMessage({ id, type: 'RESULT', payload: text });
				break;
			}

			default:
				throw new Error(`Unknown message type: ${type}`);
		}
	} catch (err: unknown) {
		const errorMessage = err instanceof Error ? err.message : String(err);
		self.postMessage({ id, type: 'ERROR', payload: errorMessage });
	}
};

export {};
