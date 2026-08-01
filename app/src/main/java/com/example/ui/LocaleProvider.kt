package com.example.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.data.AppLanguage
import java.util.Locale

/**
 * 把 Compose 樹切換到指定語言。
 *
 * 實作：自訂 [ContextWrapper]，baseContext 仍是 Activity（讓 Accompanist 的 findActivity()
 * 走 ContextWrapper.baseContext 鏈找得到 Activity），只覆寫 getResources() / getAssets()
 * 改回 localized 版本。`stringResource()` 內部讀 `LocalContext.current.resources` → 自動拿到
 * 對應 locale 的 strings.xml；ActivityResultLauncher / Permission 系統仍能找到宿主 Activity。
 *
 * 之前直接 `LocalContext provides createConfigurationContext()` 會造成 Accompanist
 * Permissions crash：「Permissions should be called in the context of an Activity」。
 */
@Composable
fun LocaleProvider(language: AppLanguage, content: @Composable () -> Unit) {
    I18n.lang = language   // 同步給「中文原文鍵」翻譯層（補 strings.xml 未涵蓋的寫死字串）
    val base = LocalContext.current
    val localized = remember(language, base) {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        val cfgContext = base.createConfigurationContext(cfg)
        LocalizedContextWrapper(base, cfgContext.resources)
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration
    ) {
        content()
    }
}

/**
 * 在 Dialog／Popup 內重掛一次語言。
 *
 * Compose 的 [androidx.compose.ui.window.Dialog] 會用自己的 window 建 view，
 * 該 view 的 AndroidComposeView 在 `ProvideAndroidCompositionLocals` 裡把
 * `LocalContext` / `LocalConfiguration` 換成「view 自己的 context」——外層 [LocaleProvider]
 * 提供的 localized context 就這樣被蓋掉了。結果是同一個面板裡
 * `I18n.tr()`（純物件、不看 context）是英文，`stringResource()` 卻退回裝置語言的中文。
 *
 * 所以凡是 Dialog 內容都要包這一層。
 */
@Composable
fun ReapplyAppLocale(content: @Composable () -> Unit) {
    LocaleProvider(language = I18n.lang, content = content)
}

/**
 * baseContext 仍為原 Activity（保留 Activity 鏈，permission API 可用），
 * 只把 resources/assets 切換到 localized 版本。
 */
private class LocalizedContextWrapper(
    base: Context,
    private val localizedResources: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
    override fun getAssets(): AssetManager = localizedResources.assets
}
