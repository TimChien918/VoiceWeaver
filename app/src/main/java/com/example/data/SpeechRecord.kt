package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speech_records")
data class SpeechRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val originalInput: String,
    val reconstructedText: String,
    val confidence: String,
    val locationTag: String,
    val visionTag: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)
