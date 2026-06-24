package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ai.VoiceWeaverViewModel
import com.example.ui.AacScreen
import com.example.ui.EyeCalibrationScreen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: VoiceWeaverViewModel = viewModel()
            // 訂閱設定，主題切換立即重組整顆樹
            val settings by viewModel.settings.collectAsState()
            MyApplicationTheme(theme = settings.theme, fontScale = settings.fontScale) {
                // 語言切換在主題之內、Scaffold 之外，保證 nav/topbar/全部 stringResource 一致
                LocaleProvider(language = settings.language) {
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
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
                                // AAC 分頁已併入「重組」主畫面（AacInlinePanel）— 此處不再列。
                                // 進階模式（沉浸式 + 評分）的 AacScreen 仍透過 route="aac" 保留，但不放在底部列上。
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
                                    onStartCalibration = { navController.go("eye_calibration") }
                                )
                            }
                            composable("eye_calibration") {
                                EyeCalibrationScreen(
                                    manager = viewModel.eyeControlManager,
                                    onDone = { navController.popBackStack() }
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
                        // 危機介入「與家人對話」視窗：偵測到危機詞語時覆蓋所有畫面（Dialog 獨立 Window）
                        com.example.ui.CrisisConversationWindow(viewModel = viewModel)
                    }
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
