package com.speakmylife.app.ui

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
import androidx.compose.ui.platform.LocalDensity
import com.speakmylife.app.data.AppLanguage
import com.speakmylife.app.ui.theme.LocalAppFontScale
import com.speakmylife.app.ui.theme.linearFontDensity
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
 * 在 Dialog／Popup 內重掛一次語言**與字級**。
 *
 * Compose 的 [androidx.compose.ui.window.Dialog] 會用自己的 window 建 view，
 * 該 view 的 AndroidComposeView 在 `ProvideAndroidCompositionLocals` 裡把
 * `LocalContext` / `LocalConfiguration` / `LocalDensity` 換成「view 自己的」——外層
 * [LocaleProvider] 提供的 localized context 與 `MyApplicationTheme` 設定的字級
 * 就這樣被蓋掉了。結果是同一個面板裡 `I18n.tr()`（純物件、不看 context）是英文、
 * `stringResource()` 卻退回裝置語言的中文，而且**字級調到最大也完全沒變**
 * ——設定子頁全部走這個元件，等於整個設定頁的字級是壞的。
 *
 * 字級要從 [LocalAppFontScale] 讀（平台不會動它），不能讀 `LocalDensity`——
 * 這裡拿到的已經是被平台換掉的那一份了。
 *
 * 所以凡是 Dialog 內容都要包這一層。
 */
@Composable
fun ReapplyAppLocale(content: @Composable () -> Unit) {
    val appScale = LocalAppFontScale.current
    // 這裡的 base 是 Dialog window 的密度：density 正確、fontScale 是系統設定值。
    // 乘上 App 自己的倍率就回到跟主畫面一致。
    val base = LocalDensity.current
    LocaleProvider(language = I18n.lang) {
        CompositionLocalProvider(
            LocalDensity provides linearFontDensity(base.density, base.fontScale * appScale)
        ) { content() }
    }
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
