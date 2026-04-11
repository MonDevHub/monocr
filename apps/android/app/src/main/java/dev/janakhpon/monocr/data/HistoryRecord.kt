package dev.janakhpon.monocr.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "history_records")
data class HistoryRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String,
    val fileType: String,
    val text: String,
    val processingTime: Int,
    val category: String = "ocr-scan",
    val fileUri: String? = null,
    val syncId: String = UUID.randomUUID().toString(),
    val isSynced: Boolean = false,
    val syncAttempts: Int = 0,
    val syncError: String? = null
)
