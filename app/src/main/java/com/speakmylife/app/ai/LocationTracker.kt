package com.speakmylife.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val address: String,       // 完整地址字串（供 UI 顯示）
    val shortTag: String       // 本地 POI/場景分類（注入 GlobalTagsVault.locationTag）
)

/**
 * 遵循 Just-in-Time Single-Shot 原則的 GPS 定位器。
 * 分兩階段：快取讀取（省電）→ 新鮮定位（快取過期時）→ Geocoder 反向解碼。
 *
 * Android 14+ 使用異步 Geocoder.getFromLocation callback API，
 * 避免 deprecated 的同步版本阻塞執行緒（雖然我們在 IO，但仍遵循最佳實踐）。
 */
/**
 * 「住家」判定條件。靠儲存的 lat/lon + 半徑（公尺），與 Geocoder 回傳的關鍵字無關。
 * 為什麼這樣設計：之前用「巷」「弄」當 keyword 會把任何台北住宅街都判成住家——
 * 那是分類錯誤而不是匹配成功。住家必須以**使用者授權的真實坐標**為唯一根據。
 */
data class HomeAnchor(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float
)

class LocationTracker(
    private val context: Context,
    /** 由 ViewModel 在每次取樣前提供最新值；返回 null 表示「沒設定住家」。 */
    private val homeProvider: () -> HomeAnchor? = { null }
) {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    /** 離線 POI 索引：bundled in APK，永遠免費、無 API、無用量限制。 */
    private val offlinePoi: OfflinePoiIndex by lazy { OfflinePoiIndex(context) }

    /**
     * 最後一次判定出來的國別（ISO 3166-1 alpha-2）。
     *
     * 危機視窗要**立刻**畫出求救按鈕，不能卡在等 GPS 定位（冷啟動動輒數秒，
     * 而那正是最不能等的時刻）。所以視窗先用這個快取值畫，GPS 回來再更新。
     * 平常的定位取樣會順手把它填好，多半在危機發生前就已經有值了。
     */
    @Volatile
    var lastKnownCountryCode: String? = null
        private set

    /**
     * 只為了拿國別的一次定位。給危機專線挑號碼用（見 `CrisisHotlineDirectory`）。
     *
     * 與 [getCurrentLocation] 的差別：不做 POI 比對、不管精度門檻。國別只需要
     * 「在哪個國家」這種公里級的答案，用街區級的定位綽綽有餘——為了選對求救號碼
     * 去等一個高精度定位是本末倒置。
     *
     * @return ISO 3166-1 alpha-2，判不出來時 null（呼叫端要有退路）。
     */
    suspend fun resolveCountryCode(): String? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "沒有定位權限，無法判定國別")
            return@withContext lastKnownCountryCode
        }
        val loc = try {
            fetchLocation()
        } catch (e: Exception) {
            Log.w(TAG, "國別定位失敗", e)
            null
        } ?: return@withContext lastKnownCountryCode

        val cc = countryCodeFor(loc.latitude, loc.longitude)
        if (cc != null) {
            lastKnownCountryCode = cc
            com.speakmylife.app.safety.CrisisHotlineDirectory.rememberCountry(cc)
        }
        cc ?: lastKnownCountryCode
    }

    /**
     * 經緯度 → 國別。Geocoder 優先（準），失敗才用邊界框（離線也能動）。
     *
     * 邊界框只涵蓋這個 App 有介面語言的四個國家，而且刻意寫得保守——寧可回 null
     * 讓上層退回介面語言，也不要把邊界附近的人判到隔壁國家去。
     */
    private suspend fun countryCodeFor(lat: Double, lon: Double): String? {
        geocodeCountry(lat, lon)?.let { return it }
        Log.w(TAG, "Geocoder 判不出國別，改用邊界框")
        return boundingBoxCountry(lat, lon)
    }

    private suspend fun geocodeCountry(lat: Double, lon: Double): String? = runCatching {
        val geocoder = Geocoder(context, Locale.US)
        val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1) { list -> cont.resume(list) }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1) ?: emptyList()
        }
        addresses.firstOrNull()?.countryCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
    }.getOrNull()

    // 順序有意義：韓國整個落在日本那個框裡面，KR 一定要排在 JP 前面，
    // 否則首爾會被判成日本、撥到日本的專線。
    private fun boundingBoxCountry(lat: Double, lon: Double): String? = when {
        isUnitedStatesCoordinate(lat, lon) -> "US"
        lat in 21.5..25.5 && lon in 119.5..122.2 -> "TW"
        lat in 33.0..38.7 && lon in 125.5..129.8 -> "KR"
        lat in 24.0..46.0 && lon in 122.5..146.5 -> "JP"
        else -> null
    }

    suspend fun getCurrentLocation(): LocationResult? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted")
            return@withContext null
        }

        try {
            val location = fetchLocation() ?: return@withContext null
            val accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
            val geo = reverseGeocode(location.latitude, location.longitude, accuracyM)
            Log.i(TAG, "Location resolved: ${geo.address} (${location.latitude}, ${location.longitude}, ±${accuracyM}m)")
            geo
        } catch (e: Exception) {
            Log.e(TAG, "Location flow failed", e)
            null
        }
    }

    // ─── 定位取得 ────────────────────────────────────────────────────────────

    private suspend fun fetchLocation(): Location? {
        val cached = try {
            fusedLocationClient.lastLocation.awaitTask()
        } catch (e: Exception) {
            Log.w(TAG, "lastLocation failed", e)
            null
        }

        if (cached != null && isFresh(cached)) {
            Log.d(TAG, "Using cached location (age: ${System.currentTimeMillis() - cached.time} ms)")
            return cached
        }

        Log.d(TAG, "Cache stale/empty, requesting fresh location")
        return try {
            val cts = CancellationTokenSource()
            // 高精度：病人講的是「我在哪裡」，講錯地點比多耗一點電嚴重得多。
            // BALANCED 只到街區級（動輒上百公尺誤差），會把隔壁棟的店名講成現在位置。
            val req = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(FRESH_WINDOW_MS)
                .setDurationMillis(LOCATION_TIMEOUT_MS)
                .build()
            fusedLocationClient.getCurrentLocation(req, cts.token).awaitTask()
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentLocation failed", e)
            null
        }
    }

    // ─── 反向地理編碼 ─────────────────────────────────────────────────────────

    /**
     * 公開的反向地理編碼入口。給「模擬定位」開發者測試用：直接傳入經緯度，
     * 不走 FusedLocationClient，照樣跑 Geocoder + classifyPoiTag()。
     */
    suspend fun simulateGeocode(lat: Double, lon: Double): LocationResult =
        reverseGeocode(lat, lon, accuracyM = 0f)

    private suspend fun reverseGeocode(lat: Double, lon: Double, accuracyM: Float): LocationResult =
        withContext(Dispatchers.IO) {
            val geocoder = Geocoder(context, preferredGeocoderLocale(lat, lon))

            val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 異步 API（非 deprecated）
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1) { list ->
                        cont.resume(list)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1) ?: emptyList()
            }

            buildLocationResult(lat, lon, addresses.firstOrNull(), accuracyM)
        }

    private suspend fun buildLocationResult(
        lat: Double,
        lon: Double,
        addr: Address?,
        accuracyM: Float
    ): LocationResult {
        // 順手把國別快取起來。危機視窗開的時候不會有時間等 GPS，靠的就是這個值。
        // 一定要留紀錄：求救號碼是照這個值挑的，事後若有人問「為什麼撥的是這支」，
        // 沒有這行就查不出來。
        (addr?.countryCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
            ?: boundingBoxCountry(lat, lon))
            ?.let {
                if (it != lastKnownCountryCode) Log.i(TAG, "國別判定：$it（求救專線會依此挑號碼）")
                lastKnownCountryCode = it
                com.speakmylife.app.safety.CrisisHotlineDirectory.rememberCountry(it)
            }

        // ① 住家最優先：純距離判定，與 Geocoder / Overpass 都無關
        val home = homeProvider()
        val homeHit = home != null && distanceMeters(lat, lon, home.latitude, home.longitude) <= home.radiusMeters

        val fullAddress = formatAddress(addr, lat, lon)
        val inUnitedStates = isUnitedStates(lat, lon, addr)

        if (homeHit) return LocationResult(lat, lon, fullAddress, "住家")

        // 誤差太大時不報「某某店名」——POI 比對半徑只有 35 公尺，
        // 定位誤差 100 公尺時挑出來的店家跟實際位置沒有關聯，等於瞎猜。
        // 這種情況只回大範圍地址（區/路），讓句子講「我在信義路附近」而不是講錯店名。
        if (accuracyM > POI_TRUSTWORTHY_ACCURACY_M) {
            Log.w(TAG, "定位誤差 ±${accuracyM}m 過大，略過 POI 比對，只用概略地址")
            val coarse = listOfNotNull(
                addr?.subLocality?.takeIf { it.isNotBlank() },
                addr?.locality?.takeIf { it.isNotBlank() },
                addr?.thoroughfare?.takeIf { it.isNotBlank() }
            ).firstOrNull()
            // 「附近」要走翻譯，不能直接串中文——英文介面會變成 "Main Street 附近"。
            return LocationResult(lat, lon, fullAddress,
                coarse?.let { com.speakmylife.app.ui.I18n.tr("%1\$s 附近").format(it) } ?: fullAddress)
        }

        // ② **離線 POI**（首選）：bundled 在 APK，零網路、零 API key、無用量限制
        val offline = offlinePoi.findNearestPoi(
            lat = lat,
            lon = lon,
            radiusM = if (inUnitedStates) US_OFFLINE_POI_RADIUS_M else DEFAULT_OFFLINE_POI_RADIUS_M
        )
        if (offline != null) {
            val (category, name) = offline
            val tag = if (name.isBlank() || name == category) category else "$category·$name"
            return LocationResult(lat, lon, fullAddress.ifBlank { name }, tag)
        }

        // ③ Google Places（雲端，有 key 時用；無 key 自動 skip）
        val places = GooglePlacesClient.findNearestPoi(
            lat = lat,
            lon = lon,
            language = if (inUnitedStates) "en" else "zh-TW"
        )
        if (places != null) {
            val (category, name) = places
            val tag = if (name.isBlank() || name == category) category else "$category·$name"
            return LocationResult(lat, lon, fullAddress.ifBlank { name }, tag)
        }

        // ④ Overpass（OSM 公開伺服器）：最後 fallback，不穩但偶爾有用
        val overpass = OverpassPoiClient.findNearestPoi(lat, lon)
        if (overpass != null) {
            val (category, name) = overpass
            val tag = if (name.isBlank() || name == category) category else "$category·$name"
            return LocationResult(lat, lon, fullAddress.ifBlank { name }, tag)
        }

        // ③ Geocoder 文字 + 內建 POI keyword fallback（用於沒網或 Overpass 掛了的情況）
        if (addr != null) {
            val classified = classifyPoiTag(addr, fullAddress)
            val fallbackTag = listOfNotNull(
                addr.featureName?.takeIf { it != addr.subThoroughfare },
                addr.premises,
                addr.thoroughfare
            ).filter { it.isNotBlank() }.firstOrNull().orEmpty().ifBlank { fullAddress }
            return LocationResult(lat, lon, fullAddress, classified.ifBlank { fallbackTag })
        }
        return LocationResult(lat, lon, fullAddress, fullAddress)
    }

    private fun preferredGeocoderLocale(lat: Double, lon: Double): Locale =
        if (isUnitedStatesCoordinate(lat, lon)) Locale.US else Locale.getDefault()

    private fun formatAddress(addr: Address?, lat: Double, lon: Double): String {
        if (addr == null) return "%.4f, %.4f".format(lat, lon)
        return if (isUnitedStates(lat, lon, addr)) {
            val street = listOfNotNull(addr.subThoroughfare, addr.thoroughfare)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            listOfNotNull(
                addr.featureName?.takeIf { it.isNotBlank() && it != addr.subThoroughfare },
                street.ifBlank { null },
                addr.locality?.takeIf { it.isNotBlank() },
                addr.adminArea?.takeIf { it.isNotBlank() },
                addr.postalCode?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(", ").ifBlank { "%.4f, %.4f".format(lat, lon) }
        } else {
            val parts = listOfNotNull(
                addr.premises,
                addr.featureName?.takeIf { it != addr.subThoroughfare },
                addr.adminArea,
                addr.locality,
                addr.subLocality,
                addr.thoroughfare
            ).filter { it.isNotBlank() }.distinct()
            parts.joinToString("").ifBlank { "%.4f, %.4f".format(lat, lon) }
        }
    }

    private fun isUnitedStates(lat: Double, lon: Double, addr: Address?): Boolean =
        addr?.countryCode.equals("US", ignoreCase = true) || isUnitedStatesCoordinate(lat, lon)

    private fun isUnitedStatesCoordinate(lat: Double, lon: Double): Boolean =
        (lat in 24.0..49.5 && lon in -125.0..-66.0) ||
            (lat in 18.5..22.5 && lon in -161.0..-154.0) ||
            (lat in 51.0..72.0 && lon in -170.0..-129.0)

    private fun classifyPoiTag(addr: Address, fullAddress: String): String {
        val haystack = listOfNotNull(
            fullAddress,
            addr.featureName,
            addr.premises,
            addr.thoroughfare,
            addr.subLocality,
            addr.locality,
            addr.adminArea
        ).joinToString(" ").lowercase(Locale.ROOT)

        return POI_RULES.firstOrNull { rule ->
            rule.keywords.any { keyword -> haystack.contains(keyword.lowercase(Locale.ROOT)) }
        }?.tag.orEmpty()
    }

    /** Haversine 距離（公尺）。住家半徑判定用，誤差遠小於 GPS 本身的飄動，可接受。 */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    // ─── 輔助 ─────────────────────────────────────────────────────────────────

    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            cont.resume(if (task.isSuccessful) task.result else null)
        }
    }

    /**
     * 快取夠不夠新「而且」夠不夠準。原本只看時間、完全不看 [Location.accuracy]，
     * 導致一個誤差 500 公尺的定位跟誤差 5 公尺的被當成一樣可信，
     * 直接拿去比對 35 公尺半徑的 POI——那個比對結果基本上是隨機的。
     */
    private fun isFresh(location: Location) =
        System.currentTimeMillis() - location.time < FRESH_WINDOW_MS &&
            location.hasAccuracy() && location.accuracy <= GOOD_ACCURACY_M

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "LocationTracker"
        private const val DEFAULT_OFFLINE_POI_RADIUS_M = 35.0
        private const val US_OFFLINE_POI_RADIUS_M = 90.0

        /** 快取可用的時間窗。原本 10 分鐘——病人 10 分鐘內可能已經從病房走到大廳。 */
        private const val FRESH_WINDOW_MS = 2 * 60 * 1000L
        /** 單次定位最長等待，避免室內收不到訊號時一直卡住。 */
        private const val LOCATION_TIMEOUT_MS = 10_000L
        /** 快取要「夠準」才直接用，否則寧可重新定位。 */
        private const val GOOD_ACCURACY_M = 50f
        /** 誤差超過這個值就不再報具體店名／POI，只講概略地址。 */
        private const val POI_TRUSTWORTHY_ACCURACY_M = 60f

        /**
         * POI 分類規則。順序很重要：**先具體後通用**（先匹配「7-11」這種品牌名，
         * 才退到「便利商店」、「市場」這類概括詞）。
         *
         * 故意不再放「巷 / 弄 / 公寓」這種地址型 token——那會把所有住宅街都當成「住家」。
         * 住家改成由 [HomeAnchor] 半徑判定，由使用者明確設定。
         */
        private val POI_RULES = listOf(
            // === 醫療 ===
            PoiRule("醫院", listOf(
                "醫院", "hospital", "醫學中心", "醫療中心", "急診", "門診",
                "馬偕", "長庚", "榮總", "三總", "亞東", "新光", "國泰", "慈濟", "彰基", "成大", "高醫", "中國醫", "義大",
                "diagnostic", "medical center", "emergency room", "er", "urgent care", "kaiser", "sutter health", "cedars-sinai",
                "mayo clinic", "cleveland clinic", "mount sinai", "nyu langone", "ucla health", "stanford health"
            )),
            PoiRule("診所", listOf("診所", "clinic", "牙醫", "牙科", "dentist", "dental", "眼科", "皮膚科", "婦產科", "小兒科", "中醫", "復健科", "獸醫", "veterinary", "physical therapy")),
            PoiRule("藥局", listOf("藥局", "藥房", "藥粧", "pharmacy", "drugstore", "cvs", "walgreens", "rite aid", "duane reade", "屈臣氏", "watsons", "康是美", "cosmed", "大樹藥局", "杏一", "佑全")),

            // === 教育 ===
            PoiRule("學校", listOf(
                "學校", "小學", "國小", "中學", "國中", "高中", "高職", "大學", "學院", "科技大學",
                "university", "college", "school", "academy", "elementary", "middle school", "high school", "幼兒園", "幼稚園", "托兒所", "kindergarten",
                "補習班", "安親班", "cram", "圖書館", "library"
            )),

            // === 便利商店 / 超商 ===
            PoiRule("便利商店", listOf(
                "7-eleven", "7-11", "seven", "統一超商",
                "全家", "familymart", "family mart",
                "萊爾富", "hi-life", "hilife",
                "ok mart", "ok超商", "okmart",
                "美廉社", "簡單購",
                "便利商店", "convenience", "convenience store", "circle k", "speedway", "wawa", "sheetz", "quickchek", "quiktrip", "kwik trip",
                "casey's", "cumberland farms", "ampm", "mini mart", "minimart", "food mart"
            )),

            // === 超市 / 量販 ===
            PoiRule("超市", listOf(
                "全聯", "px mart", "pxmart",
                "家樂福", "carrefour",
                "costco", "好市多",
                "大潤發", "rt-mart", "rtmart",
                "頂好", "wellcome",
                "美聯社", "city super", "jasons",
                "愛買", "a.mart",
                "超市", "量販", "supermarket", "hypermarket", "grocery", "grocery store", "市場", "market",
                "walmart", "target", "kroger", "safeway", "whole foods", "trader joe", "publix", "aldi", "meijer",
                "wegmans", "h-e-b", "heb", "winco", "ralphs", "vons", "giant", "stop & shop", "food lion", "sprouts"
            )),

            // === 速食 / 連鎖餐飲 ===
            PoiRule("速食店", listOf(
                "麥當勞", "mcdonald", "mcdonalds",
                "肯德基", "kfc",
                "摩斯", "mos burger", "mosburger",
                "漢堡王", "burger king", "burgerking",
                "subway", "賽百味", "taco bell", "wendy", "wendy's", "chick-fil-a", "chipotle", "in-n-out",
                "jack in the box", "popeyes", "five guys", "panda express", "domino", "pizza hut"
            )),
            PoiRule("咖啡店", listOf(
                "星巴克", "starbucks",
                "路易莎", "louisa",
                "85度c", "85°c", "85 度c",
                "cama", "cama café",
                "怡客", "ikari",
                "coffee", "café", "cafe", "咖啡", "dunkin", "peet", "blue bottle", "philz", "coffee bean"
            )),
            PoiRule("飲料店", listOf(
                "清心", "50嵐", "五十嵐", "milkshop", "迷客夏", "可不可", "kebuke",
                "coco", "都可", "comebuy", "茶湯會", "天仁", "天仁茗茶",
                "麻古", "macu", "得正", "龜記", "kebu", "鶴茶樓",
                "茶之魔手", "鮮茶道", "貢茶", "gong cha"
            )),
            PoiRule("餐廳", listOf(
                "餐廳", "餐館", "飯店", "小吃", "食堂", "restaurant", "dining", "diner", "grill", "kitchen", "bistro",
                "鬍鬚張", "三商巧福", "永和豆漿", "鼎泰豐", "牛肉麵",
                "壽司", "拉麵", "ramen", "燒肉", "火鍋", "海底撈", "王品", "瓦城",
                "buffet", "自助餐", "便當", "bento"
            )),
            PoiRule("夜市", listOf("夜市", "night market", "饒河", "士林", "寧夏", "逢甲", "瑞豐", "六合")),

            // === 交通 ===
            PoiRule("車站", listOf(
                "車站", "火車站", "高鐵", "thsr",
                "捷運", "mrt", "metro", "subway station",
                "轉運站", "客運", "公車站", "bus station", "bus stop",
                "機場", "airport"
            )),
            PoiRule("加油站", listOf("加油站", "中油", "cpc", "台塑", "fpcc", "shell", "gas station", "petrol", "fuel", "chevron", "exxon", "mobil", "bp", "76", "sunoco", "marathon", "valero", "arco", "texaco")),

            // === 金融 / 行政 ===
            PoiRule("銀行", listOf(
                "銀行", "bank", "atm", "credit union", "信用合作社", "農會",
                "玉山", "中信", "國泰世華", "兆豐", "第一銀行", "華南", "彰銀", "永豐", "台新", "新光銀行"
            )),
            PoiRule("郵局", listOf("郵局", "post office", "usps", "postal service", "中華郵政")),
            PoiRule("公家機關", listOf("區公所", "市政府", "縣政府", "里辦公處", "戶政事務所", "警察局", "派出所", "消防隊", "police", "fire station", "city hall", "courthouse", "dmv", "station")),

            // === 商業 / 娛樂 ===
            PoiRule("百貨", listOf(
                "百貨", "department store", "outlet", "購物中心", "mall", "shopping", "shopping center", "shopping plaza",
                "新光三越", "sogo", "微風", "breeze", "遠百", "遠東百貨",
                "誠品", "eslite", "大葉高島屋", "三井", "mitsui", "macy", "nordstrom", "bloomingdale", "jcpenney", "kohl"
            )),
            PoiRule("電影院", listOf("電影院", "影城", "cinema", "movie", "威秀", "vieshow", "in89", "華納", "秀泰")),
            PoiRule("健身房", listOf("健身房", "gym", "fitness", "world gym", "anytime fitness", "成吉思汗", "可爾姿")),
            PoiRule("KTV", listOf("ktv", "錢櫃", "好樂迪", "星聚點")),

            // === 信仰 / 公共 ===
            PoiRule("廟宇", listOf("廟", "宮", "寺", "教堂", "church", "temple", "cathedral", "chapel", "mosque", "synagogue", "媽祖", "土地公", "城隍", "關帝")),
            PoiRule("公園", listOf("公園", "park", "綠地", "森林公園", "運動公園", "兒童樂園", "playground", "操場", "stadium")),

            // === 旅館 ===
            PoiRule("旅館", listOf("旅館", "酒店", "hotel", "民宿", "motel", "汽車旅館", "inn", "旅店", "lodging", "holiday inn", "marriott", "hilton", "hyatt", "best western"))
        )
    }
}

private data class PoiRule(
    val tag: String,
    val keywords: List<String>
)
