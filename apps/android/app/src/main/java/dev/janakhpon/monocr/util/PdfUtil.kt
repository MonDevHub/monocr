package dev.janakhpon.monocr.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
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
    suspend fun renderPdfPageToBitmap(context: Context, uri: Uri, pageIndex: Int = 0, scale: Float = 4.16f): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (pageIndex >= renderer.pageCount) return@withContext null

                    renderer.openPage(pageIndex).use { page ->
                        // 300 DPI for OCR (scale ≈ 4.16), or lower for preview (scale ≈ 1.5)
                        val width = (page.width * scale).toInt()
                        val height = (page.height * scale).toInt()

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE) // Ensure white background
                        
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            MonLogger.e("Failed to render PDF page", e)
            null
        }
    }

    /**
     * Gets the total number of pages in a PDF file.
     */
    suspend fun getPageCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.pageCount
                }
            } ?: 0
        } catch (e: Exception) {
            MonLogger.e("Failed to get PDF page count", e)
            0
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
