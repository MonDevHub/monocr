package dev.janakhpon.monocr.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import dev.janakhpon.monocr.engine.UnreadablePdfException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A provider that has no descriptor for the file, as when access was withdrawn mid-import. */
class NoDescriptorProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = "application/pdf"
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = null
}

@RunWith(RobolectricTestRunner::class)
class PdfUtilTest {
    @Test fun `a pdf with no file descriptor raises the typed error with a specific message`() {
        Robolectric.setupContentProvider(NoDescriptorProvider::class.java, "pdftest")
        val context = RuntimeEnvironment.getApplication()
        try {
            runBlocking { PdfUtil.getPageCount(context, Uri.parse("content://pdftest/doc.pdf")) }
            fail("expected UnreadablePdfException")
        } catch (e: UnreadablePdfException) {
            assertTrue(e.message!!.contains("no file descriptor"))
            assertEquals("The PDF could not be opened: no file descriptor.", e.message)
        }
    }
}
