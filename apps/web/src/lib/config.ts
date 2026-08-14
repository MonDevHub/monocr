export const CONFIG = {
	MODELS: {
		// Pinned to a revision. `main` moves whenever the model repository is
		// pushed to, and the charset below is a local file — so tracking `main`
		// let the weights change without the table they decode through.
		// `a51be11` is the same revision the Python, JS, Go and Rust SDKs pin.
		//
		// Bumping this is a deliberate change: check the model's class count and
		// input height against static/charset.txt and TARGET_HEIGHT first.
		RECOGNITION: 'https://huggingface.co/janakhpon/monocr/resolve/a51be11/onnx/monocr.onnx',
		CHARSET: '/charset.txt'
	},
	WORKER: {
		TIMEOUT_MS: 60000,
		MAX_RETRIES: 3
	},
	UI: {
		MAX_IMAGE_SIZE_MB: 50,
		ALLOWED_FILE_TYPES: ['image/jpeg', 'image/png', 'image/webp', 'application/pdf']
	},
	SYNC: {
		MAX_RETRIES: 3,
		BATCH_DELAY_MS: 1000,
		ALLOWED_MIME_TYPES: ['image/jpeg', 'image/png', 'image/webp', 'application/pdf', 'text/plain']
	}
} as const;

export type Config = typeof CONFIG;
