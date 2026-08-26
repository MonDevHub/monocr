import * as ort from 'onnxruntime-web';

import { assessCapture } from './capture-quality';
import { normalizePagePolarity, segmentLines, tileLine } from './segmentation';

/**
 * ONNX Runtime Web-based OCR engine for Mon language.
 * Supports WebGPU (fastest), WASM with SIMD, and WASM fallback.
 */
// The model all three apps ship, identified by the revision it came from rather
// than by a date. `2026.03.21.v1` was none of: not a model generation, not a
// Hugging Face revision, and not the date of anything checkable. It was declared
// in three languages and read by nothing, so it drifted without consequence until
// someone tried to use it to answer which model was deployed.
//
// `d3d9d5e` is the revision the web app pins and the four monocr-onnx SDKs pin.
// Bump this in the same change that bumps those, or it stops being an answer.
export const MODEL_VERSION = 'v3.5@d3d9d5e';

/**
 * The model and the charset disagree about what this model is.
 *
 * Its own class, because an execution-provider failure is retried on WASM and a
 * contract failure must not be — retrying would load the model a second time to
 * fail in the same way, under a message blaming the wrong thing.
 */
export class ModelContractError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'ModelContractError';
	}
}

export class MonOcrOnnx {
	private session: ort.InferenceSession | null = null;
	private charset: string = '';
	private readonly TARGET_HEIGHT = 160;
	private readonly TARGET_WIDTH = 1024;
	/**
	 * Initialize the ONNX Runtime session with model and charset.
	 * @param modelPath Path to the ONNX model file
	 * @param charsetPath Path to the charset file
	 */
	async initialize(
		modelPath: string,
		charsetPath: string,
		onProgress?: (received: number, total: number) => void
	): Promise<void> {
		// Configure ONNX Runtime Wasm paths BEFORE creating session
		// Senior Tip: Serving WASM from same-origin is most reliable for COOP/COEP and PWA.
		ort.env.wasm.wasmPaths = '/wasm/';

		// Explicitly disable multi-threading to avoid conflicts with WebGPU/JSEP
		// on modern versions of onnxruntime-web (1.24.x)
		ort.env.wasm.numThreads = 1;

		// Determine supported execution providers
		const executionProviders: string[] = [];
		try {
			if (
				typeof navigator !== 'undefined' &&
				'gpu' in navigator &&
				(navigator as unknown as { gpu: { requestAdapter: () => Promise<unknown | null> } }).gpu
			) {
				// More robust check: try to request an adapter
				const adapter = await (
					navigator as unknown as { gpu: { requestAdapter: () => Promise<unknown | null> } }
				).gpu.requestAdapter();
				if (adapter) {
					executionProviders.push('webgpu');
					console.log('[monocr-onnx] WebGPU support detected and enabled.');
				}
			}
		} catch (e) {
			console.debug('[monocr-onnx] WebGPU detection failed or not supported:', e);
		}
		executionProviders.push('wasm');

		// Configure ONNX Runtime for optimal performance
		const sessionOptions: ort.InferenceSession.SessionOptions = {
			executionProviders,
			graphOptimizationLevel: 'all',
			enableCpuMemArena: true,
			enableMemPattern: true,
			logSeverityLevel: 3 // Error only
		};

		try {
			// One-time cache invalidation: evict any old monocr-models-* caches
			if ('caches' in self) {
				const keys = await caches.keys();
				await Promise.all(
					keys
						.filter((k) => k.startsWith('monocr-models-') && k !== 'monocr-models')
						.map((k) => caches.delete(k))
				);
			}

			// Load charset with caching
			const charsetBuffer = await this.fetchAsset(charsetPath);
			const decoder = new TextDecoder('utf-8');
			// Trailing newline stripped, matching the Python and JS SDKs. The file
			// has none today, and if one is ever added it would shift the character
			// count by one and trip the contract check below over a byte that is not
			// part of the alphabet.
			this.charset = decoder.decode(charsetBuffer).replace(/[\r\n]+$/, '');

			// Load ONNX model with Caching strategy. The charset above is 556 bytes and
			// needs no progress; this is the 46 MB one.
			const modelBuffer = await this.fetchAsset(modelPath, onProgress);

			// Init session with buffer, with automatic fallback mapping
			try {
				this.session = await ort.InferenceSession.create(modelBuffer, sessionOptions);
				this.assertModelContract();
				// Warm-up inference to JIT-compile kernels
				await this.warmup();
			} catch (epError: unknown) {
				// A contract mismatch is not an execution-provider problem. Falling
				// back to WASM would download nothing new, fail identically, and
				// report it as a WebGPU issue.
				if (epError instanceof ModelContractError) throw epError;

				const errorMsg = epError instanceof Error ? epError.message : String(epError);
				console.warn(
					`[monocr-onnx] EP Error: ${errorMsg}. Falling back to WASM natively.`,
					epError
				);

				// Fallback strategy if WebGPU completely fails at the EP level
				const fallbackOptions: ort.InferenceSession.SessionOptions = {
					...sessionOptions,
					executionProviders: ['wasm']
				};
				this.session = await ort.InferenceSession.create(modelBuffer, fallbackOptions);
				// Also here, not only on the path above. This is the session that
				// serves traffic whenever WebGPU fails, and it was unchecked until
				// 2026-08-15 — a mismatched height would have surfaced as an opaque
				// ORT shape error from warmup(), which is the failure the check exists
				// to replace with a named one.
				this.assertModelContract();

				// Warm-up WASM inference
				await this.warmup();
			}
		} catch (error) {
			console.error('[monocr-onnx] Global Observability: Model initialization failed.', error);
			throw error;
		}
	}

	/**
	 * Fetch asset with caching (Cache API) for offline robustness.
	 * Cache name matches the Workbox SW runtime cache so both paths
	 * share a single store: Cache API reads → SW writes, and vice-versa.
	 */
	private async fetchAsset(
		url: string,
		onProgress?: (received: number, total: number) => void
	): Promise<Uint8Array> {
		const CACHE_NAME = 'monocr-models';
		let cacheError: Error | null = null;

		try {
			if ('caches' in self) {
				const cache = await caches.open(CACHE_NAME);
				const cachedResponse = await cache.match(url);

				if (cachedResponse) {
					const buffer = await cachedResponse.arrayBuffer();
					return new Uint8Array(buffer);
				}

				const response = await fetch(url);
				if (!response.ok) throw new Error(`Failed to fetch ${url}: ${response.statusText}`);

				const bytes = await this.readWithProgress(response, onProgress);

				// Cache the bytes we already have rather than a second copy of the
				// stream. `cache.put(url, response.clone())` used to run before the
				// body was read, which meant the entry only landed if the whole
				// download completed — and on a slow link the worker was terminated
				// first, so nothing was ever cached and every reload started over.
				try {
					await cache.put(
						url,
						new Response(bytes.buffer as ArrayBuffer, { headers: response.headers })
					);
				} catch (e) {
					cacheError = new Error(`Cache write failed (quota?): ${e}`);
				}

				return bytes;
			}
		} catch (e) {
			cacheError = e instanceof Error ? e : new Error(String(e));
		}

		// Fallback: direct fetch (Cache API unavailable or errored)
		try {
			const response = await fetch(url);
			if (!response.ok) throw new Error(`Failed to fetch ${url}: ${response.statusText}`);
			return await this.readWithProgress(response, onProgress);
		} catch (networkError) {
			if (cacheError) {
				throw new Error(
					`Offline — cache unavailable and network failed. Cache error: ${cacheError.message}. Network: ${networkError}`,
					{ cause: networkError }
				);
			}
			throw networkError;
		}
	}

	/**
	 * Read a response body, reporting bytes as they arrive.
	 *
	 * `response.arrayBuffer()` gives no way to observe a 46 MB download, so the
	 * UI could only show an indeterminate spinner for anywhere from 8 seconds to
	 * several minutes. Reporting progress is also what keeps the worker's idle
	 * timer alive while the download is in flight.
	 *
	 * Falls back to `arrayBuffer()` where the body is not a readable stream.
	 *
	 * Writes into one preallocated buffer when content-length is known, rather
	 * than collecting chunks and concatenating: the concat held the chunk list and
	 * the finished array at the same time, roughly 92 MB live for a 46 MB model,
	 * on the low-end devices this whole path exists to serve. The chunk list is
	 * kept only for the case where the declared length is absent or wrong, because
	 * a body that overruns its content-length must not be silently truncated into
	 * a corrupt model.
	 */
	private async readWithProgress(
		response: Response,
		onProgress?: (received: number, total: number) => void
	): Promise<Uint8Array> {
		const total = Number(response.headers.get('content-length') ?? 0);

		if (!onProgress || !response.body) {
			return new Uint8Array(await response.arrayBuffer());
		}

		const reader = response.body.getReader();
		// Emit at most every 1% or 200 ms. One message per chunk meant hundreds to
		// thousands of postMessage round trips per download, each one a Svelte
		// state update and a clearTimeout/setTimeout pair. 200 ms stays far below
		// the 5-minute idle timer those messages keep alive.
		let lastEmit = 0;
		let lastPct = -1;
		const emit = (received: number, force: boolean) => {
			const now = Date.now();
			const pct = total > 0 ? Math.floor((received / total) * 100) : -1;
			// Both conditions must hold, not either: an `||` here would emit on every
			// 200 ms tick as well, which on a slow link is thousands of messages —
			// the opposite of throttling. When content-length is missing, pct is -1
			// and the time floor is the only gate.
			const tooSoon = now - lastEmit < 200;
			const samePct = pct >= 0 && pct === lastPct;
			if (!force && (tooSoon || samePct)) return;
			lastEmit = now;
			lastPct = pct;
			onProgress(received, total);
		};

		let preallocated = total > 0 ? new Uint8Array(total) : null;
		const chunks: Uint8Array[] = [];
		let received = 0;

		for (;;) {
			const { done, value } = await reader.read();
			if (done) break;

			if (preallocated && received + value.length <= preallocated.length) {
				preallocated.set(value, received);
			} else if (preallocated) {
				// The body is longer than content-length claimed. Fall back rather
				// than drop the overflow.
				chunks.push(preallocated.subarray(0, received), value);
				preallocated = null;
			} else {
				chunks.push(value);
			}

			received += value.length;
			emit(received, false);
		}

		emit(received, true);

		if (preallocated) {
			// A body shorter than declared is a truncated download, not a model.
			if (received !== preallocated.length) {
				throw new Error(`Incomplete download: got ${received} of ${preallocated.length} bytes`);
			}
			return preallocated;
		}

		const bytes = new Uint8Array(received);
		let offset = 0;
		for (const chunk of chunks) {
			bytes.set(chunk, offset);
			offset += chunk.length;
		}
		return bytes;
	}

	/**
	 * Refuse to run a model that does not match what this app decodes with.
	 *
	 * The weights come from HuggingFace and the charset is a file in static/, so
	 * nothing structurally ties the two together — they agree because someone
	 * checked, and they stay agreeing because the model URL is pinned.
	 *
	 * The failure this prevents is not a crash. A 277-class graph read through a
	 * 315-character table yields well-formed Mon text that is wrong, with no
	 * exception and no lookup miss, because every decodable index is in range of
	 * the larger table. There is no symptom to notice.
	 */
	private assertModelContract(): void {
		const session = this.session!;

		// A dimension the graph declares symbolically comes back as a string, and
		// cannot be checked here. That is not a pass — it is an unverifiable
		// contract, so say so out loud rather than returning quietly. A sidecar or
		// graph missing the fields a check needs is disproportionately likely to be
		// the one that is wrong.
		const input = session.inputMetadata[0];
		if (input?.isTensor) {
			const declaredHeight = input.shape[2];
			if (typeof declaredHeight !== 'number') {
				console.warn(
					`[monocr-onnx] input height is symbolic (${String(declaredHeight)}); ` +
						`cannot verify it against TARGET_HEIGHT=${this.TARGET_HEIGHT}.`
				);
			} else if (declaredHeight !== this.TARGET_HEIGHT) {
				throw new ModelContractError(
					`Model expects an input height of ${declaredHeight}px; this build preprocesses to ` +
						`${this.TARGET_HEIGHT}px. The model and this app are different generations.`
				);
			}
		}

		const output = session.outputMetadata[0];
		if (output?.isTensor) {
			const numClasses = output.shape[output.shape.length - 1];
			if (typeof numClasses !== 'number') {
				// Recoverable: decodePredictions re-checks against the tensor that
				// actually comes back, so this one is deferred rather than skipped.
				console.warn(
					'[monocr-onnx] output class axis is symbolic; deferring the charset ' +
						'contract check to the first decode.'
				);
			} else {
				this.assertClassCount(numClasses);
			}
		}
	}

	/**
	 * CTC reserves index 0 for the blank, so a model over N characters emits
	 * N + 1 classes. Anything else means the two files describe different models.
	 */
	private assertClassCount(numClasses: number): void {
		const expected = this.charset.length + 1;
		if (numClasses !== expected) {
			throw new ModelContractError(
				`Model emits ${numClasses} classes, implying ${numClasses - 1} characters; ` +
					`the bundled charset has ${this.charset.length}, which needs ${expected} ` +
					`(one CTC blank plus one per character). Refusing to decode.`
			);
		}
	}

	/**
	 * Warm-up the model with a dummy input to trigger JIT compilation.
	 */
	private async warmup(): Promise<void> {
		const dummyData = new Float32Array(1 * 1 * this.TARGET_HEIGHT * this.TARGET_WIDTH).fill(1.0);

		const dummyTensor = new ort.Tensor('float32', dummyData, [
			1,
			1,
			this.TARGET_HEIGHT,
			this.TARGET_WIDTH
		]);

		try {
			const inputName = this.session!.inputNames[0];
			const feeds: Record<string, ort.Tensor> = {};
			feeds[inputName] = dummyTensor;
			await this.session!.run(feeds);
		} catch (error: unknown) {
			const errorMsg = error instanceof Error ? error.message : String(error);
			console.warn(
				'[monocr-onnx] Warmup inference failed. Dimensions may be slightly off or WebGPU backend crashed on execution.',
				errorMsg
			);
			throw error;
		}
	}

	/**
	 * Process a single text line into model input tensor format.
	 */
	private async processLine(
		// A canvas, not only a bitmap: the page is polarity-normalised into
		// `segCanvas` before segmentation, and the model must read the same pixels
		// the segmenter did.
		source: ImageBitmap | OffscreenCanvas,
		sx: number,
		sy: number,
		sw: number,
		sh: number
	): Promise<Float32Array> {
		// Calculate scaled dimensions
		const scale = this.TARGET_HEIGHT / sh;
		// Senior Tip: Use Math.floor to match Android/Python integer scaling
		const scaledWidth = Math.min(Math.floor(sw * scale), this.TARGET_WIDTH);

		// Use OffscreenCanvas
		const canvas = new OffscreenCanvas(this.TARGET_WIDTH, this.TARGET_HEIGHT);
		const ctx = canvas.getContext('2d', { willReadFrequently: true })!;

		// Senior Tip: Enforce high-quality smoothing for best OCR upscale
		ctx.imageSmoothingEnabled = true;
		ctx.imageSmoothingQuality = 'high';

		// Fill with white background
		ctx.fillStyle = 'white';
		ctx.fillRect(0, 0, this.TARGET_WIDTH, this.TARGET_HEIGHT);

		// Draw cropped and scaled image
		// drawImage(image, sx, sy, sw, sh, dx, dy, dw, dh)
		// Senior Tip: Floor coordinates to prevent sub-pixel blurring/artifacts
		ctx.drawImage(
			source,
			Math.floor(sx),
			Math.floor(sy),
			Math.floor(sw),
			Math.floor(sh),
			0,
			0,
			scaledWidth,
			this.TARGET_HEIGHT
		);

		const { data } = ctx.getImageData(0, 0, this.TARGET_WIDTH, this.TARGET_HEIGHT);

		// Convert to grayscale.
		//
		// Polarity is NOT decided here any more, and the comment that used to sit
		// below this loop claiming it "matches monocr-ios and monocr-android
		// behaviour" was false: iOS removed per-line inversion precisely because the
		// segmenter had already run on the un-inverted page. This was worse still,
		// deciding per TILE, so two tiles of one line could invert differently.
		// `segmentation.normalizePagePolarity` now does it once, per page, before
		// segmentation — which is also why the active-region luminance mean this loop
		// used to accumulate is gone: nothing reads it.
		const grayscale = new Float32Array(this.TARGET_WIDTH * this.TARGET_HEIGHT);
		for (let i = 0; i < grayscale.length; i++) {
			const offset = i * 4;
			grayscale[i] =
				0.299 * data[offset] + 0.587 * data[offset + 1] + 0.114 * data[offset + 2];
		}

		// Contrast stretching: linearly scale the active-region luminance to [0,255].
		// Recovers faint Mon strokes (thin vowel marks, stacked consonants) that
		// would otherwise produce near-zero model activations.
		let minG = 255;
		let maxG = 0;
		for (let i = 0; i < grayscale.length; i++) {
			if (i % this.TARGET_WIDTH < scaledWidth) {
				if (grayscale[i] < minG) minG = grayscale[i];
				if (grayscale[i] > maxG) maxG = grayscale[i];
			}
		}
		const rangeG = maxG - minG;
		// Only stretch when there's meaningful dynamic range (>30) to avoid
		// amplifying uniform blank regions into pure noise.
		const applyStretch = rangeG > 30;
		const stretchScale = applyStretch ? 255 / rangeG : 1;
		const stretchOffset = applyStretch ? minG : 0;

		const float32Data = new Float32Array(this.TARGET_WIDTH * this.TARGET_HEIGHT);
		for (let i = 0; i < float32Data.length; i++) {
			const x = i % this.TARGET_WIDTH;
			let gray = grayscale[i];
			if (x < scaledWidth && applyStretch) {
				gray = (gray - stretchOffset) * stretchScale;
			}
			float32Data[i] = gray / 127.5 - 1.0;
		}

		return float32Data;
	}

	/**
	 * Decode CTC predictions using greedy decoding.
	 */
	private decodePredictions(logits: Float32Array, shape: number[]): string {
		const [, timeSteps, numClasses] = shape;

		// Re-checked here as well as at load. assertModelContract reads the graph's
		// declared output shape, which may be symbolic; this is the shape that
		// actually came back.
		this.assertClassCount(numClasses);

		// Greedy decoding: argmax along class dimension
		const predictions: number[] = [];
		for (let t = 0; t < timeSteps; t++) {
			let maxIdx = 0;
			let maxVal = -Infinity;

			for (let c = 0; c < numClasses; c++) {
				const val = logits[t * numClasses + c];
				if (val > maxVal) {
					maxVal = val;
					maxIdx = c;
				}
			}

			predictions.push(maxIdx);
		}

		// CTC decoding: remove blanks (0) and consecutive duplicates
		const decoded: string[] = [];
		let prevIdx = -1;

		for (const idx of predictions) {
			if (idx !== 0 && idx !== prevIdx) {
				// Map index to character (1-indexed; 0 is the CTC blank).
				//
				// This used to read `if (idx - 1 < this.charset.length)`, silently
				// dropping anything past the end. Against a charset larger than the
				// model that condition is true for every index, so it never fired and
				// a mismatch decoded as fluent wrong text. Against a smaller one it
				// dropped characters and returned a shorter answer with no warning.
				const ch = this.charset[idx - 1];
				if (ch === undefined) {
					throw new ModelContractError(
						`Class index ${idx} falls outside the ${this.charset.length}-character charset. ` +
							`The model and the charset are not the same generation.`
					);
				}
				decoded.push(ch);
			}
			prevIdx = idx;
		}

		return decoded.join('');
	}

	/**
	 * Perform OCR on an image.
	 * @param imageBytes Raw image bytes (JPG, PNG, WebP)
	 * @returns Recognized text
	 */
	async recognize(imageBytes: Uint8Array): Promise<string> {
		if (!this.session) {
			throw new Error('Model not initialized. Call initialize() first.');
		}

		// 1. Decode generic image to Bitmap
		const blob = new Blob([imageBytes as unknown as BlobPart]);
		const fullBitmap = await createImageBitmap(blob);

		// 2. Get pixel data for segmentation
		// We MUST use a canvas to get ImageData (pixels)
		const segCanvas = new OffscreenCanvas(fullBitmap.width, fullBitmap.height);
		const segCtx = segCanvas.getContext('2d', { willReadFrequently: true })!;

		// Senior Tip: Fill with white to ensure transparent PNGs/WebPs
		// segment correctly (against white background)
		segCtx.fillStyle = 'white';
		segCtx.fillRect(0, 0, segCanvas.width, segCanvas.height);

		segCtx.drawImage(fullBitmap, 0, 0);

		const imageData = segCtx.getImageData(0, 0, fullBitmap.width, fullBitmap.height);

		// Once, here, before anything reads the pixels. Written back to the canvas so
		// `segmentLines`, `tileLine` and `processLine` all see the same buffer —
		// previously the first two read `imageData` while the third drew from the
		// original bitmap, so normalising one would have desynchronised them.
		if (normalizePagePolarity(imageData)) {
			segCtx.putImageData(imageData, 0, 0);
			console.info('[monocr-onnx] page had a dark background; inverted before segmenting');
		}

		// 3. Segment Lines
		// Say what this capture is going to cost before spending a minute on it. The
		// apps advertise "300 DPI min" and nothing has ever measured anything, so a
		// bad capture was only ever diagnosable from bad Mon text.
		for (const warning of assessCapture(imageData).warnings) {
			console.warn(`[monocr-onnx] capture: ${warning}`);
		}

		let segments = segmentLines(imageData);

		// Fallback: if no segments found (e.g. single large word filling bounds?), use full image
		if (segments.length === 0) {
			segments = [{ x: 0, y: 0, width: fullBitmap.width, height: fullBitmap.height }];
		}

		const results: string[] = [];

		// 4. Process each line
		try {
			for (const seg of segments) {
				// A band that is not line-shaped is a fused block of several lines, and
				// reading it as one returns text that appears nowhere on the page —
				// upstream measured exactly that at confidence 0.83, so confidence
				// cannot be the filter. Logged rather than dropped, because the band
				// still carries text a reader may want. The flag is not yet on the
				// worker's RESULT payload, which is a plain string; surfacing it in the
				// UI needs that protocol widened.
				if (seg.lineShaped === false) {
					console.warn(
						`[monocr-onnx] band ${seg.width}x${seg.height} at (${seg.x},${seg.y}) is not ` +
							`line-shaped — it may be several fused lines read as one. Treat its text ` +
							`with suspicion.`
					);
				}

				// A line wider than the window was squeezed into it. The cost of that
				// was quoted here as CER 0.1434 against 0.0795 tiled; retired
				// 2026-08-22, harness never committed, figures do not reproduce. It is
				// width-dependent and unbounded — 0.21 CER at 4 model windows against
				// tiling's 0.06, above 0.83 by 6 (mon_OCR/eval/tiling-ab-2026-08-22.md).
				// Tiles are read separately and joined with no separator: the cut lands
				// at a white column inside a word, so a space there would be wrong.
				const tiles = tileLine(imageData, seg, this.TARGET_HEIGHT, this.TARGET_WIDTH);
				const parts: string[] = [];

				for (const tile of tiles) {
					const inputData = await this.processLine(
						segCanvas,
						tile.x,
						tile.y,
						tile.width,
						tile.height
					);
					const inputTensor = new ort.Tensor('float32', inputData, [
						1,
						1,
						this.TARGET_HEIGHT,
						this.TARGET_WIDTH
					]);

					const inputName = this.session!.inputNames[0];
					const feeds: Record<string, ort.Tensor> = {};
					feeds[inputName] = inputTensor;

					const inferResults = await this.session!.run(feeds);
					const output = inferResults[Object.keys(inferResults)[0]];
					parts.push(this.decodePredictions(output.data as Float32Array, output.dims as number[]));
				}

				const text = parts.join('');
				if (text.trim()) {
					results.push(text);
				}
			}
		} catch (error) {
			console.error(
				'[monocr-onnx] Global Observability: Inference run failed. Possible WebGPU/WASM abort.',
				error
			);
			throw error;
		} finally {
			fullBitmap.close();
		}

		return results.join('\n');
	}

	/**
	 * Release resources.
	 */
	async dispose(): Promise<void> {
		if (this.session) {
			this.session = null;
		}
	}
}
