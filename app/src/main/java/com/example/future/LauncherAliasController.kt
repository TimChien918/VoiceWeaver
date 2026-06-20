package com.example.future

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 動態 App 封面（icon / 名稱）切換器。
 *
 * 原理：在 `AndroidManifest.xml` 為每個封面建一個 `<activity-alias>`，
 * 平常只 enable 一個。要切換時：用 [PackageManager.setComponentEnabledSetting]
 * **同時**「停用舊 alias、啟用新 alias」，否則 Launcher 會出現兩個 icon。
 *
 * 注意事項：
 *  - Android 10+ Launcher 通常需要使用者**重開 App / 退出再進**才會刷新 icon
 *  - **絕對不可**把 MainActivity 本身停用（會把 App 從桌面整個拔掉，要重灌才救得回）
 *  - alias 的 `android:targetActivity` 必須指向同一個 Activity（MainActivity）
 *
 * 使用方式：
 * ```
 * LauncherAliasController(context).switchTo(LauncherCover.Festival)
 * ```
 */
enum class LauncherCover(val aliasClassName: String) {
    Default ("com.example.MainAlias"),         // 常駐封面：科技藍 ic_launcher
    Festival("com.example.FestivalAlias"),     // 過年限定：紅色封面
    Medical ("com.example.MedicalAlias")       // 醫院模式：低調白底
}

class LauncherAliasController(private val context: Context) {

    fun switchTo(target: LauncherCover) {
        val pm = context.packageManager
        LauncherCover.values().forEach { cover ->
            val component = ComponentName(context.packageName, cover.aliasClassName)
            val desired = if (cover == target) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            // DONT_KILL_APP 避免切換時把 App 自己殺掉
            pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
        }
    }

    /** 取得當前 enabled 的封面（給設定頁顯示「目前使用：XXX」） */
    fun currentCover(): LauncherCover {
        val pm = context.packageManager
        return LauncherCover.values().firstOrNull { cover ->
            val component = ComponentName(context.packageName, cover.aliasClassName)
            pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: LauncherCover.Default
    }
}
