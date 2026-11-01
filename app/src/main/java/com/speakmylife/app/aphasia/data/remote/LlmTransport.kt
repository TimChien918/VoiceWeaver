package com.speakmylife.app.aphasia.data.remote

/**
 * 「把一段 prompt 送出去、拿回一段文字」——僅此而已。
 *
 * 刻意把介面切到這麼窄：新的失語症引擎要自己掌握 **prompt 與輸出契約**
 *（這是產品邏輯，也是規格要求重寫的部分），但供應商排序、失敗換家、
 * HTTP 組裝、金鑰管理這些傳輸層的東西既有實作已經能跑，重寫只會製造回歸。
 *
 * 這個介面也讓 [com.speakmylife.app.aphasia.data.repository.LanguageModelRepositoryImpl]
 * 可以完全不碰網路就被測試——prompt 與解析才是會出錯的地方。
 */
interface LlmTransport {

    /**
     * @param timeoutMs 單次呼叫上限。備援換家會各自重新計時，所以總時間可能是這個值的數倍。
     * @return 模型的原始回應文字。失敗時帶著原始例外，呼叫端才有辦法分辨
     *   「額度用完」和「沒網路」——這兩者要對使用者說的話完全不同。
     */
    suspend fun complete(
        system: String,
        user: String,
        temperature: Double = 0.3,
        maxTokens: Int = 512,
        timeoutMs: Long = 20_000,
    ): Result<String>

    /** 這台裝置現在能不能不靠網路完成一次 [complete]。 */
    suspend fun isOfflineCapable(): Boolean

    /** 給日誌與 UI 顯示用的名稱，例如「雲端多家備援」「裝置端 Qwen2.5」。 */
    val label: String
}
