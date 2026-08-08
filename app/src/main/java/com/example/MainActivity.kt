package com.example

import com.example.ui.I18n

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ai.VoiceWeaverViewModel
import com.example.ui.AacScreen
import com.example.ui.BlinkScanOverlay
import com.example.ui.EyeGesturesOverlay
import com.example.ui.GazeCursorOverlay
import com.example.ui.HistoryScreen
import com.example.ui.LocaleProvider
import com.example.ui.RehabScreen
import com.example.ui.SettingsScreen
import com.example.ui.VoiceWeaverScreen
import com.example.ui.report.ReportScreen
import com.example.ui.settings.ExtendedSettingsScreen
import com.example.ui.training.StoryTrainingScreen
import com.example.ui.theme.LocalAppPalette
import com.example.ui.theme.MyApplicationTheme
import coil.compose.AsyncImage
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import com.example.demo.DemoController
import com.example.demo.ScreenRecordService
import com.example.ui.DemoOverlay
import com.example.ui.DemoPickerDialog

class MainActivity : ComponentActivity() {

    // EyeGestures WebView 跨 composable 存活（用於 evaluateJavascript 重置校準）
    private var eyeGesturesWebViewRef: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 盡量早——App 剛啟動那幾行（引擎選了哪個、金鑰讀到沒）往往就是後面出問題的原因。
        com.example.ai.DiagnosticLog.init(this)
        com.example.ai.DiagnosticLog.i(
            com.example.ai.DiagnosticLog.Cat.UI, "App 啟動",
            "版本=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "Android=${android.os.Build.VERSION.SDK_INT} 機型=${android.os.Build.MODEL}"
        )
        enableEdgeToEdge()
        setContent {
            val viewModel: VoiceWeaverViewModel = viewModel()
            val settings by viewModel.settings.collectAsState()
            MyApplicationTheme(theme = settings.theme, fontScale = settings.fontScale, highContrast = settings.highContrast) {
                LocaleProvider(language = settings.language) {
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    // 自動化測試側門：只在 debug 版接上。BuildConfig.DEBUG 是編譯期常數，
                    // release 建置時整段會被 R8 判定不可達而移除；接收廣播的 receiver
                    // 本來就只宣告在 src/debug/AndroidManifest.xml，正式版沒有入口。
                    if (BuildConfig.DEBUG) {
                        LaunchedEffect(navController) {
                            com.example.testing.TestHooks.navigator = { route -> navController.go(route) }
                            com.example.testing.TestHooks.attach(this@MainActivity, viewModel.testBridge)
                        }
                        LaunchedEffect(currentRoute) {
                            com.example.testing.TestHooks.currentRoute = currentRoute ?: ""
                        }
                    }

                    // 眨眼掃描：路由改變時更新全域 scan targets
                    val eyeSettings by viewModel.settings.collectAsState()
                    LaunchedEffect(currentRoute, eyeSettings.eyeControlEnabled, eyeSettings.eyeControlMode) {
                        if (!eyeSettings.eyeControlEnabled) return@LaunchedEffect
                        val tabs = listOf(
                            I18n.tr("重組") to "main",
                            I18n.tr("圖卡") to "aac",
                            I18n.tr("語音復健") to "rehab",
                            I18n.tr("報告") to "report",
                            I18n.tr("歷史") to "history",
                            I18n.tr("設定") to "settings"
                        )
                        viewModel.registerScanTargets(tabs.map { (label, route) ->
                            VoiceWeaverViewModel.ScanTarget(label) { navController.go(route) }
                        })
                    }
                    // ── 一鍵自動演示 ＋ 螢幕錄影 ──
                    // 順序是被 Android 14 綁死的：先啟動 foregroundServiceType="mediaProjection"
                    // 的前景服務，之後才拿得到 MediaProjection。而且授權那張系統對話框只能
                    // 由使用者手勢觸發，所以旁白要先合成完（可能好幾分鐘）、再要授權、再開錄，
                    // 否則授權票券放太久會失效。
                    var showDemoPicker by rememberSaveable { mutableStateOf(false) }
                    val demoScope = rememberCoroutineScope()
                    val projectionConsent = remember { mutableStateOf<CompletableDeferred<Pair<Int, Intent>?>?>(null) }
                    val projectionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { res ->
                        val data = res.data
                        projectionConsent.value?.complete(
                            if (res.resultCode == RESULT_OK && data != null) res.resultCode to data else null
                        )
                        projectionConsent.value = null
                    }

                    val palette = LocalAppPalette.current
                    val hasCustomBackground = settings.customBackgroundUri.isNotBlank()

                    // 視窗寬度決定導覽擺哪：短邊不足時（橫向手機）底部導覽會吃掉本來就
                    // 不夠的高度，圖卡整片被擠到畫面外。寬螢幕改用側邊導覽軌，把垂直
                    // 空間全留給內容。600dp 是 Material 的 compact/medium 分界。
                    val cfg = LocalConfiguration.current
                    val useRail = cfg.screenWidthDp >= 600
                    // 橫向手機的短邊只剩約 390dp，六個「圖示＋文字」的軌道項要 430dp 以上，
                    // 最後一項（設定）會被切掉。矮螢幕改成只顯示圖示，並讓軌道可捲動保底。
                    val compactRail = cfg.screenHeightDp < 480
                    val tabs = listOf(
                        NavTab("main",     Icons.Default.Home,     stringResource(R.string.nav_main)),
                        NavTab("rehab",    Icons.Default.School,   stringResource(R.string.nav_rehab)),
                        // 圖卡（AAC）獨立成一級分頁：對很多患者這才是最常用的輸入方式，
                        // 藏在重組畫面的按鈕後面等於多一步才能講話
                        NavTab("aac",      Icons.Default.GridView, I18n.tr("圖卡")),
                        NavTab("report",   Icons.Default.BarChart, stringResource(R.string.nav_report)),
                        NavTab("history",  Icons.Default.History,  stringResource(R.string.nav_history)),
                        NavTab("settings", Icons.Default.Settings, stringResource(R.string.nav_settings)),
                    )

                    val navColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = palette.neonCyan,
                        selectedTextColor = palette.neonCyan,
                        unselectedIconColor = palette.textMuted,
                        unselectedTextColor = palette.textMuted,
                        indicatorColor = palette.darkBg
                    )

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (hasCustomBackground) {
                            AsyncImage(
                                model = settings.customBackgroundUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Row(modifier = Modifier.fillMaxSize()) {
                        if (useRail) {
                            NavigationRail(
                                containerColor = if (hasCustomBackground) palette.surface.copy(alpha = 0.92f) else palette.surface,
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // 導覽軌垂直置中：橫向手機的軌道很矮，靠上排會離拇指太遠
                                if (!compactRail) Spacer(Modifier.weight(1f))
                                tabs.forEach { tab ->
                                    NavigationRailItem(
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = if (compactRail) null else {
                                            { Text(tab.label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        },
                                        selected = currentRoute == tab.route,
                                        onClick = { navController.go(tab.route) },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = palette.neonCyan,
                                            selectedTextColor = palette.neonCyan,
                                            unselectedIconColor = palette.textMuted,
                                            unselectedTextColor = palette.textMuted,
                                            indicatorColor = palette.darkBg
                                        )
                                    )
                                }
                                if (!compactRail) Spacer(Modifier.weight(1f))
                            }
                        }
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = if (hasCustomBackground) palette.darkBg.copy(alpha = 0.78f) else palette.darkBg,
                            bottomBar = {
                                // 寬螢幕改用左側導覽軌（在 Scaffold 外層），這裡就不重複出現
                                if (!useRail) {
                                    // NavigationBar 是固定 80dp 高、每格等寬的。字級拉大時
                                    // 11sp 的「重組／復健」塞不進那一格，會被縮成純粹的「…」——
                                    // 六個一模一樣的「…」比沒有標籤還糟。圖示本身就認得出來，
                                    // 而且選中的那個有底色，所以字大到放不下就直接不顯示文字。
                                    val fsNav = LocalDensity.current.fontScale.coerceAtLeast(0.1f)
                                    val navLabels = fsNav < 1.8f
                                    NavigationBar(containerColor = if (hasCustomBackground) palette.surface.copy(alpha = 0.92f) else palette.surface) {
                                        tabs.forEach { tab ->
                                            NavigationBarItem(
                                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                                label = if (!navLabels) null else {
                                                    { Text(tab.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                                },
                                                selected = currentRoute == tab.route,
                                                onClick = { navController.go(tab.route) },
                                                colors = navColors
                                            )
                                        }
                                    }
                                }
                            }
                        ) { innerPadding ->
                            // 平板／橫向：內容置中並收在可讀寬度內。整行拉到兩千像素會讓
                            // 眼睛從行尾找不回行首，對失語症患者更吃力。
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .consumeWindowInsets(innerPadding),
                                contentAlignment = Alignment.TopCenter
                            ) {
                            NavHost(
                                navController = navController,
                                startDestination = "main",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .widthIn(max = 840.dp)
                            ) {
                            composable("main") {
                                VoiceWeaverScreen(
                                    viewModel = viewModel,
                                    onPracticeSentence = { sentence ->
                                        viewModel.startRehab(sentence)
                                        navController.go("rehab")
                                    },
                                    onOpenAacFull = { navController.go("aac") }
                                )
                            }
                            composable("rehab") {
                                RehabScreen(
                                    viewModel = viewModel,
                                    onTraining = { navController.go("training") }
                                )
                            }
                            composable("aac") { AacScreen(viewModel = viewModel) }
                            // ── 我的專屬發音（com.example.aphasia）──────────────
                            // 從「設定 → 我的專屬發音」進來。刻意不放頂層分頁：
                            // 這是一次性設定，設好之後就在主畫面的收音流程裡自動生效。
                            composable("phrase_shortcuts") {
                                val graph = viewModel.aphasiaGraph
                                val vm: com.example.aphasia.presentation.settings.PhraseShortcutViewModel =
                                    viewModel {
                                        com.example.aphasia.presentation.settings.PhraseShortcutViewModel(
                                            graph,
                                            com.example.aphasia.data.local.PhraseLibrarySource(applicationContext),
                                            viewModel.settingsRepository,
                                        )
                                    }
                                com.example.aphasia.presentation.settings.PhraseShortcutScreen(
                                    viewModel = vm,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("history") {
                                HistoryScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    viewModel = viewModel,
                                    onAdvanced = { navController.go("settings_extended") },
                                    onTeachSound = { navController.go("phrase_shortcuts") },
                                    onOpenDemo = { showDemoPicker = true },
                                    onStartCalibration = {
                                        // 呼叫 EyeGestures JS 重置校準
                                        eyeGesturesWebViewRef?.evaluateJavascript("window.egRecalibrate && window.egRecalibrate();", null)
                                        viewModel.recalibrateEyeGestures()
                                    }
                                )
                            }
                            composable("report") {
                                ReportScreen(
                                    aggregator = viewModel.reportAggregator,
                                    settingsRepo = viewModel.settingsRepository,
                                    onExportCsv = { viewModel.buildRehabCsvShare() },
                                    onExportPdf = { viewModel.buildRehabPdfShare() }
                                )
                            }
                            composable("training") {
                                val tier = viewModel.deviceTier.collectAsState().value
                                StoryTrainingScreen(
                                    developerMode = settings.developerMode,
                                    deviceTier = tier,
                                    engine = viewModel.currentEngine,
                                    dao = viewModel.storyTrainingDao
                                )
                            }
                            composable("settings_extended") {
                                ExtendedSettingsScreen(
                                    settingsRepo = viewModel.settingsRepository,
                                    onClearAllHistory = { viewModel.clearAllHistory() },
                                    onImportCorpus = { items -> viewModel.appendCorpus(items) },
                                    onUseCurrentGpsAsHome = { viewModel.useCurrentGpsAsHome() }
                                )
                            }
                        }
                        // 危機介入「與家人對話」視窗（Dialog 自成 window）
                        com.example.ui.CrisisConversationWindow(viewModel = viewModel)
                        }
                        }   // Box（內容置中）
                        }   // Row（導覽軌 + 內容）

                        // ── 全螢幕 overlay：放在 Scaffold 之外的最外層 Box，
                        //    才能蓋過底部導覽列，讓鼻控游標可以點到底部分頁。 ──
                        // 鼻子頭控背景 WebView：保持 VISIBLE 但縮到極小且近乎透明，確保 rAF 持續送幀。
                        val eyeState by viewModel.eyeControlState.collectAsState()
                        // 鼻控靈敏度：設定頁滑桿改動 → 即時注入 nose_tracker.html
                        LaunchedEffect(settings.noseSensitivity, eyeSettings.eyeControlEnabled) {
                            eyeGesturesWebViewRef?.evaluateJavascript(
                                "window.egSetSensitivity && window.egSetSensitivity(${settings.noseSensitivity});", null
                            )
                        }
                        EyeGesturesOverlay(
                            enabled = eyeSettings.eyeControlEnabled,
                            onGaze = { x, y, cal -> viewModel.updateGazeFromEyeGestures(x, y, cal) },
                            onBlink = { viewModel.notifyEyeGesturesBlink() },
                            onStatus = { /* NoseTrackerOverlay already logs */ },
                            lang = settings.language.tag,
                            onReady = { wv ->
                                eyeGesturesWebViewRef = wv
                                wv?.evaluateJavascript(
                                    "window.egSetSensitivity && window.egSetSensitivity(${settings.noseSensitivity});", null
                                )
                            },
                            modifier = Modifier
                                .requiredSize(2.dp)
                                .alpha(0.01f)
                        )
                        // 鼻控游標（覆蓋整個畫面，含底部導覽列）
                        GazeCursorOverlay(eyeState = eyeState, activity = this@MainActivity)
                        BlinkScanOverlay(viewModel = viewModel, eyeState = eyeState)

                        // 錄影時畫面上不再有 Stop 鈕（那顆會被錄進影片，做報告很難看）。
                        // 實體返回鍵當中止出口：看不見，但隨時按得到。
                        val demoRunning by DemoController.active.collectAsState()
                        androidx.activity.compose.BackHandler(enabled = demoRunning) {
                            DemoController.stop()
                            ScreenRecordService.stopRecording(this@MainActivity)
                        }
                        DemoOverlay(onStop = {
                            DemoController.stop()
                            ScreenRecordService.stopRecording(this@MainActivity)
                        })
                        if (showDemoPicker) {
                            DemoPickerDialog(
                                voiceReady = viewModel.demoVoiceReady(),
                                onDismiss = { showDemoPicker = false },
                                onStart = { ids, narrate, record, audio ->
                                    showDemoPicker = false
                                    val act = this@MainActivity
                                    DemoController.start(
                                        scope = demoScope,
                                        ids = ids,
                                        narrate = narrate,
                                        voiceTag = viewModel.demoVoiceTag(),
                                        navigate = { route -> navController.go(route) },
                                        stage = object : DemoController.Stage {
                                            override fun type(text: String) = viewModel.demoTypeFragments(text)
                                            override fun candidates(list: List<String>) = viewModel.demoShowCandidates(list)
                                            override fun pick(index: Int) = viewModel.demoPickCandidate(index)
                                            override fun aacCategory(key: String?) = viewModel.demoAacCategory(key)
                                            override fun aacSentence(ids: List<String>) = viewModel.demoAacSentence(ids)
                                            override fun rehabTarget(sentence: String) = viewModel.demoRehabTarget(sentence)
                                            override fun fontScale(scale: Float) { viewModel.demoFontScale(scale) }
                                            override fun language(tag: String) { viewModel.demoLanguage(tag) }
                                            override fun severity(name: String) { viewModel.demoSeverity(name) }
                                            override fun scrollToResult() = viewModel.demoScrollToResult()
                                            override fun press(tag: String?) = viewModel.demoPress(tag)
                                            override fun speakAloud(text: String, done: () -> Unit) {
                                                viewModel.demoSpeakAloud(text, done)
                                            }
                                            override fun crisisOpen(status: String) = viewModel.demoCrisisOpen(status)
                                            override fun crisisSay(who: String, text: String) = viewModel.demoCrisisSay(who, text)
                                            override fun crisisStatus(status: String) = viewModel.demoCrisisStatus(status)
                                            override fun crisisClose() = viewModel.demoCrisisClose()
                                            override fun safetyLock(message: String?) = viewModel.demoSafetyLock(message)
                                            override fun snapshotSettings() = viewModel.demoSnapshotSettings()
                                            override fun clear() = viewModel.demoClearScreen()
                                        },
                                        prerecord = { line, done -> viewModel.demoPrerecord(line, done) },
                                        speakFallback = { line, done -> viewModel.demoSpeak(line, done) },
                                        onReady = {
                                            if (!record) return@start
                                            ScreenRecordService.startForegroundOnly(act)
                                            val waiter = CompletableDeferred<Pair<Int, Intent>?>()
                                            projectionConsent.value = waiter
                                            val mpm = act.getSystemService(MediaProjectionManager::class.java)
                                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                                            val granted = waiter.await()
                                            if (granted != null) {
                                                val m = android.util.DisplayMetrics()
                                                @Suppress("DEPRECATION")
                                                act.windowManager.defaultDisplay.getRealMetrics(m)
                                                ScreenRecordService.beginRecording(
                                                    act, granted.first, granted.second,
                                                    m.widthPixels, m.heightPixels, m.densityDpi, audio
                                                )
                                                // 等畫面真的開始寫進檔案才開演。編碼器從啟動到吐出
                                                // 第一張要一兩秒（依機型而異），固定 delay 不是猜太短
                                                // 就是白等；猜太短的話演示開頭連同第一句旁白會被切掉。
                                                kotlinx.coroutines.withTimeoutOrNull(5000) {
                                                    ScreenRecordService.capturing.first { it }
                                                }
                                                kotlinx.coroutines.delay(300)
                                            } else {
                                                ScreenRecordService.stopRecording(act)
                                            }
                                        },
                                        onFinish = { if (record) ScreenRecordService.stopRecording(act) },
                                        onNarrationMissing = { missing, total ->
                                            android.widget.Toast.makeText(
                                                act,
                                                I18n.tr("旁白錄不起來（{n}/{total} 句）：連不到電腦端的 GPT-SoVITS。請確認語音中心開著、位址設定正確，再試一次。")
                                                    .replace("{n}", missing.toString())
                                                    .replace("{total}", total.toString()),
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        },
                                    )
                                }
                            )
                        }
                    }
            }
        }
    }
}
}

/** 一個一級分頁。底部導覽列與側邊導覽軌共用同一份定義，兩邊不會漂移。 */
private data class NavTab(
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String
)

/** NavController 共用導航設定（popUpTo main + singleTop）*/
private fun androidx.navigation.NavController.go(route: String) {
    navigate(route) {
        popUpTo("main")
        launchSingleTop = true
    }
}
