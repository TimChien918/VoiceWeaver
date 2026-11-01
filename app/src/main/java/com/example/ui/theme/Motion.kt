package com.example.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 動態規範（拾語者品牌）。
 *
 * 原則：動畫只做三件事——**回饋**（按到了）、**轉場**（換到哪了）、**引導注意力**（看這裡）。
 * 使用者多是中風後的中高齡患者：太快看不到、太花會分心、彈跳過頭會頭暈，
 * 所以時長比一般 App 稍長、彈性一律低回彈，不做純裝飾的動畫。
 */
object Motion {
    /** 按壓回饋：要「立刻」有感，但不能快到看不見。 */
    const val Quick = 140
    /** 一般元件進出（卡片彈出、指示器移動）。 */
    const val Standard = 280
    /** 頁面轉場、分數滾動這種「要讓人跟得上」的變化。 */
    const val Calm = 420
    /** 依序進場時每一項之間的間隔。三個候選句一共不到半秒就全部到位。 */
    const val Stagger = 90

    /** 強調減速：一開始就動、尾巴慢慢停——眼睛最容易跟住的曲線。 */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardEasing: Easing = FastOutSlowInEasing
}

/**
 * 使用者是否在系統「開發人員選項／協助工具」關掉了動畫（動畫時長比例 = 0）。
 *
 * Compose 自己也會讀這個比例、把有限長度的動畫直接跳到終點；但**無限循環**的動畫
 * （脈動、呼吸、載入）跳到終點後會停在某個不上不下的影格。所以循環動畫要自己看這個值，
 * 改畫一張有意義的靜態圖，而不是凍在半路。
 *
 * 讀不到（Robolectric、某些 ROM）就當作「沒關」，不要因為例外讓畫面整個不動。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}
