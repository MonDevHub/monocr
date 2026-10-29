package dev.janakhpon.monocr.util

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The image another app shared to MonOCR, or null when [intent] is not an image share.
 *
 * The manifest registers MainActivity for `ACTION_SEND` of any image type; this is the
 * half of that contract which reads what was sent. The URI is readable for as long as
 * the receiving activity is, so it is read straight away and never persisted: a shared
 * URI usually grants no persistable permission.
 */
fun sharedImageUri(intent: Intent?): Uri? {
    if (intent?.action != Intent.ACTION_SEND) return null
    if (intent.type?.startsWith("image/") != true) return null
    return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
}
