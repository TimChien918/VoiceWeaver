package com.speakmylife.app.ai

import android.util.Base64
import com.speakmylife.app.data.TtsApiEntry
import com.speakmylife.app.data.TtsApiStyle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 各家語音合成 API 的格式轉接層——跟 [LlmAdapter] 同一個思路：
 * 「怎麼組請求、怎麼把回應變成音檔位元組」一家一個實作，
 * 合成流程本身不必知道任何廠商細節，新增一家只要多一個 object。
 *
 * 語音合成的廠商差異比文字 LLM 還大，大致四種回應形態：
 *  1. 直接回音檔 bytes（OpenAI 相容、ElevenLabs、Azure、Deepgram、Cartesia、Fish、PlayHT）
 *  2. 回 JSON 且音檔是 base64（Google Cloud、Speechify）
 *  3. 回 JSON 且音檔是 **hex** 字串（MiniMax——這點很容易踩雷，不是 base64）
 *  4. 完全不走 HTTP（手機內建 TTS、GPT-SoVITS 走自己既有的流程）
 */
interface TtsAdapter {

    /** 組出這次合成請求。 */
    fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request

    /**
     * 把回應變成可播放的音檔位元組。
     * @param bytes 原始回應內容（可能是音檔，也可能是 JSON 文字）
     */
    fun parseAudio(bytes: ByteArray): ByteArray

    /** 產生的音檔副檔名（決定存檔名稱，MediaPlayer 靠這個判斷格式）。 */
    val fileExt: String get() = "mp3"

    companion object {
        internal val JSON = "application/json; charset=utf-8".toMediaType()

        /** 只有真的走 HTTP 的流派才有轉接器；SYSTEM／GPT_SOVITS 回 null。 */
        fun of(entry: TtsApiEntry): TtsAdapter? = when (entry.provider.style) {
            TtsApiStyle.OPENAI_SPEECH -> OpenAiSpeechAdapter
            TtsApiStyle.ELEVENLABS -> ElevenLabsAdapter
            TtsApiStyle.GOOGLE_CLOUD -> GoogleCloudTtsAdapter
            TtsApiStyle.AZURE -> AzureTtsAdapter
            TtsApiStyle.DEEPGRAM -> DeepgramAdapter
            TtsApiStyle.CARTESIA -> CartesiaAdapter
            TtsApiStyle.FISH_AUDIO -> FishAudioAdapter
            TtsApiStyle.MINIMAX -> MiniMaxAdapter
            TtsApiStyle.PLAYHT -> PlayHtAdapter
            TtsApiStyle.SPEECHIFY -> SpeechifyAdapter
            TtsApiStyle.SYSTEM, TtsApiStyle.GPT_SOVITS -> null
        }

        /** JSON 回應裡挖出錯誤訊息，讓 log 看得懂是哪裡不對。 */
        internal fun errorOf(bytes: ByteArray): String {
            val s = String(bytes).take(300)
            return runCatching {
                val o = JSONObject(s)
                o.optJSONObject("error")?.optString("message")
                    ?: o.optString("message").ifBlank { s }
            }.getOrDefault(s)
        }
    }
}

// ─── 1. OpenAI /v1/audio/speech 相容（OpenAI、Groq、SiliconFlow）─────────────

object OpenAiSpeechAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        val body = JSONObject()
            .put("model", entry.effectiveModel)
            .put("input", text)
            .put("voice", entry.effectiveVoice)
            .put("response_format", "mp3")
            .toString()
        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .addHeader("Authorization", "Bearer ${entry.key.trim()}")
            .post(body.toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray) = bytes
}

// ─── 2. ElevenLabs：voice id 在網址裡 ────────────────────────────────────────

object ElevenLabsAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", entry.effectiveModel)
            .put("voice_settings", JSONObject()
                .put("stability", 0.5)
                .put("similarity_boost", 0.75))
            .toString()
        return Request.Builder()
            .url("${entry.effectiveEndpoint.trimEnd('/')}/${entry.effectiveVoice}")
            .addHeader("xi-api-key", entry.key.trim())
            .addHeader("Accept", "audio/mpeg")
            .post(body.toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray) = bytes
}

// ─── 3. Google Cloud：回 JSON，audioContent 是 base64 ────────────────────────

object GoogleCloudTtsAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        // voice 名稱前綴就是語言碼（cmn-TW-Wavenet-A → cmn-TW），沒填就用系統語言
        val voice = entry.effectiveVoice
        val lang = voice.split("-").take(2).joinToString("-").ifBlank { langTag }
        val body = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", lang).put("name", voice))
            .put("audioConfig", JSONObject().put("audioEncoding", "MP3"))
            .toString()
        return Request.Builder()
            .url("${entry.effectiveEndpoint}?key=${entry.key.trim()}")
            .post(body.toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray): ByteArray {
        val b64 = JSONObject(String(bytes)).optString("audioContent")
        if (b64.isBlank()) error("Google TTS 沒有回音檔：${TtsAdapter.errorOf(bytes)}")
        return Base64.decode(b64, Base64.DEFAULT)
    }
}

// ─── 4. Azure：body 是 SSML，不是 JSON ──────────────────────────────────────

object AzureTtsAdapter : TtsAdapter {
    private val SSML = "application/ssml+xml".toMediaType()

    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        val voice = entry.effectiveVoice
        val lang = voice.split("-").take(2).joinToString("-").ifBlank { langTag }
        val ssml = """
            <speak version='1.0' xml:lang='$lang'>
              <voice xml:lang='$lang' name='$voice'>${escapeXml(text)}</voice>
            </speak>
        """.trimIndent()
        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .addHeader("Ocp-Apim-Subscription-Key", entry.key.trim())
            // 這個標頭決定回傳音質；不給的話 Azure 會直接 400
            .addHeader("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            .addHeader("User-Agent", "SpeakMyLife")
            .post(ssml.toRequestBody(SSML))
            .build()
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    override fun parseAudio(bytes: ByteArray) = bytes
}

// ─── 5. Deepgram Aura：模型走 query string ─────────────────────────────────

object DeepgramAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        // 模型名是使用者在設定裡自己打的，一定要編碼：帶空白會讓 URL 直接壞掉，
        // 帶 & 等於偷偷多塞一個查詢參數進去。
        val url = "${entry.effectiveEndpoint}?model=" +
            java.net.URLEncoder.encode(entry.effectiveModel, "UTF-8") + "&encoding=mp3"
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token ${entry.key.trim()}")
            .post(JSONObject().put("text", text).toString().toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray) = bytes
}

// ─── 6. Cartesia ──────────────────────────────────────────────────────────

object CartesiaAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        val body = JSONObject()
            .put("model_id", entry.effectiveModel)
            .put("transcript", text)
            .put("voice", JSONObject().put("mode", "id").put("id", entry.effectiveVoice))
            .put("language", if (langTag.startsWith("zh")) "zh" else "en")
            .put("output_format", JSONObject()
                .put("container", "mp3")
                .put("sample_rate", 44100)
                .put("bit_rate", 128000))
            .toString()
        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .addHeader("X-API-Key", entry.key.trim())
            // 版本標頭是必填，漏了會被擋
            .addHeader("Cartesia-Version", "2024-06-10")
            .post(body.toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray) = bytes
}

// ─── 7. Fish Audio ────────────────────────────────────────────────────────

object FishAudioAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        val body = JSONObject()
            .put("text", text)
            .put("format", "mp3")
            .apply {
                // reference_id 留空＝用平台預設聲音，不能送空字串（會被當成找不到聲音）
                if (entry.effectiveVoice.isNotBlank()) put("reference_id", entry.effectiveVoice)
            }
            .toString()
        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .addHeader("Authorization", "Bearer ${entry.key.trim()}")
            .addHeader("model", entry.effectiveModel)
            .post(body.toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray) = bytes
}

// ─── 8. MiniMax：音檔是 hex 不是 base64 ────────────────────────────────────

object MiniMaxAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        val body = JSONObject()
            .put("model", entry.effectiveModel)
            .put("text", text)
            .put("stream", false)
            .put("voice_setting", JSONObject()
                .put("voice_id", entry.effectiveVoice)
                .put("speed", 1.0).put("vol", 1.0).put("pitch", 0))
            .put("audio_setting", JSONObject()
                .put("sample_rate", 32000).put("bitrate", 128000).put("format", "mp3"))
            .toString()
        // GroupId 走 query string，放在 body 裡是無效的
        return Request.Builder()
            .url("${entry.effectiveEndpoint}?GroupId=${entry.extra.trim()}")
            .addHeader("Authorization", "Bearer ${entry.key.trim()}")
            .post(body.toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray): ByteArray {
        val hex = JSONObject(String(bytes)).optJSONObject("data")?.optString("audio").orEmpty()
        if (hex.isBlank()) error("MiniMax 沒有回音檔：${TtsAdapter.errorOf(bytes)}")
        return ByteArray(hex.length / 2) {
            ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
        }
    }
}

// ─── 9. PlayHT：雙標頭 ────────────────────────────────────────────────────

object PlayHtAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        val body = JSONObject()
            .put("text", text)
            .put("voice", entry.effectiveVoice)
            .put("voice_engine", entry.effectiveModel)
            .put("output_format", "mp3")
            .put("language", if (langTag.startsWith("zh")) "mandarin" else "english")
            .toString()
        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .addHeader("Authorization", "Bearer ${entry.key.trim()}")
            .addHeader("X-User-Id", entry.extra.trim())
            .addHeader("Accept", "audio/mpeg")
            .post(body.toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray) = bytes
}

// ─── 10. Speechify：OpenAI 形狀但回 JSON base64 ───────────────────────────

object SpeechifyAdapter : TtsAdapter {
    override fun buildRequest(entry: TtsApiEntry, text: String, langTag: String): Request {
        val body = JSONObject()
            .put("input", text)
            .put("voice_id", entry.effectiveVoice)
            .put("model", entry.effectiveModel)
            .put("audio_format", "mp3")
            .toString()
        return Request.Builder()
            .url(entry.effectiveEndpoint)
            .addHeader("Authorization", "Bearer ${entry.key.trim()}")
            .post(body.toRequestBody(TtsAdapter.JSON))
            .build()
    }

    override fun parseAudio(bytes: ByteArray): ByteArray {
        val b64 = JSONObject(String(bytes)).optString("audio_data")
        if (b64.isBlank()) error("Speechify 沒有回音檔：${TtsAdapter.errorOf(bytes)}")
        return Base64.decode(b64, Base64.DEFAULT)
    }
}
