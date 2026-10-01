package dev.janakhpon.monocr.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.janakhpon.monocr.engine.OcrRepository
import dev.janakhpon.monocr.engine.OcrResult
import dev.janakhpon.monocr.engine.SegmentationMode
import dev.janakhpon.monocr.data.HistoryRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

sealed class UiState {
    data object Initializing : UiState()
    data class InitError(val message: String) : UiState()
    data object Ready : UiState()
    data class Processing(val imageUri: Uri) : UiState()
    data class Success(val imageUri: Uri, val result: OcrResult, val originalUri: Uri? = null, val fileType: String = "image/jpeg") : UiState()
    data class OcrError(val imageUri: Uri, val message: String) : UiState()
}

/**
 * Top-level ViewModel for the OCR pipeline.
 * Owns only: engine init state, scan history, and active scan/pdf processing.
 * Contribution and feedback state live in their own dedicated ViewModels.
 */
class MainViewModel(private val repository: OcrRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Initializing)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val scanHistory: StateFlow<List<HistoryRecord>> = repository.getScanHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The mode the last run used. Set from where the image came from, then overridable
     * by the user. Deliberately not inferred from the model's own output: the upstream
     * docs record 0.83 confidence on a fabricated reading, so confidence cannot decide
     * whether segmentation worked.
     */
    private val _segmentationMode = MutableStateFlow(SegmentationMode.PAGE)
    val segmentationMode: StateFlow<SegmentationMode> = _segmentationMode.asStateFlow()

    /**
     * The image the mode control can re-run against, or null when there is nothing to
     * re-run — before the first scan, or after a PDF, which is always read page mode.
     */
    private val _rerunnableImage = MutableStateFlow<Uri?>(null)
    val rerunnableImage: StateFlow<Uri?> = _rerunnableImage.asStateFlow()

    private val generations = ScanGeneration()
    private var scanJob: Job? = null
    private var currentPreviewFile: File? = null

    private fun releasePreview() {
        currentPreviewFile?.delete()
        currentPreviewFile = null
    }

    fun beginImport(uri: Uri): Long {
        val token = generations.next()
        scanJob?.cancel()
        releasePreview()
        _uiState.value = UiState.Processing(uri)
        _rerunnableImage.value = null
        return token
    }

    fun isCurrentImport(token: Long): Boolean = generations.isCurrent(token)

    /**
     * Releases a selection that was cancelled before it handed ownership to a scan job.
     * Invalidating the token also prevents delayed decode/preview work from publishing.
     */
    fun cancelImport(token: Long) {
        if (!generations.cancelIfCurrent(token)) return
        releasePreview()
        _rerunnableImage.value = null
        _uiState.value = UiState.Ready
    }

    init {
        viewModelScope.launch {
            try {
                repository.initialize()
                _uiState.value = UiState.Ready
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _uiState.value = UiState.InitError(
                    e.message ?: "Failed to initialize OCR engine"
                )
            }
        }
    }

    fun onImageSelected(uri: Uri, bitmap: android.graphics.Bitmap, mode: SegmentationMode, token: Long = beginImport(uri)) {
        if (!generations.isCurrent(token)) { bitmap.recycle(); return }
        scanJob = viewModelScope.launch {
            _uiState.value = UiState.Processing(uri)
            _segmentationMode.value = mode
            _rerunnableImage.value = uri
            try {
                val result = repository.performOcr(bitmap, mode)
                if (!generations.isCurrent(token)) return@launch
                _uiState.value = UiState.Success(uri, result, uri, "image/jpeg")
                // The result is already on screen. A new import cancels this job, and
                // that must not also drop the history row for a scan the user saw.
                withContext(NonCancellable) {
                    repository.saveToHistory(
                        fileName = uri.lastPathSegment ?: "scan",
                        fileType = "image/jpeg",
                        text = result.text,
                        durationMs = result.durationMs,
                        category = "ocr-scan",
                        fileUri = uri.toString(),
                        rawText = result.rawText,
                        warningSummary = result.warningSummary()
                    )
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generations.isCurrent(token))
                    _uiState.value = UiState.OcrError(uri, e.message ?: "OCR processing failed")
            }
        }.also { job -> job.invokeOnCompletion { bitmap.recycle() } }
    }

    fun onPdfSelected(context: android.content.Context, uri: Uri, previewUri: Uri?, token: Long = beginImport(uri), previewFile: File? = null) {
        if (!generations.isCurrent(token)) { previewFile?.delete(); return }
        currentPreviewFile = previewFile
        scanJob = viewModelScope.launch {
            _uiState.value = UiState.Processing(previewUri ?: uri)
            // A PDF render is dense text by construction, and the pages are not
            // re-readable from a single bitmap, so no mode choice is offered.
            _segmentationMode.value = SegmentationMode.PAGE
            _rerunnableImage.value = null
            try {
                val result = repository.performMultiPageOcr(context, uri)
                if (!generations.isCurrent(token)) return@launch
                _uiState.value = UiState.Success(previewUri ?: uri, result, uri, "application/pdf")
                // As for images: a later import must not cancel this insert.
                withContext(NonCancellable) {
                    repository.saveToHistory(
                        fileName = uri.lastPathSegment ?: "document.pdf",
                        fileType = "application/pdf",
                        text = result.text,
                        durationMs = result.durationMs,
                        category = "ocr-scan",
                        fileUri = uri.toString(),
                        rawText = result.rawText,
                        warningSummary = result.warningSummary()
                    )
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generations.isCurrent(token))
                    _uiState.value = UiState.OcrError(previewUri ?: uri, e.message ?: "PDF processing failed")
            }
        }
    }

    fun onError(uri: Uri, message: String, token: Long = beginImport(uri)) {
        if (!generations.isCurrent(token)) return
        _uiState.value = UiState.OcrError(uri, message)
    }

    fun deleteHistoryRecord(id: Long) {
        viewModelScope.launch { repository.deleteHistoryRecord(id) }
    }

    fun clearHistory(category: String) {
        viewModelScope.launch { repository.clearHistory(category) }
    }

    fun reset() {
        generations.next()
        scanJob?.cancel()
        releasePreview()
        if (repository.isEngineReady) {
            _uiState.value = UiState.Ready
            _rerunnableImage.value = null
        }
    }

    override fun onCleared() {
        generations.next()
        scanJob?.cancel()
        releasePreview()
        super.onCleared()
        repository.dispose()
    }
}
