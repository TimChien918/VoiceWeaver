package com.speakmylife.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 語音辨識（STT）服務商。
 *
 * **為什麼要跟文字 LLM 分開一份清單：**
 * 以前轉寫是掛在 [LlmProvider] 的 `transcribeEndpoint` 上，等於「想換轉寫供應商
 * 就得連文字重組的供應商一起換」。但這兩件事的取捨完全不同——重組要的是語意能力，
 * 轉寫要的是對含糊發音的容錯與延遲，實務上很少是同一家最好。
 * 而且失語症使用者的轉寫品質直接決定整條流程能不能用，它值得自己一個欄位。
 *
 * 舊的 LLM 內建轉寫仍然保留作為後備：這份清單是空的時候會自動退回去，
 * 既有使用者不會因為升級就突然沒有轉寫可用。
 */
enum class SttProvider(
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val needsKey: Boolean,
    val keyHint: String,
    /** 請求格式。各家差異大，實際送出在 [com.speakmylife.app.ai.SttClient] 依此分派。 */
    val style: SttApiStyle,
) {
    /** 電腦端本機 Whisper：免金鑰、語音不出區網。實際主機由 App 依 GPT-SoVITS 連線設定帶入。 */
    LOCAL(
        "本機電腦（Mac，免金鑰）",
        "http://127.0.0.1:9883/transcribe",
        "whisper-base",
        false,
        "用電腦本機運算，語音不外傳",
        SttApiStyle.LOCAL_SERVER,
    ),
    GROQ(
        "Groq Whisper（快、免費額度大）",
        "https://api.groq.com/openai/v1/audio/transcriptions",
        "whisper-large-v3",
        true,
        "console.groq.com/keys",
        SttApiStyle.OPENAI_MULTIPART,
    ),
    OPENAI(
        "OpenAI Whisper",
        "https://api.openai.com/v1/audio/transcriptions",
        "whisper-1",
        true,
        "platform.openai.com（需付費）",
        SttApiStyle.OPENAI_MULTIPART,
    ),
    MISTRAL(
        "Mistral Voxtral",
        "https://api.mistral.ai/v1/audio/transcriptions",
        "voxtral-mini-latest",
        true,
        "console.mistral.ai",
        SttApiStyle.OPENAI_MULTIPART,
    ),
    DEEPGRAM(
        // 對構音障礙語音的容錯在幾家裡算好的，而且延遲低
        "Deepgram Nova",
        "https://api.deepgram.com/v1/listen",
        "nova-2",
        true,
        "console.deepgram.com（有免費額度）",
        SttApiStyle.DEEPGRAM,
    ),
    ELEVENLABS(
        "ElevenLabs Scribe",
        "https://api.elevenlabs.io/v1/speech-to-text",
        "scribe_v1",
        true,
        "elevenlabs.io/app/settings/api-keys",
        SttApiStyle.ELEVENLABS,
    ),
    GEMINI(
        // Gemini 是「聽音訊直接回文字」，不是專門的轉寫端點，但品質好且額度寬
        "Gemini（音訊理解）",
        "https://generativelanguage.googleapis.com/v1beta/models",
        "gemini-3.5-flash",
        true,
        "Google AI Studio",
        SttApiStyle.GEMINI_INLINE,
    );

    companion object {
        fun from(id: String?): SttProvider = entries.firstOrNull { it.name == id } ?: GROQ
    }
}

/** 送出請求的格式。同一種格式的供應商可以共用同一段程式。 */
enum class SttApiStyle {
    /** OpenAI 相容的 multipart/form-data（file + model），Groq / OpenAI / Mistral 都吃這套。 */
    OPENAI_MULTIPART,

    /** Deepgram：raw body + Content-Type 指明音訊格式，參數走 query string。 */
    DEEPGRAM,

    /** ElevenLabs：multipart，欄位名與 OpenAI 不同（file → 一樣，model → model_id）。 */
    ELEVENLABS,

    /** Gemini：inline base64 音訊塞進 generateContent。 */
    GEMINI_INLINE,

    /** 電腦端自架服務：POST JSON {audio_base64, mime, language}。 */
    LOCAL_SERVER,
}

/**
 * 一筆語音辨識設定。endpoint / model 留空時用 [SttProvider] 的預設值。
 * key 只存裝置 DataStore，不進版控。
 */
data class SttApiEntry(
    val id: String,
    val provider: SttProvider,
    val name: String = "",
    val endpoint: String = "",
    val model: String = "",
    val key: String = "",
    /** 關掉但保留設定：換家測試時不必刪掉再重打一次金鑰。 */
    val enabled: Boolean = true,
) {
    val effectiveEndpoint: String get() = endpoint.trim().ifBlank { provider.defaultEndpoint }
    val effectiveModel: String get() = model.trim().ifBlank { provider.defaultModel }
    val label: String get() = name.trim().ifBlank { provider.displayName }

    /**
     * 這筆設定能不能真的送出去。
     *
     * `YOUR_` 開頭的一併排除：範例設定被原樣存下來是很常見的情況，
     * 讓它進到候選清單只會換來一個看不懂的 401。
     */
    val usable: Boolean
        get() {
            if (!enabled) return false
            if (!provider.needsKey) return true
            // trim 之後才判斷佔位符——" YOUR_KEY " 用未 trim 的字串比對會繞過去
            val k = key.trim()
            return k.isNotBlank() && !k.startsWith("YOUR_")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("provider", provider.name)
        .put("name", name)
        .put("endpoint", endpoint)
        .put("model", model)
        .put("key", key)
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): SttApiEntry = SttApiEntry(
            id = o.optString("id"),
            provider = SttProvider.from(o.optString("provider")),
            name = o.optString("name"),
            endpoint = o.optString("endpoint"),
            model = o.optString("model"),
            key = o.optString("key"),
            // 舊資料沒有這個欄位，預設為啟用才不會讓既有設定升級後全部失效
            enabled = o.optBoolean("enabled", true),
        )
    }
}

/** 語音辨識清單的 JSON 編解碼（存成單一 DataStore 字串）。 */
object SttApiStore {
    fun encode(list: List<SttApiEntry>): String =
        JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun decode(json: String): List<SttApiEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { SttApiEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
