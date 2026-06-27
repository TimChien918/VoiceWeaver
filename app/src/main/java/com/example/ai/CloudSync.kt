package com.example.ai

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.ImageApiEntry
import com.example.data.ImageApiStore
import com.example.data.ImageProvider
import com.example.data.LlmApiEntry
import com.example.data.LlmApiStore
import com.example.data.LlmProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * 雲端同步：用 Firebase Auth（Google / 匿名）+ Firestore，把「API 供應商清單 + 金鑰 + Telegram」
 * 鏡射到 users/{uid}，**與網頁版同一份結構**（llmApis/imageApis/apiKeys）。
 * 用同一個 Google 帳號登入手機與網頁即可互通。本地運算相關（GPT-SoVITS 等）不上雲。
 */
object CloudSync {
    private const val TAG = "CloudSync"
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    val uid: String? get() = auth.currentUser?.uid
    val isSignedIn: Boolean get() = auth.currentUser != null

    /**
     * 登入就緒回呼：解決「冷啟動時 Firebase auth 尚未非同步還原，當下 isSignedIn=false 導致永不 pull」競態。
     * addAuthStateListener 會在註冊當下立即以目前狀態觸發一次，之後狀態變更（還原/登入）再觸發；
     * 用 fireOnce 確保自動拉取只跑一次（避免 token 刷新重複 pull）。
     */
    fun addOnSignedIn(fireOnce: Boolean = true, cb: () -> Unit) {
        var fired = false
        auth.addAuthStateListener { a ->
            if (a.currentUser != null && !(fireOnce && fired)) {
                fired = true
                cb()
            }
        }
    }
    val displayName: String?
        get() = auth.currentUser?.let { it.displayName ?: it.email ?: if (it.isAnonymous) "匿名使用者" else "已登入" }

    fun googleClient(ctx: Context): GoogleSignInClient {
        val opt = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(ctx.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(ctx, opt)
    }

    fun signInWithGoogle(idToken: String, done: (Boolean, String) -> Unit) {
        val cred = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(cred).addOnCompleteListener { t ->
            done(t.isSuccessful, if (t.isSuccessful) "已登入：${displayName}" else (t.exception?.message ?: "登入失敗"))
        }
    }

    fun signInAnon(done: (Boolean, String) -> Unit) {
        auth.signInAnonymously().addOnCompleteListener { t ->
            done(t.isSuccessful, if (t.isSuccessful) "已匿名登入（此帳號僅本機）" else (t.exception?.message ?: "登入失敗"))
        }
    }

    fun signOut(ctx: Context) {
        auth.signOut()
        runCatching { googleClient(ctx).signOut() }
    }

    // ── 推送：Android 清單 → Firestore（web 結構）──
    fun push(llmJson: String, imgJson: String, tgToken: String, tgChat: String, googlePlaces: String = "") {
        val u = uid ?: return
        val doc = hashMapOf<String, Any>(
            "llmApis" to jsonToList(llmToCloud(llmJson)),
            "imageApis" to jsonToList(imgToCloud(imgJson)),
            "apiKeys" to hashMapOf("tgtoken" to tgToken, "tgchat" to tgChat, "googlePlaces" to googlePlaces),
            "updatedAt" to System.currentTimeMillis()
        )
        db.collection("users").document(u).set(doc, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "push failed", it) }
    }

    // ── 拉取：Firestore → 回呼（呼叫端寫回 DataStore）──
    fun pull(onApply: (llmJson: String?, imgJson: String?, tgToken: String?, tgChat: String?, googlePlaces: String?) -> Unit) {
        val u = uid ?: return
        // 強制讀「伺服器」：Firestore 預設離線持久化會優先回傳快取，
        // 冷啟動拿到的常是舊/空白快取（曾觀察到 llmApis=[]、googlePlaces 空），
        // 導致雲端最新金鑰拉不下來。Source.SERVER 確保拿伺服器最新值。
        db.collection("users").document(u)
            .get(com.google.firebase.firestore.Source.SERVER)
            .addOnSuccessListener { snap ->
            if (!snap.exists()) { onApply(null, null, null, null, null); return@addOnSuccessListener }
            val llm = (snap.get("llmApis") as? List<*>)?.let { listToJson(it) }
            val img = (snap.get("imageApis") as? List<*>)?.let { listToJson(it) }
            val ak = snap.get("apiKeys") as? Map<*, *>
            onApply(
                llm?.let { llmFromCloud(it) },
                img?.let { imgFromCloud(it) },
                ak?.get("tgtoken") as? String,
                ak?.get("tgchat") as? String,
                ak?.get("googlePlaces") as? String
            )
        }.addOnFailureListener { Log.w(TAG, "pull failed", it) }
    }

    // ── 對應：Android enum 名（大寫）↔ web 小寫字串 ──
    private fun llmToCloud(json: String): JSONArray {
        val arr = JSONArray()
        LlmApiStore.decode(json).forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("provider", e.provider.name.lowercase())
                .put("key", e.key).put("model", e.model).put("name", e.name).put("endpoint", e.endpoint))
        }
        return arr
    }
    private fun llmFromCloud(arr: JSONArray): String {
        val list = ArrayList<LlmApiEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(LlmApiEntry(
                id = o.optString("id").ifBlank { "k" + i + System.currentTimeMillis() },
                provider = LlmProvider.from(o.optString("provider").uppercase()),
                name = o.optString("name"), endpoint = o.optString("endpoint"),
                model = o.optString("model"), key = o.optString("key")))
        }
        return LlmApiStore.encode(list)
    }
    private fun imgToCloud(json: String): JSONArray {
        val arr = JSONArray()
        ImageApiStore.decode(json).forEach { e ->
            if (e.provider != ImageProvider.LOCAL)   // 本機電腦生圖不上雲（裝置專屬）
                arr.put(JSONObject().put("id", e.id).put("provider", e.provider.name.lowercase())
                    .put("key", e.key).put("model", e.model).put("name", e.name).put("endpoint", e.endpoint))
        }
        return arr
    }
    private fun imgFromCloud(arr: JSONArray): String {
        val list = ArrayList<ImageApiEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(ImageApiEntry(
                id = o.optString("id").ifBlank { "k" + i + System.currentTimeMillis() },
                provider = ImageProvider.from(o.optString("provider").uppercase()),
                name = o.optString("name"), endpoint = o.optString("endpoint"),
                model = o.optString("model"), key = o.optString("key")))
        }
        return ImageApiStore.encode(list)
    }

    // ── 拉取電腦端 IP（devices/{linkedId} → gptSovitApiBaseUrl）──
    /**
     * 讀取電腦端語音中心推送的 IP。
     * @param linkedId  使用者在電腦語音中心看到的裝置代號（Firebase 匿名 UID）
     * @param onResult  (lanUrl, tailscaleUrl) 任一有值即可用；都空代表找不到
     */
    fun pullComputerIp(linkedId: String, onResult: (lanUrl: String, tailscaleUrl: String) -> Unit) {
        if (linkedId.isBlank()) return
        db.collection("devices").document(linkedId)
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) { onResult("", ""); return@addOnSuccessListener }
                onResult(
                    snap.getString("lanUrl") ?: "",
                    snap.getString("tailscaleUrl") ?: ""
                )
            }
            .addOnFailureListener { Log.w(TAG, "pullComputerIp failed", it); onResult("", "") }
    }

    // ── 推送復健日誌（網頁版成績單讀取用）──
    fun pushRehabLog(log: com.example.data.RehabLog) {
        val u = uid ?: return
        val data = hashMapOf<String, Any>(
            "timestamp" to log.timestamp,
            "locationTag" to log.locationTag,
            "targetSentence" to log.targetSentence,
            "score" to log.score
        )
        db.collection("users").document(u).collection("rehabLogs").add(data)
            .addOnFailureListener { Log.w(TAG, "pushRehabLog failed", it) }
    }

    // ── 推送單筆歷史（與網頁 users/{uid}/history 同結構）──
    fun pushHistory(original: String, reconstructed: String, ts: Long) {
        val u = uid ?: return
        val rec = hashMapOf<String, Any>(
            "original" to original,
            "reconstructed" to reconstructed,
            "ts" to ts
        )
        db.collection("users").document(u).collection("history").add(rec)
            .addOnFailureListener { Log.w(TAG, "pushHistory failed", it) }
    }

    // Firestore 要原生 List<Map>，JSONArray 互轉
    private fun jsonToList(a: JSONArray): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val m = HashMap<String, Any?>()
            o.keys().forEach { k -> m[k] = o.get(k) }
            out.add(m)
        }
        return out
    }
    private fun listToJson(l: List<*>): JSONArray {
        val a = JSONArray()
        l.forEach { item ->
            if (item is Map<*, *>) {
                val o = JSONObject()
                item.forEach { (k, v) -> o.put(k.toString(), v ?: JSONObject.NULL) }
                a.put(o)
            }
        }
        return a
    }
}
