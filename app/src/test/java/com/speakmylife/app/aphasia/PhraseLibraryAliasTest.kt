package com.speakmylife.app.aphasia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.speakmylife.app.aphasia.data.local.PhraseLibrarySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 句庫本身的完整性，以及「改過關鍵字之後舊的還認得」。
 *
 * 樣板只存 keyword 字串，圖示、預合成音檔、「已啟用」的判定全都靠它回查句庫。
 * 改寫關鍵字時漏掉 aliases 的話，已經錄好的那些會同時發生三件事：
 * 圖示變空白、播出的變成 TTS 而不是預合成的聲音、還在「加入新的」清單裡
 * 重新冒出來叫他再錄一次——而他其實什麼都沒弄丟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhraseLibraryAliasTest {

    private fun library(): List<PhraseLibrarySource.Entry> = runBlocking {
        PhraseLibrarySource(ApplicationProvider.getApplicationContext<Context>()).load()
    }

    @Test
    fun `句庫讀得到而且每一筆都完整`() {
        val lib = library()
        assertTrue("句庫不該是空的", lib.isNotEmpty())
        lib.forEach {
            assertTrue("${it.id} 缺 keyword", it.keyword.isNotBlank())
            assertTrue("${it.id} 缺 sentence", it.sentence.isNotBlank())
            assertTrue("${it.id} 缺 category", it.category.isNotBlank())
        }
    }

    @Test
    fun `關鍵字與別名在全庫唯一`() {
        // 撞名的話回查會指到別筆，使用者會看到別的句子的圖示
        val all = library().flatMap { listOf(it.keyword) + it.aliases }.map { it.lowercase() }
        assertEquals("有重複的關鍵字/別名：${all.groupBy { it }.filter { it.value.size > 1 }.keys}",
            all.size, all.toSet().size)
    }

    @Test
    fun `id 唯一`() {
        // id 就是圖示與音檔的檔名，撞了會拿到別人的圖
        val ids = library().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `改寫過的關鍵字仍然查得到原本那一筆`() {
        // 這幾個是實際改過的（Basic needs 改成「講出來時連續的一段」）
        val lib = library()
        mapOf(
            "bathroom" to "bathroom",
            "need water" to "water",
            "need food" to "food",
            "want rest" to "rest",
            "too cold" to "cold",
            "too hot" to "hot",
            "sit down" to "sit",
        ).forEach { (oldKeyword, expectedId) ->
            val hit = lib.firstOrNull { it.matches(oldKeyword) }
            assertEquals("舊關鍵字「$oldKeyword」應該還指得到 $expectedId", expectedId, hit?.id)
        }
    }

    @Test
    fun `新的關鍵字也查得到`() {
        val lib = library()
        assertEquals("bathroom", lib.firstOrNull { it.matches("go to the bathroom") }?.id)
        assertEquals("water", lib.firstOrNull { it.matches("need some water") }?.id)
    }

    @Test
    fun `比對忽略大小寫與前後空白`() {
        val lib = library()
        assertEquals("bathroom", lib.firstOrNull { it.matches("  GO TO THE BATHROOM ") }?.id)
    }

    @Test
    fun `不相干的字串不會誤中`() {
        assertFalse(library().any { it.matches("完全不相干的一句話") })
        assertFalse(library().any { it.matches("") })
    }

    @Test
    fun `每一筆都有對應的圖示與預合成音檔`() {
        // 缺了不會崩，但圖示會空白、聲音會退回 TTS——兩個都只有在真機上才看得出來
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val icons = ctx.assets.list("phrase_icons").orEmpty().toSet()
        val audio = ctx.assets.list("phrase_audio").orEmpty().toSet()
        library().forEach {
            assertTrue("${it.id} 缺圖示", "${it.id}.png" in icons)
            assertTrue("${it.id} 缺音檔", "${it.id}.m4a" in audio)
        }
    }
}
