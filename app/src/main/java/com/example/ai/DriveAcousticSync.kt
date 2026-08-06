package com.example.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 樣板的雲端鏡像。實作要能失敗而不影響本機——同步是加值，不是前提。
 *
 * 抽成介面是為了讓 [com.example.aphasia.data.repository.AcousticTemplateRepositoryImpl]
 * 在測試裡不需要 Context 也不需要網路。
 */
interface AcousticMirror {
    fun push(t: CloudSync.RemoteTemplate)
    fun delete(id: Long)
    suspend fun pull(): List<CloudSync.RemoteTemplate>
}

/**
 * 把聲波模型放進使用者自己的 Google Drive：`VoiceWeaver/AcousticModels/<uid>/<id>.json`。
 *
 * **為什麼 Firestore 之外還要有這一份：** Firestore 那份是「App 內部的同步管道」，
 * 使用者看不到也拿不走；Drive 這份是他自己資料夾裡的檔案，可以自己備份、
 * 自己搬到別的帳號、自己刪掉。聲波模型是他本人的聲音特徵，
 * 換一個人講就完全比不出來——重錄的成本全在他身上，所以那份資料應該在他手上。
 *
 * 跟語音模型（`VoiceWeaver/Models/`）刻意分開放：一個是幾百 MB、誰拿去都能用的角色，
 * 一個是幾十 KB、只對本人有效的特徵。混在一起的話想備份或刪掉其中一種都會誤傷另一種。
 *
 * **兩份的關係：** Firestore 是快路徑（即時、不需要 Drive 授權），Drive 是使用者
 * 自己那一份。寫入時兩邊都寫，取回時兩邊都讀、以 keyword 去重。任一邊失敗
 * 都只是少一條路，不會讓本機的樣板出事。
 */
class DriveAcousticMirror(context: Context) : AcousticMirror {

    private val appContext = context.applicationContext

    // 自己的 scope：push/delete 是「順手做的事」，不該卡住呼叫端的協程，
    // 也不該因為呼叫端的畫面關掉就取消（樣板已經寫進本機了，雲端那份要補上）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun push(t: CloudSync.RemoteTemplate) {
        scope.launch {
            val uid = CloudSync.uid ?: return@launch
            val token = DriveApi.token(appContext) ?: return@launch
            val folder = DriveApi.acousticFolder(token, uid, create = true) ?: return@launch
            val ok = DriveApi.writeJson(token, "${t.remoteId}.json", folder, toJson(t).toString())
            DiagnosticLog.i(
                DiagnosticLog.Cat.STT,
                if (ok) "聲波模型已寫進 Drive" else "聲波模型寫進 Drive 失敗（不影響本機）",
                "「${t.keyword}」→ VoiceWeaver/${DriveApi.ACOUSTIC_DIR}/…/${t.remoteId}.json",
            )
        }
    }

    override fun delete(id: Long) {
        scope.launch {
            val uid = CloudSync.uid ?: return@launch
            val token = DriveApi.token(appContext) ?: return@launch
            // create=false：使用者從來沒同步過的話，不該為了刪東西反而建一堆空資料夾
            val folder = DriveApi.acousticFolder(token, uid, create = false) ?: return@launch
            DriveApi.trash(token, "$id.json", folder)
        }
    }

    override suspend fun pull(): List<CloudSync.RemoteTemplate> = withContext(Dispatchers.IO) {
        val uid = CloudSync.uid ?: return@withContext emptyList()
        val token = DriveApi.token(appContext) ?: return@withContext emptyList()
        val folder = DriveApi.acousticFolder(token, uid, create = false) ?: return@withContext emptyList()
        DriveApi.listChildren(token, folder)
            .filter { !it.isFolder && it.name.endsWith(".json", ignoreCase = true) }
            .mapNotNull { entry ->
                val text = DriveApi.readText(token, entry.id) ?: return@mapNotNull null
                // 單筆壞掉不該讓整次取回失敗——其他筆是好的，使用者該拿得到
                runCatching { fromJson(JSONObject(text)) }.getOrNull()
            }
    }

    companion object {

        /** 與 Mac 端 drive_uploader / 網頁版 js/drive.js 共用的欄位名。改這裡要三邊一起改。 */
        fun toJson(t: CloudSync.RemoteTemplate): JSONObject = JSONObject()
            .put("id", t.remoteId)
            .put("label", t.label)
            .put("keyword", t.keyword)
            .put("spokenPhrase", t.spokenPhrase)
            .put("matchMode", t.matchMode)
            .put("rejectionThreshold", t.rejectionThreshold.toDouble())
            .put("marginThreshold", t.marginThreshold.toDouble())
            .put("exemplarBlob", t.exemplarBlobB64)
            .put("createdAt", t.createdAt)
            .put("updatedAt", System.currentTimeMillis())

        /** 回 null＝這筆沒有意義（不知道要聽什麼或不知道要說什麼），跳過。 */
        fun fromJson(o: JSONObject): CloudSync.RemoteTemplate? {
            val keyword = o.optString("keyword")
            val phrase = o.optString("spokenPhrase")
            if (keyword.isBlank() || phrase.isBlank()) return null
            return CloudSync.RemoteTemplate(
                remoteId = o.optString("id"),
                label = o.optString("label").ifBlank { keyword },
                keyword = keyword,
                spokenPhrase = phrase,
                matchMode = o.optString("matchMode").ifBlank { "Keyword" },
                rejectionThreshold = o.optDouble("rejectionThreshold", 0.0).toFloat(),
                marginThreshold = o.optDouble("marginThreshold", 0.15).toFloat(),
                exemplarBlobB64 = o.optString("exemplarBlob"),
                createdAt = o.optLong("createdAt", 0L),
            )
        }

        /**
         * 合併兩個來源的樣板，**以 keyword 為鍵**去重。
         *
         * 同一個觸發詞有兩筆時留有錄音的那一筆：沒錄音的（網頁版建的文字設定）
         * 在手機上永遠不會命中，讓它蓋掉有錄音的等於把功能弄壞。
         * 兩筆都有錄音就留新的。
         */
        fun merge(vararg sources: List<CloudSync.RemoteTemplate>): List<CloudSync.RemoteTemplate> {
            val best = LinkedHashMap<String, CloudSync.RemoteTemplate>()
            for (list in sources) for (t in list) {
                val key = t.keyword.lowercase().trim()
                if (key.isEmpty()) continue
                val prev = best[key]
                best[key] = when {
                    prev == null -> t
                    prev.exemplarBlobB64.isBlank() && t.exemplarBlobB64.isNotBlank() -> t
                    prev.exemplarBlobB64.isNotBlank() && t.exemplarBlobB64.isBlank() -> prev
                    t.createdAt > prev.createdAt -> t
                    else -> prev
                }
            }
            return best.values.toList()
        }
    }
}
