package com.example.ai

import com.example.data.AacCategory
import com.example.data.AacItem
import com.example.data.DefaultAacItems

/**
 * 依當前所在地點，推薦最可能被使用的 AAC 圖卡。
 *
 * 設計：
 *  - 輸入是 [LocationTracker] / Places 給出的 shortTag，如 `便利商店·全家`、`醫院·台大醫院`。
 *  - 先取「·」之前的類別（不認店名，只認類型），避免新店名上線就失效。
 *  - 每個類別配一張「推薦組合」：3-5 個既有 [DefaultAacItems] 中的卡片 + 可能擴充的新增卡。
 *  - 加上「新增情境卡」（非預設庫的臨時卡），讓建議真的對話得起來。
 *
 * 對失語症使用者：對話啟動成本越低越好。進到 7-11 看到「結冰水 / 香菸 / 多少錢」三張卡，
 * 比起每次要從 5 個類別翻過去快得多。
 */
object AacContextSuggester {

    /** UI 看到的「情境提示」單筆：類別文字 + 推薦圖卡 + 簡短引言 */
    data class ContextHint(
        val locationLabel: String,        // e.g. "便利商店"
        val intro: String,                // e.g. "在便利商店，要不要說："
        val suggestedItems: List<AacItem>
    )

    /** 主入口。空白 / 未命中 → 回 null（UI 端不顯示提示列）。 */
    fun suggest(locationShortTag: String): ContextHint? {
        if (locationShortTag.isBlank()) return null
        val rawCategory = locationShortTag.substringBefore("·").trim()
        val category = CATEGORY_ALIASES[rawCategory] ?: rawCategory
        val rule = CATEGORY_RULES[category] ?: return null
        return ContextHint(
            locationLabel = category,
            intro = rule.intro,
            suggestedItems = rule.itemFactory()
        )
    }

    /**
     * 主畫面「快速推薦語彙」chips 用：把該位置下的 AAC 建議卡 → 純文字短語。
     *
     * 為什麼從同一個 CATEGORY_RULES 推導：避免兩份 source-of-truth 飄移。
     * 之前 VoiceWeaverScreen 寫死 4 個 legacy 地名，新位置全掉 default。
     * 用 suggestedItems.map{ it.label } 統一兜底，17+ 位置全自動覆蓋。
     *
     * 找不到位置 → 回 [DEFAULT_QUICK_PHRASES]（不回 null，UI 永遠有東西點）。
     */
    fun quickPhrasesFor(locationShortTag: String): List<String> {
        val hint = suggest(locationShortTag)
        return hint?.suggestedItems?.map { it.label }?.distinct()?.take(6)
            ?: DEFAULT_QUICK_PHRASES
    }

    /**
     * 「相機快照識別標籤」(Vision Tags) 預設項：依位置給出最可能在鏡頭內看到的物品。
     *
     * 與 [suggestedItems] 不同：suggestedItems 是「使用者會想說的字」，
     * visionPresets 是「現場可能會被拍到的東西」（攝影機自動標記範本）。
     * 例：在便利商店 suggestedItems = 結冰水/香菸/多少錢，visionPresets = 商品架/結帳台/微波爐/冷藏櫃。
     */
    fun visionPresetsFor(locationShortTag: String): List<String> {
        if (locationShortTag.isBlank()) return DEFAULT_VISION_PRESETS
        val rawCategory = locationShortTag.substringBefore("·").trim()
        val category = CATEGORY_ALIASES[rawCategory] ?: rawCategory
        return VISION_PRESETS[category] ?: DEFAULT_VISION_PRESETS
    }

    /** 單一 Vision 物品：emoji + 標籤。給下拉選單用，所有物品都有圖示。 */
    data class VisionTag(val emoji: String, val label: String)

    /**
     * 從 [VISION_CATALOG] 找對應 emoji；找不到回 fallback "📷"。
     * 給 UI 把舊有 vault.visionTags: List<String> 渲染成有 emoji 的 chip 用。
     */
    fun visionEmojiFor(label: String): String =
        visionEmojiIndex[label] ?: "📷"

    private val visionEmojiIndex: Map<String, String> by lazy {
        VISION_CATALOG.values.flatten().associate { it.label to it.emoji }
    }

    /**
     * 全部 Vision 物品的分類目錄。給「+ 更多物品」下拉選單用。
     * 分類順序按失語症患者日常出現頻率排：飲食 → 醫療 → 容器 → 家具 → 文書 → 服飾 → 3C → 交通 → 場景 → 自然。
     *
     * 每個 emoji 都挑可在 Android emoji font 正常顯示的；避免 ZWJ 組合（如 🧑‍⚕️）以免裝置不同字體掉格。
     */
    val VISION_CATALOG: Map<String, List<VisionTag>> = linkedMapOf(
        "飲食" to listOf(
            VisionTag("💧", "水杯"), VisionTag("🍵", "茶杯"), VisionTag("☕", "咖啡杯"),
            VisionTag("🥛", "牛奶"), VisionTag("🧃", "飲料"), VisionTag("🥤", "結冰水"),
            VisionTag("🧋", "珍珠奶茶"), VisionTag("🍙", "御飯糰"), VisionTag("🍞", "麵包"),
            VisionTag("🥖", "法國麵包"), VisionTag("🥯", "貝果"), VisionTag("🥐", "可頌"),
            VisionTag("🍚", "飯碗"), VisionTag("🍜", "麵碗"), VisionTag("🍲", "湯"),
            VisionTag("🍣", "壽司"), VisionTag("🍱", "便當"), VisionTag("🥟", "餃子"),
            VisionTag("🍔", "漢堡"), VisionTag("🍟", "薯條"), VisionTag("🍕", "披薩"),
            VisionTag("🌭", "熱狗"), VisionTag("🌮", "塔可"), VisionTag("🥗", "沙拉"),
            VisionTag("🍳", "煎蛋"), VisionTag("🥚", "雞蛋"), VisionTag("🧀", "起司"),
            VisionTag("🍎", "蘋果"), VisionTag("🍌", "香蕉"), VisionTag("🍊", "橘子"),
            VisionTag("🍇", "葡萄"), VisionTag("🍅", "番茄"), VisionTag("🥕", "胡蘿蔔")
        ),
        "餐具與廚房" to listOf(
            VisionTag("🥢", "筷子"), VisionTag("🍴", "刀叉"), VisionTag("🥄", "湯匙"),
            VisionTag("🍽", "餐盤"), VisionTag("🥣", "碗"), VisionTag("🧂", "鹽罐"),
            VisionTag("🍶", "醬油"), VisionTag("🧊", "冰塊"), VisionTag("🥡", "外帶盒"),
            VisionTag("🍴", "餐巾紙"), VisionTag("🧹", "抹布"), VisionTag("📋", "菜單")
        ),
        "醫療" to listOf(
            VisionTag("💊", "藥袋"), VisionTag("💉", "針筒"), VisionTag("🩺", "聽診器"),
            VisionTag("🌡", "體溫計"), VisionTag("🩹", "OK 繃"), VisionTag("😷", "口罩"),
            VisionTag("🧴", "消毒液"), VisionTag("🦯", "拐杖"), VisionTag("♿", "輪椅"),
            VisionTag("🛏", "病床"), VisionTag("📝", "掛號單"), VisionTag("📋", "看診單"),
            VisionTag("🧤", "醫療手套"), VisionTag("🦷", "牙刷"), VisionTag("🧼", "肥皂"),
            VisionTag("🪥", "牙刷"), VisionTag("💧", "點滴")
        ),
        "容器" to listOf(
            VisionTag("🛍", "購物袋"), VisionTag("🎒", "背包"), VisionTag("👜", "手提包"),
            VisionTag("👛", "錢包"), VisionTag("💼", "公事包"), VisionTag("📦", "紙箱"),
            VisionTag("📫", "信箱"), VisionTag("🪣", "水桶"), VisionTag("🗑", "垃圾桶"),
            VisionTag("🧺", "籃子"), VisionTag("🛒", "購物車")
        ),
        "家具" to listOf(
            VisionTag("🛋", "沙發"), VisionTag("🪑", "椅子"), VisionTag("🛏", "床"),
            VisionTag("🪞", "鏡子"), VisionTag("🪟", "窗戶"), VisionTag("🚪", "門"),
            VisionTag("🪴", "盆栽"), VisionTag("💡", "燈"), VisionTag("⏰", "時鐘"),
            VisionTag("🛁", "浴缸"), VisionTag("🚽", "馬桶"), VisionTag("🚿", "蓮蓬頭")
        ),
        "電器與 3C" to listOf(
            VisionTag("📺", "電視"), VisionTag("🎮", "遙控器"), VisionTag("📱", "手機"),
            VisionTag("💻", "電腦"), VisionTag("⌨", "鍵盤"), VisionTag("🖱", "滑鼠"),
            VisionTag("🖨", "印表機"), VisionTag("📷", "相機"), VisionTag("🔌", "插座"),
            VisionTag("🔋", "電池"), VisionTag("📡", "Wi-Fi 機"), VisionTag("🌬", "電風扇"),
            VisionTag("🧊", "冰箱"), VisionTag("🔥", "瓦斯爐"), VisionTag("📻", "收音機")
        ),
        "服飾" to listOf(
            VisionTag("👕", "衣服"), VisionTag("👔", "襯衫"), VisionTag("👗", "洋裝"),
            VisionTag("👖", "褲子"), VisionTag("🧥", "外套"), VisionTag("🧦", "襪子"),
            VisionTag("👟", "鞋"), VisionTag("👠", "高跟鞋"), VisionTag("👡", "涼鞋"),
            VisionTag("🧢", "帽子"), VisionTag("🎩", "禮帽"), VisionTag("👓", "眼鏡"),
            VisionTag("🕶", "墨鏡"), VisionTag("⌚", "手錶"), VisionTag("💍", "戒指"),
            VisionTag("👜", "皮包")
        ),
        "文書與標示" to listOf(
            VisionTag("📰", "報紙"), VisionTag("📚", "書"), VisionTag("📒", "存摺"),
            VisionTag("📝", "筆記本"), VisionTag("✏", "鉛筆"), VisionTag("🖊", "原子筆"),
            VisionTag("🧾", "收據"), VisionTag("💳", "信用卡"), VisionTag("🪪", "證件"),
            VisionTag("📮", "郵筒"), VisionTag("✉", "信封"), VisionTag("📦", "包裹"),
            VisionTag("🎫", "車票"), VisionTag("🏷", "標價"), VisionTag("📋", "看板"),
            VisionTag("🚪", "標示牌"), VisionTag("🚻", "廁所標示")
        ),
        "場景物件" to listOf(
            VisionTag("🛒", "商品架"), VisionTag("💰", "結帳台"), VisionTag("🍱", "微波爐"),
            VisionTag("🧊", "冷藏櫃"), VisionTag("🚉", "月台"), VisionTag("🚧", "閘門"),
            VisionTag("⛽", "油槍"), VisionTag("🏧", "ATM"), VisionTag("🛗", "電梯"),
            VisionTag("🪜", "手扶梯"), VisionTag("ℹ", "服務台"), VisionTag("🍴", "美食街"),
            VisionTag("👕", "試衣間"), VisionTag("🪑", "長椅"), VisionTag("🎁", "禮品包裝"),
            VisionTag("🎄", "聖誕樹"), VisionTag("🕯", "蠟燭"), VisionTag("🪔", "香爐"),
            VisionTag("🏮", "燈籠")
        ),
        "交通工具" to listOf(
            VisionTag("🚗", "汽車"), VisionTag("🚕", "計程車"), VisionTag("🚌", "公車"),
            VisionTag("🚆", "火車"), VisionTag("🚇", "捷運"), VisionTag("🛵", "機車"),
            VisionTag("🚲", "腳踏車"), VisionTag("✈", "飛機"), VisionTag("🚢", "船"),
            VisionTag("🚀", "太空船"), VisionTag("🚦", "紅綠燈"), VisionTag("🚦", "斑馬線")
        ),
        "人物關係" to listOf(
            VisionTag("👨", "男性"), VisionTag("👩", "女性"), VisionTag("👴", "老人"),
            VisionTag("👶", "嬰兒"), VisionTag("🧒", "小孩"), VisionTag("👨‍⚕", "醫生"),
            VisionTag("👮", "警察"), VisionTag("👷", "工人"), VisionTag("👨‍🍳", "廚師"),
            VisionTag("🐕", "狗"), VisionTag("🐈", "貓"), VisionTag("🐦", "鳥")
        ),
        "自然" to listOf(
            VisionTag("🌳", "樹"), VisionTag("🌸", "花"), VisionTag("🌹", "玫瑰"),
            VisionTag("🌿", "草"), VisionTag("☀", "太陽"), VisionTag("☁", "雲"),
            VisionTag("🌧", "雨"), VisionTag("❄", "雪"), VisionTag("🌊", "海"),
            VisionTag("🏔", "山"), VisionTag("🌙", "月亮"), VisionTag("⭐", "星星")
        )
    )

    private val DEFAULT_QUICK_PHRASES = listOf("水", "想睡覺", "要吃藥", "不舒服")
    private val DEFAULT_VISION_PRESETS = listOf("水杯", "報紙", "手機", "藥袋")

    /**
     * 主畫面「平行模擬位置」chip 列：給使用者一鍵切到某情境，不需實體 GPS。
     *
     * 排序原則：依失語症患者日常使用頻率 — 住家 / 醫院 / 藥局 在前，娛樂在後。
     * 之前寫死 4 個 (客廳/台大醫院/全聯/街口餐廳) 而且字面不對映 CATEGORY_RULES，
     * 點了情境推薦不會啟動。現在 20 個 preset 直接對映 [CATEGORY_RULES]，
     * 點哪個就立刻同步 quickPhrases / visionPresets / aacContextHint 三層。
     */
    data class LocationPreset(val emoji: String, val label: String)

    val LOCATION_PRESETS: List<LocationPreset> = listOf(
        LocationPreset("🏠", "住家"),
        LocationPreset("🏥", "醫院"),
        LocationPreset("🩺", "診所"),
        LocationPreset("💊", "藥局"),
        LocationPreset("🏪", "便利商店"),
        LocationPreset("🛒", "超市"),
        LocationPreset("🍔", "速食店"),
        LocationPreset("🍽", "餐廳"),
        LocationPreset("☕", "咖啡店"),
        LocationPreset("🧋", "飲料店"),
        LocationPreset("🏬", "百貨"),
        LocationPreset("🛍", "購物中心"),
        LocationPreset("👕", "服飾店"),
        LocationPreset("🚉", "車站"),
        LocationPreset("🚇", "捷運站"),
        LocationPreset("⛽", "加油站"),
        LocationPreset("🏦", "銀行"),
        LocationPreset("📮", "郵局"),
        LocationPreset("✈️", "機場"),
        LocationPreset("🅿️", "停車場"),
        LocationPreset("🏨", "旅館"),
        LocationPreset("🌳", "公園"),
        LocationPreset("⛩", "廟宇"),
        LocationPreset("⛪", "教堂")
    )

    /**
     * 位置 → 相機鏡頭可能拍到的物品。涵蓋與 [CATEGORY_RULES] 同一組 17+ 位置。
     * 之所以分開列：suggestedItems 是「要說的字」，visionPresets 是「眼前的物」，
     * 兩者語意不同。不要試圖共用同一份資料。
     */
    private val VISION_PRESETS: Map<String, List<String>> = mapOf(
        "便利商店" to listOf("商品架", "結帳台", "微波爐", "冷藏櫃", "御飯糰", "飲料"),
        "速食店" to listOf("點餐機", "套餐", "薯條", "可樂", "餐盤", "番茄醬"),
        "餐廳"   to listOf("菜單", "餐盤", "筷子", "茶杯", "湯匙", "餐巾"),
        "咖啡店" to listOf("咖啡杯", "糖包", "吸管", "蛋糕", "外帶杯", "菜單"),
        "飲料店" to listOf("珍珠奶茶", "杯套", "封口杯", "吸管", "菜單"),
        "醫院"   to listOf("掛號單", "藥袋", "拐杖", "輪椅", "點滴", "病床"),
        "診所"   to listOf("掛號單", "藥袋", "聽診器", "體溫計", "口罩"),
        "藥局"   to listOf("藥袋", "口罩", "保健食品", "OK 繃", "感冒藥"),
        "超市"   to listOf("購物車", "蔬菜", "牛奶", "雞蛋", "麵包", "結帳台"),
        "百貨"   to listOf("購物袋", "試衣間", "鏡子", "標價", "電梯", "手扶梯"),
        "購物中心" to listOf("購物袋", "電梯", "服務台", "美食街", "標示牌"),
        "服飾店" to listOf("試衣間", "衣架", "標價", "鏡子"),
        "車站"   to listOf("月台", "車票", "時刻表", "閘門", "行李箱"),
        "捷運站" to listOf("悠遊卡", "月台", "閘門", "時刻表"),
        "加油站" to listOf("油槍", "顯示器", "便利屋", "收據", "車輛"),
        "銀行"   to listOf("ATM", "存摺", "號碼牌", "櫃台", "提款卡"),
        "郵局"   to listOf("信封", "包裹", "郵票", "號碼牌"),
        "機場"   to listOf("登機證", "行李箱", "安檢口", "登機門", "護照"),
        "停車場" to listOf("汽車", "繳費機", "電梯", "出口", "車票"),
        "旅館"   to listOf("房卡", "櫃台", "行李箱", "電梯", "床"),
        "公園"   to listOf("樹", "長椅", "鞦韆", "草地", "石階"),
        "廟宇"   to listOf("香爐", "供桌", "神像", "金紙", "燈籠"),
        "教堂"   to listOf("長椅", "十字架", "蠟燭", "入口", "服務台"),
        "住家"   to listOf("水杯", "電視", "遙控器", "藥盒", "沙發", "床")
    )

    private data class Rule(val intro: String, val itemFactory: () -> List<AacItem>)

    /**
     * 「臨時推薦卡」工具：給情境提示用，**不會**寫進預設庫。
     * 之所以做臨時：DefaultAacItems 是檳榔攤情境的，到了醫院 / 車站還要再列圖卡。
     */
    private fun temp(id: String, label: String, emoji: String, category: AacCategory) =
        AacItem(id = "ctx_$id", label = label, emoji = emoji, category = category)

    private val CATEGORY_ALIASES: Map<String, String> = mapOf(
        "藥妝店" to "藥局",
        "Drugstore" to "藥局",
        "牙科" to "診所",
        "復健科" to "診所",
        "照護中心" to "住家",
        "獸醫" to "診所",
        "外帶店" to "速食店",
        "外送店" to "速食店",
        "麵包店" to "咖啡店",
        "商店" to "超市",
        "店家" to "超市",
        "量販店" to "超市",
        "五金行" to "超市",
        "電子產品店" to "百貨",
        "Department Store" to "百貨",
        "Shopping mall" to "購物中心",
        "公車站" to "車站",
        "計程車招呼站" to "車站",
        "停車場" to "停車場",
        "機場" to "機場",
        "ATM" to "銀行",
        "公家機關" to "郵局",
        "警察局" to "郵局",
        "消防隊" to "郵局",
        "學校" to "圖書館",
        "圖書館" to "圖書館",
        "教堂" to "教堂",
        "清真寺" to "教堂",
        "猶太教堂" to "教堂",
        "印度廟" to "廟宇",
        "景點" to "公園",
        "旅館" to "旅館"
    )

    private val CATEGORY_RULES: Map<String, Rule> = mapOf(
        // ── 便利商店：買東西、付錢、找零 ──
        "便利商店" to Rule("在便利商店常用：") {
            listOf(
                temp("water_cold", "結冰水", "🥤", AacCategory.Products),
                temp("cig", "香菸", "🚬", AacCategory.Products),
                temp("rice_ball", "御飯糰", "🍙", AacCategory.Products),
                temp("how_much", "多少錢", "💵", AacCategory.Money),
                temp("pay_card", "悠遊卡付", "💳", AacCategory.Actions),
                temp("plastic_bag", "袋子", "🛍", AacCategory.Products)
            )
        },

        // ── 速食店：點餐、選擇 ──
        "速食店" to Rule("在速食店常用：") {
            listOf(
                temp("order_meal", "我想點餐", "🍔", AacCategory.Actions),
                temp("set", "套餐", "🍟", AacCategory.Products),
                temp("no_onion", "不要洋蔥", "🚫", AacCategory.Actions),
                temp("take_away", "帶走", "🥡", AacCategory.Actions),
                temp("dine_in", "內用", "🍽", AacCategory.Actions),
                temp("ice_drink", "去冰", "❄️", AacCategory.Actions)
            )
        },

        // ── 餐廳：座位、菜單、結帳 ──
        "餐廳" to Rule("在餐廳常用：") {
            listOf(
                temp("rest_seat", "兩個人", "👫", AacCategory.Actions),
                temp("menu", "菜單", "📋", AacCategory.Products),
                temp("waiter", "麻煩您", "🤲", AacCategory.Emotions),
                temp("water", "白開水", "💧", AacCategory.Products),
                temp("check", "買單", "💳", AacCategory.Actions),
                temp("tasty", "很好吃", "😋", AacCategory.Emotions)
            )
        },

        // ── 咖啡店 / 飲料店 ──
        "咖啡店" to Rule("在咖啡店常用：") {
            listOf(
                temp("americano", "美式", "☕", AacCategory.Products),
                temp("latte", "拿鐵", "🥛", AacCategory.Products),
                temp("hot", "熱的", "🔥", AacCategory.Actions),
                temp("less_sugar", "微糖", "🍬", AacCategory.Actions),
                temp("size_m", "中杯", "Ⓜ️", AacCategory.Actions),
                temp("dine_in", "內用", "🍽", AacCategory.Actions)
            )
        },
        "飲料店" to Rule("在飲料店常用：") {
            listOf(
                temp("tea_milk", "奶茶", "🧋", AacCategory.Products),
                temp("less_sugar", "微糖", "🍬", AacCategory.Actions),
                temp("less_ice", "少冰", "❄️", AacCategory.Actions),
                temp("size_l", "大杯", "🇱", AacCategory.Actions)
            )
        },

        // ── 醫院 / 診所 / 藥局：就醫關鍵句 ──
        "醫院" to Rule("在醫院常用：") {
            listOf(
                temp("not_well", "我不舒服", "🤕", AacCategory.Emergency),
                temp("pain_chest", "胸口痛", "💔", AacCategory.Emergency),
                temp("call_family", "找家人", "📞", AacCategory.Emergency),
                temp("need_med", "需要藥", "💊", AacCategory.Actions),
                temp("nurse", "護士小姐", "🧑‍⚕️", AacCategory.Emotions),
                temp("thanks", "謝謝", "🙏", AacCategory.Emotions)
            )
        },
        "診所" to Rule("在診所常用：") {
            listOf(
                temp("reg", "掛號", "📝", AacCategory.Actions),
                temp("see_doc", "我要看醫生", "🩺", AacCategory.Actions),
                temp("temp", "量體溫", "🌡", AacCategory.Actions),
                temp("not_well", "不舒服", "🤕", AacCategory.Emergency)
            )
        },
        "藥局" to Rule("在藥局常用：") {
            listOf(
                temp("take_med", "拿藥", "💊", AacCategory.Actions),
                temp("flu", "感冒", "🤧", AacCategory.Emergency),
                temp("how_much", "多少錢", "💵", AacCategory.Money),
                temp("mask", "口罩", "😷", AacCategory.Products)
            )
        },

        // ── 超市 / 量販 ──
        "超市" to Rule("在超市常用：") {
            listOf(
                temp("basket", "推車", "🛒", AacCategory.Products),
                temp("milk", "牛奶", "🥛", AacCategory.Products),
                temp("egg", "雞蛋", "🥚", AacCategory.Products),
                temp("rice", "白米", "🍚", AacCategory.Products),
                temp("checkout", "結帳", "💳", AacCategory.Actions),
                temp("plastic_bag", "袋子", "🛍", AacCategory.Products)
            )
        },

        // ── 百貨 / 購物中心 / 服飾店 ──
        "百貨" to Rule("在百貨常用：") {
            listOf(
                temp("try_on", "可以試穿嗎", "👕", AacCategory.Actions),
                temp("size_m", "中號", "Ⓜ️", AacCategory.Actions),
                temp("color_other", "別的顏色", "🎨", AacCategory.Actions),
                temp("how_much", "多少錢", "💵", AacCategory.Money),
                temp("ticket_pkg", "禮品包裝", "🎁", AacCategory.Actions)
            )
        },
        "購物中心" to Rule("在購物中心常用：") {
            listOf(
                temp("dining_floor", "美食街在哪", "🍴", AacCategory.Actions),
                temp("rest", "休息一下", "🪑", AacCategory.Actions),
                temp("toilet", "洗手間", "🚻", AacCategory.Actions),
                temp("info", "服務台", "ℹ️", AacCategory.Actions)
            )
        },
        "服飾店" to Rule("在服飾店常用：") {
            listOf(
                temp("try_on", "我想試穿", "👕", AacCategory.Actions),
                temp("size_m", "中號", "Ⓜ️", AacCategory.Actions),
                temp("color_other", "別的顏色", "🎨", AacCategory.Actions),
                temp("how_much", "多少錢", "💵", AacCategory.Money)
            )
        },

        // ── 車站 ──
        "車站" to Rule("在車站常用：") {
            listOf(
                temp("buy_ticket", "買一張票", "🎫", AacCategory.Actions),
                temp("platform", "哪個月台", "🚉", AacCategory.Actions),
                temp("late", "誤點了", "⏰", AacCategory.Actions),
                temp("transfer", "怎麼轉乘", "🔄", AacCategory.Actions),
                temp("toilet", "洗手間", "🚻", AacCategory.Actions),
                temp("thanks", "謝謝", "🙏", AacCategory.Emotions)
            )
        },
        "捷運站" to Rule("在捷運站常用：") {
            listOf(
                temp("easycard", "悠遊卡", "💳", AacCategory.Actions),
                temp("platform", "哪個月台", "🚉", AacCategory.Actions),
                temp("transfer", "轉乘", "🔄", AacCategory.Actions)
            )
        },
        "機場" to Rule("在機場常用：") {
            listOf(
                temp("boarding_pass", "登機證", "🎫", AacCategory.Products),
                temp("gate", "登機門在哪裡", "✈️", AacCategory.Questions),
                temp("baggage", "行李", "🧳", AacCategory.Products),
                temp("translate", "請翻譯", "🌐", AacCategory.Questions),
                temp("help_counter", "服務台", "ℹ️", AacCategory.Places),
                temp("toilet", "洗手間", "🚻", AacCategory.Places),
                temp("need_help", "需要協助", "🤲", AacCategory.Needs)
            )
        },
        "停車場" to Rule("在停車場常用：") {
            listOf(
                temp("find_car", "找車", "🚗", AacCategory.Actions),
                temp("pay_parking", "繳停車費", "💳", AacCategory.Actions),
                temp("exit", "出口在哪裡", "↗️", AacCategory.Questions),
                temp("elevator", "電梯", "🛗", AacCategory.Places),
                temp("help", "需要協助", "🤲", AacCategory.Needs)
            )
        },

        // ── 加油站 ──
        "加油站" to Rule("在加油站常用：") {
            listOf(
                temp("full_tank", "加滿", "⛽", AacCategory.Actions),
                temp("92", "92 無鉛", "🅢", AacCategory.Products),
                temp("95", "95 無鉛", "🅢", AacCategory.Products),
                temp("invoice", "統編", "📃", AacCategory.Actions)
            )
        },

        // ── 銀行 / 郵局 ──
        "銀行" to Rule("在銀行常用：") {
            listOf(
                temp("withdraw", "提款", "💵", AacCategory.Actions),
                temp("deposit", "存款", "💰", AacCategory.Actions),
                temp("passbook", "存摺", "📒", AacCategory.Products),
                temp("transfer", "匯款", "🔄", AacCategory.Actions)
            )
        },
        "郵局" to Rule("在郵局常用：") {
            listOf(
                temp("send", "寄信", "✉️", AacCategory.Actions),
                temp("parcel", "寄包裹", "📦", AacCategory.Actions),
                temp("buy_stamp", "買郵票", "📮", AacCategory.Actions)
            )
        },
        "圖書館" to Rule("在圖書館常用：") {
            listOf(
                temp("quiet", "請小聲一點", "🤫", AacCategory.Actions),
                temp("borrow", "我要借書", "📚", AacCategory.Actions),
                temp("return_book", "我要還書", "📖", AacCategory.Actions),
                temp("where_seat", "座位在哪裡", "🪑", AacCategory.Questions),
                temp("help", "需要協助", "🤲", AacCategory.Needs)
            )
        },
        "旅館" to Rule("在旅館常用：") {
            listOf(
                temp("check_in", "我要入住", "🏨", AacCategory.Actions),
                temp("room_key", "房卡", "🔑", AacCategory.Products),
                temp("call_front", "找櫃台", "☎️", AacCategory.Actions),
                temp("towel", "毛巾", "🧺", AacCategory.Products),
                temp("checkout_time", "退房時間", "🕒", AacCategory.Questions)
            )
        },

        // ── 公園 / 廟宇 ──
        "公園" to Rule("在公園常用：") {
            listOf(
                temp("sit", "坐一下", "🪑", AacCategory.Actions),
                temp("water_cup", "一杯水", "💧", AacCategory.FoodDrink),
                temp("toilet", "洗手間", "🚻", AacCategory.Actions),
                temp("walk", "散步", "🚶", AacCategory.Actions)
            )
        },
        "廟宇" to Rule("在廟裡常用：") {
            listOf(
                temp("pray", "拜拜", "🙏", AacCategory.Actions),
                temp("incense", "香", "🪔", AacCategory.Products),
                temp("donate", "添香油", "💰", AacCategory.Actions),
                temp("peace", "平安", "☯", AacCategory.Emotions)
            )
        },
        "教堂" to Rule("在教堂常用：") {
            listOf(
                temp("sit", "想坐下", "🪑", AacCategory.Needs),
                temp("quiet", "我想安靜一下", "🤫", AacCategory.Emotions),
                temp("water_cup", "一杯水", "💧", AacCategory.FoodDrink),
                temp("help", "需要協助", "🤲", AacCategory.Needs),
                temp("thanks", "謝謝", "🙏", AacCategory.Emotions)
            )
        },

        // ── 住家：問候、需求 ──
        "住家" to Rule("在家裡常用：") {
            listOf(
                temp("water", "我要喝水", "💧", AacCategory.FoodDrink),
                temp("water_cup", "一杯水", "💧", AacCategory.FoodDrink),
                temp("rest", "我要休息", "🛌", AacCategory.Actions),
                temp("toilet", "上廁所", "🚻", AacCategory.Actions),
                temp("med_time", "吃藥時間", "💊", AacCategory.Actions),
                temp("call_doc", "打給醫生", "📞", AacCategory.Emergency),
                temp("not_well", "我不舒服", "🤕", AacCategory.Emergency)
            )
        }
    )
}
