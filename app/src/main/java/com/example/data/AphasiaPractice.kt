package com.example.data

/**
 * 復健題庫的四個等級（中英日韓共用同一份分級，譯文見 [AphasiaPracticeI18n]）。
 *
 * 由「單詞 → 詞組 → 短句 → 情境長句」逐級加長：失語症復健的常規順序是先把
 * 單一詞彙的命名與構音找回來，再往語法與語境走。分四級而不是三級，是因為
 * 單詞直接跳到完整需求句的落差太大——中間那一級（招呼語、兩三個字的需求）
 * 是很多中度患者每天真正用得到、也最容易建立信心的。
 *
 * displayName 是繁中原文，顯示時要走 I18n.tr()。
 */
enum class PracticeLevel(val displayName: String, val previewCount: Int) {
    Words("第 1 級・單詞命名", 14),
    Phrases("第 2 級・詞組與招呼語", 10),
    Short("第 3 級・日常短句", 8),
    Situational("第 4 級・情境長句", 6),
}

/**
 * 失語症復健練習語料（繁體中文）。
 *
 * 內容取自「漢語標準失語症檢查表（CRRCAE）」常用之復述／命名範式，以及失語症
 * 語音研究文獻所列的標準復述名詞、動詞與情境句（屬臨床通用測驗內容，非任何專利
 * 之獨創）。用作復健的跟讀題庫與離線時 AI 推薦的備援，難度分級、自適應。
 *
 * 難度四級（對應復健的平均分自適應，見 [PracticeLevel] / [levelFor]）：
 *  - 第 1 級（avg < 45）：高頻單／雙字（命名 + 構音）。
 *  - 第 2 級（45–59）：詞組與招呼語。
 *  - 第 3 級（60–79）：日常需求短句。
 *  - 第 4 級（≥ 80）：完整情境句（語法 + 語境）。
 *
 * 公開 API（[easyWords] / [mediumSentences] / [hardSentences] / [pick]）維持不變，
 * UI 與備援不需改動。
 */
object AphasiaPractice {

    // ─── 命名：名詞（日常物品、食物、身體、人物、地點）──────────────────────
    val nouns: List<String> = listOf(
        // 日常用品
        "水", "杯子", "碗", "筷子", "湯匙", "毛巾", "牙刷", "牙膏", "肥皂", "梳子",
        "鏡子", "雨傘", "鑰匙", "錢包", "眼鏡", "手機", "電話", "電視", "電燈", "時鐘",
        "鐘錶", "椅子", "桌子", "床", "棉被", "枕頭", "門", "窗戶", "冰箱", "冷氣",
        "電鍋", "鍋子", "盤子", "剪刀", "紙", "筆", "書", "報紙", "袋子", "鞋子",
        "襪子", "帽子", "衣服", "褲子", "手錶", "雨衣",
        // 食物
        "飯", "麵", "粥", "麵包", "饅頭", "雞蛋", "牛奶", "豆漿", "茶", "咖啡",
        "蘋果", "香蕉", "橘子", "西瓜", "葡萄", "番茄", "青菜", "魚", "肉", "湯",
        // 身體部位
        "頭", "頭髮", "眼睛", "耳朵", "鼻子", "嘴巴", "牙齒", "舌頭", "手", "手指",
        "腳", "肚子", "胸口", "背", "腰", "膝蓋",
        // 人物 / 地點
        "醫生", "護士", "家人", "媽媽", "爸爸", "兒子", "女兒", "孫子", "朋友",
        "醫院", "家", "房間", "廁所", "廚房", "客廳", "公園", "車站", "市場",
        // 其他（含標準復述題）
        "自行車", "火", "加油", "錢", "藥", "口罩", "輪椅", "拐杖", "血壓計"
    )

    // ─── 命名：動詞（動作）──────────────────────────────────────────────
    val verbs: List<String> = listOf(
        "坐", "站", "走", "跑", "躺", "睡", "吃", "喝", "吃飯", "喝水",
        "穿", "脫", "洗", "洗手", "洗臉", "刷牙", "拿", "放", "開", "關",
        "看", "聽", "說", "寫", "讀", "讀書", "笑", "哭", "走路", "運動",
        "鍛鍊", "敲", "買", "賣", "找", "等", "幫忙", "休息", "起床", "睡覺"
    )

    // ─── 社交 / 高頻短語 ────────────────────────────────────────────────
    val social: List<String> = listOf(
        "你好", "謝謝", "對不起", "沒關係", "好", "不要", "要", "是", "不是",
        "請", "再見", "幫忙", "我會痛", "我不舒服", "我餓了", "我渴了"
    )

    /** 易級：名詞 + 動詞 + 社交（合併池）。 */
    val easyWords: List<String> = nouns + verbs + social

    /**
     * 第 2 級：詞組與招呼語（2–4 字的固定說法）。
     * 每一句都要在 [AphasiaPracticeI18n] 有英日韓譯文，四種語言才都練得到。
     */
    val phrases: List<String> = listOf(
        "你好", "謝謝", "對不起", "沒關係", "再見", "早安",
        "晚安", "好的", "不要", "我餓了", "我渴了", "我不舒服"
    )

    /** 某一級的完整題目（繁中原文；顯示／比對前用 [AphasiaPracticeI18n.target] 換語言）。 */
    fun levelItems(level: PracticeLevel): List<String> = when (level) {
        PracticeLevel.Words -> nouns + verbs
        PracticeLevel.Phrases -> phrases
        PracticeLevel.Short -> mediumSentences
        PracticeLevel.Situational -> hardSentences
    }

    /** 畫面上每級先露出的幾題。第 1 級名詞動詞各取一些，不然整排都是名詞。 */
    fun levelPreview(level: PracticeLevel): List<String> = when (level) {
        PracticeLevel.Words -> nouns.take(8) + verbs.take(level.previewCount - 8)
        else -> levelItems(level).take(level.previewCount)
    }

    /** 依平均分決定建議練哪一級（離線備援出題用）。 */
    fun levelFor(avgScore: Double): PracticeLevel = when {
        avgScore < 45 -> PracticeLevel.Words
        avgScore < 60 -> PracticeLevel.Phrases
        avgScore < 80 -> PracticeLevel.Short
        else -> PracticeLevel.Situational
    }

    /** 中級：日常需求短句（5–10 字）。 */
    val mediumSentences: List<String> = listOf(
        "我想喝水", "我要吃飯", "我想休息", "我想睡覺", "我要上廁所",
        "請給我藥", "我會痛", "我想回家", "請等一下", "我聽不懂",
        "請再說一次", "我想打電話", "幫我開門", "幫我關燈", "我想出去走走",
        "我肚子餓了", "我覺得很累", "我要看醫生", "請幫我倒水", "我想看電視",
        "現在幾點了", "今天星期幾", "我要洗澡", "幫我拿眼鏡", "我想坐起來"
    )

    /** 難級：完整情境句（語法 + 語境，含標準失語症檢查表常見句）。 */
    val hardSentences: List<String> = listOf(
        // 標準檢查表 / 文獻常見情境句（繁體）
        "媽媽準備給男孩講故事",
        "男孩在洗頭",
        "一個學生邊讀邊寫",
        "孩子們堆了一個大雪人",
        "門開了",
        "男孩子在湖上划船",
        "兩個孩子在討論書上的圖畫",
        "男孩付錢買玩具車",
        "老人拄著拐杖獨自過馬路",
        "小妹妹的成績被大學錄取了",
        // 日常情境表達
        "可以幫我打電話給家人嗎",
        "我覺得有點不舒服，需要休息一下",
        "請問現在幾點，我該吃藥了",
        "今天天氣很好，我想去公園走走",
        "麻煩你幫我把窗戶打開好嗎",
        "晚餐我想吃一碗熱湯麵",
        "我想跟醫生說我最近睡不好",
        "請幫我把藥和開水拿過來"
    )

    // ─── 英文語料（AAC 核心詞彙 + 失語症日常功能性溝通句，通用標準內容）──────
    /** 英文核心高頻詞（AAC core words：代名詞/動詞/方位/否定…）。 */
    val enCore: List<String> = listOf(
        "I", "you", "want", "need", "more", "stop", "go", "help", "yes", "no",
        "like", "have", "do", "get", "give", "come", "see", "look", "here", "this",
        "that", "my", "please", "thank you", "finish", "again", "not", "can",
        "where", "what", "who", "when", "why", "how", "good", "bad", "now"
    )

    /** 英文命名名詞。 */
    val enNouns: List<String> = listOf(
        "water", "food", "drink", "bathroom", "toilet", "bed", "phone", "medicine",
        "doctor", "nurse", "home", "family", "mom", "dad", "pain", "chair", "table",
        "door", "window", "light", "TV", "glasses", "book", "money", "key", "shoes",
        "clothes", "hand", "head", "eye", "ear", "mouth", "tea", "coffee", "bread",
        "rice", "apple", "soup", "wheelchair", "blanket", "pillow", "towel"
    )

    /** 英文動作動詞。 */
    val enVerbs: List<String> = listOf(
        "eat", "drink", "sleep", "sit", "stand", "walk", "wash", "open", "close",
        "take", "give", "call", "rest", "read", "write", "listen", "wait", "wake up"
    )

    /** 英文日常需求短句。 */
    val enMediumSentences: List<String> = listOf(
        "I want water.", "I'm hungry.", "I need to rest.", "I want to sleep.",
        "I need the bathroom.", "Please give me my medicine.", "I'm in pain.",
        "I want to go home.", "Please wait a moment.", "I don't understand.",
        "Please say it again.", "I want to make a call.", "Please open the door.",
        "I'm tired.", "I want to see the doctor.", "Please pour me some water.",
        "What time is it?", "Please help me.", "I want to sit up.", "Please turn on the light."
    )

    /** 英文完整情境句。 */
    val enHardSentences: List<String> = listOf(
        "Can you call my family for me?",
        "I feel a little unwell and need to rest.",
        "What time is it? I should take my medicine.",
        "It's a nice day; I'd like to go to the park.",
        "Could you open the window for me?",
        "I'd like a bowl of hot noodle soup for dinner.",
        "I want to tell the doctor I haven't been sleeping well.",
        "Please bring me my medicine and some water."
    )

    // ─── 日文語料（AAC 核心 + 失語症日常功能性溝通句）────────────────────────
    val jaNouns: List<String> = listOf(
        "水", "ご飯", "トイレ", "お手洗い", "薬", "医者", "看護師", "家族", "家", "部屋",
        "電話", "ベッド", "椅子", "机", "ドア", "窓", "電気", "テレビ", "眼鏡", "お茶",
        "パン", "りんご", "手", "頭", "目", "口", "お金", "車椅子", "毛布", "枕", "タオル"
    )
    val jaVerbs: List<String> = listOf(
        "食べる", "飲む", "寝る", "座る", "立つ", "歩く", "洗う", "開ける", "閉める",
        "取る", "休む", "読む", "書く", "待つ", "行く", "来る", "起きる"
    )
    val jaMediumSentences: List<String> = listOf(
        "水が飲みたいです。", "お腹がすきました。", "休みたいです。", "寝たいです。",
        "トイレに行きたいです。", "薬をください。", "痛いです。", "家に帰りたいです。",
        "少し待ってください。", "わかりません。", "もう一度言ってください。", "電話をかけたいです。",
        "ドアを開けてください。", "疲れました。", "医者に診てもらいたいです。", "水を入れてください。",
        "今何時ですか。", "助けてください。", "テレビが見たいです。", "電気をつけてください。"
    )
    val jaHardSentences: List<String> = listOf(
        "家族に電話してもらえますか。",
        "少し気分が悪いので休みたいです。",
        "今何時ですか、薬を飲む時間です。",
        "天気がいいので公園に行きたいです。",
        "窓を開けてもらえますか。",
        "夕食に温かいラーメンが食べたいです。",
        "最近よく眠れないと医者に伝えたいです。",
        "薬と水を持ってきてください。"
    )

    // ─── 韓文語料（AAC 核心 + 失語症日常功能性溝通句）────────────────────────
    val koNouns: List<String> = listOf(
        "물", "밥", "화장실", "약", "의사", "간호사", "가족", "집", "방", "전화",
        "침대", "의자", "책상", "문", "창문", "불", "텔레비전", "안경", "차", "빵",
        "사과", "손", "머리", "눈", "입", "돈", "휠체어", "이불", "베개", "수건"
    )
    val koVerbs: List<String> = listOf(
        "먹다", "마시다", "자다", "앉다", "서다", "걷다", "씻다", "열다", "닫다",
        "잡다", "쉬다", "읽다", "쓰다", "기다리다", "가다", "오다", "일어나다"
    )
    val koMediumSentences: List<String> = listOf(
        "물 마시고 싶어요.", "배고파요.", "쉬고 싶어요.", "자고 싶어요.",
        "화장실 가고 싶어요.", "약 주세요.", "아파요.", "집에 가고 싶어요.",
        "잠깐 기다려 주세요.", "모르겠어요.", "다시 말해 주세요.", "전화하고 싶어요.",
        "문 열어 주세요.", "피곤해요.", "의사 선생님을 보고 싶어요.", "물 좀 따라 주세요.",
        "지금 몇 시예요?", "도와주세요.", "텔레비전 보고 싶어요.", "불 켜 주세요."
    )
    val koHardSentences: List<String> = listOf(
        "가족에게 전화해 주실 수 있어요?",
        "몸이 좀 안 좋아서 쉬고 싶어요.",
        "지금 몇 시예요? 약 먹을 시간이에요.",
        "날씨가 좋으니 공원에 가고 싶어요.",
        "창문 좀 열어 주시겠어요?",
        "저녁으로 따뜻한 국수를 먹고 싶어요.",
        "요즘 잠을 잘 못 잔다고 의사에게 말하고 싶어요.",
        "약과 물 좀 가져다 주세요."
    )

    /**
     * 使用者匯入的自訂語料（句子/詞），由 ViewModel 從 customCorpus 同步進來。
     * 一起納入重組的檢索候選，讓「我的語料」也能讓重組更準。
     */
    @Volatile
    var userPhrases: List<String> = emptyList()

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF
    private fun isHangul(c: Char): Boolean = c.code in 0xAC00..0xD7A3
    private fun isKana(c: Char): Boolean = c.code in 0x3040..0x30FF
    private fun isJaChar(c: Char): Boolean = isKana(c) || isCjk(c)

    private val enStop = setOf(
        "i", "you", "the", "a", "an", "to", "me", "my", "is", "am", "are", "of",
        "and", "for", "it", "please", "do", "be", "in", "on", "at", "this", "that"
    )

    /**
     * 檢索式重組輔助：用碎詞（＋周邊物件）對語料做重疊比對，回傳最相近的 [k] 句
     * 「常用表達」當作重組 LLM 的強候選提示。中文用漢字重疊、英文用實詞詞彙重疊；
     * 需 [minShared] 個共享單位才算命中，避免亂貼誤導；無命中回空。
     */
    fun reconstructionCandidates(
        fragments: String,
        vision: List<String> = emptyList(),
        k: Int = 4,
        minShared: Int = 2
    ): List<String> {
        val text = fragments + " " + vision.joinToString(" ")
        val hangul = text.count(::isHangul)
        val kana = text.count(::isKana)
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val cjk = text.count(::isCjk)
        return when {
            // 韓文：諺文音節重疊
            hangul >= 2 -> {
                val q = text.filter(::isHangul).toSet()
                rank(koMediumSentences + koHardSentences + userPhrases, k, minShared) { s ->
                    s.filter(::isHangul).toSet().count { it in q }
                }
            }
            // 日文：有假名 → 假名＋漢字字元重疊
            kana > 0 -> {
                val q = text.filter(::isJaChar).toSet()
                if (q.size < 2) emptyList()
                else rank(jaMediumSentences + jaHardSentences + userPhrases, k, minShared) { s ->
                    s.filter(::isJaChar).toSet().count { it in q }
                }
            }
            // 英文：實詞（去停用詞）詞彙重疊
            latin > cjk -> {
                val q = enTokens(text)
                if (q.size < 2) emptyList()
                else rank(enMediumSentences + enHardSentences + userPhrases, k, minShared) { s ->
                    enTokens(s).count { it in q }
                }
            }
            // 中文：漢字重疊
            else -> {
                val q = text.filter(::isCjk).toSet()
                if (q.size < 2) emptyList()
                else rank(mediumSentences + hardSentences + userPhrases, k, minShared) { s ->
                    s.filter(::isCjk).toSet().count { it in q }
                }
            }
        }
    }

    private fun enTokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 2 && it !in enStop }.toSet()

    private fun rank(pool: List<String>, k: Int, minShared: Int, score: (String) -> Int): List<String> =
        pool.asSequence()
            .map { it to score(it) }
            .filter { it.second >= minShared }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(k)
            .toList()

    /**
     * 依平均分挑選 [count] 句練習題（難級首句帶入目前場景），並排除最近練過的。
     * 隨機抽樣以增加變化（離線備援，變化比可重現更重要）。
     */
    fun pick(avgScore: Double, location: String, recent: List<String> = emptyList(), count: Int = 3): List<String> {
        val pool = when (levelFor(avgScore)) {
            PracticeLevel.Words -> easyWords
            PracticeLevel.Phrases -> phrases
            PracticeLevel.Short -> mediumSentences
            PracticeLevel.Situational -> hardSentences.map { s ->
                if (s.startsWith("我覺得")) "在${location.ifBlank { "這裡" }}，$s" else s
            }
        }
        val fresh = pool.filterNot { it in recent }
        return (fresh.ifEmpty { pool }).shuffled().take(count)
    }
}
