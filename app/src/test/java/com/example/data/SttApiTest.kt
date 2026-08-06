package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 語音辨識供應商設定的行為。
 *
 * 這一份設定決定「使用者講的話能不能變成文字」，而那是整條流程的入口——
 * 這裡壞掉的症狀是「按了沒反應」，從畫面上完全看不出是設定問題。
 *
 * 跑 Robolectric 是因為編解碼用 `org.json`——純 JVM 測試拿到的是
 * Android SDK 的空殼實作，每個方法都回 null，測不到真實行為。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SttApiTest {

    @Test
    fun `留空的端點與模型會退回供應商預設值`() {
        val e = SttApiEntry(id = "a", provider = SttProvider.GROQ)
        assertEquals(SttProvider.GROQ.defaultEndpoint, e.effectiveEndpoint)
        assertEquals(SttProvider.GROQ.defaultModel, e.effectiveModel)
    }

    @Test
    fun `自訂的端點與模型會蓋過預設值`() {
        val e = SttApiEntry(
            id = "a", provider = SttProvider.GROQ,
            endpoint = "https://example.com/v1/x", model = "my-model",
        )
        assertEquals("https://example.com/v1/x", e.effectiveEndpoint)
        assertEquals("my-model", e.effectiveModel)
    }

    @Test
    fun `沒填金鑰的付費供應商不可用`() {
        assertFalse(SttApiEntry(id = "a", provider = SttProvider.GROQ).usable)
        assertTrue(SttApiEntry(id = "a", provider = SttProvider.GROQ, key = "gsk_real").usable)
    }

    @Test
    fun `範例金鑰視為沒填`() {
        // 範例設定被原樣存下來很常見。讓它進候選清單只會換來一個看不懂的 401，
        // 而使用者只會看到「語音辨識失敗」，不知道是自己沒改金鑰。
        assertFalse(SttApiEntry(id = "a", provider = SttProvider.OPENAI, key = "YOUR_KEY_HERE").usable)
    }

    @Test
    fun `免金鑰的供應商不填金鑰也可用`() {
        assertTrue(SttApiEntry(id = "a", provider = SttProvider.LOCAL).usable)
    }

    @Test
    fun `停用的項目不進候選清單但設定留著`() {
        val e = SttApiEntry(id = "a", provider = SttProvider.GROQ, key = "gsk_real", enabled = false)
        assertFalse("停用就不該被試", e.usable)
        assertEquals("金鑰要留著，重新啟用時不必再打一次", "gsk_real", e.key)
    }

    @Test
    fun `編碼解碼之後每個欄位都一樣`() {
        val list = listOf(
            SttApiEntry("a", SttProvider.GROQ, "我的 Groq", "https://e1", "m1", "k1", true),
            SttApiEntry("b", SttProvider.DEEPGRAM, "", "", "", "k2", false),
        )
        val back = SttApiStore.decode(SttApiStore.encode(list))
        assertEquals(list, back)
    }

    @Test
    fun `順序在編碼解碼之後保持不變`() {
        // 順序就是實際的嘗試順序，不是排版偏好——亂掉等於使用者的設定失效
        val list = listOf(
            SttApiEntry("first", SttProvider.LOCAL),
            SttApiEntry("second", SttProvider.GROQ, key = "k"),
            SttApiEntry("third", SttProvider.OPENAI, key = "k"),
        )
        assertEquals(
            listOf("first", "second", "third"),
            SttApiStore.decode(SttApiStore.encode(list)).map { it.id },
        )
    }

    @Test
    fun `舊資料沒有 enabled 欄位時視為啟用`() {
        // 升級不該讓既有設定全部失效
        val json = """[{"id":"a","provider":"GROQ","key":"k"}]"""
        assertTrue(SttApiStore.decode(json).single().enabled)
    }

    @Test
    fun `壞掉的 JSON 回空清單而不是崩潰`() {
        // 設定損毀時應該退回「沒有專屬供應商」，轉寫會走 LLM 內建端點，
        // 使用者至少還能溝通。
        assertTrue(SttApiStore.decode("{{{ not json").isEmpty())
        assertTrue(SttApiStore.decode("").isEmpty())
    }

    @Test
    fun `未知的供應商名稱有安全預設值`() {
        val json = """[{"id":"a","provider":"SOME_REMOVED_VENDOR","key":"k"}]"""
        assertEquals(SttProvider.GROQ, SttApiStore.decode(json).single().provider)
    }

    @Test
    fun `每個供應商都有端點與模型的預設值`() {
        // 少一個就會在執行期送出空 URL，而錯誤訊息不會指向這裡
        SttProvider.entries.forEach { p ->
            assertTrue("${p.name} 缺少端點", p.defaultEndpoint.startsWith("http"))
            assertTrue("${p.name} 缺少預設模型", p.defaultModel.isNotBlank())
            assertTrue("${p.name} 缺少金鑰說明", p.keyHint.isNotBlank())
        }
    }
}
