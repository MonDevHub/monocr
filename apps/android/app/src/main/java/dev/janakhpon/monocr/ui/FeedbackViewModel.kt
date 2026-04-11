package dev.janakhpon.monocr.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.janakhpon.monocr.data.HistoryRecord
import dev.janakhpon.monocr.engine.OcrRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel scoped to the Feedback screen.
 * Owns feedback history and the save action, keeping MainViewModel focused on OCR state.
 */
class FeedbackViewModel(private val repository: OcrRepository) : ViewModel() {

    val feedbackHistory: StateFlow<List<HistoryRecord>> = repository.getFeedbackHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveFeedback(
        context: Context,
        fileName: String,
        text: String,
        type: String,
        sourceUri: Uri? = null,
        originalText: String = ""
    ) {
        viewModelScope.launch {
            // 1. Local Logging
            repository.saveToHistory(
                fileName = fileName,
                fileType = sourceUri?.let { context.contentResolver.getType(it) } ?: "",
                text = "[$type] $text",
                durationMs = 0L,
                category = "feedback",
                fileUri = sourceUri?.toString()
            )
            // Trigger immediate sync
            dev.janakhpon.monocr.engine.SyncService.getInstance(context).triggerSync()
        }
    }

    fun deleteHistoryRecord(id: Long) {
        viewModelScope.launch { repository.deleteHistoryRecord(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory("feedback") }
    }
}
