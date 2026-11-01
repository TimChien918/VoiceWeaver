package com.speakmylife.app.aphasia.domain.model

/**
 * 個人語句庫的一筆。既是 UI 上的快捷句，也是 Local RAG 的檢索單位。
 */
data class CommunicationPhrase(
    val id: Long = 0,
    val text: String,
    /** 情境標籤（clinic / restaurant / home…），用來縮小檢索範圍 */
    val context: String? = null,
    /** 句向量。null 表示尚未編碼，檢索時要略過而不是當成零向量。 */
    val embedding: FloatArray? = null,
    val useCount: Int = 0,
    val lastUsedMillis: Long = 0L,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CommunicationPhrase && id == other.id && text == other.text)

    override fun hashCode(): Int = id.hashCode() * 31 + text.hashCode()
}

/**
 * 視覺化意圖樹的一個節點。
 *
 * 給打字困難的使用者用：一路點下去就能組出句子，不需要輸入任何字。
 */
data class IntentNode(
    val id: String,
    val label: String,
    /** 選了這一節之後累加到句子上的片段 */
    val fragment: String,
    val iconKey: String? = null,
    val children: List<IntentNode> = emptyList(),
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

/**
 * LLM 重組的候選輸出。
 *
 * 一律回傳多個候選讓使用者選——單一答案在失語症情境下等於替使用者發言，
 * 而模型在片段輸入上的錯誤率高到不該這樣做。
 */
data class ReconstructedSentence(
    val text: String,
    val confidence: Float,
    /** 模型為何這樣重組的簡短說明，讓照顧者能判斷是否可信 */
    val rationale: String? = null,
)

/** 使用者的合成語音設定。 */
data class VoiceProfile(
    val id: String,
    val displayName: String,
    /** GPT-SoVITS 參考音檔在伺服器端的路徑 */
    val referenceAudioPath: String? = null,
    /** 參考音檔的逐字稿。與 referenceAudioPath 必須成對——拆開就會產生怪聲。 */
    val referencePrompt: String? = null,
    val speedFactor: Float = 1.0f,
)
