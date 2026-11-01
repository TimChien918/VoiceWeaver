package com.speakmylife.app.ai

import com.speakmylife.app.data.LlmApiEntry
import com.speakmylife.app.data.LlmApiStyle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 各家 LLM 廠商的 API 格式轉接層。
 *
 * 為什麼要獨立成一層：原本「怎麼組 request、怎麼讀 response」直接寫死在
 * HybridInferenceEngine 裡，每加一家廠商就要動核心邏輯，而且音訊只有 Gemini
 * 能用。這裡把「格式」跟「推理流程」切開——推理流程只管要什麼，
 * 格式由對應的 Adapter 負責，新增廠商只要加一個 Adapter，不必碰引擎。
 *
 * 兩個維度是分開的：
 *  - [LlmApiStyle]（文字）：request/response 的 JSON 長相
 *  - [LlmAudioStyle]（音訊）：怎麼把錄音塞進去，各家差異比文字更大
 *
 * 音訊實際上分三大流派（不是每家都一樣）：
 *  1. GEMINI_INLINE      — parts[] 裡放 inlineData{mimeType,data}
 *  2. OPENAI_INPUT_AUDIO — messages[].content[] 裡放 {type:input_audio, input_audio:{data,format}}
 *  3. WHISPER_TRANSCRIBE — 完全不同：multipart 上傳到 /audio/transcriptions，
 *                          先得到文字再交給文字模型（等於還是 STT，但用雲端大模型，
 *                          比裝置端 Vosk 準；對失語症仍不如原生音訊理解）
 */

/** 一次請求要的東西，與廠商無關。 */
data class LlmRequestSpec(
    val system: String,
    val user: String,
    val temperature: Double,
    val maxTokens: Int,
    /** 非 null＝要走音訊理解。 */
    val audioBase64: String? = null,
    /** 送出時宣告的音訊格式（"aac" / "wav" / "mp3"…），各家欄位名不同但值大致通用。 */
    val audioFormat: String = "aac"
)

/**
 * 廠商轉接介面。每家一個實作，負責：組 URL、組 header、組 body、解析回應。
 */
interface LlmAdapter {
    /** 文字（或含音訊的多模態）對話請求。 */
    fun buildRequest(entry: LlmApiEntry, spec: LlmRequestSpec): Request

    /** 從 HTTP 回應本體取出模型講的話。 */
    fun parseResponse(body: String): String

    companion object {
        /** 依設定挑對應的轉接器。 */
        fun of(entry: LlmApiEntry): LlmAdapter = when (entry.provider.style) {
            LlmApiStyle.OPENAI -> OpenAiCompatAdapter
            LlmApiStyle.COHERE -> CohereAdapter
            LlmApiStyle.GEMINI -> GeminiAdapter
            LlmApiStyle.ANTHROPIC -> AnthropicAdapter
        }

        internal val JSON = "application/json; charset=utf-8".toMediaType()

        /** 音訊 MIME：送 multipart 或宣告 mimeType 時用。 */
        internal fun mimeFor(format: String) = when (format.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> "audio/aac"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ① OpenAI 相容（Groq / OpenRouter / Cerebras / DeepSeek / Together / Mistral /
//    xAI / Fireworks / Nebius / SiliconFlow / Ollama / LM Studio…）
//    這是事實上的業界標準，最多家共用。音訊走 input_audio（OpenAI、Mistral Voxtral）。
// ─────────────────────────────────────────────────────────────────────────────
object OpenAiCompatAdapter : LlmAdapter {
    override fun buildRequest(entry: LlmApiEntry, spec: LlmRequestSpec): Request {
        if (entry.provider.needsKey && entry.key.isBlank()) error("API key 未設定")

        // 有音訊時，user 訊息的 content 要改成陣列形式（文字 + 音訊兩個 part）
        val userContent: Any = if (spec.audioBase64 != null) {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", spec.user))
                .put(
                    JSONObject()
                        .put("type", "input_audio")
                        .put(
                            "input_audio",
                            JSONObject()
                                .put("data", spec.audioBase64)
                                .put("format", spec.audioFormat)
                        )
                )
        } else spec.user

        val body = JSONObject()
            .put("model", entry.effectiveModel)
            .put("temperature", spec.temperature)
            .put("max_tokens", spec.maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", spec.system))
                    .put(JSONObject().put("role", "user").put("content", userContent))
            )
            .toString()

        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .header("Authorization", "Bearer ${entry.key.trim()}")
            .header("Content-Type", "application/json")
            // OpenRouter 要這兩個 header 做來源識別，其他家會忽略，帶著無害
            .header("HTTP-Referer", "https://speakmylife.local")
            .header("X-Title", "Speak My Life")
            .post(body.toRequestBody(LlmAdapter.JSON))
            .build()
    }

    override fun parseResponse(body: String): String =
        JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim()
}

// ─────────────────────────────────────────────────────────────────────────────
// ② Google Gemini — 自成一格：system 走 systemInstruction，音訊走 inlineData。
// ─────────────────────────────────────────────────────────────────────────────
object GeminiAdapter : LlmAdapter {
    override fun buildRequest(entry: LlmApiEntry, spec: LlmRequestSpec): Request {
        if (entry.key.isBlank()) error("API key 未設定")
        // model 進路徑、key 進查詢字串，兩個都是使用者自己輸入的
        // model 在「路徑」裡、key 在「查詢字串」裡，兩者的編碼規則不一樣：
        // URLEncoder 是給查詢字串用的，它把空白編成 +，放進路徑就是錯的。
        // 路徑段落要 %20，所以編完再把 + 換掉。
        fun pathSeg(v: String) = java.net.URLEncoder.encode(v.trim(), "UTF-8").replace("+", "%20")
        val url = "${entry.effectiveEndpoint.trimEnd('/')}/" + pathSeg(entry.effectiveModel) +
            ":generateContent?key=" + java.net.URLEncoder.encode(entry.key.trim(), "UTF-8")

        val parts = JSONArray().put(JSONObject().put("text", spec.user))
        if (spec.audioBase64 != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", LlmAdapter.mimeFor(spec.audioFormat))
                        .put("data", spec.audioBase64)
                )
            )
        }

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", spec.system))))
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject().put("temperature", spec.temperature).put("maxOutputTokens", spec.maxTokens)
            )
            .toString()

        return Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(LlmAdapter.JSON))
            .build()
    }

    override fun parseResponse(body: String): String =
        JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .optString("text").trim()
}

// ─────────────────────────────────────────────────────────────────────────────
// ③ Cohere v2 — messages 形狀近似 OpenAI，但回應包在 message.content[] 陣列裡。
//    不支援音訊。
// ─────────────────────────────────────────────────────────────────────────────
object CohereAdapter : LlmAdapter {
    override fun buildRequest(entry: LlmApiEntry, spec: LlmRequestSpec): Request {
        if (entry.key.isBlank()) error("API key 未設定")
        val body = JSONObject()
            .put("model", entry.effectiveModel)
            .put("temperature", spec.temperature)
            .put("max_tokens", spec.maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", spec.system))
                    .put(JSONObject().put("role", "user").put("content", spec.user))
            )
            .toString()
        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .header("Authorization", "Bearer ${entry.key.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(LlmAdapter.JSON))
            .build()
    }

    override fun parseResponse(body: String): String {
        val content = JSONObject(body).getJSONObject("message").getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) append(content.getJSONObject(i).optString("text"))
        }.trim()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ④ Anthropic Claude — 用 x-api-key 而非 Bearer、要 anthropic-version header、
//    system 是頂層欄位、回應在 content[].text。不支援音訊輸入。
// ─────────────────────────────────────────────────────────────────────────────
object AnthropicAdapter : LlmAdapter {
    private const val API_VERSION = "2023-06-01"

    override fun buildRequest(entry: LlmApiEntry, spec: LlmRequestSpec): Request {
        if (entry.key.isBlank()) error("API key 未設定")
        val body = JSONObject()
            .put("model", entry.effectiveModel)
            .put("max_tokens", spec.maxTokens)
            .put("temperature", spec.temperature)
            .put("system", spec.system)   // Anthropic 的 system 不放在 messages 裡
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", spec.user))
            )
            .toString()
        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .header("x-api-key", entry.key.trim())
            .header("anthropic-version", API_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(LlmAdapter.JSON))
            .build()
    }

    override fun parseResponse(body: String): String {
        val content = JSONObject(body).getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }.trim()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ⑤ Whisper 式轉寫（Groq / OpenAI / Mistral 的 /audio/transcriptions）
//    這個不是對話 API，是 multipart 檔案上傳，只回文字。
//    用途：沒有原生音訊模型時的中間路線——雲端 Whisper 通常比裝置端 Vosk 準，
//    但仍然是「先轉文字」，語調與含糊發音的資訊還是會丟掉。
// ─────────────────────────────────────────────────────────────────────────────
object WhisperTranscribeAdapter {
    /**
     * @param endpoint 轉寫端點（例：https://api.groq.com/openai/v1/audio/transcriptions）
     * @param languageHint ISO 語言碼，指定可提升準確度（"zh"）
     * @param promptHint 情境提示詞，Whisper 支援用它偏置詞彙（例如「醫院、吃藥、輪椅」）
     */
    fun buildRequest(
        endpoint: String,
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        audioFormat: String,
        languageHint: String? = "zh",
        promptHint: String? = null
    ): Request {
        if (apiKey.isBlank()) error("API key 未設定")
        val mime = LlmAdapter.mimeFor(audioFormat)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file", "audio.$audioFormat",
                audioBytes.toRequestBody(mime.toMediaType())
            )
        languageHint?.let { form.addFormDataPart("language", it) }
        // Whisper 的 prompt 可以偏置詞彙——對失語症場景很有用，
        // 把常見照護詞彙餵進去能降低同音誤判。
        promptHint?.takeIf { it.isNotBlank() }?.let { form.addFormDataPart("prompt", it) }
        form.addFormDataPart("response_format", "json")

        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(form.build())
            .build()
    }

    fun parseResponse(body: String): String =
        JSONObject(body).optString("text").trim()
}
