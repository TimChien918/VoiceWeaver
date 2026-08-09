package com.example.safety

import android.util.Log
import com.example.ai.LocationTracker
import com.example.net.TelegramClient

/**
 * 緊急通報門面：把「危機關鍵字攔截 → 取 GPS → 發 Telegram」三步串成一個 suspend 函式。
 *
 * 設計選擇：故意做成 class 而非 object，方便在 Test 用 fake LocationTracker 注入。
 * ViewModel 持有一個唯一的 instance（與 LocationTracker 共用 lifecycle）。
 */
class EmergencyTrigger(
    private val locationTracker: LocationTracker,
    /** lazy chat id provider：在 ViewModel 從 DataStore 取最新值，避免重啟才生效 */
    private val chatIdProvider: suspend () -> String? = { null }
) {
    /**
     * 嘗試攔截 [userText]。回傳值說明：
     *  - `null`        → 沒有命中危機關鍵字，照常往下走（給 LLM 重組 / TTS）。
     *  - `Outcome.*`   → 攔截成功，呼叫端必須停止後續流程、改顯示求救 UI。
     */
    suspend fun interceptAndReport(userText: String, poiTag: String = ""): Outcome? {
        if (!SafetyGuard.containsCrisisSignal(userText)) return null
        return report(userText, poiTag)
    }

    private suspend fun report(userText: String, poiTag: String): Outcome {
        // 拿 GPS（拿不到也要送，至少要讓家屬知道）
        val loc = runCatching { locationTracker.getCurrentLocation() }.getOrNull()
        val action = CrisisAction(
            userText = userText,
            latitude = loc?.latitude,
            longitude = loc?.longitude,
            poiTag = poiTag.ifBlank { loc?.shortTag.orEmpty() }
        )

        val chatId = runCatching { chatIdProvider() }.getOrNull()
        val sendResult = TelegramClient.sendEmergency(action.toTelegramMessage(), chatId)
        return if (sendResult.isSuccess) {
            Log.w(TAG, "Emergency Telegram sent for: ${userText.take(30)}")
            Outcome.Sent(action)
        } else {
            Log.e(TAG, "Emergency Telegram FAILED", sendResult.exceptionOrNull())
            Outcome.Failed(action, sendResult.exceptionOrNull()?.message ?: "unknown")
        }
    }

    sealed class Outcome {
        abstract val action: CrisisAction
        data class Sent(override val action: CrisisAction) : Outcome()
        data class Failed(override val action: CrisisAction, val reason: String) : Outcome()
    }

    companion object { private const val TAG = "EmergencyTrigger" }
}
