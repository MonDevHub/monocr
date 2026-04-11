import * as ort from 'onnxruntime-web';

import { segmentLines } from './segmentation';

/**
 * ONNX Runtime Web-based OCR engine for Mon language.
 * Supports WebGPU (fastest), WASM with SIMD, and WASM fallback.
 */
export const MODEL_VERSION = '2026.03.21.v1'; // Constitution Aligned
export class MonOcrOnnx {
	private session: ort.InferenceSession | null = null;
	private charset: string = '';
	private readonly TARGET_HEIGHT = 128;
	private readonly TARGET_WIDTH = 1024;

	/**
	 * Initialize the ONNX Runtime session with model and charset.
	 * @param modelPath Path to the ONNX model file
	 * @param charsetPath Path to the charset file
	 */
	async initialize(modelPath: string, charsetPath: string): Promise<void> {
		// Configure ONNX Runtime Wasm paths BEFORE creating session
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
			this.charset = decoder.decode(charsetBuffer);

			// Load ONNX model with Caching strategy
			const modelBuffer = await this.fetchAsset(modelPath);

			// Init session with buffer, with automatic fallback mapping
			try {
				this.session = await ort.InferenceSession.create(modelBuffer, sessionOptions);
				// Warm-up inference to JIT-compile kernels
				await this.warmup();
			} catch (epError: unknown) {
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
	private async fetchAsset(url: string): Promise<Uint8Array> {
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

				// Store in cache for offline use; ignore quota errors gracefully
				try {
					await cache.put(url, response.clone());
				} catch (e) {
					cacheError = new Error(`Cache write failed (quota?): ${e}`);
				}

				const buffer = await response.arrayBuffer();
				return new Uint8Array(buffer);
			}
		} catch (e) {
			cacheError = e instanceof Error ? e : new Error(String(e));
		}

		// Fallback: direct fetch (Cache API unavailable or errored)
		try {
			const response = await fetch(url);
			if (!response.ok) throw new Error(`Failed to fetch ${url}: ${response.statusText}`);
			return new Uint8Array(await response.arrayBuffer());
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
		source: ImageBitmap,
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

		// Convert to grayscale
		const grayscale = new Float32Array(this.TARGET_WIDTH * this.TARGET_HEIGHT);
		let sumGray = 0;
		let activeCount = 0;
		for (let i = 0; i < grayscale.length; i++) {
			const offset = i * 4;
			const r = data[offset];
			const g = data[offset + 1];
			const b = data[offset + 2];
			const gray = 0.299 * r + 0.587 * g + 0.114 * b;
			grayscale[i] = gray;
			// Only sample active (non-padded) region for mean luminance
			if (i % this.TARGET_WIDTH < scaledWidth) {
				sumGray += gray;
				activeCount++;
			}
		}

		// Adaptive inversion: if the active region is predominantly dark (mean < 120),
		// assume light text on dark background and invert so the model sees dark on white.
		// This matches monocr-ios and monocr-android behaviour.
		const meanGray = activeCount > 0 ? sumGray / activeCount : 255;
		const shouldInvert = meanGray < 120;

		// Apply inversion first so contrast stretching works in the correct direction
		if (shouldInvert) {
			for (let i = 0; i < grayscale.length; i++) {
				if (i % this.TARGET_WIDTH < scaledWidth) {
					grayscale[i] = 255 - grayscale[i];
				}
			}
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
				// Map index to character (1-indexed)
				if (idx - 1 < this.charset.length) {
					decoded.push(this.charset[idx - 1]);
				}
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

		// 3. Segment Lines
		let segments = segmentLines(imageData);

		// Fallback: if no segments found (e.g. single large word filling bounds?), use full image
		if (segments.length === 0) {
			segments = [{ x: 0, y: 0, width: fullBitmap.width, height: fullBitmap.height }];
		}

		const results: string[] = [];

		// 4. Process each line
		try {
			for (const seg of segments) {
				const inputData = await this.processLine(fullBitmap, seg.x, seg.y, seg.width, seg.height);
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
				const text = this.decodePredictions(output.data as Float32Array, output.dims as number[]);

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
