package com.speakmylife.app.ui.report

import com.speakmylife.app.ui.I18n

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speakmylife.app.ai.ReportAggregator
import com.speakmylife.app.ai.ReportRange
import com.speakmylife.app.ai.ReportSnapshot
import com.speakmylife.app.ai.reactionTrendBuckets
import com.speakmylife.app.ai.selectionHitTrendBuckets
import com.speakmylife.app.data.SettingsRepository
import com.speakmylife.app.net.TelegramClient
import com.speakmylife.app.ui.theme.LocalAppPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 成績單儀表板。三段式時間維度（今日 / 本月 / 本年度），
 * 上下兩張圖（折線：進步趨勢；長條：正向情緒字眼），
 * 底部「📤 發送到 Telegram」一鍵把純文字報表送到使用者在設定頁的 Chat ID。
 *
 * 設計選擇：
 *  - **Compose Canvas 而非 MPAndroidChart**：避免引入 View interop（理由見 ReportCharts.kt 註解）
 *  - **不在這支畫面截圖 PNG**：原本 spec 說「支援截圖匯出」，但 Telegram Bot API
 *    純 `sendMessage` 已足以傳達數據；要送 PNG 需 multipart/form-data，留給未來擴充。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    aggregator: ReportAggregator,
    settingsRepo: SettingsRepository,
    onExportCsv: (suspend () -> android.content.Intent)? = null,
    onExportPdf: (suspend () -> android.content.Intent)? = null,
    modifier: Modifier = Modifier
) {
    val p = LocalAppPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(ReportRange.Today) }
    var snapshot by remember { mutableStateOf<ReportSnapshot?>(null) }
    var sending by remember { mutableStateOf(false) }
    var lastSendResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(range) {
        snapshot = null
        snapshot = aggregator.build(range)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        I18n.tr("▌ 成績單 · 進步報表"),
                        color = p.textLight, fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.darkBg)
            )
        },
        containerColor = p.darkBg
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // === Segmented Buttons：今日 / 本月 / 本年度 ===
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReportRange.values().forEachIndexed { idx, r ->
                        SegmentedButton(
                            selected = range == r,
                            onClick = { range = r },
                            shape = SegmentedButtonDefaults.itemShape(idx, ReportRange.values().size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = p.neonCyan.copy(alpha = 0.25f),
                                activeContentColor = p.textLight,
                                inactiveContainerColor = p.surface,
                                inactiveContentColor = p.textMuted
                            )
                        ) { Text(I18n.tr(r.displayName)) }
                    }
                }
            }

            // === 匯出成績報告（CSV 給 Excel / PDF 給列印存檔）===
            if (onExportCsv != null || onExportPdf != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (onExportCsv != null) {
                            OutlinedButton(
                                onClick = { scope.launch { runCatching { ctx.startActivity(android.content.Intent.createChooser(onExportCsv(), I18n.tr("匯出 CSV"))) } } },
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.5f)),
                                modifier = Modifier.weight(1f)
                            ) { Text(I18n.tr("📄 匯出 CSV"), fontSize = 12.sp, color = p.neonCyan) }
                        }
                        if (onExportPdf != null) {
                            OutlinedButton(
                                onClick = { scope.launch { runCatching { ctx.startActivity(android.content.Intent.createChooser(onExportPdf(), I18n.tr("匯出 PDF"))) } } },
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.5f)),
                                modifier = Modifier.weight(1f)
                            ) { Text(I18n.tr("🖨 匯出 PDF"), fontSize = 12.sp, color = p.neonCyan) }
                        }
                    }
                }
            }

            // === 行為數據（醫師臨床建議：康復追蹤指標，全期累計）===
            item {
                val bm by settingsRepo.behaviorMetrics.collectAsState(
                    initial = com.speakmylife.app.data.BehaviorMetrics()
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.surface)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(I18n.tr("🧭 行為數據（治療師評估用，全期累計）"),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.textLight)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryTile(
                            I18n.tr("⏱ 反應"),
                            if (bm.firstSpeakCount > 0) "%.1f".format(bm.avgFirstSpeakMs / 1000.0) else "—",
                            I18n.tr("秒"), Modifier.weight(1f)
                        )
                        SummaryTile(
                            I18n.tr("🎯 選句"),
                            if (bm.candidateTotal > 0) "${(bm.firstHitRate * 100).toInt()}" else "—",
                            "%", Modifier.weight(1f)
                        )
                        SummaryTile(I18n.tr("✏️ 修改"), "${bm.undoCount}", I18n.tr("次"), Modifier.weight(1f))
                        SummaryTile(
                            I18n.tr("🖼 圖卡"),
                            if (bm.aacInputCount + bm.textInputCount > 0) "${(bm.aacRatio * 100).toInt()}" else "—",
                            "%", Modifier.weight(1f)
                        )
                    }
                    Text(
                        I18n.tr("反應 = 開 App 到第一次發聲的平均耗時 · 選句 = AI 第一候選被採用率 · ") +
                        I18n.tr("修改 = 退回/刪卡累計 · 圖卡 = 圖卡輸入佔總輸入比例"),
                        fontSize = 9.sp, color = p.textMuted, lineHeight = 13.sp
                    )
                }
            }

            // === 復健軌跡趨勢圖（逐日，最近 30 天）===
            // 上面的行為數據是全期平均，看不出「有沒有在進步」；這裡把同樣兩個指標
            // 按日畫成折線，才是治療師追蹤復健表現要看的東西。跟上方時間維度無關：
            // 「今日」只有一個點，畫不出趨勢。
            item {
                val trend by settingsRepo.dailyTrend.collectAsState(
                    initial = emptyList<com.speakmylife.app.data.DailyTrendDay>()
                )
                val reaction = remember(trend) { reactionTrendBuckets(trend) }
                val hit = remember(trend) { selectionHitTrendBuckets(trend) }
                // 反應時間沒有固定上限，用最大值留兩成空間，線才不會貼著頂
                val reactionMax = remember(reaction) {
                    ((reaction.maxOfOrNull { it.value } ?: 0f) * 1.2f).coerceAtLeast(1f)
                }
                val secUnit = I18n.tr("秒")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(I18n.tr("📉 復健軌跡趨勢圖（逐日，最近 30 天）"),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.textLight)
                    LineChartCompose(
                        data = reaction,
                        palette = p,
                        modifier = Modifier.fillMaxWidth(),
                        yMax = reactionMax,
                        title = I18n.tr("⏱ 反應時間（秒，越低越好）") +
                            (reaction.lastOrNull()?.let { b ->
                                " · " + I18n.tr("最近：%s").format("%.1f".format(b.value) + " " + secUnit)
                            } ?: "")
                    )
                    LineChartCompose(
                        data = hit,
                        palette = p,
                        modifier = Modifier.fillMaxWidth(),
                        yMax = 100f,
                        title = I18n.tr("🎯 選句命中率（%）") +
                            (hit.lastOrNull()?.let { b ->
                                " · " + I18n.tr("最近：%s").format("${b.value.toInt()}%")
                            } ?: "")
                    )
                    Text(
                        I18n.tr("供語言治療師追蹤參考；沒有使用的日子不畫點。逐日數字可用上方「匯出 CSV／PDF」取得。"),
                        fontSize = 9.sp, color = p.textMuted, lineHeight = 13.sp
                    )
                }
            }

            // === 摘要卡 ===
            val s = snapshot
            if (s == null) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = p.neonCyan) }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryTile(I18n.tr("🎯 練習"), "${s.totalRehabSessions}", I18n.tr("次"), Modifier.weight(1f))
                        SummaryTile(I18n.tr("📈 平均"), "%.0f".format(s.averageScore), I18n.tr("分"), Modifier.weight(1f))
                        SummaryTile(I18n.tr("💖 正向"), "${s.positiveEmotionCount}", I18n.tr("次"), Modifier.weight(1f))
                    }
                }

                // === 折線圖：進步趨勢 ===
                item {
                    LineChartCompose(
                        data = s.rehabDaily,
                        palette = p,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // === 折線圖：復健命中率（跟讀分數達「意思有傳達到」的比例）===
                item {
                    LineChartCompose(
                        data = s.rehabHitDaily,
                        palette = p,
                        modifier = Modifier.fillMaxWidth(),
                        yMax = 100f,
                        title = I18n.tr("復健命中率（%，分數 ≥ 70）")
                    )
                }

                // === 長條圖：正向情緒次數 ===
                item {
                    BarChartCompose(
                        data = s.emotionDaily,
                        palette = p,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // === 故事練習摘要 ===
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.surface)
                            .border(1.dp, p.neonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(I18n.tr("📖 故事練習"), color = p.neonGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            I18n.tr("嘗試 %d 次・平均 %s 分").format(s.storyAttempts, "%.0f".format(s.storyAverageScore)),
                            color = p.textLight, fontSize = 14.sp
                        )
                    }
                }

                // === 發送到 Telegram ===
                item {
                    Button(
                        onClick = {
                            scope.launch {
                                sending = true
                                val chatId = settingsRepo.settings.first().emergencyChatId
                                val text = s.toTelegramText()
                                val r = TelegramClient.sendEmergency(text, chatId)
                                lastSendResult = if (r.isSuccess) I18n.tr("✅ 已送到 Chat %s").format(chatId)
                                                 else I18n.tr("❌ 送出失敗：%s").format(r.exceptionOrNull()?.message?.take(60) ?: "")
                                sending = false
                            }
                        },
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.neonCyan,
                            contentColor = p.darkBg
                        )
                    ) {
                        Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (sending) I18n.tr("傳送中…") else I18n.tr("📤 匯出成績單到 Telegram"),
                             fontWeight = FontWeight.Bold)
                    }
                    lastSendResult?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = p.textMuted, fontSize = 12.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(p.surface)
            .border(1.dp, p.neonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 10.dp)
    ) {
        Text(label, color = p.textMuted, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = p.textLight, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            Text(unit, color = p.textMuted, fontSize = 11.sp,
                 modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
