package com.example.data

import org.json.JSONArray
import org.json.JSONObject

/** 文字 LLM 的呼叫風格（決定 [com.example.ai.HybridInferenceEngine] 怎麼打 API）。 */
enum class LlmApiStyle { OPENAI, COHERE, GEMINI }

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
    val keyHint: String
) {
    GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions", "qwen/qwen3.6-27b", LlmApiStyle.OPENAI, true, "console.groq.com/keys（免費額度大）"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "qwen/qwen3-14b", LlmApiStyle.OPENAI, true, "openrouter.ai/keys（一把通多家模型）"),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1/chat/completions", "llama-3.3-70b", LlmApiStyle.OPENAI, true, "cloud.cerebras.ai（超快）"),
    COHERE("Cohere", "https://api.cohere.com/v2/chat", "command-r7b-12-2024", LlmApiStyle.COHERE, true, "dashboard.cohere.com/api-keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", LlmApiStyle.OPENAI, true, "platform.openai.com（需付費）"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat", LlmApiStyle.OPENAI, true, "platform.deepseek.com"),
    TOGETHER("Together AI", "https://api.together.xyz/v1/chat/completions", "meta-llama/Llama-3.3-70B-Instruct-Turbo", LlmApiStyle.OPENAI, true, "api.together.xyz"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", LlmApiStyle.OPENAI, true, "console.mistral.ai"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/models", "gemini-3.5-flash", LlmApiStyle.GEMINI, true, "Google AI Studio");

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
