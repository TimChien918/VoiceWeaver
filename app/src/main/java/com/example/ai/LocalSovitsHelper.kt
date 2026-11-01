package com.example.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * GPT-SoVITS 伺服器輔助工具。
 *
 * 背景：GPT-SoVITS 是 Python + PyTorch 的桌面框架，Android 沒辦法原生跑 .pth/.ckpt 權重
 * （PyTorch Mobile 不支援其完整 ops）。
 *
 * 重要：官方 PyTorch pip wheel 不支援 Android/Termux 的 Python ABI。
 * 所以 `pip install torch --index-url https://download.pytorch.org/whl/cpu`
 * 會出現 No matching distribution。可靠路線是：
 *
 *   1. 在 Mac/PC 跑 GPT-SoVITS `api_v2.py`。
 *   2. 手機和電腦連同一個 Wi-Fi。
 *   3. VoiceWeaver 把「GPT-SoVITS API 位址」設成 `http://電腦IP:9880`。
 *
 * 這個 helper 提供：
 *  - [pingServer]：探測指定 GPT-SoVITS API 位址是否有伺服器在跑
 *  - [DEFAULT_LOCAL_URL]：給設定預設值用，省得使用者第一次要打字
 */
object LocalSovitsHelper {

    private const val TAG = "LocalSovits"

    /** 預設本機 API URL。只有 server 真的跑在手機本機時才適用；一般建議改填 Mac/PC IP。 */
    const val DEFAULT_LOCAL_URL = "http://127.0.0.1:9880"

    /** USB／區網用。這條線上連不到就是真的沒開，等再久也一樣。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * Tailscale／公網用。
     *
     * 這裡的逾時**不是**保守起見放寬的，而是那條線本來就比較慢：
     * Tailscale 的第一個封包要先把 WireGuard 隧道拉起來，直連（NAT 穿透）談不成
     * 還會退回 DERP 中繼繞一圈；手機剛從休眠醒來或走 4G/5G 時更明顯。
     * 用區網的 2 秒去量它，結果就是「明明服務開著，App 說連不到」。
     */
    private val remoteClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private const val SOCKET_TIMEOUT_LAN_MS = 1200
    private const val SOCKET_TIMEOUT_REMOTE_MS = 6000

    /**
     * 這個位址是不是「不在同一條線上」。
     *
     * Tailscale 給的是 100.64.0.0/10（CGNAT 保留段）的位址，或 MagicDNS 的
     * `*.ts.net`；兩者都要走隧道。區網（10/172.16-31/192.168）與 loopback
     * （USB 走 adb reverse 就是 127.0.0.1）則是直通的。
     * 認不出來的主機名一律當遠端——猜錯的代價只是慢一點，反過來猜錯是連不上。
     */
    internal fun isRemoteHost(host: String): Boolean {
        if (host.equals("localhost", true) || host.startsWith("127.") || host == "::1") return false
        if (host.endsWith(".ts.net", true)) return true
        val p = host.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size == 4 && host.count { it == '.' } == 3) {
            if (p[0] == 100 && p[1] in 64..127) return true          // Tailscale CGNAT
            if (p[0] == 10) return false
            if (p[0] == 192 && p[1] == 168) return false
            if (p[0] == 172 && p[1] in 16..31) return false
            return true                                              // 其餘 IPv4＝公網
        }
        return true
    }

    private fun httpFor(baseUrl: String): OkHttpClient {
        val h = runCatching { URI(baseUrl).host }.getOrNull() ?: return client
        return if (isRemoteHost(h)) remoteClient else client
    }

    /**
     * 連線健檢。先用 raw socket 判斷 port 是否開（快、不發 HTTP），
     * 若 open 再打一個 GET /health 確認 HTTP server 有應答。
     */
    suspend fun pingServer(baseUrl: String): PingResult = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_LOCAL_URL }
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return@withContext PingResult.HttpError(-2)
        val host = uri.host ?: return@withContext PingResult.HttpError(-2)
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }

        val remote = isRemoteHost(host)

        // ── 1) Socket layer ──
        val socketAlive = runCatching {
            Socket().use { s ->
                s.connect(
                    InetSocketAddress(host, port),
                    if (remote) SOCKET_TIMEOUT_REMOTE_MS else SOCKET_TIMEOUT_LAN_MS
                )
                true
            }
        }.getOrDefault(false)
        // 區網／USB 探不到就是真的沒開，直接回報，不必再多等一輪 HTTP。
        // 但**遠端不能這樣判死**：Tailscale 第一次連要先把隧道拉起來，談不成直連
        // 還會繞 DERP 中繼，這一下常常比 socket 逾時久。在這裡就回 PortClosed 的話，
        // 使用者看到的是「連不到」，實際上再試一次就通了。遠端一律往下走 HTTP 再確認。
        if (!socketAlive && !remote) return@withContext PingResult.PortClosed(false)

        // ── 2) HTTP layer：SoVITS api_v2 在 / 回 404（沒這 route）但 server 會應答；/health 通常 200 ──
        val req = Request.Builder().url("$normalized/health").get().build()
        runCatching {
            httpFor(normalized).newCall(req).execute().use { resp ->
                Log.i(TAG, "SoVITS HTTP ${resp.code} @ $normalized（remote=$remote）")
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                // 位址填到語音中心的**橋接埠 9879** 了。這裡一定要擋下來：
                // 橋接的 /health 回 200，測試連線會亮綠燈，但 App 合成打的是 api_v2 的
                // /tts，橋接沒有那個路由——於是「測試通過，真的要唸的時候卻失敗」。
                // 網頁版填 9879 是對的（它走 /speak），App 要的是 9880，
                // 兩邊的說明不一樣，貼錯是很自然的事。
                if (resp.code == 200 && body.contains("voiceweaver-bridge")) {
                    return@use PingResult.BridgePort
                }
                if (resp.code == 200) {
                    // 先從伺服器更新可用模型清單（電腦端新增/刪除的模型即時反映）
                    refreshModels(normalized)
                    // /health 會回目前載入的 weights 路徑 — 比對權重檔名找出是哪個聲音（含語言標籤）。
                    VOICES.firstOrNull {
                        body.contains(it.sovitsWeights.substringAfterLast('/')) ||
                        body.contains(it.gptWeights.substringAfterLast('/'))
                    }?.let {
                        currentModel = it
                        if (it.lang == VoiceLang.ZH) lastZhCharacter = it.character
                        Log.i(TAG, "偵測到 server 模型：${it.displayLabel}")
                    }
                }
                if (resp.code in 200..499) PingResult.Reachable
                else PingResult.HttpError(resp.code)
            }
            // socket 沒通、HTTP 也沒通 → 說「連不到」比說「HTTP 失敗」準確
        }.getOrElse { e ->
            // 遠端連不上時把原因寫進診斷日誌——使用者可以直接匯出給開發者看，
            // 不必靠他轉述「就是連不上」。
            if (remote) DiagnosticLog.e(
                DiagnosticLog.Cat.NET, "遠端語音伺服器連不上",
                "位址=$normalized socket=${if (socketAlive) "通" else "不通"} 原因=${e.message}"
            )
            if (socketAlive) PingResult.HttpError(-1) else PingResult.PortClosed(remote)
        }
    }

    enum class PingResultKind { PortClosed, Reachable, HttpError, BridgePort }
    sealed class PingResult(val kind: PingResultKind, val label: String) {
        /**
         * 連不到。**建議要看位址給**：對 Tailscale 位址講「請連同一個 Wi-Fi」
         * 是完全錯的指引——那條路本來就不需要同網段，照著查只會白費時間。
         */
        data class PortClosed(val remote: Boolean = false) : PingResult(
            PingResultKind.PortClosed,
            if (remote)
                "❌ 連不到這台電腦。請確認：① 手機的 Tailscale 有連上（VPN 圖示亮著）" +
                    "② 電腦的語音中心開著、9880 綠燈 ③ 電腦防火牆沒擋住 Python" +
                    "④ 埠是 9880（不是網頁版用的 9879）"
            else
                "❌ 連不到 API 位址，請確認 server 已啟動、IP 正確、同一個 Wi-Fi"
        )
        object Reachable  : PingResult(PingResultKind.Reachable,  "✅ SoVITS server 通了，可以合成測試")
        data class HttpError(val code: Int) : PingResult(PingResultKind.HttpError, "⚠ port 開但 HTTP $code，請檢查 api_v2.py log 或 API URL")
        /** 連到的是語音中心的橋接埠（9879），不是 GPT-SoVITS 本體（9880）。
         *  這種情況測試會通、合成一定失敗，所以要當成錯誤講清楚。 */
        object BridgePort : PingResult(PingResultKind.BridgePort, "⚠ 這是語音中心的橋接埠（9879）。App 要直接連 GPT-SoVITS，請把位址的埠改成 9880（網頁版才是填 9879）")
    }

    /**
     * 連線模式：無線 vs 有線。
     *  - **Wireless (Wi-Fi/LAN)**：手機與 Mac 連同一個區網，App 填 `http://<Mac_LAN_IP>:9880`
     *  - **Wired (USB / adb reverse)**：USB 線連電腦，Mac 跑 `adb reverse tcp:9880 tcp:9880`，
     *    手機的 `127.0.0.1:9880` 經 USB tunnel 轉發到 Mac 的 9880，跨網段、無延遲。
     *
     * 為什麼支援有線：Wi-Fi 不穩或外出場景（醫院 / 復健中心 Wi-Fi 不能用）時，
     * 直接拿 USB 線接電腦就行；adb reverse 是 Android 官方支援的方式，不需 root。
     */
    enum class ConnectionMode(val displayName: String, val description: String) {
        Wireless("無線 (Wi-Fi LAN)", "手機與電腦連同一個 Wi-Fi。API URL 填電腦 IP，例 http://192.168.1.x:9880"),
        Wired("有線 (USB)", "USB 線接電腦。電腦先跑 adb reverse tcp:9880 tcp:9880；App 填 http://127.0.0.1:9880")
    }

    /** Mac/PC 端指令：開啟 USB 反向轉發。使用者複製到電腦終端機跑。*/
    val ADB_REVERSE_COMMAND = "adb reverse tcp:9880 tcp:9880"

    /** 啟動 server 的指令（Mac/PC 端 — 電腦端已搬到 ~/VoiceWeaver-電腦端，與 Android 專案分離）*/
    val START_SERVER_COMMAND =
        "cd ~/VoiceWeaver-電腦端/GPT-SoVITS && ./start_api.sh"

    // ref_audio_path 是「server filesystem path」（Mac 本地絕對路徑），不接受手機路徑；
    // 模型的 ref/prompt 由伺服器 /models 提供，App 不再寫死任何路徑。

    /** 語音模型語言標籤。模型檔名含 `_ZH`/`_EN`/`_JA`/`_KO` 後綴，由 [langFromModelName] 解析。 */
    enum class VoiceLang(val tag: String, val ttsLang: String, val displayName: String) {
        ZH("_ZH", "zh", "中文"),
        EN("_EN", "en", "English"),
        JA("_JA", "ja", "日本語"),
        KO("_KO", "ko", "한국어")
    }

    /**
     * 從模型名稱/檔名解析語言標籤。容忍底線或空格分隔（「我的聲音_ZH」「我的聲音 ZH」皆可）。
     * _ZH/_CN→中、_EN→英、_JA/_JP→日、_KO/_KR→韓。
     */
    fun langFromModelName(name: String): VoiceLang? {
        val m = Regex("[ _](ZH|CN|EN|JA|JP|KO|KR)\\b", RegexOption.IGNORE_CASE).find(name) ?: return null
        return when (m.groupValues[1].uppercase()) {
            "ZH", "CN" -> VoiceLang.ZH
            "EN" -> VoiceLang.EN
            "JA", "JP" -> VoiceLang.JA
            "KO", "KR" -> VoiceLang.KO
            else -> null
        }
    }

    /** App 介面語言 → 語音語言（系統語言不同 → 使用語言不同的依據）。 */
    fun langFromAppTag(appTag: String): VoiceLang = when {
        appTag.startsWith("ja") -> VoiceLang.JA
        appTag.startsWith("ko") -> VoiceLang.KO
        appTag.startsWith("en") -> VoiceLang.EN
        else -> VoiceLang.ZH
    }

    /**
     * 從文字字元偵測語言：諺文→韓、假名→日、CJK 漢字→中、其餘→英。
     * 日文常混漢字，故先看假名/諺文；都沒有再用漢字判中文。
     */
    // ── 情緒（換參考音＝換語氣）──────────────────────────
    const val NEUTRAL = "中立"
    private val EMOTION_ALIASES = mapOf(
        "中立" to listOf("中立","平静","平靜","neutral"),
        "开心" to listOf("开心","開心","高兴","高興","快乐","快樂","喜悦","happy"),
        "难过" to listOf("难过","難過","伤心","傷心","难受","難受","sad"),
        "恐惧" to listOf("恐惧","恐懼","害怕","惊恐","驚恐","fear"),
        "吃惊" to listOf("吃惊","吃驚","惊讶","驚訝","惊喜","驚喜","surprise"),
    )
    private val LABEL_TO_CANON = EMOTION_ALIASES.flatMap { (c, al) -> al.map { it to c } }.toMap()
    fun canonEmotion(label: String?): String = LABEL_TO_CANON[(label ?: "").trim()] ?: (label ?: "").trim().ifBlank { NEUTRAL }

    // 中英兩份都要有：這裡吃的是「要唸出去的句子」，語言跟著介面走。
    // 只放中文的話，英文模式下每一句都命中不了任何關鍵字 → 永遠判成中立 →
    // 情緒參考音形同虛設，使用者會覺得「換情緒沒反應」。
    private val EMOTION_KEYWORDS = mapOf(
        "开心" to listOf("開心","开心","高興","高兴","快樂","快乐","太好","好耶","謝謝","谢谢","感謝","感谢","喜歡","喜欢","愛你","爱你","棒","讚","赞","哈哈","耶","幸福","舒服"),
        "难过" to listOf("難過","难过","傷心","伤心","想哭","好累","不想","唉","失望","孤單","孤单","難受","难受","委屈","痛"),
        "恐惧" to listOf("害怕","好怕","救命","危險","危险","不敢","恐怖","嚇","吓","緊張","紧张","求救"),
        "吃惊" to listOf("什麼","什么","怎麼會","怎么会","天啊","居然","竟然","哇","真的假的","不會吧","不会吧","太扯","驚","惊"),
    )

    /**
     * 英文關鍵字。用整字比對（見 [containsWordEn]）——不這樣做的話
     * "sad" 會命中 "saddle"、"ill" 會命中 "still"，情緒就亂跳。
     */
    private val EMOTION_KEYWORDS_EN = mapOf(
        "开心" to listOf("happy","glad","great","thanks","thank","love","like","good","nice",
                        "wonderful","excited","fun","haha","yay","better","comfortable"),
        "难过" to listOf("sad","sorry","tired","exhausted","lonely","alone","cry","crying",
                        "hurt","hurts","pain","painful","ache","upset","disappointed","miss"),
        "恐惧" to listOf("afraid","scared","fear","help","emergency","danger","dangerous",
                        "nervous","worried","panic","cant","cannot"),
        "吃惊" to listOf("what","really","wow","surprised","suddenly","unexpected","seriously","how"),
    )

    private fun containsWordEn(haystack: String, word: String): Boolean =
        Regex("(?<![a-z])" + Regex.escape(word) + "(?![a-z])").containsMatchIn(haystack)
    /** 從要唸的文字自動判情緒；沒把握回中立（？→吃惊、！→开心）。 */
    fun detectEmotion(text: String?): String {
        val t = text ?: ""
        val lower = t.lowercase()
        var best = NEUTRAL; var score = 0
        for ((emo, kws) in EMOTION_KEYWORDS) {
            val c = kws.count { t.contains(it) }
            if (c > score) { best = emo; score = c }
        }
        // 英文另算一輪，取兩邊命中數較高者。中英混排的句子兩邊都會被掃到。
        for ((emo, kws) in EMOTION_KEYWORDS_EN) {
            val c = kws.count { containsWordEn(lower, it) }
            if (c > score) { best = emo; score = c }
        }
        if (score == 0) {
            if (t.contains("？") || t.contains("?")) return "吃惊"
            if (t.contains("！") || t.contains("!")) return "开心"
        }
        return best
    }

    fun detectLang(text: String): VoiceLang {
        var hangul = 0; var kana = 0; var han = 0; var latin = 0
        for (c in text) {
            val cp = c.code
            when {
                cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF -> hangul++          // 諺文
                cp in 0x3040..0x309F || cp in 0x30A0..0x30FF -> kana++            // 平/片假名
                cp in 0x4E00..0x9FFF -> han++                                     // CJK 漢字
                c in 'A'..'Z' || c in 'a'..'z' -> latin++
            }
        }
        return when {
            hangul > 0 -> VoiceLang.KO
            kana > 0 -> VoiceLang.JA
            han > 0 -> VoiceLang.ZH
            latin > 0 -> VoiceLang.EN
            else -> VoiceLang.ZH
        }
    }

    /** 某語言的 ref 音檔路徑；沒有該語言模型時退回目前中文聲音。 */
    fun refPathFor(lang: VoiceLang): String =
        (voicesFor(lang).firstOrNull() ?: currentZhVoice())?.refPath.orEmpty()
    fun promptFor(lang: VoiceLang): String =
        (voicesFor(lang).firstOrNull() ?: currentZhVoice())?.prompt.orEmpty()

    /**
     * 一組「語言 + 參考音檔 + 嚴格對應的逐字稿 + server 端權重路徑」。
     * prompt 必須與音檔內容逐字一致 — 不對齊會降 clone 品質。
     * 權重路徑是相對於 Mac 上 GPT-SoVITS 工作目錄（~/VoiceWeaver-電腦端/GPT-SoVITS/）的路徑，給 /set_*_weights 熱切換用。
     */
    /** 一個情緒對應的參考音：(server 端音檔路徑, 該音的逐字稿)。 */
    data class EmotionRef(val ref: String, val prompt: String)

    data class MacRefVoice(
        val character: String,
        val lang: VoiceLang,
        val refPath: String,
        val prompt: String,
        val gptWeights: String,
        val sovitsWeights: String,
        /** 情緒 → 參考音（中立/开心/难过/恐惧/吃惊）。空＝伺服器未提供情緒，退回單一 refPath。 */
        val emotions: Map<String, EmotionRef> = emptyMap()
    ) {
        /** UI 顯示：模型名 + 語言標籤，例「我的聲音_ZH」 */
        val displayLabel: String get() = "$character${lang.tag}"

        /** 指定情緒的參考音；沒有就退中立、再退預設 refPath/prompt。 */
        fun refForEmotion(emotion: String?): EmotionRef {
            val emo = canonEmotion(emotion)
            emotions[emo]?.let { return it }
            emotions[NEUTRAL]?.let { return it }
            emotions.values.firstOrNull()?.let { return it }
            return EmotionRef(refPath, prompt)
        }
    }

    /**
     * 目前「伺服器實際擁有」的角色語音（由 [refreshModels] 從 `/models` 抓）。
     * 沒有任何預設後備：電腦上沒有模型就是空的，UI 不顯示角色、合成也直接禁用 GPT-SoVITS。
     */
    private val _voices = kotlinx.coroutines.flow.MutableStateFlow<List<MacRefVoice>>(emptyList())
    val voicesFlow: kotlinx.coroutines.flow.StateFlow<List<MacRefVoice>> = _voices

    /** 目前可用角色（伺服器實際擁有，無預設後備）。 */
    val VOICES: List<MacRefVoice> get() = _voices.value

    /** 是否有任何可用模型（沒有就禁用 GPT-SoVITS）。 */
    fun hasModels(): Boolean = _voices.value.isNotEmpty()

    /**
     * 從伺服器 `/models` 抓目前登錄的模型，轉成 [MacRefVoice] 更新 [_voices]。
     * 套用/刪除模型後（電腦控制中心），App 按「測試連線」或開面板即會即時反映。
     */
    /** 回傳是否「連得上」伺服器（200 且解析成功）；離線/錯誤回 false，並沿用現有清單。 */
    suspend fun refreshModels(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_LOCAL_URL }
        runCatching {
            val req = Request.Builder().url("$base/models").get().build()
            httpFor(base).newCall(req).execute().use { resp ->
                if (resp.code != 200) return@use false
                val body = resp.body?.string().orEmpty()
                val arr = org.json.JSONObject(body).optJSONArray("models") ?: return@use false
                val list = ArrayList<MacRefVoice>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name").trim()
                    val gpt = o.optString("gpt_rel").trim()
                    val sovits = o.optString("sovits_rel").trim()
                    if (name.isBlank() || gpt.isBlank() || sovits.isBlank()) continue
                    val lang = langFromModelName(name) ?: VoiceLang.ZH
                    // 去掉名稱結尾的語言標籤（容忍底線或空格，例「我的聲音 ZH」「我的聲音_ZH」→「我的聲音」）
                    val character = name
                        .replace(Regex("[ _](ZH|CN|EN|JA|JP|KO|KR)\\b", RegexOption.IGNORE_CASE), "")
                        .trim('_', ' ').ifBlank { name }
                    // 情緒對照（雲端橋接／新版電腦端會回 emotions:{情緒:{ref_path,prompt}}）
                    val emo = mutableMapOf<String, EmotionRef>()
                    o.optJSONObject("emotions")?.let { eo ->
                        val keys = eo.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            eo.optJSONObject(k)?.let { ev ->
                                emo[canonEmotion(k)] = EmotionRef(ev.optString("ref_path"), ev.optString("prompt"))
                            }
                        }
                    }
                    list.add(
                        MacRefVoice(
                            character = character, lang = lang,
                            refPath = o.optString("ref_path"),
                            prompt = o.optString("prompt"),
                            gptWeights = gpt, sovitsWeights = sovits,
                            emotions = emo
                        )
                    )
                }
                // 伺服器是什麼就顯示什麼（空 = UI 顯示「沒有模型」，不塞預設）
                _voices.value = list
                Log.i(TAG, "已從伺服器更新角色清單（${list.size}）：${list.joinToString { it.displayLabel }}")
                true
            }
        }.getOrElse { Log.w(TAG, "refreshModels 失敗（離線？），沿用現有清單：${it.message}"); false }
    }

    /** 雲端曲庫一筆：Drive＝唯一曲庫，downloaded 表示運算端本機快取有沒有這個角色。 */
    data class CatalogEntry(
        val name: String,
        val character: String,
        val lang: VoiceLang,
        val ready: Boolean,          // 權重+參考音齊全
        val emotions: List<String>,
        val bytes: Long,
        /** 已下載到運算端＝可直接合成；未下載＝要先從 Drive 抓（會等） */
        val downloaded: Boolean = false
    ) {
        /** 顯示用大小，例「231 MB」；不足 1 MB 顯示 KB。 */
        val sizeLabel: String get() = when {
            bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes > 0 -> "${bytes / 1024} KB"
            else -> "—"
        }
    }

    /** 從 `/catalog` 抓整個雲端曲庫（Mac api_v2 與 Colab 橋接皆有此端點）。離線回空。 */
    suspend fun fetchCatalog(baseUrl: String): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_LOCAL_URL }
        runCatching {
            val req = Request.Builder().url("$base/catalog").get().build()
            httpFor(base).newCall(req).execute().use { resp ->
                if (resp.code != 200) return@use emptyList<CatalogEntry>()
                val cat = org.json.JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("catalog") ?: return@use emptyList()
                val arr = cat.optJSONArray("characters") ?: return@use emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name").trim()
                    if (name.isBlank()) return@mapNotNull null
                    val emos = o.optJSONArray("emotions")?.let { ea ->
                        (0 until ea.length()).map { ea.optString(it) }
                    } ?: emptyList()
                    CatalogEntry(
                        name = name,
                        character = o.optString("character").ifBlank { name },
                        lang = langFromModelName(o.optString("lang").ifBlank { name }) ?: VoiceLang.ZH,
                        ready = o.optBoolean("ok", true),
                        emotions = emos,
                        bytes = o.optLong("bytes"),
                        downloaded = o.optBoolean("downloaded")
                    )
                }
            }
        }.getOrElse { Log.w(TAG, "fetchCatalog 失敗（離線？）：${it.message}"); emptyList() }
    }

    /** 隨選下載：請運算端（Mac/Colab）把某角色從 Drive 拉到本地。回 (成功, 訊息)。 */
    suspend fun prepareCharacter(baseUrl: String, character: String, lang: VoiceLang):
        Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_LOCAL_URL }
        val dlClient = OkHttpClient.Builder()
            // 連線階段要留給 Tailscale 建隧道的時間（區網 4 秒不夠）
            .connectTimeout(if (isRemoteHost(runCatching { URI(base).host }.getOrNull() ?: "")) 10 else 4,
                TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)   // 大檔從 Drive 拉可能要一段時間
            .build()
        runCatching {
            val url = "$base/prepare?character=" + java.net.URLEncoder.encode(character, "UTF-8") +
                "&lang=" + lang.tag.trim('_')
            dlClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val o = org.json.JSONObject(resp.body?.string().orEmpty())
                val ok = o.optBoolean("ok")
                val mb = o.optLong("bytes") / (1024 * 1024)
                ok to if (ok) "已下載到運算端（約 $mb MB / ${o.optInt("files")} 檔）"
                      else o.optString("error", "下載失敗")
            }
        }.getOrElse { false to (it.message ?: "連線失敗") }
    }


    /** 某語言的所有可用聲音；第一個為該語言預設。 */
    fun voicesFor(lang: VoiceLang): List<MacRefVoice> = VOICES.filter { it.lang == lang }

    /**
     * 手機端遠端切換 Mac server 載入的角色模型（無線/有線皆可，走同一個 base URL）。
     * 流程：/set_sovits_weights → /set_gpt_weights → 成功後更新 [currentModel]，
     * 之後合成自動帶該角色的 ref/prompt，不需要碰電腦。
     */
    suspend fun switchModel(baseUrl: String, voice: MacRefVoice): Result<String> =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_LOCAL_URL }
            val swapClient = OkHttpClient.Builder()
                // 同上：3 秒是區網的數字，Tailscale 冷啟動常常超過
                .connectTimeout(if (isRemoteHost(runCatching { URI(base).host }.getOrNull() ?: "")) 10 else 3,
                    TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) // CPU 載權重可能要數十秒
                .build()
            fun call(path: String, weights: String): String? {
                val url = "$base$path?weights_path=" + java.net.URLEncoder.encode(weights, "UTF-8")
                return runCatching {
                    swapClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.code == 200 && body.contains("success")) null
                        else "HTTP ${resp.code} $body".take(120)
                    }
                }.getOrElse { it.message ?: "連線失敗" }
            }
            // 切換失敗時呼叫端只會沉默地「沿用目前模型」，但參考音卻已經換成新角色的——
            // 權重與參考音錯配正是合成出怪聲音的主因之一，所以失敗一定要留下紀錄。
            call("/set_sovits_weights", voice.sovitsWeights)?.let {
                DiagnosticLog.e(
                    DiagnosticLog.Cat.NET, "SoVITS 權重切換失敗",
                    "目標=${voice.displayLabel} 權重=${voice.sovitsWeights} 原因=$it"
                )
                return@withContext Result.failure(Exception("SoVITS 權重切換失敗：$it"))
            }
            call("/set_gpt_weights", voice.gptWeights)?.let {
                DiagnosticLog.e(
                    DiagnosticLog.Cat.NET, "GPT 權重切換失敗",
                    "目標=${voice.displayLabel} 權重=${voice.gptWeights} 原因=$it"
                )
                return@withContext Result.failure(Exception("GPT 權重切換失敗：$it"))
            }
            currentModel = voice
            if (voice.lang == VoiceLang.ZH) lastZhCharacter = voice.character
            Log.i(TAG, "已切換 server 模型 → ${voice.displayLabel}")
            DiagnosticLog.i(DiagnosticLog.Cat.NET, "已切換伺服器模型", "→ ${voice.displayLabel}")
            Result.success(voice.displayLabel)
        }

    /**
     * **自動語言切換**：合成前確保 server 載入的模型語言 == 要講的語言。
     * 一致就不動（省下數十秒的權重重載）；不一致才切到該語言的預設聲音
     * （中文會優先回到使用者上次選的模型，例切到英文再切回中文仍是同一個中文模型）。
     *
     * @return 切換後實際使用的聲音；切換失敗回 null（呼叫端 fallback 用目前聲音）。
     */
    suspend fun ensureLanguageModel(baseUrl: String, lang: VoiceLang): MacRefVoice? {
        val cur = currentModel
        if (cur != null && cur.lang == lang) return cur   // 已是對的語言，免切
        // 該語言沒有可用聲音（例如使用者沒匯入英文模型）→ 不切，沿用目前模型
        val target = when (lang) {
            VoiceLang.ZH -> voicesFor(VoiceLang.ZH).firstOrNull { it.character == lastZhCharacter }
                ?: voicesFor(VoiceLang.ZH).firstOrNull()
            else -> voicesFor(lang).firstOrNull()   // EN / JA / KO：取該語言第一個（沒有則 null）
        } ?: run {
            Log.i(TAG, "$lang 沒有可用聲音，沿用目前模型")
            return cur
        }
        Log.i(TAG, "自動切換語言模型 → ${target.displayLabel}（目前：${cur?.displayLabel ?: "未知"}）")
        return switchModel(baseUrl, target).fold(onSuccess = { target }, onFailure = { null })
    }

    /**
     * 切到**指定角色**的該語言聲音。找不到那個角色就退回該語言的預設。
     *
     * ensureLanguageModel 的規則是「語言對了就不動」，所以伺服器上剛好載著哪個
     * 英文模型就用哪個——旁白每次用什麼聲音因此是不確定的。演示旁白要固定一個
     * 角色（整支影片同一個聲音，而且和示範發聲分得開），所以要能指名。
     */
    suspend fun ensureCharacter(baseUrl: String, lang: VoiceLang, character: String): MacRefVoice? {
        val target = voicesFor(lang).firstOrNull { it.character.contains(character) }
            ?: return ensureLanguageModel(baseUrl, lang)
        if (currentModel?.displayLabel == target.displayLabel) return target   // 已經是它，免切
        Log.i(TAG, "指定角色切換 → ${target.displayLabel}（目前：${currentModel?.displayLabel ?: "未知"}）")
        return switchModel(baseUrl, target).fold(onSuccess = { target }, onFailure = { null })
    }

    // ─── 多電腦（server）清單 ──────────────────────────────────────────────────
    // 存放格式：每行「名稱|http://ip:9880」，存於 AppSettings.gptSovitServerList。

    data class SovitsServer(val name: String, val url: String)

    fun parseServerList(raw: String): List<SovitsServer> =
        raw.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) SovitsServer(parts[0].trim(), parts[1].trim())
            else null
        }

    fun encodeServerList(servers: List<SovitsServer>): String =
        servers.joinToString("\n") { "${it.name}|${it.url}" }

    data class ComputerInfo(val hostname: String, val tailscaleIp: String)

    /**
     * 連到電腦時，向本機生圖伺服器(9881) /whoami 問出「主機名 + Tailscale IP」。
     * 用途：手機在連得到電腦時（USB/Wi-Fi）自動學到該電腦的 Tailscale 位址，
     * 存進電腦清單，之後在外網(4G/5G)也能一按填入。回 null＝沒有 Tailscale 或問不到。
     */
    suspend fun discoverComputer(baseUrl: String): ComputerInfo? = withContext(Dispatchers.IO) {
        val host = runCatching {
            val b = baseUrl.trim()
            URI(if (b.contains("://")) b else "http://$b").host
        }.getOrNull() ?: return@withContext null
        val target = "http://$host:9881"
        val req = Request.Builder().url("$target/whoami").get().build()
        runCatching {
            httpFor(target).newCall(req).execute().use { resp ->
                if (resp.code != 200) return@use null
                val j = JSONObject(resp.body?.string().orEmpty())
                val ts = j.optString("tailscale_ip").trim()
                if (ts.isBlank()) null
                else ComputerInfo(j.optString("hostname").ifBlank { "電腦" }, ts)
            }
        }.getOrNull()
    }

    /** Tailscale 位址轉成 GPT-SoVITS API URL（埠 9880）。 */
    fun tailscaleApiUrl(ip: String): String = "http://$ip:9880"

    /**
     * server 目前載入的模型（由 [pingServer] 從 `/health` 偵測，或 [switchModel] 主動設定）。
     * null = 還沒偵測過 → 各語言用清單第一個當預設。
     */
    @Volatile
    private var currentModel: MacRefVoice? = null

    /** 使用者上次選的中文角色（英文→中文自動切回時用）。沒有模型時為空字串。 */
    @Volatile
    private var lastZhCharacter: String = ""

    /** 目前應使用的中文 ref/prompt 組；沒有任何模型時回 null（呼叫端要禁用 GPT-SoVITS）。 */
    fun currentZhVoice(): MacRefVoice? =
        currentModel?.takeIf { it.lang == VoiceLang.ZH }
            ?: voicesFor(VoiceLang.ZH).firstOrNull { it.character == lastZhCharacter }
            ?: voicesFor(VoiceLang.ZH).firstOrNull()

    /** server 目前載入的聲音（null = 未知）。供 UI 顯示「現在是哪個角色/語言」。 */
    fun currentVoice(): MacRefVoice? = currentModel

    /**
     * 這個路徑合成伺服器讀得到嗎？讀不到就回一句「為什麼不行」，null 代表可用。
     *
     * 以前這裡是 translateRefAudioPathForServer()：碰到手機路徑就默默換成 Mac 端預設參考音。
     * 那個作法本身就是 bug——換掉了音檔卻沒換對應的逐字稿，合成出來會走音並帶雜音。
     * 現在只負責判斷「能不能用」，不能用就讓呼叫端整組退回伺服器給的成對值。
     */
    fun unusableRefReason(rawPath: String): String? = when {
        rawPath.isBlank() -> "路徑是空的"
        // 手機本機路徑：合成在電腦上跑，它看不到手機的檔案系統
        rawPath.startsWith("file:") || rawPath.startsWith("content:") ||
            rawPath.startsWith("/storage/") || rawPath.startsWith("/sdcard/") ||
            rawPath.startsWith("/data/") -> "這是手機上的路徑，電腦端讀不到"
        // 換過電腦卻沿用舊設定的典型情況：手機裡還存著上一台 Windows 的 C:\Users\...，
        // 送到 Mac/Linux 伺服器一定 404，過去只會得到一個沒頭沒尾的 HTTP 400。
        Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(rawPath) ->
            "這是 Windows 路徑，目前的伺服器不是 Windows"
        rawPath.startsWith("\\\\") -> "這是 Windows 網路芳鄰路徑，伺服器讀不到"
        else -> null
    }

    /**
     * 找出某個參考音檔對應的逐字稿。
     *
     * 兩個來源，順序有意義：
     *  1. 伺服器 `/models` 回報的 emotions／預設 ref——最權威，逐字稿是電腦端給的
     *  2. 檔名慣例 `【情緒】逐字稿.wav`——曲庫就是這樣命名的，路徑沒登記在 /models 時還撈得回來
     *
     * 找不到就回 null。**寧可不用這支參考音，也不要配一份不相干的逐字稿**——
     * 逐字稿對不上會讓 GPT-SoVITS 合成出走音、亂入雜音的結果（而且 v3/v4 不接受空逐字稿）。
     */
    fun promptForRefPath(rawPath: String, voice: MacRefVoice?): String? {
        fun fileNameOf(p: String) = p.substringAfterLast('/').substringAfterLast('\\')
        val target = fileNameOf(rawPath)
        if (target.isBlank()) return null

        val known = buildList {
            voice?.let { v ->
                addAll(v.emotions.values)
                if (v.refPath.isNotBlank()) add(EmotionRef(v.refPath, v.prompt))
            }
            VOICES.forEach { v ->
                addAll(v.emotions.values)
                if (v.refPath.isNotBlank()) add(EmotionRef(v.refPath, v.prompt))
            }
        }
        known.firstOrNull { fileNameOf(it.ref) == target && it.prompt.isNotBlank() }?.let { return it.prompt }

        // 檔名慣例：【情緒】逐字稿.wav
        val fromName = target.substringAfter('】', "").removeSuffix(".wav").trim()
        return fromName.ifBlank { null }
    }
}
