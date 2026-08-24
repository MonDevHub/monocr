package dev.janakhpon.monocr.engine

import dev.janakhpon.monocr.util.MonLogger

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
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

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
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

    val isInitialized: Boolean get() = ortSession != null

    /**
     * Load model and charset from assets. Call once before [runInference].
     * Safe to call multiple times — no-op if already initialized.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        // Load charset
        try {
            charset = context.assets.open("charset.txt").bufferedReader(Charsets.UTF_8).readText()
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

        if (!modelFile.exists()) {
            context.assets.open("monocr.onnx").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        val sessionOpts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            // FIX C2: NNAPI is not available on all devices/emulators.
            // Gracefully fall back to the CPU provider if addNnapi() throws.
            try {
                addNnapi()
            } catch (_: OrtException) {
                // No NNAPI — ORT will use default CPU provider automatically
            }
        }

        MonLogger.i("Creating ONNX session from ${modelFile.name}...")
        val session = env.createSession(modelFile.absolutePath, sessionOpts)
        try {
            assertModelContract(session)
        } catch (e: Throwable) {
            // A session that fails the contract must not stay open and must not stay
            // reachable: half-initialised, the next runInference would use it.
            session.close()
            throw e
        }
        ortSession = session
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
        val session = ortSession ?: error("Engine not initialized — call initialize() first.")
        val env     = ortEnv    ?: error("ORT environment not available.")

        val shape = longArrayOf(
            1L,
            1L,
            ImagePreprocessor.TARGET_HEIGHT.toLong(),
            ImagePreprocessor.TARGET_WIDTH.toLong()
        )

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(lineData), shape)

        try {
            tensor.use {
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
        } catch (e: OrtException) {
            // An NNAPI or driver-level abort used to be swallowed and returned as "",
            // so a device that failed on every line produced a page that looked empty
            // rather than broken. Report it and let the caller decide.
            throw LineInferenceException("line inference failed in the ONNX runtime", e)
        }
    }

    /**
     * Release all ONNX Runtime resources. Called by [OcrRepository.dispose].
     */
    fun dispose() {
        ortSession?.close(); ortSession = null
        ortEnv?.close();     ortEnv = null
    }
}
