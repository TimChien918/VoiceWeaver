package com.example.ai

import com.example.data.SttApiEntry
import com.example.data.SttApiStyle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 語音辨識的請求組裝與回應解析。
 *
 * 各家的差異只有三個地方：認證標頭、送出格式、回應欄位。
 * 所以這裡只做這三件事的分派，重試與供應商排序留在呼叫端
 * （[CloudInferenceEngine]），跟文字 LLM 的失敗轉移用同一套邏輯。
 */
object SttClient {

    private const val TAG = "SttClient"

    /** 一次轉寫的結果。失敗時 [error] 會帶原因，呼叫端要據此決定換下一家還是放棄。 */
    data class Outcome(val text: String?, val error: String?) {
        val ok: Boolean get() = !text.isNullOrBlank()
    }

    /**
     * @param languageTag ISO 語言碼（"zh" / "en"），指定可明顯提升準確度
     * @param promptHint 情境詞彙偏置。Whisper 系列吃這個，對失語症場景很有用——
     *   把「醫院、吃藥、輪椅」餵進去能降低同音誤判。不支援的供應商會忽略。
     */
    suspend fun transcribe(
        client: OkHttpClient,
        entry: SttApiEntry,
        audioBytes: ByteArray,
        audioFormat: String,
        languageTag: String,
        promptHint: String?,
    ): Outcome {
        val req = runCatching { buildRequest(entry, audioBytes, audioFormat, languageTag, promptHint) }
            .getOrElse { return Outcome(null, "組裝請求失敗：${it.message}") }

        return runCatching {
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("HTTP ${res.code}: ${body.take(160)}")
                val text = parse(entry.provider.style, body)
                if (text.isBlank()) error("回傳空字串")
                Outcome(text, null)
            }
        }.getOrElse {
            DiagnosticLog.w(
                DiagnosticLog.Cat.STT, "語音辨識失敗（${entry.label}）",
                it.message.orEmpty().take(200),
            )
            Outcome(null, it.message)
        }
    }

    // ── 組裝 ────────────────────────────────────────────────────────────

    private fun buildRequest(
        entry: SttApiEntry,
        audio: ByteArray,
        format: String,
        lang: String,
        prompt: String?,
    ): Request {
        val endpoint = entry.effectiveEndpoint
        val model = entry.effectiveModel
        val key = entry.key.trim()
        val mime = LlmAdapter.mimeFor(format)
        if (entry.provider.needsKey && key.isBlank()) error("API key 未設定")

        return when (entry.provider.style) {
            // Groq / OpenAI / Mistral 共用 OpenAI 的 multipart 規格
            SttApiStyle.OPENAI_MULTIPART -> WhisperTranscribeAdapter.buildRequest(
                endpoint = endpoint,
                apiKey = key,
                model = model,
                audioBytes = audio,
                audioFormat = format,
                languageHint = lang,
                promptHint = prompt?.takeIf { it.isNotBlank() },
            )

            // Deepgram：音訊直接當 body，參數走 query string，認證是 Token 不是 Bearer
            SttApiStyle.DEEPGRAM -> {
                val url = buildString {
                    append(endpoint)
                    append(if (endpoint.contains('?')) "&" else "?")
                    append("model=").append(model)
                    append("&language=").append(lang)
                    // smart_format 會補標點與大小寫，對「拿去唸出來」的用途差很多
                    append("&smart_format=true")
                }
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Token $key")
                    .header("Content-Type", mime)
                    .post(audio.toRequestBody(mime.toMediaType()))
                    .build()
            }

            // ElevenLabs：multipart，但欄位叫 model_id、金鑰走自訂標頭
            SttApiStyle.ELEVENLABS -> {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model_id", model)
                    .addFormDataPart("file", "audio.$format", audio.toRequestBody(mime.toMediaType()))
                    .addFormDataPart("language_code", lang)
                    .build()
                Request.Builder()
                    .url(endpoint)
                    .header("xi-api-key", key)
                    .post(form)
                    .build()
            }

            // Gemini 沒有專門的轉寫端點，是「聽音訊直接回文字」。
            // 所以要明講「逐字照抄、不要修飾」——它預設會自動幫忙修語法，
            // 而那正是轉寫最不該做的事（復健評分要比對的是實際發音）。
            SttApiStyle.GEMINI_INLINE -> {
                val instruction = buildString {
                    append("Transcribe this audio verbatim. ")
                    append("Output only what was actually said, with no correction, ")
                    append("no rewriting, and no added punctuation beyond what the speech implies. ")
                    append("Do not explain. Output the transcription text only.")
                    if (!prompt.isNullOrBlank()) append(" Context vocabulary: ").append(prompt)
                }
                val b64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                val payload = JSONObject().put(
                    "contents",
                    org.json.JSONArray().put(
                        JSONObject().put(
                            "parts",
                            org.json.JSONArray()
                                .put(JSONObject().put("text", instruction))
                                .put(
                                    JSONObject().put(
                                        "inline_data",
                                        JSONObject().put("mime_type", mime).put("data", b64),
                                    )
                                )
                        )
                    )
                )
                Request.Builder()
                    .url("$endpoint/$model:generateContent?key=$key")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            }

            // 電腦端自架：跟既有的 9883 音訊伺服器同一種 JSON 約定
            SttApiStyle.LOCAL_SERVER -> {
                val b64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                val payload = JSONObject()
                    .put("audio_base64", b64)
                    .put("mime", mime)
                    .put("language", lang)
                    .put("model", model)
                Request.Builder()
                    .url(endpoint)
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            }
        }
    }

    // ── 解析 ────────────────────────────────────────────────────────────

    private fun parse(style: SttApiStyle, body: String): String = when (style) {
        SttApiStyle.OPENAI_MULTIPART -> WhisperTranscribeAdapter.parseResponse(body)

        SttApiStyle.DEEPGRAM -> JSONObject(body)
            .optJSONObject("results")
            ?.optJSONArray("channels")?.optJSONObject(0)
            ?.optJSONArray("alternatives")?.optJSONObject(0)
            ?.optString("transcript").orEmpty().trim()

        SttApiStyle.ELEVENLABS -> JSONObject(body).optString("text").trim()

        SttApiStyle.GEMINI_INLINE -> JSONObject(body)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text").orEmpty().trim()

        SttApiStyle.LOCAL_SERVER -> JSONObject(body).let {
            // 自架服務兩種欄位名都見過，兩個都接才不會因為換版本就壞掉
            it.optString("text").ifBlank { it.optString("transcription") }
        }.trim()
    }
}
