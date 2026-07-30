package com.example.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 文字請求／回應的 JSON 格式流派。實際組 request 的程式在
 * [com.example.ai.LlmAdapter] 的各個實作裡，一家一個，互不干擾。
 */
enum class LlmApiStyle { OPENAI, COHERE, GEMINI, ANTHROPIC }

/**
 * 音訊輸入的格式流派——跟文字是**獨立**的維度，因為各家差異比文字更大。
 *
 * - [NONE]：不支援音訊輸入（只能先用 STT 轉文字）
 * - [GEMINI_INLINE]：parts[] 放 inlineData{mimeType,data}
 * - [OPENAI_INPUT_AUDIO]：messages[].content[] 放 {type:input_audio,...}
 * - [WHISPER_TRANSCRIBE]：另一個端點、multipart 上傳，只回文字（仍是 STT，
 *   但雲端 Whisper 通常比裝置端 Vosk 準，算中間路線）
 */
enum class LlmAudioStyle { NONE, GEMINI_INLINE, OPENAI_INPUT_AUDIO, WHISPER_TRANSCRIBE }

/**
 * 文字 LLM 服務商（內建支援清單）。使用者「自己加入」時挑一個服務商 + 填金鑰即可；
 * 同一服務商可建多筆（多把金鑰，可手動選 + 自動備援，像 agent 一樣多供應商輪替）。
 * 文字的「本機運算」是 App 內建的 on-device 引擎（雲端全失敗時自動退回），不需列在這裡。
 */
enum class LlmProvider(
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val style: LlmApiStyle,
    val needsKey: Boolean,
    val keyHint: String,
    /** 音訊輸入能力（預設不支援）。 */
    val audioStyle: LlmAudioStyle = LlmAudioStyle.NONE,
    /** 走音訊時要用的模型（很多家音訊要換不同 model id）；留空＝沿用 defaultModel。 */
    val audioModel: String = "",
    /** WHISPER_TRANSCRIBE 專用：轉寫端點與模型。 */
    val transcribeEndpoint: String = "",
    val transcribeModel: String = ""
) {
    // ── OpenAI 相容陣營（業界事實標準，最多家共用同一種 body）──
    GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions", "qwen/qwen3.6-27b", LlmApiStyle.OPENAI, true,
        "console.groq.com/keys（免費額度大）",
        // Groq 沒有原生音訊對話模型，但有很快的 Whisper 轉寫，當中間路線用
        audioStyle = LlmAudioStyle.WHISPER_TRANSCRIBE,
        transcribeEndpoint = "https://api.groq.com/openai/v1/audio/transcriptions",
        transcribeModel = "whisper-large-v3"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "qwen/qwen3-14b", LlmApiStyle.OPENAI, true, "openrouter.ai/keys（一把通多家模型）"),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1/chat/completions", "llama-3.3-70b", LlmApiStyle.OPENAI, true, "cloud.cerebras.ai（超快）"),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", LlmApiStyle.OPENAI, true,
        "platform.openai.com（需付費）",
        // gpt-audio 系列吃 input_audio；轉寫端點也一起帶著當備援
        audioStyle = LlmAudioStyle.OPENAI_INPUT_AUDIO,
        audioModel = "gpt-audio",
        transcribeEndpoint = "https://api.openai.com/v1/audio/transcriptions",
        transcribeModel = "whisper-1"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat", LlmApiStyle.OPENAI, true, "platform.deepseek.com"),
    TOGETHER("Together AI", "https://api.together.xyz/v1/chat/completions", "meta-llama/Llama-3.3-70B-Instruct-Turbo", LlmApiStyle.OPENAI, true, "api.together.xyz"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", LlmApiStyle.OPENAI, true,
        "console.mistral.ai",
        // Voxtral 是原生音訊理解模型，走 OpenAI 相容的 input_audio
        audioStyle = LlmAudioStyle.OPENAI_INPUT_AUDIO,
        audioModel = "voxtral-mini-latest",
        transcribeEndpoint = "https://api.mistral.ai/v1/audio/transcriptions",
        transcribeModel = "voxtral-mini-latest"),
    XAI("xAI Grok", "https://api.x.ai/v1/chat/completions", "grok-4-fast", LlmApiStyle.OPENAI, true, "console.x.ai"),
    FIREWORKS("Fireworks", "https://api.fireworks.ai/inference/v1/chat/completions", "accounts/fireworks/models/qwen3-30b-a3b", LlmApiStyle.OPENAI, true, "fireworks.ai"),
    SILICONFLOW("SiliconFlow", "https://api.siliconflow.cn/v1/chat/completions", "Qwen/Qwen3-14B", LlmApiStyle.OPENAI, true, "siliconflow.cn（中國區，中文佳）"),
    NEBIUS("Nebius", "https://api.studio.nebius.com/v1/chat/completions", "Qwen/Qwen3-14B", LlmApiStyle.OPENAI, true, "studio.nebius.com"),
    // 本機自架（Ollama / LM Studio）：不需要金鑰，區網直連，完全離線可用
    OLLAMA("Ollama（本機自架）", "http://127.0.0.1:11434/v1/chat/completions", "qwen3:14b", LlmApiStyle.OPENAI, false, "電腦跑 ollama serve；手機填電腦 IP"),
    LMSTUDIO("LM Studio（本機自架）", "http://127.0.0.1:1234/v1/chat/completions", "local-model", LlmApiStyle.OPENAI, false, "LM Studio 開 Local Server"),

    // ── 各自獨立格式 ──
    COHERE("Cohere", "https://api.cohere.com/v2/chat", "command-r7b-12-2024", LlmApiStyle.COHERE, true, "dashboard.cohere.com/api-keys"),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1/messages", "claude-sonnet-4-5", LlmApiStyle.ANTHROPIC, true, "console.anthropic.com"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/models", "gemini-3.5-flash", LlmApiStyle.GEMINI, true,
        "Google AI Studio",
        // Gemini 文字模型本身就吃音訊，不必換 model
        audioStyle = LlmAudioStyle.GEMINI_INLINE);

    companion object {
        fun from(id: String?): LlmProvider = entries.firstOrNull { it.name == id } ?: GROQ
    }
}

/** 一筆文字 LLM 設定。endpoint / model 留空時用 [LlmProvider] 預設值；key 只存裝置 DataStore。 */
data class LlmApiEntry(
    val id: String,
    val provider: LlmProvider,
    val name: String = "",
    val endpoint: String = "",
    val model: String = "",
    val key: String = ""
) {
    val effectiveEndpoint: String get() = endpoint.trim().ifBlank { provider.defaultEndpoint }
    val effectiveModel: String get() = model.trim().ifBlank { provider.defaultModel }
    val label: String get() = name.trim().ifBlank { provider.displayName }
    val usable: Boolean get() = !provider.needsKey || (key.trim().isNotBlank() && !key.startsWith("YOUR_"))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("provider", provider.name).put("name", name)
        .put("endpoint", endpoint).put("model", model).put("key", key)

    companion object {
        fun fromJson(o: JSONObject) = LlmApiEntry(
            id = o.optString("id"),
            provider = LlmProvider.from(o.optString("provider")),
            name = o.optString("name"),
            endpoint = o.optString("endpoint"),
            model = o.optString("model"),
            key = o.optString("key")
        )
    }
}

/** 文字 LLM 清單的 JSON 編解碼（存成單一 DataStore 字串）。 */
object LlmApiStore {
    fun encode(list: List<LlmApiEntry>): String =
        JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun decode(json: String): List<LlmApiEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { LlmApiEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
