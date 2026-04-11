package dev.janakhpon.monocr.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    uiState: UiState,
    viewModel: MainViewModel,
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
            scope.launch { loadAndProcess(context, it, viewModel) } 
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { 
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            scope.launch { loadAndProcess(context, it, viewModel) } 
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { uri ->
                scope.launch { loadAndProcess(context, uri, viewModel) }
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
    val scrollState = rememberScrollState()
    val showHistoryResultDialogState = remember { mutableStateOf<HistoryRecord?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    if (showLanguagePicker) {
        LanguagePickerDialog(onDismiss = { showLanguagePicker = false })
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        "MonOCR",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Version ${dev.janakhpon.monocr.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_language)) },
                    selected = false,
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showLanguagePicker = true
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Outlined.Translate, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_docs)) },
                    selected = false,
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToDocs()
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_contribute)) },
                    selected = false,
                    onClick = { 
                        onNavigateToContribute("")
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Outlined.Handshake, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_feedback)) },
                    selected = false,
                    onClick = { 
                        onNavigateToFeedback("", null)
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Outlined.Feedback, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_privacy)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToPrivacy()
                    },
                    icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_about)) },
                    selected = false,
                    onClick = { 
                        onNavigateToAbout()
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp), // Reduced from 24/16
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Hero header
            if (uiState !is UiState.Success && uiState !is UiState.OcrError) {
                HeroHeader(uiState = uiState, onMenuClick = { scope.launch { drawerState.open() } })
                Spacer(modifier = Modifier.height(12.dp)) // Reduced from 28
            }

        // ─── Main content area ─────────────────────────────────────────
        AnimatedContent(
            targetState = uiState,
            transitionSpec = {
                (fadeIn(tween(250)) + slideInVertically { it / 12 }) togetherWith
                        fadeOut(tween(180))
            },
            label = "main_content"
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
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

        Spacer(modifier = Modifier.height(16.dp))

        // History Section
        Spacer(modifier = Modifier.height(12.dp))
        HistorySection(
            title = stringResource(R.string.history_title),
            history = scanHistory,
            onDelete = { viewModel.deleteHistoryRecord(it) },
            onClearAll = { viewModel.clearHistory("ocr-scan") },
            onItemClick = { record ->
                showHistoryResultDialogState.value = record
            }
        )
        Spacer(modifier = Modifier.height(40.dp))
    }

    showHistoryResultDialogState.value?.let { record ->
        HistoryResultDialog(
            record = record,
            onDismiss = { showHistoryResultDialogState.value = null }
        )
    }


    // Floating Action Button for Reload — with entry scale transition
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
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp)
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

// ─── Helpers ──────────────────────────────────────────────────────────────────

private suspend fun loadAndProcess(context: Context, uri: Uri, viewModel: MainViewModel) {
    val fileSize = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    } catch (_: Exception) {
        0L
    }

    if (fileSize > 50 * 1024 * 1024L) {
        viewModel.onError(uri, context.getString(R.string.error_file_large, 50))
        return
    }

    if (PdfUtil.isPdf(context, uri)) {
        val previewBitmap = PdfUtil.renderPdfPageToBitmap(context, uri, 0)
        var previewUri: Uri? = null
        if (previewBitmap != null) {
            val (previewFile, fUri) = saveBitmapToCache(context, previewBitmap)
            previewUri = fUri
            previewBitmap.recycle()
            // Delete the temp preview file — it was only needed to pass to the ViewModel as a URI
            previewFile?.delete()
        }
        viewModel.onPdfSelected(context, uri, previewUri)
        return
    }

    val bitmap = withContext(Dispatchers.IO) {
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
            if (decoded != null) rotateImageIfRequired(context, decoded, uri) else null
        }
    } ?: return
    viewModel.onImageSelected(uri, bitmap)
}

private fun rotateImageIfRequired(context: Context, img: Bitmap, selectedImage: Uri): Bitmap {
    val input = context.contentResolver.openInputStream(selectedImage) ?: return img
    val ei = ExifInterface(input)
    val orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(img, 90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(img, 180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(img, 270f)
        else -> img
    }
}

private fun rotateImage(img: Bitmap, degree: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degree)
    val rotatedImg = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
    img.recycle()
    return rotatedImg
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
