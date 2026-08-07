package com.example

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SeverityMode
import com.example.ui.aacCardMinSize
import com.example.ui.aacScaleFor
import com.example.data.MAX_FONT_SCALE
import com.example.data.MIN_FONT_SCALE
import com.example.ui.theme.linearFontDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「字級調了沒反應」的回歸測試。
 *
 * Android 14 起 `.sp` 走非線性字級：小字放大、大字幾乎不動。
 * 平台預設在倍率 1.8 時，32sp 只放大到 1.15 倍、100sp 完全不動——
 * 而這個 App 的重度模式圖卡正好都是那個尺寸，所以使用者把字級拉到最大，
 * 畫面上一點差別都沒有。
 */
class FontScaleTest {

    private fun dpOf(sp: Float, scale: Float) =
        with(linearFontDensity(3f, scale)) { sp.sp.toDp().value }

    @Test
    fun `平台預設會把大字壓回去——這就是原本的 bug`() {
        // 先確認問題真的存在，不然這一整組測試是在防一個不存在的東西。
        val platform = Density(density = 3f, fontScale = 1.8f)
        val big = with(platform) { 100f.sp.toDp().value }
        assertEquals("平台在 1.8 倍下 100sp 完全沒放大", 100f, big, 1f)
    }

    @Test
    fun `我們的字級一律線性——選幾倍就是幾倍`() {
        listOf(1.25f, 1.55f, 1.8f, 2.5f, 3.0f).forEach { scale ->
            listOf(10f, 16f, 32f, 60f, 100f).forEach { sp ->
                assertEquals(
                    "${sp}sp 在 ${scale} 倍下應該剛好放大 $scale 倍",
                    sp * scale, dpOf(sp, scale), 0.01f,
                )
            }
        }
    }

    @Test
    fun `重度模式的大字在最大倍率下真的會變大`() {
        // 重度圖卡的 emoji 是 40 * 2.1 ≈ 84sp，就是被平台壓到 1.0 倍的那一段。
        val emojiSp = 40f * aacScaleFor(SeverityMode.Severe)
        val normal = dpOf(emojiSp, 1f)
        val biggest = dpOf(emojiSp, MAX_FONT_SCALE)
        assertTrue("最大字級只放大到 ${biggest / normal} 倍，使用者看不出差別",
            biggest / normal >= 2.5f)
    }

    @Test
    fun `最大倍率要夠大`() {
        // 重度視障使用者要的是「整個螢幕一個字」。1.8 倍不夠。
        assertTrue("上限 $MAX_FONT_SCALE 太小", MAX_FONT_SCALE >= 3.0f)
        assertTrue(MIN_FONT_SCALE < 1.0f)
    }

    @Test
    fun `儲存時的上限要跟滑桿一致`() {
        // 原本滑桿放到 3.0、但 SettingsRepository 還 clamp 在 1.8——
        // 使用者拉到底放開，數字自己跳回 1.8，看起來像設定存不起來。
        // 兩邊各寫死一個數字就是這個 bug 的來源，所以只准有一組常數。
        assertEquals(3.0f, MAX_FONT_SCALE, 0f)
        assertEquals(0.8f, MIN_FONT_SCALE, 0f)
    }

    @Test
    fun `重度模式的預設字級要在允許範圍內`() {
        // 預設值超過 clamp 上限的話會被默默改掉，切到重度時字級跟使用者以為的不一樣。
        listOf(1.6f, 2.4f).forEach {
            assertTrue("$it 超出 $MIN_FONT_SCALE..$MAX_FONT_SCALE",
                it in MIN_FONT_SCALE..MAX_FONT_SCALE)
        }
        // 而且重度一定要比中度大，否則兩個模式看起來一樣
        assertTrue(2.4f > 1.6f)
    }

    @Test
    fun `dp 換回 sp 是可逆的`() {
        // Compose 內部（例如 lineHeight 換算）會來回轉，不可逆會累積誤差。
        with(linearFontDensity(3f, 2.4f)) {
            assertEquals(17f, 17f.sp.toDp().toSp().value, 0.01f)
            assertEquals(24f, 24.dp.toSp().toDp().value, 0.01f)
        }
    }

    // ── 卡片尺寸 ──────────────────────────────────────────────────────

    @Test
    fun `字級放大時圖卡也要跟著長大`() {
        // 卡片是固定 dp、裡面的字是 sp。字級 3 倍時 36sp 的 emoji 會漲到 108dp，
        // 塞不進 110dp 的卡片——字被裁掉，看起來又像「調了沒反應」。
        val normal = aacCardMinSize(SeverityMode.Mild, 1f).value
        val huge = aacCardMinSize(SeverityMode.Mild, 3f).value
        assertTrue("卡片 $normal → $huge 沒跟著字放大", huge >= normal * 2.5f)
    }

    @Test
    fun `字級縮小時卡片不會跟著縮`() {
        // 字調小是為了「一頁看更多」，不是為了讓按鈕小到點不到。
        assertEquals(
            aacCardMinSize(SeverityMode.Mild, 1f).value,
            aacCardMinSize(SeverityMode.Mild, 0.8f).value,
            0f,
        )
    }

    @Test
    fun `預設字級下卡片尺寸維持原樣`() {
        assertEquals(110f, aacCardMinSize(SeverityMode.Mild).value, 0f)
        assertEquals(168f, aacCardMinSize(SeverityMode.Moderate).value, 0f)
        assertEquals(240f, aacCardMinSize(SeverityMode.Severe).value, 0f)
    }
}
