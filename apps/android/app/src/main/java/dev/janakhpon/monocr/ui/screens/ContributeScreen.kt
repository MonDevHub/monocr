package dev.janakhpon.monocr.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.janakhpon.monocr.R
import androidx.compose.ui.res.stringResource
import dev.janakhpon.monocr.ui.ContributeViewModel
import dev.janakhpon.monocr.ui.components.DashedUploadBox
import dev.janakhpon.monocr.ui.components.HistorySection
import dev.janakhpon.monocr.ui.components.SectionHeader
import dev.janakhpon.monocr.ui.components.PdfPreviewList
import dev.janakhpon.monocr.ui.theme.monScriptStyle
import dev.janakhpon.monocr.util.FileUtil
import androidx.compose.ui.platform.LocalContext
import dev.janakhpon.monocr.ui.components.HistoryResultDialog
import dev.janakhpon.monocr.data.HistoryRecord
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import android.content.Intent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributeScreen(
    viewModel: ContributeViewModel,
    initialText: String = "",
    onMenuClick: () -> Unit
) {
    val contributionHistory by viewModel.contributionHistory.collectAsState()
    var transcription by remember { mutableStateOf(initialText) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
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
            dev.janakhpon.monocr.ui.components.MonTopAppBar(
                title = stringResource(R.string.nav_contribute),
                onMenuClick = onMenuClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Hero Header ───────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.contribute_hero_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(2.dp)) // Reduced from 4.dp
                Text(
                    stringResource(R.string.contribute_hero_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp) // Reduced from 16.dp
                )
            }

            // ── Upload Section ────────────
            DashedUploadBox(
                title = if (sourceUri != null) FileUtil.getFileName(context, sourceUri!!) ?: stringResource(R.string.file_selected) else stringResource(R.string.contribute_upload_title),
                subtitle = stringResource(R.string.contribute_upload_subtitle),
                onClick = { 
                    pickerLauncher.launch(
                        arrayOf(
                            "image/*", 
                            "application/pdf", 
                            "text/plain",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        )
                    ) 
                }
            )

            // Current File Preview
            sourceUri?.let { uri ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    val isImage = context.contentResolver.getType(uri)?.startsWith("image/") ?: false
                    if (isImage) {
                        AsyncImage(
                            model = uri,
                            contentDescription = stringResource(R.string.selected_image),
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

            Spacer(modifier = Modifier.height(12.dp)) // Reduced from 24.dp
            // ── Divider ───────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Text(
                    stringResource(R.string.label_or),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 12.dp), // Reduced from 16.dp
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }

            // ── Textarea ──────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(stringResource(R.string.contribute_type_title))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = transcription,
                    onValueChange = { transcription = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text(stringResource(R.string.contribute_type_placeholder), fontSize = 13.sp) },
                    textStyle = monScriptStyle,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // ── Action ────────────────────
            Button(
                onClick = { 
                    viewModel.saveContribution(
                        context,
                        sourceUri?.let { FileUtil.getFileName(context, it) } ?: "mon_text",
                        transcription,
                        sourceUri
                    )
                    transcription = ""
                    sourceUri = null
                },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                enabled = transcription.isNotBlank() || sourceUri != null,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.contribute_submit), fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            HistorySection(
                title = stringResource(R.string.contribute_history_title),
                history = contributionHistory,
                onDelete = { viewModel.deleteHistoryRecord(it) },
                onClearAll = { viewModel.clearHistory() },
                onItemClick = { previewRecordState.value = it }
            )

            Spacer(modifier = Modifier.height(12.dp)) // Reduced from 20.dp
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
