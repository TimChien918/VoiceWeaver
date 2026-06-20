package com.example.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 生圖服務商（內建支援清單）。使用者「自己添加 API」時從這裡挑一個服務商，
 * 填上模型 / 金鑰即可；同一個服務商可以建立多筆（= 多把金鑰，可手動選 + 自動備援）。
 *
 * 每個服務商的呼叫方式不同，實際請求在 [com.example.ai.ImageGenClient] 依 provider 分派。
 */
enum class ImageProvider(
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    /** false = 免金鑰（例如 Pollinations）。 */
    val needsKey: Boolean,
    /** 申請金鑰的說明（UI 提示用）。 */
    val keyHint: String
) {
    LOCAL(
        // 跟 GPT-SoVITS 一樣在電腦本機運算，手機透過 USB/Wi-Fi 連到 Mac 的 :9881，免任何金鑰。
        // 端點實際主機由 App 依 GPT-SoVITS 連線設定自動帶入（USB=127.0.0.1、Wi-Fi=Mac IP）。
        "本機電腦（Mac，免金鑰）",
        "http://127.0.0.1:9881/generate",
        "sd-turbo",
        false,
        "用電腦本機運算，免金鑰"
    ),
    GEMINI(
        "Gemini 生圖",
        "https://generativelanguage.googleapis.com/v1beta/models",
        "gemini-2.5-flash-image",
        true,
        "Google AI Studio 申請"
    ),
    HUGGINGFACE(
        // HF 已把舊的 api-inference.huggingface.co 遷到 router.huggingface.co（Inference Providers）。
        // 實際 URL = {endpoint}/{model}，hf-inference 供應商可直接吃文生圖模型。
        "Hugging Face",
        "https://router.huggingface.co/hf-inference/models",
        "black-forest-labs/FLUX.1-schnell",
        true,
        "huggingface.co/settings/tokens（Read 即可）"
    ),
    POLLINATIONS(
        "Pollinations（免費）",
        "https://image.pollinations.ai/prompt",
        "flux",
        false,
        "免金鑰，直接可用"
    ),
    OPENAI(
        "OpenAI DALL·E 3",
        "https://api.openai.com/v1/images/generations",
        "dall-e-3",
        true,
        "platform.openai.com 申請（需付費）"
    ),
    STABILITY(
        "Stability AI",
        "https://api.stability.ai/v2beta/stable-image/generate/core",
        "core",
        true,
        "platform.stability.ai 申請"
    );

    companion object {
        fun from(id: String?): ImageProvider =
            entries.firstOrNull { it.name == id } ?: GEMINI
    }
}

/**
 * 一筆生圖 API 設定。endpoint / model 留空時用 [ImageProvider] 的預設值。
 * key 只存裝置 DataStore，不進版控。
 */
data class ImageApiEntry(
    val id: String,
    val provider: ImageProvider,
    val name: String,
    val endpoint: String = "",
    val model: String = "",
    val key: String = ""
) {
    val effectiveEndpoint: String get() = endpoint.trim().ifBlank { provider.defaultEndpoint }
    val effectiveModel: String get() = model.trim().ifBlank { provider.defaultModel }
    /** UI 顯示用：名稱（服務商）。 */
    val label: String get() = name.trim().ifBlank { provider.displayName }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("provider", provider.name)
        .put("name", name)
        .put("endpoint", endpoint)
        .put("model", model)
        .put("key", key)

    companion object {
        fun fromJson(o: JSONObject): ImageApiEntry = ImageApiEntry(
            id = o.optString("id"),
            provider = ImageProvider.from(o.optString("provider")),
            name = o.optString("name"),
            endpoint = o.optString("endpoint"),
            model = o.optString("model"),
            key = o.optString("key")
        )
    }
}

/** 生圖 API 清單的 JSON 編解碼（存成單一 DataStore 字串）。 */
object ImageApiStore {
    fun encode(list: List<ImageApiEntry>): String =
        JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun decode(json: String): List<ImageApiEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { ImageApiEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
