package com.example

import com.example.demo.DemoController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 演示腳本的結構。
 *
 * 演示是在評審面前（或錄影）播的，跟網頁版是同一套七段敘事。這裡釘住三件事：
 * 段落順序不能被改亂、四種語言每一句都要有旁白（少一句就是一段沉默或一句別的語言）、
 * 片頭片尾的標題卡要在。
 */
class DemoScriptTest {

    private val langs = listOf("zh", "en", "ja", "ko")
    private val cjk = Regex("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7a3]")

    @Test fun `七段依設計書順序`() {
        langs.forEach { l ->
            assertEquals(
                listOf("intro", "semantic", "a11y", "rehab", "crisis", "voice", "outro"),
                DemoController.units(l).map { it.id },
            )
        }
    }

    @Test fun `四種語言的步數一致而且每一句都有旁白`() {
        val counts = langs.map { l -> DemoController.units(l).map { it.steps.size } }
        assertTrue("各語言的步數要一樣（同一份腳本）", counts.distinct().size == 1)
        langs.forEach { l ->
            DemoController.units(l).forEach { u ->
                assertTrue("$l/${u.id} 單元名稱是空的", u.title.isNotBlank())
                u.steps.forEach { s -> assertTrue("$l/${u.id} 有一步沒有旁白", s.cap.isNotBlank()) }
            }
        }
    }

    @Test fun `英文旁白不能混進中日韓文字`() {
        // 英文語音引擎碰到漢字會整段跳過或唸成亂碼（品牌名在英文旁白裡只唸 Speak My Life）
        DemoController.units("en").flatMap { it.steps }.forEach { s ->
            assertFalse("英文旁白有中日韓文字：${s.cap}", cjk.containsMatchIn(s.cap))
        }
    }

    @Test fun `同一語言裡旁白不重複`() {
        // 預錄快取以句子為鍵；重複的句子會讓估時與實際播放對不上
        langs.forEach { l ->
            val caps = DemoController.units(l).flatMap { it.steps }.map { it.cap }
            assertEquals("$l 有重複的旁白", caps.size, caps.distinct().size)
        }
    }

    @Test fun `片頭有標題卡而且最後一句前收掉，片尾以終極目標收尾`() {
        langs.forEach { l ->
            val units = DemoController.units(l)
            val intro = units.first { it.id == "intro" }
            assertNotNull("$l 片頭第一步要有標題卡", intro.steps.first().card)
            assertTrue("$l 片頭最後一步要收掉標題卡", intro.steps.last().hideCard)
            val outro = units.first { it.id == "outro" }
            assertTrue(outro.steps.all { it.card != null })
            assertTrue(outro.steps.last().card!!.main.contains("Speak My Life"))
        }
        assertTrue(DemoController.units("zh").last().steps.last().cap.contains("也能被世界聽見"))
    }

    @Test fun `語意織句一次給三個候選句`() {
        langs.forEach { l -> assertEquals(3, DemoController.sample(l).candidates.size) }
    }

    @Test fun `危機段落的訊息明講是演示模擬`() {
        // 不能讓評審（或錄影的觀眾）以為真的送出了通報
        assertTrue(DemoController.sample("zh").sim.contains("未實際送出"))
        assertTrue(DemoController.sample("en").sim.contains("nothing sent"))
    }

    @Test fun `估時涵蓋每個單元`() {
        val all = DemoController.units("en").map { it.id }.toSet()
        val sum = all.sumOf { DemoController.estimateSeconds(setOf(it), narrated = true) }
        assertTrue(sum > 60)
    }
}
