package com.example.ai

import android.content.Context
import android.util.Log
import com.example.data.LlmApiEntry
import com.example.data.LlmAudioStyle
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 智能 API 路由：**不需要使用者手動勾選要用哪家**。
 *
 * 為什麼要有這層：原本是人工點 ✓ 決定「使用中」，但使用者不可能知道
 * 哪家現在比較快、哪家額度用完了、哪家剛好在掛。而且舊的健康度只記在記憶體，
 * App 一關就全忘光，每次冷啟動都要重新踩一遍雷。
 *
 * 這裡改成依「實際觀測到的表現」自動排序，並把統計寫進 SharedPreferences，
 * 跨重啟持續學習。使用者仍可「釘選」某一家強制優先（例如只想用免費的），
 * 但預設完全自動。
 *
 * 評分（分數高者先用）：
 *  1. 能力符合   ── 硬條件。要做音訊理解卻不支援音訊的，直接排除
 *  2. 成功率     ── 最重要：一直失敗的自然往後排
 *  3. 延遲       ── 次要：慢一點可以接受，但同分時偏好快的
 *  4. 免費優先   ── 免金鑰／本機自架的給加分，能省錢就省
 *  5. 冷卻懲罰   ── 剛失敗的暫時擺後面，連續失敗則指數延長冷卻
 */
object SmartApiRouter {

    private const val TAG = "SmartApiRouter"
    private const val PREFS = "vw_api_router"
    private const val KEY_STATS = "stats_json"

    /** 基礎冷卻；連續失敗會指數放大（60s → 2m → 4m…上限 30 分鐘）。 */
    private const val BASE_COOLDOWN_MS = 60_000L
    private const val MAX_COOLDOWN_MS = 30 * 60_000L

    /** 延遲的指數移動平均權重：新樣本佔 30%，避免單次抖動就翻盤。 */
    private const val EWMA_ALPHA = 0.3

    /** 一筆供應商的實測統計。 */
    private data class Stat(
        var success: Int = 0,
        var failure: Int = 0,
        /** 指數移動平均延遲（ms）；0＝還沒成功過。 */
        var avgLatencyMs: Double = 0.0,
        var lastFailureAt: Long = 0L,
        /** 連續失敗次數，用來算指數退避。成功一次就歸零。 */
        var consecutiveFailures: Int = 0
    ) {
        /** 拉普拉斯平滑：沒資料時給 0.5，不會因為第一次失敗就被永久打死。 */
        val successRate: Double get() = (success + 1.0) / (success + failure + 2.0)

        fun cooldownUntil(): Long {
            if (consecutiveFailures == 0) return 0L
            val backoff = BASE_COOLDOWN_MS shl (consecutiveFailures - 1).coerceAtMost(5)
            return lastFailureAt + backoff.coerceAtMost(MAX_COOLDOWN_MS)
        }
    }

    private val stats = mutableMapOf<String, Stat>()
    private val cursor = AtomicInteger(0)
    private var prefs: android.content.SharedPreferences? = null
    private var loaded = false

    /** App 啟動時呼叫一次，把上次學到的統計讀回來。 */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val raw = prefs?.getString(KEY_STATS, null) ?: return@runCatching
            val o = JSONObject(raw)
            for (key in o.keys()) {
                val s = o.getJSONObject(key)
                stats[key] = Stat(
                    success = s.optInt("s"),
                    failure = s.optInt("f"),
                    avgLatencyMs = s.optDouble("l", 0.0),
                    lastFailureAt = s.optLong("t"),
                    consecutiveFailures = s.optInt("c")
                )
            }
            Log.i(TAG, "載入 ${stats.size} 筆供應商實測統計")
        }.onFailure { Log.w(TAG, "統計載入失敗，重新開始學習：${it.message}") }
        loaded = true
    }

    @Synchronized
    private fun persist() {
        val p = prefs ?: return
        runCatching {
            val o = JSONObject()
            stats.forEach { (k, v) ->
                o.put(k, JSONObject()
                    .put("s", v.success).put("f", v.failure)
                    .put("l", v.avgLatencyMs).put("t", v.lastFailureAt)
                    .put("c", v.consecutiveFailures))
            }
            p.edit().putString(KEY_STATS, o.toString()).apply()
        }
    }

    // ─── 觀測回報 ───────────────────────────────────────────────────────────

    @Synchronized
    fun recordSuccess(id: String, latencyMs: Long) {
        val s = stats.getOrPut(id) { Stat() }
        s.success++
        s.consecutiveFailures = 0
        s.avgLatencyMs =
            if (s.avgLatencyMs <= 0.0) latencyMs.toDouble()
            else s.avgLatencyMs * (1 - EWMA_ALPHA) + latencyMs * EWMA_ALPHA
        persist()
    }

    @Synchronized
    fun recordFailure(id: String) {
        val s = stats.getOrPut(id) { Stat() }
        s.failure++
        s.consecutiveFailures++
        s.lastFailureAt = System.currentTimeMillis()
        persist()
    }

    // ─── 排序 ──────────────────────────────────────────────────────────────

    /** 這次請求需要什麼能力。 */
    enum class Need { TEXT, NATIVE_AUDIO, TRANSCRIBE }

    private fun capable(entry: LlmApiEntry, need: Need): Boolean = when (need) {
        Need.TEXT -> true
        Need.NATIVE_AUDIO ->
            entry.provider.audioStyle == LlmAudioStyle.GEMINI_INLINE ||
                entry.provider.audioStyle == LlmAudioStyle.OPENAI_INPUT_AUDIO
        Need.TRANSCRIBE ->
            entry.provider.audioStyle == LlmAudioStyle.WHISPER_TRANSCRIBE &&
                entry.provider.transcribeEndpoint.isNotBlank()
    }

    @Synchronized
    private fun score(entry: LlmApiEntry, now: Long): Double =
        scoreOf(entry.id, !entry.provider.needsKey, now)

    /**
     * 純粹依 id 的實測評分，不綁任何一種 API 型別——語音合成、生圖之後都能共用
     * 同一套「成功率／延遲／免費／冷卻」判斷，不必各自再寫一份健康度。
     */
    @Synchronized
    fun scoreOf(id: String, free: Boolean, now: Long = System.currentTimeMillis()): Double {
        val s = stats[id] ?: Stat()
        var sc = 0.0

        // ① 成功率（最重要）
        sc += s.successRate * 100.0

        // ② 延遲：換算成 0~20 分，2 秒內拿滿分，10 秒以上幾乎沒分
        if (s.avgLatencyMs > 0) {
            val fast = (1.0 - ((s.avgLatencyMs - 2000.0) / 8000.0)).coerceIn(0.0, 1.0)
            sc += fast * 20.0
        } else {
            sc += 10.0   // 還沒資料：給中間值，讓它有機會被試一次
        }

        // ③ 免費優先：不用金鑰（本機自架 / 免費服務）省錢，同等表現下優先
        if (free) sc += 15.0

        // ④ 冷卻懲罰：剛失敗的大幅扣分但不完全排除（全掛時仍要有東西可用）
        if (s.cooldownUntil() > now) sc -= 60.0

        return sc
    }

    /**
     * 依實測表現排出這次要用的順序（第一個最優先，其餘為自動備援）。
     *
     * @param pinnedId 使用者手動釘選的供應商；非空時強制排第一（人工覆寫自動判斷）
     */
    @Synchronized
    fun rank(
        candidates: List<LlmApiEntry>,
        need: Need = Need.TEXT,
        pinnedId: String? = null
    ): List<LlmApiEntry> {
        val usable = candidates.filter { it.usable && capable(it, need) }
        if (usable.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        // 同一供應商有多把金鑰時輪流用（分散額度），所以先在同分組內旋轉
        val rotated = usable.groupBy { it.provider }.values.flatMap { group ->
            if (group.size <= 1) group
            else {
                val off = Math.floorMod(cursor.getAndIncrement(), group.size)
                group.drop(off) + group.take(off)
            }
        }

        val sorted = rotated.sortedByDescending { score(it, now) }
        val pinned = pinnedId?.let { id -> sorted.firstOrNull { it.id == id } }
        return if (pinned != null) listOf(pinned) + sorted.filter { it.id != pinned.id } else sorted
    }

    /**
     * 通用排序：任何有 id、有「免不免費」概念的 API 清單都能用同一套實測評分排序
     * （目前給語音合成用）。跟 [rank] 共用同一份統計，所以「哪家比較穩」的學習
     * 是全 App 一致的，不會每個功能各自重踩一遍。
     *
     * @param bonusOf 型別專屬的額外加分（例如語音合成偏好支援中文的供應商）
     */
    @Synchronized
    fun <T> rankAny(
        items: List<T>,
        idOf: (T) -> String,
        freeOf: (T) -> Boolean,
        pinnedId: String? = null,
        bonusOf: (T) -> Double = { 0.0 }
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val sorted = items.sortedByDescending { scoreOf(idOf(it), freeOf(it), now) + bonusOf(it) }
        val pinned = pinnedId?.let { id -> sorted.firstOrNull { idOf(it) == id } }
        return if (pinned != null) listOf(pinned) + sorted.filter { idOf(it) != idOf(pinned) } else sorted
    }

    /** 給 UI 顯示：這家目前的實測狀態（人話）。 */
    @Synchronized
    fun statusLabel(id: String): String {
        val s = stats[id] ?: return com.example.ui.I18n.tr("尚未使用過")
        if (s.success + s.failure == 0) return com.example.ui.I18n.tr("尚未使用過")
        val rate = (s.successRate * 100).toInt()
        val ms = s.avgLatencyMs.toInt()
        val cooling = s.cooldownUntil() > System.currentTimeMillis()
        return buildString {
            append(com.example.ui.I18n.tr("成功率 %d%%").format(rate))
            if (ms > 0) append(com.example.ui.I18n.tr("・約 %dms").format(ms))
            if (cooling) append(com.example.ui.I18n.tr("・暫時跳過（剛失敗）"))
        }
    }
}
