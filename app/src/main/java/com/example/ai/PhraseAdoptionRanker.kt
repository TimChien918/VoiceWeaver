package com.example.ai

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 重組候選句的「採納紀錄」。
 *
 * **要解的問題：** 使用者每次挑候選句都在告訴系統「我要的是這個說法」，
 * 但那個資訊以前只被拿去記一個成功率數字就丟掉了。下一次講同樣的意思，
 * AI 仍然可能把他上次拒絕過的說法排第一，他得再挑一次。
 * 對一個每句話都要花力氣的人來說，那是每天重複很多次的成本。
 *
 * **做法：** 記住被說出口的句子，之後遇到相同（正規化後）的說法就加分往前排。
 * 用指數衰減而不是單純計次——說話習慣會變，三個月前常講的不該永遠壓在最前面。
 * 半衰期與加權都刻意訂得保守：這是「同分時偏向他習慣的說法」，
 * 不是「不管 AI 判斷什麼都照舊」。信心度明顯較高的新候選仍然應該贏。
 *
 * 跟 [AacUsageRanker] 同一套設計（衰減分數 + SharedPreferences），
 * 因為要解的是同一類問題：記住這個人實際選了什麼。
 */
object PhraseAdoptionRanker {

    private const val TAG = "PhraseAdoptionRanker"
    private const val PREFS = "vw_phrase_adoption"
    private const val KEY_STATS = "stats_json"

    /** 半衰期 14 天。比 AAC 圖卡的 7 天長——完整句子的說法比單張圖卡穩定。 */
    private const val HALF_LIFE_MS = 14 * 24 * 3600_000.0

    /**
     * 加權上限。採納分再高，對信心度的加成也不超過這個值。
     *
     * 0.12 是刻意壓低的：候選排序的主導權必須留給 AI 對「這次語音」的判斷。
     * 採納紀錄只在兩個候選差不多時才起作用——如果讓它蓋過信心度，
     * 使用者這次想講的新說法就永遠排不到前面，等於被自己的歷史困住。
     */
    private const val MAX_BOOST = 0.12

    /** 記太多筆沒有意義，而且會拖慢每次排序。 */
    private const val MAX_ENTRIES = 300

    private class Stat(var score: Double = 0.0, var updatedAt: Long = 0L) {
        fun decayed(now: Long): Double {
            if (score <= 0.0 || updatedAt <= 0L) return 0.0
            return score * Math.pow(2.0, -(now - updatedAt).coerceAtLeast(0L) / HALF_LIFE_MS)
        }
    }

    private val stats = HashMap<String, Stat>()

    @Volatile private var loaded = false

    /**
     * 正規化：只比「說了什麼」，不比標點與空白。
     *
     * 不做的事：不砍字、不取關鍵詞。那樣會把「我要喝水」跟「我不要喝水」
     * 併成同一筆——在這個 App 裡那是會害人講反話的錯誤。
     */
    fun normalize(text: String): String =
        text.trim()
            // 轉小寫：英文候選常常只差在句首大小寫（"I need water" / "i need water"），
            // 不轉的話同一個說法會被記成兩筆，採納分永遠累積不起來，
            // 等於這個功能對英文使用者形同無效。
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[\\s]+"), "")
            .trimEnd('。', '，', '！', '？', '.', ',', '!', '?')

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATS, null) ?: return@runCatching
            val o = JSONObject(json)
            o.keys().forEach { k ->
                val e = o.optJSONObject(k) ?: return@forEach
                stats[k] = Stat(e.optDouble("s", 0.0), e.optLong("t", 0L))
            }
        }.onFailure { Log.w(TAG, "採納紀錄載入失敗：${it.message}") }
    }

    @Synchronized
    private fun save(context: Context) {
        runCatching {
            // 超量時砍掉衰減後分數最低的，留下真正常用的
            if (stats.size > MAX_ENTRIES) {
                val now = System.currentTimeMillis()
                stats.entries.sortedBy { it.value.decayed(now) }
                    .take(stats.size - MAX_ENTRIES)
                    .forEach { stats.remove(it.key) }
            }
            val o = JSONObject()
            stats.forEach { (k, v) -> o.put(k, JSONObject().put("s", v.score).put("t", v.updatedAt)) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_STATS, o.toString()).apply()
        }.onFailure { Log.w(TAG, "採納紀錄儲存失敗：${it.message}") }
    }

    /**
     * 使用者採納了這個說法（實際講出去了）。
     *
     * 只在「真的說出口」時呼叫——被換掉、被拒絕的候選不算。
     * 那些是 AI 猜錯的，記下來只會讓錯誤的說法越排越前面。
     */
    @Synchronized
    fun recordAdopted(context: Context, text: String) {
        val key = normalize(text)
        if (key.isBlank()) return
        load(context)
        val now = System.currentTimeMillis()
        val st = stats.getOrPut(key) { Stat() }
        // 先把舊分數衰減到現在，再加這次的 1 分——不然舊分數會被當成「剛剛發生」
        st.score = st.decayed(now) + 1.0
        st.updatedAt = now
        save(context)
        DiagnosticLog.i(DiagnosticLog.Cat.LLM, "採納紀錄 +1",
            "「${text.take(20)}」分數=%.2f".format(st.score))
    }

    /**
     * 這個說法過去被採納的程度，正規化到 0..1。
     *
     * 用 score/(score+3) 而不是直接除以最大值：前幾次採納的邊際效果最大，
     * 之後趨於平緩。一個講過 20 次的說法不該比講過 5 次的多壓別人 4 倍。
     */
    @Synchronized
    fun adoptionWeight(context: Context, text: String): Double {
        load(context)
        val s = stats[normalize(text)]?.decayed(System.currentTimeMillis()) ?: 0.0
        return if (s <= 0.0) 0.0 else s / (s + 3.0)
    }

    /**
     * 排序用的加成值，直接加在信心度上。
     *
     * 上限 [MAX_BOOST]，所以它只能改變「差不多的兩個候選」的先後，
     * 不會把 AI 明顯更有把握的新說法壓下去。
     */
    fun boostFor(context: Context, text: String): Double =
        adoptionWeight(context, text) * MAX_BOOST

    /** 測試與「清除學習紀錄」用。 */
    @Synchronized
    fun clear(context: Context) {
        stats.clear()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_STATS).apply()
    }
}
