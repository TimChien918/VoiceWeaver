package com.speakmylife.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Task 3.3: JSON 解析與重組協調器 (ReconstructionUseCase)
 * 負責將 GlobalTagsVault 傳入推論引擎，配合 NativeRuleCritic 進行雙層過濾與防護。
 * 執行於 Dispatchers.Default 確保 UI 執行緒全然流暢、永不卡頓。
 */
class ReconstructionUseCase(
    private val inferenceEngine: InferenceEngine
) {

    private val fallbackMessage = "我不確定您的意思，可以點擊下方按鈕補充更多細節嗎？"

    /**
     * 重組流程串接
     */
    suspend fun execute(vault: GlobalTagsVault): ReconstructedData = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        
        // 1. 將 GlobalTagsVault 餵入 CoTInferenceEngine
        val result = try {
            inferenceEngine.reconstructSentence(vault)
        } catch (e: Exception) {
            Log.e("ReconstructionUseCase", "Engine reconstruction crashed", e)
            Result.failure(e)
        }

        val data = result.getOrNull()

        // 2. 檢驗：若是推論本身失敗
        if (data == null) {
            val elapsed = System.currentTimeMillis() - startTime
            return@withContext ReconstructedData(
                originalText = vault.rawText,
                reconstructedText = fallbackMessage,
                confidence = 0.0,
                engineUsed = "Inference Error Fallback",
                inferenceTimeMs = elapsed
            )
        }

        // 3. 雙層防護：信心極低（<0.40）或 NativeRuleCritic 判定幻覺。
        //
        //    以前只要其中一項不過，就把整句換成「我不確定您的意思…」。那太保守了：
        //    「我 痛 醫院」這種語意其實很清楚的碎詞也會被換成一句廢話，
        //    而且 fallback 會重建 ReconstructedData、把三個候選句一起丟掉，
        //    使用者連挑都沒得挑。藏起最佳猜測對講不出話的人是幫倒忙。
        //
        //    改成：句子照給，只把信心壓低 → UI 自動標「△ 把握較低，請確認再唸」，
        //    由照護者自己判斷要不要唸。真的什麼都沒有（引擎失敗／清乾淨後是空字串）
        //    才用 fallbackMessage。
        val isConfidenceLow = data.confidence < 0.40
        val passesCritic = try {
            NativeRuleCritic.audit(data.reconstructedText, vault)
        } catch (e: Exception) {
            Log.e("ReconstructionUseCase", "NativeRuleCritic crashed", e)
            false
        }

        val elapsed = System.currentTimeMillis() - startTime

        // 收斂空白：任何引擎都可能吐出前後換行/多段空行，壓成單行避免輸出卡爆高一片空白；
        // 萬一壓完變空字串，才退回 fallbackMessage，不讓卡片空白無內容。
        // 候選句也套同一套問句保留／收斂空白規則，切換時才不會漏字或跑版。
        fun cleanOne(text: String) = preserveExplicitQuestions(vault.rawText, text)
            .trim().replace(Regex("\\s*\\n\\s*"), " ")
            .ifBlank { fallbackMessage }

        val cleaned = cleanOne(data.reconstructedText)
        val distrusted = isConfidenceLow || !passesCritic

        if (distrusted) {
            Log.w(
                "ReconstructionUseCase",
                "Low trust (shown anyway) — confidence low: $isConfidenceLow (${data.confidence}), passes critic: $passesCritic"
            )
        } else {
            Log.i("ReconstructionUseCase", "Reconstruction passed all checks successfully!")
        }

        data.copy(
            reconstructedText = cleaned,
            alternatives = data.alternatives.map { it.copy(text = cleanOne(it.text)) },
            // 壓到 0.75（UI 的「大致正確」門檻）以下，讓卡片明確標示需要確認。
            // 取 min 是因為模型自評可能比實際可信度樂觀（截圖那次自評 85% 卻被 critic 打掉）。
            confidence = if (cleaned == fallbackMessage) 0.0
                         else if (distrusted) minOf(data.confidence, LOW_TRUST_CONFIDENCE)
                         else data.confidence,
            engineUsed = if (distrusted) "${data.engineUsed} -> " + com.speakmylife.app.ui.I18n.tr("低信心") else data.engineUsed,
            inferenceTimeMs = elapsed
        )
    }

    private companion object {
        /** 被判定不可信時顯示的信心值——低於 UI 的 0.75 門檻，會標「把握較低，請確認再唸」。 */
        const val LOW_TRUST_CONFIDENCE = 0.35
    }

    private fun preserveExplicitQuestions(rawText: String, reconstructedText: String): String {
        val requiredQuestions = listOf(
            "什麼時候",
            "何時",
            "在哪裡",
            "哪裡",
            "要等多久",
            "可以嗎",
            "能不能",
            "多少錢",
            "怎麼做"
        ).filter { rawText.contains(it) && !reconstructedText.contains(it) }

        if (requiredQuestions.isEmpty()) return reconstructedText

        val normalized = reconstructedText.trim().trimEnd('。', '！', '!', '?', '？')
        val questionText = requiredQuestions.joinToString("，") { keyword ->
            when (keyword) {
                "什麼時候", "何時" -> "請告訴我什麼時候"
                "在哪裡", "哪裡" -> "請告訴我在哪裡"
                "要等多久" -> "請告訴我要等多久"
                "可以嗎" -> "這樣可以嗎"
                "能不能" -> "請問能不能"
                "多少錢" -> "請問多少錢"
                "怎麼做" -> "請告訴我怎麼做"
                else -> keyword
            }
        }
        return "$normalized；$questionText？"
    }
}
