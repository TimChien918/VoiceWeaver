package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.data.ImageApiEntry
import com.example.data.ImageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 統一生圖呼叫端：依 [ImageApiEntry.provider] 分派到各服務商。
 * 全部回傳 PNG/影像 bytes 的 [Result]，失敗時帶可讀訊息（供清單自動備援判斷）。
 *
 * - GEMINI：沿用 [GeminiImageClient]（generateContent，base64 inlineData）。
 * - HUGGINGFACE：POST {endpoint}/{model}，body {"inputs": prompt}，回應直接是影像 bytes。
 * - POLLINATIONS：GET {endpoint}/{urlencoded prompt}，免金鑰，回應是影像 bytes。
 * - OPENAI：POST images/generations，回應 data[0].b64_json。
 * - STABILITY：POST v2beta core（multipart），Accept image 任意格式，回應是影像 bytes。
 */
object ImageGenClient {

    private const val TAG = "ImageGen"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 生圖較久
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    suspend fun generate(entry: ImageApiEntry, prompt: String): Result<ByteArray> {
        if (prompt.isBlank()) {
            return Result.failure(IllegalStateException("請先輸入要生成的圖片描述"))
        }
        val key = entry.key.trim()
        if (entry.provider.needsKey && (key.isBlank() || key.startsWith("YOUR_"))) {
            return Result.failure(IllegalStateException("「${entry.label}」尚未設定金鑰"))
        }
        return when (entry.provider) {
            ImageProvider.LOCAL -> local(entry, prompt)
            ImageProvider.GEMINI -> GeminiImageClient.generate(entry.effectiveEndpoint, entry.effectiveModel, key, prompt)
            ImageProvider.HUGGINGFACE -> huggingFace(entry, prompt)
            ImageProvider.POLLINATIONS -> pollinations(entry, prompt)
            ImageProvider.OPENAI -> openAi(entry, key, prompt)
            ImageProvider.STABILITY -> stability(entry, key, prompt)
        }
    }

    // ── 本機電腦（Mac）：POST {prompt} → image/png ─────────────────────────────
    private suspend fun local(entry: ImageApiEntry, prompt: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("prompt", prompt).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(entry.effectiveEndpoint).post(body).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val ct = resp.body?.contentType()?.toString().orEmpty()
                    if (!resp.isSuccessful || ct.startsWith("application/json")) {
                        val txt = bytes.toString(Charsets.UTF_8)
                        val msg = runCatching { JSONObject(txt).optString("error") }
                            .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(IllegalStateException("本機生圖：$msg"))
                    }
                    if (bytes.size < 256) return@withContext Result.failure(IllegalStateException("本機生圖回應為空"))
                    Result.success(bytes)
                }
            }.getOrElse {
                Result.failure(IllegalStateException("連不到電腦本機生圖（請確認 Mac 已啟動語音中心並接上 USB/Wi-Fi）：${it.message}"))
            }
        }

    // ── Hugging Face Inference API ──────────────────────────────────────────
    private suspend fun huggingFace(entry: ImageApiEntry, prompt: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val url = "${entry.effectiveEndpoint.trimEnd('/')}/${entry.effectiveModel}"
            val body = JSONObject().put("inputs", prompt).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${entry.key.trim()}")
                .header("Accept", "image/png")
                // 模型冷啟動時等它載入完再回，避免 503
                .header("x-wait-for-model", "true")
                .post(body)
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val ct = resp.body?.contentType()?.toString().orEmpty()
                    if (!resp.isSuccessful || ct.startsWith("application/json") || ct.contains("text")) {
                        val txt = bytes.toString(Charsets.UTF_8)
                        val msg = parseHfError(txt) ?: "HTTP ${resp.code}"
                        Log.w(TAG, "HF failed: $msg")
                        return@withContext Result.failure(IllegalStateException(msg))
                    }
                    if (bytes.size < 256) return@withContext Result.failure(IllegalStateException("回應影像為空"))
                    Result.success(bytes)
                }
            }.getOrElse { Result.failure(it) }
        }

    private fun parseHfError(json: String): String? = runCatching {
        val o = JSONObject(json)
        o.optString("error").takeIf { it.isNotBlank() }
            ?: o.optJSONArray("error")?.optString(0)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ── Pollinations（免金鑰）────────────────────────────────────────────────
    private suspend fun pollinations(entry: ImageApiEntry, prompt: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val enc = URLEncoder.encode(prompt, "UTF-8")
            val model = entry.effectiveModel.ifBlank { "flux" }
            // prompt 已經編碼了，model 卻沒有——它同樣是使用者自己打的
            val url = "${entry.effectiveEndpoint.trimEnd('/')}/$enc?model=" +
                URLEncoder.encode(model, "UTF-8") + "&nologo=true&width=1024&height=1024"
            val req = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}"))
                    }
                    if (bytes.size < 256) return@withContext Result.failure(IllegalStateException("回應影像為空"))
                    Result.success(bytes)
                }
            }.getOrElse { Result.failure(it) }
        }

    // ── OpenAI DALL·E ───────────────────────────────────────────────────────
    private suspend fun openAi(entry: ImageApiEntry, key: String, prompt: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", entry.effectiveModel)
                .put("prompt", prompt)
                .put("n", 1)
                .put("size", "1024x1024")
                .put("response_format", "b64_json")
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(entry.effectiveEndpoint)
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = runCatching { JSONObject(txt).optJSONObject("error")?.optString("message") }
                            .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(IllegalStateException(msg))
                    }
                    val b64 = runCatching {
                        JSONObject(txt).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")
                    }.getOrNull().orEmpty()
                    if (b64.isBlank()) return@withContext Result.failure(IllegalStateException("回應沒有影像"))
                    Result.success(Base64.decode(b64, Base64.DEFAULT))
                }
            }.getOrElse { Result.failure(it) }
        }

    // ── Stability AI v2beta（multipart）─────────────────────────────────────
    private suspend fun stability(entry: ImageApiEntry, key: String, prompt: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("output_format", "png")
                .build()
            val req = Request.Builder().url(entry.effectiveEndpoint)
                .header("Authorization", "Bearer $key")
                .header("Accept", "image/*")
                .post(multipart)
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val ct = resp.body?.contentType()?.toString().orEmpty()
                    if (!resp.isSuccessful || ct.startsWith("application/json")) {
                        val txt = bytes.toString(Charsets.UTF_8)
                        val msg = runCatching {
                            val o = JSONObject(txt)
                            o.optJSONArray("errors")?.optString(0) ?: o.optString("message")
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(IllegalStateException(msg))
                    }
                    if (bytes.size < 256) return@withContext Result.failure(IllegalStateException("回應影像為空"))
                    Result.success(bytes)
                }
            }.getOrElse { Result.failure(it) }
        }
}
