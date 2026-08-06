package com.example.aphasia.domain.usecase

import com.example.aphasia.domain.model.AcousticTemplate
import com.example.aphasia.domain.model.MatchResult
import com.example.aphasia.domain.model.ReconstructedSentence
import com.example.aphasia.domain.model.Utterance
import com.example.aphasia.domain.repository.AcousticTemplateRepository
import com.example.aphasia.domain.repository.AudioFeatureRepository
import com.example.aphasia.domain.repository.LanguageModelRepository
import com.example.aphasia.domain.repository.PhrasebookRepository
import com.example.aphasia.domain.repository.SpeechRepository

/**
 * 錄一次音並比對聲學樣板。Module 1 的入口。
 *
 * 靜音判斷放在這裡而不是 Repository：這是產品政策（什麼算「一次發聲」），
 * 不是資料存取細節。之前直接聽功能就是因為沒有這道閘，
 * 對著空氣按下去也會信心 95% 說出一句話。
 */
class MatchUtteranceUseCase(
    private val audio: AudioFeatureRepository,
    private val templates: AcousticTemplateRepository,
) {
    suspend operator fun invoke(maxDurationMs: Long = 4_000): MatchResult {
        val utterance = audio.record(maxDurationMs)
        if (utterance.durationMs < MIN_SPEECH_MS || utterance.peakAmplitude < MIN_PEAK) {
            return MatchResult.NotSpeech
        }
        return templates.match(utterance)
    }

    companion object {
        const val MIN_SPEECH_MS = 250L
        const val MIN_PEAK = 0.02f
    }
}

/**
 * 登錄一個新的聲學樣板。
 *
 * 強制多次錄音：門檻是由這幾次之間的離散程度算出來的，
 * 只錄一次就沒有 σ 可估，等於回到寫死常數。
 */
class EnrollAcousticTemplateUseCase(
    private val templates: AcousticTemplateRepository,
) {
    suspend operator fun invoke(
        label: String,
        spokenPhrase: String,
        takes: List<Utterance>,
    ): Result<AcousticTemplate> = runCatching {
        require(takes.size >= AcousticTemplate.MIN_ENROLLMENT_TAKES) {
            "Need at least ${AcousticTemplate.MIN_ENROLLMENT_TAKES} recordings to calibrate a threshold"
        }
        templates.enroll(label, spokenPhrase, takes)
    }
}

/**
 * 把片段重組成完整句子，並帶入個人語句庫作為上下文。
 */
class ReconstructSentenceUseCase(
    private val llm: LanguageModelRepository,
    private val phrasebook: PhrasebookRepository,
) {
    suspend operator fun invoke(fragments: List<String>): List<ReconstructedSentence> {
        if (fragments.isEmpty()) return emptyList()
        val context = runCatching {
            phrasebook.searchSimilar(fragments.joinToString(" "))
        }.getOrDefault(emptyList())
        return llm.reconstruct(fragments, context)
    }
}

/**
 * 說出一句話並記錄使用次數（讓常用句自然浮上來）。
 */
class SpeakUseCase(
    private val speech: SpeechRepository,
    private val phrasebook: PhrasebookRepository,
) {
    suspend operator fun invoke(text: String, phraseId: Long? = null): Result<Unit> {
        val result = speech.speak(text)
        if (result.isSuccess && phraseId != null) {
            runCatching { phrasebook.markUsed(phraseId) }
        }
        return result
    }
}
