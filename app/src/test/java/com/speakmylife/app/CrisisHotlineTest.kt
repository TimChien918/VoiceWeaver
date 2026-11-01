package com.speakmylife.app

import com.speakmylife.app.data.AppLanguage
import com.speakmylife.app.safety.CrisisHotlineDirectory
import com.speakmylife.app.ui.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 求救專線的挑選邏輯。
 *
 * 這是**這個 App 裡出錯代價最高的一段程式**：號碼挑錯，使用者在最壞的時刻按下
 * 求救鍵，電話撥不通，而且他會以為求救已經發出去了。原本兩支號碼是寫死的
 * 1925／119，人在美國按下去等於什麼都沒做。
 *
 * 測得到的是「挑選與退路的行為」；**測不到「這支號碼在當地真的接得通」**——
 * 那要靠人去查證，見 CrisisHotlineDirectory 的號碼表註解。
 */
class CrisisHotlineTest {

    private var savedLang: AppLanguage = AppLanguage.En

    @Before fun setUp() {
        savedLang = I18n.lang
        CrisisHotlineDirectory.resetForTest()
    }

    @After fun tearDown() {
        I18n.lang = savedLang
        CrisisHotlineDirectory.resetForTest()
    }

    @Test fun `美國拿到 988 與 911`() {
        val h = CrisisHotlineDirectory.forCountry("US")
        assertEquals("988", h.crisis.number)
        assertEquals("911", h.emergency.number)
    }

    @Test fun `台灣拿到 1925 與 119`() {
        val h = CrisisHotlineDirectory.forCountry("TW")
        assertEquals("1925", h.crisis.number)
        assertEquals("119", h.emergency.number)
    }

    @Test fun `國別大小寫與空白都要容忍`() {
        listOf("us", " US ", "Us").forEach { cc ->
            assertEquals("「$cc」應該對到美國", "911", CrisisHotlineDirectory.forCountry(cc).emergency.number)
        }
    }

    /**
     * 判不出國別時**不可以留白**——危機當下沒有按鈕等於沒有出口。
     * 退回介面語言對應的國家。
     */
    @Test fun `判不出國別時退回介面語言`() {
        I18n.lang = AppLanguage.En
        assertEquals("911", CrisisHotlineDirectory.forCountry(null).emergency.number)
        I18n.lang = AppLanguage.ZhTW
        assertEquals("119", CrisisHotlineDirectory.forCountry(null).emergency.number)
        assertEquals("1925", CrisisHotlineDirectory.forCountry(null).crisis.number)
    }

    @Test fun `不認得的國別也退回介面語言而不是崩潰`() {
        I18n.lang = AppLanguage.En
        val h = CrisisHotlineDirectory.forCountry("ZZ")
        assertEquals("988", h.crisis.number)
        assertFalse(CrisisHotlineDirectory.hasEntryFor("ZZ"))
    }

    /**
     * 這是最原始的那個 bug：英文介面 + 人在美國，卻拿到台灣號碼。
     */
    @Test fun `英文介面在美國絕不會拿到台灣號碼`() {
        I18n.lang = AppLanguage.En
        val h = CrisisHotlineDirectory.forCountry("US")
        assertNotEquals("1925", h.crisis.number)
        assertNotEquals("119", h.emergency.number)
    }

    /**
     * 定位判出的國別要勝過介面語言：台灣人用中文介面去美國，
     * 該撥的是 911 而不是 119。
     */
    @Test fun `定位國別勝過介面語言`() {
        I18n.lang = AppLanguage.ZhTW
        CrisisHotlineDirectory.rememberCountry("US")
        val h = CrisisHotlineDirectory.current()
        assertEquals("988", h.crisis.number)
        assertEquals("911", h.emergency.number)
    }

    @Test fun `空的國別不會蓋掉已經記住的`() {
        CrisisHotlineDirectory.rememberCountry("US")
        CrisisHotlineDirectory.rememberCountry(null)
        CrisisHotlineDirectory.rememberCountry("  ")
        assertEquals("US", CrisisHotlineDirectory.observedCountry())
    }

    /**
     * 號碼一定要出現在按鈕文字上。萬一國別判錯，照顧者要能一眼看出號碼不對；
     * 只寫「求救專線」的話，錯了也沒人會發現。
     */
    @Test fun `按鈕文字一定含號碼`() {
        I18n.lang = AppLanguage.En
        val h = CrisisHotlineDirectory.forCountry("US")
        assertTrue("按鈕文字要含號碼：${h.crisis.label()}", h.crisis.label().contains("988"))
        assertTrue("按鈕文字要含號碼：${h.emergency.label()}", h.emergency.label().contains("911"))
        assertTrue("用途要翻成英文：${h.emergency.label()}", h.emergency.label().contains("Emergency"))
    }

    @Test fun `每個支援國家都有兩支非空號碼`() {
        CrisisHotlineDirectory.supportedCountries().forEach { cc ->
            val h = CrisisHotlineDirectory.forCountry(cc)
            assertTrue("$cc 的危機專線是空的", h.crisis.number.isNotBlank())
            assertTrue("$cc 的緊急號碼是空的", h.emergency.number.isNotBlank())
            assertEquals(cc, h.countryCode)
        }
    }

    /** 四種介面語言都要能退回到一個真的存在的國家，不能拋例外。 */
    @Test fun `四種介面語言都有對應國家`() {
        AppLanguage.entries.forEach { lang ->
            I18n.lang = lang
            val h = CrisisHotlineDirectory.forCountry(null)
            assertTrue("$lang 退不到有效號碼", h.emergency.number.isNotBlank())
        }
    }
}
