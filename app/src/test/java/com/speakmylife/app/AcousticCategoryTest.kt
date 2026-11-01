package com.speakmylife.app

import com.speakmylife.app.ai.CloudSync
import com.speakmylife.app.ai.DriveAcousticMirror
import com.speakmylife.app.aphasia.domain.model.AcousticTemplate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 聲波模型在 Drive 上依分類分層：`AcousticModels/<分類>/<id>.json`。
 *
 * 檔名維持內部 id（唯一鍵；關鍵字是使用者隨時會改的東西，拿來當檔名
 * 改一次就多一個孤兒檔），看得懂與否交給資料夾——平放時使用者在自己的
 * 雲端硬碟只會看到一排 5.json、6.json，完全認不出哪個是哪一句。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AcousticCategoryTest {

    private fun remote(id: String = "5", keyword: String = "need water", category: String = "Basic needs") =
        CloudSync.RemoteTemplate(
            remoteId = id, label = keyword, keyword = keyword,
            spokenPhrase = "I'd like some water, please.",
            matchMode = "Keyword", rejectionThreshold = 1f, marginThreshold = 0.15f,
            exemplarBlobB64 = "AAAA", createdAt = 1L, category = category,
        )

    // ── 資料夾名稱 ─────────────────────────────────────────────────────

    @Test
    fun `分類會原封不動當成資料夾名`() {
        assertEquals("Basic needs", AcousticTemplate.sanitizeCategory("Basic needs"))
        assertEquals("Medical", AcousticTemplate.sanitizeCategory("Medical"))
    }

    @Test
    fun `空白的分類要落到 Custom 而不是建一個沒有名字的資料夾`() {
        // Drive 建不出空名字的資料夾，上傳會靜靜失敗——使用者只看到「同步沒作用」。
        listOf(null, "", "   ", "\t\n").forEach {
            assertEquals(AcousticTemplate.CATEGORY_CUSTOM, AcousticTemplate.sanitizeCategory(it))
        }
    }

    @Test
    fun `分類裡的斜線不能穿出去變成另一層資料夾`() {
        val cleaned = AcousticTemplate.sanitizeCategory("../../etc")
        assertFalse("「$cleaned」還帶著路徑分隔符", cleaned.contains("/"))
        assertFalse(cleaned.contains("\\"))
        assertEquals(".. .. etc", cleaned)
    }

    @Test
    fun `過長的分類要截短`() {
        val long = "x".repeat(200)
        assertEquals(48, AcousticTemplate.sanitizeCategory(long).length)
    }

    // ── JSON 欄位（三端共用）───────────────────────────────────────────

    @Test
    fun `分類會寫進 Drive 的 JSON`() {
        val json = DriveAcousticMirror.toJson(remote())
        assertEquals("Basic needs", json.optString("category"))
    }

    @Test
    fun `讀得回自己寫出去的分類`() {
        val back = DriveAcousticMirror.fromJson(DriveAcousticMirror.toJson(remote(category = "Medical")))
        assertEquals("Medical", back?.category)
    }

    @Test
    fun `舊檔案沒有分類欄位時歸到 Custom 而不是整筆丟掉`() {
        // 這些是使用者錄了 5 次才有的東西。因為少一個「純整理用」的欄位就讀不回來，
        // 會被「雲端是主」當成已刪除，連本機的錄音一起清掉。
        val legacy = JSONObject()
            .put("id", "5").put("label", "need water").put("keyword", "need water")
            .put("spokenPhrase", "Water please").put("matchMode", "Keyword")
            .put("rejectionThreshold", 1.0).put("marginThreshold", 0.15)
            .put("exemplarBlob", "AAAA").put("createdAt", 1L)
        val back = DriveAcousticMirror.fromJson(legacy)
        assertTrue("舊檔案應該照樣讀得回來", back != null)
        assertEquals(AcousticTemplate.CATEGORY_CUSTOM, back!!.category)
        assertEquals("need water", back.keyword)
        assertEquals("AAAA", back.exemplarBlobB64)
    }

    @Test
    fun `分類不影響去重`() {
        // 去重的鍵是觸發詞。同一句被歸到兩個分類時仍然只能留一筆，
        // 不然使用者會在清單裡看到重複的觸發詞。
        val merged = DriveAcousticMirror.merge(
            listOf(remote(id = "5", category = "Basic needs")),
            listOf(remote(id = "6", category = "Medical")),
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `樣板預設分類是 Custom`() {
        val t = AcousticTemplate(
            label = "x", spokenPhrase = "y", exemplars = emptyList(), rejectionThreshold = 1f,
        )
        assertEquals(AcousticTemplate.CATEGORY_CUSTOM, t.category)
    }
}
