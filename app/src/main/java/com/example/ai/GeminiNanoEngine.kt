@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.example.ai

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FirebaseAIOnDeviceNotAvailableException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CoTResponse(
    val reasoning: String = "",
    val reconstructed: String = "",
    val confidence: Int = 50
)

/**
 * HIGH-END 推理引擎：透過 Firebase AI SDK 的 [InferenceMode.ONLY_ON_DEVICE] 模式，
 * 鎖定 Android AICore 系統服務執行 Gemini Nano 本機推理。
 *
 * 硬體需求：Android 14+，且裝置廠商已部署 AICore（Pixel 8+、Galaxy S24+ 等）。
 * 不發出任何網路請求，不需要 API Key，推理結果永遠留在裝置端。
 *
 * 若裝置不支援 AICore，[isAvailable] 回傳 false，Factory 會自動降級至 [MediaPipeLLMEngine]。
 */
class GeminiNanoEngine(private val context: Context) : InferenceEngine {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // OnDeviceConfig 鎖定 ONLY_ON_DEVICE，確保 SDK 不會偷偷 fallback 至雲端
    private val onDeviceConfig = OnDeviceConfig(
        mode = InferenceMode.ONLY_ON_DEVICE,
        maxOutputTokens = 512,
        temperature = 0.1f,
        topK = 1
    )

    private val generativeModel by lazy {
        Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            modelName = "gemini-nano-2-0-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            },
            systemInstruction = content {
                text(SYSTEM_PROMPT)
            },
            onDeviceConfig = onDeviceConfig
        )
    }

    override suspend fun reconstructSentence(vault: GlobalTagsVault): Result<ReconstructedData> =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val response = generativeModel.generateContent(buildUserPrompt(vault))
                val responseText = response.text
                    ?: return@withContext Result.failure(
                        Exception("Gemini Nano 回傳空結果，AICore 可能正在初始化")
                    )

                Log.d(TAG, "Raw Nano response: $responseText")

                val clean = responseText.trim()
                    .removeSurrounding("```json", "```").trim()
                val parsed = jsonParser.decodeFromString<CoTResponse>(clean)

                Result.success(
                    ReconstructedData(
                        originalText      = vault.rawText,
                        reconstructedText = parsed.reconstructed,
                        confidence        = parsed.confidence.coerceIn(0, 100) / 100.0,
                        engineUsed        = "On-Device Gemini Nano (AICore · NPU Accelerated)",
                        inferenceTimeMs   = System.currentTimeMillis() - startTime
                    )
                )
            } catch (e: FirebaseAIOnDeviceNotAvailableException) {
                // AICore 不可用（模型未下載、裝置不支援）
                Log.e(TAG, "AICore not available on this device: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Nano inference failed", e)
                Result.failure(e)
            }
        }

    private fun buildUserPrompt(vault: GlobalTagsVault) = buildString {
        append("{")
        append("\"rawText\":\"${vault.rawText.escapeJson()}\",")
        append("\"locationTag\":\"${vault.locationTag.escapeJson()}\",")
        append("\"visionTags\":[")
        append(vault.visionTags.joinToString(",") { "\"${it.escapeJson()}\"" })
        append("]}")
    }

    private fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "GeminiNanoEngine"

        /**
         * 這是 `val`（不是 `get()`），所以語言在建構時就固定了。
         * 使用者中途換介面語言時要重建引擎才會生效——實務上換語言本來就會
         * 重建，但這個相依性不寫下來很容易之後被誤改成常數。
         */
        private val SYSTEM_PROMPT: String get() = """
            You help a person with aphasia say what they mean. You run fully offline.
            Combine rawText (the fragments), locationTag, and visionTags (objects in view),
            reason briefly about the intent, then rebuild it as one sentence in
            ${com.example.ui.I18n.outputLanguageNameEn()}.

            Stay on the fragments: every content word must trace back to rawText or to one of
            the given tags. Add only the grammatical glue a sentence needs. Do not invent a new
            event, time, person, or question.

            Output JSON only: {"reasoning":"brief","reconstructed":"the sentence","confidence":85}

            ${com.example.ui.I18n.outputLanguageDirective("reconstructed")}
        """.trimIndent()

        /**
         * 透過 Android 系統特性旗標快速偵測 AICore 可用性（無副作用，毫秒級）。
         * android.software.generative_ai 是 Android 15 加入的標準 feature flag。
         */
        fun isAvailable(context: Context): Boolean = try {
            val ok = context.packageManager
                .hasSystemFeature("android.software.generative_ai")
            Log.i(TAG, "AICore (Gemini Nano) available = $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "AICore feature check error, assuming unavailable", e)
            false
        }
    }
}
