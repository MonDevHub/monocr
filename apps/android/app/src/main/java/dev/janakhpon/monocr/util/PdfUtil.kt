package dev.janakhpon.monocr.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import dev.janakhpon.monocr.engine.UnreadablePdfException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfUtil {

    /**
     * Renders the first page of a PDF file to a Bitmap.
     *
     * @param context Application context
     * @param uri URI of the PDF file
     * @return Bitmap of the first page, or null if rendering fails
     */
    suspend fun renderPdfPageToBitmap(context: Context, uri: Uri, pageIndex: Int = 0, scale: Float = 4.16f): Bitmap? {
        var allocated: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return@withContext null
                        renderer.openPage(pageIndex).use { page ->
                            require(scale > 0 && scale.isFinite()) { "Invalid PDF render scale" }
                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt(),
                                (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            allocated = bitmap
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            allocated?.recycle()
            throw e
        } catch (e: Exception) {
            allocated?.recycle()
            MonLogger.e("Failed to render PDF page", e)
            return null
        }
    }

    /**
     * Gets the total number of pages in a PDF file.
     */
    suspend fun getPageCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw UnreadablePdfException("The PDF could not be opened: no file descriptor.")
        fd.use {
            val renderer = try {
                PdfRenderer(it)
            } catch (e: SecurityException) {
                throw UnreadablePdfException("The PDF is password-protected.", e)
            } catch (e: java.io.IOException) {
                throw UnreadablePdfException("The PDF is corrupt or not a PDF.", e)
            }
            renderer.use { r ->
                if (r.pageCount <= 0) throw dev.janakhpon.monocr.engine.EmptyPdfException()
                r.pageCount
            }
        }
    }

    /**
     * Checks if a URI points to a PDF file based on its mime type.
     */
    fun isPdf(context: Context, uri: Uri): Boolean {
        return context.contentResolver.getType(uri) == "application/pdf" ||
                uri.path?.endsWith(".pdf", ignoreCase = true) == true
    }
}
