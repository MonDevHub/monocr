package dev.janakhpon.monocr.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.janakhpon.monocr.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import dev.janakhpon.monocr.ui.FeedbackViewModel
import dev.janakhpon.monocr.ui.components.DashedUploadBox
import dev.janakhpon.monocr.ui.components.HistorySection
import dev.janakhpon.monocr.ui.components.SectionHeader
import dev.janakhpon.monocr.ui.theme.monScriptStyle
import dev.janakhpon.monocr.ui.components.PdfPreviewList
import dev.janakhpon.monocr.util.FileUtil
import dev.janakhpon.monocr.ui.components.HistoryResultDialog
import dev.janakhpon.monocr.data.HistoryRecord
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import android.content.Intent

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    viewModel: FeedbackViewModel,
    originalText: String = "",
    sourceUriDefault: Uri? = null,
    onBack: () -> Unit
) {
    val feedbackHistory by viewModel.feedbackHistory.collectAsState()
    var correctedText by remember { mutableStateOf(originalText) }
    var selectedType by remember { mutableStateOf("Spelling") }
    var consent by remember { mutableStateOf(false) }
    var sourceUri by remember { mutableStateOf<Uri?>(sourceUriDefault) }
    val previewRecordState = remember { mutableStateOf<HistoryRecord?>(null) }
    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { 
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            sourceUri = it 
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_feedback), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp), // Reduced from 20.dp
            verticalArrangement = Arrangement.spacedBy(12.dp) // Reduced from 24.dp
        ) {
            // ── Original Source Section ──────────────────
            SectionHeader(stringResource(R.string.feedback_source_title))
            DashedUploadBox(
                title = sourceUri?.let { FileUtil.getFileName(context, it) } ?: stringResource(R.string.feedback_upload_prompt),
                subtitle = stringResource(R.string.feedback_upload_subtitle),
                onClick = {
                    pickerLauncher.launch(arrayOf("image/*", "application/pdf"))
                }
            )

            // Current File Preview
            sourceUri?.let { uri ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp) // Reduced from 120.dp
                        .clip(RoundedCornerShape(8.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = borderStroke()
                ) {
                    val type = context.contentResolver.getType(uri) ?: ""
                    val isImage = type.startsWith("image/")
                    if (isImage) {
                        AsyncImage(
                            model = uri,
                            contentDescription = stringResource(R.string.selected_scan),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        PdfPreviewList(
                            uri = uri,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            // ── Original Output Section (if available) ────
            if (originalText.isNotBlank()) {
                SectionHeader(stringResource(R.string.feedback_original_output))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = borderStroke()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) { // Reduced from 16.dp
                        Text(
                            text = "\"$originalText\"",
                            style = monScriptStyle.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.feedback_help_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // ── Corrected Text Section ────────────────────
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(stringResource(R.string.feedback_corrected_title))
                    Text(
                        stringResource(R.string.feedback_human_verified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = correctedText,
                    onValueChange = { correctedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.feedback_corrected_placeholder), fontSize = 13.sp) },
                    textStyle = monScriptStyle,
                    shape = RoundedCornerShape(4.dp)
                )
            }

            // ── Error Type Section ────────────────────────
            Column {
                SectionHeader(stringResource(R.string.feedback_error_type))
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val types = listOf(
                        stringResource(R.string.feedback_error_spelling) to "Spelling",
                        stringResource(R.string.feedback_error_layout) to "Layout",
                        stringResource(R.string.feedback_error_formatting) to "Formatting",
                        stringResource(R.string.feedback_error_other) to "Other"
                    )
                    types.forEach { (label, value) ->
                        FilterChip(
                            selected = selectedType == value,
                            onClick = { selectedType = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(2.dp) // Reduced from 4.dp
                        )
                    }
                }
            }

            // ── Consent & Submit Section ──────────────────
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = consent,
                        onCheckedChange = { consent = it },
                        modifier = Modifier.padding(top = 0.dp)
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            stringResource(R.string.feedback_consent_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.feedback_consent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { 
                        viewModel.saveFeedback(
                            context = context,
                            fileName = sourceUri?.let { FileUtil.getFileName(context, it) } ?: "feedback_file",
                            text = correctedText,
                            type = selectedType,
                            sourceUri = sourceUri,
                            originalText = originalText
                        )
                        correctedText = ""
                        sourceUri = null
                    },
                    modifier = Modifier.fillMaxWidth().height(36.dp), // Reduced from 50.dp
                    enabled = correctedText.isNotBlank() && consent,
                    shape = RoundedCornerShape(4.dp) // Reduced from 8.dp
                ) {
                    Text(stringResource(R.string.feedback_submit))
                }
                
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.feedback_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(8.dp))

                HistorySection(
                    title = stringResource(R.string.feedback_history_title),
                    history = feedbackHistory,
                    onDelete = { viewModel.deleteHistoryRecord(it) },
                    onClearAll = { viewModel.clearHistory() },
                    onItemClick = { previewRecordState.value = it }
                )

                Spacer(modifier = Modifier.height(12.dp)) // Reduced from 20.dp
            }
        }
    }

    previewRecordState.value?.let { record ->
        HistoryResultDialog(
            record = record,
            onDismiss = {
                previewRecordState.value = null
            }
        )
    }

}

@Composable
private fun borderStroke() = androidx.compose.foundation.BorderStroke(
    1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
)
