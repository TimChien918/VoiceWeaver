package com.example.testing

import android.util.Log
import android.view.Choreographer
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 自動化測試掛勾（**只在 debug 版存在作用**）。
 *
 * ## 為什麼需要
 * 這個 App 的幾個關鍵行為靠「時間累積」才會出現——推薦列要按滿三張卡才浮出來、
 * 時間衰減要等七天才看得到差別、地點加成要人真的走到醫院。手動測要花好幾天，
 * 而且測不到邊界。這裡開的是**注入用的側門**：直接把統計、地點、字級塞進去，
 * 一秒鐘重現原本要等一週的狀態。
 *
 * ## 安全設計（三層，缺一不可）
 * 1. **Release 版沒有入口**：[TestCommandReceiver] 與其 `<receiver>` 宣告只存在於
 *    `app/src/debug/`，release APK 裡連這個 class 都不會被打包，外界沒有任何管道
 *    能觸發下面的指令。
 * 2. **編譯期短路**：本檔每個對外入口第一行都是 `if (!BuildConfig.DEBUG) return`。
 *    `BuildConfig.DEBUG` 是編譯期常數，release 建置時 R8 會把整段判定為不可達而移除。
 * 3. **每次啟動換一把權杖**：[attach] 產生隨機 token 並只寫進 logcat。Android 4.1 起
 *    一般 App 讀不到別人的 logcat，所以同機的惡意程式拿不到 token，也就送不進指令
 *    （擋掉「裝了 debug 版就被隔壁 App 遙控」這個唯一殘留風險）。
 *
 * 另外：會刪資料的指令一律要求額外帶 `confirm=true`，避免測試腳本手滑清掉真實紀錄。
 */
object TestHooks {

    const val TAG = "VW_TEST"

    /** 廣播 action：只有 debug manifest 會註冊接收端。 */
    const val ACTION = "com.example.TEST_COMMAND"

    /** 本次啟動的權杖。空字串 = 尚未 attach，任何指令都會被拒。 */
    @Volatile
    private var token: String = ""

    /** MainActivity 註冊的導航跳頁 lambda（測試腳本用 `nav` 指令切分頁）。 */
    @Volatile
    var navigator: ((String) -> Unit)? = null

    /** 由 ViewModel 注入的實作。拆成介面是為了讓 TestHooks 不反向依賴 ai 模組。 */
    interface Bridge {
        fun injectAacUsage(itemId: String, score: Double, ageDays: Double, locationTag: String)
        fun clearAacUsage()
        fun injectLocation(lat: Double, lon: Double)
        fun injectLocationTag(tag: String)
        fun setFontScale(scale: Float)
        fun setTheme(name: String, highContrast: Boolean)
        fun resetAllData()
        /** 回傳 App 當下的狀態快照（JSON 物件，交由 [dumpState] 補上 UI 層資訊）。 */
        fun snapshot(): JSONObject
    }

    @Volatile
    private var bridge: Bridge? = null

    // ─────────────────────────────────────────────────────────────
    // UI 層回報：Compose 的區域狀態（搜尋字串、格數）只有 composable 自己知道，
    // 由畫面主動回報進來，dump 才拿得到「螢幕上真正顯示的東西」而不是猜的。
    // ─────────────────────────────────────────────────────────────

    private val _aacUi = MutableStateFlow(AacUiSnapshot())
    val aacUi: StateFlow<AacUiSnapshot> = _aacUi.asStateFlow()

    data class AacUiSnapshot(
        val searchQuery: String = "",
        val selectedCategory: String = "",
        val gridItemCount: Int = 0,
        val feedItemCount: Int = 0,
        val feedVisible: Boolean = false,
        val sentenceLength: Int = 0,
        val flashcardMode: Boolean = false
    )

    fun reportAacUi(snapshot: AacUiSnapshot) {
        if (!BuildConfig.DEBUG) return
        _aacUi.value = snapshot
    }

    /**
     * 排序統計的版本號。注入或清空統計後 +1，讓畫面上 `remember` 住的推薦列重算。
     *
     * 為什麼要一個計數器：[com.example.ai.AacUsageRanker] 是物件單例，改它不會動到任何
     * StateFlow；而想靠「重發一次 aacState」來觸發也沒用——StateFlow 會用 equals 去重，
     * 發一個內容相同的 data class 等於沒發。把這個版本號放進 remember 的 key 才是可靠的。
     * release 版沒有任何寫入點，值恆為 0，等同不存在。
     */
    private val _rankerEpoch = MutableStateFlow(0)
    val rankerEpoch: StateFlow<Int> = _rankerEpoch.asStateFlow()

    /** 注入給 AAC 搜尋框的字串；畫面取用後自己歸零（一次性訊號）。 */
    val injectedSearch = MutableStateFlow<String?>(null)

    fun consumeInjectedSearch() {
        if (!BuildConfig.DEBUG) return
        injectedSearch.value = null
    }

    /**
     * 權限強制覆寫：permission 名稱 → 是否視為已授權。空 Map = 完全用系統真實狀態，
     * 而 release 版**永遠是空的**（唯一的寫入點 [execute] 在 release 直接回 "disabled"，
     * 而且觸發它的 receiver 根本沒被打包）。
     *
     * 畫面端這樣用，一行就退回系統真實值：
     * ```
     * val forced by TestHooks.forcedPermissions.collectAsState()
     * val granted = forced["MICROPHONE"] ?: micPerm.status.isGranted
     * ```
     *
     * 為什麼不直接用 `adb shell pm revoke`：那個指令會殺掉 App 行程，看不到「授權
     * 在使用中被撤銷」的那一瞬間畫面怎麼切換。這個覆寫不重啟就能切，專測那個瞬間。
     * 要驗證真實授權流程時仍該用 `pm grant/revoke`，兩者互補。
     */
    private val _forcedPermissions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val forcedPermissions: StateFlow<Map<String, Boolean>> = _forcedPermissions.asStateFlow()

    // ─────────────────────────────────────────────────────────────
    // 生命週期
    // ─────────────────────────────────────────────────────────────

    /**
     * 由 MainActivity 在 debug 版啟動時呼叫，產生本次啟動的權杖。
     *
     * 權杖寫進 App 的**內部**儲存（`filesDir`），測試腳本用 `adb shell run-as` 讀。
     *
     * 為什麼不是外部目錄（`/sdcard/Android/data/<pkg>/files/`）：這個專案的 debug 版
     * 就是使用者日常在用的版本，不是只跑在開發機上。外部目錄雖然擋得住一般 App，
     * 但拿到「所有檔案存取權」的檔案管理器讀得到——等於權杖可能外流到真實使用者的
     * 手機上。內部儲存沒有這個縫：只有 App 自己和 `run-as`（僅限可除錯的 App）進得去。
     *
     * 同時也印一份到 logcat，但**不能只靠 logcat**：部分機型（實測 ASUS ROG）
     * 會把一般 App 的 log 整個過濾掉，撈不到任何東西。
     */
    fun attach(context: android.content.Context, bridge: Bridge) {
        if (!BuildConfig.DEBUG) return
        this.bridge = bridge
        if (token.isEmpty()) {
            token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        }
        runCatching {
            java.io.File(context.filesDir, TOKEN_FILE).writeText(token)
        }.onFailure { Log.w(TAG, "權杖檔寫入失敗，只能從 logcat 取：${it.message}") }
        Log.i(TAG, "ready token=$token")
    }

    const val TOKEN_FILE = "vw_test_token"

    fun detach() {
        if (!BuildConfig.DEBUG) return
        bridge = null
        navigator = null
        stopJankMonitor()
    }

    // ─────────────────────────────────────────────────────────────
    // 指令分派
    // ─────────────────────────────────────────────────────────────

    /**
     * 執行一條測試指令。回傳給呼叫端的字串同時會寫進 logcat（`VW_TEST` tag），
     * 測試腳本靠 grep 這行拿結果。
     */
    fun execute(command: String, args: Map<String, String>): String {
        if (!BuildConfig.DEBUG) return "disabled"

        // ── 權杖檢查：擋掉同機其他 App 亂送廣播 ──
        if (token.isEmpty()) return fail("not_attached")
        if (args["token"] != token) {
            Log.w(TAG, "REJECT command=$command reason=bad_token")
            return fail("bad_token")
        }

        val b = bridge ?: return fail("no_bridge")

        return try {
            when (command) {
                // ── P0-1 使用統計注入 ──
                "inject_usage" -> {
                    val itemId = args["itemId"] ?: return fail("missing itemId")
                    val score = args["score"]?.toDoubleOrNull() ?: 1.0
                    // ageDays：讓這筆統計「看起來是幾天前按的」，用來測時間衰減。
                    val ageDays = args["ageDays"]?.toDoubleOrNull() ?: 0.0
                    val loc = args["location"] ?: ""
                    b.injectAacUsage(itemId, score, ageDays, loc)
                    _rankerEpoch.value++
                    ok("injected itemId=$itemId score=$score ageDays=$ageDays")
                }
                "clear_usage" -> {
                    b.clearAacUsage()
                    _rankerEpoch.value++
                    ok("usage cleared")
                }

                // ── P0-2 地點注入 ──
                "inject_location" -> {
                    val tag = args["tag"]
                    if (!tag.isNullOrBlank()) {
                        b.injectLocationTag(tag)
                        ok("location tag=$tag")
                    } else {
                        val lat = args["lat"]?.toDoubleOrNull() ?: return fail("missing lat/tag")
                        val lon = args["lon"]?.toDoubleOrNull() ?: return fail("missing lon")
                        b.injectLocation(lat, lon)
                        ok("location lat=$lat lon=$lon (geocoding…)")
                    }
                }

                // ── P0-3 狀態匯出 ──
                "dump" -> dumpState()

                // ── P1-4 權限覆寫 ──
                "force_permission" -> {
                    val perm = args["permission"] ?: return fail("missing permission")
                    when (val g = args["granted"]) {
                        "clear" -> {
                            _forcedPermissions.value = _forcedPermissions.value - perm
                            ok("permission $perm → 跟隨系統")
                        }
                        else -> {
                            val granted = g?.toBooleanStrictOrNull() ?: return fail("granted 需為 true/false/clear")
                            _forcedPermissions.value = _forcedPermissions.value + (perm to granted)
                            ok("permission $perm → $granted")
                        }
                    }
                }

                // ── P1-5 字級 ──
                "set_font_scale" -> {
                    val scale = args["scale"]?.toFloatOrNull() ?: return fail("missing scale")
                    b.setFontScale(scale)
                    ok("fontScale=$scale")
                }

                // ── P1-6 主題 ──
                "set_theme" -> {
                    val name = args["theme"] ?: return fail("missing theme")
                    val hc = args["highContrast"]?.toBooleanStrictOrNull() ?: false
                    b.setTheme(name, hc)
                    ok("theme=$name highContrast=$hc")
                }

                // ── P2-7 搜尋框填字 ──
                "search" -> {
                    val q = args["query"] ?: ""
                    injectedSearch.value = q
                    ok("search='$q'")
                }

                // ── P2-8 掉幀監控 ──
                "jank" -> {
                    when (args["on"]?.toBooleanStrictOrNull()) {
                        true -> { startJankMonitor(); ok("jank monitor on") }
                        false -> { val r = stopJankMonitor(); ok("jank monitor off, $r") }
                        else -> fail("on 需為 true/false")
                    }
                }

                // ── P2-9 跳分頁（讓腳本不用靠寫死的座標點導覽列）──
                "nav" -> {
                    val route = args["route"] ?: return fail("missing route")
                    val n = navigator ?: return fail("navigator 未註冊")
                    n(route)
                    ok("nav → $route")
                }

                // ── P2-10 全清（破壞性：額外要 confirm）──
                "reset_data" -> {
                    if (args["confirm"]?.toBooleanStrictOrNull() != true) {
                        return fail("reset_data 會刪掉全部歷史，需加 --es confirm true")
                    }
                    b.resetAllData()
                    _forcedPermissions.value = emptyMap()
                    _rankerEpoch.value++
                    ok("all data reset")
                }

                "ping" -> ok("pong")

                else -> fail("unknown command '$command'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "command=$command 執行失敗", e)
            fail("exception ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun ok(msg: String): String {
        Log.i(TAG, "OK $msg")
        return "OK $msg"
    }

    private fun fail(msg: String): String {
        Log.w(TAG, "ERR $msg")
        return "ERR $msg"
    }

    /**
     * 把 ViewModel 狀態 + Compose 畫面回報的 UI 狀態合成一份 JSON 印進 logcat。
     * 驗收「搜尋清空了沒」「推薦列該不該出現」這種問題，靠肉眼看截圖不可靠，
     * 直接比對數字才是可自動化的。
     */
    private fun dumpState(): String {
        val b = bridge ?: return fail("no_bridge")
        val root = b.snapshot()
        val ui = _aacUi.value
        root.put("aac_ui", JSONObject()
            .put("search_query", ui.searchQuery)
            .put("selected_category", ui.selectedCategory)
            .put("grid_item_count", ui.gridItemCount)
            .put("feed_item_count", ui.feedItemCount)
            .put("feed_visible", ui.feedVisible)
            .put("sentence_length", ui.sentenceLength)
            .put("flashcard_mode", ui.flashcardMode))
        root.put("forced_permissions", JSONObject().apply {
            _forcedPermissions.value.forEach { (k, v) -> put(k, v) }
        })
        root.put("jank", JSONObject()
            .put("monitoring", jankCallback != null)
            .put("dropped_frames", droppedFrames)
            .put("worst_frame_ms", worstFrameMs))
        // 整包 JSON 走廣播的 resultData 回給 adb（`am broadcast` 會把它印在終端機上），
        // 不走 logcat：有些機型會過濾掉一般 App 的 log，而且單行還有長度上限。
        return root.toString()
    }

    // ─────────────────────────────────────────────────────────────
    // 掉幀監控
    // ─────────────────────────────────────────────────────────────

    private const val FRAME_BUDGET_MS = 16.67

    @Volatile private var jankCallback: Choreographer.FrameCallback? = null
    @Volatile private var droppedFrames = 0
    @Volatile private var worstFrameMs = 0.0
    private var lastFrameNanos = 0L

    private fun startJankMonitor() {
        if (jankCallback != null) return
        droppedFrames = 0
        worstFrameMs = 0.0
        lastFrameNanos = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameNanos != 0L) {
                    val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
                    if (deltaMs > FRAME_BUDGET_MS * 1.5) {
                        droppedFrames++
                        if (deltaMs > worstFrameMs) worstFrameMs = deltaMs
                        Log.d(TAG, "JANK ${"%.1f".format(deltaMs)}ms")
                    }
                }
                lastFrameNanos = frameTimeNanos
                if (jankCallback === this) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        jankCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun stopJankMonitor(): String {
        val cb = jankCallback ?: return "未啟動"
        jankCallback = null
        Choreographer.getInstance().removeFrameCallback(cb)
        return "dropped=$droppedFrames worst=${"%.1f".format(worstFrameMs)}ms"
    }
}
