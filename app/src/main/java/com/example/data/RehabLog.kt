package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Task 5.3: 情境復健日誌。記錄每次跟讀練習的場景、目標句與得分，供治療師/家屬追蹤進度。
 */
@Entity(tableName = "rehab_logs")
data class RehabLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val locationTag: String,
    val targetSentence: String,
    val score: Int
)
