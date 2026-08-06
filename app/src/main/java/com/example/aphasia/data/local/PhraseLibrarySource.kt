package com.example.aphasia.data.local

import android.content.Context
import com.example.ai.DiagnosticLog
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 內建的句子清單：`assets/phrase_library_en.json`。
 *
 * 每一筆是「要聽的關鍵字」＋「聽到之後要說出口的完整句子」。
 * 使用者從清單挑一句、對著它說兩三次那個關鍵字，就啟用了——
 * 不必自己想句子，也不必打字。
 *
 * **為什麼不能直接內建錄好的樣板：** 聲波樣板是認人的，不是認詞的。
 * 別人錄的 "need water" 跟這位使用者的基頻、共振峰、語速都不同；
 * 對構音障礙使用者更嚴重，因為他的發音本來就偏離所有人的平均——
 * 那正是 STT 對他失效的原因，同一個原因也讓別人的樣板對他失效。
 * 所以清單內建，聲音必須是他自己的。
 */
class PhraseLibrarySource(private val context: Context) {

    @JsonClass(generateAdapter = true)
    data class Entry(
        val id: String,
        val category: String,
        /**
         * 要聽的那幾個字。使用者登錄時就唸這幾個字。
         *
         * **必須是他實際講那句話時「連續」的一段。** 比對走 subsequence DTW，
         * 它能拉伸時間軸，但沒辦法跳過中間插進來的音節。
         * 實測（[com.example.aphasia.KeywordContiguityTest]）：對同一句
         * 「I need to go to the bathroom」，連續的 "go to the bathroom" 距離 0.85，
         * 挑實詞跳掉虛詞的 "need go bathroom" 是 7.09——差了八倍，
         * 而不相干的話大約 12～15，等於後者幾乎沒有安全邊界。
         */
        val keyword: String,
        /** 命中後要說出口的完整句子。 */
        val sentence: String,
        /**
         * 這一筆用過的舊關鍵字。
         *
         * 關鍵字改寫之後，已經登錄過的樣板存的還是舊的字串。沒有這個對照表，
         * 那些樣板會查不到句庫、圖示變空白，而且會在「加入新的」清單裡重新冒出來，
         * 看起來像是設定不見了——但他錄的音其實還在，也還會正常命中。
         */
        val aliases: List<String> = emptyList(),
    ) {
        /** 這個關鍵字（現在的或曾經的）是不是指向這一筆。 */
        fun matches(kw: String): Boolean {
            val k = kw.trim()
            return keyword.equals(k, ignoreCase = true) ||
                aliases.any { it.equals(k, ignoreCase = true) }
        }
    }

    suspend fun load(assetName: String = DEFAULT_ASSET): List<Entry> = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val type = Types.newParameterizedType(List::class.java, Entry::class.java)
            Moshi.Builder().build().adapter<List<Entry>>(type).fromJson(json) ?: emptyList()
        }.onFailure {
            // 清單讀不到時回空而不是崩掉：使用者仍然可以自己錄一個自訂的音，
            // 其他輸入方式也都還在。
            DiagnosticLog.e(DiagnosticLog.Cat.UI, "句子清單 $assetName 載入失敗", t = it)
        }.getOrDefault(emptyList())
    }

    companion object {
        const val DEFAULT_ASSET = "phrase_library_en.json"
    }
}
