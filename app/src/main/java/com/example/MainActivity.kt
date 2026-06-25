package com.example

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

class MainActivity : ComponentActivity() {

    // EyeGestures WebView 跨 composable 存活（用於 evaluateJavascript 重置校準）
    private var eyeGesturesWebViewRef: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: VoiceWeaverViewModel = viewModel()
            val settings by viewModel.settings.collectAsState()
            MyApplicationTheme(theme = settings.theme, fontScale = settings.fontScale) {
                LocaleProvider(language = settings.language) {
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    // 眨眼掃描：路由改變時更新全域 scan targets
                    val eyeSettings by viewModel.settings.collectAsState()
                    LaunchedEffect(currentRoute, eyeSettings.eyeControlEnabled, eyeSettings.eyeControlMode) {
                        if (!eyeSettings.eyeControlEnabled) return@LaunchedEffect
                        val tabs = listOf(
                            "重組" to "main",
                            "語音復健" to "rehab",
                            "報告" to "report",
                            "歷史" to "history",
                            "設定" to "settings"
                        )
                        viewModel.registerScanTargets(tabs.map { (label, route) ->
                            VoiceWeaverViewModel.ScanTarget(label) { navController.go(route) }
                        })
                    }
                    val palette = LocalAppPalette.current
                    val hasCustomBackground = settings.customBackgroundUri.isNotBlank()

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
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = if (hasCustomBackground) palette.darkBg.copy(alpha = 0.78f) else palette.darkBg,
                            bottomBar = {
                                NavigationBar(containerColor = if (hasCustomBackground) palette.surface.copy(alpha = 0.92f) else palette.surface) {
                                val labelMain = stringResource(R.string.nav_main)
                                val labelRehab = stringResource(R.string.nav_rehab)
                                val labelHistory = stringResource(R.string.nav_history)
                                val labelSettings = stringResource(R.string.nav_settings)
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = labelMain) },
                                    label = { Text(labelMain) },
                                    selected = currentRoute == "main",
                                    onClick = { navController.go("main") },
                                    colors = navColors
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.School, contentDescription = labelRehab) },
                                    label = { Text(labelRehab) },
                                    selected = currentRoute == "rehab",
                                    onClick = { navController.go("rehab") },
                                    colors = navColors
                                )
                                val labelReport = stringResource(R.string.nav_report)
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.BarChart, contentDescription = labelReport) },
                                    label = { Text(labelReport) },
                                    selected = currentRoute == "report",
                                    onClick = { navController.go("report") },
                                    colors = navColors
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.History, contentDescription = labelHistory) },
                                    label = { Text(labelHistory) },
                                    selected = currentRoute == "history",
                                    onClick = { navController.go("history") },
                                    colors = navColors
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = labelSettings) },
                                    label = { Text(labelSettings) },
                                    selected = currentRoute == "settings",
                                    onClick = { navController.go("settings") },
                                    colors = navColors
                                )
                                }
                            }
                        ) { innerPadding ->
                            NavHost(
                                navController = navController,
                                startDestination = "main",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .consumeWindowInsets(innerPadding)
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
                                    settingsRepo = viewModel.settingsRepository
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

                        // ── 全螢幕 overlay：放在 Scaffold 之外的最外層 Box，
                        //    才能蓋過底部導覽列，讓鼻控游標可以點到底部分頁。 ──
                        // 鼻子頭控背景 WebView：保持 VISIBLE 但縮到極小且近乎透明，確保 rAF 持續送幀。
                        val eyeState by viewModel.eyeControlState.collectAsState()
                        EyeGesturesOverlay(
                            enabled = eyeSettings.eyeControlEnabled,
                            onGaze = { x, y, cal -> viewModel.updateGazeFromEyeGestures(x, y, cal) },
                            onBlink = { viewModel.notifyEyeGesturesBlink() },
                            onStatus = { /* NoseTrackerOverlay already logs */ },
                            onReady = { wv -> eyeGesturesWebViewRef = wv },
                            modifier = Modifier
                                .requiredSize(2.dp)
                                .alpha(0.01f)
                        )
                        // 鼻控游標（覆蓋整個畫面，含底部導覽列）
                        GazeCursorOverlay(eyeState = eyeState, activity = this@MainActivity)
                        BlinkScanOverlay(viewModel = viewModel, eyeState = eyeState)
                    }
            }
        }
    }
}
}

/** NavController 共用導航設定（popUpTo main + singleTop）*/
private fun androidx.navigation.NavController.go(route: String) {
    navigate(route) {
        popUpTo("main")
        launchSingleTop = true
    }
}
