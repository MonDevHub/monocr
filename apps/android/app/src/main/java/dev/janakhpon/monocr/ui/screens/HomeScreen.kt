package dev.janakhpon.monocr.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.data.HistoryRecord
import dev.janakhpon.monocr.engine.ImagePreprocessor
import dev.janakhpon.monocr.engine.SegmentationMode
import dev.janakhpon.monocr.ui.MainViewModel
import dev.janakhpon.monocr.ui.UiState
import dev.janakhpon.monocr.ui.components.HeroHeader
import dev.janakhpon.monocr.ui.components.HistoryResultDialog
import dev.janakhpon.monocr.ui.components.HistorySection
import dev.janakhpon.monocr.ui.components.InitErrorView
import dev.janakhpon.monocr.ui.components.InitializingView
import dev.janakhpon.monocr.ui.components.LanguagePickerDialog
import dev.janakhpon.monocr.ui.components.OcrErrorView
import dev.janakhpon.monocr.ui.components.PickerView
import dev.janakhpon.monocr.ui.components.ProcessingView
import dev.janakhpon.monocr.ui.components.SkeletonResultCard
import dev.janakhpon.monocr.util.PdfUtil
import dev.janakhpon.monocr.util.orientBitmap
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    uiState: UiState,
    viewModel: MainViewModel,
    onMenuClick: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDocs: () -> Unit,
    onNavigateToContribute: (String) -> Unit,
    onNavigateToFeedback: (String, Uri?) -> Unit,
    onNavigateToPrivacy: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(uiState) {
        if (uiState is UiState.Success) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            scope.launch { loadAndProcess(context, it, viewModel, chooseMode = ::galleryModeFor) }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // loadAndProcess routes a PDF to the multi-page reader before it reaches
            // the image path, so this mode is never consulted; it says what a PDF
            // would get anyway.
            scope.launch {
                loadAndProcess(context, it, viewModel) { SegmentationMode.PAGE }
            }
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { uri ->
                // A camera capture is a photo of a slide, a poster or a sign far more
                // often than it is a book page, and those need the sparse threshold.
                scope.launch {
                    loadAndProcess(context, uri, viewModel) { SegmentationMode.SPARSE }
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = createCameraUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val scanHistory by viewModel.scanHistory.collectAsState()
    val segmentationMode by viewModel.segmentationMode.collectAsState()
    val rerunnableImage by viewModel.rerunnableImage.collectAsState()
    val scrollState = rememberScrollState()
    val showHistoryResultDialogState = remember { mutableStateOf<HistoryRecord?>(null) }
    
    Scaffold(
        topBar = {
            dev.janakhpon.monocr.ui.components.MonTopAppBar(
                title = "MonOCR",
                onMenuClick = onMenuClick
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Hero header
                if (uiState !is UiState.Success && uiState !is UiState.OcrError) {
                    HeroHeader(uiState = uiState)
                }

                // ── Main content area ─────────────────────────────────────────
                AnimatedContent(
                    targetState = uiState,
                    transitionSpec = {
                        (fadeIn(tween(250)) + slideInVertically { it / 12 }) togetherWith
                                fadeOut(tween(180))
                    },
                    label = "main_content",
                    modifier = Modifier.fillMaxWidth()
                ) { state ->
                    when (state) {
                        is UiState.Initializing -> InitializingView()
                        is UiState.InitError    -> InitErrorView(state.message)
                        is UiState.Ready        -> PickerView(
                            onGallery = { galleryLauncher.launch(arrayOf("image/*")) },
                            onPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
                            onCamera = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    val uri = createCameraUri(context)
                                    cameraUri = uri
                                    cameraLauncher.launch(uri)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                        is UiState.Processing   -> {
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                ProcessingView(imageUri = state.imageUri)
                                SkeletonResultCard()
                            }
                        }
                        is UiState.Success      -> ResultView(
                            imageUri  = state.imageUri,
                            result    = state.result,
                            originalUri = state.originalUri,
                            fileType = state.fileType,
                            onNavigateToFeedback = onNavigateToFeedback
                        )
                        is UiState.OcrError     -> OcrErrorView(
                            imageUri = state.imageUri,
                            message  = state.message,
                            onReset  = viewModel::reset,
                            onViewDocs = onNavigateToDocs
                        )
                    }
                }

                // Line detection control. Offered after a run, not before: the useful
                // moment to change it is when the reading came back merged or split,
                // and re-running is one tap. Not switched automatically — the upstream
                // docs record 0.83 confidence on a fabricated reading, so the model's
                // own certainty cannot tell us segmentation went wrong.
                rerunnableImage?.let { imageUri ->
                    AnimatedVisibility(
                        visible = uiState is UiState.Success || uiState is UiState.OcrError
                    ) {
                        SegmentationModeControl(
                            selected = segmentationMode,
                            onSelect = { mode ->
                                scope.launch { loadAndProcess(context, imageUri, viewModel) { mode } }
                            }
                        )
                    }
                }

                // Warnings also belong to PDF results, which have no rerun control.
                (uiState as? UiState.Success)?.result?.let { result ->
                    if (result.captureLooksSoft) {
                        Text(stringResource(R.string.capture_soft_warning), color = MaterialTheme.colorScheme.error)
                    }
                    if (result.blockShapedLineCount > 0) {
                        Text(stringResource(R.string.segmentation_block_warning, result.blockShapedLineCount),
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (result.failedLineCount > 0) {
                        Text(stringResource(R.string.segmentation_failed_lines, result.failedLineCount),
                            color = MaterialTheme.colorScheme.error)
                    }
                    result.warningSummary(includeCaptureWarnings = false)?.let { warning ->
                        Text(warning, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                // History Section
                HistorySection(
                    title = stringResource(R.string.history_title),
                    history = scanHistory,
                    onDelete = { viewModel.deleteHistoryRecord(it) },
                    onClearAll = { viewModel.clearHistory("ocr-scan") },
                    onItemClick = { record ->
                        showHistoryResultDialogState.value = record
                    }
                )
                
                Spacer(modifier = Modifier.height(64.dp)) // Bottom spacing for FAB
            }

            showHistoryResultDialogState.value?.let { record ->
                HistoryResultDialog(
                    record = record,
                    onDismiss = { showHistoryResultDialogState.value = null }
                )
            }

            // Floating Action Button
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState is UiState.Success || uiState is UiState.OcrError,
                enter = androidx.compose.animation.expandIn(expandFrom = Alignment.Center) + fadeIn(),
                exit = androidx.compose.animation.shrinkOut(shrinkTowards = Alignment.Center) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.reset()
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.process_another),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// ─── Sub-composables extracted to ui/components/ ─────────────────────────────

/**
 * Lets the user pick how the page is split into lines, with the mode the run
 * actually used preselected.
 *
 * There is no single right threshold — a fraction of mean row density that separates
 * book lines sits below the noise floor of a photograph — so this is a choice the
 * user can see and change, not a hidden constant.
 */
@Composable
private fun SegmentationModeControl(
    selected: SegmentationMode,
    onSelect: (SegmentationMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.segmentation_mode_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentationMode.entries.forEach { mode ->
                    val label = when (mode) {
                        SegmentationMode.PAGE -> R.string.segmentation_mode_page
                        SegmentationMode.SPARSE -> R.string.segmentation_mode_sparse
                        SegmentationMode.LINE -> R.string.segmentation_mode_line
                    }
                    FilterChip(
                        selected = mode == selected,
                        onClick = { if (mode != selected) onSelect(mode) },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
            Text(
                text = stringResource(R.string.segmentation_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * A gallery image is a page unless it is too short to hold two lines, in which case
 * it is a crop of one line and segmenting it would only chop the line up.
 */
private fun galleryModeFor(bitmap: Bitmap): SegmentationMode =
    SegmentationMode.forGalleryImage(bitmap.width, bitmap.height)

/**
 * @param chooseMode picks the segmentation mode from the decoded bitmap. Provenance
 *   lives at the call site, which is the only place that knows whether this came from
 *   the camera, the gallery, or the user re-running with a mode they chose.
 */
internal class ImportPreparationResources {
    var bitmap: Bitmap? = null
    var previewFile: File? = null
}

internal data class PreparedPdfPreview(val uri: Uri?)

internal data class ImportPreparationOps(
    val fileSize: suspend (Context, Uri) -> Long,
    val isPdf: suspend (Context, Uri) -> Boolean,
    val preparePdfPreview: suspend (Context, Uri, ImportPreparationResources) -> PreparedPdfPreview,
    val decodeImage: suspend (Context, Uri, ImportPreparationResources) -> Bitmap?
)

private val defaultImportPreparationOps = ImportPreparationOps(
    fileSize = { context, uri ->
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (_: Exception) {
            0L
        }
    },
    isPdf = PdfUtil::isPdf,
    preparePdfPreview = { context, uri, resources ->
        val previewBitmap = PdfUtil.renderPdfPageToBitmap(context, uri, 0)
        var previewUri: Uri? = null
        if (previewBitmap != null) {
            try {
                val (file, fileUri) = saveBitmapToCache(context, previewBitmap)
                resources.previewFile = file
                previewUri = fileUri
            } finally { previewBitmap.recycle() }
        }
        PreparedPdfPreview(previewUri)
    },
    decodeImage = { context, uri, resources ->
        withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val reqSize = 2048
            var inSampleSize = 1
            val height = options.outHeight
            val width = options.outWidth
            if (height > reqSize || width > reqSize) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqSize && halfWidth / inSampleSize >= reqSize) {
                    inSampleSize *= 2
                }
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val decoded = BitmapFactory.decodeStream(stream, null, options)
                resources.bitmap = decoded
                if (decoded != null) {
                    val upright = rotateImageIfRequired(context, decoded, uri)
                    resources.bitmap = upright
                    if (upright.hasAlpha()) {
                        val opaque = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
                        resources.bitmap = opaque
                        try { Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(upright, 0f, 0f, null) } }
                        finally { upright.recycle() }
                    }
                    resources.bitmap
                } else null
            }
        }
    }
)

internal suspend fun loadAndProcess(
    context: Context,
    uri: Uri,
    viewModel: MainViewModel,
    preparation: ImportPreparationOps = defaultImportPreparationOps,
    chooseMode: (Bitmap) -> SegmentationMode
) {
    val token = viewModel.beginImport(uri)
    val resources = ImportPreparationResources()
    try {
        val fileSize = preparation.fileSize(context, uri)

        if (fileSize > 50 * 1024 * 1024L) {
            viewModel.onError(uri, context.getString(R.string.error_file_large, 50), token)
            return
        }

        if (preparation.isPdf(context, uri)) {
            val preview = preparation.preparePdfPreview(context, uri, resources)
            viewModel.onPdfSelected(context, uri, preview.uri, token, resources.previewFile)
            resources.previewFile = null
            return
        }

        val bitmap = preparation.decodeImage(context, uri, resources)
            ?: throw IllegalArgumentException("This image could not be decoded.")
        viewModel.onImageSelected(uri, bitmap, chooseMode(bitmap), token)
        resources.bitmap = null // The job (or stale-import rejection) now owns recycling.
    } catch (e: CancellationException) {
        viewModel.cancelImport(token)
        throw e
    } catch (e: Exception) {
        viewModel.onError(uri, e.message ?: "The selected file could not be opened.", token)
    } finally {
        resources.bitmap?.let { if (!it.isRecycled) it.recycle() }
        resources.previewFile?.delete()
    }
}

private fun rotateImageIfRequired(context: Context, img: Bitmap, selectedImage: Uri): Bitmap {
    val orientation = context.contentResolver.openInputStream(selectedImage)?.use { input ->
        ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    return orientBitmap(img, orientation)
}

private fun saveBitmapToCache(context: Context, bitmap: Bitmap): Pair<File?, Uri?> {
    return try {
        val file = File(context.cacheDir, "pdf_preview_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        Pair(file, uri)
    } catch (_: Exception) {
        Pair(null, null)
    }
}

private fun createCameraUri(context: Context): Uri {
    val file = File(context.cacheDir, "monocr_capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}
