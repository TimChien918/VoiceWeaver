package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GridView, null, tint = p.neonCyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.aac_title), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.textLight)
                    }
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
                            tint = p.auraAmber
                        )
                    }
                    if (state.sentence.isNotEmpty()) {
                        IconButton(onClick = { viewModel.aacClear() }) {
                            Icon(Icons.Default.DeleteOutline, null, tint = p.dangerRose)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.darkBg)
            )
        },
        containerColor = p.darkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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

            AacSearchAndCategoryBar(
                selectedCategory = state.selectedCategory,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onCategoryChange = { viewModel.aacSelectCategory(it) },
                p = p
            )

            // ── 主格子：類別 or 條目；可在 grid 與 flashcard 模式間切換 ──
            Box(modifier = Modifier.weight(1f)) {
                val customItems = remember(settings.customAacItems) { parseCustomAacItems(settings.customAacItems) }
                val filteredItems = remember(state.selectedCategory, searchQuery, customItems) {
                    filterAacItems(state.selectedCategory, searchQuery, customItems)
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(p.surface)
            .border(1.dp, p.neonCyan.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (items.isEmpty()) {
            Text(stringResource(R.string.aac_empty_hint), color = p.textMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items.forEach { it ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.neonCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(it.emoji, fontSize = 22.sp)
                        Text(it.label, fontSize = 11.sp, color = p.textLight, maxLines = 1)
                    }
                }
            }
            IconButton(onClick = onRemoveLast) {
                Icon(Icons.Default.Backspace, null, tint = p.dangerRose)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AacSearchAndCategoryBar(
    selectedCategory: AacCategory?,
    query: String,
    onQueryChange: (String) -> Unit,
    onCategoryChange: (AacCategory?) -> Unit,
    p: com.example.ui.theme.AppPalette
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            leadingIcon = { Icon(Icons.Default.Search, null, tint = p.neonCyan) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, null, tint = p.textMuted)
                    }
                }
            },
            placeholder = { Text(I18n.tr("搜尋圖卡"), fontSize = 12.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = p.neonCyan,
                unfocusedBorderColor = p.textMuted.copy(alpha = 0.35f),
                focusedTextColor = p.textLight,
                unfocusedTextColor = p.textLight,
                focusedContainerColor = p.surface,
                unfocusedContainerColor = p.surface
            ),
            modifier = Modifier.weight(1f)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.widthIn(min = 136.dp, max = 180.dp)
        ) {
            OutlinedTextField(
                value = selectedCategory?.let { "${it.emoji} ${stringResource(catLabelRes(it))}" } ?: I18n.tr("全部分類"),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.auraAmber,
                    unfocusedBorderColor = p.textMuted.copy(alpha = 0.35f),
                    focusedTextColor = p.textLight,
                    unfocusedTextColor = p.textLight,
                    focusedContainerColor = p.surface,
                    unfocusedContainerColor = p.surface
                ),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(I18n.tr("全部分類")) },
                    leadingIcon = { Icon(Icons.Default.Apps, null) },
                    onClick = {
                        onCategoryChange(null)
                        expanded = false
                    }
                )
                AacCategory.values().forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(stringResource(catLabelRes(cat))) },
                        leadingIcon = { Text(cat.emoji, fontSize = 18.sp) },
                        onClick = {
                            onCategoryChange(cat)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun filterAacItems(category: AacCategory?, query: String, extra: List<AacItem> = emptyList()): List<AacItem> {
    val normalizedQuery = query.trim()
    return (extra + DefaultAacItems)
        .asSequence()
        .filter { category == null || it.category == category }
        .filter { item ->
            normalizedQuery.isBlank() ||
                item.label.contains(normalizedQuery, ignoreCase = true) ||
                item.id.contains(normalizedQuery, ignoreCase = true) ||
                item.category.key.contains(normalizedQuery, ignoreCase = true)
        }
        .toList()
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
        columns = GridCells.Fixed(3),
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

@Composable
private fun ItemGrid(
    items: List<AacItem>,
    p: com.example.ui.theme.AppPalette,
    gazeIndex: Int,
    onPick: (AacItem) -> Unit,
    onCustomAmount: (() -> Unit)? = null
) {
    if (items.isEmpty() && onCustomAmount == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(I18n.tr("沒有符合的圖卡"), color = p.textMuted, fontSize = 14.sp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
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
            DwellableSurface(
                onTriggered = { onPick(item) },
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
                        item.label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = p.textLight, textAlign = TextAlign.Center, maxLines = 2
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
            label = item.label,
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
                        value = "${currency.code} (${currency.displayName})",
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
                                text = { Text("${c.symbol}  ${c.code}  ·  ${c.displayName}", fontSize = 13.sp) },
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
                "📍 ${hint.locationLabel}  ·  ${hint.intro}",
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
                    Text(item.label, color = p.textLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
