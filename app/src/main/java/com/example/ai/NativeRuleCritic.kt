package com.example.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Task 3.1: Android Native 輕量防護網 (NativeRuleCritic)
 * 嚴禁使用 ML 模型以防 OOM，改用純 Kotlin 實作之輕量級字串相似度與意圖過濾演算法。
 * 執行於 Dispatchers.Default。
 */
object NativeRuleCritic {

    // 定義強烈意圖關鍵詞（強烈意圖詞彙庫）
    //
    // 中英兩份都要有。這裡比對的是「重組出來的句子」，而重組結果的語言跟著介面走——
    // 只放中文的話，英文模式下每一句都比不中任何一個詞，整道幻覺防護等於被關掉，
    // 模型憑空生出「call an ambulance」也照樣放行。
    private val STRONG_INTENT_WORDS_ZH = setOf(
        "渴", "水", "杯", "喝", "飲", "茶",
        "餓", "飯", "麵", "吃", "飽", "食", "餐",
        "不舒服", "痛", "藥", "醫", "病", "吐", "暈", "難受", "傷",
        "廁所", "尿", "便", "洗手間", "馬桶", "拉",
        "睡", "累", "床", "瞇", "睏",
        "救", "警", "急", "跌", "扶", "倒", "幫忙", "護士",
        "熱", "冷", "寒", "悶"
    )

    /**
     * 英文版。中文可以逐「字」比對（「痛」是一個完整語素），英文必須是完整單字，
     * 否則 "ill" 會命中 "still"、"ache" 會命中 "headache" 以外的雜訊。
     * 比對時一律走 [containsWord]，不要用 String.contains。
     */
    private val STRONG_INTENT_WORDS_EN = setOf(
        "thirsty", "water", "drink", "cup", "tea", "juice",
        "hungry", "eat", "food", "meal", "rice", "bread", "full",
        "pain", "painful", "hurt", "hurts", "ache", "sick", "ill", "medicine", "pill",
        "doctor", "nurse", "hospital", "vomit", "dizzy", "wound", "bleeding",
        "toilet", "bathroom", "restroom", "pee", "poop",
        "sleep", "sleepy", "tired", "bed", "rest",
        "help", "emergency", "ambulance", "police", "fell", "fall", "danger",
        "hot", "cold", "freezing", "stuffy"
    )

    // 常見中文停用詞，計算 Jaccard 相似度時予以排除以提昇精準度
    private val STOP_WORDS = setOf(
        '，', '。', '？', '！', '、', '的', '了', '是', '我', '你', '他', '在', '有', '個', 
        '和', '與', '或', '且', '這', '那', '就', '去', '要', '想', '會', '能', '可', '到',
        '、', '；', '：', '「', '」', '『', '』', '(', ')', '[', ']', '{', '}', ' ', '\t', '\n'
    )

    /**
     * 審查重組後的文本是否包含幻覺或過度解讀
     * @return Boolean true 表示通過（無明顯幻覺，語法安全），false 表示判定為幻覺或不通過
     */
    suspend fun audit(
        reconstructedText: String,
        vault: GlobalTagsVault
    ): Boolean = withContext(Dispatchers.Default) {
        if (reconstructedText.isBlank()) return@withContext false

        // 1. 統一轉為小寫簡潔化處理
        val normalizedReconstructed = reconstructedText.lowercase()
        // rawText 只是語音辨識的第一名；其他 N-best 候選同樣是「病人真的可能講的話」，
        // 必須一起算進比對母體。否則 LLM 正確地採用了第 2 名的字（例如第一名聽成「時」、
        // 第二名才是「水」），這裡會誤判成幻覺，整句被換成「我不確定您的意思」。
        // recentUtterances（對話前文）也算合法來源：prompt 現在明確允許模型用前文
        // 消歧義（「還要一杯」→接前文的咖啡），不算進來的話，凡是靠前文補出的字
        // 都會被這裡誤判成幻覺——跟當初漏算 N-best 是一模一樣的坑。
        val normalizedRaw = (listOf(vault.rawText) + vault.sttAlternatives + vault.recentUtterances)
            .joinToString(" ").lowercase()
        val normalizedLocation = vault.locationTag.lowercase()
        val normalizedVision = vault.visionTags.joinToString(" ").lowercase()

        // 2. 檢測重組句中出現的所有強烈意圖關鍵字（中英同時檢查，不看介面語言——
        //    碎詞可能是英文而重組成中文，反之亦然，兩邊都掃才不會漏）
        val isCjk: (String) -> Boolean = { w -> w.any { it.code in 0x4E00..0x9FFF } }
        val reconstructedIntents =
            STRONG_INTENT_WORDS_ZH.filter { normalizedReconstructed.contains(it) } +
            STRONG_INTENT_WORDS_EN.filter { containsWord(normalizedReconstructed, it) }

        // 3. 檢查這些強烈意圖關鍵字是否可在原始輸入或環境 Tag 中找到對應關聯
        for (intentWord in reconstructedIntents) {
            // 英文要比對完整單字（否則 "ill" 會被 "still" 誤判成有出處），
            // 但要容許 hurt/hurts 這種字尾變化，不然正確的重組會被誤擋。
            val cjk = isCjk(intentWord)
            val has: (String) -> Boolean =
                if (cjk) { hay -> hay.contains(intentWord) }
                else { hay -> containsStem(hay, intentWord) }
            val inRaw = has(normalizedRaw)
            // 情境詞在英文裡跟意圖詞沒有字面關係（hospital vs doctor），要靠對照表
            val inLocation = has(normalizedLocation) ||
                (!cjk && contextSupports(normalizedLocation, intentWord))
            val inVision = has(normalizedVision) ||
                (!cjk && contextSupports(normalizedVision, intentWord))

            // 如果出現了強烈意圖，但該意圖在 vault (rawText/location/vision) 中找不到任何影子，
            // 判定為 LLM 自行憑空捏造的幻覺 (全新強烈意圖詞彙)，不予通過
            if (!inRaw && !inLocation && !inVision) {
                Log.w(
                    "NativeRuleCritic",
                    "Audit FAILED: Detected hallucinated intent word '$intentWord' not present in GlobalTagsVault."
                )
                return@withContext false
            }
        }

        // 4. 計算 Jaccard Index (相似度交集) 作為雙重防線
        //
        // 中文逐「字元」算是合理的（一個漢字就是一個語素），但英文逐字元算完全沒有意義——
        // 任兩個英文句子的字母集合幾乎都高度重疊，Jaccard 永遠偏高，這道防線形同虛設。
        // 所以英文改成逐「詞」，中文維持逐字元。
        val vaultCleanText = "$normalizedRaw $normalizedLocation $normalizedVision"
        val reconstructedChars = tokenize(normalizedReconstructed)
        val vaultChars = tokenize(vaultCleanText)

        if (reconstructedChars.isEmpty()) {
            return@withContext true
        }

        val intersection = reconstructedChars.intersect(vaultChars)
        val union = reconstructedChars.union(vaultChars)
        val jaccardIndex = intersection.size.toDouble() / union.size.toDouble()

        Log.d(
            "NativeRuleCritic",
            "Audit metrics - Jaccard Index: $jaccardIndex (Intersection: ${intersection.size}, Union: ${union.size})"
        )

        // 原始輸入極短時 Jaccard 本來就會低，但只要沒有憑空冒出不相干的強烈意圖仍放行。
        // 「碎詞的內容有沒有被保留下來」跟上面一樣要用同一套切分方式，否則中英混用時
        // 兩邊的粒度不一致，這個判斷會永遠成立或永遠不成立。
        val rawTokens = tokenize(normalizedRaw)
        val containsAllRawNouns = rawTokens.isNotEmpty() && rawTokens.all { reconstructedChars.contains(it) }

        if (jaccardIndex < 0.02 && !containsAllRawNouns) {
            Log.w("NativeRuleCritic", "Audit FAILED: Low Jaccard similarity without content preserve.")
            return@withContext false
        }

        return@withContext true
    }

    /**
     * 切成可比對的單位：中日韓漢字逐字，拉丁字母逐詞。
     *
     * 混排的句子（"我要 water"）兩種規則會同時作用，各自切出自己的單位。
     * 停用詞在兩邊都會被濾掉，避免 the/是/了 這種高頻詞把相似度灌高。
     */
    private fun tokenize(text: String): Set<String> {
        val out = mutableSetOf<String>()
        // 漢字：一字一單位
        text.forEach { c ->
            if (c.code in 0x4E00..0x9FFF && !STOP_WORDS.contains(c)) out.add(c.toString())
        }
        // 拉丁字母／數字：一詞一單位
        Regex("[a-z0-9']+").findAll(text).forEach { m ->
            val w = m.value.trim('\'')
            // 字幹化後再入集合，Jaccard 才不會因為 hurt/hurts 這種變形而低估相似度
            if (w.length > 1 && w !in STOP_WORDS_EN) out.add(stemEn(w))
        }
        return out
    }

    /** 英文的整字比對：避免 "ill" 命中 "still"、"eat" 命中 "great"。 */
    private fun containsWord(haystack: String, word: String): Boolean =
        Regex("(?<![a-z])" + Regex.escape(word) + "(?![a-z])").containsMatchIn(haystack)

    /**
     * 極輕量的英文字幹化：只砍掉常見的屈折字尾。
     *
     * 為什麼需要：碎詞是「tooth hurt」、重組成「My tooth hurts.」，
     * 嚴格整字比對會因為 hurt≠hurts 判定「hurts 沒有出處」而擋掉一句完全正確的重組。
     * 中文沒有動詞變化，所以原本的邏輯從來沒踩到這個坑。
     *
     * 刻意不引入真正的 Porter stemmer：這裡只需要讓同一個詞的常見變形對得上，
     * 過度字幹化反而會把不同的詞混成一個，讓防護網失去意義。
     */
    private fun stemEn(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** 這個母體裡有沒有「同一個詞」（容許常見字尾變化）。 */
    private fun containsStem(haystack: String, word: String): Boolean {
        if (containsWord(haystack, word)) return true
        val target = stemEn(word)
        return Regex("[a-z']+").findAll(haystack).any { stemEn(it.value) == target }
    }

    /**
     * 情境詞 → 可以合理支撐的意圖詞。
     *
     * 中文靠字面就有關聯（「醫院」裡有「醫」，所以能支撐「醫生」），英文沒有這種關係：
     * hospital 與 doctor 一個字母都不重疊。少了這張表，在醫院說「I want to see a doctor」
     * 會被判成幻覺擋掉——那正是這個 App 最該講得出口的一句話。
     */
    private val CONTEXT_SUPPORT_EN: Map<String, Set<String>> = mapOf(
        "hospital" to setOf("doctor", "nurse", "medicine", "pill", "pain", "hurt", "sick", "ill", "ambulance", "wound", "bleeding"),
        "clinic" to setOf("doctor", "nurse", "medicine", "pill", "pain", "hurt", "sick", "ill"),
        "pharmacy" to setOf("medicine", "pill", "doctor", "sick", "ill", "pain"),
        "dentist" to setOf("pain", "hurt", "ache", "doctor", "medicine"),
        "restaurant" to setOf("eat", "food", "meal", "hungry", "water", "drink", "rice", "bread", "tea", "juice"),
        "cafe" to setOf("drink", "water", "tea", "juice", "eat", "food"),
        "kitchen" to setOf("eat", "food", "water", "drink", "meal", "hungry", "thirsty"),
        "canteen" to setOf("eat", "food", "meal", "hungry", "water", "drink"),
        "bathroom" to setOf("toilet", "restroom", "pee", "poop"),
        "restroom" to setOf("toilet", "bathroom", "pee", "poop"),
        "bedroom" to setOf("sleep", "sleepy", "tired", "bed", "rest"),
        "bed" to setOf("sleep", "sleepy", "tired", "rest"),
    )

    /** 情境（地點／看到的物件）能不能為這個意圖詞提供出處。 */
    private fun contextSupports(context: String, intentWord: String): Boolean =
        CONTEXT_SUPPORT_EN.any { (place, supported) ->
            containsWord(context, place) && supported.any { stemEn(it) == stemEn(intentWord) }
        }

    /** 英文停用詞：跟中文的 STOP_WORDS 同樣用途，濾掉不帶語意的高頻詞。 */
    private val STOP_WORDS_EN = setOf(
        "the", "a", "an", "is", "am", "are", "was", "were", "be", "been", "being",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "them",
        "my", "your", "his", "its", "our", "their", "this", "that", "these", "those",
        "to", "of", "in", "on", "at", "for", "with", "and", "or", "but", "if",
        "do", "does", "did", "have", "has", "had", "will", "would", "can", "could",
        "please", "want", "need", "would", "like", "very", "so", "not", "no", "yes"
    )
}
