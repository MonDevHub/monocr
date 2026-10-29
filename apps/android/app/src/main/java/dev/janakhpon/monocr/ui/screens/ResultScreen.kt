package dev.janakhpon.monocr.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.janakhpon.monocr.engine.OcrResult
import dev.janakhpon.monocr.ui.theme.monScriptStyle
import dev.janakhpon.monocr.ui.components.PdfPreviewList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.janakhpon.monocr.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
fun ResultView(
    imageUri: Uri,
    result: OcrResult,
    originalUri: Uri? = null,
    fileType: String = "image/jpeg",
    onNavigateToFeedback: (String, Uri?) -> Unit = { _, _ -> },
    onSaved: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    // Save goes where the user chooses, through the system file picker. It used to
    // write to the app's private external folder, which file managers on Android 11+
    // do not show and an uninstall deletes, and it swallowed every failure.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) onSaved(writeTextTo(context, uri, result.text))
    }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var showFullScreen by remember { mutableStateOf(false) }
    val isPdf = fileType == "application/pdf"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Image/PDF preview ──────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clickable { showFullScreen = true },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp, 
                MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isPdf && originalUri != null) {
                    PdfPreviewList(
                        uri = originalUri,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = stringResource(R.string.selected_image),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                }
            }
        }
        
        if (showFullScreen) {
            FullScreenPreviewDialog(
                uri = originalUri ?: imageUri,
                isPdf = isPdf,
                onDismiss = { showFullScreen = false }
            )
        }


        // ── Result card ────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Card header: title + stat chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Announced when the result appears: until now a finished scan said
                    // nothing to TalkBack, only a haptic fired.
                    Text(
                        stringResource(R.string.extracted_text),
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val words = result.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
                        StatChip(stringResource(R.string.label_words, words))
                        StatChip(stringResource(R.string.label_ms, result.durationMs))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Mon text content area — Scrollable with constrained height
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                ) {
                    if (result.text.isBlank()) {
                        Text(
                            stringResource(R.string.no_text_extracted),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = result.text,
                            style = monScriptStyle,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Action toolbar
                if (result.text.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                    ) {
                        ResultActionButton(
                            onClick = { saveLauncher.launch(suggestedFileName()) },
                            icon = Icons.Outlined.Download,
                            label = stringResource(R.string.save_text)
                        )
                        ResultActionButton(
                            onClick = { shareText(context, result.text) },
                            icon = Icons.Outlined.Share,
                            label = stringResource(R.string.share_text)
                        )
                        ResultActionButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                copyToClipboard(context, result.text)
                                scope.launch {
                                    copied = true
                                    delay(2000)
                                    copied = false
                                }
                            },
                            icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            label = if (copied) stringResource(R.string.copied) else stringResource(R.string.copy_text),
                            tint = if (copied) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(40.dp)
    ) {
        // The Text beside it labels the button; a description here too made TalkBack
        // read every action twice.
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clipboard_label), text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
}

private fun suggestedFileName(): String =
    "monocr-${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"

/** Writes [text] as UTF-8 to [uri]; false when the destination could not be written. */
internal fun writeTextTo(context: Context, uri: Uri, text: String): Boolean = try {
    context.contentResolver.openOutputStream(uri, "wt")?.use {
        it.write(text.toByteArray(Charsets.UTF_8))
        true
    } ?: false
} catch (e: Exception) {
    dev.janakhpon.monocr.util.MonLogger.e("saving the result failed", e)
    false
}

@Composable
fun FullScreenPreviewDialog(
    uri: Uri,
    isPdf: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isPdf) {
                    PdfPreviewList(
                        uri = uri,
                        modifier = Modifier.fillMaxSize().padding(vertical = 48.dp)
                    )
                } else {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White
                    )
                }
            }
        }
    }
}
