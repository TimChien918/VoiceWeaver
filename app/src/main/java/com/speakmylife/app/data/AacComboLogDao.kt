package com.speakmylife.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AacComboLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AacComboLog): Long

    @Query("UPDATE aac_combo_logs SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int)

    @Query("SELECT * FROM aac_combo_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun between(from: Long, to: Long): List<AacComboLog>

    @Query("DELETE FROM aac_combo_logs")
    suspend fun clearAll()
}
