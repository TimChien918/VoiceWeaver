package com.example

import com.example.demo.DemoController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 預錄旁白的重試。
 *
 * 為什麼需要這個測試：一段旁白約 60 句，每句要電腦合成 10～30 秒。原本沒有重試，
 * 只要**其中一句**掉包整支錄影就中止——而且是在使用者等完全部 60 句之後。
 * USB（adb reverse）幾乎不會掉，所以看起來像「只有插線才錄得起來」；走 Wi-Fi／
 * Tailscale／ngrok(Colab) 就三天兩頭失敗。
 *
 * 這裡驗的是「暫時性失敗會被重試救回來」與「該放棄時真的放棄」。
 */
class DemoPrerecordRetryTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 產生一個「合成成功」的假音檔（非空，才會被當成可用）。 */
    private fun okFile(name: String) = tmp.newFile(name).apply { writeText("fake-audio") }

    @Test fun `第一次就成功時不重試`() = runTest {
        var calls = 0
        val f = DemoController.prerecordWithRetry(
            line = "hello",
            record = { _, done -> calls++; done(okFile("a.wav")) },
            stillRunning = { true },
        )
        assertNotNull(f)
        assertEquals("成功就不該再打", 1, calls)
    }

    /**
     * 這是這次修正的核心情境：隧道剛拉起來時掉了兩次，第三次成功。
     * 修正前這一句會直接被放棄，然後整支演示中止。
     */
    @Test fun `前兩次失敗第三次成功仍拿得到檔案`() = runTest {
        var calls = 0
        val f = DemoController.prerecordWithRetry(
            line = "hello",
            record = { _, done ->
                calls++
                done(if (calls < 3) null else okFile("b.wav"))
            },
            stillRunning = { true },
        )
        assertNotNull("暫時性失敗應該被重試救回來", f)
        assertEquals(3, calls)
    }

    @Test fun `一直失敗就放棄並回 null`() = runTest {
        var calls = 0
        val f = DemoController.prerecordWithRetry(
            line = "hello",
            record = { _, done -> calls++; done(null) },
            stillRunning = { true },
        )
        assertNull(f)
        assertEquals("重試次數要有上限，不能無限打下去", 3, calls)
    }

    /** 合成「成功」但檔案是空的，等同失敗——播出來會是一段無聲。 */
    @Test fun `空檔案視為失敗`() = runTest {
        var calls = 0
        val f = DemoController.prerecordWithRetry(
            line = "hello",
            record = { _, done ->
                calls++
                done(if (calls < 2) tmp.newFile("empty$calls.wav") else okFile("c.wav"))
            },
            stillRunning = { true },
        )
        assertNotNull(f)
        assertEquals(2, calls)
    }

    /** 使用者按了停止就不要再打——不然停止之後還會卡好幾十秒。 */
    @Test fun `已停止時完全不呼叫合成`() = runTest {
        var calls = 0
        val f = DemoController.prerecordWithRetry(
            line = "hello",
            record = { _, done -> calls++; done(null) },
            stillRunning = { false },
        )
        assertNull(f)
        assertEquals(0, calls)
    }

    /** 停止發生在重試中途：不該再繼續打完剩下的次數。 */
    @Test fun `重試途中停止就不再往下打`() = runTest {
        var calls = 0
        var running = true
        val f = DemoController.prerecordWithRetry(
            line = "hello",
            record = { _, done -> calls++; running = false; done(null) },
            stillRunning = { running },
        )
        assertNull(f)
        assertEquals("第一次之後就停了，不該有第二次", 1, calls)
    }

    /** 回呼被呼叫兩次（TTS 引擎重複回呼）不可以讓協程崩掉。 */
    @Test fun `重複回呼不會出問題`() = runTest {
        val f = DemoController.prerecordWithRetry(
            line = "hello",
            record = { _, done -> val x = okFile("d.wav"); done(x); done(x) },
            stillRunning = { true },
        )
        assertNotNull(f)
    }
}
