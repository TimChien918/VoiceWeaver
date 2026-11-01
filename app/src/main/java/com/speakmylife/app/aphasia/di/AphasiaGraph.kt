package com.speakmylife.app.aphasia.di

import android.content.Context
import com.speakmylife.app.ai.HybridInferenceEngine
import com.speakmylife.app.ai.MediaPipeLLMEngine
import com.speakmylife.app.ai.TtsModule
import com.speakmylife.app.aphasia.data.acoustic.AudioCaptureManager
import com.speakmylife.app.aphasia.data.acoustic.MfccExtractor
import com.speakmylife.app.aphasia.data.local.AphasiaDatabase
import com.speakmylife.app.aphasia.data.local.HashingTextEmbedder
import com.speakmylife.app.aphasia.data.local.IntentTreeSource
import com.speakmylife.app.aphasia.data.remote.CloudLlmTransport
import com.speakmylife.app.aphasia.data.remote.FallbackLlmTransport
import com.speakmylife.app.aphasia.data.remote.LlmTransport
import com.speakmylife.app.aphasia.data.remote.OnDeviceLlmTransport
import com.speakmylife.app.aphasia.data.repository.AcousticTemplateRepositoryImpl
import com.speakmylife.app.aphasia.data.repository.AudioFeatureRepositoryImpl
import com.speakmylife.app.aphasia.data.repository.LanguageModelRepositoryImpl
import com.speakmylife.app.aphasia.data.repository.PhrasebookRepositoryImpl
import com.speakmylife.app.aphasia.data.repository.SpeechRepositoryImpl
import com.speakmylife.app.aphasia.domain.usecase.ReconstructSentenceUseCase
import com.speakmylife.app.aphasia.domain.usecase.SpeakUseCase

/**
 * 手動組裝的物件圖。
 *
 * 沒有導入 Hilt：這棵樹的相依關係是一條線（DAO → Repository → UseCase → ViewModel），
 * 沒有分支也沒有 scope 問題。為此加一個註解處理器要付編譯時間，
 * 而且既有的 `com.speakmylife.app` 那棵樹也沒有用，兩套並存只會讓人搞不清楚該在哪裡註冊。
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
            mirror = com.speakmylife.app.ai.DriveAcousticMirror(appContext),
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
    val prebuiltAudio by lazy { com.speakmylife.app.aphasia.data.local.PrebuiltAudio(appContext) }

    private val phraseLibrary by lazy { com.speakmylife.app.aphasia.data.local.PhraseLibrarySource(appContext) }

    /**
     * keyword → 句庫 id。樣板只存 keyword，但音檔與圖示都以 id 命名。
     *
     * 讀一次就快取：句庫是內建 asset，不會在執行期改變。
     */
    private var keywordToId: Map<String, String>? = null

    suspend fun phraseIdFor(keyword: String): String? {
        val map = keywordToId ?: phraseLibrary.load()
            // 舊關鍵字也要進表。關鍵字改寫後，早就登錄好的樣板存的是舊字串，
            // 查不到 id 就播不到預合成的音檔，會安靜地退回 TTS——
            // 使用者聽到的是完全不同的聲音，而畫面上什麼都沒說。
            .flatMap { e -> (listOf(e.keyword) + e.aliases).map { it.lowercase() to e.id } }
            .toMap()
            .also { keywordToId = it }
        return map[keyword.lowercase().trim()]
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

    val speak by lazy { SpeakUseCase(speechRepository, phrasebookRepository) }
    val reconstruct: ReconstructSentenceUseCase
        get() = ReconstructSentenceUseCase(languageModelRepository, phrasebookRepository)
}
