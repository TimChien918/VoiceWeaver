package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.KeyboardType
import com.example.R
import com.example.ai.EyeInteractionMode
import com.example.ai.VoiceWeaverViewModel
import com.example.data.AacCategory
import com.example.data.AacI18n
import com.example.data.AacItem
import com.example.data.Currency
import com.example.data.DefaultAacItems
import com.example.ui.aac.DwellableSurface
import com.example.ui.theme.LocalAppPalette

/**
 * AAC 溝通板（檳榔攤情境）。
 *
 * 流程：選類別 → 選條目（疊入句子建構器）→ ✔ 對了 朗讀 / ✖ 不對 重來。
 * 連續 3 次 ✖ → 觸發家屬通報對話（含 SMS Intent 入口）。
 * 所有按鈕走 [DwellableSurface]：觸控單擊立即觸發、按住有 dwell 進度環、眼動凝視自動 dwell。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AacScreen(viewModel: VoiceWeaverViewModel) {
    val state by viewModel.aacState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val eyeControl by viewModel.eyeControlState.collectAsState()
    val contextHint by viewModel.aacContextHint.collectAsState()
    val intentImagePath by viewModel.intentImagePath.collectAsState()
    val intentImageLoading by viewModel.intentImageLoading.collectAsState()
    val p = LocalAppPalette.current
    val ctx = LocalContext.current
    var showCustomAmount by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val gazeEnabled = settings.eyeControlEnabled &&
        eyeControl.running &&
        eyeControl.mode == EyeInteractionMode.GAZE_DWELL

    DisposableEffect(Unit) {
        viewModel.enterAacScreen()
        onDispose { viewModel.exitAacScreen() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    // 問題13：統一用「圖卡」而不是「AAC 溝通板」
                    Text(I18n.tr("圖卡"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.textLight)
                },
                navigationIcon = {
                    if (state.selectedCategory != null) {
                        IconButton(onClick = { viewModel.aacSelectCategory(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = p.neonCyan)
                        }
                    }
                },
                actions = {
                    // 切換 grid ↔ flashcard（識字卡）：對認知負擔重的患者，一次一張比 9 宮格好用
                    IconButton(onClick = { viewModel.aacToggleFlashcardMode() }) {
                        Icon(
                            imageVector = if (state.flashcardMode) Icons.Default.GridView else Icons.Default.ViewCarousel,
                            contentDescription = if (state.flashcardMode) I18n.tr("切換到九宮格") else I18n.tr("切換到識字卡"),
                            tint = Color(0xFF007AFF)   // iOS 藍：頂欄動作鈕統一色
                        )
                    }
                    if (state.sentence.isNotEmpty()) {
                        IconButton(onClick = { viewModel.aacClear() }) {
                            Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFFF3B30))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.darkBg)
            )
        },
        containerColor = p.darkBg
    ) { innerPadding ->
        // 版面隨視窗調整：橫向手機又寬又矮（約 2448×1080，換算 880×390dp），
        // 全部縱向堆疊的話標題＋組合區＋情境列就把高度吃光，圖卡一張都看不到。
        // 夠寬又矮 → 左右兩欄：左邊放輔助資訊，右邊整片留給圖卡。
        val cfgAac = LocalConfiguration.current
        val twoPane = cfgAac.screenWidthDp >= 600 && cfgAac.screenHeightDp < 480

        // 這幾個值 header（推薦列）與 grid（篩選結果）都要用，兩種版面共用同一份，
        // 所以提到版面分支之前算好，避免兩邊各算一次而不同步。

        val customItems = remember(settings.customAacItems) { parseCustomAacItems(settings.customAacItems) }

        // ── 常用（For You）：社群式動態牆——依「最近使用×時段×地點」排出患者
        //    此刻最可能要講的卡。冷啟動（沒按過足夠卡片）整列自動隱藏。
        //    key 帶 sentence.size：每按一張卡就重算，剛按的內容立即反映。
        // rankerEpoch：測試注入統計後讓下面兩個 remember 重算（release 版恆為 0，無作用）
        val rankerEpoch by com.example.testing.TestHooks.rankerEpoch.collectAsState()
        val feed = remember(state.sentence.size, customItems, rankerEpoch) {
            com.example.ai.AacUsageRanker.feed(customItems + DefaultAacItems)
        }
        // 冷啟動時整列隱藏：要有 3 張以上「衰減後仍算常用」的卡才推薦。
        // 門檻直接問 ranker（同一套衰減標準），不要在這裡逐張 count——
        // 全庫兩百多張，每次重組都掃一遍不划算，而且容易跟 feed 的判準走鐘。
        val activeCount = remember(state.sentence.size, customItems, rankerEpoch) {
            com.example.ai.AacUsageRanker.activeItemCount()
        }
        val feedVisible = feed.size >= 3 && activeCount >= 3 &&
            state.selectedCategory == null && searchQuery.isBlank()

        val headerContent: @Composable ColumnScope.() -> Unit = {
            // ── 句子預覽（總在最上 ── ──
            SentencePreview(items = state.sentence, p = p, onRemoveLast = { viewModel.aacRemoveLast() })

            // ── 情境推薦（依當前定位）：點一下就接到句子 ──
            contextHint?.let { hint ->
                ContextHintRow(
                    hint = hint,
                    onPick = { viewModel.aacAppendItem(it) },
                    p = p
                )
            }
            if (feedVisible) {
                FrequentFeedRow(items = feed, onPick = { viewModel.aacAppendItem(it) }, p = p)
            }

            AacSearchAndCategoryBar(
                selectedCategory = state.selectedCategory,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onCategoryChange = { viewModel.aacSelectCategory(it) },
                p = p
            )

        }

        val gridContent: @Composable () -> Unit = {
        // ── 主格子：類別 or 條目；可在 grid 與 flashcard 模式間切換 ──
            val filteredItems = remember(state.selectedCategory, searchQuery, customItems) {
                filterAacItems(state.selectedCategory, searchQuery, customItems)
            }

            // ── 自動化測試掛勾（release 版為 no-op，見 TestHooks）──
            // 搜尋字串是 composable 的區域狀態，ViewModel 看不到；由畫面自己回報，
            // dump 出來的才是「螢幕上真正顯示的東西」而不是從別處推論的。
            val injectedSearch by com.example.testing.TestHooks.injectedSearch.collectAsState()
            LaunchedEffect(injectedSearch) {
                injectedSearch?.let {
                    searchQuery = it
                    com.example.testing.TestHooks.consumeInjectedSearch()
                }
            }
            LaunchedEffect(searchQuery, state.selectedCategory, filteredItems.size,
                           feed.size, feedVisible, state.sentence.size, state.flashcardMode) {
                com.example.testing.TestHooks.reportAacUi(
                    com.example.testing.TestHooks.AacUiSnapshot(
                        searchQuery = searchQuery,
                        selectedCategory = state.selectedCategory?.name ?: "",
                        gridItemCount = filteredItems.size,
                        feedItemCount = feed.size,
                        feedVisible = feedVisible,
                        sentenceLength = state.sentence.size,
                        flashcardMode = state.flashcardMode
                    )
                )
            }
            val browsingItems = searchQuery.isNotBlank() || state.selectedCategory != null
            when {
                // 識字卡模式（一次一張）：類別/條目都支援
                state.flashcardMode && state.selectedCategory == null -> {
                    CategoryFlashcard(
                        p = p,
                        index = state.flashcardIndex,
                        onPrev = { viewModel.aacFlashcardPrev(AacCategory.values().size) },
                        onNext = { viewModel.aacFlashcardNext(AacCategory.values().size) },
                        onPick = { viewModel.aacSelectCategory(it) }
                    )
                }
                state.flashcardMode && state.selectedCategory != null -> {
                    val cat = state.selectedCategory!!
                    val items = filteredItems
                    // Money 類別額外有「自訂金額」卡：當作虛擬卡片附在最前面
                    val hasCustomMoney = cat == AacCategory.Money
                    val totalCards = items.size + if (hasCustomMoney) 1 else 0
                    ItemFlashcard(
                        p = p,
                        category = cat,
                        items = items,
                        hasCustomMoney = hasCustomMoney,
                        index = state.flashcardIndex.coerceIn(0, (totalCards - 1).coerceAtLeast(0)),
                        onPrev = { viewModel.aacFlashcardPrev(totalCards) },
                        onNext = { viewModel.aacFlashcardNext(totalCards) },
                        onPickItem = { viewModel.aacAppendItem(it) },
                        onPickCustomMoney = { showCustomAmount = true }
                    )
                }
                !browsingItems -> {
                    CategoryGrid(
                        p = p,
                        gazeIndex = if (gazeEnabled) gazeGridIndex(eyeControl.gazeX, eyeControl.gazeY, AacCategory.values().size) else -1,
                        onPick = { viewModel.aacSelectCategory(it) }
                    )
                }
                else -> {
                    val itemCount = filteredItems.size + if (state.selectedCategory == AacCategory.Money) 1 else 0
                    ItemGrid(
                        items = filteredItems,
                        p = p,
                        gazeIndex = if (gazeEnabled) gazeGridIndex(eyeControl.gazeX, eyeControl.gazeY, itemCount) else -1,
                        onPick = { viewModel.aacAppendItem(it) },
                        onCustomAmount = if (state.selectedCategory == AacCategory.Money) {
                            { showCustomAmount = true }
                        } else null
                    )
                }
            }
        }

        val statusContent: @Composable ColumnScope.() -> Unit = {

            // ── AI 重組中提示 ──
            if (state.composing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = p.neonCyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.aac_ai_composing), fontSize = 12.sp, color = p.neonCyan)
                }
            } else if (state.pendingConfirmText.isNotBlank()) {
                // ── 動態意圖確認卡：AI 句不直接發聲，✓ 才朗讀（醫師臨床建議）──
                AacPendingConfirmCard(
                    pendingText = state.pendingConfirmText,
                    items = state.sentence,
                    bigCard = settings.aacIntentConfirmEnabled,
                    onAccept = { viewModel.aacAcceptPending() },
                    onRegenerate = { viewModel.aacRegeneratePending() },
                    onDismiss = { viewModel.aacDismissPending() },
                    p = p,
                    intentImagePath = intentImagePath,
                    intentImageLoading = intentImageLoading
                )
            } else if (state.lastConfirmedText.isNotBlank() && state.sentence.isEmpty()) {
                // ── 上一句完成提示（可重播 + 1-5 星評分） ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.neonGreen.copy(alpha = 0.12f))
                        .border(1.dp, p.neonGreen.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = p.neonGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(state.lastConfirmedText, fontSize = 13.sp, color = p.textLight, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.aacSpeakLast() }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = p.neonGreen)
                        }
                    }
                    // 1–5 星評分（寫進 Room AacComboLog，給 AI 推薦下一次圖卡用）
                    if (state.lastLogId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(I18n.tr("這次有傳達到嗎？"), color = p.textMuted, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            (1..5).forEach { n ->
                                val filled = n <= state.lastRating
                                Icon(
                                    imageVector = if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = "rating $n",
                                    tint = if (filled) p.auraAmber else p.textMuted,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clickable { viewModel.aacRateLastConfirmation(n) }
                                )
                            }
                        }
                    }
                }
            }

            // ── 巨型 ✔ / ✖ ──
            ConfirmReject(
                p = p,
                enabled = state.sentence.isNotEmpty() && !state.composing && state.pendingConfirmText.isBlank(),
                rejectCount = state.rejectCount,
                gazeX = if (gazeEnabled) eyeControl.gazeX else -1f,
                gazeY = if (gazeEnabled) eyeControl.gazeY else -1f,
                onConfirm = { viewModel.aacConfirm() },
                onReject = { viewModel.aacReject() }
            )

            if (state.rejectCount in 1..2) {
                Text(
                    I18n.tr("已連續 %d 次選錯，再 %d 次將呼叫家屬").format(state.rejectCount, 3 - state.rejectCount),
                    fontSize = 11.sp, color = p.auraAmber, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (twoPane) {
            Row(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 左欄固定寬：夠放句子預覽與情境卡，又不會把圖卡擠掉。
                // 可捲動——矮螢幕下這欄的內容仍可能比高度長。
                Column(
                    modifier = Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    headerContent()
                    statusContent()
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { gridContent() }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                headerContent()
                Box(modifier = Modifier.weight(1f)) { gridContent() }
                statusContent()
            }
        }

        // ── 自訂金額對話 ──
        if (showCustomAmount) {
            CustomAmountDialog(
                defaultCurrency = settings.defaultCurrency,
                p = p,
                onDismiss = { showCustomAmount = false },
                onConfirm = { amt, cur ->
                    viewModel.aacAppendCustomAmount(amt, cur)
                    showCustomAmount = false
                }
            )
        }

        // ── 發聲風險閘門（醫療確認 / 高風險鎖定）──
        SpeakGateDialogs(viewModel = viewModel, p = p)

        // ── 家屬通報對話 ──
        if (state.alertTriggered) {
            AlertDialog(
                onDismissRequest = { viewModel.aacDismissAlert() },
                icon = { Icon(Icons.Default.Warning, null, tint = p.dangerRose) },
                title = { Text(stringResource(R.string.aac_alert_title), color = p.textLight) },
                text = { Text(stringResource(R.string.aac_alert_body), color = p.textMuted) },
                confirmButton = {
                    TextButton(onClick = {
                        // SMS Intent（無收件人，由使用者在簡訊 App 中選聯絡人）
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                                .putExtra("sms_body", I18n.tr("我目前需要協助，請聯絡我（VoiceWeaver 緊急通報）。")))
                        }
                        viewModel.aacDismissAlert()
                    }) { Text(stringResource(R.string.aac_alert_send_sms), color = p.dangerRose) }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.aacDismissAlert() }) {
                        Text(stringResource(R.string.aac_alert_cancel), color = p.textMuted)
                    }
                },
                containerColor = p.surface
            )
        }
    }
}

@Composable
private fun SentencePreview(items: List<AacItem>, p: com.example.ui.theme.AppPalette, onRemoveLast: () -> Unit) {
    // 問題15：限制預覽區最大高度為96dp，防止超過8張卡片時折行
    // 問題16：刪除時顯示撤銷提示
    var showUndoToast by remember { mutableStateOf(false) }
    var lastRemovedItem by remember { mutableStateOf<AacItem?>(null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(p.surface)
            .border(1.dp, p.neonCyan.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (items.isEmpty()) {
            Text(stringResource(R.string.aac_empty_hint), color = p.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                items.forEach { it ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.neonCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(it.emoji, fontSize = 20.sp)
                        Text(AacI18n.label(it), fontSize = 10.sp, color = p.textLight, maxLines = 1)
                    }
                }
            }
            IconButton(
                onClick = {
                    if (items.isNotEmpty()) {
                        lastRemovedItem = items.last()
                        showUndoToast = true
                        onRemoveLast()
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Backspace, null, tint = p.dangerRose, modifier = Modifier.size(20.dp))
            }
        }
    }

    // 問題16：2秒撤銷窗口
    if (showUndoToast && lastRemovedItem != null) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            showUndoToast = false
        }
        // 簡單的toast提示（在預覽區下方顯示）
        // 如果需要完整的撤銷功能，應在ViewModel維護undo棧
    }
}

@Composable
private fun AacSearchAndCategoryBar(
    selectedCategory: AacCategory?,
    query: String,
    onQueryChange: (String) -> Unit,
    onCategoryChange: (AacCategory?) -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    // iOS 風的頂部工具列：搜尋欄＋分類選單各佔一半、44dp 高、10dp 圓角。
    // 字級全部寫死——這一列是導覽工具不是內容，跟著全域放大倍率跑的話
    // 「全部分類」會撐成半個螢幕高的巨型方塊（實際發生過）。
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 搜尋欄：BasicTextField 才能真正控制高度（OutlinedTextField 最矮 56dp）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(p.surface)
                .padding(horizontal = 10.dp)
        ) {
            Icon(Icons.Default.Search, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (query.isBlank()) {
                    Text(I18n.tr("搜尋圖卡"), fontSize = 15.sp, color = p.textMuted.copy(alpha = 0.7f), maxLines = 1)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = p.textLight),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF007AFF)),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotBlank()) {
                Icon(
                    Icons.Default.Close, I18n.tr("清除"), tint = p.textMuted,
                    modifier = Modifier.size(18.dp).clickable { onQueryChange("") }
                )
            }
        }

        // 分類選單：白卡按鈕 + 標準 DropdownMenu（44dp 列高、emoji 前綴、目前項打勾）
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.surface)
                    .clickable {
                        expanded = true
                        if (query.isNotBlank()) onQueryChange("")  // 問題5：選分類時自動清除搜尋詞
                    }
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    selectedCategory?.let { "${it.emoji} ${stringResource(catLabelRes(it))}" }
                        ?: I18n.tr("全部分類"),
                    fontSize = 15.sp, color = p.textLight, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp)
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ExpandMore, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.45f)  // 防止菜單超出屏幕邊界，添加半透明背景
            ) {
                DropdownMenuItem(
                    text = { Text(I18n.tr("全部分類"), fontSize = 15.sp, color = p.textLight) },
                    trailingIcon = {
                        if (selectedCategory == null)
                            Icon(Icons.Default.Check, null, tint = Color(0xFF007AFF), modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        onCategoryChange(null)
                        expanded = false
                        // 問題6：返回全部分類時自動清除搜尋詞
                        if (query.isNotBlank()) onQueryChange("")
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
                AacCategory.values().forEach { cat ->
                    DropdownMenuItem(
                        text = {
                            Text("${cat.emoji}  ${stringResource(catLabelRes(cat))}",
                                fontSize = 15.sp, color = p.textLight)
                        },
                        trailingIcon = {
                            if (selectedCategory == cat)
                                Icon(Icons.Default.Check, null, tint = Color(0xFF007AFF), modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onCategoryChange(cat)
                            expanded = false
                        },
                        modifier = Modifier.heightIn(min = 44.dp)
                    )
                }
            }
        }
    }
}

private fun filterAacItems(category: AacCategory?, query: String, extra: List<AacItem> = emptyList()): List<AacItem> {
    val normalizedQuery = query.trim()
    val filtered = (extra + DefaultAacItems)
        .asSequence()
        .filter { category == null || it.category == category }
        .filter { item ->
            normalizedQuery.isBlank() ||
                item.label.contains(normalizedQuery, ignoreCase = true) ||
                AacI18n.label(item).contains(normalizedQuery, ignoreCase = true) ||
                item.id.contains(normalizedQuery, ignoreCase = true) ||
                item.category.key.contains(normalizedQuery, ignoreCase = true)
        }
        .toList()
    // 類別內：常用的排前面（穩定排序，沒按過的維持原本相對位置——版面不大風吹）。
    // 有搜尋字時不重排，搜尋結果照原順序比較好對照。
    return if (normalizedQuery.isBlank()) com.example.ai.AacUsageRanker.rankWithin(filtered) else filtered
}

/** 解析使用者自訂 AAC 圖卡（JSON 陣列 [{label,emoji,category?}]）。category 預設 Needs。 */
fun parseCustomAacItems(json: String): List<AacItem> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = o.optString("label").trim()
            if (label.isBlank()) return@mapNotNull null
            val cat = runCatching { AacCategory.valueOf(o.optString("category", "Needs")) }.getOrDefault(AacCategory.Needs)
            AacItem(id = "custom_$i", label = label, emoji = o.optString("emoji", "⭐"), category = cat)
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun CategoryGrid(
    p: com.example.ui.theme.AppPalette,
    gazeIndex: Int,
    onPick: (AacCategory) -> Unit
) {
    val groups = remember {
        listOf(
            I18n.tr("基礎溝通") to listOf(AacCategory.Actions, AacCategory.Needs, AacCategory.Questions, AacCategory.Emotions, AacCategory.Emergency),
            I18n.tr("日常情境") to listOf(AacCategory.FoodDrink, AacCategory.Products, AacCategory.Money),
            I18n.tr("照護資訊") to listOf(AacCategory.People, AacCategory.Places, AacCategory.Medical, AacCategory.Time)
        )
    }
    val orderedCategories = remember(groups) { groups.flatMap { it.second } }
    LazyVerticalGrid(
        // 自適應欄數：手機直向仍是 3 欄，平板與橫向自動變多欄，不再把卡片拉長變形
        columns = GridCells.Adaptive(minSize = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        groups.forEach { (groupTitle, categories) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    groupTitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.neonCyan,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
            }
            items(categories) { cat ->
                val visualIndex = orderedCategories.indexOf(cat)
                DwellableSurface(
                    onTriggered = { onPick(cat) },
                    palette = p,
                    modifier = Modifier.aspectRatio(1f),
                    gazeWithin = visualIndex == gazeIndex
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(p.surface)
                            .padding(8.dp)
                    ) {
                        Text(cat.emoji, fontSize = 40.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(catLabelRes(cat)),
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = p.textLight, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)   // combinedClickable：圖卡長按選單要用
@Composable
private fun ItemGrid(
    items: List<AacItem>,
    p: com.example.ui.theme.AppPalette,
    gazeIndex: Int,
    onPick: (AacItem) -> Unit,
    onCustomAmount: (() -> Unit)? = null
) {
    // 問題18：搜尋無結果時顯示提示
    if (items.isEmpty() && onCustomAmount == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Search, null, tint = p.textMuted, modifier = Modifier.size(48.dp))
                Text(I18n.tr("沒有符合的圖卡"), color = p.textMuted, fontSize = 14.sp)
                Text(I18n.tr("嘗試調整搜尋條件或選擇其他分類"), color = p.textMuted, fontSize = 12.sp)
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 金額類別內插「自訂金額」卡
        if (onCustomAmount != null) {
            item {
                DwellableSurface(
                    onTriggered = onCustomAmount,
                    palette = p,
                    modifier = Modifier.aspectRatio(1f),
                    gazeWithin = gazeIndex == 0
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(p.surface)
                            .border(1.5.dp, p.auraAmber.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                            .padding(8.dp)
                    ) {
                        Text("✏️", fontSize = 36.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.aac_custom_amount),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = p.auraAmber, textAlign = TextAlign.Center, maxLines = 2
                        )
                    }
                }
            }
        }
        items(items.withIndex().toList()) { indexed ->
            val item = indexed.value
            val visualIndex = indexed.index + if (onCustomAmount != null) 1 else 0
            val tint = if (item.isStrongIntent) p.dangerRose else p.neonCyan
            var showMenu by remember { mutableStateOf(false) }  // 問題11：長按菜單狀態
            DwellableSurface(
                onTriggered = { onPick(item) },
                palette = p,
                modifier = Modifier
                    .aspectRatio(1f)
                    .combinedClickable(
                        onClick = { onPick(item) },
                        onLongClick = { showMenu = true }
                    ),
                // 觸控交給上面的 combinedClickable（它才聽得到長按）。
                // 若這裡仍是 true，DwellableSurface 內建的單擊判定會跟 onClick 各觸發一次，
                // 點一下圖卡會被加進句子兩張。onTriggered 保留給眼動凝視用。
                enableTouchTap = false,
                gazeWithin = visualIndex == gazeIndex
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(p.surface)
                        .border(
                            if (item.isStrongIntent) 1.5.dp else 0.dp,
                            if (item.isStrongIntent) tint.copy(alpha = 0.5f) else Color.Transparent,
                            RoundedCornerShape(18.dp)
                        )
                        .padding(8.dp)
                ) {
                    Text(item.emoji, fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        AacI18n.label(item), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = p.textLight, textAlign = TextAlign.Center, maxLines = 2
                    )
                }
                // 問題11：長按菜單
                if (showMenu) {
                    AlertDialog(
                        onDismissRequest = { showMenu = false },
                        title = { Text(AacI18n.label(item)) },
                        text = { Text(I18n.tr("選擇操作")) },
                        confirmButton = {
                            TextButton(onClick = {
                                onPick(item)
                                showMenu = false
                            }) { Text(I18n.tr("加入句子")) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showMenu = false }) { Text(I18n.tr("取消")) }
                        },
                        containerColor = p.surface
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmReject(
    p: com.example.ui.theme.AppPalette,
    enabled: Boolean,
    rejectCount: Int,
    gazeX: Float,
    gazeY: Float,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val gazeOnButtons = enabled && gazeY > 0.72f
    val gazeOnReject = gazeOnButtons && gazeX < 0.5f
    val gazeOnConfirm = gazeOnButtons && gazeX >= 0.5f
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),   // 拉大間距，避免眼動誤判
        modifier = Modifier.fillMaxWidth().height(90.dp)
    ) {
        DwellableSurface(
            onTriggered = { if (enabled) onReject() },
            palette = p,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            enableTouchTap = enabled,
            gazeWithin = gazeOnReject,
            cornerRadius = 22
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(if (enabled) p.dangerRose else p.dangerRose.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.aac_reject), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        DwellableSurface(
            onTriggered = { if (enabled) onConfirm() },
            palette = p,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            enableTouchTap = enabled,
            gazeWithin = gazeOnConfirm,
            cornerRadius = 22
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(if (enabled) p.neonGreen else p.neonGreen.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.aac_confirm), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

/**
 * 識字卡（flashcard）模式 — 一次顯示一張大圖卡 + 三大按鈕。
 *
 * 為什麼做這個：3x3 grid 對部分失語症 / 阿茲海默 / 認知負擔較重的患者選項太多。
 * 一張一張看 + ←/✓/→ 三大按鈕，命中率 / 速度都比掃 9 張高。
 *
 * 共用結構：FlashcardChrome 畫卡面 + 三大按鈕 + 進度 (x / N)。
 * 上層只負責決定「現在這張要畫什麼」（類別 or 條目）以及點 ✓ 時要做什麼。
 */
@Composable
private fun FlashcardChrome(
    p: com.example.ui.theme.AppPalette,
    emoji: String,
    label: String,
    sublabel: String?,
    progressText: String,
    accent: Color,
    onPrev: () -> Unit,
    onConfirm: () -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── 卡面（佔大部分高度）──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(p.surface)
                .border(2.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(emoji, fontSize = 120.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    label,
                    fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    color = p.textLight, textAlign = TextAlign.Center,
                    maxLines = 2
                )
                if (!sublabel.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(sublabel, fontSize = 14.sp, color = p.textMuted, textAlign = TextAlign.Center)
                }
            }
            Text(
                progressText,
                fontSize = 13.sp, color = p.textMuted, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.darkBg.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        // ── 三大按鈕 ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(72.dp)
        ) {
            FlashcardActionButton(
                p = p,
                bg = p.surface,
                border = p.textMuted.copy(alpha = 0.4f),
                onClick = onPrev,
                content = {
                    Icon(Icons.Default.ChevronLeft, null, tint = p.textLight, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.tr("上一張"), color = p.textLight, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                },
                modifier = Modifier.weight(1f)
            )
            FlashcardActionButton(
                p = p,
                bg = p.neonGreen,
                border = p.neonGreen,
                onClick = onConfirm,
                content = {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.tr("是這個"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                },
                modifier = Modifier.weight(1.3f)
            )
            FlashcardActionButton(
                p = p,
                bg = p.surface,
                border = p.textMuted.copy(alpha = 0.4f),
                onClick = onNext,
                content = {
                    Text(I18n.tr("下一張"), color = p.textLight, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = p.textLight, modifier = Modifier.size(28.dp))
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FlashcardActionButton(
    p: com.example.ui.theme.AppPalette,
    bg: Color,
    border: Color,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** 類別識字卡：產品 / 金額 / 動作 / 情緒 / 緊急 — 一次顯示一張，按 ✓ 進入該類別 */
@Composable
private fun CategoryFlashcard(
    p: com.example.ui.theme.AppPalette,
    index: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: (AacCategory) -> Unit
) {
    val cats = remember { AacCategory.values().toList() }
    val safeIndex = index.coerceIn(0, cats.size - 1)
    val cat = cats[safeIndex]
    FlashcardChrome(
        p = p,
        emoji = cat.emoji,
        label = stringResource(catLabelRes(cat)),
        sublabel = I18n.tr("類別 %d / %d").format(safeIndex + 1, cats.size),
        progressText = "${safeIndex + 1}/${cats.size}",
        accent = p.neonCyan,
        onPrev = onPrev,
        onConfirm = { onPick(cat) },
        onNext = onNext
    )
}

/** 條目識字卡：在某個類別下，一次顯示一個條目；按 ✓ 加進句子。 */
@Composable
private fun ItemFlashcard(
    p: com.example.ui.theme.AppPalette,
    category: AacCategory,
    items: List<AacItem>,
    hasCustomMoney: Boolean,
    index: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickItem: (AacItem) -> Unit,
    onPickCustomMoney: () -> Unit
) {
    val totalCards = items.size + if (hasCustomMoney) 1 else 0
    if (totalCards == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(I18n.tr("這個類別還沒有圖卡"), color = p.textMuted, fontSize = 14.sp)
        }
        return
    }
    val safeIndex = index.coerceIn(0, totalCards - 1)

    // Money 類別第 0 張是「自訂金額」虛擬卡
    if (hasCustomMoney && safeIndex == 0) {
        FlashcardChrome(
            p = p,
            emoji = "✏️",
            label = I18n.tr("自訂金額"),
            sublabel = I18n.tr("按 ✓ 開啟數字鍵盤輸入"),
            progressText = "1/$totalCards",
            accent = p.auraAmber,
            onPrev = onPrev,
            onConfirm = onPickCustomMoney,
            onNext = onNext
        )
    } else {
        val itemIndex = if (hasCustomMoney) safeIndex - 1 else safeIndex
        val item = items[itemIndex]
        val accent = if (item.isStrongIntent) p.dangerRose else p.neonCyan
        FlashcardChrome(
            p = p,
            emoji = item.emoji,
            label = AacI18n.label(item),
            sublabel = if (item.isStrongIntent) I18n.tr("緊急 — 按 ✓ 會立即播報") else null,
            progressText = "${safeIndex + 1}/$totalCards",
            accent = accent,
            onPrev = onPrev,
            onConfirm = { onPickItem(item) },
            onNext = onNext
        )
    }
}

/**
 * **AAC 內嵌面板** — 把整套 AAC 圖卡（類別 + 條目 + 識字卡 + 情境推薦 + 自訂金額）
 * 包成可嵌進「重組」主畫面的單一 Composable。
 *
 * 設計差異於獨立 [AacScreen]：
 *  - **不顯示 句子預覽**：在重組主畫面，輸入欄就是 rawText（圖卡 append 到那裡），沒必要再畫一個
 *  - **不顯示 ✔/✖ 按鈕**：用主畫面的「邊緣端情境重構」按鈕當送出；reject/家屬通報邏輯不在此處
 *  - **不顯示 確認後評分**：留在 AacScreen 完整模式裡
 *  - 保留：類別/條目格、識字卡切換、情境推薦列、自訂金額對話、grid ↔ flashcard 切換按鈕
 *
 * 為什麼這樣切：失語症患者在重組主畫面就應該能「拿圖卡組碎詞→按重組→聽朗讀」一氣呵成，
 * 不該為了挑圖卡跳分頁。底部分頁的 AAC 入口維持給「沉浸式練習 + 評分」用即可。
 */
@Composable
fun AacInlinePanel(
    viewModel: VoiceWeaverViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.aacState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val contextHint by viewModel.aacContextHint.collectAsState()
    val p = LocalAppPalette.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showCustomAmount by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── 標題列：圖卡輔助 + grid/flashcard 切換 + 返回類別 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.selectedCategory != null) {
                IconButton(
                    onClick = { viewModel.aacSelectCategory(null) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, I18n.tr("回類別"), tint = p.neonCyan, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            Icon(Icons.Default.GridView, null, tint = p.neonCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                I18n.tr("圖卡（點一下加進句子）"),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.textLight,
                modifier = Modifier.weight(1f)
            )
            // 模式切換：grid ↔ flashcard
            IconButton(
                onClick = { viewModel.aacToggleFlashcardMode() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (state.flashcardMode) Icons.Default.GridView else Icons.Default.ViewCarousel,
                    contentDescription = if (state.flashcardMode) I18n.tr("切換到九宮格") else I18n.tr("切換到識字卡"),
                    tint = p.auraAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ── 情境推薦（依當前定位） ──
        contextHint?.let { hint ->
            ContextHintRow(
                hint = hint,
                onPick = { viewModel.aacAppendItem(it) },
                p = p
            )
        }

        AacSearchAndCategoryBar(
            selectedCategory = state.selectedCategory,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onCategoryChange = { viewModel.aacSelectCategory(it) },
            p = p
        )

        // ── 主格子：類別 or 條目；grid 與 flashcard 可切換 ──
        // 在主畫面 LazyColumn 內保留足夠高度，讓分組目錄與圖卡不會擠成一團。
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(
                when {
                    state.flashcardMode && isLandscape -> 300.dp
                    state.flashcardMode -> 380.dp
                    isLandscape -> 300.dp
                    else -> 420.dp
                }
            )
        ) {
            val customItems = remember(settings.customAacItems) { parseCustomAacItems(settings.customAacItems) }
            val filteredItems = remember(state.selectedCategory, searchQuery, customItems) {
                filterAacItems(state.selectedCategory, searchQuery, customItems)
            }
            val browsingItems = searchQuery.isNotBlank() || state.selectedCategory != null
            when {
                state.flashcardMode && state.selectedCategory == null -> {
                    CategoryFlashcard(
                        p = p,
                        index = state.flashcardIndex,
                        onPrev = { viewModel.aacFlashcardPrev(AacCategory.values().size) },
                        onNext = { viewModel.aacFlashcardNext(AacCategory.values().size) },
                        onPick = { viewModel.aacSelectCategory(it) }
                    )
                }
                state.flashcardMode && state.selectedCategory != null -> {
                    val cat = state.selectedCategory!!
                    val items = filteredItems
                    val hasCustomMoney = cat == AacCategory.Money
                    val totalCards = items.size + if (hasCustomMoney) 1 else 0
                    ItemFlashcard(
                        p = p,
                        category = cat,
                        items = items,
                        hasCustomMoney = hasCustomMoney,
                        index = state.flashcardIndex.coerceIn(0, (totalCards - 1).coerceAtLeast(0)),
                        onPrev = { viewModel.aacFlashcardPrev(totalCards) },
                        onNext = { viewModel.aacFlashcardNext(totalCards) },
                        onPickItem = { viewModel.aacAppendItem(it) },
                        onPickCustomMoney = { showCustomAmount = true }
                    )
                }
                !browsingItems -> {
                    CategoryGrid(p = p, gazeIndex = -1, onPick = { viewModel.aacSelectCategory(it) })
                }
                else -> {
                    ItemGrid(
                        items = filteredItems,
                        p = p,
                        gazeIndex = -1,
                        onPick = { viewModel.aacAppendItem(it) },
                        onCustomAmount = if (state.selectedCategory == AacCategory.Money) {
                            { showCustomAmount = true }
                        } else null
                    )
                }
            }
        }
    }

    if (showCustomAmount) {
        CustomAmountDialog(
            defaultCurrency = settings.defaultCurrency,
            p = p,
            onDismiss = { showCustomAmount = false },
            onConfirm = { amt, cur ->
                viewModel.aacAppendCustomAmount(amt, cur)
                showCustomAmount = false
            }
        )
    }
}

private fun gazeGridIndex(gazeX: Float, gazeY: Float, count: Int, columns: Int = 3): Int {
    if (count <= 0) return -1
    val rows = ((count + columns - 1) / columns).coerceAtLeast(1)
    val col = (gazeX.coerceIn(0f, 0.999f) * columns).toInt().coerceIn(0, columns - 1)
    val row = (gazeY.coerceIn(0f, 0.999f) * rows).toInt().coerceIn(0, rows - 1)
    return (row * columns + col).coerceIn(0, count - 1)
}

private fun catLabelRes(c: AacCategory) = when (c) {
    AacCategory.Products  -> R.string.aac_cat_products
    AacCategory.FoodDrink -> R.string.aac_cat_food_drink
    AacCategory.Money     -> R.string.aac_cat_money
    AacCategory.Actions   -> R.string.aac_cat_actions
    AacCategory.Needs     -> R.string.aac_cat_needs
    AacCategory.People    -> R.string.aac_cat_people
    AacCategory.Places    -> R.string.aac_cat_places
    AacCategory.Medical   -> R.string.aac_cat_medical
    AacCategory.Time      -> R.string.aac_cat_time
    AacCategory.Questions -> R.string.aac_cat_questions
    AacCategory.Emotions  -> R.string.aac_cat_emotions
    AacCategory.Emergency -> R.string.aac_cat_emergency
}

/** 自訂金額輸入對話：數字 + 幣別 picker */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomAmountDialog(
    defaultCurrency: Currency,
    p: com.example.ui.theme.AppPalette,
    onDismiss: () -> Unit,
    onConfirm: (String, Currency) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(defaultCurrency) }
    var menuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Edit, null, tint = p.auraAmber) },
        title = { Text(stringResource(R.string.aac_custom_amount_title), color = p.textLight) },
        text = {
            Column {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' }.take(8) },
                    label = { Text(stringResource(R.string.aac_amount_label), fontSize = 11.sp) },
                    leadingIcon = { Text(currency.symbol, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.auraAmber) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = p.auraAmber,
                        unfocusedBorderColor = p.textMuted.copy(alpha = 0.4f),
                        focusedTextColor = p.textLight,
                        unfocusedTextColor = p.textLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.aac_currency_label), fontSize = 11.sp, color = p.textMuted)
                Spacer(Modifier.height(4.dp))
                ExposedDropdownMenuBox(
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it }
                ) {
                    OutlinedTextField(
                        value = "${currency.code} (${I18n.tr(currency.displayName)})",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.auraAmber,
                            unfocusedBorderColor = p.textMuted.copy(alpha = 0.4f),
                            focusedTextColor = p.textLight,
                            unfocusedTextColor = p.textLight
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        Currency.values().forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.symbol}  ${c.code}  ·  ${I18n.tr(c.displayName)}", fontSize = 13.sp) },
                                onClick = { currency = c; menuOpen = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (amount.isNotBlank()) onConfirm(amount, currency) },
                enabled = amount.isNotBlank()
            ) { Text(stringResource(R.string.aac_amount_add), color = p.auraAmber, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.aac_alert_cancel), color = p.textMuted) }
        },
        containerColor = p.surface
    )
}

/**
 * 情境推薦列：在「便利商店·全家」就推「結冰水/香菸/多少錢」，
 * 在「醫院」就推「不舒服/找家人」。點一下圖卡直接加進句子。
 */
@Composable
private fun FrequentFeedRow(
    items: List<AacItem>,
    onPick: (AacItem) -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.neonCyan.copy(alpha = 0.08f))
            .border(1.dp, p.neonCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, null, tint = p.neonCyan, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(I18n.tr("常用"), color = p.textLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            items.forEach { item ->
                var isPressed by remember { mutableStateOf(false) }
                // 問題3：固定卡片寬度（min 80dp），防止文字長短導致參差不齊
                Box(
                    modifier = Modifier
                        .widthIn(min = 80.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.surface)
                        .border(1.dp, p.neonCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .clickable {
                            isPressed = true
                            onPick(item)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        // 問題7、17：添加按下效果 + 動畫反饋
                        .graphicsLayer(scaleX = if (isPressed) 0.95f else 1f, scaleY = if (isPressed) 0.95f else 1f)
                        .animateContentSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(item.emoji, fontSize = 16.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(AacI18n.label(item), color = p.textLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                LaunchedEffect(isPressed) {
                    if (isPressed) {
                        kotlinx.coroutines.delay(200)
                        isPressed = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextHintRow(
    hint: com.example.ai.AacContextSuggester.ContextHint,
    onPick: (com.example.data.AacItem) -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.auraAmber.copy(alpha = 0.10f))
            .border(1.dp, p.auraAmber.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOn, null, tint = p.auraAmber, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "📍 ${I18n.tr(hint.locationLabel)}  ·  ${I18n.tr(hint.intro)}",
                color = p.textLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        // 圖卡數少（≤ 6 筆），用普通 Row + horizontalScroll 即可，避免 LazyRow `items` 與 grid 的 items 同名衝突。
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            hint.suggestedItems.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.surface)
                        .border(1.dp, p.auraAmber.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .clickable { onPick(item) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(item.emoji, fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(AacI18n.label(item), color = p.textLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
