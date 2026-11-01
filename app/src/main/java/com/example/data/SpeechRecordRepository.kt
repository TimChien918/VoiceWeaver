package com.example.data

import kotlinx.coroutines.flow.Flow

class SpeechRecordRepository(private val speechRecordDao: SpeechRecordDao) {
    val allRecords: Flow<List<SpeechRecord>> = speechRecordDao.getAllRecords()

    suspend fun insert(record: SpeechRecord): Long {
        return speechRecordDao.insertRecord(record)
    }

    suspend fun updateFavorite(id: Int, isFavorite: Boolean) {
        speechRecordDao.updateFavoriteStatus(id, isFavorite)
    }

    suspend fun delete(id: Int) {
        speechRecordDao.deleteRecord(id)
    }

    suspend fun clearAll() {
        speechRecordDao.deleteAll()
    }
}
