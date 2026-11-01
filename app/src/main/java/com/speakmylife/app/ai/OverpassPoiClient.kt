package com.speakmylife.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenStreetMap Overpass API 客戶端：補 Android 內建 [android.location.Geocoder]
 * 的最大缺點──Geocoder 只回行政地址、不會回店名。
 *
 * Geocoder 結果 vs Overpass 結果（同一個座標）：
 *   Geocoder："臺北市西門里成都路27巷"  ← 沒提到「7-11」
 *   Overpass："7-Eleven" (amenity=convenience)
 *
 * 為什麼不用 Google Places：要 API key、有費用、需要計費綁定。Overpass 完全免費、無 key，
 * 適合本 App 的小流量場景（單次定位、不會狂打 API）。
 *
 * 注意事項：
 * - 公共 endpoint 不適合用在大規模產品，要自架 Overpass 才穩。
 * - 失敗時回 null，呼叫端應 fallback 到 Geocoder + POI_RULES。
 */
object OverpassPoiClient {

    // 三家 Overpass mirror 輪替，公開主機常 504 / 429，輪替能大幅提升成功率
    private val ENDPOINTS = listOf(
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.osm.ch/api/interpreter"
    )
    // 西門町這種密集街區，90 m 半徑會回 8+ 個店，挑「第一個」就會抓到 H&M 而不是隔壁的麥當勞。
    // 縮小到 40 m + 客戶端按 haversine 距離排序，命中率才會準。
    private const val SEARCH_RADIUS_M = 40

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 簡易 in-memory cache：座標四捨五入到 4 位小數（~11 m 精度），
     * 一次成功後同一格 10 分鐘內不會再發網路請求。
     */
    private data class CachedEntry(val result: Pair<String, String>?, val ts: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedEntry>()
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** 查最近的 POI。回傳：(中文分類, 店名)；找不到回 null。 */
    suspend fun findNearestPoi(lat: Double, lon: Double): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val key = "%.4f,%.4f".format(lat, lon)
            cache[key]?.let { e ->
                if (System.currentTimeMillis() - e.ts < CACHE_TTL_MS) return@withContext e.result
            }
            // 依序嘗試三家 mirror；任一成功就結束
            for (endpoint in ENDPOINTS) {
                val result = queryOnce(endpoint, lat, lon, SEARCH_RADIUS_M)
                if (result != null) {
                    cache[key] = CachedEntry(result, System.currentTimeMillis())
                    return@withContext result
                }
            }
            cache[key] = CachedEntry(null, System.currentTimeMillis())
            null
        }

    private fun queryOnce(endpoint: String, lat: Double, lon: Double, radius: Int): Pair<String, String>? {
        // Overpass QL：在 radius 公尺內找有 amenity / shop / leisure / tourism / healthcare 的 node 或 way。
        // 用 `out center` 才會帶回經緯度，client 才能按距離排序。
        // 上限 30 筆：密集街區可能 ≥10 筆，多撈幾筆才能挑到真正最近的那家。
        val query = """
            [out:json][timeout:10];
            (
              nwr(around:$radius,$lat,$lon)[amenity];
              nwr(around:$radius,$lat,$lon)[shop];
              nwr(around:$radius,$lat,$lon)[leisure];
              nwr(around:$radius,$lat,$lon)[tourism];
              nwr(around:$radius,$lat,$lon)[healthcare];
            );
            out tags center 30;
        """.trimIndent()

        // Overpass API 接受 `application/x-www-form-urlencoded` 的 POST，
        // 或 GET ?data=...。用 POST 避免長 query 撞到 URL 長度上限。
        val body = ("data=" + java.net.URLEncoder.encode(query, "UTF-8"))
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val req = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "SpeakMyLife/1.0 (Android)")
            .post(body)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Overpass HTTP ${resp.code} ($endpoint)"); return null
                }
                val text = resp.body?.string() ?: return null
                val parsed = json.decodeFromString<OverpassResponse>(text)
                pickClosestSpecific(parsed.elements, lat, lon)?.also {
                    Log.i(TAG, "Overpass HIT $endpoint → ${it.first}·${it.second}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overpass query failed: ${e.message?.take(120)}")
            null
        }
    }

    /**
     * 從候選裡挑「**離查詢點最近**且有具體類別」的一筆。
     *
     * 改進原因：舊版 `pickBest` 只挑「第一個」有名字的，於是在西門町 90 m 半徑內，
     * Overpass 回傳順序是 by tile，會抓到 H&M 而不是隔壁的麥當勞。
     * 新版排序鍵：(沒名字或無類別 → 扣分) → 距離（公尺）升冪。
     */
    private fun pickClosestSpecific(
        elements: List<OverpassElement>,
        queryLat: Double,
        queryLon: Double
    ): Pair<String, String>? {
        if (elements.isEmpty()) return null
        data class Candidate(val element: OverpassElement, val distanceM: Double, val hasName: Boolean, val hasCategory: Boolean)
        val candidates = elements.mapNotNull { e ->
            val (eLat, eLon) = elementLatLon(e) ?: return@mapNotNull null
            val t = e.tags ?: return@mapNotNull null
            val hasName = !t["name:zh"].isNullOrBlank() || !t["name"].isNullOrBlank()
            val hasCategory = !t["amenity"].isNullOrBlank() || !t["shop"].isNullOrBlank() ||
                              !t["leisure"].isNullOrBlank() || !t["tourism"].isNullOrBlank() ||
                              !t["healthcare"].isNullOrBlank()
            if (!hasName && !hasCategory) return@mapNotNull null
            Candidate(e, haversine(queryLat, queryLon, eLat, eLon), hasName, hasCategory)
        }
        // 優先有名字 + 有類別、其次有名字、再次有類別；同層級內按距離升冪
        val best = candidates.sortedWith(
            compareByDescending<Candidate> { it.hasName && it.hasCategory }
                .thenByDescending { it.hasName }
                .thenBy { it.distanceM }
        ).firstOrNull() ?: return null
        return toCategoryName(best.element)
    }

    /** node 直接有 lat/lon；way / relation 需要 `out center` 提供的 [OverpassElement.center]。 */
    private fun elementLatLon(e: OverpassElement): Pair<Double, Double>? {
        val lat = e.lat ?: e.center?.lat
        val lon = e.lon ?: e.center?.lon
        return if (lat != null && lon != null) lat to lon else null
    }

    /** 公尺，誤差遠小於 40 m 半徑，夠用了。 */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun toCategoryName(element: OverpassElement): Pair<String, String>? {
        val tags = element.tags ?: return null
        val name = tags["name:zh"] ?: tags["name"] ?: ""
        val categoryKey = tags["amenity"] ?: tags["shop"] ?: tags["leisure"]
            ?: tags["tourism"] ?: tags["healthcare"] ?: ""
        return osmTagToChineseCategory(categoryKey, name) to name
    }

    /**
     * OSM 標籤 → 中文類別。
     *
     * 設計原則：
     *  - 表內命中：直接回中文（最好結果）
     *  - 表外命中：回「OSM 原 tag → 機械翻譯」（保證使用者能知道是哪一類店，而不是空泛「店家」）
     *  - 連 tag 都空：才看店名找連鎖店線索；都沒有就回「店家」
     *
     * 來源：OSM wiki 的 Key:amenity + Key:shop（涵蓋台灣常見幾乎所有店面類型）。
     */
    private fun osmTagToChineseCategory(tag: String, name: String): String {
        val byTag = AMENITY_OR_SHOP_MAP[tag]
        if (byTag != null) return byTag

        // 連鎖店店名線索（OSM 漏標 shop= 時用）
        val n = name.lowercase()
        val byName = when {
            "7-eleven" in n || "7-11" in n || "circle k" in n || "speedway" in n ||
                "wawa" in n || "sheetz" in n || "quiktrip" in n || "kwik trip" in n ||
                "全家" in name || "萊爾富" in name || "ok mart" in n -> "便利商店"
            "cvs" in n || "walgreens" in n || "rite aid" in n || "duane reade" in n -> "藥局"
            "walmart" in n || "target" in n || "kroger" in n || "safeway" in n ||
                "whole foods" in n || "trader joe" in n || "publix" in n || "aldi" in n ||
                "全聯" in name || "家樂福" in name || "好市多" in name || "costco" in n -> "超市"
            "mcdonald" in n || "kfc" in n || "burger king" in n || "taco bell" in n ||
                "wendy" in n || "chick-fil-a" in n || "chipotle" in n || "in-n-out" in n ||
                "麥當勞" in name || "肯德基" in name || "摩斯" in name || "漢堡王" in name -> "速食店"
            "starbucks" in n || "dunkin" in n || "peet" in n || "blue bottle" in n ||
                "星巴克" in name || "路易莎" in name || "85度" in name || "cama" in n -> "咖啡店"
            "誠品" in name || "新光三越" in name || "sogo" in n || "百貨" in name         -> "百貨"
            "捷運" in name || "車站" in name || "station" in n                              -> "車站"
            else -> null
        }
        if (byName != null) return byName

        // 表外但有 tag → 直接顯示 tag 字面值轉中文（avoid 空泛「店家」）
        if (tag.isNotBlank()) return tag.replace('_', ' ').replaceFirstChar { it.uppercase() }
        return "店家"
    }

    /**
     * OSM amenity / shop / leisure / tourism / healthcare 標籤對應中文類別。
     * 來源：[OSM wiki](https://wiki.openstreetmap.org/wiki/Key:shop) 與 [Key:amenity]。
     * 涵蓋台灣使用者實際會遇到的幾乎所有店面類型。
     */
    private val AMENITY_OR_SHOP_MAP: Map<String, String> = mapOf(
        // ── 醫療 (amenity / healthcare) ─────────────────────────────
        "hospital" to "醫院", "clinic" to "診所", "doctors" to "診所",
        "dentist" to "牙科", "pharmacy" to "藥局", "veterinary" to "獸醫",
        "physiotherapist" to "復健科", "optometrist" to "眼鏡行",
        "alternative" to "中醫診所",

        // ── 教育 (amenity) ──────────────────────────────────────────
        "school" to "學校", "kindergarten" to "幼兒園", "college" to "學院",
        "university" to "大學", "library" to "圖書館", "language_school" to "語言補習班",
        "music_school" to "音樂教室", "driving_school" to "駕訓班",

        // ── 餐飲 (amenity) ──────────────────────────────────────────
        "restaurant" to "餐廳", "food_court" to "美食街", "fast_food" to "速食店",
        "cafe" to "咖啡店", "bar" to "酒吧", "pub" to "酒吧",
        "ice_cream" to "冰品店", "biergarten" to "啤酒屋",

        // ── 金融 / 政務 ─────────────────────────────────────────────
        "bank" to "銀行", "atm" to "ATM", "post_office" to "郵局",
        "post_box" to "郵筒", "police" to "警察局", "fire_station" to "消防隊",
        "townhall" to "市政府", "courthouse" to "法院",

        // ── 交通 ──────────────────────────────────────────────────
        "bus_station" to "車站", "fuel" to "加油站", "charging_station" to "充電站",
        "parking" to "停車場", "car_wash" to "洗車場", "taxi" to "計程車招呼站",
        "ferry_terminal" to "渡輪碼頭",

        // ── 公共 / 信仰 / 娛樂 ──────────────────────────────────────
        "place_of_worship" to "廟宇", "marketplace" to "市場",
        "cinema" to "電影院", "theatre" to "劇院", "nightclub" to "夜店",
        "community_centre" to "社區中心", "toilets" to "公廁", "shelter" to "庇護所",
        "drinking_water" to "飲水站", "bench" to "公共座椅",
        "information" to "資訊站", "telephone" to "電話亭",
        "waste_basket" to "垃圾桶", "recycling" to "回收站",
        "social_facility" to "社福機構", "events_venue" to "活動會場",

        // ── 旅館 / 觀光 (tourism) ───────────────────────────────────
        "hotel" to "旅館", "motel" to "汽車旅館", "hostel" to "青年旅館",
        "guest_house" to "民宿", "museum" to "博物館", "gallery" to "藝廊",
        "attraction" to "景點", "viewpoint" to "觀景點", "zoo" to "動物園",
        "theme_park" to "主題樂園", "aquarium" to "水族館",

        // ── 休閒 (leisure) ──────────────────────────────────────────
        "park" to "公園", "playground" to "兒童遊戲場",
        "fitness_centre" to "健身房", "sports_centre" to "運動中心",
        "stadium" to "體育場", "swimming_pool" to "游泳池", "pitch" to "球場",
        "golf_course" to "高爾夫球場", "marina" to "碼頭",

        // ── 便利 / 量販 / 超市 (shop) ───────────────────────────────
        "convenience" to "便利商店", "supermarket" to "超市", "wholesale" to "量販店",
        "department_store" to "百貨", "mall" to "購物中心",
        "kiosk" to "小賣店", "general" to "雜貨店",

        // ── 食品 (shop) ─────────────────────────────────────────────
        "bakery" to "麵包店", "pastry" to "糕餅店", "confectionery" to "糖果店",
        "butcher" to "肉品店", "seafood" to "海鮮店", "cheese" to "起司店",
        "deli" to "熟食店", "greengrocer" to "蔬果店", "beverages" to "飲料店",
        "alcohol" to "酒類專賣店", "tea" to "茶葉店", "coffee" to "咖啡豆店",
        "chocolate" to "巧克力店", "dairy" to "乳品店", "spices" to "香料店",
        "health_food" to "健康食品店",

        // ── 服飾 / 美容 (shop) ──────────────────────────────────────
        "clothes" to "服飾店", "shoes" to "鞋店", "bag" to "包包店",
        "jewelry" to "珠寶店", "watches" to "鐘錶店", "tailor" to "裁縫店",
        "fabric" to "布料店", "hairdresser" to "美髮", "beauty" to "美容院",
        "cosmetics" to "化妝品店", "perfumery" to "香水店", "tattoo" to "刺青店",

        // ── 3C / 電器 (shop) ───────────────────────────────────────
        "electronics" to "電子產品店", "mobile_phone" to "手機店",
        "computer" to "電腦店", "hifi" to "音響店", "video" to "影音店",
        "video_games" to "電玩店", "appliance" to "家電行",

        // ── 居家 / 五金 (shop) ──────────────────────────────────────
        "hardware" to "五金行", "doityourself" to "DIY 居家用品店",
        "furniture" to "家具店", "kitchen" to "廚具店",
        "interior_decoration" to "室內裝潢", "houseware" to "居家用品店",
        "bathroom_furnishing" to "衛浴設備店", "lighting" to "燈具店",
        "florist" to "花店", "garden_centre" to "園藝店",

        // ── 文具 / 書籍 / 興趣 (shop) ──────────────────────────────
        "books" to "書店", "stationery" to "文具店",
        "music" to "唱片行", "musical_instrument" to "樂器行",
        "art" to "藝品店", "craft" to "手作店",
        "toys" to "玩具店", "gift" to "禮品店", "pet" to "寵物店",
        "hobby" to "嗜好用品店", "model" to "模型店",

        // ── 運動 / 戶外 (shop) ──────────────────────────────────────
        "sports" to "運動用品店", "bicycle" to "自行車店", "outdoor" to "戶外用品店",
        "fishing" to "釣具店", "hunting" to "狩獵用品店",

        // ── 汽機車 (shop) ───────────────────────────────────────────
        "car" to "汽車經銷商", "car_repair" to "汽車維修", "car_parts" to "汽車零件",
        "motorcycle" to "機車行", "tyres" to "輪胎行",

        // ── 醫療 / 視力 (shop) ─────────────────────────────────────
        "optician" to "眼鏡行", "medical_supply" to "醫療用品店",
        "hearing_aids" to "助聽器店", "massage" to "按摩店",

        // ── 服務 / 其他 (shop / amenity) ────────────────────────────
        "laundry" to "洗衣店", "dry_cleaning" to "乾洗店",
        "copyshop" to "影印店", "printer" to "印表店",
        "tobacco" to "菸酒店", "lottery" to "彩券行",
        "estate_agent" to "房仲", "insurance" to "保險公司",
        "travel_agency" to "旅行社", "pawnbroker" to "當鋪",
        "funeral_directors" to "葬儀社", "storage_rental" to "倉儲出租",
        "vending_machine" to "自動販賣機"
    )

    private const val TAG = "OverpassPoi"
}

@Serializable
private data class OverpassResponse(
    @SerialName("elements") val elements: List<OverpassElement> = emptyList()
)

@Serializable
private data class OverpassElement(
    @SerialName("type") val type: String? = null,
    @SerialName("id") val id: Long? = null,
    @SerialName("lat") val lat: Double? = null,    // node 直接有
    @SerialName("lon") val lon: Double? = null,
    @SerialName("center") val center: OverpassCenter? = null,  // way / relation 需要 `out center`
    @SerialName("tags") val tags: Map<String, String>? = null
)

@Serializable
private data class OverpassCenter(
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double
)
