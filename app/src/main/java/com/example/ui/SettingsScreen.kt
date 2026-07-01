package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ai.DeviceMetricSample
import com.example.ai.DeviceTier
import com.example.ai.ModelDownloadState
import com.example.ai.ModelProbeState
import com.example.ai.VoiceWeaverViewModel
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.AppTheme
import com.example.data.CloudProvider
import com.example.data.EyeControlMode
import com.example.data.GptSovitVoiceMode
import com.example.data.PowerMode
import com.example.ui.theme.LocalAppPalette
import com.example.ui.theme.iosReadable
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File

/**
 * App 全域設定分頁（底部 4th tab）。
 * 區塊：視覺風格 / 耗能模式 / 介面語言 / 朗讀語速 / 字體大小 / 其他。
 * 所有變更立即寫入 DataStore；主題、語言、語速立即生效（語言透過 LocaleProvider）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    viewModel: VoiceWeaverViewModel,
    onAdvanced: () -> Unit = {},
    onStartCalibration: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsState()
    val deviceRam by viewModel.deviceRam.collectAsState()
    val deviceTier by viewModel.deviceTier.collectAsState()
    var pendingEyeEnable by remember { mutableStateOf(false) }
    val cameraPerm = rememberPermissionState(android.Manifest.permission.CAMERA)
    // 等權限授予後才真正啟動眼控（避免先啟動後權限尚未授予的競爭）
    LaunchedEffect(cameraPerm.status.isGranted) {
        if (cameraPerm.status.isGranted && pendingEyeEnable) {
            viewModel.setEyeControlEnabled(true)
            pendingEyeEnable = false
        }
    }
    val modelDownloadState by viewModel.modelDownloadState.collectAsState()
    val modelProbeState by viewModel.modelProbeState.collectAsState()
    val metrics by viewModel.deviceMetrics.collectAsState()
    val sovitsSynthesisStatus by viewModel.sovitsSynthesisStatus.collectAsState()
    val p = LocalAppPalette.current
    val context = LocalContext.current
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setCustomBackgroundUri(uri.toString())
        }
    }

    // 雲端同步（Firebase）：Google 登入 launcher + 狀態
    val cloudUser by viewModel.cloudUser.collectAsState()
    val cloudStatus by viewModel.cloudStatus.collectAsState()
    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        runCatching {
            val acct = com.google.android.gms.auth.api.signin.GoogleSignIn
                .getSignedInAccountFromIntent(res.data)
                .getResult(com.google.android.gms.common.api.ApiException::class.java)
            viewModel.cloudSignInWithGoogle(acct.idToken)
        }.onFailure { e ->
            val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
            viewModel.cloudSignInWithGoogle(null, code)
        }
    }

    // API 金鑰管理浮動視窗
    var showApiKeys by remember { mutableStateOf(false) }
    if (showApiKeys) {
        ApiKeysPanel(viewModel = viewModel, onDismiss = { showApiKeys = false })
    }
    // AI 生圖浮動視窗
    var showImageGen by remember { mutableStateOf(false) }
    if (showImageGen) {
        ImageGenPanel(viewModel = viewModel, onDismiss = { showImageGen = false })
    }
    // 進階設定浮動視窗（緊急聯絡人 / 住家 / 清歷史 / 重置 / 匯入語料庫）
    var showExtended by remember { mutableStateOf(false) }
    if (showExtended) {
        FloatingPanel(title = I18n.tr("進階設定"), icon = Icons.Default.Tune, onDismiss = { showExtended = false }, scrollable = false) {
            com.example.ui.settings.ExtendedSettingsScreen(
                settingsRepo = viewModel.settingsRepository,
                onClearAllHistory = { viewModel.clearAllHistory() },
                onImportCorpus = { items -> viewModel.appendCorpus(items) },
                onUseCurrentGpsAsHome = { viewModel.useCurrentGpsAsHome() },
                embedded = true
            )
        }
    }
    // 開發者模式（獨立子頁，iOS 風整頁）
    var showDeveloper by remember { mutableStateOf(false) }
    if (showDeveloper) {
        val locationResult by viewModel.locationResult.collectAsState()
        val offloadPlan by viewModel.offloadPlan.collectAsState()
        LaunchedEffect(Unit) { viewModel.refreshOffloadPlan() }
        FloatingPanel(title = I18n.tr("開發者模式"), icon = Icons.Default.DeveloperMode, onDismiss = { showDeveloper = false }) {
            DeveloperModeSection(
                enabled = settings.developerMode,
                offloadPlan = offloadPlan,
                onRefreshOffload = { viewModel.refreshOffloadPlan() },
                onOpenImageGen = { showImageGen = true },
                metrics = metrics,
                hardware = viewModel.deviceHardware,
                modelDownloadState = modelDownloadState,
                modelProbeState = modelProbeState,
                locationResult = locationResult,
                onEnabledChange = { viewModel.setDeveloperMode(it) },
                onTestUrl = { viewModel.testModelUrl(it) },
                onDownload = { viewModel.startModelDownload(it) },
                onCancel = { viewModel.cancelModelDownload() },
                onSimulateLocation = { lat, lon -> viewModel.simulateLocation(lat, lon) },
                settings = settings,
                onGptSovitEnabled = { viewModel.setGptSovitEnabled(it) },
                onGptSovitVoiceMode = { viewModel.setGptSovitVoiceMode(it) },
                onChineseVoiceUri = { viewModel.setGptSovitChineseVoiceUri(it) },
                onEnglishVoiceUri = { viewModel.setGptSovitEnglishVoiceUri(it) },
                onModelDirUri = { viewModel.setGptSovitModelDirUri(it) },
                onChineseModelDirUri = { viewModel.setGptSovitChineseModelDirUri(it) },
                onEnglishModelDirUri = { viewModel.setGptSovitEnglishModelDirUri(it) },
                onAutoImportRootUri = { viewModel.setGptSovitAutoImportRootUri(it) },
                onApiBaseUrl = { viewModel.setGptSovitApiBaseUrl(it) },
                onTestLang = { lang, done -> viewModel.testGptSovitLang(lang, done) },
                onSovitsConsentAccepted = { viewModel.setSovitsConsentAccepted(true) },
                onServerListChange = { viewModel.setGptSovitServerList(it) },
                synthesisStatus = sovitsSynthesisStatus,
                p = p.iosReadable
            )
        }
    }

    val isLightTheme = (p.darkBg.red * 0.299f + p.darkBg.green * 0.587f + p.darkBg.blue * 0.114f) > 0.5f
    val iosScheme = if (isLightTheme) IosLightScheme else IosDarkScheme
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_settings),
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = iosScheme.label
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = iosScheme.bg)
            )
        },
        containerColor = iosScheme.bg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // 無障礙嚴重程度分級（醫師臨床建議）：中度/重度會隱藏複雜設定並套用大字體/識字卡預設
            item {
                Section(title = I18n.tr("使用模式（依嚴重程度）"), icon = Icons.Default.Accessibility, p = p) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.example.data.SeverityMode.values().forEach { m ->
                            ChoiceRow(
                                label = m.displayName,
                                sub = m.description,
                                selected = settings.severityMode == m,
                                onClick = { viewModel.setSeverityMode(m) },
                                p = p
                            )
                        }
                    }
                }
            }

            // 雲端同步（Firebase）：與網頁版用同一個 Google 帳號共用金鑰/供應商
            item {
                Section(title = I18n.tr("雲端同步（與網頁共用）"), icon = Icons.Default.Cloud, p = p) {
                    Text(
                        cloudUser?.let { I18n.tr("已登入：%s").format(it) } ?: I18n.tr("未登入"),
                        fontSize = 13.sp, color = p.textLight, fontWeight = FontWeight.Bold
                    )
                    if (cloudStatus.isNotBlank()) {
                        Text(cloudStatus, fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (cloudUser == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { googleLauncher.launch(com.example.ai.CloudSync.googleClient(context).signInIntent) },
                                colors = ButtonDefaults.buttonColors(containerColor = p.neonCyan),
                                modifier = Modifier.weight(1f)
                            ) { Text(I18n.tr("Google 登入"), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { viewModel.cloudSignInAnon() }, modifier = Modifier.weight(1f)) {
                                Text(I18n.tr("匿名"), fontSize = 12.sp)
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.cloudSignOut() },
                            border = BorderStroke(1.dp, p.dangerRose.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(I18n.tr("登出"), fontSize = 12.sp, color = p.dangerRose) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        I18n.tr("用同一個 Google 帳號登入手機與網頁，API 金鑰／供應商清單／Telegram 會自動雙向同步。"),
                        fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp
                    )
                }
            }

            // 中度/重度模式隱藏複雜設定（開發者模式開啟時仍顯示，照護者可用）
            val showComplex = settings.severityMode == com.example.data.SeverityMode.Mild || settings.developerMode

            // API 金鑰管理（浮動視窗）：集中管理所有 API 金鑰
            if (showComplex) item {
                Section(title = I18n.tr("API 金鑰管理"), icon = Icons.Default.Api, p = p) {
                    AdvancedEntryRow(
                        label = I18n.tr("管理所有 API 金鑰（LLM / 定位 / 通報；生圖在「AI 生圖」內）"),
                        onClick = { showApiKeys = true }, p = p
                    )
                }
            }

            // ※「AI 生圖」已移到「開發者模式」內（需要電腦/雲端的功能集中管理）。

            // 進階功能入口（資料治理）。
            // ※ 故事訓練模式已搬到「語音復健」分頁，僅在開發者模式打開時顯示入口。
            if (showComplex) item {
                Section(title = I18n.tr("進階功能"), icon = Icons.Default.Settings, p = p) {
                    AdvancedEntryRow(
                        label = I18n.tr("進階設定（緊急聯絡人 / 清歷史 / 重置 / 匯入語料庫）"),
                        onClick = { showExtended = true }, p = p
                    )
                }
            }

            // 視覺風格
            item {
                Section(title = stringResource(R.string.section_theme), icon = Icons.Default.Palette, p = p) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppTheme.values().forEach { t ->
                            ChoiceRow(
                                label = stringResource(themeLabelRes(t)),
                                sub = stringResource(themeBlurbRes(t)),
                                selected = settings.theme == t,
                                onClick = { viewModel.setTheme(t) },
                                p = p
                            )
                        }
                    }
                }
            }

            item {
                Section(title = I18n.tr("自訂背景"), icon = Icons.Default.Image, p = p) {
                    if (settings.customBackgroundUri.isNotBlank()) {
                        AsyncImage(
                            model = settings.customBackgroundUri,
                            contentDescription = I18n.tr("自訂背景預覽"),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, p.neonCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(p.darkBg.copy(alpha = 0.35f))
                                .border(1.dp, p.textMuted.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(I18n.tr("尚未設定背景"), fontSize = 12.sp, color = p.textMuted)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { backgroundPicker.launch(arrayOf("image/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = p.neonCyan),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(I18n.tr("選擇背景"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.setCustomBackgroundUri("") },
                            enabled = settings.customBackgroundUri.isNotBlank(),
                            border = BorderStroke(1.dp, p.dangerRose.copy(alpha = 0.55f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, null, tint = p.dangerRose, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("刪除", fontSize = 12.sp, color = p.dangerRose)
                        }
                    }
                }
            }

            // 耗能模式
            item {
                Section(title = stringResource(R.string.section_power), icon = Icons.Default.BatteryChargingFull, p = p) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PowerMode.values().forEach { mode ->
                            ChoiceRow(
                                label = stringResource(powerLabelRes(mode)),
                                sub = stringResource(powerDescRes(mode)),
                                selected = settings.powerMode == mode,
                                onClick = { viewModel.setPowerMode(mode) },
                                p = p
                            )
                        }
                    }
                }
            }

            item {
                HardwareAllocationSection(
                    deviceRam = deviceRam,
                    deviceTier = deviceTier,
                    modelDownloadState = modelDownloadState,
                    onDownloadDefault = { viewModel.startModelDownload() },
                    onCancelDownload = { viewModel.cancelModelDownload() },
                    p = p
                )
            }

            // 介面語言
            item {
                Section(title = stringResource(R.string.section_ai_provider), icon = Icons.Default.CloudQueue, p = p) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 只留總開關：Auto＝用雲端（多供應商在「API 金鑰管理 → 文字 LLM」設定，自動備援）、
                        // LocalOnly＝只用手機本機 AI。個別供應商不再用單選，改由清單管理。
                        listOf(CloudProvider.Auto, CloudProvider.LocalOnly).forEach { provider ->
                            ChoiceRow(
                                label = stringResource(cloudLabelRes(provider)),
                                sub = stringResource(cloudDescRes(provider)),
                                selected = settings.cloudProvider == provider ||
                                    (provider == CloudProvider.Auto && settings.cloudProvider !in listOf(CloudProvider.Auto, CloudProvider.LocalOnly)),
                                onClick = { viewModel.setCloudProvider(provider) },
                                p = p
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.cloud_note),
                        fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp
                    )
                }
            }

            // 介面語言
            item {
                Section(title = stringResource(R.string.section_language), icon = Icons.Default.Language, p = p) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppLanguage.values().forEach { lang ->
                            ChoiceRow(
                                label = stringResource(langLabelRes(lang)),
                                sub = lang.tag,
                                selected = settings.language == lang,
                                onClick = { viewModel.setLanguage(lang) },
                                p = p
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.language_note),
                        fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp
                    )
                }
            }

            // 朗讀語速
            item {
                Section(title = stringResource(R.string.section_tts_rate), icon = Icons.Default.RecordVoiceOver, p = p) {
                    SliderRow(
                        valueLabel = "%.2fx".format(settings.ttsRate),
                        value = settings.ttsRate,
                        valueRange = 0.5f..1.5f,
                        steps = 9,
                        onChange = { viewModel.setTtsRate(it) },
                        p = p
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.tts_rate_slow), fontSize = 10.sp, color = p.textMuted)
                        Text(stringResource(R.string.tts_rate_fast), fontSize = 10.sp, color = p.textMuted)
                    }
                }
            }

            // 字體大小
            item {
                Section(title = stringResource(R.string.section_font_size), icon = Icons.Default.FormatSize, p = p) {
                    SliderRow(
                        valueLabel = "%.1fx".format(settings.fontScale),
                        value = settings.fontScale,
                        valueRange = 0.8f..1.8f,
                        steps = 9,
                        onChange = { viewModel.setFontScale(it) },
                        p = p
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FontPresetChip(I18n.tr("標準"), settings.fontScale, 1.0f, { viewModel.setFontScale(1.0f) }, Modifier.weight(1f), p)
                        FontPresetChip(I18n.tr("大"), settings.fontScale, 1.25f, { viewModel.setFontScale(1.25f) }, Modifier.weight(1f), p)
                        FontPresetChip(I18n.tr("特大"), settings.fontScale, 1.55f, { viewModel.setFontScale(1.55f) }, Modifier.weight(1f), p)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(I18n.tr("字體倍率會套用到整個 App，包含 AAC、定位、設定與底部導覽。"), fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp)
                }
            }

            // 其他
            item {
                Section(title = stringResource(R.string.section_other), icon = Icons.Default.Tune, p = p) {
                    IosToggleRow(
                        title = stringResource(R.string.other_autosave_label),
                        sub = stringResource(R.string.other_autosave_sub),
                        checked = settings.autoSaveRehab,
                        onChange = { viewModel.setAutoSaveRehab(it) }
                    )
                    IosToggleRow(
                        title = I18n.tr("鼻子頭控"),
                        sub = I18n.tr("前鏡頭追蹤鼻子／頭部方向移動游標；轉頭瞄準、停留即選取（Camera Mouse 式頭控）。"),
                        checked = settings.eyeControlEnabled,
                        onChange = { on ->
                            if (on && !cameraPerm.status.isGranted) {
                                pendingEyeEnable = true
                                cameraPerm.launchPermissionRequest()
                            } else {
                                pendingEyeEnable = false
                                viewModel.setEyeControlEnabled(on)
                            }
                        }
                    )
                    if (settings.eyeControlEnabled) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            I18n.tr("轉頭移動游標，停在按鈕上約 1 秒會自動點擊（MediaPipe 鼻尖追蹤）。"),
                            fontSize = 10.sp,
                            color = p.textMuted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onStartCalibration,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.tr("重新置中（頭部歸零）"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            I18n.tr("先用自然舒適的姿勢正對螢幕，再按此鍵把目前頭部方向設為中央；之後轉頭即可移動游標。"),
                            fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    IosToggleRow(
                        title = I18n.tr("本地端 AI 安全清理"),
                        sub = I18n.tr("推理後清除快取、暫存與背景模型記憶體；不刪除模型檔。"),
                        checked = settings.autoLocalAiCleanup,
                        onChange = { viewModel.setAutoLocalAiCleanup(it) }
                    )
                    IosToggleRow(
                        title = I18n.tr("AAC 意圖確認大圖卡"),
                        sub = I18n.tr("AI 組句後跳出大圖卡（emoji + 句子）讓不識字使用者 ✓/✗ 確認；關閉時改精簡列，但仍需點擊才朗讀。"),
                        checked = settings.aacIntentConfirmEnabled,
                        onChange = { viewModel.setAacIntentConfirmEnabled(it) }
                    )
                    IosToggleRow(
                        title = I18n.tr("意圖卡 AI 生成專屬圖"),
                        sub = I18n.tr("確認卡額外用 AI 生圖畫出意圖的專屬圖片（不識字更好懂）。會花幾秒、不擋確認鈕；建議搭配本機電腦生圖。"),
                        checked = settings.aacIntentImageEnabled,
                        onChange = { viewModel.setAacIntentImageEnabled(it) }
                    )
                }
            }

            if (showComplex) item {
                Section(title = I18n.tr("開發者"), icon = Icons.Default.DeveloperMode, p = p) {
                    AdvancedEntryRow(
                        label = I18n.tr("開發者模式（GPT-SoVITS / 硬體監控 / 模擬定位 / 模型下載）"),
                        onClick = { showDeveloper = true }, p = p
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ─── string res 對應 helper ───

private fun themeLabelRes(t: AppTheme) = when (t) {
    AppTheme.Tech    -> R.string.theme_tech
    AppTheme.Cute    -> R.string.theme_cute
    AppTheme.Anime   -> R.string.theme_anime
    AppTheme.Minimal -> R.string.theme_minimal
}
private fun themeBlurbRes(t: AppTheme) = when (t) {
    AppTheme.Tech    -> R.string.theme_blurb_tech
    AppTheme.Cute    -> R.string.theme_blurb_cute
    AppTheme.Anime   -> R.string.theme_blurb_anime
    AppTheme.Minimal -> R.string.theme_blurb_minimal
}
private fun powerLabelRes(m: PowerMode) = when (m) {
    PowerMode.Saver       -> R.string.power_saver
    PowerMode.Balanced    -> R.string.power_balanced
    PowerMode.Performance -> R.string.power_performance
}
private fun powerDescRes(m: PowerMode) = when (m) {
    PowerMode.Saver       -> R.string.power_desc_saver
    PowerMode.Balanced    -> R.string.power_desc_balanced
    PowerMode.Performance -> R.string.power_desc_performance
}
private fun langLabelRes(l: AppLanguage) = when (l) {
    AppLanguage.ZhTW -> R.string.lang_zh_tw
    AppLanguage.En   -> R.string.lang_en
    AppLanguage.Ja   -> R.string.lang_ja
    AppLanguage.Ko   -> R.string.lang_ko
}
private fun cloudLabelRes(p: CloudProvider) = when (p) {
    CloudProvider.Auto       -> R.string.cloud_auto
    CloudProvider.LocalOnly  -> R.string.cloud_local_only
    CloudProvider.Groq       -> R.string.cloud_groq
    CloudProvider.OpenRouter -> R.string.cloud_openrouter
    CloudProvider.Cohere     -> R.string.cloud_cohere
}
private fun cloudDescRes(p: CloudProvider) = when (p) {
    CloudProvider.Auto       -> R.string.cloud_desc_auto
    CloudProvider.LocalOnly  -> R.string.cloud_desc_local_only
    CloudProvider.Groq       -> R.string.cloud_desc_groq
    CloudProvider.OpenRouter -> R.string.cloud_desc_openrouter
    CloudProvider.Cohere     -> R.string.cloud_desc_cohere
}

@Composable
private fun FontPresetChip(
    label: String,
    currentScale: Float,
    targetScale: Float,
    onClick: () -> Unit,
    modifier: Modifier,
    p: com.example.ui.theme.AppPalette
) {
    val selected = kotlin.math.abs(currentScale - targetScale) < 0.05f
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp, maxLines = 1) },
        leadingIcon = {
            Icon(Icons.Default.FormatSize, null, modifier = Modifier.size(14.dp))
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = p.neonCyan.copy(alpha = 0.18f),
            selectedLabelColor = p.textLight,
            selectedLeadingIconColor = p.neonCyan,
            labelColor = p.textMuted,
            iconColor = p.textMuted
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = p.textMuted.copy(alpha = 0.25f),
            selectedBorderColor = p.neonCyan
        ),
        modifier = modifier.heightIn(min = 36.dp)
    )
}

@Composable
private fun HardwareAllocationSection(
    deviceRam: Double,
    deviceTier: DeviceTier,
    modelDownloadState: ModelDownloadState,
    onDownloadDefault: () -> Unit,
    onCancelDownload: () -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    Section(title = stringResource(R.string.main_sec_hardware), icon = Icons.Default.Memory, p = p) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.main_ram_label), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.textLight)
                Text(stringResource(R.string.main_ram_size, deviceRam), fontSize = 11.sp, color = p.textMuted)
            }
            TierBadge(deviceTier, p)
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (deviceRam / 16.0).toFloat().coerceIn(0.12f, 1f) },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
            color = tierColor(deviceTier, p),
            trackColor = p.textMuted.copy(alpha = 0.18f)
        )

        Spacer(Modifier.height(10.dp))
        ModelStatusBlock(
            state = modelDownloadState,
            onDownloadDefault = onDownloadDefault,
            onCancel = onCancelDownload,
            p = p
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GptSovitDeveloperPanel(
    enabled: Boolean,
    mode: GptSovitVoiceMode,
    chineseVoiceUri: String,
    englishVoiceUri: String,
    modelDirUri: String,
    chineseModelDirUri: String,
    englishModelDirUri: String,
    autoImportRootUri: String,
    autoImportStatus: String?,
    apiBaseUrl: String,
    serverList: String,
    synthesisStatus: String?,
    consentAccepted: Boolean,
    onConsentAccepted: () -> Unit,
    onServerListChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (GptSovitVoiceMode) -> Unit,
    onApiBaseUrlChange: (String) -> Unit,
    onPickChinese: () -> Unit,
    onPickEnglish: () -> Unit,
    onPickModelDir: () -> Unit,
    onPickChineseModelDir: () -> Unit,
    onPickEnglishModelDir: () -> Unit,
    onAutoImport: () -> Unit,
    onClearImportWaste: () -> Unit,
    onTestLang: (lang: com.example.ai.LocalSovitsHelper.VoiceLang, onDone: () -> Unit) -> Unit,
    onClearChinese: () -> Unit,
    onClearEnglish: () -> Unit,
    onClearModelDir: () -> Unit,
    onClearChineseModelDir: () -> Unit,
    onClearEnglishModelDir: () -> Unit,
    onClearAutoImport: () -> Unit,
    onBack: () -> Unit,
    appLangTag: String = "zh-TW",
    p: com.example.ui.theme.AppPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.neonCyan.copy(alpha = 0.08f))
            .border(1.dp, p.neonCyan.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.ArrowBack, I18n.tr("返回開發者設定"), tint = p.textMuted, modifier = Modifier.size(18.dp))
            }
            Icon(Icons.Default.GraphicEq, null, tint = p.neonCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(I18n.tr("GPT-SoVITS 實驗語音"), fontSize = 13.sp, color = p.textLight, fontWeight = FontWeight.Bold)
                Text(I18n.tr("開發者模式專屬；可依句子自動切換中英文樣本。"), fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp)
            }
            // 聲紋授權（醫師臨床建議）：未勾選知情同意前不可啟用語音克隆
            var showConsentDialog by remember { mutableStateOf(false) }
            Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    if (want && !consentAccepted) showConsentDialog = true
                    else onEnabledChange(want)
                }
            )
            if (showConsentDialog) {
                var agreeChecked by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { showConsentDialog = false },
                    icon = { Icon(Icons.Default.PrivacyTip, null, tint = p.auraAmber) },
                    title = { Text(I18n.tr("聲紋克隆隱私知情同意"), color = p.textLight) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                I18n.tr("啟用 GPT-SoVITS 語音克隆前請確認：\n1. 參考音檔之聲音主體已同意其聲紋被用於本機語音合成。\n2. 合成語音僅供本人輔助溝通之個人非商業用途。\n3. 不得用合成語音冒充他人或散布誤導內容。\n4. 錄音與模型只存於你的手機與自己的電腦，不上傳雲端。"),
                                fontSize = 12.sp, color = p.textMuted, lineHeight = 18.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { agreeChecked = !agreeChecked }
                            ) {
                                Checkbox(checked = agreeChecked, onCheckedChange = { agreeChecked = it })
                                Text(I18n.tr("我已閱讀並同意上述條款"), fontSize = 12.sp, color = p.textLight)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = agreeChecked,
                            onClick = {
                                onConsentAccepted()
                                onEnabledChange(true)
                                showConsentDialog = false
                            }
                        ) { Text(I18n.tr("同意並啟用"), color = if (agreeChecked) p.neonGreen else p.textMuted) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConsentDialog = false }) {
                            Text(I18n.tr("取消"), color = p.textMuted)
                        }
                    },
                    containerColor = p.surface
                )
            }
        }

        // ── Mac SoVITS 伺服器面板 ── 連線健檢、多電腦切換、遠端模型切換、指令複製
        TermuxSovitsPanel(
            apiBaseUrl = apiBaseUrl,
            onApiBaseUrlChange = onApiBaseUrlChange,
            serverList = serverList,
            onServerListChange = onServerListChange,
            appLangTag = appLangTag,
            p = p
        )
        // 舊的「自動/中文/English」模式 chip 已移除：語言現在由文字自動偵測（中/英/日/韓），
        // 與下方多語言測試按鈕功能重複，故拿掉避免混淆。
        Text(
            I18n.tr("語言會依輸入文字自動切換（中／英／日／韓），不需手動選。"),
            fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp
        )
        // 用本地編輯狀態，避免「值綁定到 DataStore 非同步流」造成打字/刪除被覆寫回舊值。
        // 只有「未聚焦時」才同步外部值（例如按了預設位址按鈕），聚焦打字時不被回填。
        var urlText by remember { mutableStateOf(apiBaseUrl) }
        var urlFocused by remember { mutableStateOf(false) }
        LaunchedEffect(apiBaseUrl) {
            if (!urlFocused && apiBaseUrl != urlText) urlText = apiBaseUrl
        }
        // 防抖存檔：打字時只更新本地狀態（不掉字）；停手 350ms 後才寫入設定。
        LaunchedEffect(urlText) {
            if (urlFocused && urlText != apiBaseUrl) {
                kotlinx.coroutines.delay(350)
                onApiBaseUrlChange(urlText)
            }
        }
        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { st ->
                    urlFocused = st.isFocused
                    if (!st.isFocused && urlText != apiBaseUrl) onApiBaseUrlChange(urlText)  // 失焦立即存
                },
            singleLine = true,
            label = { Text("GPT-SoVITS API 位址") },
            placeholder = { Text("http://電腦IP:9880") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.textLight,
                unfocusedTextColor = p.textLight,
                focusedBorderColor = p.neonCyan,
                unfocusedBorderColor = p.textMuted.copy(alpha = 0.35f),
                focusedContainerColor = p.darkBg.copy(alpha = 0.25f),
                unfocusedContainerColor = p.darkBg.copy(alpha = 0.18f)
            )
        )
        // 「合成測試」多語言：中／英／日／韓各一顆，按下用該語言句子合成（會自動切到對應語言模型）。
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var testingLang by remember { mutableStateOf<com.example.ai.LocalSovitsHelper.VoiceLang?>(null) }
        var testGeneration by remember { mutableStateOf(0) }
        Text(I18n.tr("模型合成測試（多語言）"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.textLight)
        val langs = com.example.ai.LocalSovitsHelper.VoiceLang.values()
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            langs.forEach { lang ->
                val busy = testingLang == lang
                Button(
                    onClick = {
                        if (testingLang != null) return@Button
                        testingLang = lang
                        val gen = ++testGeneration
                        onTestLang(lang) { if (gen == testGeneration) testingLang = null }
                        scope.launch {
                            kotlinx.coroutines.delay(120000)
                            if (gen == testGeneration) testingLang = null
                        }
                    },
                    enabled = enabled && (testingLang == null || busy),
                    colors = ButtonDefaults.buttonColors(containerColor = p.neonCyan, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 42.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(I18n.tr("合成中…"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.GraphicEq, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(lang.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        // 合成狀態（人話回饋：以前失敗只寫進隱藏 log，使用者只看到 spinner 消失什麼都沒發生）
        synthesisStatus?.let { st ->
            Text(
                st,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = when {
                    st.startsWith("✅") -> p.neonGreen
                    st.startsWith("❌") -> p.dangerRose
                    else -> p.auraAmber
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.darkBg.copy(alpha = 0.22f))
                .border(1.dp, p.neonCyan.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Computer, null, tint = p.neonCyan, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(I18n.tr("模型由電腦設定頁管理"), fontSize = 12.sp, color = p.textLight, fontWeight = FontWeight.Bold)
            }
            Text(
                I18n.tr("在 Mac 開啟 http://127.0.0.1:9890 匯入 .ckpt / .pth / .wav 與參考文字。App 只負責呼叫目前電腦 server 載入的模型。"),
                fontSize = 10.sp,
                color = p.textMuted,
                lineHeight = 14.sp
            )
        }
    }
}

/**
 * 「用 Mac 跑 GPT-SoVITS server」助手面板。支援雙模式：
 *  - **無線 (Wi-Fi)**：手機與 Mac 同個 LAN，填 `http://<Mac_LAN_IP>:9880`
 *  - **有線 (USB)**：Mac 跑 `adb reverse tcp:9880 tcp:9880`，App 填 `http://127.0.0.1:9880`
 *
 * 顯示：
 *  - 模式切換 chip（自動填對應 URL）
 *  - 連線健檢（測當前填的 URL）
 *  - 「複製啟動指令」「複製 adb reverse 指令」按鈕
 *  - 依模式不同的教學
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TermuxSovitsPanel(
    apiBaseUrl: String,
    onApiBaseUrlChange: (String) -> Unit,
    serverList: String,
    onServerListChange: (String) -> Unit,
    appLangTag: String = "zh-TW",
    p: com.example.ui.theme.AppPalette
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pingResult by remember { mutableStateOf<com.example.ai.LocalSovitsHelper.PingResult?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var switchStatus by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(false) }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    // 依當前 URL 自動判斷現在是哪種模式
    val currentMode = remember(apiBaseUrl) {
        if (apiBaseUrl.contains("127.0.0.1") || apiBaseUrl.contains("localhost"))
            com.example.ai.LocalSovitsHelper.ConnectionMode.Wired
        else
            com.example.ai.LocalSovitsHelper.ConnectionMode.Wireless
    }
    fun copy(label: String, text: String) {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(p.neonCyan.copy(alpha = 0.06f))
            .border(1.dp, p.neonCyan.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Computer, null, tint = p.neonCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(I18n.tr("用 Mac 跑 SoVITS（無線 / 有線）"), fontSize = 12.sp, color = p.textLight, fontWeight = FontWeight.Bold)
        }
        Text(
            "GPT-SoVITS 要 Python + PyTorch，Android 內跑不了 .pth 權重。\n" +
            "做法：Mac 開 server，手機用 Wi-Fi 或 USB 連過去。",
            fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp
        )

        // ── 模式 chip：點切換時自動更新 placeholder URL ──
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            com.example.ai.LocalSovitsHelper.ConnectionMode.values().forEach { m ->
                FilterChip(
                    selected = currentMode == m,
                    onClick = {
                        if (m == com.example.ai.LocalSovitsHelper.ConnectionMode.Wired) {
                            onApiBaseUrlChange(com.example.ai.LocalSovitsHelper.DEFAULT_LOCAL_URL)
                        } else if (currentMode != com.example.ai.LocalSovitsHelper.ConnectionMode.Wireless) {
                            onApiBaseUrlChange("http://192.168.1.x:9880")
                        }
                    },
                    label = { Text(m.displayName, fontSize = 10.sp) },
                    leadingIcon = {
                        Icon(
                            if (m == com.example.ai.LocalSovitsHelper.ConnectionMode.Wired) Icons.Default.Usb else Icons.Default.Wifi,
                            null,
                            modifier = Modifier.size(13.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = p.neonCyan.copy(alpha = 0.18f),
                        selectedLabelColor = p.textLight,
                        selectedLeadingIconColor = p.neonCyan,
                        containerColor = p.darkBg.copy(alpha = 0.3f),
                        labelColor = p.textMuted,
                        iconColor = p.textMuted
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = currentMode == m,
                        borderColor = p.textMuted.copy(alpha = 0.25f),
                        selectedBorderColor = p.neonCyan
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 34.dp)
                )
            }
        }
        Text(currentMode.description, fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp)

        // ── 已存電腦清單：可切換多台電腦（每台一個 chip，✚ 存目前位址）──
        val servers = com.example.ai.LocalSovitsHelper.parseServerList(serverList)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(I18n.tr("電腦清單"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.textLight, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    val url = apiBaseUrl.trim().trimEnd('/')
                    if (url.isNotBlank() && servers.none { it.url == url }) {
                        val name = if (url.contains("127.0.0.1")) I18n.tr("USB 電腦") else I18n.tr("電腦 %d").format(servers.size + 1)
                        onServerListChange(
                            com.example.ai.LocalSovitsHelper.encodeServerList(
                                servers + com.example.ai.LocalSovitsHelper.SovitsServer(name, url)
                            )
                        )
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(I18n.tr("＋ 存目前位址"), fontSize = 10.sp, color = p.neonCyan) }
        }
        if (servers.isEmpty()) {
            Text(I18n.tr("尚無已存電腦。填好 API 位址後按「＋ 存目前位址」。"), fontSize = 10.sp, color = p.textMuted)
        } else {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                servers.forEach { srv ->
                    val active = apiBaseUrl.trim().trimEnd('/') == srv.url
                    FilterChip(
                        selected = active,
                        onClick = { onApiBaseUrlChange(srv.url); pingResult = null; switchStatus = null },
                        label = { Text("${srv.name}（${srv.url.removePrefix("http://")}）", fontSize = 10.sp) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close, I18n.tr("刪除 %s").format(srv.name),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        onServerListChange(
                                            com.example.ai.LocalSovitsHelper.encodeServerList(servers - srv)
                                        )
                                    }
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = p.neonCyan.copy(alpha = 0.18f),
                            selectedLabelColor = p.textLight,
                            containerColor = p.darkBg.copy(alpha = 0.3f),
                            labelColor = p.textMuted
                        )
                    )
                }
            }
        }

        // 連線健檢
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pingResult?.label ?: I18n.tr("尚未測試連線"),
                fontSize = 11.sp,
                color = when (pingResult?.kind) {
                    com.example.ai.LocalSovitsHelper.PingResultKind.Reachable  -> p.neonGreen
                    com.example.ai.LocalSovitsHelper.PingResultKind.PortClosed -> p.dangerRose
                    com.example.ai.LocalSovitsHelper.PingResultKind.HttpError  -> p.auraAmber
                    null -> p.textMuted
                },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    if (pinging) return@OutlinedButton
                    pinging = true
                    scope.launch {
                        pingResult = com.example.ai.LocalSovitsHelper.pingServer(apiBaseUrl)
                        // 連得到 → 順便問電腦的 Tailscale IP，自動加入電腦清單（之後外網/4G/5G 一按填入）
                        if (pingResult?.kind == com.example.ai.LocalSovitsHelper.PingResultKind.Reachable) {
                            val info = com.example.ai.LocalSovitsHelper.discoverComputer(apiBaseUrl)
                            if (info != null) {
                                val url = com.example.ai.LocalSovitsHelper.tailscaleApiUrl(info.tailscaleIp)
                                val servers = com.example.ai.LocalSovitsHelper.parseServerList(serverList)
                                if (servers.none { it.url == url }) {
                                    onServerListChange(
                                        com.example.ai.LocalSovitsHelper.encodeServerList(
                                            servers + com.example.ai.LocalSovitsHelper.SovitsServer("${info.hostname} (Tailscale)", url)
                                        )
                                    )
                                    switchStatus = I18n.tr("已加入 Tailscale 電腦：%s（%s）").format(info.hostname, info.tailscaleIp)
                                }
                            }
                        }
                        pinging = false
                    }
                },
                enabled = !pinging,
                border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                if (pinging) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = p.neonCyan, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.NetworkCheck, null, tint = p.neonCyan, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(I18n.tr("測試連線"), fontSize = 11.sp, color = p.neonCyan)
            }
        }

        // ── 角色模型（依介面語言過濾）──
        LaunchedEffect(apiBaseUrl) {
            runCatching { com.example.ai.LocalSovitsHelper.refreshModels(apiBaseUrl) }
        }
        val voices by com.example.ai.LocalSovitsHelper.voicesFlow.collectAsState()
        val curVoice = com.example.ai.LocalSovitsHelper.currentVoice()
        // 依目前 App 介面語言過濾：只顯示對應語言的模型
        val voiceLangFilter = com.example.ai.LocalSovitsHelper.langFromAppTag(appLangTag)
        val filteredVoices = voices.filter { it.lang == voiceLangFilter }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.tr("角色模型"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.textLight)
            Spacer(Modifier.width(6.dp))
            Text("（${voiceLangFilter.displayName}）", fontSize = 10.sp, color = p.neonCyan)
            Spacer(Modifier.weight(1f))
            if (voices.size > filteredVoices.size) {
                Text(I18n.tr("顯示 %d/%d").format(filteredVoices.size, voices.size), fontSize = 9.sp, color = p.textMuted)
            }
        }
        if (filteredVoices.isEmpty()) {
            Text(
                if (voices.isEmpty())
                    I18n.tr("電腦上尚無任何模型，請在 Mac 語音中心匯入後按上方「測試連線」重新整理。")
                else
                    I18n.tr("目前介面語言（%s）沒有對應模型。請在 Mac 語音中心匯入含「%s」標籤的模型。").format(voiceLangFilter.displayName, voiceLangFilter.tag),
                fontSize = 11.sp, color = p.textMuted, lineHeight = 16.sp
            )
        }
        // 2 欄宮格，每排兩張卡，全部展開不捲動
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            filteredVoices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { v ->
                        val active = curVoice?.character == v.character && curVoice.lang == v.lang
                        val borderColor = if (active) p.neonCyan else p.textMuted.copy(alpha = 0.35f)
                        val bgColor = if (active) p.neonCyan.copy(alpha = 0.12f) else p.surface.copy(alpha = 0.6f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(bgColor)
                                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                .clickable(enabled = !switching) {
                                    if (switching) return@clickable
                                    switching = true
                                    switchStatus = I18n.tr("⏳ 正在切換到 %s…").format(v.displayLabel)
                                    scope.launch {
                                        val r = com.example.ai.LocalSovitsHelper.switchModel(apiBaseUrl, v)
                                        switchStatus = r.fold(
                                            onSuccess = { I18n.tr("✅ 已切換到 %s，ref/prompt 已自動跟上").format(it) },
                                            onFailure = { "❌ ${it.message}" }
                                        )
                                        pingResult = com.example.ai.LocalSovitsHelper.pingServer(apiBaseUrl)
                                        switching = false
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        v.character,
                                        fontSize = 13.sp,
                                        color = if (active) p.neonCyan else p.textLight,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (active) {
                                        Text("●", fontSize = 8.sp, color = p.neonCyan)
                                    }
                                }
                                Text(
                                    v.lang.displayName,
                                    fontSize = 9.sp,
                                    color = p.textMuted
                                )
                            }
                        }
                    }
                    // 奇數模型時補空白佔位，維持左對齊
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Text(
            I18n.tr("切換 App 介面語言即可篩選對應聲音模型；切換約需數十秒（CPU 載權重），同語言內連續講不會重切。"),
            fontSize = 9.sp, color = p.textMuted, lineHeight = 13.sp
        )
        switchStatus?.let {
            Text(it, fontSize = 10.sp, color = if (it.startsWith("✅")) p.neonGreen else if (it.startsWith("❌")) p.dangerRose else p.auraAmber, lineHeight = 14.sp)
        }

        // Mac 指令複製按鈕
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { copy("Start GPT-SoVITS", com.example.ai.LocalSovitsHelper.START_SERVER_COMMAND) },
                border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.ContentCopy, null, tint = p.neonCyan, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text(I18n.tr("複製啟動指令"), fontSize = 10.sp, color = p.neonCyan, fontWeight = FontWeight.Bold)
            }
            if (currentMode == com.example.ai.LocalSovitsHelper.ConnectionMode.Wired) {
                OutlinedButton(
                    onClick = { copy("adb reverse", com.example.ai.LocalSovitsHelper.ADB_REVERSE_COMMAND) },
                    border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Usb, null, tint = p.neonCyan, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(I18n.tr("複製 USB 指令"), fontSize = 10.sp, color = p.neonCyan, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = { copy("Get Mac LAN IP", "ipconfig getifaddr en0") },
                    border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Wifi, null, tint = p.neonCyan, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(I18n.tr("複製查 IP"), fontSize = 10.sp, color = p.neonCyan, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 依模式不同的教學
        val instructions = if (currentMode == com.example.ai.LocalSovitsHelper.ConnectionMode.Wired) {
            "① USB 線把手機接上 Mac，授權 USB 偵錯\n" +
            "② Mac 雙擊「VoiceWeaver語音中心.app」(會自動 adb reverse 並啟動伺服器)\n" +
            "③ 按「測試連線」綠燈 → 用「🎤 合成測試」確認授權角色音色"
        } else {
            "① 手機與 Mac 連同一個 Wi-Fi\n" +
            "② Mac 雙擊「VoiceWeaver語音中心.app」啟動伺服器\n" +
            "③ 看控制中心(自動開啟)顯示的區網 IP，上方 API URL 改成 http://<那個IP>:9880\n" +
            "④ 按「測試連線」綠燈（外網可裝 Tailscale，改填 100.x IP）"
        }
        Text(instructions, fontSize = 10.sp, color = p.textMuted, lineHeight = 15.sp)
    }
}

@Composable
private fun GptSovitModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    p: com.example.ui.theme.AppPalette
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = p.neonGreen.copy(alpha = 0.18f),
            selectedLabelColor = p.textLight,
            labelColor = p.textMuted
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = p.textMuted.copy(alpha = 0.24f),
            selectedBorderColor = p.neonGreen
        ),
        modifier = modifier.heightIn(min = 36.dp)
    )
}

@Composable
private fun VoiceSampleRow(
    label: String,
    uri: String,
    readyText: String = I18n.tr("已永久保存 URI 權限"),
    emptyText: String = I18n.tr("尚未匯入"),
    onPick: () -> Unit,
    onClear: () -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = p.textLight, fontWeight = FontWeight.Bold)
            Text(if (uri.isBlank()) emptyText else readyText, fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp)
        }
        OutlinedButton(
            onClick = onPick,
            border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.55f)),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.UploadFile, null, tint = p.neonCyan, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(I18n.tr("匯入"), fontSize = 11.sp, color = p.neonCyan)
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onClear, enabled = uri.isNotBlank(), modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.Delete, null, tint = if (uri.isBlank()) p.textMuted.copy(alpha = 0.45f) else p.dangerRose, modifier = Modifier.size(18.dp))
        }
    }
}

private data class GptSovitDetectedBundle(
    val chineseDirUri: String?,
    val englishDirUri: String?,
    val message: String
)

private data class GptSovitImportDialogState(
    val title: String,
    val message: String,
    val openSystemPicker: () -> Unit,
    val useDefaultFolder: (() -> Unit)? = null
)

private data class GptSovitTreeEntry(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val pathHint: String
)

@Composable
private fun GptSovitImportDialog(
    state: GptSovitImportDialogState,
    onDismiss: () -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        titleContentColor = p.textLight,
        textContentColor = p.textMuted,
        title = { Text(state.title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text(state.message, fontSize = 12.sp, lineHeight = 17.sp) },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    state.openSystemPicker()
                },
                colors = ButtonDefaults.buttonColors(containerColor = p.neonCyan)
            ) {
                Text(I18n.tr("開啟系統檔案頁"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.useDefaultFolder?.let { useDefault ->
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            useDefault()
                        },
                        border = BorderStroke(1.dp, p.auraAmber.copy(alpha = 0.55f))
                    ) {
                        Text(I18n.tr("使用預設資料夾"), fontSize = 12.sp, color = p.auraAmber)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(I18n.tr("取消"), fontSize = 12.sp, color = p.textMuted)
                }
            }
        }
    )
}

private fun persistTreePermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
}

private fun detectGptSovitBundle(context: Context, rootUri: Uri): GptSovitDetectedBundle {
    val candidates = collectGptSovitCandidates(context, rootUri)
    val chinese = candidates
        .filter { languageScore(it.pathHint, true) > 0 }
        .maxByOrNull { languageScore(it.pathHint, true) }
    val english = candidates
        .filter { languageScore(it.pathHint, false) > 0 }
        .maxByOrNull { languageScore(it.pathHint, false) }

    val fallback = candidates.filter { it.uri != chinese?.uri && it.uri != english?.uri }
    val finalChinese = chinese ?: fallback.firstOrNull()
    val finalEnglish = english ?: fallback.firstOrNull { it.uri != finalChinese?.uri }
    val found = listOfNotNull(
        finalChinese?.let { "中文=${it.name}" },
        finalEnglish?.let { "英文=${it.name}" }
    ).joinToString("，")

    return GptSovitDetectedBundle(
        chineseDirUri = finalChinese?.uri?.toString(),
        englishDirUri = finalEnglish?.uri?.toString(),
        message = if (found.isBlank()) {
            "沒有偵測到完整 GPT-SoVITS 模型包。請確認資料夾內有 SoVITS_weights/*.pth 與 GPT_weights/*.ckpt。"
        } else {
            "自動匯入完成：$found"
        }
    )
}

private fun defaultGptSovitDir(context: Context): File {
    val mediaRoot = context.externalMediaDirs.firstOrNull()
        ?: File(context.filesDir, "media")
    return File(mediaRoot, "VoiceWeaver/GPT-SoVITS").apply { mkdirs() }
}

private fun defaultGptSovitInitialTreeUri(context: Context): Uri? {
    defaultGptSovitDir(context)
    val relative = "Android/media/${context.packageName}/VoiceWeaver/GPT-SoVITS"
    return runCatching {
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$relative"
        )
    }.getOrNull()
}

private fun clearDefaultGptSovitDir(context: Context): String {
    val root = defaultGptSovitDir(context)
    val deleted = root.listFiles().orEmpty().count { child ->
        runCatching { child.deleteRecursively() }.getOrDefault(false)
    }
    root.mkdirs()
    return "已清除預設匯入資料夾的 $deleted 個項目：${root.absolutePath}"
}

private fun detectGptSovitBundleFromDefaultFolder(context: Context): GptSovitDetectedBundle {
    val root = defaultGptSovitDir(context)
    val candidates = collectFileGptSovitCandidates(root)
    val chinese = candidates
        .filter { languageScore(it.absolutePath, true) > 0 }
        .maxByOrNull { languageScore(it.absolutePath, true) }
    val english = candidates
        .filter { languageScore(it.absolutePath, false) > 0 }
        .maxByOrNull { languageScore(it.absolutePath, false) }
    val fallback = candidates.filter { it != chinese && it != english }
    val finalChinese = chinese ?: fallback.firstOrNull()
    val finalEnglish = english ?: fallback.firstOrNull { it != finalChinese }
    val found = listOfNotNull(
        finalChinese?.let { "中文=${it.name}" },
        finalEnglish?.let { "英文=${it.name}" }
    ).joinToString("，")
    return GptSovitDetectedBundle(
        chineseDirUri = finalChinese?.toURI()?.toString(),
        englishDirUri = finalEnglish?.toURI()?.toString(),
        message = if (found.isBlank()) {
            "預設資料夾尚未找到模型。請把整包放到 ${root.absolutePath}"
        } else {
            "預設資料夾匯入完成：$found"
        }
    )
}

private fun collectFileGptSovitCandidates(root: File): List<File> {
    val result = mutableListOf<File>()
    fun scan(dir: File, depth: Int) {
        val children = dir.listFiles().orEmpty()
        val names = children.map { it.name.lowercase() }
        val hasSovits = names.any { it.startsWith("sovits_weights") || it.endsWith(".pth") }
        val hasGpt = names.any { it.startsWith("gpt_weights") || it.endsWith(".ckpt") }
        if (hasSovits && hasGpt) result += dir
        if (depth < 4) {
            children.filter { it.isDirectory }.forEach { scan(it, depth + 1) }
        }
    }
    if (root.exists()) scan(root, 0)
    return result.distinctBy { it.absolutePath }
}

private fun collectGptSovitCandidates(context: Context, rootUri: Uri): List<GptSovitTreeEntry> {
    val result = mutableListOf<GptSovitTreeEntry>()
    fun scan(entry: GptSovitTreeEntry, depth: Int) {
        val children = listTreeChildren(context, rootUri, entry.uri)
        val names = children.map { it.name.lowercase() }
        val hasSovits = names.any { it.startsWith("sovits_weights") || it.endsWith(".pth") }
        val hasGpt = names.any { it.startsWith("gpt_weights") || it.endsWith(".ckpt") }
        if (hasSovits && hasGpt) result += entry
        if (depth < 4) {
            children.filter { it.isDirectory }.forEach { scan(it, depth + 1) }
        }
    }

    val root = GptSovitTreeEntry("選取的資料夾", rootUri, true, "")
    scan(root, 0)
    return result.distinctBy { it.uri.toString() }
}

private fun listTreeChildren(context: Context, treeUri: Uri, folderUri: Uri): List<GptSovitTreeEntry> {
    val folderDocId = runCatching { DocumentsContract.getDocumentId(folderUri) }
        .getOrElse { runCatching { DocumentsContract.getTreeDocumentId(folderUri) }.getOrNull() }
        ?: return emptyList()
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    return runCatching {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList {
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol).orEmpty()
                    val mime = cursor.getString(mimeCol).orEmpty()
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val parentPath = folderUri.toString().substringAfterLast("%2F", "")
                    add(
                        GptSovitTreeEntry(
                            name = name,
                            uri = childUri,
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            pathHint = "$parentPath/$name"
                        )
                    )
                }
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

private fun languageScore(text: String, chinese: Boolean): Int {
    val t = text.lowercase()
    return if (chinese) {
        listOf("zh", "zhtw", "zh-tw", "cn", "chinese", "中文", "漢", "汉").count { it in t }
    } else {
        listOf("en", "eng", "english", "英文").count { it in t }
    }
}

@Composable
private fun OffloadPlanCard(
    plan: com.example.ai.OffloadManager.OffloadPlan,
    onRefresh: () -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    val tierColor = when (plan.tier) {
        com.example.ai.OffloadManager.OffloadTier.FULL,
        com.example.ai.OffloadManager.OffloadTier.HEAVY -> p.neonCyan
        com.example.ai.OffloadManager.OffloadTier.LIGHT -> p.dangerRose
        com.example.ai.OffloadManager.OffloadTier.OFFLINE -> p.textMuted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.darkBg.copy(alpha = 0.35f))
            .border(1.dp, tierColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Computer, null, tint = tierColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(I18n.tr("電腦接手計畫：%s").format(plan.tier.title), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.textLight)
                Text(plan.tier.blurb, fontSize = 10.sp, color = p.textMuted, lineHeight = 13.sp)
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, I18n.tr("重新量測"), tint = p.textMuted, modifier = Modifier.size(16.dp))
            }
        }
        if (plan.metrics.reachable) {
            Spacer(Modifier.height(6.dp))
            val kindTxt = if (plan.metrics.kind == com.example.ai.OffloadManager.LinkKind.Wired) I18n.tr("有線 USB") else I18n.tr("無線 Wi-Fi")
            val rttTxt = if (plan.metrics.rttMs >= 0) I18n.tr("、延遲 %dms").format(plan.metrics.rttMs) else ""
            Text(I18n.tr("連線：%s（%s%s）").format(plan.metrics.speedLabel, kindTxt, rttTxt), fontSize = 11.sp, color = p.textMuted)
        } else {
            Spacer(Modifier.height(6.dp))
            Text(I18n.tr("未連接電腦：全部運算由手機本機處理"), fontSize = 11.sp, color = p.textMuted)
        }
        Spacer(Modifier.height(8.dp))
        OffloadTaskRow(I18n.tr("🔊 語音合成"), plan.voice, p)
        OffloadTaskRow(I18n.tr("🖼 生圖"), plan.image, p)
        OffloadTaskRow(I18n.tr("💬 文字重組"), plan.text, p)
    }
}

@Composable
private fun OffloadTaskRow(
    label: String,
    target: com.example.ai.OffloadManager.Target,
    p: com.example.ui.theme.AppPalette
) {
    val color = when (target) {
        com.example.ai.OffloadManager.Target.Computer -> p.neonCyan
        com.example.ai.OffloadManager.Target.Cloud -> p.neonCyan.copy(alpha = 0.75f)
        com.example.ai.OffloadManager.Target.Phone -> p.textMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = p.textLight)
        Text(I18n.tr("由 %s 處理").format(target.label), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun DeveloperModeSection(
    enabled: Boolean,
    offloadPlan: com.example.ai.OffloadManager.OffloadPlan,
    onRefreshOffload: () -> Unit,
    onOpenImageGen: () -> Unit,
    metrics: List<DeviceMetricSample>,
    hardware: com.example.ai.DeviceHardwareInfo,
    modelDownloadState: ModelDownloadState,
    modelProbeState: ModelProbeState,
    locationResult: com.example.ai.LocationResult?,
    onEnabledChange: (Boolean) -> Unit,
    onTestUrl: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onSimulateLocation: (Double, Double) -> Unit,
    settings: AppSettings,
    onGptSovitEnabled: (Boolean) -> Unit,
    onGptSovitVoiceMode: (GptSovitVoiceMode) -> Unit,
    onChineseVoiceUri: (String) -> Unit,
    onEnglishVoiceUri: (String) -> Unit,
    onModelDirUri: (String) -> Unit,
    onChineseModelDirUri: (String) -> Unit,
    onEnglishModelDirUri: (String) -> Unit,
    onAutoImportRootUri: (String) -> Unit,
    onApiBaseUrl: (String) -> Unit,
    onTestLang: (lang: com.example.ai.LocalSovitsHelper.VoiceLang, onDone: () -> Unit) -> Unit,
    onSovitsConsentAccepted: () -> Unit,
    onServerListChange: (String) -> Unit,
    synthesisStatus: String?,
    p: com.example.ui.theme.AppPalette
) {
    var hfUrl by remember { mutableStateOf("") }
    var showGptSovitPanel by remember { mutableStateOf(true) }
    var autoImportStatus by remember { mutableStateOf<String?>(null) }
    var importDialog by remember { mutableStateOf<GptSovitImportDialogState?>(null) }
    val context = LocalContext.current
    val chineseVoicePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onChineseVoiceUri(uri.toString())
        }
    }
    val englishVoicePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onEnglishVoiceUri(uri.toString())
        }
    }
    val modelDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            onModelDirUri(uri.toString())
        }
    }
    val chineseModelDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri)
            onChineseModelDirUri(uri.toString())
        }
    }
    val englishModelDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri)
            onEnglishModelDirUri(uri.toString())
        }
    }
    val autoImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            persistTreePermission(context, uri)
            onAutoImportRootUri(uri.toString())
            val detected = detectGptSovitBundle(context, uri)
            detected.chineseDirUri?.let(onChineseModelDirUri)
            detected.englishDirUri?.let(onEnglishModelDirUri)
            autoImportStatus = detected.message
        }
    }

    Section(title = I18n.tr("開發者模式"), icon = Icons.Default.DeveloperMode, p = p) {
        importDialog?.let { state ->
            GptSovitImportDialog(
                state = state,
                onDismiss = { importDialog = null },
                p = p
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(I18n.tr("效能監控與 Hugging Face 模型管理"), fontSize = 13.sp, color = p.textLight, fontWeight = FontWeight.Bold)
                Text(I18n.tr("關閉時不取樣，避免額外耗電。"), fontSize = 10.sp, color = p.textMuted)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        if (enabled) {
            Spacer(Modifier.height(12.dp))

            // 電腦接手計畫：依實測連線速度顯示語音/生圖/文字各由誰處理
            OffloadPlanCard(plan = offloadPlan, onRefresh = onRefreshOffload, p = p)
            Spacer(Modifier.height(12.dp))

            // AI 生圖（需要電腦/雲端）：移到開發者模式集中管理
            AdvancedEntryRow(
                label = I18n.tr("🖼 AI 生圖（輸入描述生成圖片、可設背景；本機電腦或雲端）"),
                onClick = onOpenImageGen, p = p
            )
            Spacer(Modifier.height(12.dp))

            if (showGptSovitPanel) {
                GptSovitDeveloperPanel(
                    enabled = settings.gptSovitEnabled,
                    mode = settings.gptSovitVoiceMode,
                    chineseVoiceUri = settings.gptSovitChineseVoiceUri,
                    englishVoiceUri = settings.gptSovitEnglishVoiceUri,
                    modelDirUri = settings.gptSovitModelDirUri,
                    chineseModelDirUri = settings.gptSovitChineseModelDirUri,
                    englishModelDirUri = settings.gptSovitEnglishModelDirUri,
                    autoImportRootUri = settings.gptSovitAutoImportRootUri,
                    autoImportStatus = autoImportStatus,
                    apiBaseUrl = settings.gptSovitApiBaseUrl,
                    serverList = settings.gptSovitServerList,
                    synthesisStatus = synthesisStatus,
                    consentAccepted = settings.sovitsConsentAccepted,
                    onConsentAccepted = onSovitsConsentAccepted,
                    onServerListChange = onServerListChange,
                    onEnabledChange = onGptSovitEnabled,
                    onModeChange = onGptSovitVoiceMode,
                    onApiBaseUrlChange = onApiBaseUrl,
                    onPickChinese = {
                        importDialog = GptSovitImportDialogState(
                            title = "匯入中文參考音檔",
                            message = "這會開啟 Android 系統檔案頁。若不想進檔案頁，請先用模型資料夾或自動匯入整包。",
                            openSystemPicker = { chineseVoicePicker.launch(arrayOf("audio/*")) }
                        )
                    },
                    onPickEnglish = {
                        importDialog = GptSovitImportDialogState(
                            title = "匯入英文參考音檔",
                            message = "這會開啟 Android 系統檔案頁。若不想進檔案頁，請先用模型資料夾或自動匯入整包。",
                            openSystemPicker = { englishVoicePicker.launch(arrayOf("audio/*")) }
                        )
                    },
                    onPickModelDir = {
                        importDialog = GptSovitImportDialogState(
                            title = "選擇舊版模型資料夾",
                            message = "建議改用中文/英文模型資料夾。系統檔案頁若沒有關閉鈕，請用手機返回鍵離開。",
                            openSystemPicker = { modelDirPicker.launch(defaultGptSovitInitialTreeUri(context)) }
                        )
                    },
                    onPickChineseModelDir = {
                        importDialog = GptSovitImportDialogState(
                            title = "匯入中文模型資料夾",
                            message = "選包含 SoVITS_weights / GPT_weights 的中文授權模型資料夾。也可以把整包放到預設資料夾後自動掃描。",
                            openSystemPicker = { chineseModelDirPicker.launch(defaultGptSovitInitialTreeUri(context)) },
                            useDefaultFolder = {
                                val detected = detectGptSovitBundleFromDefaultFolder(context)
                                detected.chineseDirUri?.let(onChineseModelDirUri)
                                autoImportStatus = detected.message
                            }
                        )
                    },
                    onPickEnglishModelDir = {
                        importDialog = GptSovitImportDialogState(
                            title = "匯入英文模型資料夾",
                            message = "選包含 SoVITS_weights / GPT_weights 的英文授權模型資料夾。也可以把整包放到預設資料夾後自動掃描。",
                            openSystemPicker = { englishModelDirPicker.launch(defaultGptSovitInitialTreeUri(context)) },
                            useDefaultFolder = {
                                val detected = detectGptSovitBundleFromDefaultFolder(context)
                                detected.englishDirUri?.let(onEnglishModelDirUri)
                                autoImportStatus = detected.message
                            }
                        )
                    },
                    onAutoImport = {
                        importDialog = GptSovitImportDialogState(
                            title = "自動匯入整包資料夾",
                            message = "你可以開系統檔案頁選最外層資料夾，也可以把整包放到預設資料夾後直接掃描。預設資料夾：${defaultGptSovitDir(context).absolutePath}",
                            openSystemPicker = { autoImportPicker.launch(defaultGptSovitInitialTreeUri(context)) },
                            useDefaultFolder = {
                                val detected = detectGptSovitBundleFromDefaultFolder(context)
                                detected.chineseDirUri?.let(onChineseModelDirUri)
                                detected.englishDirUri?.let(onEnglishModelDirUri)
                                onAutoImportRootUri(defaultGptSovitDir(context).toURI().toString())
                                autoImportStatus = detected.message
                            }
                        )
                    },
                    onClearImportWaste = {
                        autoImportStatus = clearDefaultGptSovitDir(context)
                        onChineseVoiceUri("")
                        onEnglishVoiceUri("")
                        onModelDirUri("")
                        onChineseModelDirUri("")
                        onEnglishModelDirUri("")
                        onAutoImportRootUri("")
                    },
                    onTestLang = onTestLang,
                    onClearChinese = { onChineseVoiceUri("") },
                    onClearEnglish = { onEnglishVoiceUri("") },
                    onClearModelDir = { onModelDirUri("") },
                    onClearChineseModelDir = { onChineseModelDirUri("") },
                    onClearEnglishModelDir = { onEnglishModelDirUri("") },
                    onClearAutoImport = {
                        onAutoImportRootUri("")
                        autoImportStatus = null
                    },
                    onBack = { showGptSovitPanel = false },
                    appLangTag = settings.language.tag,
                    p = p.iosReadable
                )
            } else {
                OutlinedButton(
                    onClick = { showGptSovitPanel = true },
                    border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.GraphicEq, null, tint = p.neonCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.tr("開啟 GPT-SoVITS 匯入與中英語音切換"), fontSize = 12.sp, color = p.neonCyan)
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 硬體規格列（一次性，不會動）──
            HardwareHeaderStrip(hardware = hardware, p = p)
            Spacer(Modifier.height(10.dp))

            // ── 即時指標 4×2：把 sandbox 拿得到的全部誠實列出，**不放假的「系統 CPU」**。
            //   - 系統 CPU 在 untrusted_app sandbox 永遠讀不到（SELinux 全擋 /proc/stat），
            //     舊版顯示「0%」騙人，這版直接拿掉；要看真實系統 CPU 請用 Android Studio Profiler。
            //   - App CPU / App RAM 來自 `top -n 1 -b` parse 我們自己這個 PID 的列，誤差 < 5%。
            val last = metrics.lastOrNull()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactMetricTile("App CPU", "%.1f%%".format(last?.appCpuPercent ?: 0f), p.dangerRose, Modifier.weight(1f), p)
                    CompactMetricTile("App RAM",
                        last?.appRssMb?.takeIf { it > 0 }?.let { if (it >= 1024) "%.1fG".format(it/1024) else "%.0fM".format(it) } ?: "—",
                        p.neonCyan, Modifier.weight(1f), p)
                    CompactMetricTile("GPU", last?.gpuUsagePercent?.let { "%.0f%%".format(it) } ?: "—",
                        p.auraAmber, Modifier.weight(1f), p)
                    CompactMetricTile("FPS", "%.0f".format(last?.frameRateHz ?: 0f), p.neonGreen, Modifier.weight(1f), p)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactMetricTile(I18n.tr("系統 RAM"), "%.0f%%".format(last?.ramUsedPercent ?: 0f), p.neonCyan, Modifier.weight(1f), p)
                    CompactMetricTile(I18n.tr("電量"), "%.0f%%".format(last?.batteryPercent ?: 0f), p.neonGreen, Modifier.weight(1f), p)
                    CompactMetricTile(I18n.tr("電池溫"), "%.1f°".format(last?.batteryTempC ?: 0f), p.auraAmber, Modifier.weight(1f), p)
                    CompactMetricTile(I18n.tr("網路"),
                        last?.let { it.networkEstimatedKbps.takeIf { v -> v > 0 } ?: it.networkDownKbps }
                            ?.let { if (it >= 1000) "%.0fM".format(it / 1000) else "%.0fK".format(it) } ?: "—",
                        p.neonCyan, Modifier.weight(1f), p)
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── 模擬定位測試（dev only）：用預設經緯度驗證 POI 分類規則 ──
            SimulateLocationPanel(
                locationResult = locationResult,
                onSimulate = onSimulateLocation,
                p = p
            )
            Spacer(Modifier.height(10.dp))

            // ── 趨勢圖：本 App CPU + 網路 ──
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricChart(I18n.tr("本 App CPU 趨勢"),
                    "%.1f%%".format(last?.appCpuPercent ?: 0f),
                    metrics, { it.appCpuPercent }, p.dangerRose, p)
                MetricChart(I18n.tr("網路下載速度"),
                    networkLabel(last),
                    metrics, { maxOf(it.networkDownKbps, it.networkEstimatedKbps) },
                    p.neonCyan, p)
            }

            Spacer(Modifier.height(14.dp))
            Text(I18n.tr("Hugging Face 公開模型下載"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.textLight)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = hfUrl,
                onValueChange = { hfUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                label = { Text("貼上公開 .task 模型 URL") },
                placeholder = { Text("https://huggingface.co/.../resolve/main/model.task") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = p.textLight,
                    unfocusedTextColor = p.textLight,
                    focusedBorderColor = p.neonCyan,
                    unfocusedBorderColor = p.textMuted.copy(alpha = 0.35f),
                    focusedContainerColor = p.darkBg.copy(alpha = 0.35f),
                    unfocusedContainerColor = p.darkBg.copy(alpha = 0.25f)
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(
                I18n.tr("不需要帳號或 API key；只有公開直連 URL 可下載。MediaPipe 推理目前會啟用 .task 格式。"),
                fontSize = 10.sp,
                color = p.textMuted,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onTestUrl(hfUrl) },
                    enabled = hfUrl.isNotBlank() && modelProbeState !is ModelProbeState.Testing,
                    border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.55f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (modelProbeState is ModelProbeState.Testing) I18n.tr("測試中") else I18n.tr("測試連線"), fontSize = 12.sp, color = p.neonCyan)
                }
                Button(
                    onClick = { onDownload(hfUrl) },
                    enabled = hfUrl.isNotBlank() && modelDownloadState !is ModelDownloadState.Downloading,
                    colors = ButtonDefaults.buttonColors(containerColor = p.neonCyan),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.tr("下載 URL"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = modelDownloadState is ModelDownloadState.Downloading,
                    border = BorderStroke(1.dp, p.dangerRose.copy(alpha = 0.55f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(I18n.tr("取消"), fontSize = 12.sp, color = p.dangerRose)
                }
            }
            ProbeStatus(modelProbeState, p)
            Spacer(Modifier.height(8.dp))
            ModelStatusBlock(modelDownloadState, onDownloadDefault = { onDownload("") }, onCancel = onCancel, p = p)
        }
    }
}

@Composable
private fun ProbeStatus(state: ModelProbeState, p: com.example.ui.theme.AppPalette) {
    val (text, color, icon) = when (state) {
        ModelProbeState.Idle -> return
        ModelProbeState.Testing -> Triple("正在測試公開 URL...", p.auraAmber, Icons.Default.NetworkCheck)
        is ModelProbeState.Success -> Triple(state.message, p.neonGreen, Icons.Default.CheckCircle)
        is ModelProbeState.Error -> Triple(state.message, p.dangerRose, Icons.Default.ErrorOutline)
    }
    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 10.sp, color = color, lineHeight = 14.sp)
    }
}

private fun networkLabel(sample: DeviceMetricSample?): String {
    val effective = maxOf(sample?.networkDownKbps ?: 0f, sample?.networkEstimatedKbps ?: 0f)
    return when {
        effective >= 1000f -> "%.1f Mbps".format(effective / 1000f)
        effective > 0f -> "%.0f Kbps".format(effective)
        else -> I18n.tr("未知")
    }
}

@Composable
private fun ModelStatusBlock(
    state: ModelDownloadState,
    onDownloadDefault: () -> Unit,
    onCancel: () -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    when (state) {
        is ModelDownloadState.Downloading -> {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(p.auraAmber.copy(alpha = 0.08f))
                    .border(1.dp, p.auraAmber.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.main_dl_downloading), fontSize = 11.sp, color = p.auraAmber, fontWeight = FontWeight.Bold)
                    Text("${state.progress}%", fontSize = 11.sp, color = p.auraAmber, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = p.auraAmber,
                    trackColor = p.textMuted.copy(alpha = 0.18f)
                )
                if (state.totalMb > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("${state.downloadedMb} MB / ${state.totalMb} MB", fontSize = 10.sp, color = p.textMuted)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.main_dl_cancel), fontSize = 11.sp, color = p.dangerRose)
                }
            }
        }
        is ModelDownloadState.Ready -> StatusRow(I18n.tr("本地 AI 模型已就緒"), Icons.Default.CheckCircle, p.neonGreen, p)
        is ModelDownloadState.Error -> {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(p.dangerRose.copy(alpha = 0.08f))
                    .border(1.dp, p.dangerRose.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = p.dangerRose, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.main_dl_failed), fontSize = 11.sp, color = p.dangerRose, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(state.message, fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDownloadDefault, colors = ButtonDefaults.buttonColors(containerColor = p.neonCyan), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.main_dl_retry), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        is ModelDownloadState.NotAvailable -> {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(p.neonGreen.copy(alpha = 0.07f))
                    .border(1.dp, p.neonGreen.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(stringResource(R.string.main_rule_engine_running), fontSize = 11.sp, color = p.neonGreen, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(state.instructions, fontSize = 10.sp, color = p.textMuted, lineHeight = 14.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDownloadDefault, colors = ButtonDefaults.buttonColors(containerColor = p.neonCyan), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.main_dl_button), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        ModelDownloadState.Idle -> StatusRow(I18n.tr("尚未啟動模型載入"), Icons.Default.Info, p.textMuted, p)
    }
}

@Composable
private fun MetricChart(
    title: String,
    value: String,
    samples: List<DeviceMetricSample>,
    selector: (DeviceMetricSample) -> Float,
    color: Color,
    p: com.example.ui.theme.AppPalette
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(p.darkBg.copy(alpha = 0.28f))
            .border(1.dp, p.textMuted.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(title, fontSize = 11.sp, color = p.textLight, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(58.dp)) {
            val values = samples.takeLast(40).map(selector)
            val max = (values.maxOrNull() ?: 100f).coerceAtLeast(1f)
            val min = (values.minOrNull() ?: 0f).coerceAtMost(max - 0.1f)
            val range = (max - min).coerceAtLeast(1f)
            drawLine(p.textMuted.copy(alpha = 0.16f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            if (values.size < 2) return@Canvas
            val path = Path()
            values.forEachIndexed { index, v ->
                val x = index.toFloat() / (values.lastIndex).coerceAtLeast(1).toFloat() * size.width
                val y = size.height - ((v - min) / range).coerceIn(0f, 1f) * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun TierBadge(tier: DeviceTier, p: com.example.ui.theme.AppPalette) {
    val color = tierColor(tier, p)
    Box(
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(tier.name, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun StatusRow(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    p: com.example.ui.theme.AppPalette
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = color)
    }
}

private fun tierColor(tier: DeviceTier, p: com.example.ui.theme.AppPalette) = when (tier) {
    DeviceTier.HIGH_END -> p.neonGreen
    DeviceTier.MID_TIER -> p.auraAmber
    DeviceTier.LOW_END -> p.dangerRose
}

// ─── iOS「設定」風格配色（蘋果設計美學，淺色 / 深色兩套，隨主題切換）───
private data class IosScheme(
    val bg: Color, val card: Color, val label: Color, val secondary: Color,
    val separator: Color, val blue: Color, val green: Color, val chevron: Color
)

private val IosDarkScheme = IosScheme(
    bg = Color(0xFF000000), card = Color(0xFF1C1C1E), label = Color(0xFFFFFFFF),
    secondary = Color(0xFF98989F), separator = Color(0xFF38383A),
    blue = Color(0xFF0A84FF), green = Color(0xFF30D158), chevron = Color(0xFF5B5B60)
)

private val IosLightScheme = IosScheme(
    bg = Color(0xFFF2F2F7), card = Color(0xFFFFFFFF), label = Color(0xFF000000),
    secondary = Color(0xFF6C6C70), separator = Color(0xFFC6C6C8),
    blue = Color(0xFF007AFF), green = Color(0xFF34C759), chevron = Color(0xFFBFBFC4)
)

/**
 * 依「當前 LocalAppPalette 的亮度」推算 iOS 配色：
 * 主畫面 → 跟著主題；浮動面板（FloatingPanel 會把 LocalAppPalette 換成 iosReadable）→ 跟著面板深淺。
 * 兩邊都靠同一個來源，不會出現「深卡配黑字」的不一致。
 */
@Composable
private fun currentIosScheme(): IosScheme {
    val bg = LocalAppPalette.current.darkBg
    val lum = bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f
    return if (lum > 0.5f) IosLightScheme else IosDarkScheme
}

// ─── Section / Row helpers（iOS inset-grouped 樣式）───

@Composable
private fun Section(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    p: com.example.ui.theme.AppPalette,
    content: @Composable () -> Unit
) {
    val c = currentIosScheme()
    Column(modifier = Modifier.fillMaxWidth()) {
        // 分組標題在卡片上方（iOS 風：灰色、非粗體、左內縮）
        Text(
            title,
            fontSize = 13.sp,
            color = c.secondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.card)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            content()
        }
    }
}

/** iOS 列容器：上下 11、最小 44dp（左右內縮由 Section 提供）。 */
@Composable
private fun IosCell(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            .padding(vertical = 11.dp),
        content = content
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    val c = currentIosScheme()
    IosCell(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = c.label)
            if (sub.isNotBlank()) {
                Text(sub, fontSize = 13.sp, color = c.secondary, lineHeight = 17.sp)
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.Check, null, tint = c.blue, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SliderRow(
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    val c = currentIosScheme()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = c.blue, inactiveTrackColor = c.separator),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(valueLabel, fontSize = 15.sp, color = c.secondary)
    }
}

@Composable
private fun AdvancedEntryRow(
    label: String,
    onClick: () -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    val c = currentIosScheme()
    IosCell(onClick = onClick) {
        Text(label, color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("›", color = c.chevron, fontSize = 20.sp)
    }
}

/** iOS 開關列：標題 + 說明 + 系統綠開關。 */
@Composable
private fun IosToggleRow(
    title: String,
    sub: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val c = currentIosScheme()
    IosCell {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = c.label)
            if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = c.secondary, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = c.green,
                checkedBorderColor = c.green,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = c.separator,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun HardwareHeaderStrip(
    hardware: com.example.ai.DeviceHardwareInfo,
    p: com.example.ui.theme.AppPalette
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, p.neonCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Default.Memory, null, tint = p.neonCyan, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(hardware.socFriendlyName, color = p.textLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "${hardware.gpuName}  ·  ${hardware.cpuCoreCount} cores",
                color = p.textMuted, fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun CompactMetricTile(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    p: com.example.ui.theme.AppPalette
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(p.surface)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(label, color = p.textMuted, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 模擬定位測試面板。預設 6 個台灣常見 POI，按一下就直接走 Geocoder + POI 規則，
 * 不需要實體 GPS。下方顯示分類結果，方便驗證 [com.example.ai.LocationTracker]
 * 的 keyword 表能否打中（缺哪一類就在 POI_RULES 補關鍵字）。
 */
@Composable
private fun SimulateLocationPanel(
    locationResult: com.example.ai.LocationResult?,
    onSimulate: (Double, Double) -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    // 經緯度全部對 `assets/poi/taiwan_pois.tsv` 校準到實際 OSM POI 中心點（誤差 < 30 m），
    // 這樣離線索引能 100% 命中。要新增測試點，先 grep 資料檔確認該座標有資料再加。
    // 覆蓋全台四區（北中南東），驗證離線 POI 索引在每個地方都查得到。
    // 座標都對過 OSM 確認周圍有 POI 資料。
    val presets = remember {
        listOf(
            Triple("🏥 台大醫院",     25.0411, 121.5165),       // 北 · 台北
            Triple("🚆 台北車站",     25.0478, 121.5170),       // 北 · 台北
            Triple("🍔 麥當勞-西門", 25.04484, 121.50527),     // 北 · 台北
            Triple("🏯 台中車站",     24.13748, 120.68200),     // 中 · 台中
            Triple("🌃 六合夜市",     22.63565, 120.30203),     // 南 · 高雄
            Triple("🌊 花蓮車站",     23.99114, 121.60330)      // 東 · 花蓮
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, p.auraAmber.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOn, null, tint = p.auraAmber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("🧪 模擬定位（dev 測試）", color = p.textLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        // 兩欄按鈕網格
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (name, lat, lon) ->
                        OutlinedButton(
                            onClick = { onSimulate(lat, lon) },
                            border = BorderStroke(1.dp, p.auraAmber.copy(alpha = 0.55f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(name, fontSize = 11.sp, color = p.textLight)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── 自由輸入：使用者可以從 Google Maps 複製座標來測 ──
        Text("自訂座標", color = p.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        var coordInput by remember { mutableStateOf("") }
        var parseError by remember { mutableStateOf<String?>(null) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        OutlinedTextField(
            value = coordInput,
            onValueChange = { coordInput = it; parseError = null },
            placeholder = { Text("貼上「25.0411, 121.5165」格式", fontSize = 11.sp, color = p.textMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.textLight,
                unfocusedTextColor = p.textLight,
                focusedBorderColor = p.auraAmber,
                unfocusedBorderColor = p.auraAmber.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        parseError?.let {
            Text(it, color = p.dangerRose, fontSize = 10.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    when (val r = parseLatLon(coordInput)) {
                        null -> parseError = "格式不對。應為「lat, lon」，例：25.0411, 121.5165"
                        else -> { parseError = null; onSimulate(r.first, r.second) }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = p.auraAmber, contentColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("測這個座標", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            // 「從 Google Maps 選」：開系統 Geo Intent，user 在 Maps 長按拿座標複製回來
            OutlinedButton(
                onClick = {
                    val center = locationResult?.let { "${it.latitude},${it.longitude}" } ?: "23.97,120.97"
                    val uri = "geo:$center?z=15&q=$center"
                    runCatching {
                        ctx.startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = p.neonCyan),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("📍 開 Maps", fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        // ── 結果顯示 ──
        if (locationResult != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.surface)
                    .padding(8.dp)
            ) {
                Text("分類: ${locationResult.shortTag.ifBlank { "（未命中規則）" }}",
                     color = p.neonGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("座標: %.5f, %.5f".format(locationResult.latitude, locationResult.longitude),
                     color = p.textMuted, fontSize = 10.sp)
                Text("地址: ${locationResult.address}",
                     color = p.textLight, fontSize = 10.sp, lineHeight = 13.sp)
                // 即時 AAC 情境推薦預覽
                val hint = com.example.ai.AacContextSuggester.suggest(locationResult.shortTag)
                if (hint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("→ AAC 推薦：${hint.suggestedItems.take(4).joinToString("、") { "${it.emoji}${it.label}" }}",
                         color = p.neonCyan, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        } else {
            Text("點上方按鈕或輸入座標做測試 →", color = p.textMuted, fontSize = 10.sp)
        }
    }
}

/**
 * 解析「lat, lon」、「lat,lon」、「lat lon」等格式。
 * 不做 DMS 解析（複雜度太高），Google Maps 的「複製座標」就是十進位 lat,lon 格式。
 */
private fun parseLatLon(input: String): Pair<Double, Double>? {
    val cleaned = input.trim().replace(Regex("[°NSEW,\\s]+"), " ").trim()
    val parts = cleaned.split(Regex("\\s+"))
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}
