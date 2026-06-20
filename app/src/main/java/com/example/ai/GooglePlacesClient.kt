package com.example.ai

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Google Places Nearby Search 客戶端。優先於 [OverpassPoiClient]：
 *  - 商業級 SLA，台灣連線 < 500 ms（vs Overpass 公共伺服器常 504）
 *  - 繁中名稱完整（OSM 在台灣常缺 `name:zh`）
 *  - 收錄包含**所有商業 POI**：連鎖店、本土店、住址型店、政府單位
 *
 * 啟用步驟（一次設定）：
 *  1. 到 https://console.cloud.google.com → APIs & Services → Library
 *  2. 啟用 **Places API**（注意不是 Places API (New)，後者 endpoint 不同）
 *  3. 拿 API key 填進 `.env` 的 `GOOGLE_PLACES_API_KEY=`
 *  4. （重要）API key 加上限制：HTTP referrers 不適用 Android；用 **Android apps** 限制
 *     並貼 App 的 SHA-1 + package name `com.aistudio.aphasiaassist.pkztuv`
 *
 * 計費：前 200 美元 / 月免費（Google 給 GCP 帳號每月免費額度），
 * Nearby Search 單價 $0.032 / 次 → 200/0.032 ≈ 6250 次/月免費。AAC 場景大概打不到。
 */
object GooglePlacesClient {

    private const val BASE = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
    private const val RADIUS_M = 40

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class CachedEntry(val result: Pair<String, String>?, val ts: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedEntry>()
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** @return null 表示「key 未設定」或「API 失敗」；應 fallback 到 Overpass 或 Geocoder。 */
    suspend fun findNearestPoi(
        lat: Double,
        lon: Double,
        language: String = "zh-TW"
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            // 使用者在 API 金鑰管理填的優先，空才退回 BuildConfig（.env）
            val key = com.example.data.ApiKeyOverrides.resolve(
                com.example.data.ApiKeyOverrides.googlePlaces,
                runCatching { BuildConfig.GOOGLE_PLACES_API_KEY }.getOrNull().orEmpty()
            )
            // secrets plugin 對 .env 空值會生壞 Java，所以用 placeholder「YOUR_*」標示「沒設定」
            if (key.isBlank() || key.startsWith("YOUR_")) return@withContext null

            val cacheKey = "%.4f,%.4f,%s".format(lat, lon, language)
            cache[cacheKey]?.let { e ->
                if (System.currentTimeMillis() - e.ts < CACHE_TTL_MS) return@withContext e.result
            }

            // `rankby=distance` 會自動按距離排序，**但與 radius 互斥**；
            // 我們選 radius 模式 + 客戶端 implicit 排序（API 預設用 prominence）。
            // 為了「最近優先」：拿前 5 名後用 Google 給的 geometry.location 排距離。
            val url = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("location", "$lat,$lon")
                .addQueryParameter("radius", RADIUS_M.toString())
                .addQueryParameter("language", language)
                .addQueryParameter("key", key)
                .build()

            val req = Request.Builder().url(url).build()
            val result = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Places HTTP ${resp.code}"); return@runCatching null
                    }
                    val text = resp.body?.string() ?: return@runCatching null
                    val parsed = json.decodeFromString<PlacesResponse>(text)
                    when (parsed.status) {
                        "OK" -> Unit
                        "ZERO_RESULTS" -> return@runCatching null
                        else -> {
                            Log.w(TAG, "Places status=${parsed.status} ${parsed.errorMessage.orEmpty()}")
                            return@runCatching null
                        }
                    }
                    pickClosest(parsed.results, lat, lon)?.also {
                        Log.i(TAG, "Places HIT → ${it.first}·${it.second}")
                    }
                }
            }.getOrElse {
                Log.w(TAG, "Places call failed: ${it.message?.take(120)}"); null
            }

            cache[cacheKey] = CachedEntry(result, System.currentTimeMillis())
            result
        }

    private fun pickClosest(places: List<PlaceItem>, lat: Double, lon: Double): Pair<String, String>? {
        if (places.isEmpty()) return null
        val best = places.mapNotNull { pl ->
            val ploc = pl.geometry?.location ?: return@mapNotNull null
            val d = haversine(lat, lon, ploc.lat, ploc.lng)
            Triple(pl, d, pl.name.orEmpty().isNotBlank())
        }.sortedWith(compareByDescending<Triple<PlaceItem, Double, Boolean>> { it.third }.thenBy { it.second })
            .firstOrNull()?.first ?: return null

        val name = best.name.orEmpty()
        val category = googleTypesToChinese(best.types ?: emptyList(), name)
        return category to name
    }

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

    /**
     * Google Places type → 中文類別。types 是陣列，按優先序找第一個有對映的。
     * 沒對映就用第一個 type 機械轉中文（避免「店家」空殼）。
     *
     * 完整 types 清單：https://developers.google.com/maps/documentation/places/web-service/supported_types
     */
    private fun googleTypesToChinese(types: List<String>, name: String): String {
        for (t in types) GOOGLE_TYPE_MAP[t]?.let { return it }
        // 連鎖店名 fallback
        val n = name.lowercase()
        return when {
            "7-eleven" in n || "7-11" in n || "circle k" in n || "speedway" in n ||
                "wawa" in n || "sheetz" in n || "quiktrip" in n || "kwik trip" in n ||
                "全家" in name || "萊爾富" in name -> "便利商店"
            "cvs" in n || "walgreens" in n || "rite aid" in n || "duane reade" in n -> "藥局"
            "walmart" in n || "target" in n || "kroger" in n || "safeway" in n ||
                "whole foods" in n || "trader joe" in n || "publix" in n || "aldi" in n ||
                "costco" in n || "全聯" in name || "家樂福" in name || "好市多" in name -> "超市"
            "mcdonald" in n || "kfc" in n || "burger king" in n || "taco bell" in n ||
                "wendy" in n || "chick-fil-a" in n || "chipotle" in n || "in-n-out" in n ||
                "麥當勞" in name || "肯德基" in name || "摩斯" in name || "漢堡王" in name -> "速食店"
            "starbucks" in n || "dunkin" in n || "peet" in n || "blue bottle" in n ||
                "星巴克" in name || "路易莎" in name || "85度" in name -> "咖啡店"
            else -> types.firstOrNull()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "店家"
        }
    }

    /** Google Places types → 中文。涵蓋 95% 台灣使用者會遇到的場景。 */
    private val GOOGLE_TYPE_MAP: Map<String, String> = mapOf(
        // 醫療
        "hospital" to "醫院", "doctor" to "診所", "dentist" to "牙科",
        "pharmacy" to "藥局", "drugstore" to "藥妝店", "veterinary_care" to "獸醫",
        "physiotherapist" to "復健科",
        // 教育
        "school" to "學校", "primary_school" to "國小", "secondary_school" to "中學",
        "university" to "大學", "library" to "圖書館", "book_store" to "書店",
        // 餐飲
        "restaurant" to "餐廳", "meal_takeaway" to "外帶店", "meal_delivery" to "外送店",
        "cafe" to "咖啡店", "bakery" to "麵包店", "bar" to "酒吧",
        "night_club" to "夜店", "food" to "餐廳",
        // 便利 / 量販 / 百貨
        "convenience_store" to "便利商店", "supermarket" to "超市",
        "grocery_or_supermarket" to "超市", "department_store" to "百貨",
        "shopping_mall" to "購物中心", "clothing_store" to "服飾店",
        "shoe_store" to "鞋店", "jewelry_store" to "珠寶店",
        // 3C / 電器
        "electronics_store" to "電子產品店", "home_goods_store" to "家居用品店",
        "hardware_store" to "五金行", "furniture_store" to "家具店",
        // 服務
        "beauty_salon" to "美容院", "hair_care" to "美髮", "spa" to "SPA",
        "laundry" to "洗衣店", "post_office" to "郵局",
        // 金融
        "bank" to "銀行", "atm" to "ATM", "accounting" to "會計事務所",
        "insurance_agency" to "保險公司",
        // 交通
        "transit_station" to "車站", "subway_station" to "捷運站",
        "train_station" to "火車站", "bus_station" to "公車站",
        "airport" to "機場", "gas_station" to "加油站",
        "parking" to "停車場", "taxi_stand" to "計程車招呼站",
        "car_rental" to "汽車租賃", "car_dealer" to "汽車經銷商",
        "car_repair" to "汽車維修", "car_wash" to "洗車場",
        // 信仰 / 公共
        "church" to "教堂", "mosque" to "清真寺", "hindu_temple" to "印度廟",
        "synagogue" to "猶太教堂", "place_of_worship" to "廟宇",
        "city_hall" to "市政府", "courthouse" to "法院", "embassy" to "大使館",
        "fire_station" to "消防隊", "police" to "警察局",
        "local_government_office" to "公家機關",
        // 旅館 / 觀光
        "lodging" to "旅館", "tourist_attraction" to "景點",
        "museum" to "博物館", "art_gallery" to "藝廊", "amusement_park" to "遊樂園",
        "aquarium" to "水族館", "zoo" to "動物園",
        // 休閒
        "park" to "公園", "gym" to "健身房", "stadium" to "體育場",
        "movie_theater" to "電影院", "bowling_alley" to "保齡球館",
        // 寵物 / 嗜好
        "pet_store" to "寵物店", "florist" to "花店",
        "bicycle_store" to "自行車店", "liquor_store" to "酒類專賣店",
        // 通用 fallback
        "store" to "商店", "point_of_interest" to "景點"
    )

    private const val TAG = "GooglePlaces"
}

@Serializable
private data class PlacesResponse(
    @SerialName("status") val status: String = "",
    @SerialName("results") val results: List<PlaceItem> = emptyList(),
    @SerialName("error_message") val errorMessage: String? = null
)

@Serializable
private data class PlaceItem(
    @SerialName("name") val name: String? = null,
    @SerialName("types") val types: List<String>? = null,
    @SerialName("vicinity") val vicinity: String? = null,
    @SerialName("geometry") val geometry: PlaceGeometry? = null
)

@Serializable
private data class PlaceGeometry(@SerialName("location") val location: PlaceLatLng? = null)

@Serializable
private data class PlaceLatLng(
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double
)
