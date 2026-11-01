package com.speakmylife.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Telegram Bot API：發送純文字訊息。
 *
 * Endpoint：`POST https://api.telegram.org/bot{token}/sendMessage`
 *
 * 設計選擇：把 `{token}` 走在 URL path 上 (`@Path`)，這樣切換 Bot 不需要改 baseUrl，
 * 也避免硬碰硬把 token 進 OkHttp Interceptor 的 header（容易在 log 漏出）。
 */
interface TelegramApiService {

    // 用完整絕對 URL（@Url）而非 path 模板：token 含 ':'（如 8880092554:AAH...），
    // 放在 path 模板會被 Retrofit 誤判成 URL scheme → Malformed URL。絕對 URL 沒這問題。
    @POST
    suspend fun sendMessage(
        @Url url: String,
        @Body body: TelegramSendMessageRequest
    ): Response<TelegramSendMessageResponse>
}

@Serializable
data class TelegramSendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("text") val text: String,
    /** "MarkdownV2" / "HTML" / null。求救訊息用 HTML，方便加粗「緊急」字樣 */
    @SerialName("parse_mode") val parseMode: String? = "HTML",
    @SerialName("disable_web_page_preview") val disablePreview: Boolean = true
)

@Serializable
data class TelegramSendMessageResponse(
    @SerialName("ok") val ok: Boolean,
    @SerialName("description") val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null
)
