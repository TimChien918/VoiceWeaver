package com.speakmylife.app.ui

import android.Manifest
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

/** 需要的三組單一權限 */
object AppPermissions {
    const val LOCATION  = Manifest.permission.ACCESS_FINE_LOCATION
    const val CAMERA    = Manifest.permission.CAMERA
    const val MICROPHONE = Manifest.permission.RECORD_AUDIO
}

/**
 * 包裝單一權限的 Guard Composable。
 * 若已授權直接執行 [content]；否則顯示說明卡片並提供請求按鈕。
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WithPermission(
    permission: String,
    rationaleTitle: String,
    rationaleText: String,
    content: @Composable (PermissionState) -> Unit
) {
    val permState = rememberPermissionState(permission)
    if (permState.status.isGranted) {
        content(permState)
    } else {
        PermissionRationaleCard(
            title        = rationaleTitle,
            body         = rationaleText,
            showRationale = permState.status.shouldShowRationale,
            onRequest    = { permState.launchPermissionRequest() }
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRationaleCard(
    title: String,
    body: String,
    showRationale: Boolean,
    onRequest: () -> Unit
) {
    val darkBg     = Color(0xFF1E293B)
    val neonCyan   = Color(0xFF06B6D4)
    val textLight  = Color(0xFFF1F5F9)
    val textMuted  = Color(0xFF94A3B8)

    Card(
        colors = CardDefaults.cardColors(containerColor = darkBg),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector  = Icons.Default.Lock,
                contentDescription = null,
                tint         = neonCyan,
                modifier     = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textLight)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (showRationale) "此功能需要授予權限才能運作，請點擊下方按鈕。" else body,
                fontSize  = 11.sp,
                color     = textMuted,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRequest,
                colors  = ButtonDefaults.buttonColors(containerColor = neonCyan)
            ) {
                Text(I18n.tr("授予權限"), fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

/** 快捷輔助函數：回傳 true = 可繼續操作，false = 需先授權 */
@OptIn(ExperimentalPermissionsApi::class)
fun PermissionState.isReady() = status.isGranted
