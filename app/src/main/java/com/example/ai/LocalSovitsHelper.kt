package com.example.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 *  - [openTermux]：用 Intent 啟動 Termux app（找不到 → 帶去 F-Droid 安裝頁）
 *  - [TERMUX_SETUP_SCRIPT]：顯示 Termux 不能直接安裝官方 torch wheel 的說明
 *
 * 為什麼不自動跑腳本：Termux 不允許別的 app 從外部直接執行任意指令（安全限制 Android 10+），
 * 但 clipboard share 是合法的 — 我們複製腳本，使用者貼到 Termux 跑。
 */
object LocalSovitsHelper {

    private const val TAG = "LocalSovits"

    /** 預設本機 API URL。只有 server 真的跑在手機本機時才適用；一般建議改填 Mac/PC IP。 */
    const val DEFAULT_LOCAL_URL = "http://127.0.0.1:9880"

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun pingLocalServer(): PingResult = pingServer(DEFAULT_LOCAL_URL)

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

        // ── 1) Socket layer ──
        val socketAlive = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 1200)
                true
            }
        }.getOrDefault(false)
        if (!socketAlive) return@withContext PingResult.PortClosed

        // ── 2) HTTP layer：SoVITS api_v2 在 / 回 404（沒這 route）但 server 會應答；/health 通常 200 ──
        val req = Request.Builder().url("$normalized/health").get().build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                Log.i(TAG, "SoVITS HTTP ${resp.code} @ $normalized")
                if (resp.code == 200) {
                    // 先從伺服器更新可用模型清單（電腦端新增/刪除的模型即時反映）
                    refreshModels(normalized)
                    // /health 會回目前載入的 weights 路徑 — 比對權重檔名找出是哪個聲音（含語言標籤）。
                    val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
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
        }.getOrElse { PingResult.HttpError(-1) }
    }

    enum class PingResultKind { PortClosed, Reachable, HttpError }
    sealed class PingResult(val kind: PingResultKind, val label: String) {
        object PortClosed : PingResult(PingResultKind.PortClosed, "❌ 連不到 API 位址，請確認 server 已啟動、IP 正確、同一個 Wi-Fi")
        object Reachable  : PingResult(PingResultKind.Reachable,  "✅ SoVITS server 通了，可以合成測試")
        data class HttpError(val code: Int) : PingResult(PingResultKind.HttpError, "⚠ port 開但 HTTP $code，請檢查 api_v2.py log 或 API URL")
    }

    /**
     * 用 Intent 啟動 Termux 顯示說明。找不到 → 開瀏覽器去 F-Droid 安裝頁
     * （**不要用 Play Store 版本** — 它停在 SDK 28 而 Android 14+ 會壞掉）
     */
    fun openTermux(context: Context) {
        val pkg = "com.termux"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // 沒裝 → 開 F-Droid 頁面
        val fdroidUrl = "https://f-droid.org/en/packages/com.termux/"
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(fdroidUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Termux 說明腳本。使用者複製到 Termux 後會看到清楚原因與 LAN 方案。
     *
     * 舊版腳本會在 `pip install torch` 停住；保留按鈕但改成不再執行失敗安裝。
     */
    val TERMUX_SETUP_SCRIPT = """
        # ====== VoiceWeaver GPT-SoVITS 說明 ======
        echo "原因：官方 PyTorch pip wheel 不支援 Android/Termux。"
        echo "所以 pip install torch --index-url https://download.pytorch.org/whl/cpu 會失敗。"
        echo ""
        echo "可靠做法：在 Mac/PC 跑 GPT-SoVITS API server，手機用 USB 或同一個 Wi-Fi 連線。"
        echo ""
        echo "電腦端：直接雙擊「VoiceWeaver語音中心.app」即可（會自動啟動所有伺服器 + adb reverse）。"
        echo "手動啟動（電腦端已搬到 ~/VoiceWeaver-電腦端）："
        echo "  cd ~/VoiceWeaver-電腦端/GPT-SoVITS && ./start_api.sh"
        echo ""
        echo "查 Mac Wi-Fi IP："
        echo "  ipconfig getifaddr en0"
        echo ""
        echo "重要：上面的指令要在 Mac 的「終端機」執行，不是在 Termux 執行。"
        echo ""
        echo "VoiceWeaver 的 GPT-SoVITS API 位址填："
        echo "  http://你的電腦IP:9880    （無線/LAN）"
        echo "  http://127.0.0.1:9880     （有線/USB，需先 adb reverse tcp:9880 tcp:9880）"
        echo "  http://100.x.x.x:9880     （全域網 Tailscale，外網/4G/5G 皆可）"
        echo ""
        echo "注意：不要加 /tts，App 會自動呼叫 /tts。"
    """.trimIndent()

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
     * 從模型名稱/檔名解析語言標籤。容忍底線或空格分隔（「胡桃_ZH」「胡桃 ZH」皆可）。
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
    data class MacRefVoice(
        val character: String,
        val lang: VoiceLang,
        val refPath: String,
        val prompt: String,
        val gptWeights: String,
        val sovitsWeights: String
    ) {
        /** UI 顯示：角色名 + 語言標籤，例「胡桃_ZH」 */
        val displayLabel: String get() = "$character${lang.tag}"
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
            client.newCall(req).execute().use { resp ->
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
                    // 去掉名稱結尾的語言標籤（容忍底線或空格，例「胡桃 ZH」「胡桃_ZH」→「胡桃」）
                    val character = name
                        .replace(Regex("[ _](ZH|CN|EN|JA|JP|KO|KR)\\b", RegexOption.IGNORE_CASE), "")
                        .trim('_', ' ').ifBlank { name }
                    list.add(
                        MacRefVoice(
                            character = character, lang = lang,
                            refPath = o.optString("ref_path"),
                            prompt = o.optString("prompt"),
                            gptWeights = gpt, sovitsWeights = sovits
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

    /** 向後相容：只取中文清單（設定頁角色切換 chip 用）。 */
    val ZH_VOICES: List<MacRefVoice> get() = VOICES.filter { it.lang == VoiceLang.ZH }

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
                .connectTimeout(3, TimeUnit.SECONDS)
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
            call("/set_sovits_weights", voice.sovitsWeights)?.let {
                return@withContext Result.failure(Exception("SoVITS 權重切換失敗：$it"))
            }
            call("/set_gpt_weights", voice.gptWeights)?.let {
                return@withContext Result.failure(Exception("GPT 權重切換失敗：$it"))
            }
            currentModel = voice
            if (voice.lang == VoiceLang.ZH) lastZhCharacter = voice.character
            Log.i(TAG, "已切換 server 模型 → ${voice.displayLabel}")
            Result.success(voice.displayLabel)
        }

    /**
     * **自動語言切換**：合成前確保 server 載入的模型語言 == 要講的語言。
     * 一致就不動（省下數十秒的權重重載）；不一致才切到該語言的預設聲音
     * （中文會優先回到使用者上次選的角色，例切到英文再切回中文仍是胡桃/钟离）。
     *
     * @return 切換後實際使用的聲音；切換失敗回 null（呼叫端 fallback 用目前聲音）。
     */
    suspend fun ensureLanguageModel(baseUrl: String, lang: VoiceLang): MacRefVoice? {
        val cur = currentModel
        if (cur != null && cur.lang == lang) return cur   // 已是對的語言，免切
        // 該語言沒有可用聲音（例如英文 Furina_EN 已移除）→ 不切，沿用目前模型
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
        val req = Request.Builder().url("http://$host:9881/whoami").get().build()
        runCatching {
            client.newCall(req).execute().use { resp ->
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

    // 沒有模型時回空字串（合成路徑會先以 hasModels() 擋掉，不會走到這裡）
    val MAC_REF_ZH_PATH: String get() = currentZhVoice()?.refPath.orEmpty()
    val MAC_REF_EN_PATH: String get() = (voicesFor(VoiceLang.EN).firstOrNull() ?: currentZhVoice())?.refPath.orEmpty()

    val PROMPT_ZH: String get() = currentZhVoice()?.prompt.orEmpty()
    val PROMPT_EN: String get() = (voicesFor(VoiceLang.EN).firstOrNull() ?: currentZhVoice())?.prompt.orEmpty()

    /**
     * 把手機端路徑（`file://`、`content://`、`/storage/...`）翻成 Mac 上實際存在的路徑。
     * SoVITS server 在 Mac 上跑，任何手機檔案路徑它都讀不到 — 一律改用 Mac 端預設槽位，
     * 避免 server 直接回 400。非手機路徑視為使用者自訂的 server-side path，原樣放行。
     */
    fun translateRefAudioPathForServer(rawPath: String, isEnglish: Boolean): String {
        val isPhonePath = rawPath.startsWith("file:") ||
            rawPath.startsWith("content:") ||
            rawPath.startsWith("/storage/") ||
            rawPath.startsWith("/sdcard/") ||
            rawPath.startsWith("/data/")
        return if (isPhonePath) {
            if (isEnglish) MAC_REF_EN_PATH else MAC_REF_ZH_PATH
        } else rawPath
    }
}
