package dev.janakhpon.monocr.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.janakhpon.monocr.R
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.janakhpon.monocr.ui.OcrFailure
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext

@Composable
fun InitErrorView(message: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.error_engine_load),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** The one message the screen shows for [failure]. */
@Composable
internal fun ocrFailureMessage(failure: OcrFailure): String = when (failure) {
    is OcrFailure.FileTooLarge -> stringResource(R.string.error_file_large, failure.limitMb)
    OcrFailure.UnreadableFile -> stringResource(R.string.error_file_unreadable)
    OcrFailure.UnreadableImage -> stringResource(R.string.error_corrupt)
    OcrFailure.PdfNoPages -> stringResource(R.string.error_pdf_no_pages)
    OcrFailure.OutOfMemory -> stringResource(R.string.error_memory)
    OcrFailure.ModelRuntime -> stringResource(R.string.error_model_runtime)
    is OcrFailure.Unknown -> stringResource(R.string.error_generic_ocr)
}

/** monocr-cli is the route for a file over the cap and for a device the model fails on. */
internal fun OcrFailure.suggestsCli(): Boolean =
    this is OcrFailure.FileTooLarge || this == OcrFailure.ModelRuntime

@Composable
fun OcrErrorView(imageUri: Uri, failure: OcrFailure, onReset: () -> Unit, onViewDocs: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = imageUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.error_ocr_failed),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    ocrFailureMessage(failure),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                if (failure.suggestsCli()) {
                    TextButton(
                        onClick = onViewDocs,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            stringResource(R.string.view_cli_guide),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            content = {
                Text(stringResource(R.string.process_another))
            }
        )
    }
}
