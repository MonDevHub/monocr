package dev.janakhpon.monocr.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    onNavigateToFeedback: (String, Uri?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var showFullScreen by remember { mutableStateOf(false) }
    val isPdf = fileType == "application/pdf"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp) // Reduced from 16
    ) {
        // ── Image/PDF preview ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp) // Reduced from 240
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { showFullScreen = true }
        ) {
            if (isPdf && originalUri != null) {
                PdfPreviewList(
                    uri = originalUri,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Processed image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
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
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Card header: title + stat chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp), // Reduced from 16/12
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.extracted_text),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val words = result.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
                        StatChip(stringResource(R.string.label_words, words))
                        StatChip(stringResource(R.string.label_chars, result.text.length))
                        StatChip(stringResource(R.string.label_ms, result.durationMs))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Mon text content area — Scrollable with constrained height
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp) // Reduced from 400
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp) // Reduced from 16
                ) {
                    if (result.text.isBlank()) {
                        Text(
                            stringResource(R.string.no_text_extracted),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    } else {
                        Text(
                            text = result.text,
                            style = monScriptStyle,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // FIX F5: Action toolbar replaces bare IconButtons with labeled TextButtons
                // — much clearer affordance, thumb-reachable, scannable at a glance
                if (result.text.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp), // Tighter toolbar
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
                    ) {
                        // Save
                        TextButton(onClick = { saveTextToFile(context, result.text) }) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = stringResource(R.string.save_text),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.save_text),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Share
                        TextButton(onClick = { shareText(context, result.text) }) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.share_text),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Copy — with visual feedback
                        TextButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            copyToClipboard(context, result.text)
                            scope.launch {
                                copied = true
                                delay(2000)
                                copied = false
                            }
                        }) {
                            Icon(
                                if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                contentDescription = if (copied) "Copied" else "Copy",
                                modifier = Modifier.size(16.dp),
                                tint = if (copied) MaterialTheme.colorScheme.tertiary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (copied) stringResource(R.string.copied) else stringResource(R.string.copy_text),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (copied) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Report Issue
                        TextButton(onClick = {
                            onNavigateToFeedback(result.text, originalUri ?: imageUri)
                        }) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = "Report",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.action_report),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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

private fun saveTextToFile(context: Context, text: String) {
    try {
        val sdf = SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.getDefault())
        val filename = "monocr-${sdf.format(Date())}.txt"
        val file = File(context.getExternalFilesDir(null), filename)
        file.writeText(text, Charsets.UTF_8)

        @Suppress("SpellCheckingInspection")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_file_chooser_title)))
    } catch (_: Exception) { /* ignore */ }
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
