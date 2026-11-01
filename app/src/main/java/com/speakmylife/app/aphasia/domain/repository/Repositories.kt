package com.speakmylife.app.aphasia.domain.repository

import com.speakmylife.app.aphasia.domain.model.AcousticTemplate
import com.speakmylife.app.aphasia.domain.model.CommunicationPhrase
import com.speakmylife.app.aphasia.domain.model.FeatureSequence
import com.speakmylife.app.aphasia.domain.model.IntentNode
import com.speakmylife.app.aphasia.domain.model.MatchResult
import com.speakmylife.app.aphasia.domain.model.ReconstructedSentence
import com.speakmylife.app.aphasia.domain.model.Utterance
import com.speakmylife.app.aphasia.domain.model.VoiceProfile
import kotlinx.coroutines.flow.Flow

/** 錄音 + 特徵抽取。實作見 data/acoustic（Phase 2）。 */
interface AudioFeatureRepository {
    /** 一次錄音（按下→放開）。回傳 16 kHz 單聲道 PCM。 */
    suspend fun record(maxDurationMs: Long): Utterance

    /** 錄音期間的即時音量，給 UI 畫波形用 */
    val liveAmplitude: Flow<Float>

    fun extractFeatures(utterance: Utterance): FeatureSequence
}

/** 聲學樣板的儲存與比對。 */
interface AcousticTemplateRepository {
    fun observeAll(): Flow<List<AcousticTemplate>>

    /**
     * 由多次登錄錄音建立樣板，並在此校準 rejectionThreshold。
     * @throws IllegalArgumentException 若 takes 少於 [AcousticTemplate.MIN_ENROLLMENT_TAKES]
     */
    suspend fun enroll(label: String, spokenPhrase: String, takes: List<Utterance>): AcousticTemplate

    suspend fun delete(id: Long)

    /** 對所有樣板做 DTW 比對。這是整個 Module 1 的熱路徑。 */
    suspend fun match(utterance: Utterance): MatchResult
}

/** 個人語句庫 + 裝置端向量檢索（Local RAG）。 */
interface PhrasebookRepository {
    fun observeAll(context: String? = null): Flow<List<CommunicationPhrase>>
    suspend fun upsert(phrase: CommunicationPhrase): Long
    suspend fun delete(id: Long)
    suspend fun markUsed(id: Long)

    /** 語意檢索，給 LLM 當作重組時的個人化上下文。 */
    suspend fun searchSimilar(query: String, topK: Int = 5): List<CommunicationPhrase>

    /** 視覺化意圖樹（唯讀資源 + 使用者自訂）。 */
    fun observeIntentTree(): Flow<List<IntentNode>>
}

/** 句子重組。可能走裝置端，也可能走雲端，呼叫端不需要知道。 */
interface LanguageModelRepository {
    /**
     * 把破碎的關鍵字重組成完整句子。
     *
     * @param fragments 使用者實際說出/選出的片段，順序即使用者給的順序
     * @param personalContext 由 [PhrasebookRepository.searchSimilar] 取得
     * @return 多個候選，依信心度排序
     */
    suspend fun reconstruct(
        fragments: List<String>,
        personalContext: List<CommunicationPhrase> = emptyList(),
    ): List<ReconstructedSentence>

    /** 文法修飾（Grammar Sync）：保留原意，只補時態、冠詞、介系詞。 */
    suspend fun polishGrammar(text: String): ReconstructedSentence

    /** 語氣調整（Politeness）：同一句話換成適合對象的說法。 */
    suspend fun adjustPoliteness(text: String, level: PolitenessLevel): ReconstructedSentence

    /** 這台裝置目前能不能離線跑 */
    suspend fun isAvailableOffline(): Boolean
}

enum class PolitenessLevel { Casual, Neutral, Formal }

/** 語音合成與嗓音設定。 */
interface SpeechRepository {
    fun observeProfiles(): Flow<List<VoiceProfile>>
    suspend fun activeProfile(): VoiceProfile?
    suspend fun setActiveProfile(id: String)

    /** 說出一句話。回傳實際播出的音檔路徑，失敗回 null。 */
    suspend fun speak(text: String, profile: VoiceProfile? = null): Result<Unit>
    suspend fun stop()
}
