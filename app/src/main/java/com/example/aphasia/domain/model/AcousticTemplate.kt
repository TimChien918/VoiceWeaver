package com.example.aphasia.domain.model

/**
 * 「直接波形比對」的核心資料：使用者發得出來的一個聲音，綁定到一句他想說的話。
 *
 * 失語症患者常常只剩下少數幾個能穩定發出的音（可能不是任何真實詞彙），
 * 傳統 ASR 一定會辨識失敗。這裡完全繞過語言模型：錄下那個聲音，
 * 用 DTW 比對，命中就播出對應的完整句子。
 */
data class AcousticTemplate(
    val id: Long = 0,
    /** 使用者看得到的標籤，例如 "Water" */
    val label: String,
    /**
     * 觸發方式。
     *
     * [MatchMode.Keyword] 是預設：使用者說「I need some water」時，
     * 樣板只要對上其中的「need water」就算命中，前後講了什麼不影響。
     * [MatchMode.Whole] 留給「那個音本身就是全部」的情況——
     * 失語症患者常只剩幾個發得出的怪音，那些音沒有前後文可言。
     */
    val matchMode: MatchMode = MatchMode.Keyword,
    /** 要聽的那幾個字，例如 "need water"。只是給使用者看的，比對完全靠波形。 */
    val keyword: String = label,
    /**
     * 這一筆屬於語句庫的哪一類（Basic needs／Medical／…），自己加的是 [CATEGORY_CUSTOM]。
     *
     * 純粹是**整理用**的欄位，比對邏輯完全不看它——上雲之後會變成
     * `AcousticModels/<分類>/<id>.json` 的資料夾名稱，讓使用者在自己的
     * 雲端硬碟裡看得出來哪個檔案是哪一類，而不是一排 5.json、6.json。
     */
    val category: String = CATEGORY_CUSTOM,
    /** 命中後實際要說出口的完整句子，例如 "I'd like some water, please." */
    val spokenPhrase: String,
    /** 三次以上的登錄錄音各自的 MFCC 序列；保留多個樣板可涵蓋發音變異 */
    val exemplars: List<FeatureSequence>,
    /**
     * 拒絕門檻。低於它才算命中。
     *
     * 由登錄錄音之間的「組內距離」校準而來（mean + 2σ），不是寫死的常數——
     * 每個人、每個音的穩定度差很多，寫死一定會對某些人全部誤觸發或全部不觸發。
     */
    val rejectionThreshold: Float,
    /**
     * 與次佳樣板的最小距離差，**相對比例**而非絕對距離。
     * 0.15 表示次佳必須比最佳遠 15% 以上，否則判定為「不確定」。
     *
     * 曾經是絕對距離，但真實距離落在 3～15 之間，0.15 的絕對差等於這個檢查
     * 從來沒有生效過——兩個樣板一樣像的時候，App 仍然會很有把握地講錯那句。
     * 對這個 App 來說，誤觸發（說錯話）比沒觸發（再試一次）危險得多。
     */
    val marginThreshold: Float = DEFAULT_MARGIN,
    val createdAtMillis: Long = 0L,
) {
    companion object {
        const val DEFAULT_MARGIN = 0.15f
        /** 低於這個登錄次數就無法可靠地估計 σ */
        const val MIN_ENROLLMENT_TAKES = 3

        /** 不屬於內建語句庫任何一類（使用者自己加的）。也是舊資料升級後的落點。 */
        const val CATEGORY_CUSTOM = "Custom"

        /**
         * 把分類洗成可以當資料夾名稱的字串。
         *
         * Drive 的檔名不能有 `/`，前後空白會被吃掉，空字串則直接建不出資料夾——
         * 這些都會讓上傳靜靜失敗，而使用者只會看到「同步好像沒作用」。
         * 分類目前全部來自內建語句庫（都是乾淨的英文字），但使用者自訂的那條路
         * 遲早會有人塞進奇怪的字，所以在寫進雲端前就擋掉。
         */
        fun sanitizeCategory(raw: String?): String {
            val cleaned = raw.orEmpty()
                .replace(Regex("""[/\\:*?"<>|]"""), " ")
                .trim()
                .replace(Regex("""\s+"""), " ")
                .take(48)
            return cleaned.ifBlank { CATEGORY_CUSTOM }
        }
    }
}

/** 樣板要整段對齊，還是在語音裡找片段。 */
enum class MatchMode { Keyword, Whole }

/** DTW 比對結果。 */
sealed interface MatchResult {
    data class Hit(
        val template: AcousticTemplate,
        /** 路徑長度正規化後的 DTW 距離，越小越像 */
        val distance: Float,
        /** 與次佳候選的距離差，相對比例（0.5＝次佳遠 50%）。無次佳時為無限大。 */
        val margin: Float,
        /** 關鍵字命中在語音裡的位置（幀）。整段比對時為 null。 */
        val span: IntRange? = null,
    ) : MatchResult

    /** 有候選但不夠確定——UI 應該詢問而不是直接說出口。 */
    data class Ambiguous(val candidates: List<Hit>) : MatchResult

    /** 沒有任何樣板夠接近。 */
    data object NoMatch : MatchResult

    /** 太短或太安靜，根本不是一次發聲。 */
    data object NotSpeech : MatchResult
}
