package dev.janakhpon.monocr.util

import android.graphics.Bitmap
import android.graphics.Matrix

/** Linear part of the eight EXIF source-to-upright transforms; Bitmap supplies translation. */
internal fun exifTransform(orientation: Int): FloatArray = when (orientation) {
    2 -> floatArrayOf(-1f, 0f, 0f, 1f)
    3 -> floatArrayOf(-1f, 0f, 0f, -1f)
    4 -> floatArrayOf(1f, 0f, 0f, -1f)
    5 -> floatArrayOf(0f, 1f, 1f, 0f)
    6 -> floatArrayOf(0f, -1f, 1f, 0f)
    7 -> floatArrayOf(0f, -1f, -1f, 0f)
    8 -> floatArrayOf(0f, 1f, -1f, 0f)
    else -> floatArrayOf(1f, 0f, 0f, 1f)
}

/** Transfers ownership of [image] to the returned upright bitmap. */
internal fun orientBitmap(image: Bitmap, orientation: Int): Bitmap {
    if (orientation !in 2..8) return image
    val t = exifTransform(orientation)
    val matrix = Matrix().apply {
        setValues(floatArrayOf(t[0], t[1], 0f, t[2], t[3], 0f, 0f, 0f, 1f))
    }
    val upright = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
    if (upright !== image) image.recycle()
    return upright
}
