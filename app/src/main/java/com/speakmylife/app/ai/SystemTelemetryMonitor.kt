package com.speakmylife.app.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 即時取樣的指標。**重要：欄位是真的，不再放假數據**。
 *
 * 為什麼舊版的 CPU 是錯的：
 *  - 舊版讀 `/proc/stat` 想拿系統級 CPU。但 Android 8.0+ 起，SELinux 把 `/proc/stat` 對 untrusted_app
 *    domain 禁讀（無論裝置廠商），結果 runCatching 永遠吞掉 SecurityException，
 *    回傳 0。再用「100 - 0 = 100%」當作 CPU 效率，畫面就會死板地停在 100%。
 *  - 新版改成「per-process CPU」：把 [Process.getElapsedCpuTime] 在兩次 sample 之間的增量
 *    除以「wall clock 時間 × CPU 核數」。這是 app sandbox **真正能讀到** 的 CPU 指標，
 *    語意是「本 App 在過去一秒佔了多少 CPU 容量」。
 *
 * 為什麼舊版沒有 GPU：
 *  - 一般 app 無法讀系統 GPU 使用率。但 Qualcomm Adreno 在 `/sys/class/kgsl/kgsl-3d0/gpubusy`
 *    暴露兩個數字 `<busy_ns> <total_ns>`，**這條路徑 SELinux 對 app 開放**，
 *    所以我們可以拿 `busy/total` 當 GPU 使用率。非 Qualcomm 機型回傳 null（UI 端顯示「—」）。
 *
 * 為什麼加 frameRate：
 *  - 對 AAC App 來說，眼動磁吸 / 故事訓練動畫卡頓是真正影響使用者的事。Choreographer 每次
 *    onVFrame 的間隔就是實際 frame interval，1s / interval ≈ FPS。
 */
data class DeviceMetricSample(
    val timestamp: Long = System.currentTimeMillis(),
    val ramUsedPercent: Float,
    val batteryPercent: Float,
    val batteryTempC: Float,
    /**
     * 本 App 過去一個 sample 區間佔用的 CPU 容量百分比（0~100，依核數 normalize）。
     *
     * **故意不提供「系統 CPU」**：Android 8+ 的 SELinux 對 `untrusted_app` 域全面禁讀
     * `/proc/stat`，連 `ProcessBuilder("top")` spawn 出的子進程也吃同一個 sandbox（永遠回 0%）。
     * 與其顯示假的「系統 CPU = 0%」騙使用者，這欄直接專注在「**本 App 自己**耗了多少 CPU」。
     */
    val appCpuPercent: Float,
    /** 本 App 的 RSS（MB）— 真實佔用實體記憶體；從 top 拿，比 ActivityManager 準 */
    val appRssMb: Float,
    /** GPU 使用率（0~100）。讀 Adreno kgsl；非 Adreno 為 null */
    val gpuUsagePercent: Float?,
    /** 平均畫面更新率（FPS）；正常為 60 或 120 */
    val frameRateHz: Float,
    val networkDownKbps: Float,
    val networkUpKbps: Float,
    val networkEstimatedKbps: Float
)

/**
 * 一次性硬體資訊（不會隨時間變）。在 UI 標題顯示用。
 */
data class DeviceHardwareInfo(
    val socManufacturer: String,   // e.g. "QTI"
    val socModel: String,          // e.g. "SM8350"
    val socFriendlyName: String,   // e.g. "Qualcomm SM8350 (Snapdragon 888)"
    val gpuName: String,           // e.g. "Adreno 660"
    val cpuCoreCount: Int
)

class SystemTelemetryMonitor(private val context: Context) {

    // --- 取樣用狀態 ---
    private var lastProcessCpuMs: Long = Process.getElapsedCpuTime()
    private var lastWallMs: Long = SystemClock.elapsedRealtime()
    private var lastRxBytes: Long = TrafficStats.getTotalRxBytes()
    private var lastTxBytes: Long = TrafficStats.getTotalTxBytes()
    private var lastNetworkMs: Long = System.currentTimeMillis()
    private var lastGpuBusy: Pair<Long, Long>? = null   // (busy_ns, total_ns)

    // --- Choreographer FPS 追蹤（持續累積，sample 時抽快照） ---
    @Volatile private var frameCount: Int = 0
    @Volatile private var frameWindowStartNs: Long = System.nanoTime()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    init {
        // postFrameCallback 必須在主執行緒；MainActivity 內第一次建 ViewModel 時 init 已經在 main。
        runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }
    }

    suspend fun sample(): DeviceMetricSample = withContext(Dispatchers.IO) {
        val battery = readBattery()
        val fps = consumeFrameRateHz()
        val network = readNetworkKbps()
        val gpu = readAdrenoGpuBusyPercent()

        // top 一次拿到「本 App CPU + 本 App RSS + 系統 RAM」（系統 CPU 拿不到，見 DeviceMetricSample 註解）
        val topResult = readTopSnapshot()
        val appCpu = topResult?.appCpuPercent ?: readAppCpuPercent()
        val appRss = topResult?.appRssMb ?: 0f
        val ram = topResult?.systemRamUsedPercent?.takeIf { it > 0f } ?: readRamUsedPercent()

        NetworkQualityGate.update(
            estimatedDownKbps = network.estimatedDownKbps,
            actualDownKbps = network.downKbps
        )
        DeviceMetricSample(
            ramUsedPercent = ram,
            batteryPercent = battery.first,
            batteryTempC = battery.second,
            appCpuPercent = appCpu,
            appRssMb = appRss,
            gpuUsagePercent = gpu,
            frameRateHz = fps,
            networkDownKbps = network.downKbps,
            networkUpKbps = network.upKbps,
            networkEstimatedKbps = network.estimatedDownKbps
        )
    }

    /**
     * 解析 `top -n 1 -b` 一次拿三個指標。
     *
     * `/proc/stat` 從 Android 8 起被 SELinux 對 untrusted_app domain 全禁，
     * 但 `top` 二進位是系統提供的 toybox，在 untrusted_app 也可執行（內部用特權路徑）。
     * 這是目前**唯一**在無 root 的 app sandbox 拿到系統 CPU% 的方法。
     *
     * top 輸出範例（toybox/Android）：
     * ```
     * Mem:    11242M total,    11019M used,      223M free,        2M buffers
     * 800%cpu   0%user   0%nice   0%sys 800%idle ...
     *   PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS
     * 14051 u0_a220      10 -10  20G 449M 147M S  8.0   3.9   1:42.90 com.speakmylife...
     * ```
     */
    private data class TopSnapshot(
        val systemRamUsedPercent: Float, // 0~100，從 `Mem:` 那行算
        val appCpuPercent: Float,        // 0~100，已 normalize 過核數
        val appRssMb: Float
    )

    private fun readTopSnapshot(): TopSnapshot? = runCatching {
        val proc = ProcessBuilder("top", "-n", "1", "-b").redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        if (output.isBlank()) return@runCatching null

        val myPid = Process.myPid().toString()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1).toFloat()

        var sysRam = 0f
        var appCpu = 0f
        var appRss = 0f

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            when {
                // "Mem:    11242M total,    11019M used,      223M free, ..."
                line.startsWith("Mem:") -> {
                    val totalM = Regex("""(\d+(?:\.\d+)?)M\s+total""").find(line)?.groupValues?.get(1)?.toFloatOrNull()
                    val usedM  = Regex("""(\d+(?:\.\d+)?)M\s+used""").find(line)?.groupValues?.get(1)?.toFloatOrNull()
                    if (totalM != null && usedM != null && totalM > 0f) {
                        sysRam = (usedM / totalM * 100f).coerceIn(0f, 100f)
                    }
                }
                // 本 App 那行：PID 起頭，欄位用空白分隔
                else -> {
                    val cols = line.split(Regex("\\s+"))
                    if (cols.firstOrNull() != myPid) continue
                    if (cols.size >= 9) {
                        appRss = parseSize(cols[5])
                        val rawCpu = cols[8].toFloatOrNull() ?: 0f
                        // top 的 %CPU 以「單核 = 100%」為單位；換成「佔全機容量」需要 ÷ 核數
                        appCpu = (rawCpu / cores).coerceIn(0f, 100f)
                    }
                }
            }
        }
        TopSnapshot(sysRam, appCpu, appRss)
    }.getOrElse {
        android.util.Log.w("Telemetry", "top parse failed: ${it.message?.take(80)}")
        null
    }

    /** "449M" / "1.2G" / "256K" → MB float */
    private fun parseSize(token: String): Float {
        val m = Regex("""([\d.]+)([KMG])?""").matchEntire(token) ?: return 0f
        val v = m.groupValues[1].toFloatOrNull() ?: return 0f
        return when (m.groupValues[2]) {
            "G" -> v * 1024f
            "M" -> v
            "K" -> v / 1024f
            else -> v / (1024f * 1024f)  // 純 byte
        }
    }

    /** 一次性讀取硬體資訊。給 UI 標題列顯示 SoC / GPU 名字（不會跳動）。 */
    fun readHardwareInfo(): DeviceHardwareInfo {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val socManu = systemProp("ro.soc.manufacturer").ifBlank { systemProp("ro.product.manufacturer") }
        val socModel = systemProp("ro.soc.model").ifBlank { systemProp("ro.product.board") }
        val gpuRaw = runCatching {
            File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim()
        }.getOrDefault("").ifBlank { "Unknown GPU" }

        return DeviceHardwareInfo(
            socManufacturer = socManu.ifBlank { "Unknown" },
            socModel = socModel.ifBlank { "Unknown" },
            socFriendlyName = socFriendlyName(socManu, socModel),
            gpuName = prettifyGpu(gpuRaw),
            cpuCoreCount = cores
        )
    }

    // === Privates ===

    private fun readRamUsedPercent(): Float = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val used = info.totalMem - info.availMem
        (used.toFloat() / info.totalMem.toFloat() * 100f).coerceIn(0f, 100f)
    }.getOrDefault(0f)

    private fun readBattery(): Pair<Float, Float> {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = manager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it >= 0 }
            ?.toFloat()
            ?: 0f

        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val tempC = if (tempTenths == Int.MIN_VALUE) 0f else tempTenths / 10f
        return percent.coerceIn(0f, 100f) to tempC
    }

    /**
     * 真正準的 App CPU 用量。公式：
     *   (此 App 在 [lastWallMs, now] 之間用掉的 CPU ms) / (wallClock ms × cores) × 100
     *
     * 用 [SystemClock.elapsedRealtime] 而不是 [System.currentTimeMillis]，
     * 才不會被 NTP 時鐘校正搞壞 delta。
     */
    private fun readAppCpuPercent(): Float = runCatching {
        val nowCpuMs = Process.getElapsedCpuTime()
        val nowWallMs = SystemClock.elapsedRealtime()
        val cpuDelta = nowCpuMs - lastProcessCpuMs
        val wallDelta = nowWallMs - lastWallMs
        lastProcessCpuMs = nowCpuMs
        lastWallMs = nowWallMs
        if (wallDelta <= 0L) return@runCatching 0f
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        (cpuDelta.toFloat() / (wallDelta.toFloat() * cores) * 100f).coerceIn(0f, 100f)
    }.getOrDefault(0f)

    /**
     * 讀 Qualcomm Adreno 的 gpubusy。檔案格式為「busy_ns total_ns」，
     * 兩次 sample 取 delta → busy/total = GPU 使用率。
     */
    private fun readAdrenoGpuBusyPercent(): Float? {
        val path = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
        if (!path.exists()) return null
        val pair = runCatching {
            val txt = path.readText().trim().split(Regex("\\s+"))
            if (txt.size < 2) return null
            val busy = txt[0].toLong()
            val total = txt[1].toLong()
            busy to total
        }.getOrNull() ?: return null

        val (currBusy, currTotal) = pair
        val previous = lastGpuBusy
        lastGpuBusy = pair
        if (previous == null) return 0f
        val busyDelta = currBusy - previous.first
        val totalDelta = currTotal - previous.second
        return if (totalDelta <= 0L) 0f
        else (busyDelta.toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
    }

    /**
     * 把 Choreographer 累積的 frame 數轉成 FPS 並重置窗口。
     */
    private fun consumeFrameRateHz(): Float {
        val now = System.nanoTime()
        val count = frameCount
        val windowStart = frameWindowStartNs
        frameCount = 0
        frameWindowStartNs = now
        val elapsedNs = (now - windowStart).coerceAtLeast(1L)
        return (count * 1_000_000_000f / elapsedNs).coerceIn(0f, 240f)
    }

    private fun readNetworkKbps(): NetworkSample {
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val elapsedMs = (now - lastNetworkMs).coerceAtLeast(1L)
        val downKbps = if (rx >= 0 && lastRxBytes >= 0) {
            ((rx - lastRxBytes).coerceAtLeast(0L) * 8f / elapsedMs)
        } else 0f
        val upKbps = if (tx >= 0 && lastTxBytes >= 0) {
            ((tx - lastTxBytes).coerceAtLeast(0L) * 8f / elapsedMs)
        } else 0f
        lastRxBytes = rx
        lastTxBytes = tx
        lastNetworkMs = now
        return NetworkSample(
            downKbps = downKbps,
            upKbps = upKbps,
            estimatedDownKbps = readEstimatedNetworkKbps()
        )
    }

    private fun readEstimatedNetworkKbps(): Float = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@runCatching 0f
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching 0f
        caps.linkDownstreamBandwidthKbps.toFloat().coerceAtLeast(0f)
    }.getOrDefault(0f)

    private data class NetworkSample(
        val downKbps: Float,
        val upKbps: Float,
        val estimatedDownKbps: Float
    )
}

/**
 * 把 ro.soc.* 對映成人類看得懂的名字。沒命中就退到原始字串。
 *
 * 表格不放完整，只放台灣常見手機晶片；之後可以加。
 */
private fun socFriendlyName(manufacturer: String, model: String): String {
    val friendly = when (model.uppercase()) {
        "SM8350"  -> "Snapdragon 888"
        "SM8450"  -> "Snapdragon 8 Gen 1"
        "SM8475"  -> "Snapdragon 8+ Gen 1"
        "SM8550"  -> "Snapdragon 8 Gen 2"
        "SM8650"  -> "Snapdragon 8 Gen 3"
        "SM8250"  -> "Snapdragon 865"
        "SM7325"  -> "Snapdragon 778G"
        "MT6983"  -> "Dimensity 9000"
        "MT6985"  -> "Dimensity 9200"
        "MT6989"  -> "Dimensity 9300"
        "EXYNOS2200" -> "Exynos 2200"
        else -> ""
    }
    val vendor = when (manufacturer.uppercase()) {
        "QTI", "QUALCOMM" -> "Qualcomm"
        "MTK", "MEDIATEK" -> "MediaTek"
        "SAMSUNG" -> "Samsung"
        else -> manufacturer
    }
    return when {
        friendly.isNotBlank() && vendor.isNotBlank() -> "$vendor $model ($friendly)"
        model.isNotBlank() && vendor.isNotBlank() -> "$vendor $model"
        model.isNotBlank() -> model
        else -> "Unknown SoC"
    }
}

/** "Adreno660v2" → "Adreno 660" */
private fun prettifyGpu(raw: String): String {
    val cleaned = raw.trim()
    val m = Regex("Adreno(\\d+)([a-zA-Z0-9]*)").find(cleaned)
    if (m != null) {
        val number = m.groupValues[1]
        return "Adreno $number"
    }
    return cleaned.ifBlank { "Unknown GPU" }
}

/** 透過 reflection 讀 `android.os.SystemProperties.get(name)`，免 hidden API 限制 */
private fun systemProp(name: String): String = runCatching {
    val cls = Class.forName("android.os.SystemProperties")
    val getMethod = cls.getMethod("get", String::class.java)
    (getMethod.invoke(null, name) as? String).orEmpty()
}.getOrDefault("")

// ─────────────────────────────────────────────────────────

object NetworkQualityGate {
    private const val CLOUD_MIN_KBPS = 1_500f
    @Volatile private var estimatedDownKbps: Float = 0f
    @Volatile private var actualDownKbps: Float = 0f

    fun update(estimatedDownKbps: Float, actualDownKbps: Float) {
        if (estimatedDownKbps > 0f) this.estimatedDownKbps = estimatedDownKbps
        if (actualDownKbps > 0f) this.actualDownKbps = actualDownKbps
    }

    fun shouldPreferLocal(): Boolean {
        val effective = when {
            estimatedDownKbps > 0f -> estimatedDownKbps
            actualDownKbps > 0f -> actualDownKbps
            else -> return false
        }
        return effective < CLOUD_MIN_KBPS
    }

    fun reason(): String = "網路低於 ${CLOUD_MIN_KBPS.toInt()} Kbps，改用本地端 AI"
}
