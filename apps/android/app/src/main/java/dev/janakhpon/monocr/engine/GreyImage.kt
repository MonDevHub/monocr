package dev.janakhpon.monocr.engine

/**
 * A grayscale page or crop held as plain data: one 0..255 value per pixel, row-major.
 *
 * Segmentation, polarity normalisation and tiling all used to take an
 * [android.graphics.Bitmap], which put every pixel decision behind a class that
 * only exists on a device. The unit tests for that arithmetic could not run off
 * a device, so they did not run at all. The arithmetic lives on this type now and
 * the bitmap handling is a thin wrapper, which is what makes it testable on the JVM.
 *
 * [IntArray] rather than [ByteArray] because every consumer here compares against
 * 0..255 thresholds; a signed byte would need masking at each read for no memory
 * win worth the noise, and the segmenter already allocates several buffers this size.
 */
class GreyImage(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width >= 0 && height >= 0) { "grey image cannot have a negative side: ${width}x$height" }
        require(pixels.size == width * height) {
            "grey buffer is ${pixels.size} long but ${width}x$height needs ${width * height}"
        }
    }

    fun at(x: Int, y: Int): Int = pixels[y * width + x]

    /**
     * A copy of the `[x, x + w) x [y, y + h)` window. The window must lie inside
     * the image; a caller that clamps silently is a caller that reads the wrong
     * pixels and cannot tell.
     */
    fun crop(x: Int, y: Int, w: Int, h: Int): GreyImage {
        require(x >= 0 && y >= 0 && w >= 0 && h >= 0) { "crop origin and size must be non-negative" }
        require(x + w <= width && y + h <= height) {
            "crop ${w}x$h at ($x, $y) does not fit a ${width}x$height image"
        }
        val out = IntArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(pixels, (y + row) * width + x, out, row * w, w)
        }
        return GreyImage(w, h, out)
    }

    companion object {
        /**
         * ITU-R BT.601 luma, the same weights PIL's `convert("L")` uses, so a page
         * normalised here matches what the training pipeline saw.
         *
         * Converts in place and takes ownership of [argb]: the caller must not read it
         * afterwards. A 300 DPI A4 page is 8.4 million pixels, so a second buffer here
         * is 33 MB on a device that is also holding the source bitmap and the model.
         */
        fun fromArgbInPlace(argb: IntArray, width: Int, height: Int): GreyImage {
            for (i in argb.indices) {
                val px = argb[i]
                val r = (px shr 16 and 0xFF).toFloat()
                val g = (px shr 8 and 0xFF).toFloat()
                val b = (px and 0xFF).toFloat()
                argb[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            }
            return GreyImage(width, height, argb)
        }
    }
}
