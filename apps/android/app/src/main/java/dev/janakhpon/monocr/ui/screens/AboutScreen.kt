package dev.janakhpon.monocr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.FileProvider
import android.content.Intent
import dev.janakhpon.monocr.util.MonLogger
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.janakhpon.monocr.R
import androidx.compose.ui.res.stringResource

private data class InfoRow(val label: String, val value: String)

@Composable
fun AboutScreen(
    onMenuClick: () -> Unit,
    onNavigateToDocs: () -> Unit,
    onNavigateToContribute: () -> Unit,
    onNavigateToFeedback: () -> Unit,
    onNavigateToPrivacy: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            dev.janakhpon.monocr.ui.components.MonTopAppBar(
                title = stringResource(R.string.nav_about),
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InfoCard(
                title = stringResource(R.string.about_overview),
                body  = stringResource(R.string.about_overview_desc)
            )

            ModelInfoCard()

            InfoCard(
                title = stringResource(R.string.about_lang_support),
                body  = stringResource(R.string.about_lang_support_desc)
            )

            // FIX F13: LinksCard now uses clean Row-based links with external icon
            LinksCard(
                onLinkClick = { url -> uriHandler.openUri(url) },
                onPrivacyClick = onNavigateToPrivacy
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Internal app links for consistency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onNavigateToDocs) {
                    Text(stringResource(R.string.nav_docs), style = MaterialTheme.typography.labelMedium)
                }
                Text(" • ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                TextButton(onClick = onNavigateToContribute) {
                    Text(stringResource(R.string.nav_contribute), style = MaterialTheme.typography.labelMedium)
                }
                Text(" • ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                TextButton(onClick = onNavigateToFeedback) {
                    Text(stringResource(R.string.nav_feedback), style = MaterialTheme.typography.labelMedium)
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(
                    onClick = {
                        val logFile = MonLogger.getLogFile(context)
                        if (logFile.exists()) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.about_export_logs_chooser)))
                        } else {
                            android.widget.Toast.makeText(context, context.getString(R.string.about_no_logs), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.about_export_logs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "MIT License · © 2026 Janakhpon",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModelInfoCard() {
    // Read off the bundled artifact, not off a spec sheet. app/src/main/assets/
    // monocr.onnx is 26,342,200 bytes of FP32 = 26.3 MB decimal, matching
    // README.md and the web app's own download string. Decimal MB, not MiB:
    // this figure previously read ~25 MB here and 26.3 MB in the web UI, which
    // is the same file measured two ways and reads as a contradiction.
    //
    // Three of these rows were wrong until 2026-08-15. Precision read FP16 and
    // size read ~13 MB, both describing a quantised export this app has never
    // shipped. "Val CER 2.79%" was worse, and it is not an invented number:
    // mon_OCR's AUDIT-2026-08.md F-07 records it as that repository's own README
    // figure, reported as a beam-decode column beside 1.52% greedy for a code
    // path that could not produce two different numbers, because beam silently
    // ran greedy. It was retracted there and went on shipping here. Removed
    // rather than replaced with the v2 checkpoint's 2.5%, which was measured on
    // a split that shared its typefaces with training.
    val rows = listOf(
        InfoRow("Architecture", "MobileNetV3 + BiLSTM-384 + CTC"),
        InfoRow("Parameters",   "~6.6M"),
        InfoRow("Input",        "128 × 1024 px"),
        InfoRow("Precision",    "FP32"),
        InfoRow("Model size",   "26.3 MB"),
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.about_model_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            rows.forEachIndexed { idx, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (idx < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun LinksCard(
    onLinkClick: (String) -> Unit,
    onPrivacyClick: () -> Unit
) {
    // FIX F13: Replaced TextButton with inner Row (ambiguous tap zone) with
    //           direct TextButton using Arrangement.SpaceBetween — clean, clear affordance
    data class Link(val label: String, val url: String)
    val links = listOf(
        Link("Hugging Face Models",  "https://huggingface.co/janakhpon/monocr"),
        Link("monocr-web (GitHub)",  "https://github.com/MonDevHub/monocr-web"),
        Link("NPM Package",          "https://www.npmjs.com/package/monocr"),
        Link("PyPI Package",         "https://pypi.org/project/monocr-onnx/"),
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.about_resources_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            
            // Native Privacy Policy Link
            TextButton(
                onClick = onPrivacyClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Privacy Policy, opens in-app" }
            ) {
                Text(
                    stringResource(R.string.privacy_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew, // Or use a different icon for internal
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            }
            
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            
            links.forEachIndexed { idx, link ->
                TextButton(
                    onClick = { onLinkClick(link.url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "${link.label}, opens in browser" }
                ) {
                    Text(
                        link.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
                if (idx < links.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}
