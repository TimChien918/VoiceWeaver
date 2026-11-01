package com.speakmylife.app.aphasia.data.repository

import com.speakmylife.app.ai.DiagnosticLog
import com.speakmylife.app.ai.TtsModule
import com.speakmylife.app.aphasia.domain.model.VoiceProfile
import com.speakmylife.app.aphasia.domain.repository.SpeechRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 包住既有的 [TtsModule]。
 *
 * 沒有重寫語音合成：GPT-SoVITS 的參考音檔配對、多引擎切換、語言判斷這些
 * 都是踩過坑才修對的（參考音檔與逐字稿必須成對決定，拆開就會發出怪聲），
 * 重寫等於把那些坑再踩一次。
 */
class SpeechRepositoryImpl(
    private val tts: TtsModule,
) : SpeechRepository {

    private val profiles = MutableStateFlow<List<VoiceProfile>>(emptyList())
    private val active = MutableStateFlow<VoiceProfile?>(null)

    override fun observeProfiles(): Flow<List<VoiceProfile>> = profiles.asStateFlow()

    override suspend fun activeProfile(): VoiceProfile? = active.value

    override suspend fun setActiveProfile(id: String) {
        active.value = profiles.value.firstOrNull { it.id == id }
    }

    override suspend fun speak(text: String, profile: VoiceProfile?): Result<Unit> {
        val clean = text.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Nothing to speak"))

        DiagnosticLog.i(DiagnosticLog.Cat.TTS, "說出「$clean」", "嗓音=${(profile ?: active.value)?.displayName ?: "預設"}")

        // TtsModule 的 onComplete 在某些引擎路徑上不保證會被呼叫（合成失敗、
        // 引擎沒初始化完）。沒有逾時的話這裡會永遠掛著，UI 的「說話中」也永遠不消失。
        val finished = withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val resumed = AtomicBoolean(false)
                cont.invokeOnCancellation { tts.stopSpeaking() }
                tts.speak(clean) {
                    if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(Unit)
                }
            }
        }
        return if (finished != null) {
            Result.success(Unit)
        } else {
            DiagnosticLog.w(DiagnosticLog.Cat.TTS, "合成逾時（${SPEAK_TIMEOUT_MS}ms）", "文字=「$clean」")
            Result.failure(IllegalStateException("Speech synthesis timed out"))
        }
    }

    override suspend fun stop() = tts.stopSpeaking()

    companion object {
        /** 一句可講的話最長也就十幾秒；超過代表引擎卡住了，不是句子太長。 */
        const val SPEAK_TIMEOUT_MS = 30_000L
    }
}
