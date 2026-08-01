package com.example.data

import com.example.ui.I18n

/**
 * 復健題庫的多語版本。同一份譯文在兩種模式下用途不同，取決於「辨識器認不認得非中文」。
 *
 * ## 兩種模式
 * [AphasiaPractice] 裡的字是**要唸出來、而且會被語音辨識評分**的練習目標：
 * 點題目 → 設成 rehabTarget → 病人唸 → 辨識 → scoreRehab(target, recognized) 比對。
 *
 * - **雲端 Whisper 可用**（多語）→ [target]：整題換成介面語言，畫面上的字＝拿去比對的字。
 *   英文介面的使用者練英文句子，唸的跟比對的是同一串，分數才有意義。
 * - **只有裝置端 Vosk**（`vosk-model-small-cn-0.22`，中文單一模型）→ [gloss]：
 *   題目一律保留中文，只在下面補一行語意註解給看不懂中文的照護者。
 *   這時若把「水」換成「Water」，病人看著英文唸、中文辨識器聽不懂，分數永遠是 0。
 *
 * 題庫本身是「漢語標準失語症檢查表（CRRCAE）」的臨床題，譯文只是同義對照，
 * 不是另一套經過標準化的英文題庫——臨床解讀時要知道這件事。
 */
object AphasiaPracticeI18n {

    /** 目前介面語言的譯文；中文介面或查無資料時回 null。 */
    private fun translate(zh: String): String? {
        if (I18n.lang == AppLanguage.ZhTW) return null
        val row = M[zh] ?: return null
        val i = when (I18n.lang) {
            AppLanguage.En -> 0
            AppLanguage.Ja -> 1
            AppLanguage.Ko -> 2
            else -> return null
        }
        return row.getOrNull(i)?.takeIf { it.isNotBlank() }
    }

    /** 目前介面語言下的語意註解；中文介面或查無資料時回 null（呼叫端就不顯示）。 */
    fun gloss(zh: String): String? = translate(zh)

    /**
     * 拿去練習與比對的題目本身。
     *
     * @param multilingualStt 辨識器認不認得非中文（＝雲端 Whisper 可不可用）。
     *        false 時一律回中文原文——這是唯一辨識得出來的語言，換掉只會得 0 分。
     */
    fun target(zh: String, multilingualStt: Boolean): String =
        if (multilingualStt) translate(zh) ?: zh else zh

    // 鍵 = 練習題原文（繁中）；值 = [en, ja, ko]
    private val M: Map<String, Array<String>> = mapOf(
        // ── 名詞：日常用品 ──
        "水" to arrayOf("water", "水", "물"),
        "杯子" to arrayOf("cup", "コップ", "컵"),
        "碗" to arrayOf("bowl", "お椀", "그릇"),
        "筷子" to arrayOf("chopsticks", "箸", "젓가락"),
        "湯匙" to arrayOf("spoon", "スプーン", "숟가락"),
        "毛巾" to arrayOf("towel", "タオル", "수건"),
        "牙刷" to arrayOf("toothbrush", "歯ブラシ", "칫솔"),
        "牙膏" to arrayOf("toothpaste", "歯磨き粉", "치약"),
        "肥皂" to arrayOf("soap", "石鹸", "비누"),
        "梳子" to arrayOf("comb", "櫛", "빗"),
        "鏡子" to arrayOf("mirror", "鏡", "거울"),
        "雨傘" to arrayOf("umbrella", "傘", "우산"),
        "鑰匙" to arrayOf("key", "鍵", "열쇠"),
        "錢包" to arrayOf("wallet", "財布", "지갑"),
        "眼鏡" to arrayOf("glasses", "メガネ", "안경"),
        "手機" to arrayOf("mobile phone", "スマホ", "휴대폰"),
        "電話" to arrayOf("telephone", "電話", "전화"),
        "電視" to arrayOf("television", "テレビ", "텔레비전"),
        "電燈" to arrayOf("lamp", "電灯", "전등"),
        "時鐘" to arrayOf("clock", "時計", "시계"),
        "鐘錶" to arrayOf("clock/watch", "時計類", "시계"),
        "椅子" to arrayOf("chair", "椅子", "의자"),
        "桌子" to arrayOf("table", "机", "탁자"),
        "床" to arrayOf("bed", "ベッド", "침대"),
        "棉被" to arrayOf("quilt", "布団", "이불"),
        "枕頭" to arrayOf("pillow", "枕", "베개"),
        "門" to arrayOf("door", "ドア", "문"),
        "窗戶" to arrayOf("window", "窓", "창문"),
        "冰箱" to arrayOf("fridge", "冷蔵庫", "냉장고"),
        "冷氣" to arrayOf("air conditioner", "エアコン", "에어컨"),
        "電鍋" to arrayOf("rice cooker", "炊飯器", "전기밥솥"),
        "鍋子" to arrayOf("pot", "鍋", "냄비"),
        "盤子" to arrayOf("plate", "皿", "접시"),
        "剪刀" to arrayOf("scissors", "はさみ", "가위"),
        "紙" to arrayOf("paper", "紙", "종이"),
        "筆" to arrayOf("pen", "ペン", "펜"),
        "書" to arrayOf("book", "本", "책"),
        "報紙" to arrayOf("newspaper", "新聞", "신문"),
        "袋子" to arrayOf("bag", "袋", "봉지"),
        "鞋子" to arrayOf("shoes", "靴", "신발"),
        "襪子" to arrayOf("socks", "靴下", "양말"),
        "帽子" to arrayOf("hat", "帽子", "모자"),
        "衣服" to arrayOf("clothes", "服", "옷"),
        "褲子" to arrayOf("trousers", "ズボン", "바지"),
        "手錶" to arrayOf("watch", "腕時計", "손목시계"),
        "雨衣" to arrayOf("raincoat", "レインコート", "우비"),

        // ── 名詞：食物 ──
        "飯" to arrayOf("rice", "ごはん", "밥"),
        "麵" to arrayOf("noodles", "麺", "국수"),
        "粥" to arrayOf("congee", "お粥", "죽"),
        "麵包" to arrayOf("bread", "パン", "빵"),
        "饅頭" to arrayOf("steamed bun", "マントウ", "찐빵"),
        "雞蛋" to arrayOf("egg", "卵", "달걀"),
        "牛奶" to arrayOf("milk", "牛乳", "우유"),
        "豆漿" to arrayOf("soy milk", "豆乳", "두유"),
        "茶" to arrayOf("tea", "お茶", "차"),
        "咖啡" to arrayOf("coffee", "コーヒー", "커피"),
        "蘋果" to arrayOf("apple", "リンゴ", "사과"),
        "香蕉" to arrayOf("banana", "バナナ", "바나나"),
        "橘子" to arrayOf("orange", "みかん", "귤"),
        "西瓜" to arrayOf("watermelon", "スイカ", "수박"),
        "葡萄" to arrayOf("grapes", "ぶどう", "포도"),
        "番茄" to arrayOf("tomato", "トマト", "토마토"),
        "青菜" to arrayOf("greens", "青菜", "채소"),
        "魚" to arrayOf("fish", "魚", "생선"),
        "肉" to arrayOf("meat", "肉", "고기"),
        "湯" to arrayOf("soup", "スープ", "국"),

        // ── 名詞：身體部位 ──
        "頭" to arrayOf("head", "頭", "머리"),
        "頭髮" to arrayOf("hair", "髪", "머리카락"),
        "眼睛" to arrayOf("eyes", "目", "눈"),
        "耳朵" to arrayOf("ears", "耳", "귀"),
        "鼻子" to arrayOf("nose", "鼻", "코"),
        "嘴巴" to arrayOf("mouth", "口", "입"),
        "牙齒" to arrayOf("teeth", "歯", "이"),
        "舌頭" to arrayOf("tongue", "舌", "혀"),
        "手" to arrayOf("hand", "手", "손"),
        "手指" to arrayOf("finger", "指", "손가락"),
        "腳" to arrayOf("foot", "足", "발"),
        "肚子" to arrayOf("belly", "お腹", "배"),
        "胸口" to arrayOf("chest", "胸", "가슴"),
        "背" to arrayOf("back", "背中", "등"),
        "腰" to arrayOf("waist", "腰", "허리"),
        "膝蓋" to arrayOf("knee", "膝", "무릎"),

        // ── 名詞：人物／地點 ──
        "醫生" to arrayOf("doctor", "医師", "의사"),
        "護士" to arrayOf("nurse", "看護師", "간호사"),
        "家人" to arrayOf("family", "家族", "가족"),
        "媽媽" to arrayOf("mother", "お母さん", "엄마"),
        "爸爸" to arrayOf("father", "お父さん", "아빠"),
        "兒子" to arrayOf("son", "息子", "아들"),
        "女兒" to arrayOf("daughter", "娘", "딸"),
        "孫子" to arrayOf("grandchild", "孫", "손주"),
        "朋友" to arrayOf("friend", "友だち", "친구"),
        "醫院" to arrayOf("hospital", "病院", "병원"),
        "家" to arrayOf("home", "家", "집"),
        "房間" to arrayOf("room", "部屋", "방"),
        "廁所" to arrayOf("toilet", "トイレ", "화장실"),
        "廚房" to arrayOf("kitchen", "台所", "부엌"),
        "客廳" to arrayOf("living room", "居間", "거실"),
        "公園" to arrayOf("park", "公園", "공원"),
        "車站" to arrayOf("station", "駅", "역"),
        "市場" to arrayOf("market", "market", "시장"),

        // ── 名詞：其他 ──
        "自行車" to arrayOf("bicycle", "自転車", "자전거"),
        "火" to arrayOf("fire", "火", "불"),
        "加油" to arrayOf("refuel / cheer up", "給油・がんばれ", "주유·힘내"),
        "錢" to arrayOf("money", "お金", "돈"),
        "藥" to arrayOf("medicine", "薬", "약"),
        "口罩" to arrayOf("face mask", "マスク", "마스크"),
        "輪椅" to arrayOf("wheelchair", "車いす", "휠체어"),
        "拐杖" to arrayOf("walking stick", "杖", "지팡이"),
        "血壓計" to arrayOf("blood pressure monitor", "血圧計", "혈압계"),

        // ── 動詞 ──
        "坐" to arrayOf("sit", "座る", "앉다"),
        "站" to arrayOf("stand", "立つ", "서다"),
        "走" to arrayOf("go / walk", "歩く", "가다"),
        "跑" to arrayOf("run", "走る", "달리다"),
        "躺" to arrayOf("lie down", "横になる", "눕다"),
        "睡" to arrayOf("sleep", "寝る", "자다"),
        "吃" to arrayOf("eat", "食べる", "먹다"),
        "喝" to arrayOf("drink", "飲む", "마시다"),
        "吃飯" to arrayOf("eat a meal", "食事する", "밥 먹다"),
        "喝水" to arrayOf("drink water", "水を飲む", "물 마시다"),
        "穿" to arrayOf("put on", "着る", "입다"),
        "脫" to arrayOf("take off", "脱ぐ", "벗다"),
        "洗" to arrayOf("wash", "洗う", "씻다"),
        "洗手" to arrayOf("wash hands", "手を洗う", "손 씻다"),
        "洗臉" to arrayOf("wash face", "顔を洗う", "세수하다"),
        "刷牙" to arrayOf("brush teeth", "歯を磨く", "양치하다"),
        "拿" to arrayOf("take", "取る", "잡다"),
        "放" to arrayOf("put down", "置く", "놓다"),
        "開" to arrayOf("open / turn on", "開ける・つける", "열다·켜다"),
        "關" to arrayOf("close / turn off", "閉める・消す", "닫다·끄다"),
        "看" to arrayOf("look", "見る", "보다"),
        "聽" to arrayOf("listen", "聞く", "듣다"),
        "說" to arrayOf("speak", "話す", "말하다"),
        "寫" to arrayOf("write", "書く", "쓰다"),
        "讀" to arrayOf("read", "読む", "읽다"),
        "讀書" to arrayOf("study", "勉強する", "공부하다"),
        "笑" to arrayOf("laugh", "笑う", "웃다"),
        "哭" to arrayOf("cry", "泣く", "울다"),
        "走路" to arrayOf("walk", "歩く", "걷다"),
        "運動" to arrayOf("exercise", "運動する", "운동하다"),
        "鍛鍊" to arrayOf("work out", "鍛える", "단련하다"),
        "敲" to arrayOf("knock", "叩く", "두드리다"),
        "買" to arrayOf("buy", "買う", "사다"),
        "賣" to arrayOf("sell", "売る", "팔다"),
        "找" to arrayOf("look for", "探す", "찾다"),
        "等" to arrayOf("wait", "待つ", "기다리다"),
        "幫忙" to arrayOf("help", "手伝う", "돕다"),
        "休息" to arrayOf("rest", "休む", "쉬다"),
        "起床" to arrayOf("get up", "起きる", "일어나다"),
        "睡覺" to arrayOf("go to sleep", "寝る", "자다"),

        // ── 短句 ──
        "我想喝水" to arrayOf("I want to drink water", "水が飲みたい", "물 마시고 싶어요"),
        "我要吃飯" to arrayOf("I want to eat", "ご飯が食べたい", "밥 먹고 싶어요"),
        "我想休息" to arrayOf("I want to rest", "休みたい", "쉬고 싶어요"),
        "我想睡覺" to arrayOf("I want to sleep", "寝たい", "자고 싶어요"),
        "我要上廁所" to arrayOf("I need the toilet", "トイレに行きたい", "화장실 가고 싶어요"),
        "請給我藥" to arrayOf("Please give me my medicine", "薬をください", "약 주세요"),
        "我會痛" to arrayOf("It hurts", "痛い", "아파요"),
        "我想回家" to arrayOf("I want to go home", "家に帰りたい", "집에 가고 싶어요"),
        "請等一下" to arrayOf("Please wait a moment", "少し待ってください", "잠깐만 기다려 주세요"),
        "我聽不懂" to arrayOf("I don't understand", "聞き取れません", "못 알아듣겠어요"),
        "請再說一次" to arrayOf("Please say it again", "もう一度言ってください", "다시 말해 주세요"),
        "我想打電話" to arrayOf("I want to make a call", "電話をかけたい", "전화하고 싶어요"),
        "幫我開門" to arrayOf("Please open the door for me", "ドアを開けてください", "문 열어 주세요"),
        "幫我關燈" to arrayOf("Please turn off the light", "電気を消してください", "불 꺼 주세요"),
        "我想出去走走" to arrayOf("I want to go out for a walk", "散歩に出かけたい", "산책하러 나가고 싶어요"),
        "我肚子餓了" to arrayOf("I'm hungry", "お腹が空いた", "배고파요"),
        "我覺得很累" to arrayOf("I feel very tired", "とても疲れた", "너무 피곤해요"),
        "我要看醫生" to arrayOf("I need to see a doctor", "医師に診てほしい", "진료 받고 싶어요"),
        "請幫我倒水" to arrayOf("Please pour me some water", "水を注いでください", "물 좀 따라 주세요"),
        "我想看電視" to arrayOf("I want to watch TV", "テレビが見たい", "텔레비전 보고 싶어요"),
        "現在幾點了" to arrayOf("What time is it now?", "今何時ですか", "지금 몇 시예요?"),
        "今天星期幾" to arrayOf("What day is it today?", "今日は何曜日ですか", "오늘 무슨 요일이에요?"),
        "我要洗澡" to arrayOf("I want to take a bath", "お風呂に入りたい", "목욕하고 싶어요"),
        "幫我拿眼鏡" to arrayOf("Please pass me my glasses", "メガネを取ってください", "안경 좀 주세요"),
        "我想坐起來" to arrayOf("I want to sit up", "起き上がりたい", "일어나 앉고 싶어요"),

        // ── 情境句 ──
        "媽媽準備給男孩講故事" to arrayOf(
            "Mum is getting ready to tell the boy a story",
            "お母さんが男の子に物語を読んであげる準備をしている",
            "엄마가 남자아이에게 이야기를 들려주려 한다"
        ),
        "男孩在洗頭" to arrayOf("The boy is washing his hair", "男の子が髪を洗っている", "남자아이가 머리를 감고 있다"),
        "一個學生邊讀邊寫" to arrayOf(
            "A student is reading and writing at the same time",
            "ある学生が読みながら書いている",
            "한 학생이 읽으면서 쓰고 있다"
        ),
        "孩子們堆了一個大雪人" to arrayOf(
            "The children built a big snowman",
            "子どもたちが大きな雪だるまを作った",
            "아이들이 큰 눈사람을 만들었다"
        ),
        "門開了" to arrayOf("The door opened", "ドアが開いた", "문이 열렸다"),
        "男孩子在湖上划船" to arrayOf(
            "The boy is rowing a boat on the lake",
            "男の子が湖でボートを漕いでいる",
            "남자아이가 호수에서 배를 젓고 있다"
        ),
        "兩個孩子在討論書上的圖畫" to arrayOf(
            "Two children are discussing the pictures in the book",
            "二人の子どもが本の絵について話している",
            "두 아이가 책의 그림에 대해 이야기하고 있다"
        ),
        "男孩付錢買玩具車" to arrayOf(
            "The boy pays for a toy car",
            "男の子がお金を払っておもちゃの車を買う",
            "남자아이가 돈을 내고 장난감 차를 산다"
        ),
        "老人拄著拐杖獨自過馬路" to arrayOf(
            "An elderly man crosses the road alone with a walking stick",
            "お年寄りが杖をついて一人で道路を渡る",
            "노인이 지팡이를 짚고 혼자 길을 건넌다"
        ),
        "小妹妹的成績被大學錄取了" to arrayOf(
            "My little sister's grades got her into university",
            "妹の成績で大学に合格した",
            "여동생의 성적으로 대학에 합격했다"
        ),
        "可以幫我打電話給家人嗎" to arrayOf(
            "Could you call my family for me?",
            "家族に電話してもらえますか",
            "가족에게 전화해 주실 수 있나요?"
        ),
        "我覺得有點不舒服，需要休息一下" to arrayOf(
            "I feel a bit unwell and need to rest",
            "少し具合が悪いので休みたいです",
            "몸이 좀 안 좋아서 쉬어야겠어요"
        ),
        "請問現在幾點，我該吃藥了" to arrayOf(
            "What time is it? I need to take my medicine",
            "今何時ですか、薬を飲む時間です",
            "지금 몇 시예요? 약 먹을 시간이에요"
        ),
        "今天天氣很好，我想去公園走走" to arrayOf(
            "The weather is lovely today; I'd like to walk in the park",
            "今日はいい天気なので公園を散歩したいです",
            "오늘 날씨가 좋아서 공원에 산책 가고 싶어요"
        ),
        "麻煩你幫我把窗戶打開好嗎" to arrayOf(
            "Could you open the window for me, please?",
            "窓を開けてもらえますか",
            "창문 좀 열어 주시겠어요?"
        ),
        "晚餐我想吃一碗熱湯麵" to arrayOf(
            "For dinner I'd like a bowl of hot noodle soup",
            "夕食は温かいラーメンが食べたいです",
            "저녁으로 따뜻한 국수 한 그릇 먹고 싶어요"
        ),
        "我想跟醫生說我最近睡不好" to arrayOf(
            "I want to tell the doctor I haven't been sleeping well",
            "最近よく眠れないと医師に伝えたいです",
            "요즘 잠을 잘 못 잔다고 의사에게 말하고 싶어요"
        ),
        "請幫我把藥和開水拿過來" to arrayOf(
            "Please bring me my medicine and some water",
            "薬とお水を持ってきてください",
            "약과 물을 가져다 주세요"
        ),
    )
}
