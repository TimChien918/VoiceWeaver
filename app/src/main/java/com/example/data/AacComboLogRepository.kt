package com.example.data

import kotlinx.coroutines.flow.Flow

class AacComboLogRepository(private val dao: AacComboLogDao) {

    fun recent(limit: Int = 100): Flow<List<AacComboLog>> = dao.observeRecent(limit)

    suspend fun log(
        finalSentence: String,
        items: List<AacItem>,
        locationTag: String,
        poiTag: String,
        triggeredEmergency: Boolean
    ): Long {
        val categorySig = items.groupingBy { it.category.key }
            .eachCount()
            .entries.joinToString(",") { "${it.key}:${it.value}" }
        return dao.insert(
            AacComboLog(
                finalSentence = finalSentence,
                itemIdsPipe = items.joinToString("|") { it.id },
                itemLabelsPipe = items.joinToString("|") { it.label },
                categorySig = categorySig,
                locationTag = locationTag,
                poiTag = poiTag,
                triggeredEmergency = triggeredEmergency
            )
        )
    }

    suspend fun setRating(id: Long, rating: Int) = dao.setRating(id, rating.coerceIn(1, 5))

    suspend fun topRatedAt(location: String): List<AacComboLog> =
        dao.topRatedAtLocation(location)
}
