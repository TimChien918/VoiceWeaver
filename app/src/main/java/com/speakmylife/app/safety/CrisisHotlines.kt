package com.speakmylife.app.safety

import com.speakmylife.app.data.AppLanguage
import com.speakmylife.app.ui.I18n

/**
 * 危機介入視窗要撥的兩支電話：自殺防治專線與緊急號碼。
 *
 * **為什麼需要這個檔案：** 原本這兩支號碼是寫死的 `1925` 與 `119`，而且英文標籤
 * 直譯成 "1925 helpline" / "119 emergency"。人在美國、用英文介面、正處於危機當下
 * 的使用者按下去，撥出的是兩支在美國不存在的號碼——畫面上的鎖定訊息還寫著
 * 「call your local crisis line」。**求救按鈕撥不通，比沒有按鈕更危險**，因為它
 * 讓人以為求救已經發出去了。
 *
 * ## 兩個刻意的設計決定
 *
 * **① 號碼一定顯示在按鈕上。** 標籤是「號碼 ＋ 用途」（"988 Crisis line"、
 * 「1925 安心專線」），不是只寫「求救專線」。萬一國別判斷錯了，照顧者一眼就能
 * 看出號碼不對；只寫用途的話，錯了也沒人發現。
 *
 * **② 判不出國別時不留空白。** 退回介面語言對應的國家（見 [byLanguage]）。
 * 空白按鈕在危機當下等於沒有出口；而因為①，猜錯至少是看得見的。
 */
data class Hotline(
    val number: String,
    /** I18n 的鍵（中文原文）。實際標籤是「號碼 空格 用途」。 */
    val purposeKey: String
) {
    /** 例：`"988 Crisis line"`、`"1925 安心專線"`。 */
    fun label(): String = "$number ${I18n.tr(purposeKey)}"
}

data class CrisisHotlines(
    /** 自殺防治／心理危機專線。 */
    val crisis: Hotline,
    /** 消防／醫療緊急號碼。 */
    val emergency: Hotline,
    /** 這組號碼屬於哪個國家（ISO 3166-1 alpha-2），供記錄與除錯用。 */
    val countryCode: String
)

object CrisisHotlineDirectory {

    private const val PURPOSE_CRISIS = "安心專線"
    private const val PURPOSE_EMERGENCY = "緊急"

    /**
     * 各國號碼表。
     *
     * **改這張表之前請先查證。** 這些號碼是要在最壞的時刻被撥出去的，寫錯不會有
     * 任何測試抓得到——`CrisisHotlineTest` 只能驗結構與退路，驗不了「這支號碼在
     * 當地真的接得通」。新增國家時附上查證來源與日期。
     *
     * - TW：1925 安心專線（衛福部）、119 消防。
     * - US：988 Suicide & Crisis Lifeline、911。
     * - JP：0570-064-556 こころの健康相談統一ダイヤル、119。
     * - KR：109 자살예방 상담전화、119。
     */
    private val BY_COUNTRY: Map<String, CrisisHotlines> = mapOf(
        "TW" to CrisisHotlines(
            crisis = Hotline("1925", PURPOSE_CRISIS),
            emergency = Hotline("119", PURPOSE_EMERGENCY),
            countryCode = "TW"
        ),
        "US" to CrisisHotlines(
            crisis = Hotline("988", PURPOSE_CRISIS),
            emergency = Hotline("911", PURPOSE_EMERGENCY),
            countryCode = "US"
        ),
        "JP" to CrisisHotlines(
            crisis = Hotline("0570-064-556", PURPOSE_CRISIS),
            emergency = Hotline("119", PURPOSE_EMERGENCY),
            countryCode = "JP"
        ),
        "KR" to CrisisHotlines(
            crisis = Hotline("109", PURPOSE_CRISIS),
            emergency = Hotline("119", PURPOSE_EMERGENCY),
            countryCode = "KR"
        ),
    )

    /** 表裡沒有這個國家時，用介面語言猜一個。四種介面語言各對到一個國家。 */
    private fun byLanguage(): CrisisHotlines {
        val cc = when (I18n.lang) {
            AppLanguage.En -> "US"
            AppLanguage.Ja -> "JP"
            AppLanguage.Ko -> "KR"
            AppLanguage.ZhTW -> "TW"
        }
        return BY_COUNTRY.getValue(cc)
    }

    /**
     * 最後一次由定位判定出來的國別。
     *
     * 放在這裡而不是 `LocationTracker` 的實例上，是因為取用的人湊不到那個實例：
     * `SafetyGuard` 是沒有 Context 的 object，卻要在提示詞裡寫出專線號碼。
     * 由 `LocationTracker` 單向寫入，其他人只讀。
     */
    @Volatile
    private var observedCountryCode: String? = null

    /** 由 `LocationTracker` 在每次判定出國別後呼叫。空值不覆蓋既有的。 */
    fun rememberCountry(countryCode: String?) {
        val cc = countryCode?.trim()?.uppercase().orEmpty()
        if (cc.isNotBlank()) observedCountryCode = cc
    }

    /** 用目前已知的國別取號碼。不做任何定位動作，可在任何執行緒同步呼叫。 */
    fun current(): CrisisHotlines = forCountry(observedCountryCode)

    /** 目前記住的國別（測試與除錯用）。 */
    fun observedCountry(): String? = observedCountryCode

    /**
     * 依國別取號碼。[countryCode] 為 null／空白／不在表內時退回介面語言。
     *
     * 這裡**不做任何網路或定位動作**——呼叫端負責拿國別（見
     * `LocationTracker.resolveCountryCode()`）。危機視窗要能立刻畫出來，
     * 不能卡在等 GPS。
     */
    fun forCountry(countryCode: String?): CrisisHotlines {
        val cc = countryCode?.trim()?.uppercase().orEmpty()
        return BY_COUNTRY[cc] ?: byLanguage()
    }

    /** 測試用：清掉記住的國別，讓每個測試從乾淨狀態開始。 */
    internal fun resetForTest() { observedCountryCode = null }

    /** 這個國家有沒有專屬號碼（false 表示 [forCountry] 會退回介面語言）。 */
    fun hasEntryFor(countryCode: String?): Boolean =
        countryCode?.trim()?.uppercase()?.let { BY_COUNTRY.containsKey(it) } == true

    /** 供測試列舉。 */
    internal fun supportedCountries(): Set<String> = BY_COUNTRY.keys
}
