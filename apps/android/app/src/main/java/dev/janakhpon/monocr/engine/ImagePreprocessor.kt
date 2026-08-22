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
 *  - Target: [1, 1, 160, 1024] Float32 tensor (Batch, Channel, Height, Width)
 *  - Scale to TARGET_HEIGHT, pad right with white
 *  - Normalize: pixel / 127.5 - 1.0
 */
object ImagePreprocessor {

    const val TARGET_HEIGHT = 160
    const val TARGET_WIDTH = 1024

    /**
     * Render a normalised grey page as a bitmap, so the scaling below can use
     * Canvas's bilinear filter — the same resampler the iOS and web preprocessors
     * use, which is why it is worth a conversion rather than a hand-written
     * resampler here.
     *
     * Converts [page]'s buffer in place and takes ownership of it: the caller must
     * finish segmenting and tiling before calling this. A separate ARGB buffer would
     * be another 33 MB on a 300 DPI A4 page, on top of the bitmap it feeds.
     */
    fun toBitmapConsuming(page: GreyImage): Bitmap {
        val argb = page.pixels
        for (i in argb.indices) {
            val v = argb[i]
            argb[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = androidx.core.graphics.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, page.width, 0, 0, page.width, page.height)
        return bitmap
    }

    /**
     * Extract and preprocess a line segment from [normalizedPage] at [segment].
     * Returns a Float32Array of shape [1, 1, TARGET_HEIGHT, TARGET_WIDTH].
     *
     * [normalizedPage] must already be dark ink on white — [PageNormalizer] does
     * that once for the whole page. There is deliberately no polarity check here.
     * The old per-line `meanGray < 120` inversion ran after segmentation, so on an
     * inverted page the segmenter had already found the background and called it
     * text; and because the page-level normalisation is not idempotent, keeping both
     * would invert twice on exactly the inputs that need it.
     */
    suspend fun processLine(normalizedPage: Bitmap, segment: LineSegment): FloatArray =
        withContext(Dispatchers.Default) {
            val sy = segment.y
            val sh = minOf(segment.height, normalizedPage.height - sy)
            val sx = segment.x
            val sw = minOf(segment.width, normalizedPage.width - sx)

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
            canvasG.drawBitmap(normalizedPage, srcRect, dstRect, paint)

            val pixels = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
            canvas.getPixels(pixels, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)
            canvas.recycle()

            // Pass 1: Convert pixels → grayscale float
            val float32 = FloatArray(TARGET_WIDTH * TARGET_HEIGHT)

            for (i in float32.indices) {
                val px = pixels[i]
                val r = (px shr 16 and 0xFF).toFloat()
                val g = (px shr 8  and 0xFF).toFloat()
                val b = (px        and 0xFF).toFloat()
                float32[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            // Pass 2: Compute min/max on the active region for the contrast stretch
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
