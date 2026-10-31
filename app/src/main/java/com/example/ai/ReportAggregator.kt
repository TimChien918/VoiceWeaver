package com.example.ai

import com.example.data.AacComboLogDao
import com.example.data.DailyTrendCodec
import com.example.data.DailyTrendDay
import com.example.data.RehabLogDao
import com.example.data.StoryTrainingLogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/** 時間維度：成績單三段過濾 */
enum class ReportRange(val displayName: String) {
    Today("今日"),
    Month("本月"),
    Year("本年度");

    /** 回傳 (fromEpochMillis, toEpochMillis)，皆為包頭包尾。 */
    fun bounds(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance(Locale.TAIWAN).apply { timeInMillis = now }
        when (this) {
            Today -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            }
            Month -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            }
            Year -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            }
        }
        return cal.timeInMillis to now
    }
}

/**
 * 一份完整成績單。給 ReportScreen 直接綁定，也是 Telegram 匯出時序列化的來源。
 */
data class ReportSnapshot(
    val range: ReportRange,
    val totalRehabSessions: Int,
    val averageScore: Float,                     // 0–100
    val correctRate: Float,                      // 0–1，圖卡正確率 = rehab 平均 / 100
    val positiveEmotionCount: Int,               // 正向情緒字眼出現次數
    val rehabDaily: List<TimeBucket>,            // 折線圖：每日（或每月）平均分數
    val emotionDaily: List<TimeBucket>,          // 長條圖：正向情緒字眼每日次數
    val storyAttempts: Int,
    val storyAverageScore: Float,
    /** 折線圖：復健命中率（分數 ≥ [DailyTrendCodec.REHAB_HIT_SCORE] 的比例，0–100）。 */
    val rehabHitDaily: List<TimeBucket> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 給 Telegram 用的純文字版本（不需要圖片附件）。
     *
     * **整段都要走翻譯層。** 原本每一行都是寫死的中文，所以英日韓介面的使用者，
     * 推給家人的成績單仍然是一整篇中文——而收到的家人本來就是那個語言的人。
     * range.displayName 也一樣，原本直接內插沒有經過 tr()。
     */
    fun toTelegramText(): String = buildString {
        val tr = com.example.ui.I18n::tr
        appendLine("📊 <b>${tr("拾語者")} ${tr("成績單")} · ${tr(range.displayName)}</b>")
        appendLine("─────────────")
        appendLine("🎯 ${tr("復健次數")}：$totalRehabSessions")
        appendLine("📈 ${tr("平均分數")}：${"%.1f".format(averageScore)} / 100")
        appendLine("✅ ${tr("圖卡正確率")}：${"%.0f".format(correctRate * 100)}%")
        appendLine("💖 ${tr("正向情緒字眼")}：$positiveEmotionCount")
        appendLine("📖 ${tr("故事練習")}：$storyAttempts（${tr("平均")} ${"%.1f".format(storyAverageScore)}）")
        appendLine()
        val asc = rehabDaily.zipWithNext().count { it.second.value > it.first.value }
        val desc = rehabDaily.zipWithNext().count { it.second.value < it.first.value }
        appendLine(when {
            rehabDaily.size < 2 -> tr("尚無趨勢資料，繼續加油 💪")
            asc > desc -> tr("趨勢向上 📈 — 持續進步中！")
            asc < desc -> tr("趨勢略降 📉 — 適度休息，明天再戰")
            else -> tr("保持穩定 ✨")
        })
    }.trim()
}

/** 折線/長條圖的一個資料點 */
data class TimeBucket(
    val labelShort: String,    // "12/3" 或 "Jun"
    val value: Float           // 該 bucket 對應的數值（分數 / 次數）
)

/**
 * 正向情緒詞典。被使用者唸出來就 +1，作為「正向情緒字眼次數」指標。
 *
 * **不可以放單獨一個「好」。** 舊版放了，而註解還寫著「"我不好"裡的「好」
 * 這種反例不會誤計」——但比對是純粹的 indexOf 子字串搜尋，實測
 * 「我不好」「不好意思」「我這裡好痛」全部被算成正向情緒。
 * 「好痛」被記成正向，是把最需要被看見的疼痛訊號反向記進治療師的報表裡。
 *
 * 改成只收「本身就帶正向語意、不會被否定詞黏住」的詞組。
 */
private val POSITIVE_WORDS = listOf(
    "謝謝", "喜歡", "開心", "高興", "幸福", "舒服", "加油",
    // 原本那個單字「好」拆成明確的正向說法
    "很好", "好多了", "太好了", "好棒", "好極了",
)

/**
 * 否定詞。出現在正向詞正前方時那一次不算數——「不喜歡」「沒有好多了」
 * 跟「喜歡」「好多了」是相反的意思，計進同一個指標等於指標是壞的。
 */
private val NEGATIONS = listOf("不", "沒", "別", "無", "未", "毋")

fun countPositiveWords(text: String): Int =
    POSITIVE_WORDS.sumOf { w ->
        var idx = 0; var count = 0
        while (true) {
            val found = text.indexOf(w, idx); if (found < 0) break
            val prev = if (found > 0) text.substring(found - 1, found) else ""
            if (prev !in NEGATIONS) count++
            idx = found + w.length
        }
        count
    }

class ReportAggregator(
    private val rehabDao: RehabLogDao,
    private val aacDao: AacComboLogDao,
    private val storyDao: StoryTrainingLogDao
) {

    suspend fun build(range: ReportRange): ReportSnapshot = withContext(Dispatchers.Default) {
        val (from, to) = range.bounds()

        val rehabRows = rehabDao.between(from, to)
        val aacRows   = aacDao.between(from, to)
        val storyRows = storyDao.between(from, to)

        // RehabLog 平均分
        val avgScore = if (rehabRows.isEmpty()) 0f
                       else rehabRows.map { it.score }.average().toFloat()
        val correctRate = (avgScore / 100f).coerceIn(0f, 1f)

        // 正向情緒：rehab 句子 + aac 最終句 + story 回覆 全部加總
        val positiveCount =
            rehabRows.sumOf { countPositiveWords(it.targetSentence) } +
            aacRows.sumOf { countPositiveWords(it.finalSentence) } +
            storyRows.sumOf { countPositiveWords(it.userResponse) }

        val rehabDaily = bucketize(range, rehabRows.map { it.timestamp to it.score.toFloat() }) { avg(it) }
        val emotionDaily = bucketize(range, rehabRows.map { it.timestamp to countPositiveWords(it.targetSentence).toFloat() }) { it.sum() }
        // 命中＝0 或 100，取平均就是該時段的命中百分比
        val rehabHitDaily = bucketize(range, rehabRows.map {
            it.timestamp to (if (it.score >= DailyTrendCodec.REHAB_HIT_SCORE) 100f else 0f)
        }) { avg(it) }

        ReportSnapshot(
            range = range,
            totalRehabSessions = rehabRows.size,
            averageScore = avgScore,
            correctRate = correctRate,
            positiveEmotionCount = positiveCount,
            rehabDaily = rehabDaily,
            emotionDaily = emotionDaily,
            storyAttempts = storyRows.size,
            storyAverageScore = if (storyRows.isEmpty()) 0f else storyRows.map { it.score }.average().toFloat(),
            rehabHitDaily = rehabHitDaily
        )
    }

    private fun avg(values: List<Float>) = if (values.isEmpty()) 0f else values.average().toFloat()

    /**
     * 把資料丟到時間 bucket：
     *   Today → 每 2 小時 1 bucket（12 個）
     *   Month → 每 1 天 1 bucket（最多 31 個）
     *   Year  → 每 1 月 1 bucket（12 個）
     */
    private fun bucketize(
        range: ReportRange,
        points: List<Pair<Long, Float>>,
        reducer: (List<Float>) -> Float
    ): List<TimeBucket> {
        if (points.isEmpty()) return emptyList()
        val cal = Calendar.getInstance(Locale.TAIWAN)
        val groups = LinkedHashMap<String, MutableList<Float>>()
        points.forEach { (ts, v) ->
            cal.timeInMillis = ts
            val key = when (range) {
                ReportRange.Today -> "%02d:00".format((cal.get(Calendar.HOUR_OF_DAY) / 2) * 2)
                ReportRange.Month -> "%d/%d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                // 「月」原本寫死中文，X 軸標籤在英日韓介面會變成「1月 2月…」
                ReportRange.Year  -> com.example.ui.I18n.tr("%d月").format(cal.get(Calendar.MONTH) + 1)
            }
            groups.getOrPut(key) { mutableListOf() }.add(v)
        }
        return groups.map { (k, vs) -> TimeBucket(k, reducer(vs)) }
    }
}

// ─── 復健軌跡趨勢圖：逐日行為數據 → 折線圖資料點 ────────────────────────────

/**
 * 最近 [windowDays] 天裡**有資料**的日子，取一個值變成折線圖的點。
 *
 * 沒資料的日子直接跳過、不補 0：反應時間補 0 會畫成「那天反應超快」，
 * 命中率補 0 會畫成「那天全錯」，兩種都是假訊號，治療師會被誤導。
 */
internal fun trendBuckets(
    days: List<DailyTrendDay>,
    now: Long,
    windowDays: Int,
    value: (DailyTrendDay) -> Float?,
): List<TimeBucket> {
    val from = DailyTrendCodec.dayKey(now - (windowDays - 1).coerceAtLeast(0) * 86_400_000L)
    return days
        .filter { it.day >= from }
        .sortedBy { it.day }
        .mapNotNull { d ->
            val v = value(d) ?: return@mapNotNull null
            // "2026-10-31" → "10/31"
            val label = d.day.substring(5).split('-').joinToString("/") { it.trimStart('0').ifEmpty { "0" } }
            TimeBucket(label, v)
        }
}

/** 反應時間趨勢（秒）。 */
fun reactionTrendBuckets(
    days: List<DailyTrendDay>,
    now: Long = System.currentTimeMillis(),
    windowDays: Int = 30,
): List<TimeBucket> = trendBuckets(days, now, windowDays) { d -> d.avgReactionMs?.let { it / 1000f } }

/** 選句命中率趨勢（0–100）。 */
fun selectionHitTrendBuckets(
    days: List<DailyTrendDay>,
    now: Long = System.currentTimeMillis(),
    windowDays: Int = 30,
): List<TimeBucket> = trendBuckets(days, now, windowDays) { d -> d.hitRate?.let { it * 100f } }
