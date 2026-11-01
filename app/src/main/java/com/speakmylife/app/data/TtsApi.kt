package com.speakmylife.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 語音合成的請求格式流派。各家差異比文字 LLM 還大（有的回原始音檔、有的回 base64、
 * 有的回 hex、有的要 SSML），所以一家一個實作，寫在 [com.speakmylife.app.ai.TtsAdapter]。
 */
enum class TtsApiStyle {
    /** 手機內建 TTS，不連網。 */
    SYSTEM,
    /** 跑在使用者電腦上的 GPT-SoVITS 聲音克隆（既有流程）。 */
    GPT_SOVITS,
    /** OpenAI /v1/audio/speech 相容：{model,input,voice}，直接回音檔 bytes。 */
    OPENAI_SPEECH,
    /** ElevenLabs：voiceId 在網址裡，xi-api-key 標頭。 */
    ELEVENLABS,
    /** Google Cloud TTS：回 JSON，audioContent 是 base64。 */
    GOOGLE_CLOUD,
    /** Azure 語音服務：body 是 SSML，回音檔 bytes。 */
    AZURE,
    /** Deepgram Aura：參數走 query string，body 只有 {text}。 */
    DEEPGRAM,
    /** Cartesia：X-API-Key + Cartesia-Version，output_format 要自己描述。 */
    CARTESIA,
    /** Fish Audio：{text,reference_id,format}。 */
    FISH_AUDIO,
    /** MiniMax：回 JSON，data.audio 是 **hex** 字串（不是 base64）。 */
    MINIMAX,
    /** PlayHT：X-User-Id + Authorization 雙標頭。 */
    PLAYHT,
    /** Speechify：OpenAI 形狀但回 JSON，audio_data 是 base64。 */
    SPEECHIFY
}

/**
 * 語音合成服務商（內建支援清單）。跟生圖／文字 LLM 同一套模式：
 * 挑服務商 + 填金鑰即可，同一家可加多筆（多把金鑰或多個聲音），合成時自動備援。
 *
 * @param defaultVoice 該家的預設聲音 id；使用者可在每一筆裡改成自己想要的。
 * @param voiceHint 聲音欄位的說明（去哪找 voice id）。
 * @param extraLabel 有些家需要第三個欄位（Azure 區域、MiniMax GroupId、PlayHT User ID）；
 *                   留空＝這家不需要，UI 就不會顯示那個欄位。
 * @param multilingual 是否支援中文；不支援中文的（例如純英文 Aura 2）合成中文會失敗，
 *                     排序時會被降權。
 * @param qualityBias 排序偏好修正。免金鑰的選項在通用評分裡本來就加分，但「手機內建語音」
 *                    再免費也不該壓過使用者付錢買的自然聲音，所以給它大幅負分當保底備援；
 *                    GPT-SoVITS 能複製家人的聲音、又免費又不出門，給正分。
 */
enum class TtsProvider(
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val defaultVoice: String,
    val style: TtsApiStyle,
    val needsKey: Boolean,
    val keyHint: String,
    val voiceHint: String = "",
    val extraLabel: String = "",
    val extraHint: String = "",
    val multilingual: Boolean = true,
    val qualityBias: Double = 0.0
) {
    // ── 免金鑰／本機 ────────────────────────────────────────────────────────
    SYSTEM(
        "手機內建語音（免金鑰）", "", "", "", TtsApiStyle.SYSTEM, false,
        "手機本身就有，離線可用，最穩但比較機械",
        qualityBias = -100.0
    ),
    GPT_SOVITS(
        // 跟生圖的「本機電腦」同一個模式：模型跑在使用者的 Mac/PC，手機走區網連過去。
        // 這是唯一能複製家人聲音的選項，所以放在最前面的付費選項之前。
        "GPT-SoVITS（跑在你的電腦）", "http://127.0.0.1:9880", "", "", TtsApiStyle.GPT_SOVITS, false,
        "在電腦開 GPT-SoVITS 語音中心，位址改成電腦的區網 IP（例 http://192.168.1.5:9880）",
        qualityBias = 30.0
    ),

    // ── OpenAI /v1/audio/speech 相容陣營 ──────────────────────────────────
    OPENAI(
        "OpenAI 語音", "https://api.openai.com/v1/audio/speech", "gpt-4o-mini-tts", "alloy",
        TtsApiStyle.OPENAI_SPEECH, true,
        "platform.openai.com（需付費）",
        voiceHint = "alloy／echo／fable／onyx／nova／shimmer／coral"
    ),
    GROQ(
        "Groq 語音", "https://api.groq.com/openai/v1/audio/speech", "playai-tts", "Fritz-PlayAI",
        TtsApiStyle.OPENAI_SPEECH, true,
        "console.groq.com/keys（很快，免費額度大）",
        voiceHint = "Fritz-PlayAI 等；中文請改用 playai-tts-arabic 以外的多語模型",
        multilingual = false
    ),
    SILICONFLOW(
        "SiliconFlow 語音", "https://api.siliconflow.cn/v1/audio/speech",
        "FunAudioLLM/CosyVoice2-0.5B", "FunAudioLLM/CosyVoice2-0.5B:alex",
        TtsApiStyle.OPENAI_SPEECH, true,
        "siliconflow.cn（中國區，中文自然度高）",
        voiceHint = "…:alex／…:anna／…:bella；也可填自己複製的音色 id"
    ),

    // ── 各自獨立格式 ──────────────────────────────────────────────────────
    ELEVENLABS(
        "ElevenLabs", "https://api.elevenlabs.io/v1/text-to-speech",
        "eleven_multilingual_v2", "21m00Tcm4TlvDq8ikWAM",
        TtsApiStyle.ELEVENLABS, true,
        "elevenlabs.io（情感最自然，可複製聲音）",
        voiceHint = "Voice ID（在 ElevenLabs 的 Voices 頁複製）"
    ),
    GOOGLE_TTS(
        "Google 雲端語音", "https://texttospeech.googleapis.com/v1/text:synthesize",
        "", "cmn-TW-Wavenet-A",
        TtsApiStyle.GOOGLE_CLOUD, true,
        "Google Cloud Console 啟用 Text-to-Speech API",
        voiceHint = "cmn-TW-Wavenet-A／-B／-C（台灣中文）"
    ),
    AZURE(
        "Azure 語音", "https://%s.tts.speech.microsoft.com/cognitiveservices/v1",
        "", "zh-TW-HsiaoChenNeural",
        TtsApiStyle.AZURE, true,
        "portal.azure.com 的語音服務（免費額度每月 50 萬字）",
        voiceHint = "zh-TW-HsiaoChenNeural／zh-TW-YunJheNeural",
        extraLabel = "區域 Region", extraHint = "例 eastasia、japaneast"
    ),
    DEEPGRAM(
        "Deepgram Aura", "https://api.deepgram.com/v1/speak", "aura-2-thalia-en", "",
        TtsApiStyle.DEEPGRAM, true,
        "deepgram.com（延遲極低，但目前只有英文）",
        voiceHint = "模型即聲音：aura-2-thalia-en 等",
        multilingual = false
    ),
    CARTESIA(
        "Cartesia Sonic", "https://api.cartesia.ai/tts/bytes", "sonic-2",
        "bf0a246a-8642-498a-9950-80c35e9276b5",
        TtsApiStyle.CARTESIA, true,
        "cartesia.ai（低延遲、可複製聲音）",
        voiceHint = "Voice ID（Cartesia 主控台複製）"
    ),
    FISH_AUDIO(
        "Fish Audio", "https://api.fish.audio/v1/tts", "speech-1.6", "",
        TtsApiStyle.FISH_AUDIO, true,
        "fish.audio（中文表現好，可上傳聲音複製）",
        voiceHint = "reference_id（留空＝用預設聲音）"
    ),
    MINIMAX(
        "MiniMax 語音", "https://api.minimax.io/v1/t2a_v2", "speech-2.5-hd-preview",
        "female-tianmei",
        TtsApiStyle.MINIMAX, true,
        "minimax.io（中文情感自然）",
        voiceHint = "female-tianmei／male-qn-qingse 等",
        extraLabel = "GroupId", extraHint = "在 MiniMax 主控台帳號資訊裡"
    ),
    PLAYHT(
        "PlayHT", "https://api.play.ht/api/v2/tts/stream", "Play3.0-mini",
        "s3://voice-cloning-zero-shot/d9ff78ba-d016-47f6-b0ef-dd630f59414e/female-cs/manifest.json",
        TtsApiStyle.PLAYHT, true,
        "play.ht（可複製聲音）",
        voiceHint = "Voice manifest 網址",
        extraLabel = "User ID", extraHint = "PlayHT 主控台的 User ID"
    ),
    SPEECHIFY(
        "Speechify", "https://api.sws.speechify.com/v1/audio/speech", "simba-multilingual", "scott",
        TtsApiStyle.SPEECHIFY, true,
        "speechify.com/api",
        voiceHint = "scott／lisa 等"
    );

    companion object {
        fun from(id: String?): TtsProvider = entries.firstOrNull { it.name == id } ?: SYSTEM
    }
}

/**
 * 一筆語音合成設定。endpoint／model／voice 留空時用 [TtsProvider] 預設值；
 * key 只存裝置 DataStore，不進版控。
 */
data class TtsApiEntry(
    val id: String,
    val provider: TtsProvider,
    val name: String = "",
    val endpoint: String = "",
    val model: String = "",
    val voice: String = "",
    val key: String = "",
    /** Azure 區域／MiniMax GroupId／PlayHT User ID 之類的第三個欄位。 */
    val extra: String = ""
) {
    val effectiveEndpoint: String get() {
        val base = endpoint.trim().ifBlank { provider.defaultEndpoint }
        // Azure 的網址帶區域；使用者只填 region，這裡補進去
        return if (provider.style == TtsApiStyle.AZURE && base.contains("%s")) {
            base.format(extra.trim().ifBlank { "eastasia" })
        } else base
    }
    val effectiveModel: String get() = model.trim().ifBlank { provider.defaultModel }
    val effectiveVoice: String get() = voice.trim().ifBlank { provider.defaultVoice }
    val label: String get() = name.trim().ifBlank { provider.displayName }

    /**
     * 能不能用。除了金鑰，還要檢查那些「沒填就一定失敗」的必要欄位——
     * 例如 Azure 沒填區域網址會組不出來、MiniMax 沒 GroupId 直接被拒。
     */
    val usable: Boolean get() {
        if (provider.needsKey && (key.trim().isBlank() || key.startsWith("YOUR_"))) return false
        if (provider.extraLabel.isNotBlank() && extra.trim().isBlank()) return false
        return true
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("provider", provider.name).put("name", name)
        .put("endpoint", endpoint).put("model", model).put("voice", voice)
        .put("key", key).put("extra", extra)

    companion object {
        fun fromJson(o: JSONObject) = TtsApiEntry(
            id = o.optString("id"),
            provider = TtsProvider.from(o.optString("provider")),
            name = o.optString("name"),
            endpoint = o.optString("endpoint"),
            model = o.optString("model"),
            voice = o.optString("voice"),
            key = o.optString("key"),
            extra = o.optString("extra")
        )
    }
}

/** 語音合成清單的 JSON 編解碼（存成單一 DataStore 字串）。 */
object TtsApiStore {
    fun encode(list: List<TtsApiEntry>): String =
        JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun decode(json: String): List<TtsApiEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { TtsApiEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
