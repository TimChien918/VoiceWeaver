package com.example.aphasia.data.remote

import com.example.ai.DiagnosticLog
import com.example.ai.HybridInferenceEngine
import com.example.ai.MediaPipeLLMEngine

/** 走既有的多供應商雲端備援。 */
class CloudLlmTransport(private val engine: HybridInferenceEngine) : LlmTransport {

    override val label = "雲端（多家自動備援）"

    override suspend fun complete(
        system: String, user: String, temperature: Double, maxTokens: Int, timeoutMs: Long,
    ): Result<String> = runCatching {
        engine.complete(system, user, temperature, maxTokens, timeoutMs)
    }

    override suspend fun isOfflineCapable() = false
}

/** 走裝置端 MediaPipe LLM。 */
class OnDeviceLlmTransport(private val engine: MediaPipeLLMEngine) : LlmTransport {

    override val label = "裝置端 LLM"

    override suspend fun complete(
        system: String, user: String, temperature: Double, maxTokens: Int, timeoutMs: Long,
    ): Result<String> = runCatching {
        // maxTokens 與 timeoutMs 在裝置端沒有對應設定：MediaPipe 的長度上限是
        // 建立 LlmInference 時就固定的，逾時則由 App 生命週期控制。
        // 這裡不假裝支援，直接忽略而不是靜靜地給出錯誤的行為。
        engine.complete(system, user, temperature.toFloat())
            ?: error("On-device model is not ready or generation failed")
    }

    override suspend fun isOfflineCapable() = true
}

/**
 * 先本機、失敗再上雲。
 *
 * 順序是刻意的：這個 App 處理的是使用者最私密的溝通內容，能在裝置上做完就不該送出去。
 * 只有本機真的做不到時才走雲端，而且 [preferOnDevice] 為 false（使用者選了雲端優先）
 * 時才反過來——這個決定屬於使用者，不屬於程式。
 */
class FallbackLlmTransport(
    private val onDevice: LlmTransport?,
    private val cloud: LlmTransport?,
    private val preferOnDevice: Boolean = true,
) : LlmTransport {

    override val label: String = listOfNotNull(
        onDevice?.label?.takeIf { preferOnDevice },
        cloud?.label,
        onDevice?.label?.takeUnless { preferOnDevice },
    ).joinToString(" → ").ifEmpty { "無可用引擎" }

    override suspend fun complete(
        system: String, user: String, temperature: Double, maxTokens: Int, timeoutMs: Long,
    ): Result<String> {
        val order = if (preferOnDevice) listOfNotNull(onDevice, cloud) else listOfNotNull(cloud, onDevice)
        if (order.isEmpty()) {
            return Result.failure(IllegalStateException("No language model is available on this device"))
        }
        var firstError: Throwable? = null
        for (transport in order) {
            val result = transport.complete(system, user, temperature, maxTokens, timeoutMs)
            if (result.isSuccess) return result
            // 第一個錯誤才是使用者真正該看到的——後面的失敗多半是「因為前面失敗才輪到我」。
            // 之前把錯誤整個吞掉（runCatching{}.getOrNull()），害得 429 額度用完
            // 在畫面上只顯示成「語音辨識掛了」，查了一輪才找到。
            if (firstError == null) firstError = result.exceptionOrNull()
            DiagnosticLog.w(
                DiagnosticLog.Cat.LLM, "${transport.label} 失敗，換下一個",
                result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
            )
        }
        return Result.failure(firstError ?: IllegalStateException("All language model transports failed"))
    }

    override suspend fun isOfflineCapable(): Boolean = onDevice?.isOfflineCapable() == true
}
