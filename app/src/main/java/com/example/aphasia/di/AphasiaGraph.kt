package com.example.aphasia.di

import android.content.Context
import com.example.ai.HybridInferenceEngine
import com.example.ai.MediaPipeLLMEngine
import com.example.ai.TtsModule
import com.example.aphasia.data.acoustic.AudioCaptureManager
import com.example.aphasia.data.acoustic.MfccExtractor
import com.example.aphasia.data.local.AphasiaDatabase
import com.example.aphasia.data.local.HashingTextEmbedder
import com.example.aphasia.data.local.IntentTreeSource
import com.example.aphasia.data.remote.CloudLlmTransport
import com.example.aphasia.data.remote.FallbackLlmTransport
import com.example.aphasia.data.remote.LlmTransport
import com.example.aphasia.data.remote.OnDeviceLlmTransport
import com.example.aphasia.data.repository.AcousticTemplateRepositoryImpl
import com.example.aphasia.data.repository.AudioFeatureRepositoryImpl
import com.example.aphasia.data.repository.LanguageModelRepositoryImpl
import com.example.aphasia.data.repository.PhrasebookRepositoryImpl
import com.example.aphasia.data.repository.SpeechRepositoryImpl
import com.example.aphasia.domain.usecase.EnrollAcousticTemplateUseCase
import com.example.aphasia.domain.usecase.MatchUtteranceUseCase
import com.example.aphasia.domain.usecase.ReconstructSentenceUseCase
import com.example.aphasia.domain.usecase.SpeakUseCase

/**
 * 手動組裝的物件圖。
 *
 * 沒有導入 Hilt：這棵樹的相依關係是一條線（DAO → Repository → UseCase → ViewModel），
 * 沒有分支也沒有 scope 問題。為此加一個註解處理器要付編譯時間，
 * 而且既有的 `com.example` 那棵樹也沒有用，兩套並存只會讓人搞不清楚該在哪裡註冊。
 *
 * 全部 lazy：App 啟動時不該為了「使用者也許會用到失語症引擎」而開資料庫、
 * 配置音訊緩衝區。
 */
class AphasiaGraph(
    private val context: Context,
    private val tts: TtsModule,
    /** 雲端引擎。null＝使用者選了「只用本機」或還沒設定金鑰。 */
    private val cloudEngine: () -> HybridInferenceEngine?,
    private val onDeviceEngine: () -> MediaPipeLLMEngine?,
    /** 使用者的隱私偏好。true＝能在裝置上做完就不送出去。 */
    private val preferOnDevice: () -> Boolean = { true },
    /** 聲學捷徑的靈敏度倍率（使用者可調）。每次比對重讀，改完立即生效。 */
    private val shortcutSensitivity: () -> Float = { 1f },
) {

    private val appContext = context.applicationContext

    val database: AphasiaDatabase by lazy { AphasiaDatabase.get(appContext) }

    val audioCapture: AudioCaptureManager by lazy { AudioCaptureManager(appContext) }

    private val extractor by lazy { MfccExtractor() }

    val audioFeatureRepository by lazy { AudioFeatureRepositoryImpl(audioCapture, extractor) }

    val templateRepository by lazy {
        AcousticTemplateRepositoryImpl(
            dao = database.acousticTemplateDao(),
            extractor = extractor,
            sensitivity = shortcutSensitivity,
            // 建構時不碰網路也不查登入狀態：每次操作才即時取 token，
            // 沒登入／沒授權 Drive 就安靜地略過，不影響本機。
            mirror = com.example.ai.DriveAcousticMirror(appContext),
        )
    }

    val phrasebookRepository by lazy {
        val intentTree = IntentTreeSource(appContext)
        PhrasebookRepositoryImpl(
            dao = database.phraseDao(),
            embedder = HashingTextEmbedder(),
            intentTreeSource = { intentTree.load() },
        )
    }

    val speechRepository by lazy { SpeechRepositoryImpl(tts) }

    /** 預先合成好的句庫音檔。命中捷徑時優先播它——離線、零延遲、免額度。 */
    val prebuiltAudio by lazy { com.example.aphasia.data.local.PrebuiltAudio(appContext) }

    private val phraseLibrary by lazy { com.example.aphasia.data.local.PhraseLibrarySource(appContext) }

    /**
     * keyword → 句庫 id。樣板只存 keyword，但音檔與圖示都以 id 命名。
     *
     * 讀一次就快取：句庫是內建 asset，不會在執行期改變。
     */
    private var keywordToId: Map<String, String>? = null

    suspend fun phraseIdFor(keyword: String): String? {
        val map = keywordToId ?: phraseLibrary.load()
            .associate { it.keyword.lowercase() to it.id }
            .also { keywordToId = it }
        return map[keyword.lowercase()]
    }

    /**
     * 每次取用都重新組：使用者可能在設定裡換掉引擎或改隱私偏好，
     * 快取起來會讓那些改動要重開 App 才生效。
     */
    val languageModelRepository: LanguageModelRepositoryImpl
        get() = LanguageModelRepositoryImpl(buildTransport())

    private fun buildTransport(): LlmTransport = FallbackLlmTransport(
        onDevice = onDeviceEngine()?.let { OnDeviceLlmTransport(it) },
        cloud = cloudEngine()?.let { CloudLlmTransport(it) },
        preferOnDevice = preferOnDevice(),
    )

    // ── Use cases ───────────────────────────────────────────────────────

    val matchUtterance by lazy { MatchUtteranceUseCase(audioFeatureRepository, templateRepository) }
    val enrollTemplate by lazy { EnrollAcousticTemplateUseCase(templateRepository) }
    val speak by lazy { SpeakUseCase(speechRepository, phrasebookRepository) }
    val reconstruct: ReconstructSentenceUseCase
        get() = ReconstructSentenceUseCase(languageModelRepository, phrasebookRepository)
}
