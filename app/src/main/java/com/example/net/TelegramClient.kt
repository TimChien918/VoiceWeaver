package com.example.net

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Telegram 發送統一入口。Token / 預設 Chat ID 都從 [BuildConfig]（由 secrets-gradle-plugin
 * 從 gitignored 的 `.env` 注入），**禁止**在原始碼中以字面值寫死。
 *
 * 使用方式：
 * ```
 * TelegramClient.sendEmergency(
 *   "🆘 [VoiceWeaver] 失語症使用者送出求救\n地點: 24.123, 120.456",
 *   chatId = null  // null = 用 BuildConfig.TELEGRAM_DEFAULT_CHAT_ID
 * )
 * ```
 */
object TelegramClient {

    private const val BASE_URL = "https://api.telegram.org/"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // 注意：刻意不掛 HttpLoggingInterceptor —— Telegram 的 URL 含有 bot token，
            // 一旦印到 logcat 會被 ASUS / 第三方工具撈走。要除錯時改用本物件回傳的
            // [Result] 訊息，不要開 OkHttp Logger。
            .build()
    }

    /** 媒體上傳用 client：上傳照片/音檔較久（行動網路上行慢），逾時拉長避免「timeout」失敗。 */
    private val uploadHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val api: TelegramApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TelegramApiService::class.java)
    }

    /**
     * 發送求救訊息。Network IO 自動切到 [Dispatchers.IO]。
     * 失敗會回傳 [Result.failure]（含 HTTP code），呼叫端可以選擇 UI 上提示「請手動撥打 119」。
     */
    suspend fun sendEmergency(
        message: String,
        chatId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        // 使用者在 API 金鑰管理填的 token 優先，空才退回 BuildConfig（.env）
        val token = com.example.data.ApiKeyOverrides.resolve(
            com.example.data.ApiKeyOverrides.telegramBotToken, BuildConfig.TELEGRAM_BOT_TOKEN
        )
        val target = chatId ?: BuildConfig.TELEGRAM_DEFAULT_CHAT_ID
        if (token.isBlank() || target.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Telegram secrets missing in BuildConfig (.env not picked up)")
            )
        }
        runCatching {
            val resp = api.sendMessage(
                url = "${BASE_URL}bot$token/sendMessage",
                body = TelegramSendMessageRequest(chatId = target, text = message)
            )
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code()} ${resp.errorBody()?.string()?.take(160).orEmpty()}")
            }
            val body = resp.body()
            if (body?.ok != true) {
                error("Telegram rejected: ${body?.errorCode} ${body?.description}")
            }
            "sent"
        }
    }

    // ─── 危機介入：媒體上傳 + 家人回覆輪詢（原生 OkHttp multipart / GET）───────────

    private fun token(): String = com.example.data.ApiKeyOverrides.resolve(
        com.example.data.ApiKeyOverrides.telegramBotToken, BuildConfig.TELEGRAM_BOT_TOKEN
    )
    private fun target(chatId: String?): String = chatId ?: BuildConfig.TELEGRAM_DEFAULT_CHAT_ID

    /** 上傳照片（前/後鏡頭擷取）給家人。method = sendPhoto，欄位 photo。 */
    suspend fun sendPhoto(file: File, caption: String, chatId: String? = null): Result<String> =
        uploadMedia("sendPhoto", "photo", "image/jpeg", file, caption, chatId)

    /** 上傳使用者主動錄的語音訊息給家人（m4a/aac，用 sendAudio）。 */
    suspend fun sendVoiceClip(file: File, caption: String, chatId: String? = null): Result<String> =
        uploadMedia("sendAudio", "audio", "audio/mp4", file, caption, chatId)

    private suspend fun uploadMedia(
        method: String, field: String, mime: String, file: File, caption: String, chatId: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val tk = token(); val tg = target(chatId)
        if (tk.isBlank() || tg.isBlank()) return@withContext Result.failure(IllegalStateException("Telegram 未設定"))
        if (!file.exists() || file.length() == 0L) return@withContext Result.failure(IllegalStateException("檔案不存在：${file.name}"))
        runCatching {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", tg)
                .addFormDataPart("caption", caption.take(1000))
                .addFormDataPart(field, file.name, file.asRequestBody(mime.toMediaType()))
                .build()
            val req = Request.Builder().url("${BASE_URL}bot$tk/$method").post(body).build()
            uploadHttp.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || !JSONObject(txt).optBoolean("ok", false)) {
                    error("HTTP ${resp.code} ${txt.take(140)}")
                }
                "sent"
            }
        }
    }

    /** 家人的一則回覆。 */
    data class FamilyReply(val text: String, val updateId: Long)

    /**
     * 輪詢家人在 Telegram 的回覆。回傳 (新的 offset, 新訊息清單)。
     * 只取來自緊急 chatId 的文字訊息；offset = 最後 update_id + 1（Telegram 規範）。
     */
    suspend fun pollFamilyReplies(offset: Long, chatId: String? = null): Result<Pair<Long, List<FamilyReply>>> =
        withContext(Dispatchers.IO) {
            val tk = token(); val tg = target(chatId)
            if (tk.isBlank()) return@withContext Result.failure(IllegalStateException("Telegram 未設定"))
            runCatching {
                val url = "${BASE_URL}bot$tk/getUpdates?timeout=0&allowed_updates=%5B%22message%22%5D" +
                    if (offset > 0) "&offset=$offset" else ""
                val req = Request.Builder().url(url).get().build()
                okHttp.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    val root = JSONObject(txt)
                    if (!root.optBoolean("ok", false)) error("getUpdates ${txt.take(120)}")
                    val arr = root.optJSONArray("result") ?: return@use (offset to emptyList<FamilyReply>())
                    var newOffset = offset
                    val replies = mutableListOf<FamilyReply>()
                    for (i in 0 until arr.length()) {
                        val upd = arr.getJSONObject(i)
                        val updateId = upd.optLong("update_id")
                        if (updateId + 1 > newOffset) newOffset = updateId + 1
                        val msg = upd.optJSONObject("message") ?: continue
                        val fromChat = msg.optJSONObject("chat")?.optLong("id")?.toString()
                        val text = msg.optString("text").orEmpty()
                        // 只收緊急 chat 的文字回覆
                        if (text.isNotBlank() && (fromChat == tg)) replies.add(FamilyReply(text, updateId))
                    }
                    newOffset to replies
                }
            }
        }
}
