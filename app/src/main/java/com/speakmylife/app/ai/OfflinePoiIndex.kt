package com.speakmylife.app.ai

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 完全離線的 POI 查詢：載入 APK assets 內的 gzipped TSV，建立記憶體空間索引，
 * 永遠不發網路請求、無 API key、無用量限制。
 *
 * 資料來源：
 *   `assets/poi/worldwide_pois.tsv` 或舊版 `assets/poi/taiwan_pois.tsv` — 每行 `lat\tlon\tcategory\tname`
 *
 * 目前可用 `tools/fetch_poi_multi.sh` 打包台灣 + 美國主要都會區。
 *
 * 設計：
 *  - **網格分桶**：把 (lat, lon) 量化到 0.005° 一格（約 555 m），查詢時只掃中心 + 8 鄰格
 *  - **lazy load**：第一次呼叫才解壓 + parse；之後常駐記憶體
 *  - **執行緒安全**：載入用 synchronized 雙重檢查，查詢階段純讀
 *
 * 記憶體估算：52k POI × 平均 64 byte/筆 ≈ 3.3 MB heap。比 Vosk 模型小很多。
 */
class OfflinePoiIndex(private val appContext: Context) {

    /** 一筆 POI 紀錄。緊湊 data class 比 Map<String, String> 省 ~4 倍記憶體。 */
    private data class Poi(
        val lat: Double,
        val lon: Double,
        val category: String,
        val name: String
    )

    /** 網格鍵：(latBucket, lonBucket)。Long 比 Pair 省 GC 壓力。 */
    private fun cellKey(latBucket: Int, lonBucket: Int): Long =
        (latBucket.toLong() shl 32) or (lonBucket.toLong() and 0xFFFFFFFFL)

    private fun latBucket(lat: Double) = (lat / CELL_DEG).toInt()
    private fun lonBucket(lon: Double) = (lon / CELL_DEG).toInt()

    @Volatile private var index: HashMap<Long, ArrayList<Poi>>? = null
    private val loadLock = Any()

    /** 確保 index 已載入。首次呼叫會做完整 IO + parse；之後 return immediately。 */
    private fun ensureLoaded() {
        if (index != null) return
        synchronized(loadLock) {
            if (index != null) return
            val map = HashMap<Long, ArrayList<Poi>>(8192)
            var count = 0
            try {
                // 為什麼不用 .gz：Android AAPT 對 `.gz` 副檔名會在 packaging 階段自動解壓，
                // 拿到 InputStream 時已經是未壓縮的內容；硬包 GZIPInputStream 反而會炸。
                // 直接放 .tsv，APK 內部 ZIP 還是會做 deflate，總體大小差不多。
                val assetPath = firstExistingAssetPath()
                appContext.assets.open(assetPath).use { rawIn ->
                    BufferedReader(InputStreamReader(rawIn, Charsets.UTF_8)).useLines { lines ->
                        for (line in lines) {
                            val parts = line.split('\t')
                            if (parts.size < 3) continue
                            val lat = parts[0].toDoubleOrNull() ?: continue
                            val lon = parts[1].toDoubleOrNull() ?: continue
                            val name = parts.getOrNull(3).orEmpty()
                            val cat = CATEGORY_CORRECTIONS[name] ?: parts[2]
                            if (cat.isBlank()) continue
                            val key = cellKey(latBucket(lat), lonBucket(lon))
                            map.getOrPut(key) { ArrayList(4) }.add(Poi(lat, lon, cat, name))
                            count++
                        }
                    }
                }
                index = map
                Log.i(TAG, "Loaded $count POIs from $assetPath into ${map.size} cells (avg ${count / map.size.coerceAtLeast(1)} per cell)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load offline POI", e)
                index = HashMap()  // 設成空 map，避免一直重試
            }
        }
    }

    private fun firstExistingAssetPath(): String {
        val poiAssets = runCatching { appContext.assets.list("poi")?.toSet().orEmpty() }.getOrDefault(emptySet())
        return ASSET_CANDIDATES.firstOrNull { candidate ->
            candidate.substringAfter("poi/") in poiAssets
        } ?: LEGACY_ASSET_PATH
    }

    /**
     * 找最近 POI。回傳 (中文類別, 店名) 或 null。
     *
     * 為什麼 [radiusM] 預設 35m：實測台北 / 高雄密集街區，80m 會把使用者住家所在巷子 (e.g. 忠孝一路 226 巷)
     * 對映到隔壁 50-60m 外某家被 OSM 誤標 shop=wholesale 的「食品行」，類別顯示成「量販店」極不直觀。
     * 35m 等於 Google Places 的等級，必須站在店面入口附近才會 match，誤抓率降到接近零。
     * 不夠近就 fallback 到地址文字（更誠實）；要客製就用 [HomeAnchor] 設家裡。
     */
    fun findNearestPoi(lat: Double, lon: Double, radiusM: Double = 35.0): Pair<String, String>? {
        ensureLoaded()
        val map = index ?: return null

        val latB = latBucket(lat)
        val lonB = lonBucket(lon)
        var best: Poi? = null
        var bestDist = radiusM

        // 掃 3x3 鄰格（中心 + 8 周圍），確保跨格邊界的 POI 也能找到
        for (dLat in -1..1) for (dLon in -1..1) {
            val bucket = map[cellKey(latB + dLat, lonB + dLon)] ?: continue
            for (p in bucket) {
                val d = haversine(lat, lon, p.lat, p.lon)
                if (d <= bestDist) { best = p; bestDist = d }
            }
        }
        return best?.let { it.category to it.name }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        private const val TAG = "OfflinePoiIndex"
        private const val LEGACY_ASSET_PATH = "poi/taiwan_pois.tsv"
        private val ASSET_CANDIDATES = listOf("poi/worldwide_pois.tsv", LEGACY_ASSET_PATH)
        /** 網格大小（度）。0.005° ≈ 555 m，3x3 掃描範圍 ≈ 1.7 km，遠超 80m 半徑安全裕度 */
        private const val CELL_DEG = 0.005

        /**
         * 上游 OSM 分類標錯的逐筆修正。
         *
         * 例：明誠附小在 OSM 被標成 amenity=kindergarten，使用者人在小學裡，
         * 句子卻說他在幼兒園。位置比對其實是準的（就是這棟），錯的只有類別。
         *
         * 刻意用「逐筆對照」而不是「看名字含小學就改成學校」的規則：
         * 全台有 80 筆「○○國小附設幼兒園」，那些標幼兒園是**對的**，
         * 用名稱猜規則會把它們一起改壞。真正標錯的只有個位數，列出來最安全。
         *
         * 修正寫在程式碼而不是直接改 assets 的 TSV——那份 13MB 的資料沒有納入 git，
         * 改了不會留下紀錄，日後重新匯出就沒了。
         */
        private val CATEGORY_CORRECTIONS = mapOf(
            "天主教高雄市明誠高級中學附設雙語實驗小學" to "學校"
        )
    }
}
