package com.example.future

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * Hugging Face Inference API 手勢辨識 wrapper。
 *
 * **設計重點：強制走 Cloud**（Spec 要求 — 比讚 / 數錢 / OK 等手勢辨識本地 RAM 撐不住）。
 * 所以這個 service 不做任何本地 fallback；網路失敗就回傳 [Result.failure]，
 * UI 端應顯示「目前無法辨識，請改用圖卡」。
 *
 * Endpoint 樣式（HuggingFace Inference API）：
 *   `POST https://api-inference.huggingface.co/models/{modelId}`
 *   Header: `Authorization: Bearer hf_xxx`
 *   Body: base64 圖片
 */
interface SignLanguageHfApi {
    @POST("models/{modelId}")
    suspend fun classify(
        @Header("Authorization") authHeader: String,
        @Path("modelId", encoded = true) modelId: String,
        @Body request: HfImageRequest
    ): List<HfClassification>
}

@Serializable
data class HfImageRequest(
    /** base64-encoded image bytes（PNG/JPEG）*/
    @SerialName("inputs") val inputsBase64: String
)

@Serializable
data class HfClassification(
    @SerialName("label") val label: String,
    @SerialName("score") val score: Float
)

object SignLanguageClient {

    /** 可以換成你 fine-tune 過的手勢模型；先用一個 placeholder。 */
    private const val DEFAULT_MODEL = "google/vit-base-patch16-224"
    private const val BASE_URL = "https://api-inference.huggingface.co/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val api: SignLanguageHfApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SignLanguageHfApi::class.java)
    }

    /**
     * 傳入相機 frame 的 PNG/JPEG bytes，回傳 top-1 label。
     * **故意不接受 local fallback**——失敗就讓 UI 引導使用者改回圖卡。
     */
    suspend fun classifyGesture(imageBytes: ByteArray, modelId: String = DEFAULT_MODEL): Result<HfClassification> =
        withContext(Dispatchers.IO) {
            val token = runCatching {
                // 重用 GEMINI/GROQ 的 .env 注入路徑：在 .env 加 HF_API_KEY=hf_xxx
                BuildConfig::class.java.getField("HF_API_KEY").get(null) as? String
            }.getOrNull().orEmpty()

            if (token.isBlank()) {
                return@withContext Result.failure(IllegalStateException(
                    "HF_API_KEY 未設定。請在 .env 加上 HF_API_KEY=hf_xxx 後重新編譯"))
            }
            runCatching {
                val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                val raw = api.classify(
                    authHeader = "Bearer $token",
                    modelId = modelId,
                    request = HfImageRequest(inputsBase64 = base64)
                )
                raw.maxByOrNull { it.score } ?: error("HF returned empty list")
            }
        }
}
