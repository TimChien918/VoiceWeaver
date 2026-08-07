package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.LocalAppPalette
import com.example.ui.theme.iosReadable

/**
 * 設定子頁（iOS 風格的「整頁」）。原本是半透明置中對話框、字看不清楚；
 * 改成**整頁實色背景 + 頂部返回列**，並在內容外層覆蓋高對比 [IosReadablePalette]，
 * 讓面板內所有文字（白字 / 淺灰說明 / 系統藍強調）在深底上清楚可讀。
 *
 * 仍用 [Dialog] 取得獨立全螢幕 Window（蓋住底部導覽），但行為像「點進去的分頁」：
 * 左上返回 ‹ 或系統返回鍵關閉。
 */
@Composable
fun FloatingPanel(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    /** true：面板自帶垂直捲動（內容用一般 Column）。false：內容自己捲（例如內含 LazyColumn）。 */
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val iosP = LocalAppPalette.current.iosReadable
        // Dialog 會用自己的 view context 蓋掉 LocalContext，stringResource() 會退回裝置語言，
        // 造成面板裡 I18n.tr() 是英文、strings.xml 的字卻是中文。這裡重掛一次語言。
        ReapplyAppLocale {
        CompositionLocalProvider(LocalAppPalette provides iosP) {
            val p = iosP
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(p.darkBg)
            ) {
                // 頂部返回列（iOS：‹ 返回 + 標題）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.darkBg)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = p.neonCyan, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(2.dp))
                    Icon(icon, null, tint = p.textLight, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    // 返回列也是固定高度的導覽列，標題放大幅度要設上限（見 chromeSp）。
                    Text(title, fontSize = com.example.ui.theme.chromeSp(20f),
                        fontWeight = FontWeight.Bold, color = p.textLight,
                        maxLines = 1, modifier = Modifier.weight(1f))
                }
                if (scrollable) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        content()
                    }
                } else {
                    content()
                }
            }
        }
        }
    }
}
