package com.speakmylife.app.data

import com.speakmylife.app.data.AppLanguage

/**
 * AAC 圖卡詞面的多語對照（英／日／韓），以**卡片 id** 為鍵。
 *
 * ## 為什麼不直接翻 [AacItem.label]
 * `label` 是繁體中文原文，同時是三個地方的**比對基準**，翻掉全部會壞：
 *  1. [com.speakmylife.app.ai.AacUsageRanker] 的概念詞群（水／食／藥／錢…）是用中文策劃的，
 *     語意相似靠它做冷啟動——換成譯文後，按「喝水」就再也拉不出「杯子」。
 *  2. AAC 組句的 rawDraft 會先送進危機字眼攔截（[com.speakmylife.app.safety] / EmergencyTrigger），
 *     那是比對中文關鍵字的安全機制。
 *  3. 送進 LLM 的圖卡序列。
 * 所以：**顯示走這張表，比對一律走 label**。
 *
 * ## 為什麼以 id 為鍵而不是中文詞面
 * 有兩張卡的中文詞面相同但語境不同——「等一下」在「動作」是請對方稍候（Wait a
 * moment），在「時間」是稍後（In a moment）。以詞面為鍵會被迫共用一個譯法。
 */
object AacI18n {

    /** 這張卡在目前介面語言下要顯示的字；查無則回原本的繁中詞面。 */
    fun label(item: AacItem, lang: AppLanguage): String {
        if (lang == AppLanguage.ZhTW) return item.label
        val row = M[item.id] ?: return item.label
        val i = when (lang) {
            AppLanguage.En -> 0
            AppLanguage.Ja -> 1
            AppLanguage.Ko -> 2
            else -> return item.label
        }
        return row.getOrNull(i)?.takeIf { it.isNotBlank() } ?: item.label
    }

    /** 目前語言下的詞面（給非 composable 的地方用，語言取自 [com.speakmylife.app.ui.I18n]）。 */
    fun label(item: AacItem): String = label(item, com.speakmylife.app.ui.I18n.lang)

    // 鍵 = 卡片 id；值 = [en, ja, ko]
    private val M: Map<String, Array<String>> = mapOf(

        // ── Products ──
        "p_baoyue" to arrayOf("Betel (leaf)", "包み葉ビンロウ", "잎 빈랑"),   // 包葉檳榔
        "p_jingzai" to arrayOf("Betel (plain)", "ビンロウ（素）", "생 빈랑"),   // 菁仔
        "p_cig" to arrayOf("Cigarettes", "タバコ", "담배"),   // 香菸
        "p_lighter" to arrayOf("Lighter", "ライター", "라이터"),   // 打火機
        "p_bag" to arrayOf("Bag", "袋", "봉지"),   // 袋子
        "p_receipt" to arrayOf("Invoice", "レシート", "영수증"),   // 發票
        "p_menu" to arrayOf("Menu", "メニュー", "메뉴"),   // 菜單
        "p_ticket" to arrayOf("Ticket", "チケット", "티켓"),   // 票券
        "p_phone" to arrayOf("Phone", "スマホ", "휴대폰"),   // 手機
        "p_key" to arrayOf("Keys", "鍵", "열쇠"),   // 鑰匙
        "p_wallet" to arrayOf("Wallet", "財布", "지갑"),   // 錢包
        "p_id" to arrayOf("ID", "身分証", "신분증"),   // 證件
        "p_card" to arrayOf("Card", "カード", "카드"),   // 卡片
        "p_glasses" to arrayOf("Glasses", "メガネ", "안경"),   // 眼鏡
        "p_cane" to arrayOf("Cane", "杖", "지팡이"),   // 拐杖
        "p_wheelchair" to arrayOf("Wheelchair", "車いす", "휠체어"),   // 輪椅
        "p_charger" to arrayOf("Charger", "充電器", "충전기"),   // 充電器
        "p_tissue" to arrayOf("Tissue", "ティッシュ", "휴지"),   // 衛生紙

        // ── FoodDrink ──
        "f_water_cup" to arrayOf("A glass of water", "水一杯", "물 한 잔"),   // 一杯水
        "f_want_water" to arrayOf("I want water", "水が飲みたい", "물 마시고 싶어요"),   // 我要喝水
        "f_water_please" to arrayOf("Water, please", "水をください", "물 주세요"),   // 請給我水
        "f_warm_water_cup" to arrayOf("Warm water, a cup", "ぬるま湯一杯", "미온수 한 잔"),   // 溫水一杯
        "f_cold_water_cup" to arrayOf("Ice water, a cup", "氷水一杯", "얼음물 한 잔"),   // 冰水一杯
        "f_water" to arrayOf("Plain water", "お水", "맹물"),   // 白開水
        "f_cold_water" to arrayOf("Iced water", "氷水", "얼음물"),   // 結冰水
        "f_warm_water" to arrayOf("Warm water", "ぬるま湯", "미온수"),   // 溫開水
        "f_tea" to arrayOf("Tea", "お茶", "차"),   // 茶
        "f_drink" to arrayOf("Drink", "飲み物", "음료"),   // 飲料
        "f_coffee" to arrayOf("Coffee", "コーヒー", "커피"),   // 咖啡
        "f_meal" to arrayOf("Meal", "食事", "식사"),   // 餐點
        "f_rice" to arrayOf("Rice", "ごはん", "밥"),   // 飯
        "f_noodle" to arrayOf("Noodles", "麺", "면"),   // 麵
        "f_soup" to arrayOf("Soup", "スープ", "국"),   // 湯
        "f_fruit" to arrayOf("Fruit", "果物", "과일"),   // 水果
        "f_medicine_food" to arrayOf("Take with meds", "薬と一緒に", "약과 함께"),   // 配藥吃
        "f_bread" to arrayOf("Bread", "パン", "빵"),   // 麵包
        "f_milk" to arrayOf("Milk", "牛乳", "우유"),   // 牛奶
        "f_no_sugar" to arrayOf("No sugar", "砂糖なし", "무설탕"),   // 無糖
        "f_less_sugar" to arrayOf("Less sugar", "砂糖少なめ", "설탕 적게"),   // 少糖
        "f_less_ice" to arrayOf("Less ice", "氷少なめ", "얼음 적게"),   // 少冰
        "f_hot" to arrayOf("Hot", "ホット", "뜨겁게"),   // 熱的
        "f_cold" to arrayOf("Cold", "冷たい", "차갑게"),   // 冰的
        "f_straw" to arrayOf("Straw", "ストロー", "빨대"),   // 吸管

        // ── Money ──
        "m_how_much" to arrayOf("How much?", "いくら？", "얼마예요?"),   // 多少錢
        "m_50" to arrayOf("$50", "$50", "$50"),   // $50
        "m_100" to arrayOf("$100", "$100", "$100"),   // $100
        "m_200" to arrayOf("$200", "$200", "$200"),   // $200
        "m_500" to arrayOf("$500", "$500", "$500"),   // $500
        "m_1000" to arrayOf("$1000", "$1000", "$1000"),   // $1000
        "m_change" to arrayOf("Change", "おつり", "거스름돈"),   // 找零
        "m_1" to arrayOf("1 pack", "1袋", "1봉지"),   // 1 包
        "m_2" to arrayOf("2 packs", "2袋", "2봉지"),   // 2 包
        "m_3" to arrayOf("3 packs", "3袋", "3봉지"),   // 3 包
        "m_1cup" to arrayOf("1 cup", "1杯", "1잔"),   // 1 杯
        "m_2cup" to arrayOf("2 cups", "2杯", "2잔"),   // 2 杯
        "m_cash" to arrayOf("Cash", "現金", "현금"),   // 現金
        "m_card" to arrayOf("Card payment", "カード払い", "카드 결제"),   // 刷卡
        "m_easycard" to arrayOf("EasyCard", "悠遊カード", "이지카드"),   // 悠遊卡
        "m_credit" to arrayOf("Credit card", "クレジットカード", "신용카드"),   // 信用卡
        "m_debit" to arrayOf("Debit card", "デビットカード", "체크카드"),   // 金融卡
        "m_mobile_pay" to arrayOf("Mobile pay", "モバイル決済", "모바일 결제"),   // 行動支付
        "m_receipt" to arrayOf("Receipt", "レシート", "영수증"),   // 收據
        "m_tip" to arrayOf("Tip", "チップ", "팁"),   // 小費
        "m_expensive" to arrayOf("Too expensive", "高すぎる", "너무 비싸요"),   // 太貴

        // ── Actions ──
        "a_give" to arrayOf("Here you go", "どうぞ", "여기요"),   // 給您
        "a_ok" to arrayOf("Got it", "了解", "알겠어요"),   // 收到
        "a_wait" to arrayOf("Wait a moment", "ちょっと待って", "잠깐만요"),   // 等一下
        "a_no" to arrayOf("All out", "もうない", "다 떨어졌어요"),   // 沒有了
        "a_yes" to arrayOf("OK", "いいよ", "좋아요"),   // 好
        "a_takeaway" to arrayOf("Take away", "持ち帰り", "포장"),   // 帶走
        "a_dinein" to arrayOf("Eat in", "店内", "매장"),   // 內用
        "a_open" to arrayOf("Open it", "開けて", "열어 주세요"),   // 打開
        "a_close" to arrayOf("Close it", "閉めて", "닫아 주세요"),   // 關起來
        "a_call" to arrayOf("Make a call", "電話する", "전화해요"),   // 打電話
        "a_write" to arrayOf("Write it down", "書いて", "적어 주세요"),   // 寫下來
        "a_repeat" to arrayOf("Say it again", "もう一度", "다시 말해 주세요"),   // 再說一次
        "a_slow" to arrayOf("Slower", "ゆっくり", "천천히"),   // 慢一點
        "a_stop" to arrayOf("Stop a moment", "止めて", "잠깐 멈춰요"),   // 停一下
        "a_help_move" to arrayOf("Help me up", "支えて", "부축해 주세요"),   // 扶我
        "a_show" to arrayOf("Show me", "見せて", "보여 주세요"),   // 請給我看
        "a_choose" to arrayOf("Choose for me", "選んで", "골라 주세요"),   // 幫我選
        "a_find" to arrayOf("Find it for me", "探して", "찾아 주세요"),   // 幫我找
        "a_back" to arrayOf("I want to go back", "帰りたい", "돌아가고 싶어요"),   // 我要回去
        "a_buy" to arrayOf("I want to buy", "買いたい", "사고 싶어요"),   // 我要買
        "a_checkin" to arrayOf("I'm checking in", "受付します", "접수할게요"),   // 我要報到

        // ── Needs ──
        "n_toilet" to arrayOf("I need the toilet", "トイレに行きたい", "화장실 가고 싶어요"),   // 我要上廁所
        "n_rest" to arrayOf("I need to rest", "休みたい", "쉬고 싶어요"),   // 我要休息
        "n_sleep" to arrayOf("Sleepy", "眠い", "졸려요"),   // 想睡覺
        "n_hot" to arrayOf("Too hot", "暑すぎる", "너무 더워요"),   // 太熱
        "n_cold" to arrayOf("Too cold", "寒すぎる", "너무 추워요"),   // 太冷
        "n_thirsty" to arrayOf("Thirsty", "のどが渇いた", "목말라요"),   // 口渴
        "n_hungry" to arrayOf("Hungry", "お腹すいた", "배고파요"),   // 肚子餓
        "n_pain" to arrayOf("It hurts", "痛い", "아파요"),   // 會痛
        "n_sit" to arrayOf("Want to sit", "座りたい", "앉고 싶어요"),   // 想坐下
        "n_stand" to arrayOf("Want to stand", "立ちたい", "일어서고 싶어요"),   // 想站起來
        "n_clean" to arrayOf("Need cleaning", "掃除がいる", "청소가 필요해요"),   // 需要清潔
        "n_charge" to arrayOf("Need charging", "充電したい", "충전이 필요해요"),   // 需要充電
        "n_quiet" to arrayOf("Too noisy", "うるさい", "너무 시끄러워요"),   // 太吵
        "n_light" to arrayOf("Too bright", "まぶしい", "너무 밝아요"),   // 太亮
        "n_blanket" to arrayOf("Need a blanket", "毛布がほしい", "담요가 필요해요"),   // 需要毯子
        "n_tissue" to arrayOf("Need tissue", "ティッシュがほしい", "휴지가 필요해요"),   // 需要衛生紙
        "n_mask" to arrayOf("Need a mask", "マスクがほしい", "마스크가 필요해요"),   // 需要口罩
        "n_wifi" to arrayOf("Need Wi-Fi", "Wi-Fiがほしい", "Wi-Fi가 필요해요"),   // 需要 Wi-Fi
        "n_lost" to arrayOf("I'm lost", "道に迷った", "길을 잃었어요"),   // 我迷路了
        "n_wait_family" to arrayOf("Waiting for family", "家族を待つ", "가족을 기다려요"),   // 等家人

        // ── People ──
        "pe_family" to arrayOf("Family", "家族", "가족"),   // 家人
        "pe_mom" to arrayOf("Mom", "お母さん", "엄마"),   // 媽媽
        "pe_dad" to arrayOf("Dad", "お父さん", "아빠"),   // 爸爸
        "pe_child" to arrayOf("Child", "子ども", "아이"),   // 小孩
        "pe_friend" to arrayOf("Friend", "友だち", "친구"),   // 朋友
        "pe_doctor" to arrayOf("Doctor", "医師", "의사"),   // 醫生
        "pe_nurse" to arrayOf("Nurse", "看護師", "간호사"),   // 護理師
        "pe_customer" to arrayOf("Customer", "お客さん", "손님"),   // 客人
        "pe_clerk" to arrayOf("Clerk", "店員", "점원"),   // 店員
        "pe_driver" to arrayOf("Driver", "運転手", "기사"),   // 司機
        "pe_teacher" to arrayOf("Teacher", "先生", "선생님"),   // 老師
        "pe_police" to arrayOf("Police", "警察", "경찰"),   // 警察
        "pe_staff" to arrayOf("Staff", "係の人", "직원"),   // 服務人員
        "pe_interpreter" to arrayOf("Interpreter", "通訳", "통역사"),   // 翻譯員
        "pe_caregiver" to arrayOf("Caregiver", "介護士", "요양보호사"),   // 照護員
        "pe_pharmacist" to arrayOf("Pharmacist", "薬剤師", "약사"),   // 藥師

        // ── Places ──
        "pl_home" to arrayOf("Home", "家", "집"),   // 家裡
        "pl_shop" to arrayOf("The shop", "店", "가게"),   // 店裡
        "pl_hospital" to arrayOf("Hospital", "病院", "병원"),   // 醫院
        "pl_clinic" to arrayOf("Clinic", "診療所", "의원"),   // 診所
        "pl_pharmacy" to arrayOf("Pharmacy", "薬局", "약국"),   // 藥局
        "pl_toilet" to arrayOf("Toilet", "トイレ", "화장실"),   // 廁所
        "pl_restaurant" to arrayOf("Restaurant", "レストラン", "식당"),   // 餐廳
        "pl_station" to arrayOf("Station", "駅", "역"),   // 車站
        "pl_car" to arrayOf("In the car", "車の中", "차 안"),   // 車上
        "pl_outside" to arrayOf("Outside", "外", "밖"),   // 外面
        "pl_airport" to arrayOf("Airport", "空港", "공항"),   // 機場
        "pl_parking" to arrayOf("Parking", "駐車場", "주차장"),   // 停車場
        "pl_bank" to arrayOf("Bank", "銀行", "은행"),   // 銀行
        "pl_post" to arrayOf("Post office", "郵便局", "우체국"),   // 郵局
        "pl_school" to arrayOf("School", "学校", "학교"),   // 學校
        "pl_library" to arrayOf("Library", "図書館", "도서관"),   // 圖書館
        "pl_hotel" to arrayOf("Hotel", "ホテル", "호텔"),   // 旅館
        "pl_gate" to arrayOf("Boarding gate", "搭乗口", "탑승구"),   // 登機門

        // ── Medical ──
        "md_sick" to arrayOf("Not feeling well", "具合が悪い", "몸이 안 좋아요"),   // 不舒服
        "md_pain_head" to arrayOf("Headache", "頭痛", "두통"),   // 頭痛
        "md_pain_chest" to arrayOf("Chest pain", "胸が痛い", "가슴이 아파요"),   // 胸口痛
        "md_dizzy" to arrayOf("Dizzy", "めまい", "어지러워요"),   // 頭暈
        "md_nausea" to arrayOf("Nauseous", "吐き気", "토할 것 같아요"),   // 想吐
        "md_breath" to arrayOf("Short of breath", "息苦しい", "숨쉬기 힘들어요"),   // 喘不過氣
        "md_medicine" to arrayOf("Need medicine", "薬がほしい", "약이 필요해요"),   // 需要藥
        "md_hospital" to arrayOf("Go to hospital", "病院へ", "병원에 가요"),   // 去醫院
        "md_pressure" to arrayOf("Blood pressure", "血圧を測る", "혈압 측정"),   // 量血壓
        "md_sugar" to arrayOf("Blood sugar", "血糖を測る", "혈당 측정"),   // 量血糖
        "md_allergy" to arrayOf("Allergy", "アレルギー", "알레르기"),   // 過敏
        "md_diabetes" to arrayOf("Diabetes", "糖尿病", "당뇨병"),   // 糖尿病
        "md_heart" to arrayOf("Heart discomfort", "心臓が苦しい", "심장이 안 좋아요"),   // 心臟不舒服
        "md_fever" to arrayOf("Fever", "発熱", "열이 나요"),   // 發燒
        "md_fall" to arrayOf("Fell down", "転んだ", "넘어졌어요"),   // 跌倒
        "md_appointment" to arrayOf("Appointment", "予約", "예약"),   // 預約
        "md_insurance" to arrayOf("Insurance card", "保険証", "보험증"),   // 保險卡
        "md_prescription" to arrayOf("Prescription", "処方箋", "처방전"),   // 處方箋

        // ── Time ──
        "t_now" to arrayOf("Now", "今", "지금"),   // 現在
        "t_later" to arrayOf("In a moment", "後で", "잠시 후"),   // 等一下
        "t_morning" to arrayOf("Morning", "朝", "아침"),   // 早上
        "t_noon" to arrayOf("Noon", "昼", "점심"),   // 中午
        "t_evening" to arrayOf("Evening", "夜", "저녁"),   // 晚上
        "t_today" to arrayOf("Today", "今日", "오늘"),   // 今天
        "t_tomorrow" to arrayOf("Tomorrow", "明日", "내일"),   // 明天
        "t_yesterday" to arrayOf("Yesterday", "昨日", "어제"),   // 昨天
        "t_min5" to arrayOf("5 min", "5分", "5분"),   // 5 分鐘
        "t_min10" to arrayOf("10 min", "10分", "10분"),   // 10 分鐘
        "t_min30" to arrayOf("30 min", "30分", "30분"),   // 30 分鐘
        "t_hour1" to arrayOf("1 hour", "1時間", "1시간"),   // 1 小時
        "t_before" to arrayOf("Before", "前", "전"),   // 之前
        "t_after" to arrayOf("After", "後", "후"),   // 之後
        "t_medicine_time" to arrayOf("Medicine time", "薬の時間", "약 먹을 시간"),   // 吃藥時間
        "t_checkout" to arrayOf("Check-out time", "チェックアウト", "체크아웃 시간"),   // 退房時間

        // ── Questions ──
        "q_where" to arrayOf("Where?", "どこ？", "어디예요?"),   // 在哪裡？
        "q_when" to arrayOf("When?", "いつ？", "언제예요?"),   // 什麼時候？
        "q_how" to arrayOf("How?", "どうやって？", "어떻게 해요?"),   // 怎麼做？
        "q_can" to arrayOf("May I?", "いい？", "괜찮아요?"),   // 可以嗎？
        "q_have" to arrayOf("Do you have it?", "ある？", "있어요?"),   // 有嗎？
        "q_price" to arrayOf("How much?", "いくら？", "얼마예요?"),   // 多少錢？
        "q_again" to arrayOf("Say that again?", "もう一度いい？", "다시 말해 줄래요?"),   // 可以再說一次嗎？
        "q_help" to arrayOf("Can you help me?", "手伝ってくれる？", "도와줄 수 있어요?"),   // 可以幫我嗎？
        "q_toilet" to arrayOf("Where's the toilet?", "トイレはどこ？", "화장실 어디예요?"),   // 廁所在哪裡？
        "q_exit" to arrayOf("Where's the exit?", "出口はどこ？", "출구 어디예요?"),   // 出口在哪裡？
        "q_counter" to arrayOf("Where's the counter?", "受付はどこ？", "안내데스크 어디예요?"),   // 服務台在哪裡？
        "q_gate" to arrayOf("Where's the gate?", "搭乗口はどこ？", "탑승구 어디예요?"),   // 登機門在哪裡？
        "q_appointment" to arrayOf("How long is the wait?", "どのくらい待つ？", "얼마나 기다려요?"),   // 要等多久？
        "q_english" to arrayOf("Do you speak Chinese?", "中国語話せる？", "중국어 하세요?"),   // 可以說中文嗎？

        // ── Emotions ──
        "e_thanks" to arrayOf("Thank you", "ありがとう", "감사합니다"),   // 謝謝
        "e_sorry" to arrayOf("Sorry", "ごめんなさい", "죄송해요"),   // 抱歉
        "e_welcome" to arrayOf("Welcome", "いらっしゃいませ", "어서 오세요"),   // 歡迎光臨
        "e_happy" to arrayOf("Happy", "うれしい", "기뻐요"),   // 開心
        "e_worried" to arrayOf("Worried", "心配", "걱정돼요"),   // 擔心
        "e_angry" to arrayOf("Angry", "怒ってる", "화나요"),   // 生氣
        "e_tired" to arrayOf("Tired", "疲れた", "피곤해요"),   // 很累
        "e_scared" to arrayOf("Scared", "こわい", "무서워요"),   // 害怕
        "e_good" to arrayOf("Good", "いい", "좋아요"),   // 很好
        "e_bad" to arrayOf("Not good", "よくない", "안 좋아요"),   // 不好
        "e_comfort" to arrayOf("It's OK", "大丈夫", "괜찮아요"),   // 沒關係
        "e_confused" to arrayOf("I don't understand", "わからない", "못 알아듣겠어요"),   // 我聽不懂
        "e_nervous" to arrayOf("Nervous", "緊張してる", "긴장돼요"),   // 緊張
        "e_lonely" to arrayOf("Lonely", "さびしい", "외로워요"),   // 孤單
        "e_safe" to arrayOf("Relieved", "安心", "안심돼요"),   // 安心
        "e_hurry" to arrayOf("In a hurry", "急いでる", "급해요"),   // 很急

        // ── Emergency ──
        "g_help" to arrayOf("Help!", "助けて", "살려주세요"),   // 救命
        "g_family" to arrayOf("Get my family", "家族を呼んで", "가족을 불러 주세요"),   // 找家人
        "g_sick" to arrayOf("Not feeling well", "具合が悪い", "몸이 안 좋아요"),   // 不舒服
        "g_police" to arrayOf("Call the police", "警察を呼んで", "경찰 불러 주세요"),   // 報警
        "g_ambulance" to arrayOf("Call an ambulance", "救急車を呼んで", "구급차 불러 주세요"),   // 叫救護車
        "g_fall" to arrayOf("I fell", "転んだ", "넘어졌어요"),   // 跌倒了
        "g_choke" to arrayOf("Choking", "のどに詰まった", "목에 걸렸어요"),   // 噎到
        "g_fire" to arrayOf("Fire", "火事", "불이야"),   // 火災
        "g_lost" to arrayOf("Lost", "道に迷った", "길을 잃었어요"),   // 迷路
        "g_cant_breathe" to arrayOf("Can't breathe", "息ができない", "숨을 못 쉬어요"),   // 不能呼吸
        "g_seizure" to arrayOf("Seizure", "けいれん", "경련"),   // 抽搐
        "g_allergy" to arrayOf("Severe allergy", "重いアレルギー", "심한 알레르기"),   // 嚴重過敏
        "g_chest" to arrayOf("Chest pain", "胸が痛い", "가슴 통증"),   // 胸痛

        // ── 情境推薦的臨時卡（AacContextSuggester 依定位產生，不進預設庫）──
        "ctx_92" to arrayOf("Regular 92", "レギュラー92", "92 무연"),
        "ctx_95" to arrayOf("Mid 95", "ハイオク95", "95 무연"),
        "ctx_americano" to arrayOf("Americano", "アメリカーノ", "아메리카노"),
        "ctx_baggage" to arrayOf("Baggage", "荷物", "짐"),
        "ctx_basket" to arrayOf("Cart", "カート", "카트"),
        "ctx_boarding_pass" to arrayOf("Boarding pass", "搭乗券", "탑승권"),
        "ctx_borrow" to arrayOf("I want to borrow", "本を借りたい", "책 빌리고 싶어요"),
        "ctx_buy_stamp" to arrayOf("Buy stamps", "切手を買う", "우표 사기"),
        "ctx_buy_ticket" to arrayOf("One ticket", "切符を1枚", "표 한 장"),
        "ctx_call_doc" to arrayOf("Call the doctor", "医師に電話", "의사에게 전화"),
        "ctx_call_family" to arrayOf("Get my family", "家族を呼んで", "가족을 불러 주세요"),
        "ctx_call_front" to arrayOf("Call the front desk", "フロントを呼ぶ", "프런트 불러 주세요"),
        "ctx_check" to arrayOf("Check, please", "お会計", "계산해 주세요"),
        "ctx_check_in" to arrayOf("I'm checking in", "チェックインします", "체크인할게요"),
        "ctx_checkout" to arrayOf("Checkout", "会計", "계산"),
        "ctx_checkout_time" to arrayOf("Check-out time", "チェックアウト時間", "체크아웃 시간"),
        "ctx_cig" to arrayOf("Cigarettes", "タバコ", "담배"),
        "ctx_color_other" to arrayOf("Another colour", "別の色", "다른 색"),
        "ctx_deposit" to arrayOf("Deposit", "預け入れ", "입금"),
        "ctx_dine_in" to arrayOf("Eat in", "店内", "매장"),
        "ctx_dining_floor" to arrayOf("Where's the food court?", "フードコートはどこ？", "푸드코트 어디예요?"),
        "ctx_donate" to arrayOf("Make an offering", "お布施", "시주"),
        "ctx_easycard" to arrayOf("EasyCard", "悠遊カード", "이지카드"),
        "ctx_egg" to arrayOf("Eggs", "卵", "계란"),
        "ctx_elevator" to arrayOf("Elevator", "エレベーター", "엘리베이터"),
        "ctx_exit" to arrayOf("Where's the exit?", "出口はどこ？", "출구 어디예요?"),
        "ctx_find_car" to arrayOf("Find my car", "車を探す", "차 찾기"),
        "ctx_flu" to arrayOf("A cold", "風邪", "감기"),
        "ctx_full_tank" to arrayOf("Fill it up", "満タン", "가득 채워 주세요"),
        "ctx_gate" to arrayOf("Where's the gate?", "搭乗口はどこ？", "탑승구 어디예요?"),
        "ctx_help" to arrayOf("I need help", "手伝ってほしい", "도움이 필요해요"),
        "ctx_help_counter" to arrayOf("Service desk", "サービスカウンター", "서비스 데스크"),
        "ctx_hot" to arrayOf("Hot", "ホット", "따뜻하게"),
        "ctx_how_much" to arrayOf("How much?", "いくら？", "얼마예요?"),
        "ctx_ice_drink" to arrayOf("No ice", "氷なし", "얼음 빼고"),
        "ctx_incense" to arrayOf("Incense", "お線香", "향"),
        "ctx_info" to arrayOf("Info desk", "案内所", "안내데스크"),
        "ctx_invoice" to arrayOf("Company tax ID", "法人番号", "사업자 번호"),
        "ctx_late" to arrayOf("It's delayed", "遅れてる", "연착됐어요"),
        "ctx_latte" to arrayOf("Latte", "ラテ", "라떼"),
        "ctx_less_ice" to arrayOf("Less ice", "氷少なめ", "얼음 적게"),
        "ctx_less_sugar" to arrayOf("Less sugar", "甘さ控えめ", "설탕 적게"),
        "ctx_mask" to arrayOf("Mask", "マスク", "마스크"),
        "ctx_med_time" to arrayOf("Medicine time", "薬の時間", "약 먹을 시간"),
        "ctx_menu" to arrayOf("Menu", "メニュー", "메뉴"),
        "ctx_milk" to arrayOf("Milk", "牛乳", "우유"),
        "ctx_need_help" to arrayOf("I need help", "手伝ってほしい", "도움이 필요해요"),
        "ctx_need_med" to arrayOf("Need medicine", "薬がほしい", "약이 필요해요"),
        "ctx_no_onion" to arrayOf("No onion", "玉ねぎ抜き", "양파 빼고"),
        "ctx_not_well" to arrayOf("I'm not well", "具合が悪い", "몸이 안 좋아요"),
        "ctx_nurse" to arrayOf("Nurse", "看護師さん", "간호사님"),
        "ctx_order_meal" to arrayOf("I'd like to order", "注文したい", "주문할게요"),
        "ctx_pain_chest" to arrayOf("Chest pain", "胸が痛い", "가슴이 아파요"),
        "ctx_parcel" to arrayOf("Send a parcel", "小包を送る", "소포 보내기"),
        "ctx_passbook" to arrayOf("Passbook", "通帳", "통장"),
        "ctx_pay_card" to arrayOf("Pay by card", "カードで払う", "카드로 결제"),
        "ctx_pay_parking" to arrayOf("Pay for parking", "駐車料金を払う", "주차비 결제"),
        "ctx_peace" to arrayOf("Peace", "無事", "평안"),
        "ctx_plastic_bag" to arrayOf("Bag", "袋", "봉지"),
        "ctx_platform" to arrayOf("Which platform?", "何番線？", "몇 번 승강장?"),
        "ctx_pray" to arrayOf("Pray", "お参り", "기도"),
        "ctx_quiet" to arrayOf("Please keep it down", "静かにお願い", "조용히 해주세요"),
        "ctx_reg" to arrayOf("Registration", "受付", "접수"),
        "ctx_rest" to arrayOf("Take a break", "少し休む", "잠깐 쉬기"),
        "ctx_rest_seat" to arrayOf("Two people", "2人", "두 명"),
        "ctx_return_book" to arrayOf("I want to return", "本を返したい", "책 반납할게요"),
        "ctx_rice" to arrayOf("Rice", "お米", "쌀"),
        "ctx_rice_ball" to arrayOf("Rice ball", "おにぎり", "삼각김밥"),
        "ctx_room_key" to arrayOf("Room key", "ルームキー", "객실 키"),
        "ctx_see_doc" to arrayOf("I want to see a doctor", "医師に診てほしい", "진료 받고 싶어요"),
        "ctx_send" to arrayOf("Send a letter", "手紙を出す", "편지 부치기"),
        "ctx_set" to arrayOf("Combo set", "セット", "세트"),
        "ctx_sit" to arrayOf("Sit for a while", "少し座る", "잠깐 앉기"),
        "ctx_size_l" to arrayOf("Large", "Lサイズ", "큰 사이즈"),
        "ctx_size_m" to arrayOf("Medium", "Mサイズ", "중간 사이즈"),
        "ctx_take_away" to arrayOf("Take away", "持ち帰り", "포장"),
        "ctx_take_med" to arrayOf("Pick up medicine", "薬を受け取る", "약 받기"),
        "ctx_tasty" to arrayOf("It's delicious", "おいしい", "맛있어요"),
        "ctx_tea_milk" to arrayOf("Milk tea", "ミルクティー", "밀크티"),
        "ctx_temp" to arrayOf("Take temperature", "体温を測る", "체온 측정"),
        "ctx_thanks" to arrayOf("Thank you", "ありがとう", "감사합니다"),
        "ctx_ticket_pkg" to arrayOf("Gift wrap", "ギフト包装", "선물 포장"),
        "ctx_toilet" to arrayOf("Restroom", "お手洗い", "화장실"),
        "ctx_towel" to arrayOf("Towel", "タオル", "수건"),
        "ctx_transfer" to arrayOf("How do I transfer?", "乗り換えは？", "환승 어떻게 해요?"),
        "ctx_translate" to arrayOf("Please translate", "通訳お願い", "통역 부탁해요"),
        "ctx_try_on" to arrayOf("Can I try it on?", "試着できる？", "입어 봐도 돼요?"),
        "ctx_waiter" to arrayOf("Excuse me", "すみません", "저기요"),
        "ctx_walk" to arrayOf("Take a walk", "散歩", "산책"),
        "ctx_water" to arrayOf("Plain water", "お水", "맹물"),
        "ctx_water_cold" to arrayOf("Iced water", "氷水", "얼음물"),
        "ctx_water_cup" to arrayOf("A glass of water", "水一杯", "물 한 잔"),
        "ctx_where_seat" to arrayOf("Where's my seat?", "席はどこ？", "자리 어디예요?"),
        "ctx_withdraw" to arrayOf("Withdraw", "引き出し", "출금"),
    )
}
