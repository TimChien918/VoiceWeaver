package com.example.aphasia.data.repository

import com.example.ai.DiagnosticLog
import com.example.aphasia.data.remote.AphasiaPrompts
import com.example.aphasia.data.remote.LlmResponseParser
import com.example.aphasia.data.remote.LlmTransport
import com.example.aphasia.data.remote.ReconstructionGuard
import com.example.aphasia.domain.model.CommunicationPhrase
import com.example.aphasia.domain.model.ReconstructedSentence
import com.example.aphasia.domain.repository.LanguageModelRepository
import com.example.aphasia.domain.repository.PolitenessLevel

/**
 * Module 2（碎詞重組）與 Module 3（Grammar Sync / Politeness）。
 *
 * 這一層負責的是 **prompt、輸出契約、與把關**；「送去哪家、失敗換誰」
 * 全部交給 [LlmTransport]。因此整個類別可以完全不碰網路被測試——
 * 而 prompt 與解析正是最容易出錯的地方。
 */
class LanguageModelRepositoryImpl(
    private val transport: LlmTransport,
) : LanguageModelRepository {

    override suspend fun reconstruct(
        fragments: List<String>,
        personalContext: List<CommunicationPhrase>,
    ): List<ReconstructedSentence> {
        val clean = fragments.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return emptyList()

        val raw = transport.complete(
            system = AphasiaPrompts.RECONSTRUCT_SYSTEM,
            user = AphasiaPrompts.reconstructUser(clean, personalContext),
            temperature = RECONSTRUCT_TEMPERATURE,
            maxTokens = 400,
        ).getOrElse { error ->
            // 錯誤要往上傳，不要吞掉。之前用 runCatching{}.getOrNull() 把 429
            // 額度用完變成畫面上的「語音辨識掛了」，查了一整輪才找到真正原因。
            DiagnosticLog.e(DiagnosticLog.Cat.LLM, "重組失敗（${transport.label}）", t = error)
            throw error
        }

        val parsed = LlmResponseParser.parseCandidates(raw).getOrElse { error ->
            DiagnosticLog.e(
                DiagnosticLog.Cat.LLM, "重組回應無法解析",
                "回應前 200 字=${raw.take(200)}", error,
            )
            return emptyList()
        }

        val kept = ReconstructionGuard.filter(parsed, clean, personalContext) { rejected, added ->
            DiagnosticLog.w(
                DiagnosticLog.Cat.LLM, "候選句被擋下：憑空生成",
                "句子=「${rejected.text}」 多出來的詞=${added.joinToString("/")}",
            )
        }
        DiagnosticLog.i(
            DiagnosticLog.Cat.LLM,
            "重組「${clean.joinToString(" ")}」→ ${kept.size}/${parsed.size} 個候選",
            "引擎=${transport.label} 上下文=${personalContext.size} 筆",
        )
        return kept.sortedByDescending { it.confidence }
    }

    override suspend fun polishGrammar(text: String): ReconstructedSentence {
        val input = text.trim()
        require(input.isNotEmpty()) { "Cannot polish an empty sentence" }

        val raw = transport.complete(
            system = AphasiaPrompts.GRAMMAR_SYSTEM,
            user = AphasiaPrompts.grammarUser(input),
            // 文法修正沒有創意空間，溫度壓到 0。同一句話每次得到不同結果
            // 會讓使用者不知道該相信哪一個。
            temperature = 0.0,
            maxTokens = 200,
        ).getOrThrow()

        val result = LlmResponseParser.parseSingle(raw).getOrElse {
            DiagnosticLog.w(DiagnosticLog.Cat.LLM, "文法修正回應無法解析，保留原句", raw.take(200))
            return unchanged(input)
        }
        // 文法修正不該引入新的實詞。模型有時會順手「補完」句子——
        // 那已經不是修文法，是替使用者說話。
        return when (ReconstructionGuard.check(result, listOf(input))) {
            ReconstructionGuard.Verdict.Allowed -> result
            is ReconstructionGuard.Verdict.Fabricated -> {
                DiagnosticLog.w(
                    DiagnosticLog.Cat.LLM, "文法修正改動了內容，退回原句",
                    "原句=「$input」 模型=「${result.text}」",
                )
                unchanged(input)
            }
        }
    }

    override suspend fun adjustPoliteness(
        text: String,
        level: PolitenessLevel,
    ): ReconstructedSentence {
        val input = text.trim()
        require(input.isNotEmpty()) { "Cannot rephrase an empty sentence" }

        val raw = transport.complete(
            system = AphasiaPrompts.POLITENESS_SYSTEM,
            user = AphasiaPrompts.politenessUser(input, level),
            temperature = 0.2,
            maxTokens = 200,
        ).getOrThrow()

        return LlmResponseParser.parseSingle(raw).getOrElse {
            DiagnosticLog.w(DiagnosticLog.Cat.LLM, "語氣調整回應無法解析，保留原句", raw.take(200))
            unchanged(input)
        }
        // 這裡刻意**不**套 ReconstructionGuard：換語氣本來就會換詞
        //（"give me water" → "could I have some water"），
        // 用實詞比對去擋會把正確結果全部否決掉。
    }

    override suspend fun isAvailableOffline(): Boolean = transport.isOfflineCapable()

    private fun unchanged(text: String) = ReconstructedSentence(text, confidence = 1f, rationale = "no change")

    companion object {
        /**
         * 稍微高一點的溫度是為了讓 3 個候選真的不一樣。
         * 溫度 0 時模型對短碎詞常常吐出三句幾乎一模一樣的話，去重完只剩一個，
         * 使用者就沒得挑——而「給使用者挑」正是這個設計的重點。
         */
        const val RECONSTRUCT_TEMPERATURE = 0.6
    }
}
