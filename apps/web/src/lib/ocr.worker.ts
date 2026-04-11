import { MonOcrOnnx } from './monocr-onnx';

let engine: MonOcrOnnx | null = null;

self.onmessage = async (e: MessageEvent) => {
	const { id, type, payload } = e.data;

	try {
		switch (type) {
			case 'INIT':
				if (!engine) {
					engine = new MonOcrOnnx();
					const { modelPath, charsetPath } = payload;
					await engine.initialize(modelPath, charsetPath);
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
