package com.speakmylife.app.aphasia.data.remote

import com.speakmylife.app.aphasia.domain.model.CommunicationPhrase
import com.speakmylife.app.aphasia.domain.model.ReconstructedSentence

/**
 * 擋掉模型憑空補進來的內容。
 *
 * prompt 裡已經寫了「禁止加入碎詞沒有的資訊」，但那是請求不是保證——
 * 實際跑下來，模型仍然會把 "ten" 補成一整句帶時間地點的話，
 * 甚至對著幾乎沒有內容的輸入給出 95% 信心的完整句子。
 * 這個 App 會用合成語音把結果說出去，所以程式層必須再驗一次。
 *
 * 判斷方式是**實詞比對**：候選句裡每一個實詞（名詞、動詞、形容詞、數字）
 * 都必須能在碎詞或個人語句庫裡找到來源，否則就是憑空生出來的。
 * 功能詞與少數幾個「載體詞」（I / want / need / please…）不算，
 * 因為把碎詞變成完整句子本來就需要它們。
 *
 * 語言：以英文為主，同時處理中文（逐字比對，中文沒有詞形變化的問題）。
 */
object ReconstructionGuard {

    /**
     * 功能詞：代名詞、冠詞、介系詞、連接詞、助動詞。
     * 這些出現在候選句裡不需要碎詞支撐——沒有它們就組不出句子。
     */
    private val FUNCTION_WORDS = setOf(
        "i", "me", "my", "mine", "myself", "you", "your", "yours", "we", "us", "our",
        "it", "its", "this", "that", "these", "those", "there", "here",
        "a", "an", "the", "some", "any", "no", "not", "and", "or", "but", "so", "if",
        "of", "to", "in", "on", "at", "for", "with", "from", "by", "up", "down", "out",
        "is", "am", "are", "was", "were", "be", "been", "being",
        "do", "does", "did", "have", "has", "had",
        "can", "could", "will", "would", "shall", "should", "may", "might", "must",
        "please", "thank", "thanks", "yes", "okay", "ok",
    )

    /**
     * 載體詞：把碎詞包成一句話最起碼需要的動詞。
     *
     * 這份清單刻意很短。每加一個詞就等於允許模型多憑空生出一個概念，
     * 而 "help"、"doctor"、"pain" 這類看似無害的詞，補錯了後果很嚴重——
     * 所以它們不在這裡。
     */
    private val CARRIER_WORDS = setOf(
        "want", "wants", "wanted", "need", "needs", "needed",
        "like", "likes", "would", "get", "give", "go", "going",
    )

    /** 中文的功能字與載體字。中文沒有詞形變化，逐字比對即可。 */
    private val FUNCTION_CHARS_ZH = setOf(
        '我', '你', '他', '她', '它', '的', '了', '嗎', '呢', '吧', '啊', '喔',
        '是', '在', '有', '和', '跟', '與', '要', '想', '請', '可', '以', '能',
        '這', '那', '個', '們', '不', '沒', '很', '好', '一', '下', '幫',
    )

    sealed interface Verdict {
        data object Allowed : Verdict
        /** 候選句引入了來源不明的實詞。 */
        data class Fabricated(val addedWords: List<String>) : Verdict
    }

    /**
     * @param candidate 模型給的候選句
     * @param fragments 使用者實際說出／選出的碎詞
     * @param personalContext 個人語句庫檢索到的句子。這些是使用者自己講過的話，
     *   所以它們裡面的詞算是有來源——但只有在檢索有命中時才會傳進來。
     */
    fun check(
        candidate: ReconstructedSentence,
        fragments: List<String>,
        personalContext: List<CommunicationPhrase> = emptyList(),
    ): Verdict {
        val allowed = buildSet {
            fragments.forEach { addAll(stems(it)) }
            personalContext.forEach { addAll(stems(it.text)) }
        }
        val added = stems(candidate.text).filter { token ->
            token !in allowed && !isFreelyAllowed(token) && !hasStemMatch(token, allowed)
        }
        return if (added.isEmpty()) Verdict.Allowed else Verdict.Fabricated(added.distinct())
    }

    /** 過濾掉憑空生成的候選，並記錄被擋下的原因。 */
    fun filter(
        candidates: List<ReconstructedSentence>,
        fragments: List<String>,
        personalContext: List<CommunicationPhrase> = emptyList(),
        onRejected: (ReconstructedSentence, List<String>) -> Unit = { _, _ -> },
    ): List<ReconstructedSentence> = candidates.filter { candidate ->
        when (val verdict = check(candidate, fragments, personalContext)) {
            Verdict.Allowed -> true
            is Verdict.Fabricated -> { onRejected(candidate, verdict.addedWords); false }
        }
    }

    private fun isFreelyAllowed(token: String): Boolean =
        token in FUNCTION_WORDS || token in CARRIER_WORDS ||
            (token.length == 1 && token[0] in FUNCTION_CHARS_ZH)

    /**
     * 詞形變化的容錯：碎詞是 "hurt" 而候選句寫 "hurts"，不該被當成憑空生成。
     * 反過來也要成立，所以兩邊都取詞幹再比。
     */
    private fun hasStemMatch(token: String, allowed: Set<String>): Boolean {
        val stem = stemEn(token)
        if (stem.length < 3) return false
        return allowed.any { stemEn(it) == stem }
    }

    /** 切詞。中文逐字，英文按空白與標點切開。數字保留（"10" 與 "ten" 都算實詞）。 */
    private fun stems(text: String): Set<String> {
        val out = HashSet<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) { out.add(word.toString()); word.clear() }
        }
        for (ch in text.lowercase()) {
            when {
                ch.code in 0x4E00..0x9FFF -> { flush(); out.add(ch.toString()) }
                ch.isLetterOrDigit() || ch == '\'' -> word.append(ch)
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** 很輕的英文詞幹處理。只剝最常見的字尾，不做完整的 Porter stemming。 */
    private fun stemEn(word: String): String {
        var w = word.removeSuffix("'s")
        for (suffix in listOf("ing", "ed", "es", "s")) {
            if (w.length > suffix.length + 2 && w.endsWith(suffix)) {
                w = w.dropLast(suffix.length)
                break
            }
        }
        return w
    }
}
