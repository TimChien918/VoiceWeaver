package com.speakmylife.app.data

import java.util.Calendar
import java.util.Locale

/**
 * 行為數據的「逐日」累計（給復健軌跡趨勢圖用）。
 *
 * 為什麼要另外存一份：[BehaviorMetrics] 只有全期彙總值，治療師只看得到「平均反應 3.2 秒」，
 * 看不出這個人是從 8 秒慢慢進步到 3 秒，還是一直卡在 3 秒——而後者才是復健追蹤要的資訊。
 * 所以每次記一筆反應時間／選句時，同時累加到「當天」那一格。
 *
 * 存法沿用既有的 DataStore（一個字串鍵），不另開 Room 表：資料量很小（一天一行、
 * 最多保留 [MAX_DAYS] 天），而且開新表要做資料庫遷移，風險遠大於收益。
 */
data class DailyTrendDay(
    /** 當地日期 yyyy-MM-dd。字串排序＝時間排序。 */
    val day: String,
    /** 反應時間：當天記了幾筆、總耗時。 */
    val speakCount: Long = 0,
    val speakSumMs: Long = 0,
    /** 選句：當天總選句次數、其中 AI 第一候選被採用的次數。 */
    val candTotal: Long = 0,
    val candHit: Long = 0,
) {
    /** 當天平均反應時間；沒有資料回 null（不是 0——0 秒會被畫成「超快」）。 */
    val avgReactionMs: Long? get() = if (speakCount > 0) speakSumMs / speakCount else null

    /** 當天選句命中率 0..1；沒有資料回 null。 */
    val hitRate: Float? get() = if (candTotal > 0) candHit.toFloat() / candTotal else null
}

/** 逐日趨勢的編碼／更新。純 Kotlin、不碰 Android，單元測試直接跑。 */
object DailyTrendCodec {

    /** 保留天數上限：一年多一點，夠治療師看長期軌跡，又不會讓 DataStore 無限長大。 */
    const val MAX_DAYS = 400

    private val DAY_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 時間戳 → 當地日期鍵。用裝置時區：「今天」要跟使用者牆上的日曆一致。 */
    fun dayKey(timestampMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * 解析。格式：每天一段「日期,反應筆數,反應總毫秒,選句總數,選句命中」，段與段用 `;` 分隔。
     * 壞掉的段直接略過——寧可少一天，也不要因為一段髒資料讓整張報表打不開。
     */
    fun decode(raw: String): List<DailyTrendDay> =
        raw.split(';').mapNotNull { seg ->
            val f = seg.trim().split(',')
            if (f.size != 5 || !DAY_RE.matches(f[0])) return@mapNotNull null
            val n = f.drop(1).map { it.trim().toLongOrNull() ?: return@mapNotNull null }
            if (n.any { it < 0 }) return@mapNotNull null
            DailyTrendDay(f[0], n[0], n[1], n[2], n[3])
        }
            .groupBy { it.day }
            .map { (_, same) -> same.last() }   // 同一天重複時以最後一筆為準
            .sortedBy { it.day }

    fun encode(days: List<DailyTrendDay>): String =
        days.sortedBy { it.day }
            .takeLast(MAX_DAYS)
            .joinToString(";") { "${it.day},${it.speakCount},${it.speakSumMs},${it.candTotal},${it.candHit}" }

    private fun update(raw: String, day: String, change: (DailyTrendDay) -> DailyTrendDay): String {
        val days = decode(raw).toMutableList()
        val i = days.indexOfFirst { it.day == day }
        if (i >= 0) days[i] = change(days[i]) else days.add(change(DailyTrendDay(day)))
        return encode(days)
    }

    /** 當天多一筆反應時間。負值當 0（時鐘被調過時可能出現）。 */
    fun addReaction(raw: String, day: String, ms: Long): String =
        update(raw, day) { it.copy(speakCount = it.speakCount + 1, speakSumMs = it.speakSumMs + ms.coerceAtLeast(0)) }

    /** 當天多一筆選句；firstHit = 採用的是 AI 第一候選。 */
    fun addCandidate(raw: String, day: String, firstHit: Boolean): String =
        update(raw, day) {
            it.copy(candTotal = it.candTotal + 1, candHit = it.candHit + if (firstHit) 1 else 0)
        }

    /**
     * 匯出用的逐日列：把行為數據跟復健日誌（時間戳, 分數）按日期合併。
     *
     * @param hitScore 復健「命中」門檻。見 [REHAB_HIT_SCORE]。
     */
    fun rows(
        days: List<DailyTrendDay>,
        rehab: List<Pair<Long, Int>>,
        hitScore: Int = REHAB_HIT_SCORE,
    ): List<DailyTrendRow> {
        val rehabByDay = rehab.groupBy({ dayKey(it.first) }, { it.second })
        val byDay = days.associateBy { it.day }
        return (byDay.keys + rehabByDay.keys).toSortedSet().map { d ->
            val b = byDay[d]
            val scores = rehabByDay[d].orEmpty()
            DailyTrendRow(
                day = d,
                avgReactionMs = b?.avgReactionMs,
                candTotal = b?.candTotal ?: 0,
                candHitRate = b?.hitRate,
                rehabCount = scores.size,
                rehabHitRate = if (scores.isEmpty()) null
                               else scores.count { it >= hitScore }.toFloat() / scores.size,
            )
        }
    }

    /**
     * 復健「命中」的分數門檻。
     *
     * 70 對應評分提示詞裡「70-89 = 意思有傳達到、只有小瑕疵」這一級：
     * 對失語症復健來說，把意思說出來就是命中，不要求字字精準。
     */
    const val REHAB_HIT_SCORE = 70
}

/** 匯出（CSV／PDF）用的一天。null＝那天沒有該項資料，匯出時留空而不是寫 0。 */
data class DailyTrendRow(
    val day: String,
    val avgReactionMs: Long?,
    val candTotal: Long,
    val candHitRate: Float?,
    val rehabCount: Int,
    val rehabHitRate: Float?,
)
