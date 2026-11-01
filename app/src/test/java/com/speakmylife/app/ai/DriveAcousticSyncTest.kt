package com.speakmylife.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 聲波模型在 Drive 上的編解碼與合併。
 *
 * 這裡最要緊的是 [DriveAcousticMirror.merge]：使用者為了一個觸發詞要錄 5 次音，
 * 合併時把有錄音的那筆換成沒錄音的，等於叫他重錄一遍——而且畫面上看起來
 * 一切正常，他只會覺得「這個怎麼突然不會動了」。
 *
 * 走 Robolectric 是因為用 `org.json`：純 JVM 測試拿到的是 Android SDK 的空殼實作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DriveAcousticSyncTest {

    private fun t(
        id: String, keyword: String, blob: String = "AAAA", createdAt: Long = 1_000L,
        phrase: String = "I need some water",
    ) = CloudSync.RemoteTemplate(
        remoteId = id, label = keyword, keyword = keyword, spokenPhrase = phrase,
        matchMode = "Keyword", rejectionThreshold = 7.5f, marginThreshold = 0.15f,
        exemplarBlobB64 = blob, createdAt = createdAt,
    )

    // ── 編解碼 ──────────────────────────────────────────────────────────

    @Test
    fun `編碼解碼之後每個欄位都一樣`() {
        val src = t("1700000000", "need water")
        val back = DriveAcousticMirror.fromJson(DriveAcousticMirror.toJson(src))
        assertEquals(src, back)
    }

    @Test
    fun `錄音波形要一起寫進去`() {
        // 少了這個欄位，換手機拉回來的就只是一筆文字設定，5 次錄音等於白錄
        val json = DriveAcousticMirror.toJson(t("1", "need water", blob = "QUJDRA=="))
        assertEquals("QUJDRA==", json.getString("exemplarBlob"))
    }

    @Test
    fun `缺觸發詞或缺句子的視為無效`() {
        // 不知道要聽什麼、或不知道要說什麼，兩種都沒有意義
        assertNull(DriveAcousticMirror.fromJson(
            DriveAcousticMirror.toJson(t("1", keyword = ""))))
        assertNull(DriveAcousticMirror.fromJson(
            DriveAcousticMirror.toJson(t("1", "need water", phrase = ""))))
    }

    @Test
    fun `舊檔案缺欄位時用安全預設值`() {
        // Drive 上的檔案可能是舊版本寫的，不該因此整筆讀不出來
        val o = JSONObject()
            .put("id", "9").put("keyword", "need water").put("spokenPhrase", "I need some water")
        val r = DriveAcousticMirror.fromJson(o)!!
        assertEquals("Keyword", r.matchMode)
        assertEquals("need water", r.label)      // 沒 label 就用 keyword
        assertEquals(0.15f, r.marginThreshold, 1e-6f)
        assertEquals("", r.exemplarBlobB64)      // 沒錄音＝要在手機上補錄
    }

    @Test
    fun `壞掉的 JSON 不會炸掉整批`() {
        val ok = runCatching { DriveAcousticMirror.fromJson(JSONObject("{}")) }
        assertTrue("空物件應該回 null 而不是丟例外", ok.isSuccess && ok.getOrNull() == null)
    }

    // ── 合併 ───────────────────────────────────────────────────────────

    @Test
    fun `同一個觸發詞只留一筆`() {
        val merged = DriveAcousticMirror.merge(
            listOf(t("1", "need water")), listOf(t("2", "need water")))
        assertEquals(1, merged.size)
    }

    @Test
    fun `有錄音的永遠勝過沒錄音的`() {
        // 網頁版建的是純文字設定（瀏覽器沒有 DTW 比對），在手機上永遠不會命中。
        // 讓它蓋掉有錄音的那筆，就是把已經在用的功能弄壞。
        val withAudio = t("1", "need water", blob = "AAAA", createdAt = 1_000L)
        val textOnly = t("2", "need water", blob = "", createdAt = 9_999L)   // 而且還比較新

        assertEquals("AAAA",
            DriveAcousticMirror.merge(listOf(textOnly), listOf(withAudio)).single().exemplarBlobB64)
        assertEquals("順序反過來也要一樣", "AAAA",
            DriveAcousticMirror.merge(listOf(withAudio), listOf(textOnly)).single().exemplarBlobB64)
    }

    @Test
    fun `都有錄音時留比較新的`() {
        val old = t("1", "need water", blob = "OLD", createdAt = 1_000L)
        val new = t("2", "need water", blob = "NEW", createdAt = 2_000L)
        assertEquals("NEW", DriveAcousticMirror.merge(listOf(old), listOf(new)).single().exemplarBlobB64)
        assertEquals("NEW", DriveAcousticMirror.merge(listOf(new), listOf(old)).single().exemplarBlobB64)
    }

    @Test
    fun `觸發詞比對不分大小寫與前後空白`() {
        val merged = DriveAcousticMirror.merge(
            listOf(t("1", "Need Water")), listOf(t("2", " need water ")))
        assertEquals(1, merged.size)
    }

    @Test
    fun `不同觸發詞都要留著`() {
        val merged = DriveAcousticMirror.merge(
            listOf(t("1", "need water"), t("2", "need food")),
            listOf(t("3", "help me")))
        assertEquals(setOf("need water", "need food", "help me"),
            merged.map { it.keyword }.toSet())
    }

    @Test
    fun `空觸發詞不會被留下`() {
        assertTrue(DriveAcousticMirror.merge(listOf(t("1", "   "))).isEmpty())
    }

    @Test
    fun `其中一邊是空的也要正常`() {
        // 只登入了其中一邊（沒授權 Drive、或 Firestore 讀失敗）是很常見的狀態
        assertEquals(1, DriveAcousticMirror.merge(emptyList(), listOf(t("1", "need water"))).size)
        assertEquals(1, DriveAcousticMirror.merge(listOf(t("1", "need water")), emptyList()).size)
        assertTrue(DriveAcousticMirror.merge(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `合併結果的順序穩定`() {
        // 每次取回都換一個順序的話，使用者會以為清單被動過
        val a = listOf(t("1", "a"), t("2", "b"), t("3", "c"))
        val b = listOf(t("4", "b"), t("5", "d"))
        assertEquals(listOf("a", "b", "c", "d"),
            DriveAcousticMirror.merge(a, b).map { it.keyword })
    }
}
