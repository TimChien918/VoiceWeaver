package com.speakmylife.app.data

import kotlinx.coroutines.flow.Flow

class RehabLogRepository(private val rehabLogDao: RehabLogDao) {
    val allLogs: Flow<List<RehabLog>> = rehabLogDao.getAll()
    val locationStats: Flow<List<LocationScoreStat>> = rehabLogDao.getLocationStats()

    suspend fun insert(log: RehabLog): Long = rehabLogDao.insert(log)
    suspend fun clearAll() = rehabLogDao.deleteAll()
}
