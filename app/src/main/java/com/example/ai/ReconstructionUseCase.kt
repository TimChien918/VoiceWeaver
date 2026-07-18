package com.example.ai

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

        // 3. 雙層防護機制審核
        // A. 判定信心度：只有「極低把握（<0.40）」才整句蓋成 fallback。
        //    原本 0.70 太嚴——失語症碎詞（例如單字「十」）LLM 常自評 0.4~0.6，
        //    卻被整句藏掉、換成「我不確定您的意思」，等於很多可用的最佳猜測都看不到。
        //    幻覺由 NativeRuleCritic 另外把關（憑空捏造照樣擋），所以這裡放寬即可；
        //    0.40~0.75 的猜測仍會顯示，UI 會自動標「△ 把握較低，請確認再唸」讓照護者確認。
        val isConfidenceLow = data.confidence < 0.40

        // B. 判定幻覺：NativeRuleCritic 是否審核不通過
        val passesCritic = try {
            NativeRuleCritic.audit(data.reconstructedText, vault)
        } catch (e: Exception) {
            Log.e("ReconstructionUseCase", "NativeRuleCritic crashed", e)
            false
        }

        val elapsed = System.currentTimeMillis() - startTime

        if (isConfidenceLow || !passesCritic) {
            Log.w(
                "ReconstructionUseCase",
                "Fallback triggered! Confidence low: $isConfidenceLow (value: ${data.confidence}), Passes critic: $passesCritic"
            )
            // 觸發 Fallback: 回傳極佳 UI 安全提示
            ReconstructedData(
                originalText = vault.rawText,
                reconstructedText = fallbackMessage,
                confidence = data.confidence,
                engineUsed = "${data.engineUsed} -> Fallback Protection Grid",
                inferenceTimeMs = elapsed
            )
        } else {
            Log.i("ReconstructionUseCase", "Reconstruction passed all checks successfully!")
            // 全部通過，返回重組後的句子。
            // 收斂空白：任何引擎都可能吐出前後換行/多段空行，壓成單行避免輸出卡爆高一片空白；
            // 萬一壓完變空字串，退回 fallbackMessage，不讓卡片空白無內容。
            val cleaned = preserveExplicitQuestions(vault.rawText, data.reconstructedText)
                .trim().replace(Regex("\\s*\\n\\s*"), " ")
                .ifBlank { fallbackMessage }
            data.copy(
                reconstructedText = cleaned,
                inferenceTimeMs = elapsed
            )
        }
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
