package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 4 種視覺風格 */
enum class AppTheme(val displayName: String) {
    Tech("科技風"),
    Cute("可愛風"),
    Anime("動漫風"),
    Minimal("簡約風")
}

/** 4 種介面語言 */
enum class AppLanguage(val tag: String, val displayName: String) {
    ZhTW("zh-TW", "繁體中文"),
    En("en", "English"),
    Ja("ja", "日本語"),
    Ko("ko", "한국어")
}

/** 3 級耗能模式（影響 STT/LLM/相機資源使用）*/
enum class PowerMode(val displayName: String, val description: String) {
    Saver("省電", "強制規則引擎、關閉相機、不連續監聽"),
    Balanced("標準", "依硬體自動分流（建議）"),
    Performance("高效能", "優先 LLM 推理、允許連續監聽")
}

/**
 * 雲端 LLM 提供者選項。
 * Auto = 有網就雲端、無網/超時自動本地；其餘 3 選項為強制指定。
 */
enum class CloudProvider(val displayName: String) {
    Auto("自動（網路 → 雲端，否則本地）"),
    LocalOnly("僅本地（Qwen）"),
    Groq("Groq · Llama 3.3 70B"),
    OpenRouter("OpenRouter · Gemini Flash"),
    Cohere("Cohere · Command-R")
}

/**
 * 「AI 直接聽」要用誰的算力。
 *
 * 兩條路的取捨很實際：雲端（Gemini）品質好但免費額度很容易爆（一次直接聽會平行打
 * 3 次 API，實測連續用幾次就 HTTP 429），而且語音必須離開裝置；電腦端本機模型
 * （Gemma 4 E4B，跑在使用者自己的 Mac 上）不用金鑰、語音不出區網，但對含糊發音的
 * 理解力目前未經真實病患語音驗證。所以預設 Auto，讓使用者能自己按情況鎖定。
 */
enum class AudioUnderstandSource(val displayName: String) {
    Auto("自動（電腦就緒就用電腦，否則雲端）"),
    LocalOnly("只用電腦本機（免金鑰、語音不外傳）"),
    CloudOnly("只用雲端（品質優先）")
}

/** 眼動控制模式 */
enum class EyeControlMode(val displayName: String) {
    GazeDwell("凝視停留"),
    BlinkScan("眨眼掃描")
}

/** 相機鏡頭偏好：預覽與單次視覺辨識共用，避免兩邊看到不同畫面。 */
enum class CameraLens(val displayName: String, val lensFacing: Int?) {
    Back("後攝影機", androidx.camera.core.CameraSelector.LENS_FACING_BACK),
    Front("前攝影機", androidx.camera.core.CameraSelector.LENS_FACING_FRONT),
    Both("前後一起拍", null)
}

/** GPT-SoVITS 實驗語音路由。Auto 會依句子文字自動切換中/英樣本槽位。 */
enum class GptSovitVoiceMode(val displayName: String) {
    Auto("自動切換"),
    Chinese("中文語音"),
    English("英文語音")
}

/**
 * 無障礙嚴重程度分級（醫師臨床建議）。
 * 切換時套用對應預設（字體倍率、AAC 識字卡模式），並決定是否隱藏複雜設定。
 */
enum class SeverityMode(val displayName: String, val description: String) {
    Mild("輕度", "鍵盤打字為主，完整功能"),
    Moderate("中度", "超大圖卡預設展開，隱藏複雜設定"),
    Severe("重度", "全螢幕識字卡逐張掃描 + 特大字體")
}

/**
 * 行為數據彙總（醫師臨床建議：康復追蹤指標）。只存彙總值不存逐筆，
 * 給治療師看趨勢用；清除歷史不影響此彙總（由一鍵重置清除）。
 */
data class BehaviorMetrics(
    /** 反應時間：開 App 到第一次發聲的耗時（每次 App 啟動記一筆）*/
    val firstSpeakCount: Long = 0,
    val firstSpeakSumMs: Long = 0,
    /** 選句：AI 第一候選被點次數 / 總選句次數 */
    val candidateFirstHit: Long = 0,
    val candidateTotal: Long = 0,
    /** 修改次數：退回重選圖卡 / 刪除圖卡的累計 */
    val undoCount: Long = 0,
    /** 輸入佔比：圖卡輸入 vs 文字（打字/語音）輸入 */
    val aacInputCount: Long = 0,
    val textInputCount: Long = 0
) {
    val avgFirstSpeakMs: Long get() = if (firstSpeakCount > 0) firstSpeakSumMs / firstSpeakCount else 0
    val firstHitRate: Float get() = if (candidateTotal > 0) candidateFirstHit.toFloat() / candidateTotal else 0f
    val aacRatio: Float get() {
        val total = aacInputCount + textInputCount
        return if (total > 0) aacInputCount.toFloat() / total else 0f
    }
}

/** App 全域設定快照（單一資料源，UI 訂閱 [SettingsRepository.settings] 取得即時值）*/
data class AppSettings(
    val theme: AppTheme = AppTheme.Tech,
    /** 預設英文：這個 App 以英語使用者為主要對象。 */
    val language: AppLanguage = AppLanguage.En,
    val powerMode: PowerMode = PowerMode.Balanced,
    val cloudProvider: CloudProvider = CloudProvider.Auto,
    /** 「AI 直接聽」用誰的算力：自動／只用電腦本機／只用雲端。 */
    val audioUnderstandSource: AudioUnderstandSource = AudioUnderstandSource.Auto,
    val ttsRate: Float = 0.85f,
    val fontScale: Float = 1.0f,
    val autoSaveRehab: Boolean = true,
    val eyeControlEnabled: Boolean = false,
    val eyeControlMode: EyeControlMode = EyeControlMode.GazeDwell,
    val eyeScanIntervalMs: Float = 1200f,
    val cameraLens: CameraLens = CameraLens.Back,
    val gptSovitEnabled: Boolean = false,
    val gptSovitVoiceMode: GptSovitVoiceMode = GptSovitVoiceMode.Auto,
    val gptSovitChineseVoiceUri: String = "",
    val gptSovitEnglishVoiceUri: String = "",
    val gptSovitModelDirUri: String = "",
    val gptSovitChineseModelDirUri: String = "",
    val gptSovitEnglishModelDirUri: String = "",
    val gptSovitAutoImportRootUri: String = "",
    val gptSovitApiBaseUrl: String = "",
    /** 已儲存的 SoVITS 電腦清單：每行一筆「名稱|http://ip:9880」。 */
    val gptSovitServerList: String = "",
    val ngrokToken: String = "",
    val ngrokDomain: String = "",
    val ngrokPairCode: String = "",
    /** 聲紋克隆隱私知情同意（未勾選前不可啟用 GPT-SoVITS / 錄音匯入）。 */
    val sovitsConsentAccepted: Boolean = false,
    /** AAC 意圖確認卡開關：AI 組句後跳大圖卡讓不識字使用者 ✓/✗ 確認。 */
    val aacIntentConfirmEnabled: Boolean = true,
    /** 意圖確認卡用 AI 生圖產生「專屬圖卡」（不識字適用；較耗時，預設關）。 */
    val aacIntentImageEnabled: Boolean = false,
    /** 無障礙嚴重程度分級。 */
    val severityMode: SeverityMode = SeverityMode.Mild,
    // ─── 自訂 API（懸浮視窗設定）：預設給 Gemini 生圖；金鑰存裝置 DataStore，不進版控 ───
    val customApiEnabled: Boolean = false,
    val customApiName: String = "Gemini 生圖",
    val customApiEndpoint: String = DEFAULT_CUSTOM_API_ENDPOINT,
    val customApiModel: String = DEFAULT_CUSTOM_API_MODEL,
    /** 自訂 API 金鑰（Gemini 生圖）。空 = 首次啟動用 BuildConfig.GEMINI_IMAGE_API_KEY（gitignored .env）種入。 */
    val customApiKey: String = "",
    /**
     * 生圖 API 清單（JSON）：每筆 {id, provider, name, endpoint, model, key}。
     * 由 [ImageApiStore] 編解碼。空 = 首次用舊的 customApi*（Gemini）自動遷移種入。
     * 同一服務商可建多筆（多把金鑰，可手動選 + 自動備援）。
     */
    val imageApis: String = "",
    /**
     * 語音辨識供應商清單（[SttApiStore] 的 JSON）。
     *
     * 跟文字 LLM 分開存：重組要語意能力、轉寫要對含糊發音的容錯，
     * 很少是同一家最好。空字串＝沿用 LLM 內建的轉寫端點（向後相容）。
     */
    val sttApis: String = "",
    /** 目前生圖使用中的設定 id；空 = 用清單第一筆。 */
    val imageApiSelectedId: String = "",
    /** 文字 LLM API 清單（JSON）：每筆 {id, provider, name, endpoint, model, key}，由 [LlmApiStore] 編解碼。 */
    val llmApis: String = "",
    /** 目前文字 LLM 使用中的設定 id；空 = 用清單第一筆。 */
    val llmApiSelectedId: String = "",
    /**
     * 語音合成 API 清單（JSON）：每筆 {id, provider, name, endpoint, model, voice, key, extra}，
     * 由 [TtsApiStore] 編解碼。跟生圖／文字 LLM 同一套模式：可加多筆，合成時自動排序＋備援。
     */
    val ttsApis: String = "",
    /** 語音合成「釘選」的供應商 id；空 = 完全交給 SmartApiRouter 自動判斷。 */
    val ttsApiSelectedId: String = "",
    // ─── 其他 API 金鑰（可在 API 金鑰管理浮動視窗編輯；空則退回 BuildConfig）───
    val apiKeyGroq: String = "",
    val apiKeyOpenRouter: String = "",
    val apiKeyCohere: String = "",
    val apiKeyGemini: String = "",
    val apiKeyGooglePlaces: String = "",
    val apiKeyTelegramBotToken: String = "",
    /** 家人電話：危機介入時「即時通話（像打電話）」直接撥打。空 = 不顯示通話鈕。 */
    val emergencyPhoneNumber: String = "",
    /**
     * 視訊通話伺服器（Jitsi）。預設用**免登入**的社群實例 meet.ffmuc.net
     * （meet.jit.si 現在建立會議要登入）。可改成自架或其他開放實例。
     */
    val videoCallBaseUrl: String = DEFAULT_VIDEO_CALL_BASE,
    // ─── LLM 端點 / 模型（可自訂；名稱固定。空 = 用內建預設）───
    val llmGroqEndpoint: String = "",
    val llmGroqModel: String = "",
    val llmOpenRouterEndpoint: String = "",
    val llmOpenRouterModel: String = "",
    val llmCohereEndpoint: String = "",
    val llmCohereModel: String = "",
    val customBackgroundUri: String = "",
    val autoLocalAiCleanup: Boolean = true,
    val developerMode: Boolean = false,
    /** AAC 自訂金額預設貨幣（記住上次使用）*/
    val defaultCurrency: Currency = Currency.NTD,
    /**
     * 緊急通報的 Telegram Chat ID。**預設為空字串**——不內建任何真實接收者；
     * 使用者需於設定頁自行填入家屬或診所的 Chat ID。空字串時 [com.example.net.TelegramClient]
     * 會 fallback 到 BuildConfig 預設值。
     */
    val emergencyChatId: String = DEFAULT_EMERGENCY_CHAT_ID,

    /** 常用句「我的最愛」：每行一句，重組/AAC 結果可收藏，一鍵朗讀。雲端同步。 */
    val favoriteSentences: String = "",
    /** 自訂 AAC 圖卡（JSON 陣列：每筆 {label, emoji}）。使用者自己加的詞。 */
    val customAacItems: String = "",
    /** 鼻控靈敏度倍率（0.5~2.0，1.0=預設）。即時注入 nose_tracker.html。 */
    val noseSensitivity: Float = 1.0f,
    /**
     * 聲學捷徑的靈敏度倍率（乘在拒絕門檻上）。
     *
     * 1.0＝用登錄錄音自動校準出來的門檻。調高比較容易觸發但也比較容易說錯話，
     * 調低相反。做成可調是因為每個人、每句話的發音穩定度差很多——
     * 一個寫死的常數不可能同時適合所有人，而猜錯的代價由使用者承擔。
     */
    val shortcutSensitivity: Float = 1.0f,
    /** 高對比模式：純黑底 + 高彩文字，視力弱者用。 */
    val highContrast: Boolean = false,
    /** 每日復健提醒開關。 */
    val dailyReminderEnabled: Boolean = false,
    /** 每日提醒時間（24 小時制的小時，0~23）。 */
    val dailyReminderHour: Int = 19,
    /** 成績單自動推送家屬 Telegram 的頻率（none/daily/weekly）。 */
    val reportPushFrequency: String = "none",
    /** 已連結電腦的 Firebase 裝置代號（devices/{id}）；空 = 未連結。 */
    val linkedComputerId: String = "",
    /** 使用者自定的住家地址（純顯示用，給家屬看「目前定為這裡」）。空 = 未設定。 */
    val homeAddress: String = "",
    /** 住家經度。`Double.NaN` = 未設定。配合 [homeLongitude] 一起使用。 */
    val homeLatitude: Double = Double.NaN,
    val homeLongitude: Double = Double.NaN,
    /** 命中半徑（公尺）。預設 150 m 適合公寓 / 巷弄；獨棟可調小。 */
    val homeRadiusMeters: Float = 150f,
    /** 問題10：保存當前選定的地點，分頁切換時保留。 */
    val currentLocationTag: String = ""
) {
    val hasHome: Boolean get() =
        !homeLatitude.isNaN() && !homeLongitude.isNaN()

    companion object {
        const val DEFAULT_EMERGENCY_CHAT_ID = ""   // 不內建真實 chat id，由使用者自行填寫
        /** Gemini 生圖預設端點（base，不含 model）。實際呼叫：{endpoint}/{model}:generateContent */
        const val DEFAULT_CUSTOM_API_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        // 支援 generateContent 的生圖模型（gemini-2.0-flash-preview-image-generation 已下架）。
        const val DEFAULT_CUSTOM_API_MODEL = "gemini-2.5-flash-image"
        /**
         * 視訊預設用 meet.jit.si：搭配 prejoinPageEnabled=false 可**直接進通話、免輸入名字、免登入**
         * （瀏覽器加入不需登入；社群實例 ffmuc 反而強制名字頁，故不用）。
         */
        const val DEFAULT_VIDEO_CALL_BASE = "https://meet.jit.si"
    }
}

private val Context.settingsDataStore by preferencesDataStore("app_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val Theme               = stringPreferencesKey("theme")
        val Language            = stringPreferencesKey("language")
        val PowerMode           = stringPreferencesKey("power_mode")
        val CloudProvider       = stringPreferencesKey("cloud_provider")
        val AudioUnderstandSrc  = stringPreferencesKey("audio_understand_source")
        val TtsRate             = floatPreferencesKey("tts_rate")
        val FontScale           = floatPreferencesKey("font_scale")
        val AutoSave            = booleanPreferencesKey("auto_save_rehab")
        val EyeControlEnabled   = booleanPreferencesKey("eye_control_enabled")
        val EyeControlMode      = stringPreferencesKey("eye_control_mode")
        val EyeScanIntervalMs   = floatPreferencesKey("eye_scan_interval_ms")
        val CameraLens          = stringPreferencesKey("camera_lens")
        val GptSovitEnabled     = booleanPreferencesKey("gpt_sovit_enabled")
        val GptSovitVoiceMode   = stringPreferencesKey("gpt_sovit_voice_mode")
        val GptSovitZhVoiceUri  = stringPreferencesKey("gpt_sovit_zh_voice_uri")
        val GptSovitEnVoiceUri  = stringPreferencesKey("gpt_sovit_en_voice_uri")
        val GptSovitModelDirUri = stringPreferencesKey("gpt_sovit_model_dir_uri")
        val GptSovitZhModelDirUri = stringPreferencesKey("gpt_sovit_zh_model_dir_uri")
        val GptSovitEnModelDirUri = stringPreferencesKey("gpt_sovit_en_model_dir_uri")
        val GptSovitAutoRootUri = stringPreferencesKey("gpt_sovit_auto_root_uri")
        val GptSovitApiBaseUrl = stringPreferencesKey("gpt_sovit_api_base_url")
        val GptSovitServerList = stringPreferencesKey("gpt_sovit_server_list")
        val NgrokToken = stringPreferencesKey("ngrok_token")
        val NgrokDomain = stringPreferencesKey("ngrok_domain")
        val NgrokPairCode = stringPreferencesKey("ngrok_pair_code")
        val SovitsConsent       = booleanPreferencesKey("sovits_consent_accepted")
        val AacIntentConfirm    = booleanPreferencesKey("aac_intent_confirm")
        val AacIntentImage      = booleanPreferencesKey("aac_intent_image")
        val SeverityMode        = stringPreferencesKey("severity_mode")
        val CustomApiEnabled    = booleanPreferencesKey("custom_api_enabled")
        val CustomApiName       = stringPreferencesKey("custom_api_name")
        val CustomApiEndpoint   = stringPreferencesKey("custom_api_endpoint")
        val CustomApiModel      = stringPreferencesKey("custom_api_model")
        val CustomApiKey        = stringPreferencesKey("custom_api_key")
        val ApiKeyGroq          = stringPreferencesKey("api_key_groq")
        val ApiKeyOpenRouter    = stringPreferencesKey("api_key_openrouter")
        val ApiKeyCohere        = stringPreferencesKey("api_key_cohere")
        val ApiKeyGemini        = stringPreferencesKey("api_key_gemini")
        val ApiKeyGooglePlaces  = stringPreferencesKey("api_key_google_places")
        val ApiKeyTelegram      = stringPreferencesKey("api_key_telegram_bot")
        val EmergencyPhone      = stringPreferencesKey("emergency_phone_number")
        val VideoCallBase       = stringPreferencesKey("video_call_base_url")
        val LlmGroqEndpoint     = stringPreferencesKey("llm_groq_endpoint")
        val LlmGroqModel        = stringPreferencesKey("llm_groq_model")
        val LlmOpenRouterEndpoint = stringPreferencesKey("llm_openrouter_endpoint")
        val LlmOpenRouterModel  = stringPreferencesKey("llm_openrouter_model")
        val LlmCohereEndpoint   = stringPreferencesKey("llm_cohere_endpoint")
        val LlmCohereModel      = stringPreferencesKey("llm_cohere_model")
        val ImageApis           = stringPreferencesKey("image_apis")
        val SttApis             = stringPreferencesKey("stt_apis")
        val ImageApiSelectedId  = stringPreferencesKey("image_api_selected_id")
        val LlmApis             = stringPreferencesKey("llm_apis")
        val LlmApiSelectedId    = stringPreferencesKey("llm_api_selected_id")
        val TtsApis             = stringPreferencesKey("tts_apis")
        val TtsApiSelectedId    = stringPreferencesKey("tts_api_selected_id")
        val CustomBackgroundUri = stringPreferencesKey("custom_background_uri")
        // 行為數據彙總
        val BmFirstSpeakCount = longPreferencesKey("bm_first_speak_count")
        val BmFirstSpeakSumMs = longPreferencesKey("bm_first_speak_sum_ms")
        val BmCandidateFirstHit = longPreferencesKey("bm_candidate_first_hit")
        val BmCandidateTotal  = longPreferencesKey("bm_candidate_total")
        val BmUndoCount       = longPreferencesKey("bm_undo_count")
        val BmAacInputCount   = longPreferencesKey("bm_aac_input_count")
        val BmTextInputCount  = longPreferencesKey("bm_text_input_count")
        val AutoLocalAiCleanup  = booleanPreferencesKey("auto_local_ai_cleanup")
        val DeveloperMode       = booleanPreferencesKey("developer_mode")
        val DefaultCurrency     = stringPreferencesKey("default_currency")
        val EmergencyChatId     = stringPreferencesKey("emergency_chat_id")
        val HomeAddress         = stringPreferencesKey("home_address")
        val HomeLat             = floatPreferencesKey("home_lat")
        val HomeLon             = floatPreferencesKey("home_lon")
        val HomeRadius          = floatPreferencesKey("home_radius")
        val FavoriteSentences   = stringPreferencesKey("favorite_sentences")
        val CustomAacItems      = stringPreferencesKey("custom_aac_items")
        val NoseSensitivity     = floatPreferencesKey("nose_sensitivity")
        val ShortcutSensitivity = floatPreferencesKey("shortcut_sensitivity")
        val HighContrast        = booleanPreferencesKey("high_contrast")
        val DailyReminderEnabled= booleanPreferencesKey("daily_reminder_enabled")
        val DailyReminderHour   = androidx.datastore.preferences.core.intPreferencesKey("daily_reminder_hour")
        val ReportPushFrequency = stringPreferencesKey("report_push_frequency")
        val LinkedComputerId    = stringPreferencesKey("linked_computer_id")
        val CurrentLocationTag  = stringPreferencesKey("current_location_tag")  // 問題10：保存地點狀態
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        theme         = runCatching { AppTheme.valueOf(this[Keys.Theme] ?: AppTheme.Tech.name) }.getOrDefault(AppTheme.Tech),
        language      = runCatching { AppLanguage.valueOf(this[Keys.Language] ?: AppLanguage.En.name) }.getOrDefault(AppLanguage.En),
        powerMode     = runCatching { PowerMode.valueOf(this[Keys.PowerMode] ?: PowerMode.Balanced.name) }.getOrDefault(PowerMode.Balanced),
        cloudProvider = runCatching { CloudProvider.valueOf(this[Keys.CloudProvider] ?: CloudProvider.Auto.name) }.getOrDefault(CloudProvider.Auto),
        audioUnderstandSource = runCatching { AudioUnderstandSource.valueOf(this[Keys.AudioUnderstandSrc] ?: AudioUnderstandSource.Auto.name) }.getOrDefault(AudioUnderstandSource.Auto),
        ttsRate       = this[Keys.TtsRate] ?: 0.85f,
        fontScale     = this[Keys.FontScale] ?: 1.0f,
        autoSaveRehab = this[Keys.AutoSave] ?: true,
        eyeControlEnabled = this[Keys.EyeControlEnabled] ?: false,
        eyeControlMode    = runCatching { EyeControlMode.valueOf(this[Keys.EyeControlMode] ?: EyeControlMode.GazeDwell.name) }.getOrDefault(EyeControlMode.GazeDwell),
        eyeScanIntervalMs = this[Keys.EyeScanIntervalMs] ?: 1200f,
        cameraLens        = runCatching { CameraLens.valueOf(this[Keys.CameraLens] ?: CameraLens.Back.name) }.getOrDefault(CameraLens.Back),
        gptSovitEnabled   = this[Keys.GptSovitEnabled] ?: false,
        gptSovitVoiceMode = runCatching { GptSovitVoiceMode.valueOf(this[Keys.GptSovitVoiceMode] ?: GptSovitVoiceMode.Auto.name) }.getOrDefault(GptSovitVoiceMode.Auto),
        gptSovitChineseVoiceUri = this[Keys.GptSovitZhVoiceUri] ?: "",
        gptSovitEnglishVoiceUri = this[Keys.GptSovitEnVoiceUri] ?: "",
        gptSovitModelDirUri = this[Keys.GptSovitModelDirUri] ?: "",
        gptSovitChineseModelDirUri = this[Keys.GptSovitZhModelDirUri] ?: (this[Keys.GptSovitModelDirUri] ?: ""),
        gptSovitEnglishModelDirUri = this[Keys.GptSovitEnModelDirUri] ?: "",
        gptSovitAutoImportRootUri = this[Keys.GptSovitAutoRootUri] ?: "",
        gptSovitApiBaseUrl = this[Keys.GptSovitApiBaseUrl] ?: "",
        gptSovitServerList = this[Keys.GptSovitServerList] ?: "",
        ngrokToken = this[Keys.NgrokToken] ?: "",
        ngrokDomain = this[Keys.NgrokDomain] ?: "",
        ngrokPairCode = this[Keys.NgrokPairCode] ?: "",
        sovitsConsentAccepted = this[Keys.SovitsConsent] ?: false,
        aacIntentConfirmEnabled = this[Keys.AacIntentConfirm] ?: true,
        aacIntentImageEnabled = this[Keys.AacIntentImage] ?: false,
        severityMode = runCatching { SeverityMode.valueOf(this[Keys.SeverityMode] ?: SeverityMode.Mild.name) }.getOrDefault(SeverityMode.Mild),
        customApiEnabled = this[Keys.CustomApiEnabled] ?: false,
        customApiName = this[Keys.CustomApiName] ?: "Gemini 生圖",
        customApiEndpoint = this[Keys.CustomApiEndpoint] ?: AppSettings.DEFAULT_CUSTOM_API_ENDPOINT,
        customApiModel = this[Keys.CustomApiModel] ?: AppSettings.DEFAULT_CUSTOM_API_MODEL,
        customApiKey = this[Keys.CustomApiKey] ?: "",
        imageApis = this[Keys.ImageApis] ?: "",
        sttApis = this[Keys.SttApis] ?: "",
        imageApiSelectedId = this[Keys.ImageApiSelectedId] ?: "",
        llmApis = this[Keys.LlmApis] ?: "",
        llmApiSelectedId = this[Keys.LlmApiSelectedId] ?: "",
        ttsApis = this[Keys.TtsApis] ?: "",
        ttsApiSelectedId = this[Keys.TtsApiSelectedId] ?: "",
        apiKeyGroq = this[Keys.ApiKeyGroq] ?: "",
        apiKeyOpenRouter = this[Keys.ApiKeyOpenRouter] ?: "",
        apiKeyCohere = this[Keys.ApiKeyCohere] ?: "",
        apiKeyGemini = this[Keys.ApiKeyGemini] ?: "",
        apiKeyGooglePlaces = this[Keys.ApiKeyGooglePlaces] ?: "",
        apiKeyTelegramBotToken = this[Keys.ApiKeyTelegram] ?: "",
        emergencyPhoneNumber = this[Keys.EmergencyPhone] ?: "",
        videoCallBaseUrl = this[Keys.VideoCallBase] ?: AppSettings.DEFAULT_VIDEO_CALL_BASE,
        llmGroqEndpoint = this[Keys.LlmGroqEndpoint] ?: "",
        llmGroqModel = this[Keys.LlmGroqModel] ?: "",
        llmOpenRouterEndpoint = this[Keys.LlmOpenRouterEndpoint] ?: "",
        llmOpenRouterModel = this[Keys.LlmOpenRouterModel] ?: "",
        llmCohereEndpoint = this[Keys.LlmCohereEndpoint] ?: "",
        llmCohereModel = this[Keys.LlmCohereModel] ?: "",
        customBackgroundUri = this[Keys.CustomBackgroundUri] ?: "",
        autoLocalAiCleanup = this[Keys.AutoLocalAiCleanup] ?: true,
        developerMode      = this[Keys.DeveloperMode] ?: false,
        defaultCurrency    = runCatching { Currency.valueOf(this[Keys.DefaultCurrency] ?: Currency.NTD.name) }.getOrDefault(Currency.NTD),
        emergencyChatId    = this[Keys.EmergencyChatId] ?: AppSettings.DEFAULT_EMERGENCY_CHAT_ID,
        homeAddress        = this[Keys.HomeAddress] ?: "",
        homeLatitude       = (this[Keys.HomeLat] ?: Float.NaN).toDouble(),
        homeLongitude      = (this[Keys.HomeLon] ?: Float.NaN).toDouble(),
        homeRadiusMeters   = this[Keys.HomeRadius] ?: 150f,
        favoriteSentences  = this[Keys.FavoriteSentences] ?: "",
        customAacItems     = this[Keys.CustomAacItems] ?: "",
        noseSensitivity    = this[Keys.NoseSensitivity] ?: 1.0f,
        shortcutSensitivity= this[Keys.ShortcutSensitivity] ?: 1.0f,
        highContrast       = this[Keys.HighContrast] ?: false,
        dailyReminderEnabled = this[Keys.DailyReminderEnabled] ?: false,
        dailyReminderHour  = this[Keys.DailyReminderHour] ?: 19,
        reportPushFrequency = this[Keys.ReportPushFrequency] ?: "none",
        linkedComputerId    = this[Keys.LinkedComputerId] ?: "",
        currentLocationTag  = this[Keys.CurrentLocationTag] ?: ""  // 問題10：讀取保存的地點
    )

    suspend fun setTheme(theme: AppTheme)            = update { it[Keys.Theme] = theme.name }
    suspend fun setLanguage(lang: AppLanguage)       = update { it[Keys.Language] = lang.name }
    suspend fun setPowerMode(mode: PowerMode)        = update { it[Keys.PowerMode] = mode.name }
    suspend fun setCloudProvider(p: CloudProvider)   = update { it[Keys.CloudProvider] = p.name }
    suspend fun setAudioUnderstandSource(s: AudioUnderstandSource) = update { it[Keys.AudioUnderstandSrc] = s.name }
    suspend fun setTtsRate(rate: Float)              = update { it[Keys.TtsRate] = rate.coerceIn(0.5f, 1.5f) }
    suspend fun setFontScale(scale: Float)           = update { it[Keys.FontScale] = scale.coerceIn(0.8f, 1.8f) }
    suspend fun setAutoSaveRehab(on: Boolean)        = update { it[Keys.AutoSave] = on }
    suspend fun setEyeControlEnabled(on: Boolean)    = update { it[Keys.EyeControlEnabled] = on }
    suspend fun setEyeControlMode(m: EyeControlMode) = update { it[Keys.EyeControlMode] = m.name }
    suspend fun setEyeScanIntervalMs(ms: Float)      = update { it[Keys.EyeScanIntervalMs] = ms.coerceIn(600f, 2200f) }
    suspend fun setCameraLens(lens: CameraLens)      = update { it[Keys.CameraLens] = lens.name }
    suspend fun setGptSovitEnabled(on: Boolean)      = update { it[Keys.GptSovitEnabled] = on }
    suspend fun setGptSovitVoiceMode(mode: GptSovitVoiceMode) = update { it[Keys.GptSovitVoiceMode] = mode.name }
    suspend fun setGptSovitChineseVoiceUri(uri: String) = update { it[Keys.GptSovitZhVoiceUri] = uri }
    suspend fun setGptSovitEnglishVoiceUri(uri: String) = update { it[Keys.GptSovitEnVoiceUri] = uri }
    suspend fun setGptSovitModelDirUri(uri: String) = update { it[Keys.GptSovitModelDirUri] = uri }
    suspend fun setGptSovitChineseModelDirUri(uri: String) = update { it[Keys.GptSovitZhModelDirUri] = uri }
    suspend fun setGptSovitEnglishModelDirUri(uri: String) = update { it[Keys.GptSovitEnModelDirUri] = uri }
    suspend fun setGptSovitAutoImportRootUri(uri: String) = update { it[Keys.GptSovitAutoRootUri] = uri }
    suspend fun setGptSovitApiBaseUrl(url: String) = update { it[Keys.GptSovitApiBaseUrl] = url.trim() }
    suspend fun setGptSovitServerList(list: String) = update { it[Keys.GptSovitServerList] = list }
    suspend fun setNgrokToken(v: String) = update { it[Keys.NgrokToken] = v.trim() }
    suspend fun setNgrokDomain(v: String) = update { it[Keys.NgrokDomain] = v.trim() }
    suspend fun setNgrokPairCode(v: String) = update { it[Keys.NgrokPairCode] = v }
    suspend fun setSovitsConsentAccepted(on: Boolean) = update { it[Keys.SovitsConsent] = on }
    suspend fun setAacIntentConfirmEnabled(on: Boolean) = update { it[Keys.AacIntentConfirm] = on }
    suspend fun setAacIntentImageEnabled(on: Boolean) = update { it[Keys.AacIntentImage] = on }
    suspend fun setSeverityMode(mode: SeverityMode) = update { it[Keys.SeverityMode] = mode.name }
    suspend fun setCustomApiEnabled(on: Boolean) = update { it[Keys.CustomApiEnabled] = on }
    suspend fun setCustomApiName(v: String) = update { it[Keys.CustomApiName] = v }
    suspend fun setCustomApiEndpoint(v: String) = update { it[Keys.CustomApiEndpoint] = v.trim() }
    suspend fun setCustomApiModel(v: String) = update { it[Keys.CustomApiModel] = v.trim() }
    suspend fun setCustomApiKey(v: String) = update { it[Keys.CustomApiKey] = v.trim() }
    suspend fun setImageApis(json: String) = update { it[Keys.ImageApis] = json }
    suspend fun setSttApis(json: String) = update { it[Keys.SttApis] = json }
    suspend fun setImageApiSelectedId(id: String) = update { it[Keys.ImageApiSelectedId] = id }
    suspend fun setLlmApis(json: String) = update { it[Keys.LlmApis] = json }
    suspend fun setLlmApiSelectedId(id: String) = update { it[Keys.LlmApiSelectedId] = id }
    suspend fun setTtsApis(json: String) = update { it[Keys.TtsApis] = json }
    suspend fun setTtsApiSelectedId(id: String) = update { it[Keys.TtsApiSelectedId] = id }
    suspend fun setApiKeyGroq(v: String) = update { it[Keys.ApiKeyGroq] = v.trim() }
    suspend fun setApiKeyOpenRouter(v: String) = update { it[Keys.ApiKeyOpenRouter] = v.trim() }
    suspend fun setApiKeyCohere(v: String) = update { it[Keys.ApiKeyCohere] = v.trim() }
    suspend fun setApiKeyGemini(v: String) = update { it[Keys.ApiKeyGemini] = v.trim() }
    suspend fun setApiKeyGooglePlaces(v: String) = update { it[Keys.ApiKeyGooglePlaces] = v.trim() }
    suspend fun setApiKeyTelegramBotToken(v: String) = update { it[Keys.ApiKeyTelegram] = v.trim() }
    suspend fun setEmergencyPhoneNumber(v: String) = update { it[Keys.EmergencyPhone] = v.trim() }
    suspend fun setVideoCallBaseUrl(v: String) = update {
        it[Keys.VideoCallBase] = v.trim().trimEnd('/').ifBlank { AppSettings.DEFAULT_VIDEO_CALL_BASE }
    }
    suspend fun setLlmGroqEndpoint(v: String) = update { it[Keys.LlmGroqEndpoint] = v.trim() }
    suspend fun setLlmGroqModel(v: String) = update { it[Keys.LlmGroqModel] = v.trim() }
    suspend fun setLlmOpenRouterEndpoint(v: String) = update { it[Keys.LlmOpenRouterEndpoint] = v.trim() }
    suspend fun setLlmOpenRouterModel(v: String) = update { it[Keys.LlmOpenRouterModel] = v.trim() }
    suspend fun setLlmCohereEndpoint(v: String) = update { it[Keys.LlmCohereEndpoint] = v.trim() }
    suspend fun setLlmCohereModel(v: String) = update { it[Keys.LlmCohereModel] = v.trim() }
    suspend fun setCustomBackgroundUri(uri: String)  = update { it[Keys.CustomBackgroundUri] = uri }
    suspend fun setAutoLocalAiCleanup(on: Boolean)   = update { it[Keys.AutoLocalAiCleanup] = on }
    suspend fun setDeveloperMode(on: Boolean)        = update { it[Keys.DeveloperMode] = on }
    suspend fun setDefaultCurrency(c: Currency)      = update { it[Keys.DefaultCurrency] = c.name }
    suspend fun setEmergencyChatId(id: String) = update {
        // 只允許純數字或 `-1001...` 形式的 Telegram chat id；空字串回退預設
        val cleaned = id.trim().ifBlank { AppSettings.DEFAULT_EMERGENCY_CHAT_ID }
        it[Keys.EmergencyChatId] = cleaned
    }
    /** 設定住家地址 + 經緯度（兩者必須同時存在；任一為空就視為「清除住家」）。 */
    suspend fun setHome(address: String, latitude: Double?, longitude: Double?) = update {
        if (address.isBlank() || latitude == null || longitude == null) {
            it.remove(Keys.HomeAddress); it.remove(Keys.HomeLat); it.remove(Keys.HomeLon)
        } else {
            it[Keys.HomeAddress] = address
            it[Keys.HomeLat] = latitude.toFloat()
            it[Keys.HomeLon] = longitude.toFloat()
        }
    }
    suspend fun setHomeRadius(radiusMeters: Float) = update {
        it[Keys.HomeRadius] = radiusMeters.coerceIn(20f, 1000f)
    }
    suspend fun setFavoriteSentences(v: String) = update { it[Keys.FavoriteSentences] = v }
    /** 切換一句的收藏狀態（已收藏→移除，未收藏→加到最前）。回傳是否為「已加入」。 */
    suspend fun toggleFavorite(sentence: String) {
        val s = sentence.trim()
        if (s.isBlank()) return
        update { p ->
            val list = (p[Keys.FavoriteSentences] ?: "").split("\n").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            if (list.remove(s)) { /* 已移除 */ } else { list.add(0, s) }
            p[Keys.FavoriteSentences] = list.distinct().take(50).joinToString("\n")
        }
    }
    suspend fun setCustomAacItems(json: String) = update { it[Keys.CustomAacItems] = json }
    suspend fun setNoseSensitivity(v: Float) = update { it[Keys.NoseSensitivity] = v.coerceIn(0.5f, 2.0f) }
    suspend fun setHighContrast(on: Boolean) = update { it[Keys.HighContrast] = on }
    suspend fun setDailyReminder(enabled: Boolean, hour: Int) = update {
        it[Keys.DailyReminderEnabled] = enabled
        it[Keys.DailyReminderHour] = hour.coerceIn(0, 23)
    }

    /** @see AppSettings.shortcutSensitivity */
    suspend fun setShortcutSensitivity(v: Float) =
        update { it[Keys.ShortcutSensitivity] = v.coerceIn(0.7f, 1.8f) }
    suspend fun setReportPushFrequency(freq: String) = update { it[Keys.ReportPushFrequency] = freq }
    suspend fun setLinkedComputerId(id: String) = update { it[Keys.LinkedComputerId] = id.trim() }
    suspend fun setCurrentLocationTag(tag: String) = update { it[Keys.CurrentLocationTag] = tag }  // 問題10：保存地點

    // ─── 行為數據彙總（康復追蹤指標）───────────────────────────────────────────

    val behaviorMetrics: Flow<BehaviorMetrics> = context.settingsDataStore.data.map { p ->
        BehaviorMetrics(
            firstSpeakCount   = p[Keys.BmFirstSpeakCount] ?: 0,
            firstSpeakSumMs   = p[Keys.BmFirstSpeakSumMs] ?: 0,
            candidateFirstHit = p[Keys.BmCandidateFirstHit] ?: 0,
            candidateTotal    = p[Keys.BmCandidateTotal] ?: 0,
            undoCount         = p[Keys.BmUndoCount] ?: 0,
            aacInputCount     = p[Keys.BmAacInputCount] ?: 0,
            textInputCount    = p[Keys.BmTextInputCount] ?: 0
        )
    }

    /** 反應時間：開 App → 第一次發聲的耗時，每次啟動最多記一筆。 */
    suspend fun recordFirstSpeakLatency(ms: Long) = update {
        it[Keys.BmFirstSpeakCount] = (it[Keys.BmFirstSpeakCount] ?: 0) + 1
        it[Keys.BmFirstSpeakSumMs] = (it[Keys.BmFirstSpeakSumMs] ?: 0) + ms.coerceAtLeast(0)
    }

    /** 選句：每次點候選句記一筆；點的是 AI 第一候選則 firstHit +1。 */
    suspend fun recordCandidateChoice(firstHit: Boolean) = update {
        it[Keys.BmCandidateTotal] = (it[Keys.BmCandidateTotal] ?: 0) + 1
        if (firstHit) it[Keys.BmCandidateFirstHit] = (it[Keys.BmCandidateFirstHit] ?: 0) + 1
    }

    /** 修改：退回重選 / 刪除圖卡 +1。 */
    suspend fun recordUndo() = update { it[Keys.BmUndoCount] = (it[Keys.BmUndoCount] ?: 0) + 1 }

    /** 輸入來源：圖卡 vs 文字。 */
    suspend fun recordInput(viaAac: Boolean) = update {
        val key = if (viaAac) Keys.BmAacInputCount else Keys.BmTextInputCount
        it[key] = (it[key] ?: 0) + 1
    }

    /** 「一鍵重置」：把所有 DataStore key 清掉，下次讀取會回退到資料類別的預設值。 */
    suspend fun resetAll() = context.settingsDataStore.edit { it.clear() }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }
}
