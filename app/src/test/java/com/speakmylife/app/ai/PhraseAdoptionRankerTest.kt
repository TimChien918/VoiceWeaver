package com.speakmylife.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 採納紀錄的行為。
 *
 * 這裡最重要的不是「有沒有加分」，而是**加分不能蓋過 AI 的判斷**——
 * 讓歷史主導排序會把使用者困在自己的舊說法裡，他這次想講的新句子就永遠
 * 排不到前面，而那正是這個 App 該幫他做到的事。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhraseAdoptionRankerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = PhraseAdoptionRanker.clear(ctx)

    @Test
    fun `沒採納過的說法沒有任何加成`() {
        assertEquals(0.0, PhraseAdoptionRanker.boostFor(ctx, "我要喝水"), 0.0)
    }

    @Test
    fun `採納過就會有加成`() {
        PhraseAdoptionRanker.recordAdopted(ctx, "我要喝水")
        assertTrue(PhraseAdoptionRanker.boostFor(ctx, "我要喝水") > 0.0)
    }

    @Test
    fun `加成有上限不會蓋過信心度`() {
        // 這是整個機制的安全閥。採納一百次也不能讓一個 AI 沒把握的說法
        // 壓過一個明顯更有把握的新候選。
        repeat(100) { PhraseAdoptionRanker.recordAdopted(ctx, "我要喝水") }
        val boost = PhraseAdoptionRanker.boostFor(ctx, "我要喝水")
        assertTrue("加成 $boost 應該有上限", boost <= 0.12 + 1e-9)

        // 具體情境：信心 0.55 且採納過很多次 vs 信心 0.75 但從沒採納過
        assertTrue(
            "信心明顯較高的新說法仍應勝出",
            0.55 + boost < 0.75 + PhraseAdoptionRanker.boostFor(ctx, "來一杯水"),
        )
    }

    @Test
    fun `採納越多次加成越高但邊際遞減`() {
        PhraseAdoptionRanker.recordAdopted(ctx, "甲")
        val once = PhraseAdoptionRanker.boostFor(ctx, "甲")
        repeat(4) { PhraseAdoptionRanker.recordAdopted(ctx, "甲") }
        val fiveTimes = PhraseAdoptionRanker.boostFor(ctx, "甲")
        repeat(15) { PhraseAdoptionRanker.recordAdopted(ctx, "甲") }
        val twentyTimes = PhraseAdoptionRanker.boostFor(ctx, "甲")

        assertTrue("次數多應該分數高", once < fiveTimes && fiveTimes < twentyTimes)
        // 前幾次的邊際效果要明顯大於後面——講 20 次的不該比講 5 次的多壓別人 4 倍
        assertTrue(
            "邊際效果應遞減：$once → $fiveTimes → $twentyTimes",
            (twentyTimes - fiveTimes) < (fiveTimes - once),
        )
    }

    @Test
    fun `標點與空白不影響比對`() {
        PhraseAdoptionRanker.recordAdopted(ctx, "我要喝水。")
        assertTrue(PhraseAdoptionRanker.boostFor(ctx, "我要喝水") > 0.0)
        assertTrue(PhraseAdoptionRanker.boostFor(ctx, " 我要喝水 ！") > 0.0)
    }

    @Test
    fun `意思相反的句子不會被混為一談`() {
        // 正規化只砍標點與空白，刻意不做關鍵詞萃取——
        // 那樣會把「我要喝水」跟「我不要喝水」併成同一筆，
        // 在這個 App 裡那是會害人講反話的錯誤。
        PhraseAdoptionRanker.recordAdopted(ctx, "我要喝水")
        assertEquals(0.0, PhraseAdoptionRanker.boostFor(ctx, "我不要喝水"), 0.0)
    }

    @Test
    fun `空字串不會被記錄`() {
        PhraseAdoptionRanker.recordAdopted(ctx, "   ")
        assertEquals(0.0, PhraseAdoptionRanker.boostFor(ctx, "   "), 0.0)
    }

    @Test
    fun `紀錄會存下來`() {
        PhraseAdoptionRanker.recordAdopted(ctx, "我要喝水")
        val before = PhraseAdoptionRanker.boostFor(ctx, "我要喝水")
        assertTrue(before > 0.0)
        // 同一個 process 內至少要保持一致；跨行程由 SharedPreferences 負責
        assertEquals(before, PhraseAdoptionRanker.boostFor(ctx, "我要喝水"), 1e-9)
    }

    @Test
    fun `清除之後回到沒有紀錄的狀態`() {
        PhraseAdoptionRanker.recordAdopted(ctx, "我要喝水")
        PhraseAdoptionRanker.clear(ctx)
        assertEquals(0.0, PhraseAdoptionRanker.boostFor(ctx, "我要喝水"), 0.0)
    }
}
