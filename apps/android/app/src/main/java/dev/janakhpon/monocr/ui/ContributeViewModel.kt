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
 * ViewModel scoped to the Contribute screen.
 * Owns contribution history and the save action, keeping MainViewModel focused on OCR state.
 */
class ContributeViewModel(private val repository: OcrRepository) : ViewModel() {

    val contributionHistory: StateFlow<List<HistoryRecord>> = repository.getContributionHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveContribution(
        context: Context,
        fileName: String,
        text: String,
        sourceUri: Uri? = null
    ) {
        viewModelScope.launch {
            repository.saveToHistory(
                fileName = fileName,
                fileType = sourceUri?.let { context.contentResolver.getType(it) } ?: "",
                text = text,
                durationMs = 0L,
                category = "contribution",
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
        viewModelScope.launch { repository.clearHistory("contribution") }
    }
}
