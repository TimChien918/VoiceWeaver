package com.example.data

/**
 * 執行期 API 金鑰覆蓋層。
 *
 * 背景：金鑰原本只從 `BuildConfig`（由 secrets-gradle-plugin 讀 gitignored `.env`）取得，
 * 編譯後就固定、使用者無法改。現在讓使用者能在「API 金鑰管理」浮動視窗即時編輯，
 * 值存在裝置 DataStore（不進版控、不打包進原始碼）。
 *
 * 機制：ViewModel 訂閱 settings flow，把使用者填的值灌進這裡的 @Volatile 欄位；
 * 各 consumer（CloudInferenceEngine / GooglePlacesClient / TelegramClient）改讀這裡，
 * 空值才退回 BuildConfig。同步讀取、無協程依賴，consumer 不需要改成 suspend。
 */
object ApiKeyOverrides {
    @Volatile var groq: String = ""
    @Volatile var openRouter: String = ""
    @Volatile var cohere: String = ""
    @Volatile var gemini: String = ""
    @Volatile var geminiImage: String = ""
    @Volatile var googlePlaces: String = ""
    @Volatile var telegramBotToken: String = ""

    // LLM 端點 / 模型（空 = 用內建預設 [LlmDefaults]）
    @Volatile var groqEndpoint: String = ""
    @Volatile var groqModel: String = ""
    @Volatile var openRouterEndpoint: String = ""
    @Volatile var openRouterModel: String = ""
    @Volatile var cohereEndpoint: String = ""
    @Volatile var cohereModel: String = ""

    // 多供應商文字 LLM 清單（給 HybridInferenceEngine 同步讀取，免協程）
    @Volatile var llmApis: List<LlmApiEntry> = emptyList()
    @Volatile var llmSelectedId: String = ""

    /** 使用者覆蓋優先、空則退回 fallback（也擋掉 YOUR_ 佔位）。 */
    fun resolve(override: String, fallback: String): String {
        val o = override.trim()
        if (o.isNotBlank() && !o.startsWith("YOUR_")) return o
        return fallback.takeUnless { it.isBlank() || it.startsWith("YOUR_") } ?: ""
    }

    /** 純文字欄位（端點/模型）：使用者填的優先，空則退回預設。 */
    fun or(override: String, default: String): String =
        override.trim().ifBlank { default }

    /** ViewModel 在 settings 變動時呼叫，把使用者填的值同步進來。 */
    fun update(s: AppSettings) {
        groq = s.apiKeyGroq
        openRouter = s.apiKeyOpenRouter
        cohere = s.apiKeyCohere
        gemini = s.apiKeyGemini
        geminiImage = s.customApiKey
        googlePlaces = s.apiKeyGooglePlaces
        telegramBotToken = s.apiKeyTelegramBotToken
        groqEndpoint = s.llmGroqEndpoint
        groqModel = s.llmGroqModel
        openRouterEndpoint = s.llmOpenRouterEndpoint
        openRouterModel = s.llmOpenRouterModel
        cohereEndpoint = s.llmCohereEndpoint
        cohereModel = s.llmCohereModel
        llmApis = LlmApiStore.decode(s.llmApis)
        llmSelectedId = s.llmApiSelectedId
    }
}

/** LLM 內建預設端點與模型。使用者在 API 金鑰管理留空時採用。 */
object LlmDefaults {
    const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    const val GROQ_MODEL = "llama-3.3-70b-versatile"
    const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    const val OPENROUTER_MODEL = "qwen/qwen-2.5-7b-instruct"
    const val COHERE_ENDPOINT = "https://api.cohere.com/v2/chat"
    const val COHERE_MODEL = "command-r7b-12-2024"
}
