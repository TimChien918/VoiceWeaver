package com.speakmylife.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 「電腦接手計算」決策核心。
 *
 * 概念：當手機連得到電腦（GPT-SoVITS 9880 / 生圖 9881 / 文字推論 9882）時，依照實測的
 *  **連線種類**（有線 USB＝localhost、無線 Wi-Fi＝LAN IP）、**延遲 RTT**、**吞吐量**，
 *  算出一份「接手計畫」(OffloadPlan)：把哪些運算丟給電腦、哪些留在手機，盡量減少手機負擔。
 *
 * 三種策略（對應使用者說的「依數據算出來選 1/2/3」）：
 *  - FULL  ：連線快（有線或高速 Wi-Fi）→ 語音、生圖、文字重組全部交給電腦。手機最輕。
 *  - HEAVY ：一般無線 → GPU 重活（語音、生圖）交電腦；短文字重組走雲端/手機（省一次往返）。
 *  - LIGHT ：慢速/不穩 → 只把「手機做不到」的生圖丟電腦；語音改用手機系統 TTS；文字留手機/雲端。
 *  - OFFLINE：連不到電腦 → 全部手機本機（系統 TTS、本機 LLM；生圖只能走雲端）。
 *
 * 量測本身很輕：socket 連線測 RTT，再向生圖伺服器的 /bench 抓固定大小資料測吞吐量。
 */
object OffloadManager {
    private const val TAG = "OffloadManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** /bench 抓多少資料來估吞吐量（200KB：夠準又不拖慢）。*/
    private const val BENCH_BYTES = 200_000

    enum class LinkKind { None, Wired, Wireless }

    /** 一項運算要交給誰處理。*/
    enum class Target(val label: String) {
        Computer("電腦"), Phone("手機"), Cloud("雲端")
    }

    enum class OffloadTier(val title: String, val blurb: String) {
        FULL("全部交給電腦", "連線快，語音、生圖、文字重組都由電腦處理，手機最省力"),
        HEAVY("重活交給電腦", "語音與生圖由電腦處理；文字重組走雲端/手機"),
        LIGHT("僅生圖交給電腦", "連線較慢，只有生圖交給電腦；語音改用手機內建朗讀"),
        OFFLINE("全部由手機處理", "未連接電腦，使用手機本機運算")
    }

    /** 連線實測數據。*/
    data class LinkMetrics(
        val reachable: Boolean,
        val kind: LinkKind,
        val rttMs: Long,            // -1＝測不到
        val throughputKbps: Long,   // -1＝測不到
        val textServerReady: Boolean
    ) {
        val speedLabel: String
            get() = when {
                !reachable -> "未連線"
                throughputKbps <= 0 -> if (rttMs in 0..40) "良好" else "偵測中"
                throughputKbps >= 8_000 -> "極快 (${throughputKbps / 1000} Mbps)"
                throughputKbps >= 1_500 -> "良好 (${throughputKbps / 1000}.${(throughputKbps % 1000) / 100} Mbps)"
                else -> "慢速 (${throughputKbps} kbps)"
            }
    }

    /** 接手計畫：每項運算的目標 + 整體分級 + 人話說明。*/
    data class OffloadPlan(
        val tier: OffloadTier,
        val voice: Target,
        val image: Target,
        val text: Target,
        val metrics: LinkMetrics
    ) {
        companion object {
            val OFFLINE = OffloadPlan(
                tier = OffloadTier.OFFLINE,
                voice = Target.Phone, image = Target.Cloud, text = Target.Phone,
                metrics = LinkMetrics(false, LinkKind.None, -1, -1, false)
            )
        }
    }

    private fun hostOf(url: String): String? = runCatching {
        val b = url.trim()
        URI(if (b.contains("://")) b else "http://$b").host
    }.getOrNull()

    private fun isLocalHost(host: String?): Boolean =
        host == "127.0.0.1" || host == "localhost" || host == "::1"

    /** 量測到電腦的連線。gptSovitBase 例：http://127.0.0.1:9880 或 http://192.168.1.5:9880 。*/
    suspend fun probe(gptSovitBase: String): LinkMetrics = withContext(Dispatchers.IO) {
        val host = hostOf(gptSovitBase) ?: return@withContext LinkMetrics(false, LinkKind.None, -1, -1, false)
        val kind = if (isLocalHost(host)) LinkKind.Wired else LinkKind.Wireless

        // 1) RTT：對生圖埠 9881 做 socket 連線計時（9881 是有線/無線都會轉發的本機運算埠）。
        val rtt = measureSocketRtt(host, 9881).let { if (it < 0) measureSocketRtt(host, 9880) else it }
        if (rtt < 0) return@withContext LinkMetrics(false, LinkKind.None, -1, -1, false)

        // 2) 吞吐量：向生圖伺服器 /bench 抓固定大小資料計時（伺服器沒實作時回 -1，不影響分級）。
        val kbps = measureThroughput("http://$host:9881/bench?bytes=$BENCH_BYTES")

        // 3) 文字推論伺服器 9882 是否就緒（tier-FULL 才需要）。
        val textReady = pingHealth("http://$host:9882/health")

        LinkMetrics(true, kind, rtt, kbps, textReady)
    }

    private fun measureSocketRtt(host: String, port: Int): Long = runCatching {
        val t0 = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), 1500) }
        (System.nanoTime() - t0) / 1_000_000
    }.getOrDefault(-1L)

    private fun measureThroughput(url: String): Long = runCatching {
        val req = Request.Builder().url(url).get().build()
        val t0 = System.nanoTime()
        client.newCall(req).execute().use { resp ->
            if (resp.code != 200) return@runCatching -1L
            val bytes = resp.body?.bytes()?.size ?: return@runCatching -1L
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms <= 0 || bytes <= 0) return@runCatching -1L
            // kbps = bytes*8 / ms（bytes*8/1000 ÷ ms/1000）
            (bytes.toLong() * 8L) / ms
        }
    }.getOrDefault(-1L)

    private fun pingHealth(url: String): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { it.code == 200 }
    }.getOrDefault(false)

    /**
     * 依實測數據算出接手計畫。這是「依數據選 1/2/3」的決策表。
     */
    fun computePlan(m: LinkMetrics): OffloadPlan {
        if (!m.reachable) return OffloadPlan.OFFLINE

        // 有線(USB) 視為極快、零延遲；無線則看 RTT/吞吐量。
        val fast = m.kind == LinkKind.Wired ||
            (m.throughputKbps >= 8_000 && m.rttMs in 0..30) ||
            (m.throughputKbps <= 0 && m.rttMs in 0..15)        // 測不到吞吐但延遲極低 → 視為快
        val decent = m.throughputKbps >= 1_500 ||
            (m.throughputKbps <= 0 && m.rttMs in 0..80)

        return when {
            fast -> OffloadPlan(
                tier = OffloadTier.FULL,
                voice = Target.Computer,
                image = Target.Computer,
                // 文字也交電腦，但電腦文字伺服器沒就緒時退回雲端（不擋使用）。
                text = if (m.textServerReady) Target.Computer else Target.Cloud,
                metrics = m
            )
            decent -> OffloadPlan(
                tier = OffloadTier.HEAVY,
                voice = Target.Computer,
                image = Target.Computer,
                text = Target.Cloud,
                metrics = m
            )
            else -> OffloadPlan(
                tier = OffloadTier.LIGHT,
                voice = Target.Phone,        // 音檔大，慢線不划算 → 手機系統 TTS
                image = Target.Computer,     // 手機做不到生圖，仍交電腦（一次性、可等）
                text = Target.Phone,
                metrics = m
            )
        }
    }

    // ── 給推理引擎/分派用的最新狀態（靜態，仿 NetworkQualityGate 模式）─────────────
    @Volatile var lastPlan: OffloadPlan = OffloadPlan.OFFLINE
        private set
    @Volatile private var computerHost: String? = null

    suspend fun probeAndPlan(gptSovitBase: String): OffloadPlan {
        val m = probe(gptSovitBase)
        val plan = computePlan(m)
        lastPlan = plan
        computerHost = if (m.reachable) hostOf(gptSovitBase) else null
        // 順手刷新音訊伺服器狀態，讓 UI 的同步旗標不會一直是開 App 當下的舊值
        if (m.reachable) runCatching { audioServerReady(gptSovitBase) }
        Log.i(TAG, "tier=${plan.tier} voice=${plan.voice} image=${plan.image} text=${plan.text} (${m.speedLabel})")
        return plan
    }

    /** 文字重組是否該交給電腦（plan=Computer 且文字伺服器就緒）。*/
    fun shouldUseComputerText(): Boolean =
        lastPlan.text == Target.Computer && lastPlan.metrics.textServerReady && computerHost != null

    /** 語音是否該交給電腦（plan=Computer）；否則用手機系統 TTS。*/
    fun shouldUseComputerVoice(): Boolean = lastPlan.voice == Target.Computer

    /** 生圖是否該優先用電腦本機（plan=Computer）。*/
    fun shouldPreferComputerImage(): Boolean = lastPlan.image == Target.Computer

    /**
     * 把句子重組交給電腦端文字伺服器(9882/reconstruct)。
     * 送 {system,user}，回傳重組後句子；任何失敗回 null（呼叫端退回雲端/本機）。
     */
    suspend fun computerReconstruct(system: String, user: String, temperature: Double = 0.5): String? = withContext(Dispatchers.IO) {
        val host = computerHost ?: return@withContext null
        val payload = JSONObject()
            .put("system", system)
            .put("user", user)
            .put("temperature", temperature)   // 評分類任務傳低溫求穩定；舊版伺服器會忽略此欄位
            .toString()
        val req = Request.Builder()
            .url("http://$host:9882/reconstruct")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@use null
                val body = resp.body?.string().orEmpty()
                val txt = JSONObject(body).optString("text").trim()
                txt.ifBlank { null }
            }
        }.getOrNull()
    }

    /**
     * 電腦端音訊理解伺服器(9883)是否備妥。
     *
     * 跟 9882 分開問，因為它是獨立行程、模型又大（E4B 8-bit 約 8.5GB），
     * 常見狀況是「文字伺服器在跑、音訊的還在載入或根本沒開」。
     * loading=true 時回 false——寧可這次走雲端，也不要讓病人乾等模型載完。
     */
    suspend fun audioServerReady(gptSovitBase: String): Boolean = withContext(Dispatchers.IO) {
        val host = hostOf(gptSovitBase) ?: return@withContext false
        val ok = runCatching {
            val req = Request.Builder().url("http://$host:$AUDIO_PORT/health").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@use false
                val o = JSONObject(resp.body?.string().orEmpty())
                o.optBoolean("ready", false)
            }
        }.getOrDefault(false)
        audioServerReadyCached = ok
        ok
    }

    /**
     * 上一次健康檢查的結果。
     *
     * UI 決定要不要顯示「直接說給 AI 聽」時是在 Compose 裡同步取值，沒辦法 await
     * 一個網路請求，所以只能看快取。代價是剛開 App 那一下還是舊值——由
     * [probeAndPlan] 順手刷新，實務上打開設定或按過一次就會校正。
     */
    @Volatile var audioServerReadyCached: Boolean = false
        private set

    /**
     * 把「直接聽音訊」交給電腦端(9883/understand)——免金鑰、語音不出區網。
     *
     * 回傳模型的原始輸出（JSON 字串，格式與雲端那條路一致，交給同一支 parseCoT 解析）；
     * 任何失敗回 null，由呼叫端決定要不要退回雲端。
     */
    suspend fun computerUnderstandAudio(
        gptSovitBase: String,
        audioBase64: String,
        mime: String,
        system: String,
        user: String,
    ): String? = withContext(Dispatchers.IO) {
        val host = hostOf(gptSovitBase) ?: return@withContext null
        val payload = JSONObject()
            .put("audio_base64", audioBase64)
            .put("mime", mime)
            .put("system", system)
            .put("user", user)
            .toString()
        val req = Request.Builder()
            .url("http://$host:$AUDIO_PORT/understand")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        // 本機推論在 M 系列 Mac 上約 4～11 秒，但冷啟動或同時在合成語音時會更久，
        // 所以用比文字重組寬鬆得多的逾時。
        val audioClient = client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
        runCatching {
            audioClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code != 200) {
                    DiagnosticLog.w(
                        DiagnosticLog.Cat.STT, "電腦端音訊理解 HTTP ${resp.code}",
                        body.take(300)
                    )
                    return@use null
                }
                JSONObject(body).optString("text").trim().ifBlank { null }
            }
        }.onFailure {
            DiagnosticLog.w(DiagnosticLog.Cat.STT, "電腦端音訊理解連線失敗", it.message)
        }.getOrNull()
    }

    /** 電腦端音訊理解伺服器的埠（audio_infer_server.py）。 */
    const val AUDIO_PORT = 9883

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
}
