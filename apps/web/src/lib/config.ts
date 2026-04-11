export const CONFIG = {
	MODELS: {
		RECOGNITION: 'https://huggingface.co/janakhpon/monocr/resolve/main/onnx/monocr.onnx',
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
