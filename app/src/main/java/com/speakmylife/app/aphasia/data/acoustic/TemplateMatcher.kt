package com.speakmylife.app.aphasia.data.acoustic

import com.speakmylife.app.aphasia.domain.model.AcousticTemplate
import com.speakmylife.app.aphasia.domain.model.FeatureSequence
import com.speakmylife.app.aphasia.domain.model.MatchMode
import com.speakmylife.app.aphasia.domain.model.MatchResult
import kotlin.math.max

/**
 * 比對政策：把 DTW 距離變成「要不要替使用者說出這句話」的決定。
 *
 * 純 Kotlin，與儲存層無關，可以完整用單元測試驗證——這裡的每一個分支
 * 都對應到一種真實的失敗模式，值得個別測。
 *
 * 判定順序：
 * 1. 距離必須低於該樣板自己的 [AcousticTemplate.rejectionThreshold]（登錄時校準）
 * 2. 最佳與次佳的差距必須大於 margin，否則判為 [MatchResult.Ambiguous]
 *
 * 第 2 條是刻意的保守設計。這個 App 會直接用合成語音替使用者發言，
 * **誤觸發（說出他沒想說的話）比沒觸發（再試一次）危險得多**——
 * 尤其當語句庫裡同時有 "I need the bathroom" 和 "I need my medication" 這種
 * 開頭相同、聲學上也接近的句子時。寧可問一次。
 */
object TemplateMatcher {

    /**
     * 算相對 margin 時分母的下限。
     *
     * 距離小於這個值的兩個候選，實務上都算「貼得很近」，再用比例去放大差異
     * 只會製造假的把握。而 best≈0 時若直接回無限大，等於宣告「完全確定」——
     * 但兩個樣板都貼合到距離趨近 0 時，那正是最該判成歧義的情況。
     */
    private const val MIN_MARGIN_SCALE = 0.5f

    /**
     * @param query 使用者這次發聲的特徵
     * @param templates 全部已登錄樣板
     * @param ambiguityLimit 進入 Ambiguous 時最多回傳幾個候選給 UI 選
     */
    /**
     * 每個樣板的比對結果。
     *
     * 公開出來是為了「測試」畫面：使用者剛錄好一句話，需要看到
     * **距離與門檻的實際數字**才知道是差一點還是差很多，
     * 也才有辦法回報「這個一直觸發不了」給我調參數。
     * 只回傳 Hit/NoMatch 的話，門檻要靠猜。
     */
    data class Score(
        val template: AcousticTemplate,
        val distance: Float,
        val span: IntRange?,
        /** 這個樣板這次適用的門檻（關鍵字模式會再收緊）。 */
        val threshold: Float,
    ) {
        val passed: Boolean get() = distance < threshold
        /** 距離相對門檻的比值。<1 通過，越小越有把握。 */
        val ratio: Float get() = if (threshold > 0f) distance / threshold else Float.MAX_VALUE
    }

    /**
     * 對所有樣板算距離，依相似度排序。不套任何門檻或 margin 判斷。
     *
     * 每個樣板依自己的模式比對：
     *  Keyword — 在語音裡滑動搜尋（講「I need some water」也能命中樣板「need water」）
     *  Whole   — 從頭對到尾（那個音本身就是全部，沒有前後文）
     */
    fun scoreAll(
        query: FeatureSequence,
        templates: List<AcousticTemplate>,
        /** 使用者可調的靈敏度倍率。>1 比較容易命中，<1 比較嚴格。 */
        sensitivity: Float = 1f,
    ): List<Score> =
        templates.mapNotNull { template ->
            when (template.matchMode) {
                MatchMode.Whole -> {
                    val d = DtwMatcher.distanceToBest(query, template.exemplars)
                    if (d < Float.MAX_VALUE) {
                        Score(template, d, null, template.rejectionThreshold * sensitivity)
                    } else null
                }
                MatchMode.Keyword -> {
                    val hit = DtwMatcher.subsequenceToBest(query, template.exemplars) ?: return@mapNotNull null
                    // 收緊幅度隨「搜尋自由度」縮放，不是固定的 0.75。
                    // 只說關鍵字時查詢跟樣板一樣長，沒有窗口可挑，就不該被收緊——
                    // 固定係數會讓最單純的情況反而最難觸發。
                    val avgTemplateFrames =
                        template.exemplars.sumOf { it.frameCount } / template.exemplars.size.coerceAtLeast(1)
                    val factor = DtwMatcher.keywordThresholdFactor(query.frameCount, avgTemplateFrames)
                    Score(
                        template, hit.distance, hit.startFrame until hit.endFrame,
                        template.rejectionThreshold * factor * sensitivity,
                    )
                }
            }
        }.sortedBy { it.distance }

    fun match(
        query: FeatureSequence,
        templates: List<AcousticTemplate>,
        ambiguityLimit: Int = 3,
        sensitivity: Float = 1f,
    ): MatchResult {
        if (query.frameCount == 0) return MatchResult.NotSpeech
        if (templates.isEmpty()) return MatchResult.NoMatch

        val scored = scoreAll(query, templates, sensitivity)
        if (scored.isEmpty()) return MatchResult.NoMatch

        // 只保留過得了自己門檻的。門檻是每個樣板各自校準的，
        // 所以不能先排序再套一個共同門檻。
        val accepted = scored.filter { it.passed }
        if (accepted.isEmpty()) return MatchResult.NoMatch

        val best = accepted.first()
        // 次佳要看「全部候選」而不是只看通過門檻的：一個距離很近但門檻剛好較嚴的
        // 樣板，代表這次發聲本來就有歧義，不該因為它沒過門檻就被當作不存在。
        // 相對比例而非絕對差：真實距離落在 3～15 之間，絕對差 0.15 的檢查
        // 等於從來沒生效過——兩個樣板一樣像時 App 仍會很有把握地講錯那句。
        // **要取「best 以外」的最佳者，不能寫死索引 1。**
        // scored 是全部樣板、accepted 是過了各自門檻的子集，兩者的第一名不一定是同一個。
        // 只要距離最近的那個沒過自己的門檻，best 就會是 scored[1]，
        // 而 scored.getOrNull(1) 取到的正是 best 自己 → margin 恆為 0
        // → 永遠判成「有歧義」，明明只有一個候選卻要使用者再選一次。
        val runnerUp = scored.firstOrNull { it.template.id != best.template.id }
        val margin = runnerUp?.let {
            // 分母加下限而不是在 best≈0 時回傳無限大。
            // 回無限大等於「完全確定」，但兩個樣板都貼合到距離趨近 0 時，
            // 那正是最該判成歧義的情況——講錯話比多問一次危險得多。
            (it.distance - best.distance) / max(best.distance, MIN_MARGIN_SCALE)
        } ?: Float.MAX_VALUE

        val hit = MatchResult.Hit(best.template, best.distance, margin, best.span)
        if (margin >= best.template.marginThreshold) return hit

        return MatchResult.Ambiguous(
            accepted.take(ambiguityLimit)
                .map { MatchResult.Hit(it.template, it.distance, margin, it.span) }
                .ifEmpty { listOf(hit) }
        )
    }
}
