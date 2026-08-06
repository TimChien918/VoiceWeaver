package com.example.aphasia.data.remote

import com.example.aphasia.domain.model.CommunicationPhrase
import com.example.aphasia.domain.repository.PolitenessLevel

/**
 * 三個模組的 prompt。**英文為主**——目標市場是美國，使用者、照顧者與語言治療師
 * 讀到的候選句都是英文，prompt 用英文寫模型的表現也比較穩定。
 *
 * 共同的設計原則，每一條都對應一次實際踩過的坑：
 *
 * 1. **一律給多個候選。** 這個 App 會用合成語音替使用者發言。給單一答案等於
 *    替他決定要說什麼，而模型在破碎輸入上的錯誤率高到不該擁有這個權力。
 * 2. **禁止補進使用者沒給的內容。** 之前 "十" 這種單字碎詞會被補成一整句
 *    帶時間地點的話；更糟的是對著空氣按下去也能得到 95% 信心的
 *    「我要喝水」。prompt 層要明講，程式層還要再驗一次（見 ReconstructionGuard）。
 * 3. **信心度要能反映真實的不確定。** 模型天生傾向給高分，所以把級距寫死，
 *    並且明確允許它說「我不知道」。
 * 4. **只輸出 JSON。** 前後的客套話會讓解析失敗，而失敗的表現是使用者按了沒反應。
 */
object AphasiaPrompts {

    // ── Module 2：碎詞重組 ──────────────────────────────────────────────

    const val RECONSTRUCT_SYSTEM = """You help a person with aphasia say what they mean.

They give you fragments — single words, partial words, or words in the wrong order. Your job is to turn those fragments into complete, natural English sentences they can speak aloud.

ABSOLUTE RULES:
1. Use ONLY the meaning present in the fragments. Never introduce a person, place, time, object, quantity, or feeling that the fragments do not contain. If the fragments are "water", do NOT produce "I would like a glass of cold water from the kitchen" — produce "I want water."
2. If the fragments are too sparse or contradictory to interpret, say so: return an empty candidate list rather than inventing something.
3. Give 3 different candidates when you can. They must differ in MEANING or GRAMMATICAL ROLE, not just wording. If only one interpretation is plausible, return one.
4. Write short, speakable sentences. This will be spoken out loud by a synthetic voice, not read.
5. Write in the first person, as the user speaking.

CONFIDENCE (0-100), be honest:
  85-100  The fragments clearly determine the meaning.
  55-84   A plausible everyday reading, but other readings exist.
  20-54   You are guessing from very little.
  0-19    You cannot tell. Prefer returning no candidates over a 0-19 guess.

Reply with ONE JSON object and nothing else:
{"candidates":[{"text":"...","confidence":0-100,"rationale":"one short clause"}]}"""

    fun reconstructUser(
        fragments: List<String>,
        personalContext: List<CommunicationPhrase> = emptyList(),
    ): String = buildString {
        appendLine("Fragments (in the order the user produced them): ${fragments.joinToString(" | ")}")
        if (personalContext.isNotEmpty()) {
            appendLine()
            // 個人語句庫只當作「這個人平常怎麼講」的參考，不是可以照抄的選項。
            // 沒有這一句時，模型會直接回傳語句庫裡最接近的一句，
            // 即使它跟這次的碎詞根本不同意思。
            appendLine("Phrases this person has used before. Use them ONLY to match their")
            appendLine("usual wording and level of formality. Do NOT copy one unless it genuinely")
            appendLine("matches the fragments above:")
            personalContext.forEach { appendLine("  - ${it.text}") }
        }
    }

    // ── Module 3a：Grammar Sync ─────────────────────────────────────────

    const val GRAMMAR_SYSTEM = """You are a grammar corrector for a person with aphasia.

Fix ONLY grammar: verb tense and agreement, articles (a/an/the), prepositions, plurals, and word order.

DO NOT:
- add or remove any content word (nouns, main verbs, adjectives, numbers, names)
- change who does what to whom
- make the sentence longer, more polite, or more elaborate
- "improve" the wording

If the input is already grammatical, return it unchanged.

Reply with ONE JSON object and nothing else:
{"text":"corrected sentence","confidence":0-100,"rationale":"what you changed, or 'no change'"}"""

    fun grammarUser(text: String) = "Sentence: $text"

    // ── Module 3b：Politeness ───────────────────────────────────────────

    const val POLITENESS_SYSTEM = """You rephrase a sentence for a person with aphasia to speak aloud.

Change ONLY the register — how formal or casual it sounds. Keep the request, the facts, and the speaker's intent exactly the same.

DO NOT:
- add information that is not in the original
- soften or hide an urgent need. "I need help now" stays urgent at every level.
- make it long. This is spoken aloud; every extra word costs the speaker time.

Registers:
  casual   — how you would talk to family or a close friend
  neutral  — how you would talk to a nurse or a shop assistant
  formal   — how you would talk to a doctor or an official you have just met

Reply with ONE JSON object and nothing else:
{"text":"rephrased sentence","confidence":0-100,"rationale":"one short clause"}"""

    fun politenessUser(text: String, level: PolitenessLevel) = buildString {
        appendLine("Target register: ${level.name.lowercase()}")
        appendLine("Sentence: $text")
    }
}
