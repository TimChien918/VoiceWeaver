package com.example.ai

import android.content.Context
import android.util.Log
import com.example.data.AacItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * AAC 圖卡的「動態牆」排序引擎——把社群媒體 feed 的做法搬到溝通板上。
 *
 * 為什麼：預設圖卡庫兩百多張，但每個患者天天講的就是那十幾句。社群平台
 * 解決的正是同一個問題（內容太多、注意力有限），它們的核心配方是
 * 「互動分數 × 時間衰減 ＋ 情境相關性」：
 *
 *  - **時間衰減**（Hacker News 的 votes/age^1.8、Reddit 的每 12.5 小時 10 倍）：
 *    這裡用指數衰減 Σ 2^(-age/半衰期)，半衰期 7 天——上週開始常講的新需求
 *    很快浮上來，上個月的舊話題自然沉下去，而且增量可維護（一個累積值
 *    乘上衰減再 +1，不用保留整串時間戳）。
 *  - **情境相關性**（社群的「你可能認識的人」）：吃藥、吃飯這種需求有強烈的
 *    時段性；醫療類在醫院才高頻。所以每張卡另外記「幾點按的」「在哪按的」，
 *    同時段／同地點加成。
 *  - **個人化不是黑箱**：排序只影響「常用」推薦列與類別內的順序，
 *    類別結構本身不動——失語症患者靠位置記憶找卡，整個版面跟著演算法
 *    大風吹反而是災難。這是跟社群 feed 唯一該不一樣的地方。
 *
 * 資料存 SharedPreferences（跟 SmartApiRouter 同模式）：量小（每卡一筆統計）、
 * 跨重啟持續學習、壞掉就重新開始學，不影響主功能。
 */
object AacUsageRanker {

    private const val TAG = "AacUsageRanker"
    private const val PREFS = "vw_aac_ranker"
    private const val KEY_STATS = "stats_json"

    /** 指數衰減半衰期：7 天。7 天沒按分數砍半，一個月不按幾乎歸零。 */
    private const val HALF_LIFE_MS = 7 * 24 * 3600_000.0

    /** 時段桶：一天切 6 桶（每 4 小時）。吃早餐藥跟睡前藥會落在不同桶。 */
    private const val HOUR_BUCKETS = 6

    /** 「常用」推薦列最多幾張。 */
    const val FEED_SIZE = 8

    /** 一張卡的使用統計。 */
    private class Stat(
        /** 衰減後的累積使用分（讀取時再補衰減）。 */
        var score: Double = 0.0,
        /** 上次更新 score 的時間（算衰減用）。 */
        var updatedAt: Long = 0L,
        /** 各時段桶的使用次數（情境加成用，故意不衰減——作息很穩定）。 */
        val hourBuckets: IntArray = IntArray(HOUR_BUCKETS),
        /** 最近一次使用時的地點標籤（粗略：只記最後一個，夠用了）。 */
        var lastLocation: String = ""
    ) {
        fun decayedScore(now: Long): Double {
            if (score <= 0.0 || updatedAt <= 0L) return 0.0
            val age = (now - updatedAt).coerceAtLeast(0L)
            return score * Math.pow(2.0, -age / HALF_LIFE_MS)
        }
    }

    private val stats = HashMap<String, Stat>()
    private var prefs: android.content.SharedPreferences? = null
    private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val raw = prefs?.getString(KEY_STATS, null) ?: return@runCatching
            val o = JSONObject(raw)
            for (key in o.keys()) {
                val s = o.getJSONObject(key)
                val buckets = IntArray(HOUR_BUCKETS)
                val arr = s.optJSONArray("h") ?: JSONArray()
                for (i in 0 until minOf(arr.length(), HOUR_BUCKETS)) buckets[i] = arr.optInt(i)
                stats[key] = Stat(
                    score = s.optDouble("s", 0.0),
                    updatedAt = s.optLong("t"),
                    hourBuckets = buckets,
                    lastLocation = s.optString("l")
                )
            }
            Log.i(TAG, "載入 ${stats.size} 張圖卡的使用統計")
        }.onFailure { Log.w(TAG, "統計載入失敗，重新學習：${it.message}") }
        loaded = true
    }

    @Synchronized
    private fun persist() {
        val p = prefs ?: return
        runCatching {
            val o = JSONObject()
            stats.forEach { (k, v) ->
                o.put(k, JSONObject()
                    .put("s", v.score).put("t", v.updatedAt)
                    .put("h", JSONArray().apply { v.hourBuckets.forEach { put(it) } })
                    .put("l", v.lastLocation))
            }
            p.edit().putString(KEY_STATS, o.toString()).apply()
        }
    }

    private fun bucketOf(now: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return cal.get(java.util.Calendar.HOUR_OF_DAY) / (24 / HOUR_BUCKETS)
    }

    /** 患者按了一張卡：先把舊分數衰減到現在，再 +1（社群的「一次互動」）。 */
    @Synchronized
    fun recordUse(itemId: String, locationTag: String = "") {
        val now = System.currentTimeMillis()
        val s = stats.getOrPut(itemId) { Stat() }
        s.score = s.decayedScore(now) + 1.0
        s.updatedAt = now
        s.hourBuckets[bucketOf(now)]++
        if (locationTag.isNotBlank()) s.lastLocation = locationTag
        persist()
    }

    /**
     * 一張卡此刻的推薦分。
     *
     * 基底＝衰減後使用分；情境加成用**乘法**——沒用過的卡（基底 0）不會只因
     * 時段對就冒出來，避免推薦列出現患者根本不認識的卡。
     */
    @Synchronized
    private fun scoreOf(itemId: String, now: Long, locationTag: String): Double {
        val s = stats[itemId] ?: return 0.0
        var sc = s.decayedScore(now)
        if (sc <= 0.0) return 0.0

        // 時段加成：這張卡歷史上有 ≥30% 的使用落在目前時段 → ×1.5
        val total = s.hourBuckets.sum()
        if (total >= 3) {
            val share = s.hourBuckets[bucketOf(now)].toDouble() / total
            if (share >= 0.3) sc *= 1.0 + share
        }
        // 地點加成：上次也是在這裡按的 → ×1.3（醫療卡在醫院浮上來）
        if (locationTag.isNotBlank() && locationTag == s.lastLocation) sc *= 1.3
        return sc
    }

    /**
     * 「常用」推薦列（社群的 For You）：全庫取分數最高的前 N 張。
     * 冷啟動（什麼都沒按過）回空清單，UI 就不顯示這一列——不硬塞猜測。
     */
    @Synchronized
    fun feed(allItems: List<AacItem>, locationTag: String = "", limit: Int = FEED_SIZE): List<AacItem> {
        val now = System.currentTimeMillis()
        return allItems.asSequence()
            .map { it to scoreOf(it.id, now, locationTag) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * 類別內排序：常用的往前、沒用過的維持原本順序附在後面。
     * sortedByDescending 是穩定排序，同分（都是 0）不會互換——版面不大風吹。
     */
    @Synchronized
    fun rankWithin(items: List<AacItem>, locationTag: String = ""): List<AacItem> {
        val now = System.currentTimeMillis()
        return items.sortedByDescending { scoreOf(it.id, now, locationTag) }
    }

    /** 患者最常用的詞（給重組 prompt 當個人詞彙偏好）。 */
    @Synchronized
    fun topLabels(allItems: List<AacItem>, limit: Int = 12): List<String> {
        val now = System.currentTimeMillis()
        return allItems.asSequence()
            .map { it to (stats[it.id]?.decayedScore(now) ?: 0.0) }
            .filter { it.second > 0.5 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first.label }
            .toList()
    }
}
