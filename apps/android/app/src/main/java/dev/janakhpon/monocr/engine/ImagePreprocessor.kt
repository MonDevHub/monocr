package dev.janakhpon.monocr.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Preprocesses a line strip from a bitmap for OCR inference.
 *
 * Ported from monocr-onnx.ts processLine():
 *  - Target: [1, 1, 128, 1024] Float32 tensor (Batch, Channel, Height, Width)
 *  - Scale to TARGET_HEIGHT, pad right with white
 *  - Normalize: pixel / 127.5 - 1.0
 */
object ImagePreprocessor {

    const val TARGET_HEIGHT = 128
    const val TARGET_WIDTH = 1024

    /**
     * Extract and preprocess a line segment from [source] at [segment].
     * Returns a Float32Array of shape [1, 1, TARGET_HEIGHT, TARGET_WIDTH].
     *
     * Includes adaptive inversion (ported from monocr-ios):
     * If the mean luminance of the active region is < 120, the segment is
     * assumed to be light text on a dark background. It is inverted so the
     * model always receives dark text on a white canvas.
     */
    suspend fun processLine(source: Bitmap, segment: LineSegment): FloatArray =
        withContext(Dispatchers.Default) {
            val sy = segment.y
            val sh = minOf(segment.height, source.height - sy)
            val sx = segment.x
            val sw = minOf(segment.width, source.width - sx)

            // Scale to fit TARGET_HEIGHT, capped at TARGET_WIDTH
            val scale = TARGET_HEIGHT.toFloat() / sh
            val scaledWidth = minOf((sw * scale).toInt(), TARGET_WIDTH)

            // Create white canvas at target dimensions and draw the scaled crop
            val canvas = androidx.core.graphics.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            canvas.eraseColor(Color.WHITE)
            
            val canvasG = Canvas(canvas)
            // FILTER_BITMAP_FLAG = bilinear interpolation during scaling (matches iOS)
            // ANTI_ALIAS_FLAG  = sub-pixel smoothing on scaled text edges
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

            val srcRect = Rect(sx, sy, sx + sw, sy + sh)
            val dstRect = Rect(0, 0, scaledWidth, TARGET_HEIGHT)
            canvasG.drawBitmap(source, srcRect, dstRect, paint)

            val pixels = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
            canvas.getPixels(pixels, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)
            canvas.recycle()

            // Pass 1: Convert pixels → grayscale float + compute mean for inversion check
            val float32 = FloatArray(TARGET_WIDTH * TARGET_HEIGHT)
            var sumGray = 0.0
            var activeCount = 0

            for (i in float32.indices) {
                val px = pixels[i]
                val r = (px shr 16 and 0xFF).toFloat()
                val g = (px shr 8  and 0xFF).toFloat()
                val b = (px        and 0xFF).toFloat()
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                float32[i] = gray
                if (i % TARGET_WIDTH < scaledWidth) {
                    sumGray += gray
                    activeCount++
                }
            }

            // Adaptive inversion: if dark background, invert FIRST before computing
            // stretch statistics — matches the web training pipeline exactly.
            val meanGray = if (activeCount > 0) sumGray / activeCount else 255.0
            val shouldInvert = meanGray < 120.0

            if (shouldInvert) {
                for (i in float32.indices) {
                    if (i % TARGET_WIDTH < scaledWidth) {
                        float32[i] = 255f - float32[i]
                    }
                }
            }

            // Pass 2: Compute min/max on already-inverted active region
            // (must match web: stretch calibrated to post-inversion values)
            var minG = 255f
            var maxG = 0f
            for (i in float32.indices) {
                if (i % TARGET_WIDTH < scaledWidth) {
                    val g = float32[i]
                    if (g < minG) minG = g
                    if (g > maxG) maxG = g
                }
            }
            val rangeG = maxG - minG
            val applyStretch = rangeG > 30f
            val stretchScale = if (applyStretch) 255f / rangeG else 1f
            val stretchOffset = if (applyStretch) minG else 0f

            // Pass 3: Apply stretch + normalize to [-1, 1]
            for (i in float32.indices) {
                val x = i % TARGET_WIDTH
                if (x < scaledWidth) {
                    if (applyStretch) float32[i] = (float32[i] - stretchOffset) * stretchScale
                    float32[i] = float32[i] / 127.5f - 1.0f
                } else {
                    float32[i] = 1.0f  // padded region: normalized white
                }
            }

            float32
        }
}
