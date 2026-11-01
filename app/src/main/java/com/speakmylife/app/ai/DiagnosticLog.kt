package com.speakmylife.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * 全 App 的本地診斷日誌。
 *
 * 為什麼需要這支：今天查「唸出來的聲音很怪」花了一整輪，最後靠插 USB 抓 logcat 才發現
 * 參考音檔和逐字稿被拆成兩個不同的檔案。而 logcat 有三個致命問題——
 *   1. 要猜對 tag（那次真正的錯誤 tag 是 HybridInferenceEngine，過濾成 CloudInferenceEngine 就整段看不到）
 *   2. 緩衝區會被系統訊息洗掉，事發後幾分鐘就撈不回來
 *   3. 使用者手上的手機沒接電腦，等於完全沒有現場紀錄
 * 所以改成「一律寫本地檔案」：事後 adb pull 得到，使用者也能自己匯出給我們。
 *
 * 檔案位置：`Android/data/<pkg>/files/diagnostics/sml-YYYY-MM-DD.log`
 * 用外部檔案目錄（非 cacheDir）是刻意的：adb pull 不必 root，使用者也能用檔案管理員找到；
 * 隨 App 解除安裝一起刪掉，不會留下孤兒資料。
 */
object DiagnosticLog {

    /** 記錄分類——查問題時先看分類就能鎖定是哪條路壞掉。 */
    enum class Cat {
        /** 使用者動作：按鈕、輸入、切換設定 */ UI,
        /** 語音輸入：錄音、Vosk、雲端轉寫 */ STT,
        /** 句子重組：引擎選擇、雲端呼叫、候選 */ LLM,
        /** 語音合成：參考音、逐字稿、合成參數 */ TTS,
        /** 跨端連線：SoVITS server、模型切換 */ NET,
        /** 降級與失敗：任何「改用 XXX」都必須留下原因 */ FALLBACK,
    }

    private const val TAG = "SpeakMyLife"
    private const val PREFIX = "sml-"
    /** 改名前（原名 VoiceWeaver）的日誌檔名前綴；同 App 內若有，啟動時改名成 [PREFIX]。 */
    private const val LEGACY_PREFIX = "vw-"
    private const val MAX_MEMORY_LINES = 500
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024   // 8MB 上限，超過就刪最舊的整天檔案
    private const val KEEP_DAYS = 7

    // SimpleDateFormat **不是執行緒安全的**，而 write() 是從任何執行緒呼叫的
    //（收音在 IO、比對在 Default、按鈕在 Main）。共用一個實例時，兩邊同時
    // format 會踩到它內部共用的 Calendar，產出亂掉的時間戳，偶爾直接丟
    // ArrayIndexOutOfBoundsException——而那個例外會從一行「記個 log」冒出來，
    // 看起來完全像是別的地方壞掉。每個執行緒一份最省事。
    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
    private val daySuffix = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    /** 寫檔丟到單一背景執行緒，呼叫端（含 UI 執行緒）永遠不阻塞、也不必自己同步。 */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sml-diag-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Volatile private var dir: File? = null

    /** init 之前的紀錄先排隊，等拿到 Context 再倒進檔案——App 剛啟動那幾行往往最關鍵。 */
    private val pending = LinkedBlockingQueue<String>(200)

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    /** 給 App 內的日誌檢視器用的即時畫面（僅保留最近 [MAX_MEMORY_LINES] 行）。 */
    val recent: StateFlow<List<String>> = _recent

    /**
     * 綁定 Context 並把排隊中的紀錄寫出去。重複呼叫安全（第二次之後直接返回）。
     */
    fun init(context: Context) {
        if (dir != null) return
        val target = runCatching {
            File(context.applicationContext.getExternalFilesDir(null), "diagnostics")
                .apply { if (!isDirectory) mkdirs() }
        }.getOrElse {
            // 外部儲存不可用（極少見）時退回內部目錄，寧可 adb pull 麻煩也不要沒紀錄
            File(context.applicationContext.filesDir, "diagnostics").apply { if (!isDirectory) mkdirs() }
        }
        dir = target
        writer.execute {
            val queued = ArrayList<String>(pending.size)
            pending.drainTo(queued)
            if (queued.isNotEmpty()) appendLines(queued)
            rotate()
        }
        i(Cat.UI, "診斷日誌啟動", "path=${target.absolutePath}")
    }

    /** 一般紀錄。[detail] 放結構化細節（key=value），查問題時比散文好搜。 */
    fun i(cat: Cat, message: String, detail: String? = null) = write("I", cat, message, detail, null)

    /** 有疑慮但還能繼續。降級、重試、參數異常都走這裡。 */
    fun w(cat: Cat, message: String, detail: String? = null) = write("W", cat, message, detail, null)

    /** 失敗。[t] 會連 stacktrace 第一層一起記，不要只留 message。 */
    fun e(cat: Cat, message: String, detail: String? = null, t: Throwable? = null) =
        write("E", cat, message, detail, t)

    private fun write(level: String, cat: Cat, message: String, detail: String?, t: Throwable?) {
        val sb = StringBuilder()
            .append(stamp.get()!!.format(Date())).append(' ')
            .append(level).append(' ')
            .append('[').append(cat.name).append("] ")
            .append(redact(message))
        if (!detail.isNullOrBlank()) sb.append("  |  ").append(redact(detail))
        if (t != null) {
            sb.append("  |  ").append(t.javaClass.simpleName).append(": ").append(redact(t.message ?: ""))
            t.stackTrace.firstOrNull()?.let { sb.append(" @ ").append(it) }
            t.cause?.let { sb.append(" <- ").append(it.javaClass.simpleName).append(": ").append(redact(it.message ?: "")) }
        }
        val line = sb.toString()

        // logcat 仍然照送一份，接著電腦除錯時 tag 統一成 SpeakMyLife，不必再猜是哪個類別的 tag
        when (level) { "E" -> Log.e(TAG, line); "W" -> Log.w(TAG, line); else -> Log.i(TAG, line) }

        // update{} 而不是讀了再寫：write() 會從多個執行緒同時進來，
        // `value = value + line` 是 read-modify-write，兩邊撞在一起就會丟行——
        // 而丟掉的往往正是同時發生的那幾行，也就是查併發問題最需要的那幾行。
        _recent.update { (it + line).takeLast(MAX_MEMORY_LINES) }

        if (dir == null) { pending.offer(line); return }
        writer.execute { appendLines(listOf(line)) }
    }

    /**
     * 金鑰、token 一律不進檔案——這份日誌的用途就是要能直接傳給別人看，
     * 只要有一次把 Bearer token 寫進去，使用者分享出來就等於外洩。
     */
    private val secretPatterns = listOf(
        Regex("""(?i)(bearer\s+)[A-Za-z0-9\-._~+/]{8,}"""),
        Regex("""(?i)(api[_-]?key["'\s:=]+)[A-Za-z0-9\-._~+/]{8,}"""),
        Regex("""(?i)(sk-|gsk_|xi-|AIza)[A-Za-z0-9\-._~+/]{8,}"""),
        Regex("""(?i)(["']?(?:key|token|secret|password)["']?\s*[:=]\s*["']?)[A-Za-z0-9\-._~+/]{8,}"""),
    )

    private fun redact(s: String): String {
        var out = s
        for (p in secretPatterns) out = p.replace(out) { m -> (m.groupValues.getOrNull(1) ?: "") + "***" }
        return out
    }

    private fun todayFile(): File? {
        val d = dir ?: return null
        return File(d, "$PREFIX${daySuffix.get()!!.format(Date())}.log")
    }

    private fun appendLines(lines: List<String>) {
        val f = todayFile() ?: return
        runCatching {
            f.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    /** 刪掉過舊或超量的整天檔案。只在啟動時跑一次，不必每行都檢查。 */
    private fun rotate() {
        val d = dir ?: return
        runCatching {
            d.listFiles { f -> f.isFile && f.name.startsWith(LEGACY_PREFIX) }?.forEach { f ->
                val target = File(d, PREFIX + f.name.removePrefix(LEGACY_PREFIX))
                if (target.exists() || !f.renameTo(target)) f.delete()
            }
        }
        runCatching {
            val files = d.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }
                ?.sortedByDescending { it.name } ?: return
            var total = 0L
            files.forEachIndexed { index, f ->
                total += f.length()
                // index 0 是今天這一份，永遠不刪。
                // 沒有這個例外的話，單日就寫超過 8MB 時（跑診斷、連續失敗重試）
                // 下次開 App 會先把今天的紀錄刪掉——而那正是要查的那一份。
                if (index == 0) return@forEachIndexed
                if (index >= KEEP_DAYS || total > MAX_TOTAL_BYTES) f.delete()
            }
        }
    }

    /** 目前所有日誌檔（新到舊），給「匯出／分享」用。 */
    fun files(): List<File> =
        dir?.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }?.sortedByDescending { it.name }
            ?: emptyList()

    /** 把最近幾天的內容併成一份純文字，給分享 intent 用。 */
    fun dumpText(maxBytes: Int = 512 * 1024): String {
        val sb = StringBuilder()
        for (f in files()) {
            if (sb.length >= maxBytes) break
            sb.append("───── ").append(f.name).append(" ─────\n")
            sb.append(runCatching { f.readText() }.getOrDefault("(讀取失敗)\n"))
        }
        // files() 是「新到舊」，所以 sb 的開頭才是最新的紀錄。
        // 超量時要砍掉尾巴（最舊的），砍開頭等於把剛剛出問題的那幾行丟掉——
        // 而使用者按「分享日誌」正是因為剛剛出了問題。
        return if (sb.length > maxBytes) sb.substring(0, maxBytes) else sb.toString()
    }

    /** 使用者按「清除日誌」用。 */
    fun clear() {
        _recent.value = emptyList()
        writer.execute { files().forEach { it.delete() } }
    }
}
