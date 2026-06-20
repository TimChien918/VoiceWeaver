package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ai.VoiceWeaverViewModel
import com.example.data.SpeechRecord
import com.example.ui.theme.LocalAppPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: VoiceWeaverViewModel,
    onBack: () -> Unit
) {
    val historyRecords by viewModel.historyRecords.collectAsState()

    val p = LocalAppPalette.current
    val darkBg = p.darkBg
    val surfaceColor = p.surface
    val neonCyan = p.neonCyan
    val neonGreen = p.neonGreen
    val auraAmber = p.auraAmber
    val dangerRose = p.dangerRose
    val textLight = p.textLight
    val textMuted = p.textMuted

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.history_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "back",
                            tint = neonCyan
                        )
                    }
                },
                actions = {
                    if (historyRecords.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "clear all",
                                tint = dangerRose.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = darkBg,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = darkBg
    ) { innerPadding ->
        if (historyRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = textMuted,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.history_empty_title), fontSize = 14.sp, color = textMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.history_empty_sub), fontSize = 11.sp, color = textMuted.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.history_count, historyRecords.size),
                        fontSize = 11.sp,
                        color = textMuted,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(historyRecords, key = { it.id }) { record ->
                    HistoryRecordCard(
                        record = record,
                        textLight = textLight,
                        textMuted = textMuted,
                        surfaceColor = surfaceColor,
                        neonCyan = neonCyan,
                        neonGreen = neonGreen,
                        dangerRose = dangerRose,
                        auraAmber = auraAmber,
                        onToggleFavorite = { viewModel.toggleFavorite(record.id, !record.isFavorite) },
                        onDelete = { viewModel.deleteRecord(record.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(
    record: SpeechRecord,
    textLight: Color,
    textMuted: Color,
    surfaceColor: Color,
    neonCyan: Color,
    neonGreen: Color,
    dangerRose: Color,
    auraAmber: Color,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date(record.timestamp))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(
            0.5.dp,
            if (record.isFavorite) auraAmber.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row: timestamp + location + actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(dateStr, fontSize = 10.sp, color = textMuted)
                    if (record.locationTag.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Place, null,
                            tint = neonCyan,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            record.locationTag,
                            fontSize = 10.sp,
                            color = textMuted,
                            maxLines = 1
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (record.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "favorite",
                            tint = if (record.isFavorite) auraAmber else textMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete, "delete",
                            tint = dangerRose.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Original fragments
            Text(
                text = stringResource(R.string.history_fragment_prefix, record.originalInput),
                fontSize = 11.sp,
                color = textMuted
            )

            Spacer(Modifier.height(4.dp))

            // Reconstructed sentence
            Text(
                text = record.reconstructedText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textLight,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(8.dp))

            // Footer: confidence + vision tags
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(neonGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(record.confidence, fontSize = 10.sp, color = neonGreen, fontWeight = FontWeight.Bold)
                }
                if (record.visionTag.isNotBlank()) {
                    Text(
                        text = record.visionTag.take(40),
                        fontSize = 10.sp,
                        color = textMuted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
