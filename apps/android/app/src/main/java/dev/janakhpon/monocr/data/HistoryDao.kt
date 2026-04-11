package dev.janakhpon.monocr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HistoryRecord)

    @Query("SELECT * FROM history_records WHERE category = :category ORDER BY timestamp DESC")
    fun getRecordsByCategory(category: String): Flow<List<HistoryRecord>>

    @Query("DELETE FROM history_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history_records WHERE category = :category")
    suspend fun clearCategory(category: String)

    @Query("SELECT * FROM history_records WHERE isSynced = 0 AND syncAttempts < 5 AND (category = 'contribution' OR category = 'feedback' OR category = 'contribute')")
    suspend fun getUnsyncedRecords(): List<HistoryRecord>

    @androidx.room.Update
    suspend fun update(record: HistoryRecord)
}
