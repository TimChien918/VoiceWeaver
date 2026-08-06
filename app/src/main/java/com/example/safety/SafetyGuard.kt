package com.example.safety

/**
 * 醫療級安全防護層。位於 LLM 與 UI 之間：
 *
 *  - [DEFENSIVE_SYSTEM_PROMPT]：在送進雲端 / 本地 LLM 的 system message 前置黏上，
 *    強制限制模型不可生成自殺、自殘、絕望、否定醫治等字眼。
 *  - [containsCrisisSignal]：在使用者**輸入**端做攔截。任何來源（圖卡組合後的句子、
 *    重組區的片段、STT 結果）都必須先經過這層；命中即觸發 [CrisisAction]。
 *  - [sanitizeForSpeech]：在 TTS / 顯示之前再過一次模型輸出，如果模型還是吐出
 *    禁字，替換成 "（請與家屬聯絡）"，避免讓使用者聽到負面語句。
 *
 * 這三層是「縱深防禦」：prompt 設限是第一層；輸入攔截是第二層；輸出消毒是第三層。
 * 不要因為信任 prompt 而拿掉第三層。
 */
object SafetyGuard {

    /** 中文 / 英文常見危機關鍵字。盡量比對「字串包含」而不是「等於」，因為使用者
     *  可能會把字拆成片段（"自...殺"）餵進重組區。 */
    /**
     * 危機訊號。
     *
     * **這份清單漏一個說法，就等於那個說法完全沒有安全網。** 實測過
     * 「I want to die」原本判定為 NORMAL——它不在清單裡，而 kill myself／
     * end my life 都在，顯然只是漏掉。介面已經支援 en/ja/ko，安全層卻還停在
     * 以中文為主的階段；這一層漏掉的代價跟其他地方完全不同，寧可多擋。
     *
     * 寧可誤報也不要漏報：誤報的代價是多跳一個關心的畫面，漏報的代價是
     * 這個人唯一能求救的管道安靜地放行。
     */
    private val CRISIS_PATTERNS: List<Regex> = listOf(
        // 中文
        Regex("自\\s*殺"),
        Regex("自\\s*殘"),
        Regex("自\\s*我\\s*了\\s*斷"),
        Regex("想\\s*死"),
        Regex("不\\s*想\\s*活"),
        Regex("活\\s*不\\s*下\\s*去"),
        Regex("結\\s*束\\s*生\\s*命"),
        Regex("了\\s*結\\s*自\\s*己"),
        Regex("跳\\s*樓"),
        Regex("上\\s*吊"),
        Regex("吞\\s*藥"),
        Regex("割\\s*腕"),
        // 英文
        Regex("(?i)suicid"),                       // suicide / suicidal
        Regex("(?i)kill\\s*(myself|my\\s*self)"),
        Regex("(?i)end\\s*(my|it\\s*all)\\b.*\\blife|end\\s*it\\s*all"),
        Regex("(?i)take\\s*my\\s*(own\\s*)?life"),
        Regex("(?i)self[-\\s]?harm"),
        Regex("(?i)hurt\\s*myself"),
        Regex("(?i)cut\\s*myself"),
        Regex("(?i)want\\s*(to\\s*)?die"),          // 實測原本漏掉，是最常見的說法之一
        Regex("(?i)wanna\\s*die"),
        Regex("(?i)(don'?t|do\\s*not)\\s*want\\s*to\\s*live"),
        Regex("(?i)better\\s*off\\s*dead"),
        Regex("(?i)hang\\s*myself"),
        Regex("(?i)jump\\s*off"),
        Regex("(?i)overdose"),
        // 日文
        Regex("自\\s*殺"),
        Regex("死\\s*に\\s*たい"),
        Regex("消\\s*え\\s*たい"),
        Regex("生\\s*き\\s*た\\s*く\\s*な\\s*い"),
        // 韓文
        Regex("자\\s*살"),
        Regex("죽\\s*고\\s*싶"),
        Regex("살\\s*기\\s*싫"),
    )

    /** 模型若還是吐出來，要靜默替換的詞 → 給病人友善的中性說法。 */
    private val OUTPUT_REDACTIONS: Map<Regex, String> = mapOf(
        Regex("(?i)你\\s*不\\s*會\\s*好\\s*了")    to "我們會一起努力復健",
        Regex("(?i)你\\s*治\\s*不\\s*好")          to "我們會繼續陪你",
        Regex("(?i)沒\\s*救\\s*了")               to "請聯絡家屬一起決定",
        Regex("(?i)放\\s*棄\\s*治\\s*療")          to "與醫師討論下一步",
        Regex("自\\s*殺")                         to "（請與家屬聯絡）",
        Regex("自\\s*殘")                         to "（請與家屬聯絡）",
        Regex("(?i)suicid\\w*")                   to "(please contact family)",
        Regex("(?i)kill\\s*yourself")             to "(please contact family)",
        Regex("(?i)you\\s*(will\\s*)?(never|won'?t)\\s*(get\\s*)?(better|recover)")
                                                 to "we will work on rehab together",
        Regex("(?i)there'?s\\s*no\\s*hope")      to "please talk with your family",
        Regex("(?i)give\\s*up\\s*(on\\s*)?treatment")
                                                 to "discuss the next step with your doctor"
    )

    /**
     * 預設貼在所有 LLM 呼叫的 system prompt 最前面。
     *
     * 用英文寫：模型對英文硬指令的遵從度較好，而且提示詞主體現在也全面英文化了，
     * 中英夾雜反而會讓模型在語言之間搖擺（見 [com.example.ui.I18n.outputLanguageDirective] 的註解）。
     *
     * **這裡刻意不再指定輸出語言。** 舊版第 4 條寫死「輸出永遠是繁體中文」，
     * 而這段文字黏在每一次 LLM 呼叫的最前面——等於每次都在跟各處的
     * outputLanguageDirective 對打，英文介面拿到中文回應有一部分就是這樣來的。
     * 輸出語言由各個 prompt 自己負責，這裡只管安全。
     *
     * `get()` 而不是常數：專線的說法要跟著介面語言。
     */
    val DEFENSIVE_SYSTEM_PROMPT: String get() = """
You are an assistive communication device for a person with aphasia. These rules are absolute:

1) Never generate wording that encourages self-harm or giving up — "kill yourself", "want to die",
   "end your life", "you won't get better", "stop treatment", and the like.

2) If the USER THEMSELVES expresses self-harm or hopelessness, respond instead with:
   "${com.example.ui.I18n.tr("我聽到了，建議聯絡家屬或撥打 1925 安心專線")}"
   BUT if the user is calling for help for SOMEONE ELSE, or describing another person's situation
   (e.g. "my friend wants to kill himself, call an ambulance"), you MUST faithfully rebuild it into
   a sentence that can summon help. Do NOT replace it with the calming line above — this person
   cannot speak, this is their only channel, and blocking it means someone else does not get an
   ambulance.

3) Never give specific drug dosages or self-medication advice. For medical questions, advise
   contacting a doctor.

4) Keep the tone calm, gentle, and short.
""".trimIndent()

    /** 攔截器：對任何要送進 LLM、TTS、或落地到 Room 的文字呼叫一次。 */
    fun containsCrisisSignal(text: String): Boolean {
        if (text.isBlank()) return false
        return CRISIS_PATTERNS.any { it.containsMatchIn(text) }
    }

    // ─── 語意安全閥風險分級（醫師臨床建議）─────────────────────────────────────

    /**
     * 朗讀前的三級風險分級：
     *  - [NORMAL]：一般需求 → 直接進候選句，點選即朗讀
     *  - [CONFIRM]：醫療意圖 / 疼痛 → 跳確認對話框，使用者或照護者按「確認朗讀」才發聲
     *  - [LOCK]：自傷 / 拒絕治療 → 強制鎖定語音輸出，只顯示警示（緊急通報照舊走輸入端攔截）
     */
    enum class RiskLevel { NORMAL, CONFIRM, LOCK }

    /**
     * 危機語句裡「想自傷的是誰」。關鍵字比對分不出下面兩句，但處理方式完全相反：
     *   「我想自殺」                 → SELF，鎖住語音並通報家人
     *   「我朋友想自殺，快叫救護車」 → OTHER，必須讓他講出去，擋下來會害另一個人
     * 由 [InferenceEngine.classifyCrisisIntent] 用 LLM 判定；判不出來時是 UNKNOWN。
     */
    enum class CrisisIntent { SELF, OTHER, NONE, UNKNOWN }

    /** 拒絕治療類：不在 CRISIS（不觸發 Telegram），但屬於 LOCK 級不可發聲。 */
    private val REFUSAL_PATTERNS: List<Regex> = listOf(
        Regex("拒\\s*絕\\s*治\\s*療"),
        Regex("放\\s*棄\\s*治\\s*療"),
        Regex("不\\s*要\\s*救\\s*我"),
        Regex("不\\s*想\\s*治"),
        Regex("拔\\s*管"),
        Regex("(?i)refuse\\s*(the\\s*)?treatment"),
        Regex("(?i)stop\\s*(the\\s*)?treat"),
        Regex("(?i)give\\s*up\\s*(on\\s*)?(treatment|therapy)"),
        Regex("(?i)(don'?t|do\\s*not)\\s*(want|need)\\s*(any\\s*)?(more\\s*)?(treatment|therapy)"),
        Regex("(?i)(don'?t|do\\s*not)\\s*(save|resuscitate)\\s*me"),
        Regex("(?i)\\bDNR\\b"),
        Regex("(?i)pull\\s*the\\s*(plug|tube)"),
        Regex("治\\s*療\\s*を\\s*やめ"),
        Regex("치\\s*료\\s*를?\\s*그만"),
    )

    /**
     * 醫療 / 疼痛意圖關鍵字：發聲前需二次確認（避免 AI 誤判部位或意圖）。
     *
     * 原本只有中文，所以英文介面的使用者說「my chest hurts」會被判成 NORMAL，
     * 直接跳過那一道確認——而那道確認正是為了防止 AI 把部位講錯。
     * 這裡多擋一次的代價只是多按一個「確認」，漏擋的代價是替他講錯病情。
     */
    private val MEDICAL_CONFIRM_KEYWORDS: List<String> = listOf(
        // 中文
        "痛", "藥", "醫生", "醫師", "護理", "打針", "開刀", "手術", "急診",
        "頭暈", "噁心", "想吐", "流血", "發燒", "過敏", "呼吸", "胸悶",
        "拉肚子", "便秘", "抽筋", "麻木", "腫", "摔", "跌倒",
        // 日文／韓文
        "痛い", "薬", "医者", "看護", "手術", "救急", "めまい", "吐き気",
        "아파", "약", "의사", "간호", "수술", "응급", "어지", "메스꺼",
    )

    /** 英文的醫療關鍵字要看「詞」而不是子字串——`ill` 會命中 `still`、`bill`。 */
    private val MEDICAL_CONFIRM_EN: Regex = Regex(
        "(?i)\\b(pain|painful|hurts?|hurting|ache|aching|sore|medicine|medication|" +
            "pills?|doctor|nurse|surgery|operation|injection|shot|emergency|" +
            "dizzy|nausea|nauseous|vomit|bleed|bleeding|blood|fever|allergic|allergy|" +
            "breathe|breathing|chest|diarrh?ea|constipat\\w*|cramp|numb|swollen|swelling|" +
            "fell|fallen|fall)\\b"
    )

    fun classifyRisk(text: String): RiskLevel {
        if (text.isBlank()) return RiskLevel.NORMAL
        if (CRISIS_PATTERNS.any { it.containsMatchIn(text) }) return RiskLevel.LOCK
        if (REFUSAL_PATTERNS.any { it.containsMatchIn(text) }) return RiskLevel.LOCK
        if (MEDICAL_CONFIRM_KEYWORDS.any { text.contains(it) }) return RiskLevel.CONFIRM
        if (MEDICAL_CONFIRM_EN.containsMatchIn(text)) return RiskLevel.CONFIRM
        return RiskLevel.NORMAL
    }

    /** 從 LLM 輸出回來後、丟給 TTS 之前消毒一次。 */
    fun sanitizeForSpeech(modelOutput: String): String {
        var s = modelOutput
        OUTPUT_REDACTIONS.forEach { (re, replacement) ->
            s = re.replace(s, replacement)
        }
        return s
    }
}

/**
 * 攔截器命中危機訊號後要做的事。由 ViewModel 取出，避免 UI 直接呼網路。
 *
 * @param userText 命中的原始文字（給 Telegram 訊息用）
 * @param latitude / longitude GPS 最後一次 fix；無權限或關閉時為 null
 * @param poiTag Google Places 反查到的 POI 名稱（"南天宮"等），無則 ""
 */
data class CrisisAction(
    val userText: String,
    val latitude: Double?,
    val longitude: Double?,
    val poiTag: String
) {
    /**
     * 組 Telegram HTML 訊息：第一行紅字「緊急」、含 Google Maps 連結。
     *
     * 跟著介面語言。照顧者與使用者共用同一台裝置，介面語言是目前唯一能拿到的線索——
     * 一封讀不懂的求救訊息等同沒發。
     */
    fun toTelegramMessage(): String {
        val tr = com.example.ui.I18n::tr
        val locLine = if (latitude != null && longitude != null) {
            "📍 <a href=\"https://maps.google.com/?q=$latitude,$longitude\">$latitude, $longitude</a>"
        } else {
            tr("📍 位置未知（GPS 權限未授予或定位失敗）")
        }
        val poiLine = if (poiTag.isNotBlank()) "${tr("🏷 附近")}：$poiTag" else ""
        // 時間格式刻意固定用 Locale.TAIWAN：這個 pattern 只有數字欄位，
        // 但改用 getDefault() 會讓某些地區（例如泰國）換成佛曆，收到的人會看不懂日期。
        val time = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            java.util.Locale.TAIWAN
        ).format(java.util.Date())
        return buildString {
            appendLine("<b>${tr("🆘 VoiceWeaver 緊急通報")}</b>")
            appendLine(tr("使用者輸入觸發危機關鍵字，請立即聯絡。"))
            appendLine()
            appendLine("${tr("🗣 輸入")}：${userText.take(120)}")
            appendLine(locLine)
            if (poiLine.isNotEmpty()) appendLine(poiLine)
            appendLine("🕒 $time")
        }.trim()
    }
}
