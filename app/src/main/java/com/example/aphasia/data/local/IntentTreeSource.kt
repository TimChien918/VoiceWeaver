package com.example.aphasia.data.local

import android.content.Context
import com.example.ai.DiagnosticLog
import com.example.aphasia.domain.model.IntentNode
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 視覺化意圖樹的內容來源：`assets/intent_tree_en.json`。
 *
 * 放在 assets 而不是寫死在 Kotlin 裡：治療師與照顧者要能替個別使用者改內容，
 * 而改 JSON 不需要重新編譯。之後要做「匯入自訂樹」也是同一條路。
 */
class IntentTreeSource(private val context: Context) {

    @JsonClass(generateAdapter = true)
    data class NodeDto(
        val id: String,
        val label: String,
        val fragment: String,
        val icon: String? = null,
        val children: List<NodeDto>? = null,
    )

    suspend fun load(assetName: String = DEFAULT_ASSET): List<IntentNode> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
                val type = Types.newParameterizedType(List::class.java, NodeDto::class.java)
                val adapter = Moshi.Builder().build().adapter<List<NodeDto>>(type)
                (adapter.fromJson(json) ?: emptyList()).map { it.toDomain() }
            }.onFailure {
                // 意圖樹讀不到時回空清單而不是崩掉：其他輸入方式（發聲比對、打字）
                // 都還能用，使用者不該因為一個 asset 壞掉就完全無法溝通。
                DiagnosticLog.e(DiagnosticLog.Cat.UI, "意圖樹 $assetName 載入失敗", t = it)
            }.getOrDefault(emptyList())
        }

    private fun NodeDto.toDomain(): IntentNode = IntentNode(
        id = id,
        label = label,
        fragment = fragment,
        iconKey = icon,
        children = children.orEmpty().map { it.toDomain() },
    )

    companion object {
        const val DEFAULT_ASSET = "intent_tree_en.json"
    }
}
