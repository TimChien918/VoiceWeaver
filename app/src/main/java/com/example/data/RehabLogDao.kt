package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 各場景平均分統計（供進度視覺化） */
data class LocationScoreStat(
    val locationTag: String,
    val avgScore: Double,
    val count: Int
)

@Dao
interface RehabLogDao {
    @Query("SELECT * FROM rehab_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<RehabLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: RehabLog): Long

    @Query("DELETE FROM rehab_logs")
    suspend fun deleteAll()

    /** 依場景分組統計平均分與次數 */
    @Query("SELECT locationTag, AVG(score) AS avgScore, COUNT(*) AS count FROM rehab_logs GROUP BY locationTag ORDER BY avgScore DESC")
    fun getLocationStats(): Flow<List<LocationScoreStat>>

    /** ReportAggregator 用：拿區間內所有紀錄做時間序列聚合 */
    @Query("SELECT * FROM rehab_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun between(from: Long, to: Long): List<RehabLog>
}
