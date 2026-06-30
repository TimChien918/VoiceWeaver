package com.example.ui

import com.example.data.AppLanguage

/**
 * 以「繁體中文原文」為鍵的執行期翻譯層，補足 Compose 程式碼裡寫死、未走 strings.xml 的字串。
 *
 * 為何不全用 strings.xml：很多字串在非 composable 範圍（lambda/coroutine 的狀態指派、ViewModel
 * 訊息）產生，`stringResource()` 只能在 composable 內呼叫；`I18n.tr()` 是純函式、哪裡都能用。
 *
 * 用法：
 *   Text(I18n.tr("設為我家"))
 *   status = I18n.tr("目前地點：%s").format(addr)
 *
 * 語言由 [LocaleProvider] 在切換時設定 [lang]；繁中直接回原文，其餘查表、查無則回原文（安全退回）。
 *
 * 注意：只翻「顯示文字」。用來比對中文輸入的關鍵字（危機偵測、地點比對、情境/文法規則）
 *       不可進這張表，否則功能會壞。
 */
object I18n {
    @Volatile var lang: AppLanguage = AppLanguage.ZhTW

    /** 取翻譯；繁中或查無 → 回原文。 */
    fun tr(zh: String): String {
        if (lang == AppLanguage.ZhTW) return zh
        val row = M[zh] ?: return zh
        val i = when (lang) {
            AppLanguage.En -> 0
            AppLanguage.Ja -> 1
            AppLanguage.Ko -> 2
            else -> return zh
        }
        return row.getOrNull(i)?.takeIf { it.isNotBlank() } ?: zh
    }

    // 鍵 = 繁中原文；值 = [en, ja, ko]
    private val M: Map<String, Array<String>> = mapOf(
        // ── 主畫面 VoiceWeaverScreen ──
        "🏥 快速求救（一鍵發聲）" to arrayOf("🏥 Quick help (tap to speak)", "🏥 緊急ヘルプ（タップで発声）", "🏥 빠른 도움 (탭하여 발성)"),
        "😖 我痛" to arrayOf("😖 Pain", "😖 痛い", "😖 아파요"),
        "我很痛，請幫幫我。" to arrayOf("I'm in pain, please help me.", "とても痛いです、助けてください。", "너무 아파요, 도와주세요."),
        "💧 喝水" to arrayOf("💧 Water", "💧 水", "💧 물"),
        "我想要喝水。" to arrayOf("I want to drink water.", "水が飲みたいです。", "물을 마시고 싶어요."),
        "🚻 廁所" to arrayOf("🚻 Toilet", "🚻 トイレ", "🚻 화장실"),
        "我想上廁所，請帶我去。" to arrayOf("I need the toilet, please take me.", "トイレに行きたいです、連れて行ってください。", "화장실에 가고 싶어요, 데려다 주세요."),
        "⭐ 我的最愛（點一下朗讀）" to arrayOf("⭐ Favorites (tap to speak)", "⭐ お気に入り（タップで読み上げ）", "⭐ 즐겨찾기 (탭하여 읽기)"),
        "目前地點：%s" to arrayOf("Current place: %s", "現在地：%s", "현재 위치: %s"),
        "設定中…" to arrayOf("Setting…", "設定中…", "설정 중…"),
        "✅ 已設為我家：%s" to arrayOf("✅ Set as home: %s", "✅ 自宅に設定：%s", "✅ 집으로 설정: %s"),
        "❌ 沒抓到 GPS" to arrayOf("❌ No GPS fix", "❌ GPS を取得できません", "❌ GPS를 찾지 못함"),
        "設為我家" to arrayOf("Set as home", "自宅に設定", "집으로 설정"),
        "看地圖" to arrayOf("View map", "地図を見る", "지도 보기"),
        "更多物品" to arrayOf("More items", "もっと見る", "더 보기"),
        "開啟預覽" to arrayOf("Open preview", "プレビューを開く", "미리보기 열기"),
        "🃏 大圖卡模式（適合用圖說話）" to arrayOf("🃏 Big-card mode (talk with pictures)", "🃏 大カードモード（絵で話す）", "🃏 큰 카드 모드 (그림으로 말하기)"),
        "意圖示意圖" to arrayOf("Intent illustration", "意図イラスト", "의도 일러스트"),
        "正在生成專屬圖卡…" to arrayOf("Generating your card…", "カードを生成中…", "카드를 생성 중…"),
        "不對？唸原始輸入：「%s」" to arrayOf("Wrong? Speak the original: \"%s\"", "違う？元の入力を読む：「%s」", "틀렸나요? 원본 읽기: \"%s\""),
        "複製" to arrayOf("Copy", "コピー", "복사"),
        "分享" to arrayOf("Share", "共有", "공유"),
        "分享句子" to arrayOf("Share sentence", "文を共有", "문장 공유"),
        "收藏" to arrayOf("Save", "お気に入り", "즐겨찾기"),
        "攝影預覽" to arrayOf("Camera preview", "カメラプレビュー", "카메라 미리보기"),
        "前後一起拍會按一次連續擷取兩顆鏡頭；預覽先顯示後攝影機。" to arrayOf(
            "Tapping captures both cameras in sequence; preview shows the rear camera first.",
            "一度で前後カメラを連続撮影；プレビューは背面カメラを先に表示。",
            "한 번에 전후 카메라를 연속 촬영; 미리보기는 후면 카메라 먼저 표시."),
        "關閉視窗後會立即釋放相機。" to arrayOf(
            "The camera is released as soon as this window closes.",
            "ウィンドウを閉じるとすぐにカメラを解放します。",
            "창을 닫으면 즉시 카메라를 해제합니다."),
        "選擇看到的物品" to arrayOf("Pick what you see", "見えた物を選ぶ", "보이는 물건 선택"),
        "已選 %d" to arrayOf("%d selected", "%d 個選択", "%d개 선택"),
        "完成" to arrayOf("Done", "完了", "완료"),
        "選擇%s情境" to arrayOf("Pick a %s context", "%sの場面を選ぶ", "%s 상황 선택"),
    )
}
