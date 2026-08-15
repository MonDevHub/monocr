export const CONFIG = {
	MODELS: {
		// Pinned to a revision. `main` moves whenever the model repository is
		// pushed to, and the charset below is a local file — so tracking `main`
		// let the weights change without the table they decode through.
		// `d3d9d5e` is the same revision the Python, JS, Go and Rust SDKs pin.
		//
		// Bumping this is a deliberate change: check the model's class count and
		// input height against static/charset.txt and TARGET_HEIGHT first.
		RECOGNITION: 'https://huggingface.co/janakhpon/monocr/resolve/d3d9d5e/onnx/monocr.onnx',

		// Local override for development. Put the model at `static/monocr.onnx`
		// and every reload reads it off disk instead of pulling 26.3 MB from
		// Hugging Face. Gitignored — see the README for the fetch command.
		//
		// The path is the same one `functions/monocr.onnx.js` proxies in
		// production, so nothing about the request shape changes between the two.
		RECOGNITION_LOCAL: '/monocr.onnx',

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

/**
 * Which model URL to load: the local file in development if it is really there,
 * the pinned remote otherwise.
 *
 * Production never takes the local branch. In development the check is
 * deliberately fussy, because SvelteKit's dev server answers an unknown path
 * with the SPA fallback page — a 200 carrying HTML. Accepting that would hand
 * ONNX Runtime a document and produce a parse error nobody would connect back to
 * a missing file, so a candidate has to be non-HTML and large enough to be
 * weights before it is used.
 *
 * The choice is logged either way. A silently selected model is how you end up
 * debugging the wrong artifact.
 */
export async function resolveRecognitionModel(): Promise<string> {
	const pinned = CONFIG.MODELS.RECOGNITION;
	if (!import.meta.env.DEV) return pinned;

	const local = CONFIG.MODELS.RECOGNITION_LOCAL;
	try {
		const res = await fetch(local, { method: 'HEAD' });
		const type = res.headers.get('content-type') ?? '';
		const bytes = Number(res.headers.get('content-length') ?? 0);

		if (res.ok && !type.includes('html') && bytes > 1_000_000) {
			console.info(`[monocr] dev: using local model ${local} (${(bytes / 1e6).toFixed(1)} MB)`);
			return local;
		}
	} catch {
		// Fall through to the pinned URL — an unreachable local path is the
		// ordinary case, not an error worth surfacing.
	}

	console.info(`[monocr] dev: no local model at ${local}; fetching the pinned revision`);
	return pinned;
}
