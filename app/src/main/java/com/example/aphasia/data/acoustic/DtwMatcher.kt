package com.example.aphasia.data.acoustic

import com.example.aphasia.domain.model.FeatureSequence
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dynamic Time Warping，帶 Sakoe-Chiba band 與路徑長度正規化。
 *
 * 自己寫而不是引 FastDTW：現成實作普遍缺這兩件事，而它們在這裡不是最佳化，
 * 是正確性問題——
 *
 * - **路徑長度正規化**：不正規化的話，長的發音累積成本天生就大。
 *   門檻若沒有跨長度可比，"water"（短）和 "bathroom"（長）就無法共用一套判斷邏輯。
 * - **Sakoe-Chiba band**：不限制對齊路徑的話，DTW 會為了壓低成本把一個音
 *   拉伸去對上完全不同長度的另一個音，製造出假的相似度。這正是誤觸發的來源。
 *
 * 記憶體是 O(min(n,m))：只保留兩排滾動列，不存整個成本矩陣。
 * 4 秒錄音約 400 幀，就算存整個矩陣也才 160k 個 float，
 * 但比對是熱路徑（每次發聲要對所有樣板跑一遍），少配置就少一次 GC。
 */
object DtwMatcher {

    /** 對齊路徑允許偏離對角線的比例。0.1 = ±10% 的序列長度。 */
    const val BAND_RATIO = 0.10f

    /** band 半徑的下限，避免極短的音被壓到幾乎不能彎折 */
    const val MIN_BAND_RADIUS = 5

    /** 落在 band 外的格子 */
    private const val INF = Float.MAX_VALUE / 4f

    /**
     * 正規化後的 DTW 距離。越小越像。
     *
     * @return 累積成本 / 對齊路徑長度。長度不同或無法對齊時回 [Float.MAX_VALUE]。
     */
    fun distance(a: FeatureSequence, b: FeatureSequence): Float {
        val n = a.frameCount
        val m = b.frameCount
        if (n == 0 || m == 0) return Float.MAX_VALUE

        // 長度差太多就直接判定不同，不必算。band 本來就會把這種情況擋成 INF，
        // 但先擋掉可以省下整個矩陣的計算——這是熱路徑最有效的一刀。
        val ratio = max(n, m).toFloat() / min(n, m)
        if (ratio > MAX_LENGTH_RATIO) return Float.MAX_VALUE

        val radius = max(MIN_BAND_RADIUS, (BAND_RATIO * max(n, m)).roundToInt())

        // 兩排滾動列：cost 是累積成本，steps 是走到該格所經過的路徑長度。
        // 必須平行追蹤 steps——用 (n+m) 近似會在對齊路徑偏離對角線時失準，
        // 而構音障礙的語音正好經常大幅偏離。
        var prevCost = FloatArray(m + 1) { INF }
        var prevSteps = IntArray(m + 1)
        var curCost = FloatArray(m + 1)
        var curSteps = IntArray(m + 1)
        prevCost[0] = 0f

        for (i in 1..n) {
            java.util.Arrays.fill(curCost, INF)
            java.util.Arrays.fill(curSteps, 0)
            // 這一列在 band 內的 j 範圍。用 i*m/n 而不是 i，才能處理長度不等的序列。
            val center = (i.toLong() * m / n).toInt()
            val lo = max(1, center - radius)
            val hi = min(m, center + radius)
            if (lo > hi) continue

            val frameA = a.frames[i - 1]
            for (j in lo..hi) {
                val d = euclidean(frameA, b.frames[j - 1])

                var bestCost = prevCost[j - 1]   // 對角：兩邊各前進一格
                var bestSteps = prevSteps[j - 1]
                if (prevCost[j] < bestCost) {    // 上：a 前進，b 停留
                    bestCost = prevCost[j]; bestSteps = prevSteps[j]
                }
                if (curCost[j - 1] < bestCost) { // 左：b 前進，a 停留
                    bestCost = curCost[j - 1]; bestSteps = curSteps[j - 1]
                }
                if (bestCost >= INF) continue

                curCost[j] = bestCost + d
                curSteps[j] = bestSteps + 1
            }

            prevCost = curCost.also { curCost = prevCost }
            prevSteps = curSteps.also { curSteps = prevSteps }
        }

        val total = prevCost[m]
        val steps = prevSteps[m]
        // steps == 0 表示 band 把終點切斷了，等同「對不起來」
        if (total >= INF || steps == 0) return Float.MAX_VALUE
        return total / steps
    }

    // ── 子序列比對（關鍵字觸發）──────────────────────────────────────────

    /**
     * 在一段語音裡找出最像樣板的那一小段。
     *
     * @param startFrame / [endFrame] 命中的區段落在 stream 的哪裡（10 ms 一幀）
     * @param distance 正規化後的距離，與 [DtwMatcher.distance] 同一個尺度
     */
    data class Subsequence(val distance: Float, val startFrame: Int, val endFrame: Int) {
        val lengthFrames: Int get() = endFrame - startFrame
    }

    /**
     * 子序列 DTW：**樣板必須走完，語音的起點與終點自由**。
     *
     * 這是「關鍵字觸發」與「整段比對」的唯一差別。整段比對要求兩邊從頭對到尾，
     * 所以講「I need some water」去比對樣板「water」會因為長度差太多直接被擋掉。
     * 這裡把第 0 列全部初始化為 0（語音可以從任何一幀開始），
     * 終點取最後一列的最小值（可以在任何一幀結束），中間的遞迴式完全一樣。
     *
     * **代價是它幾乎一定找得到「某個」還算像的窗口。** 長語音裡總有一小段碰巧接近，
     * 所以這裡一定要有長度合理性檢查——命中區段的長度必須跟樣板同一個量級，
     * 否則 DTW 會把樣板拉伸或壓縮去硬套一段完全不同的音，產生假的低距離。
     * 這正是誤觸發（替使用者說錯話）的來源。
     *
     * @return null 表示找不到長度合理的命中
     */
    fun subsequenceDistance(
        template: FeatureSequence,
        stream: FeatureSequence,
        minLengthRatio: Float = MIN_KEYWORD_LENGTH_RATIO,
        maxLengthRatio: Float = MAX_KEYWORD_LENGTH_RATIO,
    ): Subsequence? {
        val n = template.frameCount
        val m = stream.frameCount
        if (n == 0 || m == 0) return null
        // 語音比樣板還短就不可能包含它
        if (m < n * minLengthRatio) return null

        // 三排平行資料：累積成本、路徑長度、這條路徑是從語音的哪一幀起算的。
        // startCol 必須一起傳遞——沒有它就無法做長度檢查，而長度檢查是這個演算法
        // 唯一擋得住誤觸發的機制。
        var prevCost = FloatArray(m + 1)          // 第 0 列全 0 = 起點自由
        var prevSteps = IntArray(m + 1)
        var prevStart = IntArray(m + 1) { it }    // 從第 j 幀開始
        var curCost = FloatArray(m + 1)
        var curSteps = IntArray(m + 1)
        var curStart = IntArray(m + 1)

        for (i in 1..n) {
            curCost[0] = INF                       // 樣板還沒走完就沒有語音可用
            curSteps[0] = 0
            curStart[0] = 0
            val frameT = template.frames[i - 1]
            for (j in 1..m) {
                val d = euclidean(frameT, stream.frames[j - 1])

                var bestCost = prevCost[j - 1]     // 對角
                var bestSteps = prevSteps[j - 1]
                var bestStart = prevStart[j - 1]
                if (prevCost[j] < bestCost) {      // 樣板前進、語音停留
                    bestCost = prevCost[j]; bestSteps = prevSteps[j]; bestStart = prevStart[j]
                }
                if (curCost[j - 1] < bestCost) {   // 語音前進、樣板停留
                    bestCost = curCost[j - 1]; bestSteps = curSteps[j - 1]; bestStart = curStart[j - 1]
                }
                if (bestCost >= INF) {
                    curCost[j] = INF; curSteps[j] = 0; curStart[j] = 0
                } else {
                    curCost[j] = bestCost + d
                    curSteps[j] = bestSteps + 1
                    curStart[j] = bestStart
                }
            }
            prevCost = curCost.also { curCost = prevCost }
            prevSteps = curSteps.also { curSteps = prevSteps }
            prevStart = curStart.also { curStart = prevStart }
        }

        var best: Subsequence? = null
        for (j in 1..m) {
            if (prevCost[j] >= INF || prevSteps[j] == 0) continue
            val start = prevStart[j]
            val matched = j - start
            // 長度合理性：命中區段跟樣板不能差太多。少了這道檢查，
            // 一段兩秒的語音幾乎必定能生出一個看起來很像的短窗口。
            if (matched < n * minLengthRatio || matched > n * maxLengthRatio) continue
            val normalized = prevCost[j] / prevSteps[j]
            if (best == null || normalized < best.distance) {
                best = Subsequence(normalized, start, j)
            }
        }
        return best
    }

    /** 對一組樣板做子序列比對，取最像的那一個。 */
    fun subsequenceToBest(stream: FeatureSequence, exemplars: List<FeatureSequence>): Subsequence? =
        exemplars.mapNotNull { subsequenceDistance(it, stream) }.minByOrNull { it.distance }

    /** 命中區段長度相對樣板的下限 */
    const val MIN_KEYWORD_LENGTH_RATIO = 0.6f

    /** 命中區段長度相對樣板的上限。允許講慢，但不允許 DTW 把樣板攤平到整段語音上。 */
    const val MAX_KEYWORD_LENGTH_RATIO = 1.8f

    /**
     * 查詢遠長於樣板時的門檻收緊係數。
     *
     * 子序列比對可以在長語音裡挑最好的窗口，所以它的距離**天生低於**整段比對。
     * 直接沿用由登錄錄音（短對短）算出來的門檻會太鬆，一講長句就到處誤觸發。
     */
    const val KEYWORD_THRESHOLD_FACTOR = 0.85f

    /** 查詢長到樣板的幾倍時，收緊到滿檔。 */
    const val FULL_FREEDOM_RATIO = 2.0f

    /**
     * 依「搜尋自由度」決定要收緊多少。
     *
     * 這是修掉「只說關鍵字反而不會觸發」的關鍵。收緊的理由是子序列比對
     * 可以在長語音裡挑最好的窗口，但**查詢和樣板一樣長時根本沒有窗口可挑**——
     * 自由度是零，卻照樣被收緊 25%，等於對最單純的情況最嚴格。
     *
     * 實測（真人錄音，樣板平均 87 幀）：
     *   只說關鍵字 97 幀  → 自由度 0.11 → 幾乎不收緊，距離 4.01 輕鬆通過
     *   講整句   281 幀  → 自由度 2.23 → 收緊到 0.75，5.54 被正確擋下
     *
     * @param queryFrames 這次語音的幀數
     * @param templateFrames 樣板的平均幀數
     */
    fun keywordThresholdFactor(queryFrames: Int, templateFrames: Int): Float {
        if (templateFrames <= 0) return KEYWORD_THRESHOLD_FACTOR
        val freedom = (queryFrames - templateFrames).toFloat() / templateFrames
        val t = (freedom / FULL_FREEDOM_RATIO).coerceIn(0f, 1f)
        return 1f - (1f - KEYWORD_THRESHOLD_FACTOR) * t
    }

    /**
     * 對一組樣板取最小距離。同一個標籤保留多次登錄錄音，
     * 命中只要像其中任何一次就算——發音變異本來就不該要求每次都像平均值。
     */
    fun distanceToBest(query: FeatureSequence, exemplars: List<FeatureSequence>): Float =
        exemplars.minOfOrNull { distance(query, it) } ?: Float.MAX_VALUE

    /**
     * 由登錄錄音之間的「組內距離」推出拒絕門檻。
     *
     * 用 mean + kσ 而不是寫死常數：每個人、每個音的穩定度差很多。
     * 對某個使用者「很像」的距離，對另一個可能已經算是不同的音了。
     *
     * @param k 幾個標準差。
     *
     *   預設 2.8，不是常見的 2.0。理由是**小樣本**：3～5 次登錄只產生 3～10 個
     *   配對距離，樣本標準差在這個規模下會系統性低估母體離散度
     *（統計上要涵蓋 95% 且有 95% 信心，n 這麼小時需要的 k 遠大於 2）。
     *   用 2.0 的實測後果就是使用者講同一句話有時中有時不中——
     *   實測距離在 3.6 到 7.7 之間跳，而 k=2.0 算出來的門檻只有 7.3。
     *
     * @return null 表示樣本不足以估計離散程度，呼叫端應要求使用者多錄幾次
     */
    fun calibrateThreshold(exemplars: List<FeatureSequence>, k: Float = 2.8f): Float? {
        if (exemplars.size < 2) return null

        // **用「最近鄰距離」校準，不是全配對平均。**
        //
        // 比對時取的是「與任一次登錄錄音的最小距離」（distanceToBest），
        // 所以門檻也必須用同一個運算來估，否則兩者對不上。
        //
        // 用全配對平均的實測後果：補錄之後門檻從 10.1 一路漲到 21.9——
        // 因為新加的錄音跟舊的不一致時，mean 和 σ 會一起被拉高。
        // 門檻寬到那種程度的樣板會把所有聲音都吞掉，使用者說 A 卻聽到 B。
        //
        // 最近鄰的行為正好相反：多錄幾次會讓涵蓋變密、最近鄰距離變小，
        // 門檻跟著收斂。補錄因此是「提高召回率並收緊門檻」，而不是雙重放寬。
        val nearest = exemplars.indices.mapNotNull { i ->
            exemplars.indices
                .filter { it != i }
                .map { distance(exemplars[i], exemplars[it]) }
                .filter { it < Float.MAX_VALUE }
                .minOrNull()
        }
        // 少於 2 個有效值就沒有 σ 可估。
        if (nearest.size < 2) return null

        // 砍掉最差的四分之一再統計。
        //
        // 沒有這一步的話，一次錄壞（咳嗽、環境噪音、講到一半停住）就會把門檻整個拉高——
        // 那一次自己的最近鄰距離很大，而它會同時推高 mean、σ 和下限。
        // 實測補進一個完全不同的音會讓門檻從 0.45 跳到 33.4。
        // 四分之一是保守的：4 次錄音只砍 1 次，3 次則一次都不砍（樣本太少不敢砍）。
        val sorted = nearest.sorted()
        val kept = sorted.take(sorted.size - sorted.size / 4)

        val mean = kept.average().toFloat()
        val variance = kept.sumOf { val e = it - mean; (e * e).toDouble() } / kept.size
        val sigma = sqrt(variance).toFloat()

        // σ 為 0（幾次錄音幾乎一模一樣）時給一個相對下限，
        // 否則門檻會緊到連使用者自己都過不了。
        val spread = max(sigma * k, mean * MIN_RELATIVE_SPREAD)
        // 門檻至少要收得下（砍掉離群值之後）最孤立的那一次。
        val floor = kept.max() * (1f + MIN_RELATIVE_SPREAD)
        // **絕對下限。** mean 與 σ 同時為 0 時（幾次錄音的特徵完全一樣，
        // 例如錄到的是靜音或極短的片段）上面三項全是 0，門檻就變成 0——
        // 而距離永遠不小於 0，等於這個樣板**永遠不會命中**。
        // 使用者會看到「已啟用」卻怎麼叫都沒反應，完全查不出原因。
        return max(max(mean + spread, floor), MIN_ABSOLUTE_THRESHOLD)
    }

    /**
     * 這組登錄錄音有多一致。給登錄後的品質提示用。
     *
     * @return 最近鄰距離的平均。數字越大代表幾次錄音彼此越不像——
     *   要嘛是使用者每次講得都不一樣，要嘛錄到的根本不是他的聲音（環境噪音）。
     *   後者實測會產生門檻 10 以上的樣板，而那種樣板會把別的音一起吞掉。
     */
    fun enrollmentSpread(exemplars: List<FeatureSequence>): Float? {
        if (exemplars.size < 2) return null
        val nearest = exemplars.indices.mapNotNull { i ->
            exemplars.indices.filter { it != i }
                .map { distance(exemplars[i], exemplars[it]) }
                .filter { it < Float.MAX_VALUE }
                .minOrNull()
        }
        return if (nearest.isEmpty()) null else nearest.average().toFloat()
    }

    /**
     * 兩幀之間的距離。**維度不同時回 [Float.MAX_VALUE]，不是只比共同的前綴。**
     *
     * 只比前綴的後果實測是災難性的：一個 2 維的樣板對上 3 維的查詢會算出
     * 距離 0.0——也就是「完全一樣」，於是那個樣板會命中所有聲音，
     * 使用者講什麼都被說成同一句。
     *
     * 這不是假想：特徵維度會因為 MFCC 設定改變而變（係數個數、要不要 delta），
     * 而樣板現在會跨裝置同步——舊裝置存的樣板拉到新版就是不同維度。
     */
    private fun euclidean(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    /** 兩段長度比超過這個值就不必比對——不可能是同一個發音 */
    const val MAX_LENGTH_RATIO = 2.5f

    /** σ≈0 時門檻相對於 mean 的最小放寬幅度，同時也是「最差配對」的安全邊際 */
    private const val MIN_RELATIVE_SPREAD = 0.25f

    /**
     * 門檻的絕對下限。
     *
     * 防的是「門檻算成 0 ⇒ 樣板永遠不會命中」。訂得很小（真實錄音的組內距離
     * 通常在 3～8），所以對正常的登錄完全沒有影響——它只在退化的輸入上起作用。
     */
    private const val MIN_ABSOLUTE_THRESHOLD = 0.05f

    /** 提供給測試與診斷：兩段序列的長度是否落在可比對範圍內 */
    fun lengthsComparable(a: FeatureSequence, b: FeatureSequence): Boolean {
        if (a.frameCount == 0 || b.frameCount == 0) return false
        return max(a.frameCount, b.frameCount).toFloat() /
            min(a.frameCount, b.frameCount) <= MAX_LENGTH_RATIO
    }
}
