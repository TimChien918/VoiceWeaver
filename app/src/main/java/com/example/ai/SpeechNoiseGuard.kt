package com.example.ai

/**
 * 擋掉「其實沒說話」被當成說了話的兩種情況。
 *
 * 為什麼這件事對這個 App 特別重要：失語症患者本來就常常「想說但發不出聲音」，
 * 那是最常見的一種嘗試，不是例外。如果這種時候系統回一段他根本沒講過的話、
 * 再配一個分數記進復健日誌，對患者是打擊，對治療師是髒資料。
 *
 * 兩道關卡：
 * 1. [isTooQuiet] / [isTooShort]——錄音本身的音量與長度。這關在上傳前就擋掉，
 *    連 API 都不用打。
 * 2. [looksLikeSilenceHallucination]——Whisper 對靜音／噪音會「幻覺」出完整
 *    句子，這是它已知的行為（訓練資料裡的字幕殘留）。實測錄一段環境噪音會拿到
 *    「ah i know this what's going on? we are going to the bathroom to rest...」
 *    這種毫無來由的長句。常見的固定產物在下面列出來直接擋。
 */
object SpeechNoiseGuard {

    /**
     * 音量門檻（MediaRecorder.getMaxAmplitude 的 0..32767 刻度）。
     *
     * 刻意訂得低。這裡的兩種錯法代價差很多：把真的講話當成沒講話，等於告訴一個
     * 正在努力的人「你沒出聲」——那比偶爾放一段噪音進去嚴重得多。所以寧可漏擋，
     * 不要誤擋；構音微弱的氣音也要能過。
     */
    const val MIN_PEAK_AMPLITUDE = 600

    /** 短於這個長度不可能是一次跟讀嘗試（多半是手誤點兩下）。 */
    const val MIN_DURATION_MS = 500L

    fun isTooQuiet(peakAmplitude: Int): Boolean =
        peakAmplitude in 0 until MIN_PEAK_AMPLITUDE

    fun isTooShort(durationMs: Long): Boolean = durationMs < MIN_DURATION_MS

    /**
     * Whisper 對靜音／純噪音常吐出來的固定句子。多半來自訓練資料裡的字幕尾巴，
     * 跟使用者說了什麼完全無關。
     *
     * 這裡寫成人看得懂的原句，實際比對前會統一過 [normalize]（去標點空白、
     * 轉小寫）——不先正規化的話 "thank you" 永遠比不到 "Thank you." 正規化後的
     * "thankyou"，整張表等於是死的。
     */
    private val HALLUCINATIONS: Set<String> = setOf(
        // 英文
        "thank you", "thanks for watching", "thank you for watching",
        "please subscribe", "subscribe to my channel", "bye", "you", "okay", "ok",
        // 中文（簡繁都列，Whisper 兩種都會吐）
        "謝謝觀看", "谢谢观看", "謝謝大家", "谢谢大家", "謝謝", "谢谢",
        "字幕由 Amara.org 社群提供", "字幕由 Amara.org 社区提供",
        "請不吝點贊訂閱轉發打賞支持明鏡與點點欄目",
        "请不吝点赞订阅转发打赏支持明镜与点点栏目",
        "明鏡與點點欄目", "明镜与点点栏目",
        // 日文／韓文
        "ご視聴ありがとうございました", "ご清聴ありがとうございました",
        "구독과 좋아요 부탁드립니다", "시청해주셔서 감사합니다"
    ).map(::normalize).toSet()

    /** 去標點、去空白、轉小寫，讓比對不受標點影響。 */
    private fun normalize(s: String): String =
        s.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * 這段辨識結果看起來是「沒說話時的幻覺」嗎？
     *
     * @param target 這次的目標句。有它才分得出「使用者真的講了『謝謝』」跟
     *   「Whisper 對著靜音吐出『謝謝』」——目標句本身就是謝謝的話不能擋。
     */
    fun looksLikeSilenceHallucination(recognized: String, target: String): Boolean {
        val r = normalize(recognized)
        if (r.isBlank()) return true
        val t = normalize(target)
        if (t.isNotBlank() && r == t) return false          // 完全唸對，當然不是幻覺
        return r in HALLUCINATIONS && r != t
    }
}
