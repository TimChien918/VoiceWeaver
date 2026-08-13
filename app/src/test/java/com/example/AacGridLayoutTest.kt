package com.example

import androidx.compose.ui.unit.dp
import com.example.data.SeverityMode
import com.example.ui.AAC_CARD_FLOOR
import com.example.ui.aacCardMinSize
import com.example.ui.aacGridColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圖卡格線的欄數計算。
 *
 * 為什麼需要這個測試：圖卡是正方形（`aspectRatio(1f)`），所以**邊長完全由寬度
 * 決定**。橫向手機是「又寬又矮」（實測 933×387dp，圖卡區只剩約 290dp 高），
 * 只看寬度就會算出 164dp 的大卡片，然後連第一列都露不完整——畫面上是一大片白
 * 配半張卡。這裡驗的是「高度不夠時會自動多開幾欄把卡片縮小」，
 * 以及「不會為了塞進去把卡片縮到點不到」。
 */
class AacGridLayoutTest {

    private val gap = 12.dp

    /** 給定欄數時一張卡的邊長（與實作同一條算式）。 */
    private fun cardSize(width: Float, cols: Int) = (width - 12f * (cols - 1)) / cols

    // ── 直向：不該因為這次改動而改變 ──────────────────────────────────

    @Test fun `直向手機的欄數維持原本的樣子`() {
        // 約 393×720dp 的直向手機，圖卡區高度充裕
        val cols = aacGridColumns(
            availableWidth = 360.dp,
            availableHeight = 560.dp,
            preferredCardSize = aacCardMinSize(SeverityMode.Moderate),
            gap = gap,
        )
        assertEquals("中度在直向應該是 2 欄", 2, cols)
    }

    @Test fun `輕度在直向是三欄`() {
        val cols = aacGridColumns(360.dp, 560.dp, aacCardMinSize(SeverityMode.Mild), gap)
        assertEquals(3, cols)
    }

    // ── 橫向：這次要修的情境 ────────────────────────────────────────

    /**
     * 實測數值：橫向 2448×1080px @420dpi → 933×387dp，
     * 雙欄版面右側圖卡區約 516dp 寬、290dp 高。
     */
    @Test fun `橫向下第一列一定要看得完整`() {
        val w = 516.dp
        val h = 290.dp
        val cols = aacGridColumns(w, h, aacCardMinSize(SeverityMode.Moderate), gap)
        val card = cardSize(516f, cols)
        assertTrue("卡片 ${card}dp 高，比可用高度 290dp 還高，第一列會被切掉", card <= 290f)
    }

    /**
     * 很矮的視窗：卡片一定要縮到一列放得下。
     * 這是「一大片白配半張卡」的直接成因。
     */
    @Test fun `視窗很矮時會多開欄把卡片縮小`() {
        val w = 516.dp
        val hTall = 400.dp
        val hShort = 120.dp
        val pref = aacCardMinSize(SeverityMode.Moderate)
        val tall = aacGridColumns(w, hTall, pref, gap)
        val short = aacGridColumns(w, hShort, pref, gap)
        assertTrue("矮視窗應該開更多欄（$tall → $short）", short > tall)
        assertTrue("縮完仍要放得下一列", cardSize(516f, short) <= 120f)
    }

    /** 高度充裕時不該因為這次改動而多開欄——大卡片是刻意的。 */
    @Test fun `高度充裕時維持嚴重程度想要的大小`() {
        val pref = aacCardMinSize(SeverityMode.Moderate)
        val cols = aacGridColumns(516.dp, 900.dp, pref, gap)
        assertTrue("卡片不該小於嚴重程度要求的 ${pref.value}dp", cardSize(516f, cols) >= pref.value)
    }

    // ── 底線：不能為了塞進去把卡片縮到點不到 ──────────────────────────

    @Test fun `卡片不會小於可點擊的底線`() {
        // 極端：很寬很矮
        val cols = aacGridColumns(900.dp, 100.dp, aacCardMinSize(SeverityMode.Severe), gap)
        val card = cardSize(900f, cols)
        assertTrue("卡片 ${card}dp 已小於底線 ${AAC_CARD_FLOOR.value}dp", card >= AAC_CARD_FLOOR.value)
    }

    @Test fun `重度模式在直向仍然是大卡片`() {
        val cols = aacGridColumns(360.dp, 620.dp, aacCardMinSize(SeverityMode.Severe), gap)
        assertEquals("重度要維持一次看一張", 1, cols)
    }

    // ── 退化輸入不可以崩 ────────────────────────────────────────────

    @Test fun `量不到高度時只照寬度算`() {
        val cols = aacGridColumns(360.dp, 0.dp, aacCardMinSize(SeverityMode.Moderate), gap)
        assertEquals(2, cols)
    }

    @Test fun `寬度為零時回一欄而不是除以零`() {
        assertEquals(1, aacGridColumns(0.dp, 300.dp, 168.dp, gap))
    }

    @Test fun `欄數永遠至少一欄`() {
        listOf(10.dp, 50.dp, 120.dp, 400.dp, 1200.dp).forEach { w ->
            listOf(0.dp, 40.dp, 200.dp, 900.dp).forEach { h ->
                val c = aacGridColumns(w, h, 168.dp, gap)
                assertTrue("寬 $w 高 $h 算出 $c 欄", c >= 1)
            }
        }
    }

    @Test fun `欄數有上限不會變成一排小圖示`() {
        val cols = aacGridColumns(4000.dp, 60.dp, 110.dp, gap)
        assertTrue("欄數 $cols 過多", cols <= 8)
    }

    /** 字級放大時卡片要跟著變大，欄數自然變少——這是既有行為，不能被這次改動吃掉。 */
    @Test fun `字級放大時直向欄數變少`() {
        val normal = aacGridColumns(360.dp, 560.dp, aacCardMinSize(SeverityMode.Mild, 1f), gap)
        val big = aacGridColumns(360.dp, 560.dp, aacCardMinSize(SeverityMode.Mild, 2f), gap)
        assertTrue("字級放大後欄數應該變少（$normal → $big）", big < normal)
    }
}
