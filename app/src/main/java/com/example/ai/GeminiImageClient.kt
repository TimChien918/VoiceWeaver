package com.example.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini 生圖呼叫端。對應 API 金鑰管理裡的「Gemini 生圖（自訂 API）」設定。
 *
 * 端點格式：{endpoint}/{model}:generateContent?key=API_KEY
 * （例：https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-preview-image-generation:generateContent）
 *
 * 回應從 candidates[].content.parts[].inlineData.data 取出 base64 PNG。
 * 金鑰由呼叫端從 [com.example.data.ApiKeyOverrides] 取得（不在這裡碰 BuildConfig）。
 */
object GeminiImageClient {

    private const val TAG = "GeminiImage"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 生圖較久
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /** 生圖結果：成功回 PNG bytes（必要時附帶模型回的文字說明）。 */
    suspend fun generate(
        endpoint: String,
        model: String,
        apiKey: String,
        prompt: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.startsWith("YOUR_")) {
            return@withContext Result.failure(IllegalStateException("尚未設定 Gemini 生圖金鑰"))
        }
        if (prompt.isBlank()) {
            return@withContext Result.failure(IllegalStateException("請先輸入要生成的圖片描述"))
        }
        val url = "${endpoint.trimEnd('/')}/$model:generateContent?key=$apiKey"
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            // 要圖片就必須宣告 responseModalities 含 IMAGE
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder().url(url).post(body).build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = parseError(text) ?: "HTTP ${resp.code}"
                    Log.w(TAG, "generate failed: $msg")
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val png = extractImage(text)
                    ?: return@withContext Result.failure(IllegalStateException("回應沒有圖片（可能被內容政策擋下或模型不支援生圖）"))
                Result.success(png)
            }
        }.getOrElse { Result.failure(it) }
    }

    /** 從 candidates[].content.parts[].inlineData.data 取第一張圖並 base64 解碼。 */
    private fun extractImage(json: String): ByteArray? = runCatching {
        val candidates = JSONObject(json).optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.getJSONObject(i)
                .optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val inline = parts.getJSONObject(j).optJSONObject("inlineData")
                    ?: parts.getJSONObject(j).optJSONObject("inline_data")
                val data = inline?.optString("data").orEmpty()
                if (data.isNotBlank()) return Base64.decode(data, Base64.DEFAULT)
            }
        }
        null
    }.getOrNull()

    private fun parseError(json: String): String? = runCatching {
        JSONObject(json).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
