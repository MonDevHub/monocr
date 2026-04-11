package dev.janakhpon.monocr.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.janakhpon.monocr.engine.OcrRepository
import dev.janakhpon.monocr.engine.OcrResult
import dev.janakhpon.monocr.data.HistoryRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    init {
        viewModelScope.launch {
            try {
                repository.initialize()
                _uiState.value = UiState.Ready
            } catch (e: Exception) {
                _uiState.value = UiState.InitError(
                    e.message ?: "Failed to initialize OCR engine"
                )
            }
        }
    }

    fun onImageSelected(uri: Uri, bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            _uiState.value = UiState.Processing(uri)
            try {
                val result = repository.performOcr(bitmap)
                _uiState.value = UiState.Success(uri, result, uri, "image/jpeg")
                repository.saveToHistory(
                    fileName = uri.lastPathSegment ?: "scan",
                    fileType = "image/jpeg",
                    text = result.text,
                    durationMs = result.durationMs,
                    category = "ocr-scan",
                    fileUri = uri.toString()
                )
            } catch (e: Exception) {
                _uiState.value = UiState.OcrError(uri, e.message ?: "OCR processing failed")
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun onPdfSelected(context: android.content.Context, uri: Uri, previewUri: Uri?) {
        viewModelScope.launch {
            _uiState.value = UiState.Processing(previewUri ?: uri)
            try {
                val result = repository.performMultiPageOcr(context, uri)
                _uiState.value = UiState.Success(previewUri ?: uri, result, uri, "application/pdf")
                repository.saveToHistory(
                    fileName = uri.lastPathSegment ?: "document.pdf",
                    fileType = "application/pdf",
                    text = result.text,
                    durationMs = result.durationMs,
                    category = "ocr-scan",
                    fileUri = uri.toString()
                )
            } catch (e: Exception) {
                _uiState.value = UiState.OcrError(previewUri ?: uri, e.message ?: "PDF processing failed")
            }
        }
    }

    fun onError(uri: Uri, message: String) {
        _uiState.value = UiState.OcrError(uri, message)
    }

    fun deleteHistoryRecord(id: Long) {
        viewModelScope.launch { repository.deleteHistoryRecord(id) }
    }

    fun clearHistory(category: String) {
        viewModelScope.launch { repository.clearHistory(category) }
    }

    fun reset() {
        if (repository.isEngineReady) {
            _uiState.value = UiState.Ready
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.dispose()
    }
}

