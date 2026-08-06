package com.example.aphasia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.aphasia.data.local.IntentTreeSource
import com.example.aphasia.domain.model.IntentNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 意圖樹的 asset。
 *
 * 這個檔案是純資料，很容易在編輯時打壞而編譯器完全不會抱怨——
 * 壞掉的症狀是使用者打開畫面看到一片空白，沒有任何錯誤訊息。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IntentTreeTest {

    private fun load() = runBlocking {
        IntentTreeSource(ApplicationProvider.getApplicationContext<Context>()).load()
    }

    private fun IntentNode.flatten(): List<IntentNode> = listOf(this) + children.flatMap { it.flatten() }

    @Test
    fun `內建的意圖樹讀得進來`() {
        val tree = load()
        assertTrue("應有頂層分類", tree.size >= 5)
        assertTrue("每個頂層都該有子項", tree.all { it.children.isNotEmpty() })
    }

    @Test
    fun `id 不重複`() {
        // 重複的 id 會讓 LazyVerticalGrid 的 key 撞在一起，畫面出現重繪錯亂
        val all = load().flatMap { it.flatten() }
        val ids = all.map { it.id }
        assertEquals("有重複的 id：${ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}",
            ids.size, ids.toSet().size)
    }

    @Test
    fun `每個葉節點都有非空的碎詞`() {
        // 葉節點的 fragment 是實際要送進 LLM 的字。空的話使用者點了會什麼都沒發生
        val leaves = load().flatMap { it.flatten() }.filter { it.isLeaf }
        val broken = leaves.filter { it.fragment.isBlank() }
        assertTrue("這些葉節點沒有碎詞：${broken.map { it.id }}", broken.isEmpty())
        assertTrue("葉節點數量偏少：${leaves.size}", leaves.size >= 30)
    }

    @Test
    fun `每個節點都有標籤`() {
        val unlabeled = load().flatMap { it.flatten() }.filter { it.label.isBlank() }
        assertTrue("這些節點沒有標籤：${unlabeled.map { it.id }}", unlabeled.isEmpty())
    }

    @Test
    fun `涵蓋醫療溝通的基本需求`() {
        // 這些是失語症患者最需要能立刻表達的東西。少了任何一項都是產品缺陷，
        // 不是「內容還沒補齊」。
        val fragments = load().flatMap { it.flatten() }.map { it.fragment.lowercase() }.toSet()
        listOf("pain", "water", "bathroom", "help", "medication", "doctor", "yes", "no")
            .forEach { assertTrue("缺少必要的碎詞「$it」", it in fragments) }
    }

    @Test
    fun `讀不到 asset 時回空清單而不是崩掉`() {
        // 其他輸入方式（發聲比對、打字）都還能用，
        // 使用者不該因為一個 asset 壞掉就完全無法溝通
        val tree = runBlocking {
            IntentTreeSource(ApplicationProvider.getApplicationContext<Context>())
                .load("does_not_exist.json")
        }
        assertEquals(0, tree.size)
    }
}
