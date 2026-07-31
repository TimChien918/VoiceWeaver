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
 * **EdgeRank 式推薦指數**（Facebook 動態牆的骨架：affinity × weight × decay）：
 * 一張卡的最終分數不只看「自己被按多少次」，還會從**相關的卡**吸收分數——
 *  - **協同關聯（學來的 affinity）**：同一句話裡一起出現過的卡互相拉抬，
 *    像 FB 的「你跟這個人互動多→他的貼文浮上來」。患者常把「喝水」和「杯子」
 *    組在同一句 → 之後按「喝水」，「杯子」的推薦指數就跟著升。
 *  - **語意相似（內建的 content similarity）**：概念詞群（水／食／藥／錢…）
 *    先天把「喝水」「杯子」「口渴」連在一起，解決協同關聯的冷啟動——
 *    第一次按「喝水」時「杯子」就已經有加成，不用等患者真的組過那一句。
 * 傳播只做一層（i ← j 的分數 × 關聯強度 × 0.5），跟 FB 一樣防止分數在圖上
 * 迴圈放大；而且只從「有分數的卡」出發，全庫 200+ 張也只算個位數來源。
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

    /**
     * 協同關聯（學來的 affinity）：pairKey("a|b"，字典序) → 衰減後的共現分。
     * 跟使用分同一個半衰期——上個月常組的句型不該永遠綁著推薦。
     */
    private class Edge(var score: Double = 0.0, var updatedAt: Long = 0L) {
        fun decayed(now: Long): Double {
            if (score <= 0.0 || updatedAt <= 0L) return 0.0
            return score * Math.pow(2.0, -(now - updatedAt).coerceAtLeast(0L) / HALF_LIFE_MS)
        }
    }
    private val edges = HashMap<String, Edge>()
    private fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

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
                if (key == "_edges") continue
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
            o.optJSONObject("_edges")?.let { eo ->
                for (k in eo.keys()) {
                    val e = eo.getJSONObject(k)
                    edges[k] = Edge(e.optDouble("s", 0.0), e.optLong("t"))
                }
            }
            Log.i(TAG, "載入 ${stats.size} 張圖卡統計、${edges.size} 條關聯")
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
            val eo = JSONObject()
            edges.forEach { (k, v) -> eo.put(k, JSONObject().put("s", v.score).put("t", v.updatedAt)) }
            o.put("_edges", eo)
            p.edit().putString(KEY_STATS, o.toString()).apply()
        }
    }

    /**
     * 概念詞群（content similarity 的冷啟動知識）：同一群的卡先天相關。
     * 用「詞包含關鍵字」比對——「喝水」「一杯水」「杯子」「口渴」都命中水群。
     * 詞群刻意粗：它只負責把相關卡拉進推薦視野，細排序交給學來的共現分。
     */
    private val CONCEPTS: List<List<String>> = listOf(
        listOf("水", "喝", "渴", "杯", "飲", "茶", "咖啡", "牛奶", "吸管", "冰", "熱的", "湯"),
        listOf("吃", "飯", "餓", "麵", "餐", "食", "水果", "麵包", "糖", "菜單"),
        listOf("藥", "醫", "病", "痛", "暈", "吐", "燒", "血", "護理", "診", "處方", "過敏"),
        listOf("錢", "$", "元", "付", "買", "刷卡", "現金", "找零", "貴", "發票", "收據", "悠遊卡"),
        listOf("廁所", "尿", "清潔", "衛生紙"),
        listOf("家", "媽", "爸", "小孩", "朋友"),
        listOf("車", "站", "機場", "登機", "司機", "停車"),
        listOf("睡", "累", "休息", "躺", "毯子", "床"),
        listOf("冷", "熱", "溫"),
        listOf("救", "警", "緊急", "危險", "跌倒", "呼吸", "噎")
    )

    private fun conceptsOf(label: String): Set<Int> =
        CONCEPTS.indices.filterTo(HashSet()) { idx -> CONCEPTS[idx].any { label.contains(it) } }

    /** 兩張卡的語意相似度：共享概念群 → 0.4（共享多群不再加，避免詞群重疊灌水）。 */
    private fun similarity(a: AacItem, b: AacItem): Double {
        if (a.id == b.id) return 0.0
        val shared = conceptsOf(a.label) intersect conceptsOf(b.label)
        return if (shared.isNotEmpty()) 0.4 else 0.0
    }

    private fun bucketOf(now: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return cal.get(java.util.Calendar.HOUR_OF_DAY) / (24 / HOUR_BUCKETS)
    }

    /**
     * 患者按了一張卡：先把舊分數衰減到現在，再 +1（社群的「一次互動」）。
     *
     * @param companions 目前句子裡已經有的卡——跟新按的這張建立協同關聯
     *   （同一句一起出現＝患者心裡這些概念是綁在一起的，FB 的 affinity 學的
     *   就是這種共現訊號）。
     */
    @Synchronized
    fun recordUse(itemId: String, locationTag: String = "", companions: List<String> = emptyList()) {
        val now = System.currentTimeMillis()
        val s = stats.getOrPut(itemId) { Stat() }
        s.score = s.decayedScore(now) + 1.0
        s.updatedAt = now
        s.hourBuckets[bucketOf(now)]++
        if (locationTag.isNotBlank()) s.lastLocation = locationTag
        companions.filter { it != itemId }.forEach { other ->
            val e = edges.getOrPut(pairKey(itemId, other)) { Edge() }
            e.score = e.decayed(now) + 1.0
            e.updatedAt = now
        }
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
     * EdgeRank 式推薦指數：base（自己的使用分）＋ 從相關卡吸收的分數。
     *
     *   rec(i) = base(i) + Σ_j  base(j) × ( 共現(i,j)/(共現+2) ＋ 語意相似(i,j) ) × 0.5
     *
     * - j 只跑「真的有分數的卡」（患者實際用過的那幾張），全庫 200+ 張也很便宜
     * - 共現分做拉普拉斯式壓縮 x/(x+2)：共現 1 次 ≈ 0.33、5 次 ≈ 0.71，
     *   永遠 < 1——學來的關聯再強也只能「拉抬」，不能超越本尊
     * - ×0.5 是單層傳播的阻尼（FB 同款思路）：分數只擴散一步，不在圖上迴圈放大
     * - 效果：患者常按「喝水」→「杯子」「口渴」即使沒按過也有分數，
     *   會依推薦指數浮進「常用」列
     */
    @Synchronized
    private fun recommendScore(
        item: AacItem,
        used: List<Pair<AacItem, Double>>,
        now: Long,
        locationTag: String
    ): Double {
        var rec = scoreOf(item.id, now, locationTag)
        for ((other, otherBase) in used) {
            if (other.id == item.id) continue
            val co = edges[pairKey(item.id, other.id)]?.decayed(now) ?: 0.0
            val affinity = co / (co + 2.0) + similarity(item, other)
            if (affinity > 0.0) rec += otherBase * affinity * 0.5
        }
        return rec
    }

    /** 全庫裡「有使用分」的卡（推薦指數的傳播來源）。 */
    private fun usedOf(allItems: List<AacItem>, now: Long, locationTag: String): List<Pair<AacItem, Double>> =
        allItems.mapNotNull { item ->
            val base = scoreOf(item.id, now, locationTag)
            if (base > 0.0) item to base else null
        }

    /**
     * 「常用」推薦列（社群的 For You）：全庫依**推薦指數**取前 N 張。
     * 不只是用過的卡——跟常用卡相關的（喝水→杯子）也會依指數浮進來。
     * 冷啟動（什麼都沒按過）回空清單，UI 就不顯示這一列——不硬塞猜測。
     */
    /**
     * 這張卡「現在還算不算常用」——回傳衰減後仍有效的使用次數（無條件捨去）。
     *
     * 必須看衰減後的分數而不是原始 score：一張三個月前按過五次、之後再沒碰過的卡，
     * 原始 score 永遠 > 0，但推薦分早已趨近 0，[feed] 根本不會把它排進來。
     * 若這裡仍算它「用過」，UI 的冷啟動判斷（totalUsed >= 3）就會在推薦列其實
     * 生不出東西的情況下把空列顯示出來——兩邊要用同一套衰減標準才不會打架。
     */
    @Synchronized
    fun getUsageCount(itemId: String): Int {
        val stat = stats[itemId] ?: return 0
        return stat.decayedScore(System.currentTimeMillis()).toInt()
    }

    /** 目前仍有有效分數的卡有幾張（冷啟動判斷用，比在 UI 端逐張 count 便宜）。 */
    @Synchronized
    fun activeItemCount(): Int {
        val now = System.currentTimeMillis()
        return stats.count { it.value.decayedScore(now) >= 1.0 }
    }

    // ─────────────────────────────────────────────────────────────
    // 測試注入（見 com.example.testing.TestHooks 的安全說明）
    // 推薦列、時間衰減、地點加成都要靠好幾天的真實使用才會浮現，
    // 手動測不到；這裡直接把統計塞進去，一秒重現原本要等一週的狀態。
    // ─────────────────────────────────────────────────────────────

    /**
     * 偽造一筆使用統計。
     *
     * @param ageDays 讓這筆統計「看起來是幾天前按的」。半衰期是 7 天，所以
     *   `score=8, ageDays=7` 會衰減成 4、`ageDays=14` 衰減成 2——用這個參數
     *   就能在不改系統時間的前提下驗證衰減曲線。
     * @param hourBucketOverride 指定落在哪個時段桶（0..5，每桶 4 小時）；
     *   -1 = 用現在的時段。測「早上該浮出吃藥卡」時要用。
     */
    @Synchronized
    fun injectStatForTesting(
        itemId: String,
        score: Double,
        ageDays: Double = 0.0,
        locationTag: String = "",
        hourBucketOverride: Int = -1
    ) {
        val now = System.currentTimeMillis()
        val backdatedAt = now - (ageDays * 24 * 3600_000.0).toLong()
        val s = stats.getOrPut(itemId) { Stat() }
        s.score = score
        s.updatedAt = backdatedAt
        val bucket = if (hourBucketOverride in 0 until HOUR_BUCKETS) hourBucketOverride else bucketOf(now)
        // 時段桶要填到讓 scoreOf 的 `total >= 3` 門檻成立，否則時段加成永遠不會生效，
        // 注入出來的狀態就跟真實累積出來的不一樣。
        s.hourBuckets[bucket] = maxOf(s.hourBuckets[bucket], score.toInt().coerceAtLeast(3))
        if (locationTag.isNotBlank()) s.lastLocation = locationTag
        persist()
        Log.i(TAG, "[TEST] 注入 $itemId score=$score ageDays=$ageDays bucket=$bucket loc='$locationTag'")
    }

    /** 偽造一條共現關聯（測 EdgeRank 的分數傳播：按「喝水」時「杯子」該不該浮上來）。 */
    @Synchronized
    fun injectEdgeForTesting(a: String, b: String, score: Double, ageDays: Double = 0.0) {
        val now = System.currentTimeMillis()
        val e = edges.getOrPut(pairKey(a, b)) { Edge() }
        e.score = score
        e.updatedAt = now - (ageDays * 24 * 3600_000.0).toLong()
        persist()
        Log.i(TAG, "[TEST] 注入關聯 $a↔$b score=$score")
    }

    /** 清空所有學到的統計與關聯，回到出廠的冷啟動狀態。 */
    @Synchronized
    fun clearStatsForTesting() {
        stats.clear()
        edges.clear()
        persist()
        Log.i(TAG, "[TEST] 統計已清空")
    }

    /** 目前統計的除錯快照（給 TestHooks 的 dump 用）。 */
    @Synchronized
    fun debugSnapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val items = JSONObject()
        stats.forEach { (id, s) ->
            items.put(id, JSONObject()
                .put("raw_score", s.score)
                .put("decayed_score", s.decayedScore(now))
                .put("age_days", (now - s.updatedAt) / 86_400_000.0)
                .put("last_location", s.lastLocation))
        }
        return JSONObject()
            .put("tracked_items", stats.size)
            .put("active_items", stats.count { it.value.decayedScore(now) >= 1.0 })
            .put("edges", edges.size)
            .put("items", items)
    }

    @Synchronized
    fun feed(allItems: List<AacItem>, locationTag: String = "", limit: Int = FEED_SIZE): List<AacItem> {
        val now = System.currentTimeMillis()
        val used = usedOf(allItems, now, locationTag)
        if (used.isEmpty()) return emptyList()
        return allItems.asSequence()
            .map { it to recommendScore(it, used, now, locationTag) }
            .filter { it.second > 0.1 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * 類別內排序：依推薦指數往前排、沒分數的維持原本順序附在後面。
     * sortedByDescending 是穩定排序，同分（都是 0）不會互換——版面不大風吹。
     */
    @Synchronized
    fun rankWithin(items: List<AacItem>, locationTag: String = ""): List<AacItem> {
        val now = System.currentTimeMillis()
        val used = usedOf(items, now, locationTag)
        if (used.isEmpty()) return items
        return items.sortedByDescending { recommendScore(it, used, now, locationTag) }
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
