package com.speakmylife.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 4 格故事訓練的單一場景。Emoji 當縮圖佔位（避免依賴外部圖庫，
 * 後續可換成 `drawableRes: Int` 或 `imageUrl: String`）。
 */
data class StoryFrame(val emoji: String, val hint: String)

data class StoryPrompt(
    val id: String,
    val title: String,
    val frames: List<StoryFrame>,
    /** 給 AI 評論用的「期望關鍵字」，命中越多分數越高 */
    val expectedKeywords: List<String>
) {
    init { require(frames.size == 4) { "Story must have exactly 4 frames" } }
}

/**
 * 預設故事庫（檳榔攤情境 + 通用日常）。
 * - 「賣檳榔給客人」測雙向交易語意
 * - 「不舒服 → 找家人」測情緒+緊急路徑
 * - 「廟口買飲料」測 POI 推薦
 */
val DefaultStoryPrompts: List<StoryPrompt> = listOf(
    StoryPrompt(
        id = "story_betel_sell",
        title = "賣一包檳榔給客人",
        frames = listOf(
            StoryFrame("🏪", "客人走到攤位"),
            StoryFrame("🌿", "拿出包葉檳榔"),
            StoryFrame("💵", "客人付 100 元"),
            StoryFrame("🤲", "找零 50 元給客人")
        ),
        expectedKeywords = listOf("檳榔", "客人", "一百", "找零", "謝謝")
    ),
    StoryPrompt(
        id = "story_feel_sick",
        title = "覺得不舒服，找家人幫忙",
        frames = listOf(
            StoryFrame("😣", "胸口悶悶的"),
            StoryFrame("📞", "拿起電話"),
            StoryFrame("🏥", "決定去醫院"),
            StoryFrame("👨‍👩‍👧", "家人陪同")
        ),
        expectedKeywords = listOf("不舒服", "家人", "醫院", "陪", "幫忙")
    ),
    StoryPrompt(
        id = "story_temple_drink",
        title = "廟口買一杯結冰水",
        frames = listOf(
            StoryFrame("⛩️", "經過廟口"),
            StoryFrame("🥤", "看到飲料攤"),
            StoryFrame("💵", "拿出 50 元"),
            StoryFrame("😊", "喝到結冰水")
        ),
        expectedKeywords = listOf("廟", "結冰水", "飲料", "五十", "謝謝")
    )
)

/**
 * Room：故事訓練的紀錄。
 * - `score` 0–100 由 AI 評論（或 fallback 用關鍵字命中率）給出
 * - `aiFeedback` 是 AI 的正向回饋字串，列在歷史頁讓使用者重溫
 */
@Entity(tableName = "story_training_logs")
data class StoryTrainingLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val storyId: String,
    val userResponse: String,
    val score: Int,
    val aiFeedback: String,
    val positiveWords: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface StoryTrainingLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: StoryTrainingLog): Long

    @Query("SELECT * FROM story_training_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<StoryTrainingLog>>

    @Query("SELECT * FROM story_training_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun between(from: Long, to: Long): List<StoryTrainingLog>

    @Query("DELETE FROM story_training_logs")
    suspend fun clearAll()
}
