package dev.janakhpon.monocr.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import dev.janakhpon.monocr.util.sharedImageUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentTest {
    private val photo = Uri.parse("content://media/external/images/media/42")

    private fun share(type: String?, stream: Uri? = photo) = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
    }

    @Test fun `an image share yields the shared image`() {
        assertEquals(photo, sharedImageUri(share("image/jpeg")))
    }

    @Test fun `a share that carries the image only in its clip data is still read`() {
        val intent = share("image/png", stream = null).apply {
            clipData = ClipData.newRawUri("", photo)
        }
        assertEquals(photo, sharedImageUri(intent))
    }

    @Test fun `launcher starts, text shares and missing streams are not image shares`() {
        assertNull(sharedImageUri(null))
        assertNull(sharedImageUri(Intent(Intent.ACTION_MAIN)))
        assertNull(sharedImageUri(share("text/plain")))
        assertNull(sharedImageUri(share("image/jpeg", stream = null)))
    }
}
