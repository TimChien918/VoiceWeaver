package com.speakmylife.app.testing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB 測試指令的接收端。
 *
 * **這個檔案在 `app/src/debug/`——release 建置根本不會編譯它**，
 * 連同 `src/debug/AndroidManifest.xml` 裡的 `<receiver>` 宣告一起消失。
 * 正式版 APK 裡不存在這個 class，也就沒有任何外部管道能碰到 [TestHooks]。
 *
 * 必須 `exported="true"`：`adb shell am broadcast` 是以 shell UID 送出的，
 * 不同 UID 進不了未匯出的接收端。真正的把關在 [TestHooks.execute] 的權杖檢查——
 * token 每次啟動重新產生、只寫進 logcat，同機的其他 App 讀不到（Android 4.1+
 * 已禁止跨 App 讀 logcat），所以拿不到 token 就送不進任何指令。
 *
 * 用法：
 * ```
 * adb shell am broadcast -a com.speakmylife.app.TEST_COMMAND \
 *   --es token <TOKEN> --es command ping
 * ```
 */
class TestCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TestHooks.ACTION) return

        val extras = intent.extras ?: run {
            Log.w(TestHooks.TAG, "ERR 沒有帶任何參數")
            return
        }

        val command = extras.getString("command") ?: run {
            Log.w(TestHooks.TAG, "ERR 缺少 command")
            return
        }

        // am broadcast 送進來的旗標（--ez / --ef）在 Bundle 裡不是 String，
        // 這裡一律轉成字串，讓 TestHooks 只處理一種型別。
        val args = buildMap {
            for (key in extras.keySet()) {
                if (key == "command") continue
                val v = extras.get(key) ?: continue
                put(key, v.toString())
            }
        }

        // 結果同時回給呼叫端（setResultData）與 logcat。
        // `am broadcast` 預設就會把 result data 印在終端機上，腳本可以直接讀。
        val result = TestHooks.execute(command, args)
        if (isOrderedBroadcast) {
            resultCode = if (result.startsWith("OK")) 0 else 1
            resultData = result
        }
    }
}
