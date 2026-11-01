package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeechRecordDao {
    @Query("SELECT * FROM speech_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<SpeechRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: SpeechRecord): Long

    @Query("UPDATE speech_records SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Int, isFavorite: Boolean)

    @Query("DELETE FROM speech_records WHERE id = :id")
    suspend fun deleteRecord(id: Int)

    @Query("DELETE FROM speech_records")
    suspend fun deleteAll()
}
