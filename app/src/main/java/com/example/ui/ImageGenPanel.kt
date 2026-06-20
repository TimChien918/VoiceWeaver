package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ai.VoiceWeaverViewModel
import com.example.data.ImageApiEntry
import com.example.ui.theme.AppPalette
import com.example.ui.theme.LocalAppPalette
import java.io.File

/**
 * AI 生圖浮動視窗：選「使用中」的生圖服務 → 輸入描述 → 生成 → 預覽 / 設為背景。
 * 服務商與金鑰的新增/管理在「API 金鑰管理」裡（每筆＝服務商下拉＋金鑰）。
 */
@Composable
fun ImageGenPanel(
    viewModel: VoiceWeaverViewModel,
    onDismiss: () -> Unit
) {
    val p = LocalAppPalette.current
    val state by viewModel.imageGenState.collectAsState()
    val apis by viewModel.imageApisFlow.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var prompt by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.ensureImageApisSeeded() }

    val selected = apis.firstOrNull { it.id == settings.imageApiSelectedId } ?: apis.firstOrNull()

    FloatingPanel(title = "AI 生圖", icon = Icons.Default.Image, onDismiss = onDismiss) {

        // 使用中服務（管理在 API 金鑰管理）
        Text("使用中的生圖服務", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.neonCyan)
        ActivePicker(apis = apis, selected = selected, onSelect = { viewModel.selectImageApi(it.id) }, p = p)

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("想生成的圖片描述") },
            placeholder = { Text("例：溫馨的客廳，柔和光線，扁平插畫風") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(p)
        )

        val loading = state is VoiceWeaverViewModel.ImageGenState.Loading
        Button(
            onClick = { viewModel.generateImage(prompt) },
            enabled = !loading && prompt.isNotBlank() && selected != null,
            colors = ButtonDefaults.buttonColors(containerColor = p.neonGreen),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("生成中…（雲端運算，約 10～30 秒）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            } else {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("生成圖片", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        when (val s = state) {
            is VoiceWeaverViewModel.ImageGenState.Success -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                ) {
                    AsyncImage(
                        model = File(s.pngPath),
                        contentDescription = "生成的圖片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.setCustomBackgroundUri("file://${s.pngPath}") },
                    border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Wallpaper, null, tint = p.neonCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("設為 App 背景", color = p.neonCyan, fontSize = 13.sp)
                }
            }
            is VoiceWeaverViewModel.ImageGenState.Error ->
                Text("❌ ${s.message}", fontSize = 12.sp, color = p.dangerRose, lineHeight = 16.sp)
            else -> Unit
        }

        Text(
            "要新增服務商或金鑰，請到「設定 → API 金鑰管理 → AI 生圖」。同一服務商可放多把金鑰，生圖時自動備援。",
            fontSize = 10.sp, color = p.textMuted, lineHeight = 15.sp
        )
    }
}

/** 使用中服務下拉選擇。 */
@Composable
private fun ActivePicker(
    apis: List<ImageApiEntry>,
    selected: ImageApiEntry?,
    onSelect: (ImageApiEntry) -> Unit,
    p: AppPalette
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            enabled = apis.isNotEmpty(),
            border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                selected?.provider?.displayName ?: "尚無生圖設定，請到 API 金鑰管理新增",
                color = p.textLight, fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ExpandMore, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            apis.forEachIndexed { index, entry ->
                val sameBefore = apis.take(index).count { it.provider == entry.provider }
                val label = entry.provider.displayName + if (sameBefore > 0) " #${sameBefore + 1}" else ""
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        if (entry.id == selected?.id)
                            Icon(Icons.Default.CheckCircle, null, tint = p.neonGreen, modifier = Modifier.size(16.dp))
                    },
                    onClick = { onSelect(entry); open = false }
                )
            }
        }
    }
}

@Composable
private fun fieldColors(p: AppPalette) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = p.textLight, unfocusedTextColor = p.textLight,
    focusedBorderColor = p.neonCyan, unfocusedBorderColor = p.textMuted.copy(alpha = 0.35f),
    focusedLabelColor = p.neonCyan, unfocusedLabelColor = p.textMuted, cursorColor = p.neonCyan,
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
)
