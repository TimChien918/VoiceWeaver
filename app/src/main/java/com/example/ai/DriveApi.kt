package com.example.ai

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Drive REST v3 的最小客戶端（只做這個 App 需要的那幾件事）。
 *
 * **為什麼是手寫 REST 而不是官方 SDK：** Drive 的 Android SDK 早就停止維護，
 * 而 google-api-client 那一套會多拖進好幾 MB 的相依。這裡總共只用到
 * 建資料夾／找檔／上傳／下載／丟垃圾桶五個端點，直接打 HTTP 反而清楚。
 *
 * 權限範圍是 [CloudSync.DRIVE_SCOPE]（drive.file）：只碰「這個專案自己建立的檔案」，
 * 使用者 Drive 裡的其他東西一律看不到。Mac 端的 drive_uploader 與網頁版
 * 用的是同一個 Google Cloud 專案，所以三邊看得到彼此建立的檔案——
 * drive.file 的授權是跟著專案走，不是跟著單一個 OAuth 客戶端。
 *
 * 所有函式都會阻塞，必須在背景執行緒呼叫。
 */
object DriveApi {

    private const val TAG = "DriveApi"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val FILES = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

    /** Drive 上的根資料夾名，與 Mac 端 drive_uploader.ROOT_FOLDER 一致。 */
    const val ROOT_FOLDER = "VoiceWeaver"

    /** 語音模型（GPT-SoVITS 聲文權重）。手機不合成，只會讀這一層來顯示有哪些聲音。 */
    const val MODELS_DIR = "Models"

    /** 聲波模型（專屬發音的關鍵字樣板）。跟語音模型分開，兩者性質完全不同。 */
    const val ACOUSTIC_DIR = "AcousticModels"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // 樣板檔只有幾十 KB，但使用者的網路可能很慢；讀取放寬一點
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 取 access token；沒登入或沒授權 Drive 時為 null。阻塞。 */
    fun token(ctx: Context): String? = CloudSync.driveAccessToken(ctx)

    // ── 基本操作 ────────────────────────────────────────────────────────

    private fun get(token: String, url: String): String? {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string()?.take(160)}")
                r.body?.string()
            }
        }.getOrElse {
            DiagnosticLog.w(DiagnosticLog.Cat.STT, "Drive 讀取失敗", it.message.orEmpty().take(200))
            null
        }
    }

    private fun q(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 在 [parent] 底下找同名項目的 id；沒有回 null。 */
    private fun findId(token: String, name: String, parent: String, folder: Boolean): String? {
        val mime = if (folder) "and mimeType = '$FOLDER_MIME'" else "and mimeType != '$FOLDER_MIME'"
        val query = "name = ${q(name)} and ${q(parent)} in parents and trashed = false $mime"
        val body = get(token, "$FILES?q=${enc(query)}&spaces=drive&fields=${enc("files(id,name)")}&pageSize=10")
            ?: return null
        val arr = runCatching { JSONObject(body).optJSONArray("files") }.getOrNull() ?: return null
        return if (arr.length() > 0) arr.getJSONObject(0).optString("id").ifBlank { null } else null
    }

    /** 找資料夾，沒有就建。回 id；失敗回 null。 */
    /** 取得（必要時建立）parent 底下叫 name 的資料夾。聲波模型照分類分層時會用到。 */
    fun ensureFolder(token: String, name: String, parent: String): String? {
        findId(token, name, parent, folder = true)?.let { return it }
        val meta = JSONObject()
            .put("name", name).put("mimeType", FOLDER_MIME)
            .put("parents", org.json.JSONArray().put(parent))
        val req = Request.Builder()
            .url("$FILES?fields=id")
            .header("Authorization", "Bearer $token")
            .post(meta.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string()?.take(160)}")
                JSONObject(r.body!!.string()).optString("id").ifBlank { null }
            }
        }.getOrElse {
            DiagnosticLog.w(DiagnosticLog.Cat.STT, "Drive 建資料夾失敗", it.message.orEmpty().take(200))
            null
        }
    }

    /**
     * 逐層 ensure 一條路徑，回最後一層的 id。
     * [create] 為 false 時只找不建，任何一層缺就回 null。
     */
    fun folderPath(token: String, parts: List<String>, create: Boolean): String? {
        var parent = "root"
        for (name in parts) {
            parent = (if (create) ensureFolder(token, name, parent)
                      else findId(token, name, parent, folder = true)) ?: return null
        }
        return parent
    }

    /**
     * 聲波模型資料夾：`VoiceWeaver/AcousticModels`。
     *
     * 底下不再分一層帳號 id。這是「使用者自己的」Drive——一個 Google 帳號一個雲端硬碟，
     * 而 Firebase 身分本來就是同一個 Google 帳號換來的，多包一層永遠只有一個子資料夾，
     * 只是讓他在 Drive 裡多點一次、還看到一串沒有意義的亂碼。
     */
    fun acousticFolder(token: String, create: Boolean): String? =
        folderPath(token, listOf(ROOT_FOLDER, ACOUSTIC_DIR), create)

    /**
     * 語音模型資料夾。新版面是 VoiceWeaver/Models，
     * 舊版面（語言資料夾直接掛在 VoiceWeaver 底下）就回 VoiceWeaver 本身——
     * 與 Mac 端 emotion_models.models_root 是同一條規則。
     */
    fun modelsFolder(token: String): String? {
        val root = folderPath(token, listOf(ROOT_FOLDER), create = false) ?: return null
        findId(token, MODELS_DIR, root, folder = true)?.let { return it }
        val hasLegacy = listChildren(token, root, foldersOnly = true)
            .any { it.name.uppercase() in setOf("ZH", "EN", "JA", "KO") }
        return if (hasLegacy) root else null
    }

    /** Drive 上的一個項目。 */
    data class Entry(val id: String, val name: String, val mimeType: String, val size: Long) {
        val isFolder: Boolean get() = mimeType == FOLDER_MIME
    }

    fun listChildren(token: String, parent: String, foldersOnly: Boolean = false): List<Entry> {
        val out = ArrayList<Entry>()
        var pageToken: String? = null
        var query = "${q(parent)} in parents and trashed = false"
        if (foldersOnly) query += " and mimeType = '$FOLDER_MIME'"
        do {
            val url = buildString {
                append("$FILES?q=").append(enc(query))
                append("&spaces=drive&pageSize=200")
                append("&fields=").append(enc("nextPageToken, files(id,name,mimeType,size)"))
                if (pageToken != null) append("&pageToken=").append(enc(pageToken!!))
            }
            val body = get(token, url) ?: break
            val o = runCatching { JSONObject(body) }.getOrNull() ?: break
            val files = o.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) {
                val f = files!!.getJSONObject(i)
                out.add(Entry(
                    id = f.optString("id"), name = f.optString("name"),
                    mimeType = f.optString("mimeType"),
                    size = f.optString("size").toLongOrNull() ?: 0L,
                ))
            }
            pageToken = o.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        return out
    }

    /** 讀檔內容（純文字用）。失敗回 null。 */
    fun readText(token: String, fileId: String): String? =
        get(token, "$FILES/$fileId?alt=media")

    /**
     * 寫一個 JSON 檔（同名就覆蓋）。回是否成功。
     *
     * 用 multipart 一次把 metadata 與內容送出去：分兩次打（先建再上傳）的話，
     * 中間失敗會在使用者 Drive 裡留下一個 0 byte 的空檔。
     */
    fun writeJson(token: String, name: String, parent: String, json: String): Boolean {
        val existing = findId(token, name, parent, folder = false)
        val meta = JSONObject().put("name", name).apply {
            // 更新既有檔案時不能帶 parents，Drive 會回 400
            if (existing == null) put("parents", org.json.JSONArray().put(parent))
        }
        val jsonMime = "application/json; charset=UTF-8".toMediaType()
        val body = okhttp3.MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(jsonMime))
            .addPart(json.toRequestBody(jsonMime))
            .build()
        val url = if (existing == null) "$UPLOAD?uploadType=multipart&fields=id"
                  else "$UPLOAD/$existing?uploadType=multipart&fields=id"
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .let { if (existing == null) it.post(body) else it.patch(body) }
            .build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string()?.take(160)}")
                true
            }
        }.getOrElse {
            DiagnosticLog.w(DiagnosticLog.Cat.STT, "Drive 寫入失敗（$name）", it.message.orEmpty().take(200))
            false
        }
    }

    /**
     * 丟垃圾桶（不是永久刪除）——使用者可以在 Drive 自己還原。
     * 本來就不存在時回 true：那已經是想要的狀態了。
     */
    fun trash(token: String, name: String, parent: String): Boolean {
        val id = findId(token, name, parent, folder = false) ?: return true
        val req = Request.Builder().url("$FILES/$id")
            .header("Authorization", "Bearer $token")
            .patch(JSONObject().put("trashed", true).toString()
                .toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { r -> r.isSuccessful }
        }.getOrDefault(false)
    }
}
