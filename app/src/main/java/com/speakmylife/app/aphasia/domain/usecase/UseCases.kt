package com.speakmylife.app.aphasia.domain.usecase

import com.speakmylife.app.aphasia.domain.model.ReconstructedSentence
import com.speakmylife.app.aphasia.domain.repository.LanguageModelRepository
import com.speakmylife.app.aphasia.domain.repository.PhrasebookRepository
import com.speakmylife.app.aphasia.domain.repository.SpeechRepository

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
