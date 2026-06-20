package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AacComboLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AacComboLog): Long

    @Query("UPDATE aac_combo_logs SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int)

    @Query("SELECT * FROM aac_combo_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AacComboLog>>

    /**
     * AI 推薦排序用：同地點下，高星序列優先。
     * `rating DESC, timestamp DESC` → 5 星且最近的排第一。
     */
    @Query("""
        SELECT * FROM aac_combo_logs
        WHERE locationTag = :location AND rating >= :minRating
        ORDER BY rating DESC, timestamp DESC
        LIMIT :limit
    """)
    suspend fun topRatedAtLocation(location: String, minRating: Int = 4, limit: Int = 20): List<AacComboLog>

    @Query("SELECT * FROM aac_combo_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun between(from: Long, to: Long): List<AacComboLog>

    @Query("DELETE FROM aac_combo_logs")
    suspend fun clearAll()
}
