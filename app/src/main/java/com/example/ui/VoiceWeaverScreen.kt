package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.ai.*
import com.example.data.AacCategory
import com.example.data.CameraLens
import com.example.data.DefaultAacItems
import com.example.ui.theme.LocalAppPalette
import com.example.ui.theme.backgroundGradient
import com.example.ui.theme.accentGradient
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VoiceWeaverScreen(
    viewModel: VoiceWeaverViewModel,
    onPracticeSentence: (String) -> Unit = {},
    /** 開啟沉浸式 AAC 大圖卡畫面（含 ✔/✖ 意圖確認卡，適合不識字使用者）*/
    onOpenAacFull: () -> Unit = {}
) {

    // UI flow states
    val deviceTier by viewModel.deviceTier.collectAsState()
    val vault by viewModel.globalTagsVault.collectAsState()
    val reconstructionState by viewModel.reconstructionState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val synthesisStatus by viewModel.sovitsSynthesisStatus.collectAsState()
    val isSynthesizing by viewModel.isSynthesizing.collectAsState()
    val intentImagePath by viewModel.intentImagePath.collectAsState()
    val intentImageLoading by viewModel.intentImageLoading.collectAsState()

    val isSensingLocation by viewModel.isSensingLocation.collectAsState()
    val isSensingVision by viewModel.isSensingVision.collectAsState()
    val locationResult by viewModel.locationResult.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val sttState by viewModel.sttState.collectAsState()
    val isListeningDirect by viewModel.isListeningDirect.collectAsState()
    val ttsInitialized by viewModel.ttsInitialized.collectAsState()

    // Colors 從 LocalAppPalette 取（隨設定主題切換立即重組）
    val p = LocalAppPalette.current
    val darkBg = p.darkBg
    val surfaceColor = p.surface
    val neonCyan = p.neonCyan
    val neonGreen = p.neonGreen
    val auraAmber = p.auraAmber
    val dangerRose = p.dangerRose
    val textLight = p.textLight
    val textMuted = p.textMuted
    val isLightTheme = surfaceColor.luminance() > 0.7f
    val inputContainer = if (isLightTheme) Color.White else Color.Black.copy(alpha = 0.2f)
    val subtleContainer = if (isLightTheme) Color(0xFFF8FAFC) else Color.Black.copy(alpha = 0.25f)
    val inactiveChipContainer = if (isLightTheme) Color(0xFFE5E7EB) else Color.DarkGray.copy(alpha = 0.4f)
    val faintBorder = if (isLightTheme) textMuted.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f)
    val hasCustomBackground = settings.customBackgroundUri.isNotBlank()
    val scaffoldBg = if (hasCustomBackground) darkBg.copy(alpha = 0.84f) else darkBg

    Scaffold(
        // 外層 MainActivity Scaffold 已透過 padding(innerPadding) 處理 NavigationBar 高度，
        // 內層 Scaffold 設 WindowInsets(0) 避免重複計算造成底部內容被截斷
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(neonCyan, Color.Transparent)))
                                .border(1.5.dp, neonCyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = "VoiceWeaver Logo",
                                tint = textLight,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "VoiceWeaver",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = textLight
                            )
                            Text(
                                text = stringResource(R.string.main_app_tagline),
                                fontSize = 11.sp,
                                color = textMuted,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scaffoldBg,
                    titleContentColor = textLight
                )
            )
        },
        // 沒有自訂背景圖時用主題漸層底（有深度，不死板平塗）；有圖時維持半透明壓底讓圖透出
        containerColor = if (hasCustomBackground) scaffoldBg else Color.Transparent
    ) { innerPadding ->
        // 發聲風險閘門：醫療內容確認 / 高風險鎖定（全畫面共用）
        SpeakGateDialogs(viewModel = viewModel, p = p)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (hasCustomBackground) Modifier
                    else Modifier.background(p.backgroundGradient)
                )
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 臨床高頻置頂面板（醫師臨床建議）：定位在醫療場所時強制置頂一鍵求救 ──
            val clinicalLocation =
                listOf("醫院", "診所", "復健", "病房", "急診", "藥局").any { vault.locationTag.contains(it) }
            if (clinicalLocation) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = dangerRose.copy(alpha = 0.12f)),
                        border = BorderStroke(1.5.dp, dangerRose.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                I18n.tr("🏥 快速求救（一鍵發聲）"),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = dangerRose
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf(I18n.tr("😖 我痛") to I18n.tr("我很痛，請幫幫我。"),
                                       I18n.tr("💧 喝水") to I18n.tr("我想要喝水。"),
                                       I18n.tr("🚻 廁所") to I18n.tr("我想上廁所，請帶我去。")).forEach { (label, phrase) ->
                                    Button(
                                        onClick = { viewModel.speakClinicalPhrase(phrase) },
                                        colors = ButtonDefaults.buttonColors(containerColor = dangerRose),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 14.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 我的最愛：收藏的常用句，一鍵朗讀 ──
            val favorites = settings.favoriteSentences.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            if (favorites.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = surfaceColor),
                        border = BorderStroke(1.dp, auraAmber.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(I18n.tr("⭐ 我的最愛（點一下朗讀）"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = auraAmber)
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                favorites.take(12).forEach { fav ->
                                    Box(
                                        modifier = Modifier
                                            .padding(bottom = 6.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(auraAmber.copy(alpha = 0.14f))
                                            .border(1.dp, auraAmber.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                            .clickable { if (ttsInitialized) viewModel.speak(fav) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) { Text(fav, fontSize = 14.sp, color = textLight, maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Context Manager Panel (GPS, Camera, Sensors)
            item {
                Text(
                    text = stringResource(R.string.main_sec_context),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = neonCyan
                )

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                    border = BorderStroke(1.dp, faintBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = neonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.main_gps_section),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = textLight
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LocationPresetDropdown(
                            locationTag = vault.locationTag,
                            onPick = { viewModel.onLocationChanged(it.label) },
                            surfaceColor = surfaceColor,
                            neonCyan = neonCyan,
                            auraAmber = auraAmber,
                            textLight = textLight,
                            textMuted = textMuted
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // GPS 按鈕 — 未授權時原地顯示授權按鈕，不改變版面
                        val locPerm = rememberPermissionState(AppPermissions.LOCATION)
                        // 見 MICROPHONE 處的說明：release 版此 Map 恆空，等同讀系統真實狀態。
                        val forcedLocPerms by com.example.testing.TestHooks.forcedPermissions.collectAsState()
                        if (forcedLocPerms["LOCATION"] ?: locPerm.status.isGranted) {
                            Button(
                                onClick = { viewModel.startSensingLocation() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSensingLocation) textMuted.copy(alpha = 0.35f) else neonCyan,
                                    contentColor = Color.White,
                                    disabledContainerColor = textMuted.copy(alpha = 0.20f),
                                    disabledContentColor = textMuted
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().testTag("gps_single_shot_btn"),
                                enabled = !isSensingLocation
                            ) {
                                if (isSensingLocation) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.main_gps_loading), fontSize = 11.sp)
                                } else {
                                    Icon(Icons.Default.MyLocation, "gps_icon", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.main_gps_button), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { locPerm.launchPermissionRequest() },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Lock, null, tint = neonCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.main_perm_location), fontSize = 11.sp, color = neonCyan)
                            }
                        }

                        // GPS result display
                        locationResult?.let { result ->
                            Spacer(modifier = Modifier.height(6.dp))
                            // 之所以拆成兩列：右邊放「📍 設為我家」一鍵動作；
                            // 看到誤抓店家時，使用者可以立刻把當前 GPS 釘成住家錨點，
                            // 下次再按 GPS 就會回「住家」而不是隔壁巷子的食品行。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(neonCyan.copy(alpha = 0.08f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LocationOn, null, tint = neonCyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = I18n.tr("目前地點：%s").format(result.shortTag),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textLight,
                                            maxLines = 1
                                        )
                                        if (result.address != result.shortTag) {
                                            Text(result.address, fontSize = 11.sp, color = neonCyan, maxLines = 2)
                                        }
                                        Text("%.4f, %.4f".format(result.latitude, result.longitude), fontSize = 10.sp, color = textMuted)
                                    }
                                }
                                // 一鍵把當前 GPS 設為「住家」 — 解決 OSM 誤標 / 街口隔壁店誤抓
                                var setHomeStatus by remember { mutableStateOf<String?>(null) }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            setHomeStatus = I18n.tr("設定中…")
                                            viewModel.setCurrentGpsAsHomeNow { success, addr ->
                                                setHomeStatus = if (success) I18n.tr("✅ 已設為我家：%s").format(addr ?: "") else I18n.tr("❌ 沒抓到 GPS")
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = auraAmber),
                                        border = BorderStroke(1.dp, auraAmber.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Home, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(I18n.tr("設為我家"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val uri = android.net.Uri.parse("geo:${result.latitude},${result.longitude}?z=18&q=${result.latitude},${result.longitude}")
                                            runCatching {
                                                context.startActivity(
                                                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                                        .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = neonCyan),
                                        border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f).height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Map, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(I18n.tr("看地圖"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                setHomeStatus?.let {
                                    Text(it, fontSize = 10.sp, color = if (it.startsWith("✅")) neonGreen else dangerRose)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = neonGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.main_vision_section),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = textLight
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 同樣統一改走 AacContextSuggester.visionPresetsFor — 涵蓋 17+ 位置，
                        // 例：在便利商店預設帶「商品架/結帳台/微波爐/冷藏櫃/御飯糰/飲料」；在醫院帶「掛號單/藥袋/輪椅…」
                        val activeVisionPresets = remember(vault.locationTag) {
                            com.example.ai.AacContextSuggester.visionPresetsFor(vault.locationTag)
                        }
                        // 顯示順序：當前選取的 visionTags 排第一（不論在不在 preset 內），其後接 preset 沒選的部分去重。
                        // 這樣使用者從「+ 更多物品」加進來的非 preset 物品也會留在主畫面看得到，不會跳出選單就消失。
                        val combinedTags = remember(activeVisionPresets, vault.visionTags) {
                            val seen = mutableSetOf<String>()
                            val out = mutableListOf<String>()
                            vault.visionTags.forEach { if (seen.add(it)) out.add(it) }
                            activeVisionPresets.forEach { if (seen.add(it)) out.add(it) }
                            out
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            combinedTags.forEach { tag ->
                                val isSelected = vault.visionTags.contains(tag)
                                val emoji = com.example.ai.AacContextSuggester.visionEmojiFor(tag)
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) neonGreen.copy(alpha = 0.16f) else inactiveChipContainer)
                                        .border(
                                            1.dp,
                                            if (isSelected) neonGreen else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            val current = vault.visionTags.toMutableList()
                                            if (current.contains(tag)) current.remove(tag) else current.add(tag)
                                            viewModel.onVisionTagsChanged(current)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(emoji, fontSize = 14.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = I18n.tr(tag),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) textLight else textMuted
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Default.Check, null, tint = neonGreen, modifier = Modifier.size(10.dp))
                                        }
                                    }
                                }
                            }
                            // 「+ 更多物品」按鈕作為 FlowRow 末項，跟其它 chip 同高度
                            var showCatalog by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(auraAmber.copy(alpha = 0.12f))
                                    .border(1.dp, auraAmber.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                    .clickable { showCatalog = true }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, null, tint = auraAmber, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(I18n.tr("更多物品"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = auraAmber)
                                }
                            }
                            if (showCatalog) {
                                VisionCatalogDialog(
                                    selected = vault.visionTags,
                                    onToggle = { tag ->
                                        val current = vault.visionTags.toMutableList()
                                        if (current.contains(tag)) current.remove(tag) else current.add(tag)
                                        viewModel.onVisionTagsChanged(current)
                                    },
                                    onDismiss = { showCatalog = false }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Camera button guarded by camera permission
                        val camPerm = rememberPermissionState(AppPermissions.CAMERA)
                        // 見 MICROPHONE 處的說明：release 版此 Map 恆空，等同讀系統真實狀態。
                        val forcedCamPerms by com.example.testing.TestHooks.forcedPermissions.collectAsState()
                        if (forcedCamPerms["CAMERA"] ?: camPerm.status.isGranted) {
                            var showCameraPreview by remember { mutableStateOf(false) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                CameraLensChip(
                                    label = I18n.tr(CameraLens.Back.displayName),
                                    selected = settings.cameraLens == CameraLens.Back,
                                    onClick = { viewModel.setCameraLens(CameraLens.Back) },
                                    modifier = Modifier.weight(1f),
                                    selectedColor = neonGreen,
                                    textLight = textLight,
                                    textMuted = textMuted
                                )
                                CameraLensChip(
                                    label = I18n.tr(CameraLens.Front.displayName),
                                    selected = settings.cameraLens == CameraLens.Front,
                                    onClick = { viewModel.setCameraLens(CameraLens.Front) },
                                    modifier = Modifier.weight(1f),
                                    selectedColor = neonGreen,
                                    textLight = textLight,
                                    textMuted = textMuted
                                )
                                CameraLensChip(
                                    label = I18n.tr(CameraLens.Both.displayName),
                                    selected = settings.cameraLens == CameraLens.Both,
                                    onClick = { viewModel.setCameraLens(CameraLens.Both) },
                                    modifier = Modifier.weight(1f),
                                    selectedColor = neonGreen,
                                    textLight = textLight,
                                    textMuted = textMuted
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = { showCameraPreview = true },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, neonGreen.copy(alpha = 0.55f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Visibility, null, tint = neonGreen, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(I18n.tr("開啟預覽"), fontSize = 11.sp, color = neonGreen, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { viewModel.startSensingVision() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSensingVision) textMuted.copy(alpha = 0.35f) else neonGreen,
                                        contentColor = Color.White,
                                        disabledContainerColor = textMuted.copy(alpha = 0.20f),
                                        disabledContentColor = textMuted
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    // 預設的按鈕內距左右各 24dp，光是它就吃掉 48dp——這顆跟旁邊
                                    // 平分寬度（各約 160dp），圖示加十個字剛好差一點點就得折成兩行。
                                    // 收掉多餘內距讓標籤維持單行。
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f).testTag("vision_single_shot_btn"),
                                    enabled = !isSensingVision
                                ) {
                                    if (isSensingVision) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.main_camera_loading), fontSize = 11.sp)
                                    } else {
                                        Icon(Icons.Default.PhotoCamera, "camera_icon", modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.main_camera_button), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (showCameraPreview) {
                                CameraPreviewDialog(
                                    onDismiss = { showCameraPreview = false },
                                    isLandscape = isLandscape,
                                    lens = settings.cameraLens,
                                    onLensChange = { viewModel.setCameraLens(it) },
                                    surfaceColor = surfaceColor,
                                    neonGreen = neonGreen,
                                    textLight = textLight,
                                    textMuted = textMuted
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { camPerm.launchPermissionRequest() },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, neonGreen.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Lock, null, tint = neonGreen, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.main_perm_camera), fontSize = 11.sp, color = neonGreen)
                            }
                        }
                    }
                }
            }

            // Section 3: Input Fragment Workspace
            item {
                Text(
                    text = stringResource(R.string.main_sec_input),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = neonCyan
                )

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                    border = BorderStroke(1.dp, faintBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.main_input_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = textMuted,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        OutlinedTextField(
                            value = vault.rawText,
                            onValueChange = { viewModel.onRawTextChanged(it) },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.main_input_placeholder),
                                    fontSize = 13.sp,
                                    color = textMuted
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isLandscape) 76.dp else 96.dp)
                                .testTag("raw_text_input_field"),
                            maxLines = if (isLandscape) 3 else 4,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = inputContainer,
                                unfocusedContainerColor = inputContainer,
                                focusedBorderColor = neonCyan,
                                unfocusedBorderColor = textMuted.copy(alpha = 0.45f),
                                focusedTextColor = textLight,
                                unfocusedTextColor = textLight,
                                focusedPlaceholderColor = textMuted,
                                unfocusedPlaceholderColor = textMuted
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // STT section guarded by microphone permission
                        val micPerm = rememberPermissionState(AppPermissions.MICROPHONE)
                        var showMicPermAlert by remember { mutableStateOf(false) }  // 問題8：權限提示
                        // 測試側門可在不重啟 App 的情況下模擬「授權被撤銷」；release 版這個
                        // Map 永遠是空的，等同直接讀系統的真實授權狀態。
                        val forcedPerms by com.example.testing.TestHooks.forcedPermissions.collectAsState()
                        val micGranted = forcedPerms["MICROPHONE"] ?: micPerm.status.isGranted
                        if (micGranted) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(subtleContainer)
                                    .border(
                                        1.dp,
                                        when (sttState) {
                                            is SttState.Listening -> neonCyan.copy(alpha = 0.6f)
                                            is SttState.Processing -> auraAmber.copy(alpha = 0.4f)
                                            is SttState.Error -> dangerRose.copy(alpha = 0.4f)
                                            else -> faintBorder
                                        },
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when (sttState) {
                                                    is SttState.Listening -> neonCyan.copy(alpha = 0.2f)
                                                    is SttState.Processing -> auraAmber.copy(alpha = 0.1f)
                                                    else -> neonCyan.copy(alpha = 0.1f)
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (sttState is SttState.Listening) Icons.Default.MicOff else Icons.Default.Mic,
                                            contentDescription = "STT",
                                            tint = when (sttState) {
                                                is SttState.Listening -> neonCyan
                                                is SttState.Processing -> auraAmber
                                                is SttState.Error -> dangerRose
                                                else -> neonCyan
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = when (sttState) {
                                                is SttState.Listening -> stringResource(R.string.main_stt_listening)
                                                is SttState.Processing -> stringResource(R.string.main_stt_processing)
                                                is SttState.Preparing -> (sttState as SttState.Preparing).message
                                                is SttState.Error -> (sttState as SttState.Error).message
                                                else -> stringResource(R.string.main_stt_idle)
                                            },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when (sttState) {
                                                is SttState.Listening -> neonCyan
                                                is SttState.Processing -> auraAmber
                                                is SttState.Preparing -> auraAmber
                                                is SttState.Error -> dangerRose
                                                else -> textLight
                                            }
                                        )
                                        Text(
                                            text = when (sttState) {
                                                is SttState.Listening -> stringResource(R.string.main_stt_listening_sub)
                                                is SttState.Processing -> stringResource(R.string.main_stt_processing_sub)
                                                is SttState.Preparing -> stringResource(R.string.main_stt_preparing_sub)
                                                else -> stringResource(R.string.main_stt_idle_sub)
                                            },
                                            fontSize = 11.sp,
                                            color = textMuted
                                        )
                                    }
                                }

                                Button(
                                    onClick = { viewModel.toggleStt() },
                                    enabled = sttState !is SttState.Processing && sttState !is SttState.Preparing,
                                    colors = ButtonDefaults.buttonColors(
                                        // 有雲端音訊能力時這顆會走「直接讓 AI 聽」，錄音中是 isListeningDirect
                                        // 而不是 sttState.Listening——兩個都要看，否則錄音中按鈕看起來沒反應。
                                        containerColor = when {
                                            isListeningDirect -> dangerRose
                                            sttState is SttState.Listening -> dangerRose
                                            else -> neonCyan
                                        },
                                        contentColor = Color.White,
                                        disabledContainerColor = textMuted.copy(alpha = 0.20f),
                                        disabledContentColor = textMuted
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("stt_btn")
                                ) {
                                    Text(
                                        text = when {
                                            isListeningDirect -> stringResource(R.string.main_stt_btn_stop)
                                            sttState is SttState.Listening -> stringResource(R.string.main_stt_btn_stop)
                                            sttState is SttState.Processing -> stringResource(R.string.main_stt_btn_processing)
                                            sttState is SttState.Preparing -> stringResource(R.string.main_stt_btn_preparing)
                                            else -> stringResource(R.string.main_stt_btn_start)
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            // ── 音訊原生理解：不轉文字，直接讓 AI 聽 ──
                            // STT 會把構音障礙的語音壓成錯誤文字，語調／停頓／語音錯誤全丟掉；
                            // 這條路把錄音整段交給模型自己聽，準確度對失語症患者明顯更好。
                            // 只有設定了具音訊能力的供應商（Gemini）時才顯示。
                            // 綁到會變動的設定欄位：使用者新增 Gemini 金鑰後才會重新評估，
                            // 直接呼叫函式的話 Compose 不知道要重組，按鈕會一直不出現。
                            val canHearAudio = remember(
                                settings.llmApis, settings.llmApiSelectedId, settings.cloudProvider
                            ) { viewModel.canUnderstandAudio() }
                            if (canHearAudio) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.toggleDirectAudioUnderstanding() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isListeningDirect) dangerRose else neonGreen,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                ) {
                                    Icon(
                                        if (isListeningDirect) Icons.Default.StopCircle else Icons.Default.Hearing,
                                        null, modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (isListeningDirect) I18n.tr("說完了，請 AI 聽")
                                        else I18n.tr("直接說給 AI 聽（不轉文字）"),
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    if (isListeningDirect)
                                        I18n.tr("正在錄音…說完按上面的按鈕。")
                                    else
                                        I18n.tr("咬字不清楚也沒關係，AI 會直接聽你的聲音判斷意思。"),
                                    fontSize = 12.sp, color = textMuted, lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        } else {
                            // 問題8：未授權時顯示權限請求提示
                            OutlinedButton(
                                onClick = {
                                    micPerm.launchPermissionRequest()
                                    showMicPermAlert = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Default.Lock, null, tint = neonCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.main_perm_microphone), fontSize = 12.sp, color = neonCyan)
                            }
                            if (showMicPermAlert) {
                                AlertDialog(
                                    onDismissRequest = { showMicPermAlert = false },
                                    title = { Text(I18n.tr("需要麥克風權限")) },
                                    text = { Text(I18n.tr("請允許麥克風權限以使用語音輸入功能。")) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            micPerm.launchPermissionRequest()
                                            showMicPermAlert = false
                                        }) { Text(I18n.tr("前往設定")) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showMicPermAlert = false }) { Text(I18n.tr("取消")) }
                                    },
                                    containerColor = p.surface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.resetState() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = textMuted),
                                border = BorderStroke(1.dp, textMuted.copy(alpha = 0.45f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, "reset")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.main_btn_reset), fontSize = 13.sp)
                            }

                            val reconstructEnabled = vault.rawText.isNotBlank()
                            Button(
                                onClick = { viewModel.triggerSentenceReconstruction() },
                                contentPadding = PaddingValues(0.dp),   // 讓漸層填滿整顆按鈕
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    disabledContentColor = textMuted
                                ),
                                shape = RoundedCornerShape(12.dp),
                                enabled = reconstructEnabled,
                                modifier = Modifier
                                    .weight(1.8f)
                                    .height(52.dp)   // 主要 CTA：加高，最容易按
                                    .testTag("weaver_reconstruct_button")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (reconstructEnabled) Modifier.background(p.accentGradient)
                                            else Modifier.background(textMuted.copy(alpha = 0.20f))
                                        )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, "execute")
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.main_btn_reconstruct), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = stringResource(R.string.main_quick_phrases_label),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = textMuted
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        // 之前寫死 4 個地名 (客廳/台大醫院/全聯/街口餐廳)，新動態位置 (住家 / 便利商店·全家 / 醫院·XXX) 全掉 default。
                        // 改走 AacContextSuggester.quickPhrasesFor 統一兜底，17+ 位置全自動覆蓋。
                        val quickPhrases = remember(vault.locationTag) {
                            com.example.ai.AacContextSuggester.quickPhrasesFor(vault.locationTag)
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            quickPhrases.forEach { phrase ->
                                AssistChip(
                                    onClick = {
                                        val current = vault.rawText
                                        viewModel.onRawTextChanged(if (current.isEmpty()) phrase else "$current $phrase")
                                    },
                                    label = { Text(text = I18n.tr(phrase), fontSize = 11.sp) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = subtleContainer,
                                        labelColor = textLight
                                    ),
                                    border = BorderStroke(0.5.dp, textMuted.copy(alpha = 0.3f)),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        // 圖卡已獨立成底部「圖卡」分頁（一按就到），重組頁不再內嵌整套
                        // 溝通板——同一份東西出現在兩處只會讓患者困惑「要在哪裡按」。
                        // 這裡只留一顆窄捷徑給還在找它的人。
                        OutlinedButton(
                            onClick = onOpenAacFull,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        ) {
                            Text(I18n.tr("用圖卡說話 → 到「圖卡」分頁"), fontSize = 13.sp, color = neonCyan)
                        }
                    }
                }
            }

            // Section 4: Output / Reconstructed Result Screen
            item {
                Text(
                    text = stringResource(R.string.main_sec_output),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = neonCyan
                )

                when (val state = reconstructionState) {
                        is ReconstructionUiState.Idle -> {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.6f)),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = textMuted,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.main_output_idle),
                                        fontSize = 12.sp,
                                        color = textMuted,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        is ReconstructionUiState.Loading -> {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.8f)),
                                border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    CircularProgressIndicator(color = neonCyan, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = stringResource(R.string.main_output_loading),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textLight
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.main_output_running,
                                                stringResource(
                                                    when (deviceTier) {
                                                        DeviceTier.HIGH_END -> R.string.main_engine_gemini
                                                        DeviceTier.MID_TIER -> R.string.main_engine_qwen
                                                        else -> R.string.main_engine_rule
                                                    }
                                                )
                                            ),
                                            fontSize = 11.sp,
                                            color = textMuted
                                        )
                                    }
                                }
                            }
                        }

                        is ReconstructionUiState.Error -> {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                border = BorderStroke(1.dp, dangerRose.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = dangerRose,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(text = state.message, fontSize = 13.sp, color = dangerRose)
                                }
                            }
                        }

                        is ReconstructionUiState.Success -> {
                            val data = state.data
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                border = BorderStroke(1.dp, neonGreen.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    // 設計：患者視角只需要「句子 + 把握程度 + 唸出來」。
                                    // 引擎名 / 延遲 ms / 信心百分比是工程指標 — 收進開發者模式。
                                    if (settings.developerMode) {
                                        // 引擎名很長（可能含 fallback 字串），跟延遲/信心放同一列會把右側擠成直排。
                                        // 改上下兩行：① 圓點 + 引擎名（佔滿、可折 2 行）② 延遲 · 信心（自己一行不被擠）。
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 5.dp)
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(neonGreen)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = data.engineUsed,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = neonGreen,
                                                lineHeight = 15.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.main_latency, data.inferenceTimeMs.toInt()) +
                                                " · " + stringResource(R.string.main_conf_label, (data.confidence * 100).toInt()),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = textMuted,
                                            modifier = Modifier.padding(start = 12.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    // 動態專屬圖卡：依重組語意生成的 AI 圖（開「意圖卡 AI 生成專屬圖」時）
                                    if (intentImagePath != null) {
                                        coil.compose.AsyncImage(
                                            model = java.io.File(intentImagePath!!),
                                            contentDescription = I18n.tr("意圖示意圖"),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 220.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    } else if (intentImageLoading) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = neonCyan, strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(I18n.tr("正在生成專屬圖卡…"), fontSize = 12.sp, color = textMuted)
                                        }
                                    }

                                    Text(
                                        text = data.reconstructedText,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textLight,
                                        lineHeight = 28.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("weaver_output_sentence")
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = when {
                                            data.confidence >= 0.90 -> stringResource(R.string.main_conf_high)
                                            data.confidence >= 0.75 -> stringResource(R.string.main_conf_mid)
                                            else -> stringResource(R.string.main_conf_low)
                                        },
                                        fontSize = 11.sp,
                                        color = textMuted,
                                        fontWeight = FontWeight.Medium
                                    )

                                    // AI 一次取樣 3 個候選句（自我一致性），三句一起攤開讓使用者挑，
                                    // 依信心度由高到低排——最可能對的排最上面，手指移動距離最短。
                                    // 點一下只是「選定」，不會發聲；要唸出來還是按下面那顆大的朗讀鍵，
                                    // 這樣挑的過程不會每點一次就送一次合成請求。
                                    if (data.alternatives.size > 1) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            I18n.tr("挑一句最接近的"),
                                            fontSize = 11.sp, color = textMuted, fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val ranked = remember(data.alternatives) {
                                            data.alternatives.withIndex().sortedByDescending { it.value.confidence }
                                        }
                                        ranked.forEach { (originalIndex, cand) ->
                                            val selected = data.alternativeIndex == originalIndex
                                            OutlinedButton(
                                                onClick = { viewModel.selectReconstructionAlternative(originalIndex) },
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(
                                                    if (selected) 2.dp else 1.dp,
                                                    if (selected) neonCyan else textMuted.copy(alpha = 0.35f)
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 6.dp)
                                            ) {
                                                Icon(
                                                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                    null,
                                                    tint = if (selected) neonCyan else textMuted,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    cand.text,
                                                    fontSize = 14.sp,
                                                    color = if (selected) textLight else textMuted,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                    lineHeight = 20.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "${(cand.confidence * 100).toInt()}%",
                                                    fontSize = 11.sp,
                                                    color = if (selected) neonCyan else textMuted,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                        // 三句都不對＝AI 這批全猜錯，重新呼叫 LLM 生一批新的
                                        OutlinedButton(
                                            onClick = { viewModel.regenerateAlternatives() },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.5f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Autorenew, null, tint = neonCyan, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                I18n.tr("三個都不對？重新生成"),
                                                fontSize = 12.sp, color = neonCyan, fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // 候選句二次確認（醫師臨床建議）：AI 句禁止自動發聲，
                                    // 點選候選 → 風險分級 → 一般直接朗讀 / 醫療確認 / 高風險鎖定。
                                    Button(
                                        onClick = {
                                            if (ttsInitialized) {
                                                viewModel.chooseCandidate(0, data.reconstructedText)
                                            }
                                        },
                                        contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth().height(56.dp)   // 最終動作：最大目標
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(neonGreen, androidx.compose.ui.graphics.lerp(neonGreen, neonCyan, 0.4f))
                                                    )
                                                )
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "tts")
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(text = stringResource(R.string.main_btn_tts), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            }
                                        }
                                    }

                                    // 合成狀態：角色聲音走電腦 CPU 要 10～30 秒，按下朗讀後
                                    // 畫面得有東西動，否則分不清在算還是壞了，只好一直重按。
                                    // 不畫進度條——伺服器不回報進度，畫出來的比例只會是編的。
                                    if (isSynthesizing) {
                                        synthesisStatus?.let { st ->
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(st, fontSize = 12.sp, color = textMuted)
                                        }
                                    }

                                    // 候選 2：AI 重組不符合意圖時，改唸原始輸入（不經 LLM）
                                    if (data.originalText.isNotBlank() && data.originalText != data.reconstructedText) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = {
                                                if (ttsInitialized) {
                                                    viewModel.chooseCandidate(1, data.originalText)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, textMuted.copy(alpha = 0.5f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = I18n.tr("不對？唸原始輸入：「%s」").format(data.originalText.take(20)),
                                                fontSize = 12.sp,
                                                color = textMuted,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // ── 次要動作收進「更多」──
                                    // 病人當下真正要的只有「唸出來」跟「不對就換一個」；
                                    // 練習發音／複製／分享／收藏／四國語言共 8 顆按鈕平常只是干擾，
                                    // 收起來讓卡片一眼就看得完，要用時再展開。
                                    Spacer(modifier = Modifier.height(6.dp))
                                    var showMoreActions by remember(data.reconstructedText) { mutableStateOf(false) }
                                    TextButton(
                                        onClick = { showMoreActions = !showMoreActions },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            if (showMoreActions) I18n.tr("收起") else I18n.tr("更多"),
                                            fontSize = 12.sp, color = textMuted
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            if (showMoreActions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            null, tint = textMuted, modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    if (showMoreActions) {
                                    // 跳轉到獨立的「語音復健」分頁，並把這句帶過去
                                    OutlinedButton(
                                        onClick = { onPracticeSentence(data.reconstructedText) },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.School, null, tint = neonCyan, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.main_btn_practice), fontSize = 12.sp, color = neonCyan, fontWeight = FontWeight.Bold)
                                    }

                                    // ── 複製 / 分享 / 收藏 ──
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val isFav = settings.favoriteSentences.split("\n").any { it.trim() == data.reconstructedText.trim() }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = {
                                                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                clip.setPrimaryClip(android.content.ClipData.newPlainText("VoiceWeaver", data.reconstructedText))
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, textMuted.copy(alpha = 0.5f)),
                                            modifier = Modifier.weight(1f)
                                        ) { Icon(Icons.Default.ContentCopy, null, tint = textMuted, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(I18n.tr("複製"), fontSize = 12.sp, color = textMuted) }
                                        OutlinedButton(
                                            onClick = {
                                                context.startActivity(android.content.Intent.createChooser(
                                                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, data.reconstructedText)
                                                    }, I18n.tr("分享句子")))
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, textMuted.copy(alpha = 0.5f)),
                                            modifier = Modifier.weight(1f)
                                        ) { Icon(Icons.Default.Share, null, tint = textMuted, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(I18n.tr("分享"), fontSize = 12.sp, color = textMuted) }
                                        OutlinedButton(
                                            onClick = { viewModel.toggleFavorite(data.reconstructedText) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, if (isFav) auraAmber else textMuted.copy(alpha = 0.5f)),
                                            modifier = Modifier.weight(1f)
                                        ) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (isFav) auraAmber else textMuted, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(I18n.tr("收藏"), fontSize = 12.sp, color = if (isFav) auraAmber else textMuted) }
                                    }

                                    // ── 多語朗讀（中英日韓）──
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        listOf(
                                            "🇹🇼 中" to com.example.data.AppLanguage.ZhTW,
                                            "🇺🇸 EN" to com.example.data.AppLanguage.En,
                                            "🇯🇵 日" to com.example.data.AppLanguage.Ja,
                                            "🇰🇷 한" to com.example.data.AppLanguage.Ko
                                        ).forEach { (label, lang) ->
                                            OutlinedButton(
                                                onClick = { if (ttsInitialized) viewModel.speakIn(data.reconstructedText, lang) },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.4f)),
                                                modifier = Modifier.weight(1f)
                                            ) { Text(label, fontSize = 12.sp, color = neonCyan) }
                                        }
                                    }
                                    }   // ← 「更多」展開區塊結束
                                }
                            }
                        }
                }
            }

            item {
                // 底部留白，確保最後一個卡片完整顯示在 NavigationBar 上方
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

private fun aacCategoryLabelRes(category: AacCategory): Int = when (category) {
    AacCategory.Products -> R.string.aac_cat_products
    AacCategory.FoodDrink -> R.string.aac_cat_food_drink
    AacCategory.Money -> R.string.aac_cat_money
    AacCategory.Actions -> R.string.aac_cat_actions
    AacCategory.Needs -> R.string.aac_cat_needs
    AacCategory.People -> R.string.aac_cat_people
    AacCategory.Places -> R.string.aac_cat_places
    AacCategory.Medical -> R.string.aac_cat_medical
    AacCategory.Time -> R.string.aac_cat_time
    AacCategory.Questions -> R.string.aac_cat_questions
    AacCategory.Emotions -> R.string.aac_cat_emotions
    AacCategory.Emergency -> R.string.aac_cat_emergency
}

private data class LocationPresetGroup(
    val icon: String,
    val title: String,
    val presets: List<AacContextSuggester.LocationPreset>
)

private val locationPresetGroups = listOf(
    LocationPresetGroup(
        icon = "🏥",
        title = "照護與日常",
        presets = listOf(
            AacContextSuggester.LocationPreset("🏠", "住家"),
            AacContextSuggester.LocationPreset("🏥", "醫院"),
            AacContextSuggester.LocationPreset("🩺", "診所"),
            AacContextSuggester.LocationPreset("🦷", "牙科"),
            AacContextSuggester.LocationPreset("♿", "復健科"),
            AacContextSuggester.LocationPreset("💊", "藥局"),
            AacContextSuggester.LocationPreset("🧓", "照護中心")
        )
    ),
    LocationPresetGroup(
        icon = "🛒",
        title = "餐飲與購物",
        presets = listOf(
            AacContextSuggester.LocationPreset("🏪", "便利商店"),
            AacContextSuggester.LocationPreset("🏬", "量販店"),
            AacContextSuggester.LocationPreset("🛒", "超市"),
            AacContextSuggester.LocationPreset("🍔", "速食店"),
            AacContextSuggester.LocationPreset("🍽", "餐廳"),
            AacContextSuggester.LocationPreset("🥐", "麵包店"),
            AacContextSuggester.LocationPreset("☕", "咖啡店"),
            AacContextSuggester.LocationPreset("🧋", "飲料店"),
            AacContextSuggester.LocationPreset("🏬", "百貨"),
            AacContextSuggester.LocationPreset("🛍", "購物中心"),
            AacContextSuggester.LocationPreset("👕", "服飾店"),
            AacContextSuggester.LocationPreset("🔧", "五金行"),
            AacContextSuggester.LocationPreset("💻", "電子產品店")
        )
    ),
    LocationPresetGroup(
        icon = "🚉",
        title = "交通與外出",
        presets = listOf(
            AacContextSuggester.LocationPreset("🚉", "車站"),
            AacContextSuggester.LocationPreset("🚌", "公車站"),
            AacContextSuggester.LocationPreset("🚇", "捷運站"),
            AacContextSuggester.LocationPreset("✈️", "機場"),
            AacContextSuggester.LocationPreset("🅿️", "停車場"),
            AacContextSuggester.LocationPreset("⛽", "加油站"),
            AacContextSuggester.LocationPreset("🏨", "旅館"),
            AacContextSuggester.LocationPreset("🚕", "計程車招呼站")
        )
    ),
    LocationPresetGroup(
        icon = "🏦",
        title = "公共服務",
        presets = listOf(
            AacContextSuggester.LocationPreset("🏦", "銀行"),
            AacContextSuggester.LocationPreset("🏧", "ATM"),
            AacContextSuggester.LocationPreset("📮", "郵局"),
            AacContextSuggester.LocationPreset("🏛", "公家機關"),
            AacContextSuggester.LocationPreset("🚓", "警察局"),
            AacContextSuggester.LocationPreset("🚒", "消防隊"),
            AacContextSuggester.LocationPreset("🏫", "學校"),
            AacContextSuggester.LocationPreset("📚", "圖書館"),
            AacContextSuggester.LocationPreset("🌳", "公園"),
            AacContextSuggester.LocationPreset("⛩", "廟宇"),
            AacContextSuggester.LocationPreset("⛪", "教堂")
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPresetDropdown(
    locationTag: String,
    onPick: (AacContextSuggester.LocationPreset) -> Unit,
    surfaceColor: Color,
    neonCyan: Color,
    auraAmber: Color,
    textLight: Color,
    textMuted: Color
) {
    val activeCategory = locationTag.substringBefore("·").trim()
    val selectedPreset = locationPresetGroups
        .asSequence()
        .flatMap { it.presets.asSequence() }
        .firstOrNull { it.label == activeCategory }
    val selectedGroupIndex = locationPresetGroups.indexOfFirst { group ->
        group.presets.any { it.label == selectedPreset?.label }
    }.coerceAtLeast(0)
    var groupIndex by remember(selectedGroupIndex) { mutableStateOf(selectedGroupIndex) }
    var groupExpanded by remember { mutableStateOf(false) }
    var presetExpanded by remember { mutableStateOf(false) }
    val group = locationPresetGroups[groupIndex.coerceIn(locationPresetGroups.indices)]

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = groupExpanded,
            onExpandedChange = { groupExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = "${group.icon} ${I18n.tr(group.title)}",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Category, null, tint = neonCyan, modifier = Modifier.size(18.dp)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupExpanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = auraAmber,
                    unfocusedBorderColor = textMuted.copy(alpha = 0.35f),
                    focusedTextColor = textLight,
                    unfocusedTextColor = textLight,
                    focusedContainerColor = surfaceColor,
                    unfocusedContainerColor = surfaceColor
                ),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = groupExpanded,
                onDismissRequest = { groupExpanded = false }
            ) {
                locationPresetGroups.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = { Text(I18n.tr(item.title), fontSize = 14.sp, fontWeight = FontWeight.Bold) },
                        leadingIcon = { Text(item.icon, fontSize = 18.sp) },
                        trailingIcon = {
                            if (index == groupIndex) Icon(Icons.Default.Check, null, tint = auraAmber, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            groupIndex = index
                            groupExpanded = false
                        }
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = presetExpanded,
            onExpandedChange = { presetExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedPreset?.takeIf { group.presets.any { p -> p.label == it.label } }
                    ?.let { "${it.emoji} ${I18n.tr(it.label)}" }
                    ?: I18n.tr("選擇%s情境").format(I18n.tr(group.title)),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Place, null, tint = neonCyan, modifier = Modifier.size(18.dp)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = auraAmber,
                    unfocusedBorderColor = textMuted.copy(alpha = 0.35f),
                    focusedTextColor = textLight,
                    unfocusedTextColor = textLight,
                    focusedContainerColor = surfaceColor,
                    unfocusedContainerColor = surfaceColor
                ),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = presetExpanded,
                onDismissRequest = { presetExpanded = false }
            ) {
                group.presets.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                I18n.tr(preset.label),
                                fontSize = 14.sp,
                                fontWeight = if (preset.label == activeCategory) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = { Text(preset.emoji, fontSize = 18.sp) },
                        trailingIcon = {
                            if (preset.label == activeCategory) {
                                Icon(Icons.Default.Check, null, tint = auraAmber, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = {
                            onPick(preset)
                            presetExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraLensChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    selectedColor: Color,
    textLight: Color,
    textMuted: Color
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                fontSize = 11.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        },
        leadingIcon = {
            Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(14.dp))
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = selectedColor.copy(alpha = 0.18f),
            selectedLabelColor = textLight,
            selectedLeadingIconColor = selectedColor,
            labelColor = textMuted,
            iconColor = textMuted
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = textMuted.copy(alpha = 0.22f),
            selectedBorderColor = selectedColor
        ),
        modifier = modifier.heightIn(min = 36.dp)
    )
}

@Composable
private fun CameraPreviewDialog(
    onDismiss: () -> Unit,
    isLandscape: Boolean,
    lens: CameraLens,
    onLensChange: (CameraLens) -> Unit,
    surfaceColor: Color,
    neonGreen: Color,
    textLight: Color,
    textMuted: Color
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var cameraProvider by remember { mutableStateOf<androidx.camera.lifecycle.ProcessCameraProvider?>(null) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.82f else 0.94f)
                .clip(RoundedCornerShape(18.dp))
                .background(surfaceColor)
                .border(1.dp, neonGreen.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Visibility, null, tint = neonGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(I18n.tr("攝影預覽"), color = textLight, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, null, tint = textMuted, modifier = Modifier.size(18.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CameraLensChip(
                    label = I18n.tr(CameraLens.Back.displayName),
                    selected = lens == CameraLens.Back,
                    onClick = { onLensChange(CameraLens.Back) },
                    modifier = Modifier.weight(1f),
                    selectedColor = neonGreen,
                    textLight = textLight,
                    textMuted = textMuted
                )
                CameraLensChip(
                    label = I18n.tr(CameraLens.Front.displayName),
                    selected = lens == CameraLens.Front,
                    onClick = { onLensChange(CameraLens.Front) },
                    modifier = Modifier.weight(1f),
                    selectedColor = neonGreen,
                    textLight = textLight,
                    textMuted = textMuted
                )
                CameraLensChip(
                    label = I18n.tr(CameraLens.Both.displayName),
                    selected = lens == CameraLens.Both,
                    onClick = { onLensChange(CameraLens.Both) },
                    modifier = Modifier.weight(1f),
                    selectedColor = neonGreen,
                    textLight = textLight,
                    textMuted = textMuted
                )
            }
            AndroidView(
                factory = { ctx ->
                    androidx.camera.view.PreviewView(ctx).apply {
                        scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                        implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isLandscape) 260.dp else 360.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) { previewView ->
                val providerFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val preview = androidx.camera.core.Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = androidx.camera.core.CameraSelector.Builder()
                        .requireLensFacing(lens.lensFacing ?: androidx.camera.core.CameraSelector.LENS_FACING_BACK)
                        .build()
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview
                        )
                    }
                }, androidx.core.content.ContextCompat.getMainExecutor(context))
            }
            Text(
                if (lens == CameraLens.Both) I18n.tr("前後一起拍會按一次連續擷取兩顆鏡頭；預覽先顯示後攝影機。") else I18n.tr("關閉視窗後會立即釋放相機。"),
                color = textMuted,
                fontSize = 11.sp
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
        }
    }
}

/**
 * 「+ 更多物品」下拉選單：完整的物品目錄（~150 件）依分類分區，每件都帶 emoji。
 *
 * 設計選擇：
 *  - 用 [AlertDialog]（而不是 ExposedDropdownMenu）：item 太多，dropdown 會超出畫面；
 *    Dialog 能 LazyColumn 包起來，UX 較穩定
 *  - 分類 header 用 sticky-ish padding，視覺上像 settings 分組
 *  - 多選即時生效：點一下立刻 toggle，不需要按確定；外面 chip 同步更新
 *  - 已選的項目顯示 ✓ + 高亮，無需再回主畫面確認
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisionCatalogDialog(
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val p = com.example.ui.theme.LocalAppPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(p.surface)
                .border(1.dp, p.neonGreen.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.darkBg.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, null, tint = p.neonGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        I18n.tr("選擇看到的物品"),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = p.textLight, modifier = Modifier.weight(1f)
                    )
                    Text(I18n.tr("已選 %d").format(selected.size), fontSize = 11.sp, color = p.neonGreen)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
                    }
                }
                // 分類 + 物品 LazyColumn
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    com.example.ai.AacContextSuggester.VISION_CATALOG.forEach { (category, items) ->
                        item(key = "h_$category") {
                            Text(
                                I18n.tr(category),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = p.neonGreen,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                        item(key = "g_$category") {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                items.forEach { v ->
                                    val isSel = selected.contains(v.label)
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) p.neonGreen.copy(alpha = 0.2f) else p.darkBg.copy(alpha = 0.4f))
                                            .border(
                                                1.dp,
                                                if (isSel) p.neonGreen else p.textMuted.copy(alpha = 0.25f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { onToggle(v.label) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(v.emoji, fontSize = 14.sp)
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                I18n.tr(v.label),
                                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                                color = if (isSel) p.textLight else p.textMuted
                                            )
                                            if (isSel) {
                                                Spacer(Modifier.width(4.dp))
                                                Icon(Icons.Default.Check, null, tint = p.neonGreen, modifier = Modifier.size(10.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
                // 底部「完成」
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.darkBg.copy(alpha = 0.4f))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(I18n.tr("完成"), color = p.neonGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
