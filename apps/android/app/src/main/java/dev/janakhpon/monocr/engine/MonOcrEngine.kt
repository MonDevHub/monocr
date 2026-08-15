package dev.janakhpon.monocr.engine

import dev.janakhpon.monocr.util.MonLogger

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
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
        // `a51be11` is the revision the web app pins and the four monocr-onnx SDKs pin.
        // Bump this in the same change that bumps those, or it stops being an answer.
        const val MODEL_VERSION = "v3.5@d3d9d5e"
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

        // FIX C8: Instead of readBytes() which double-buffers 25MB in JVM heap + Native ORT,
        // we copy the asset once to the cache directory, and load via file path.
        val modelFile = File(context.cacheDir, "monocr.onnx")
        
        // CTO AUDIT: Cleanup legacy fp16 model if it exists to free up space
        val legacyModelFile = File(context.cacheDir, "monocr_fp16.onnx")
        if (legacyModelFile.exists()) {
            legacyModelFile.delete()
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
        ortSession = env.createSession(modelFile.absolutePath, sessionOpts)
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
                    CtcDecoder.decode(logits, timeSteps, numClasses, charset)
                }
            }
        } catch (e: OrtException) {
            // NNAPI/driver-level abort on certain devices — degrade gracefully rather
            // than crashing the entire OCR pipeline. The caller will skip blank lines.
            MonLogger.e("Inference failed for line segment (OrtException). Skipping.", e)
            ""
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
