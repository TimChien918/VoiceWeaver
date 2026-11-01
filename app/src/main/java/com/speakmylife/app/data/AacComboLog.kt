package com.speakmylife.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AAC 圖文組合區「工作記憶」單筆紀錄。
 *
 * 寫入時機：使用者在組合區按下 ✔️ 接受、AI/TTS 唸完整句、並完成 1–5 星評分後，
 * 由 [AacComboLogRepository.insert] 落地一筆。星等代表「這次組句是否真的傳達了想說的」，
 * 之後可丟給 AI 作為下一次推薦排序依據（高星圖卡序列 → 更高權重）。
 *
 * 設計重點：
 * - 把 itemIds（圖卡 id 用 `|` 串接）拆開存，方便日後做 N-gram 推薦
 * - 同場景（locationTag/poiTag）+ 高星 → 排在推薦最前面
 * - rating 設 0 代表「尚未評分」（避免讓 nullable 把查詢搞複雜）
 */
@Entity(tableName = "aac_combo_logs")
data class AacComboLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 使用者實際確認念出的整句（AI 串好的自然句） */
    val finalSentence: String,
    /** 原始圖卡序列的 id，用 `|` 串接（保持順序） */
    val itemIdsPipe: String,
    /** 原始圖卡序列的 label，用 `|` 串接（為了人類可讀的歷史頁） */
    val itemLabelsPipe: String,
    /** 來源類別計數（products/money/actions/emotions/emergency） */
    val categorySig: String,
    /** GPS 反查的地點標籤（e.g. "檳榔攤", "廟宇"），無則 "" */
    val locationTag: String = "",
    /** Google Places POI 名稱（e.g. "南天宮"），無則 "" */
    val poiTag: String = "",
    /** 1–5 星，0 = 尚未評分 */
    val rating: Int = 0,
    /** 是否觸發過緊急通報（Telegram 發出） */
    val triggeredEmergency: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
