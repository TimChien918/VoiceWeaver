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
    fun push(llmJson: String, imgJson: String, tgToken: String, tgChat: String) {
        val u = uid ?: return
        val doc = hashMapOf<String, Any>(
            "llmApis" to jsonToList(llmToCloud(llmJson)),
            "imageApis" to jsonToList(imgToCloud(imgJson)),
            "apiKeys" to hashMapOf("tgtoken" to tgToken, "tgchat" to tgChat),
            "updatedAt" to System.currentTimeMillis()
        )
        db.collection("users").document(u).set(doc, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "push failed", it) }
    }

    // ── 拉取：Firestore → 回呼（呼叫端寫回 DataStore）──
    fun pull(onApply: (llmJson: String?, imgJson: String?, tgToken: String?, tgChat: String?) -> Unit) {
        val u = uid ?: return
        db.collection("users").document(u).get().addOnSuccessListener { snap ->
            if (!snap.exists()) { onApply(null, null, null, null); return@addOnSuccessListener }
            val llm = (snap.get("llmApis") as? List<*>)?.let { listToJson(it) }
            val img = (snap.get("imageApis") as? List<*>)?.let { listToJson(it) }
            val ak = snap.get("apiKeys") as? Map<*, *>
            onApply(
                llm?.let { llmFromCloud(it) },
                img?.let { imgFromCloud(it) },
                ak?.get("tgtoken") as? String,
                ak?.get("tgchat") as? String
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
