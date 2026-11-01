package com.speakmylife.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 外部語料庫匯入器。支援兩種格式：
 *
 * **JSON**（推薦，能帶 emoji / category / isStrongIntent 完整 metadata）
 * ```json
 * [
 *   { "id": "p_betel", "label": "包葉檳榔", "emoji": "🌿", "category": "products", "isStrongIntent": false },
 *   { "id": "g_help",  "label": "救命",     "emoji": "🆘", "category": "emergency", "isStrongIntent": true }
 * ]
 * ```
 *
 * **CSV**（簡易，給家屬用 Excel 編輯。第一列為 header）
 * ```
 * id,label,emoji,category,isStrongIntent
 * p_betel,包葉檳榔,🌿,products,false
 * g_help,救命,🆘,emergency,true
 * ```
 *
 * 解析策略：
 *  - 不認得的 category → 落到 [AacCategory.Products]（保守 fallback，不會擋住匯入）
 *  - id 重複 → 後者覆蓋前者（行為與 DataStore 一致）
 *  - 任一列 parse 失敗 → 整批拒絕（避免半套資料污染既有語料）
 */
object CorpusImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    data class JsonRow(
        val id: String,
        val label: String,
        val emoji: String = "🔹",
        val category: String = "products",
        val isStrongIntent: Boolean = false
    )

    sealed class Result {
        data class Ok(val items: List<AacItem>) : Result()
        data class Err(val reason: String) : Result()
    }

    /** 從 SAF / ACTION_OPEN_DOCUMENT 拿到的 [Uri] 讀檔並 parse。 */
    suspend fun importFromUri(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@runCatching Result.Err("無法讀取檔案")

            val name = uri.lastPathSegment.orEmpty().lowercase()
            when {
                name.endsWith(".json") || raw.trimStart().startsWith("[") -> parseJson(raw)
                name.endsWith(".csv")  || raw.contains(",")               -> parseCsv(raw)
                else -> Result.Err("不支援的格式：請選 .json 或 .csv")
            }
        }.getOrElse { Result.Err(it.message ?: "未知錯誤") }
    }

    fun parseJson(raw: String): Result = runCatching {
        val rows: List<JsonRow> = json.decodeFromString(raw)
        Result.Ok(rows.map { it.toAacItem() })
    }.getOrElse { Result.Err("JSON 格式錯誤：${it.message?.take(80)}") }

    /**
     * 切一列 CSV，**認得雙引號包起來的欄位**。
     *
     * 直接 `split(",")` 的後果實測是整列往後位移一格：
     * `a1,"I need water, please",💧,products` 會被切成
     * `label=「"I need water」`、`emoji=「please"」`——匯進來的圖卡標籤是半句話、
     * emoji 欄變成文字，而畫面上不會有任何錯誤，使用者只會覺得「匯入的卡怪怪的」。
     * 而 AAC 的標籤本來就是句子，帶逗號是常態不是例外。
     *
     * 依 RFC 4180：引號內的逗號屬於內容，連續兩個雙引號代表一個字面雙引號。
     */
    internal fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i++          // "" → 一個字面雙引號
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out.map { it.trim() }
    }

    fun parseCsv(raw: String): Result = runCatching {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return Result.Err("CSV 至少要有 header + 1 列")
        val header = splitCsvLine(lines.first()).map { it.lowercase() }
        val idxId            = header.indexOf("id")
        val idxLabel         = header.indexOf("label")
        val idxEmoji         = header.indexOf("emoji")
        val idxCategory      = header.indexOf("category")
        val idxStrongIntent  = header.indexOf("isstrongintent")
        if (idxId < 0 || idxLabel < 0) return Result.Err("CSV header 缺少 id 或 label 欄")

        val items = lines.drop(1).map { line ->
            val cols = splitCsvLine(line)
            JsonRow(
                id = cols.getOrNull(idxId).orEmpty(),
                label = cols.getOrNull(idxLabel).orEmpty(),
                emoji = cols.getOrNull(idxEmoji).orEmpty().ifBlank { "🔹" },
                category = cols.getOrNull(idxCategory).orEmpty().ifBlank { "products" },
                isStrongIntent = cols.getOrNull(idxStrongIntent)?.equals("true", ignoreCase = true) == true
            ).toAacItem()
        }
        Result.Ok(items)
    }.getOrElse { Result.Err("CSV 解析失敗：${it.message?.take(80)}") }

    private fun JsonRow.toAacItem(): AacItem = AacItem(
        id = id,
        label = label,
        emoji = emoji,
        category = AacCategory.values().firstOrNull { it.key == category.lowercase() }
            ?: AacCategory.Products,
        isStrongIntent = isStrongIntent
    )
}
