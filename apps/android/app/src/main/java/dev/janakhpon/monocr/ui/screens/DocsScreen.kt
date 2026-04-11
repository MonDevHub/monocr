package dev.janakhpon.monocr.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.janakhpon.monocr.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_docs), style = MaterialTheme.typography.titleLarge) },
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
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // ── Introduction ────────────────
            Column {
                Text(
                    stringResource(R.string.hero_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Installation ────────────────
            DocSection(
                number = "1",
                title = stringResource(R.string.docs_installation),
                icon = Icons.Outlined.RocketLaunch
            ) {
                Text(
                    stringResource(R.string.docs_installation_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CodeBlock("pip install monocr")
            }

            // ── CLI Reference ───────────────
            DocSection(
                number = "2",
                title = stringResource(R.string.docs_cli_reference),
                icon = Icons.Outlined.Terminal
            ) {
                Text(
                    stringResource(R.string.docs_cli_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CodeBlock("monocr read image.png\nmonocr batch folder/")
            }

            // ── SDKs ────────────────────────
            Column {
                DocSection(
                    number = "3",
                    title = stringResource(R.string.docs_sdks),
                    icon = Icons.Outlined.Code
                ) {
                    Text(
                        stringResource(R.string.docs_sdks_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                SdkSwitcher()
            }

            // ── Input Standards ─────────────
            DocSection(
                number = "4",
                title = stringResource(R.string.docs_input_standards),
                icon = Icons.Outlined.HighQuality
            ) {
                Text(
                    stringResource(R.string.docs_input_standards_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QualityCard(
                        icon = Icons.Outlined.PhotoCamera,
                        title = stringResource(R.string.docs_resolution),
                        body = stringResource(R.string.docs_resolution_desc),
                        modifier = Modifier.weight(1f)
                    )
                    QualityCard(
                        icon = Icons.Outlined.LightMode,
                        title = stringResource(R.string.docs_lighting),
                        body = stringResource(R.string.docs_lighting_desc),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Privacy ─────────────────────
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.docs_privacy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.docs_privacy_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ShieldMoon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.docs_privacy_stat),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── Model Hub ───────────────────
            DocSection(
                number = "5",
                title = stringResource(R.string.docs_model_hub),
                icon = Icons.Outlined.CloudDownload
            ) {
                Text(
                    stringResource(R.string.docs_model_hub_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { /* Open link */ },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(stringResource(R.string.docs_visit_hf))
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun DocSection(
    number: String,
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
fun CodeBlock(code: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Text(
            text = code,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

@Composable
fun QualityCard(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        }
    }
}

@Composable
fun SdkSwitcher() {
    val sdks = listOf("JS", "Python", "Go", "Rust")
    var selectedSdk by remember { mutableStateOf("JS") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().padding(1.dp)
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                sdks.forEach { sdk ->
                    val isSelected = selectedSdk == sdk
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clickable { selectedSdk = sdk },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shadowElevation = if (isSelected) 2.dp else 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                sdk,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        AnimatedContent(
            targetState = selectedSdk,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sdk_content"
        ) { sdk ->
            val code = when (sdk) {
                "JS" -> "// 1. Install\nnpm install monocr\n\n// 2. Use\nimport { MonOCR } from 'monocr';\nconst ocr = new MonOCR();\nconst text = await ocr.predict('page.jpg');"
                "Python" -> "# 1. Install\npip install monocr\n\n# 2. Use\nfrom monocr import MonOCR\nocr = MonOCR()\ntext = ocr.predict(\"page.jpg\")"
                "Go" -> "// 1. Install\ngo get github.com/MonDevHub/monocr-onnx/go\n\n// 2. Use\nimport \"ocr\"\nengine, _ := ocr.NewMonOCR(\"\")\ntext, _ := engine.Predict(\"page.jpg\")"
                "Rust" -> "// 1. Install\ncargo add monocr-onnx\n\n// 2. Use\nuse monocr_onnx::MonOCR;\nlet ocr = MonOCR::new(\"monocr.onnx\")?;\nlet text = ocr.predict(\"page.jpg\")?;"
                else -> ""
            }
            CodeBlock(code)
        }
    }
}
