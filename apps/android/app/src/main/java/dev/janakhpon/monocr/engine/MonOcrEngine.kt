package dev.janakhpon.monocr.engine

import dev.janakhpon.monocr.util.MonLogger

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime-backed OCR engine for Mon language.
 *
 * Loads monocr.onnx from assets and runs inference on
 * preprocessed line images ([1, 1, 160, 1024] Float32 tensors).
 *
 * Equivalent to MonOcrOnnx in monocr-onnx.ts.
 */
class MonOcrEngine(private val context: Context) {

    private val runtimeLock = Any()
    private var usingNnapi = false
    private var cachedModel: File? = null
    @Volatile private var disposed = false
    private var ortEnv: OrtEnvironment? = null
    @Volatile private var ortSession: OrtSession? = null
    private var charset: String = ""
    
    companion object {
        // The model all three apps ship, identified by the revision it came from rather
        // than by a date. `2026.03.21.v1` was none of: not a model generation, not a
        // Hugging Face revision, and not the date of anything checkable. It was declared
        // in three languages and read by nothing, so it drifted without consequence until
        // someone tried to use it to answer which model was deployed.
        //
        // `d3d9d5e` is the revision the web app pins and the four monocr-onnx SDKs pin.
        // Bump this in the same change that bumps those, or it stops being an answer.
        const val MODEL_VERSION = "v3.5@d3d9d5e"
        private const val MODEL_SHA256 = "b95de1ea0e3dc99a5c31bea32e220835da801f683ed17091eaec9954c80a4c04"
        private const val CHARSET_SHA256 = "edfd75f688e4155c64aeee0dbac755da0e7ba45a388a2d178a84190fb3d7e953"

        /**
         * The cache filename carries the version, because `cacheDir` survives an app
         * update and the old copy did not.
         *
         * The asset used to be copied to a fixed `monocr.onnx` only when that file was
         * absent. A device that had run the v2 build kept the v2 graph — 26,342,200
         * bytes, input height 128 — after updating to a build that preprocesses to 160,
         * and nothing noticed: the graph loads, inference runs, and the output is wrong
         * Mon text. Derived from [MODEL_VERSION] rather than written out, so the two
         * cannot drift.
         */
        val CACHED_MODEL_NAME: String = "monocr-${MODEL_VERSION.replace('@', '-')}.onnx"

        /** Anything matching this that is not [CACHED_MODEL_NAME] is a superseded copy. */
        private val CACHED_MODEL_PATTERN = Regex("""^monocr.*\.onnx$""")
    }

    val isInitialized: Boolean get() = !disposed && ortSession != null

    /**
     * Load model and charset from assets. Call once before [runInference].
     * Safe to call multiple times — no-op if already initialized.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        synchronized(runtimeLock) {
            check(!disposed) { "OCR engine has been disposed" }
            if (isInitialized) return@synchronized

            // Load charset
            try {
                // Only the TRAILING end is trimmed: a leading newline would shift every
                // index by one and silently change what every class decodes to, so
                // stripping it would hide a corrupt file rather than fail on it.
                //
                // This was a bare readText(). It fails closed, because the class-count
                // check below would refuse a charset one character too long, but the
                // message blames the model for a mismatch the file introduced. iOS and
                // web have both trimmed since they were written; this port was the
                // outlier, and the four shipped charset.txt files happen to carry no
                // trailing newline today, which is the only reason it never fired.
                context.assets.open("charset.txt").use { input ->
                    check(VerifiedArtifactCache.sha256(input) == CHARSET_SHA256) { "Charset checksum mismatch" }
                }
                charset = context.assets.open("charset.txt").bufferedReader(Charsets.UTF_8).use {
                    it.readText().trimEnd('\n', '\r')
                }
            } catch (e: Exception) {
                MonLogger.e("Failed to load charset", e)
                throw e
            }

            MonLogger.i("Initializing ONNX environment...")
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            // Instead of readBytes(), which double-buffers 25MB in JVM heap and native ORT,
            // copy the asset once to the cache directory and load via file path.
            val modelFile = File(context.cacheDir, CACHED_MODEL_NAME)

            // Every other cached graph is from a previous build: the unversioned
            // monocr.onnx, the retired monocr_fp16.onnx, and any earlier version key.
            // Leaving them costs 25MB each and, worse, leaves a plausible-looking file for
            // a future bug to load.
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name != CACHED_MODEL_NAME && CACHED_MODEL_PATTERN.matches(file.name)) {
                    if (file.delete()) {
                        MonLogger.i("deleted stale cached model: name=${file.name}")
                    } else {
                        // Not fatal — the current model still loads from its own path.
                        MonLogger.w("could not delete stale cached model: name=${file.name}")
                    }
                }
            }

            // A copy interrupted by process death is never a `.onnx`, so the sweep
            // above cannot see it. Safe here: this lock is the only writer.
            VerifiedArtifactCache.deleteOrphanedPartials(context.cacheDir, "monocr").forEach {
                MonLogger.i("deleted orphaned partial model: name=$it")
            }

            cachedModel = VerifiedArtifactCache.ensure(modelFile, MODEL_SHA256) {
                context.assets.open("monocr.onnx")
            }
            ortSession = try {
                createSession(env, modelFile, allowNnapi = true)
            } catch (e: OrtException) {
                MonLogger.w("NNAPI session failed; retrying with CPU")
                createSession(env, modelFile, allowNnapi = false)
            }
        }
    }

    private fun createSession(env: OrtEnvironment, file: File, allowNnapi: Boolean): OrtSession {
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            usingNnapi = false
            if (allowNnapi) try {
                options.addNnapi()
                usingNnapi = true
            } catch (_: OrtException) { /* Default CPU provider remains available. */ }
            val session = env.createSession(file.absolutePath, options)
            try { assertModelContract(session) } catch (e: Throwable) { session.close(); throw e }
            session
        }
    }

    /**
     * Refuse to run a model that does not match what this app decodes with.
     *
     * The weights are a build asset and the charset is another build asset, so nothing
     * structurally ties the two together — they agree because someone checked. The
     * failure this prevents is silent: a 277-class graph read through a 315-character
     * table yields well-formed Mon text that is wrong, with no exception and no lookup
     * miss, because every decodable index is in range of the larger table.
     *
     * Mirrors the check in apps/web `monocr-onnx.ts`.
     */
    private fun assertModelContract(session: OrtSession) {
        val inputInfo = session.inputInfo.values.firstOrNull()?.info as? TensorInfo
        val inputShape = inputInfo?.shape
        if (inputShape == null || inputShape.size < 4) {
            // Unverifiable, not verified. A graph missing the fields a check needs is
            // disproportionately likely to be the one that is wrong, so say so.
            MonLogger.w(
                "model input is not a 4d tensor; cannot verify input height against " +
                    "target_height=${ImagePreprocessor.TARGET_HEIGHT}"
            )
        } else {
            val declaredHeight = inputShape[2]
            if (declaredHeight <= 0) {
                // ORT reports a symbolic dimension as -1. Nothing to compare against.
                MonLogger.w(
                    "model input height is symbolic (dim=$declaredHeight); cannot verify it " +
                        "against target_height=${ImagePreprocessor.TARGET_HEIGHT}"
                )
            } else if (declaredHeight.toInt() != ImagePreprocessor.TARGET_HEIGHT) {
                throw ModelContractException(
                    "Model expects an input height of ${declaredHeight}px; this build " +
                        "preprocesses to ${ImagePreprocessor.TARGET_HEIGHT}px. The model and this " +
                        "app are different generations."
                )
            }
        }

        val outputShape = (session.outputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape
        val declaredClasses = outputShape?.lastOrNull() ?: -1L
        if (declaredClasses <= 0) {
            // Recoverable: runInference re-checks against the tensor that actually comes
            // back, so this one is deferred rather than skipped.
            MonLogger.w(
                "model output class axis is symbolic; deferring the charset contract check " +
                    "to the first decode"
            )
        } else {
            assertClassCount(declaredClasses.toInt())
        }
    }

    /**
     * CTC reserves index 0 for the blank, so a model over N characters emits N + 1
     * classes. Anything else means the two assets describe different models.
     */
    private fun assertClassCount(numClasses: Int) {
        val expected = charset.length + 1
        if (numClasses != expected) {
            throw ModelContractException(
                "Model emits $numClasses classes, implying ${numClasses - 1} characters; the " +
                    "bundled charset has ${charset.length}, which needs $expected (one CTC blank " +
                    "plus one per character). Refusing to decode."
            )
        }
    }

    /**
     * Run inference on a single preprocessed line tensor.
     *
     * @param lineData Float32 array of shape [TARGET_HEIGHT × TARGET_WIDTH]
     * @return Decoded Mon text string for this line
     */
    suspend fun runInference(lineData: FloatArray): String = withContext(Dispatchers.Default) {
        val caller = currentCoroutineContext()
        synchronized(runtimeLock) {
            caller.ensureActive()
            check(!disposed) { "OCR engine has been disposed" }
            try { infer(lineData)
            } catch (e: OrtException) {
                caller.ensureActive()
                if (!usingNnapi) throw LineInferenceException("CPU inference failed", e)
                usingNnapi = false
                try { ortSession?.close() }
                catch (closeError: OrtException) { MonLogger.e("Failed to close failed NNAPI session", closeError) }
                ortSession = null
                try {
                    ortSession = createSession(checkNotNull(ortEnv), checkNotNull(cachedModel), false)
                    infer(lineData)
                } catch (retry: OrtException) {
                    throw LineInferenceException("Inference failed after CPU retry", retry)
                }
            }
        }
    }

    private fun infer(lineData: FloatArray): String {
        val session = ortSession ?: error("Engine not initialized — call initialize() first.")
        val env     = ortEnv    ?: error("ORT environment not available.")

        val shape = longArrayOf(
            1L,
            1L,
            ImagePreprocessor.TARGET_HEIGHT.toLong(),
            ImagePreprocessor.TARGET_WIDTH.toLong()
        )

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(lineData), shape)

        return tensor.use {
            val inputName = session.inputNames.first()
            val results   = session.run(mapOf(inputName to tensor))
            results.use { output ->
                val outputTensor = output.first().value as OnnxTensor
                // Prefer array() fast path; fall back to bulk get for non-array buffers
                val logits = try {
                    outputTensor.floatBuffer.array()
                } catch (_: UnsupportedOperationException) {
                    FloatArray(outputTensor.floatBuffer.remaining()).also { buf ->
                        outputTensor.floatBuffer.get(buf)
                    }
                }
                val dims       = outputTensor.info.shape          // [1, T, C]
                val timeSteps  = dims[1].toInt()
                val numClasses = dims[2].toInt()
                // Cheap, and it closes the gap the load-time check leaves open when
                // the graph declares the class axis symbolically.
                assertClassCount(numClasses)
                CtcDecoder.decode(logits, timeSteps, numClasses, charset)
            }
        }
    }

    /**
     * Release all ONNX Runtime resources. Called by [OcrRepository.dispose].
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        // ViewModel teardown runs on Main. A native call may finish before its session
        // can close; wait on IO, never block Clear/navigation on the runtime monitor.
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(runtimeLock) {
                try { ortSession?.close() }
                catch (e: Exception) { MonLogger.e("Failed to close OCR session", e) }
                finally {
                    ortSession = null
                    // The environment is process-wide and may serve another repository.
                    ortEnv = null
                }
            }
        }
    }
}
