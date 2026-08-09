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
    /** 預設英文；LocaleProvider 會在設定載入後覆寫。 */
    @Volatile var lang: AppLanguage = AppLanguage.En

    /**
     * 給 LLM 提示詞用的「輸出語言」名稱。
     *
     * 提示詞本身維持中文（模型對中文指令的遵從度在實測中比較穩），只把「要用什麼
     * 語言回答」這件事抽出來——日文介面的使用者不該拿到中文句子。
     */
    fun outputLanguageName(): String = when (lang) {
        AppLanguage.En -> "英文"
        AppLanguage.Ja -> "日文"
        AppLanguage.Ko -> "韓文"
        else           -> "繁體中文"
    }

    /**
     * 目標語言的**英文**名稱。
     *
     * prompt 主體已經全面英文化（見 CloudInferenceEngine），所以指令裡要嵌入
     * 語言名稱時得用英文寫。[outputLanguageName] 回的是中文名稱，
     * 那個留給仍然顯示給使用者看的地方。
     */
    fun outputLanguageNameEn(): String = when (lang) {
        AppLanguage.En -> "English"
        AppLanguage.Ja -> "Japanese"
        AppLanguage.Ko -> "Korean"
        else           -> "Traditional Chinese"
    }

    /**
     * 給 LLM 的「輸出語言」硬性指令，**用目標語言本身寫**。
     *
     * 為什麼不能只靠語言名稱：舊版提示詞主體是中文，模型看到夾在中文句子裡的
     * 「用英文回答」很容易整段跟著中文走——實測英文介面的跟讀評語仍然
     * 回「非常好，繼續加油！」。提示詞主體改成英文後這個拉力小了很多，
     * 但仍然保留這道指令：用該語言自己的祈使句寫、放在最後一行（recency），
     * 是實測下來最穩的組合。
     */
    fun outputLanguageDirective(field: String): String = when (lang) {
        AppLanguage.En -> "IMPORTANT: the \"$field\" value MUST be written in English."
        AppLanguage.Ja -> "重要：「$field」の値は必ず日本語で書いてください。"
        AppLanguage.Ko -> "중요: \"$field\" 값은 반드시 한국어로 작성하세요."
        else           -> "重要：「$field」的內容必須用繁體中文書寫。"
    }

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
        "🆘 求救" to arrayOf("🆘 Help", "🆘 助けて", "🆘 도움"),
        "要通知家人嗎？" to arrayOf("Alert your family?", "家族に知らせますか？", "가족에게 알릴까요?"),
        "會立刻傳訊息給你的緊急聯絡人，並附上你目前的位置，然後開啟與家人的對話視窗。" to arrayOf(
            "This messages your emergency contact with your current location, then opens a window so you can talk to them.",
            "緊急連絡先に現在地を添えてすぐにメッセージを送り、家族と話せる画面を開きます。",
            "긴급 연락처에 현재 위치와 함께 즉시 메시지를 보내고, 가족과 대화할 수 있는 창을 엽니다."),
        "通知家人" to arrayOf("Alert family", "家族に知らせる", "가족에게 알리기"),
        "求救" to arrayOf("Help", "助けを求める", "도움 요청"),
        "正在通知家人…" to arrayOf("Alerting your family…", "家族に知らせています…", "가족에게 알리는 중…"),
        "✅ 已通知家人，並附上你的位置。" to arrayOf(
            "✅ Your family has been alerted, with your location.",
            "✅ 家族に位置情報つきで知らせました。",
            "✅ 위치와 함께 가족에게 알렸습니다."),
        "⚠ 通報傳送失敗（%s）。請直接撥打 1925 安心專線或 119。" to arrayOf(
            "⚠ The alert could not be sent (%s). Please call 1925 or 119 directly.",
            "⚠ 通報を送信できませんでした（%s）。1925 または 119 に直接お電話ください。",
            "⚠ 신고를 보내지 못했습니다(%s). 1925 또는 119로 직접 전화해 주세요."),
        "⚠ 通報傳送失敗。請直接撥打 1925 安心專線或 119。" to arrayOf(
            "⚠ The alert could not be sent. Please call 1925 or 119 directly.",
            "⚠ 通報を送信できませんでした。1925 または 119 に直接お電話ください。",
            "⚠ 신고를 보내지 못했습니다. 1925 또는 119로 직접 전화해 주세요."),
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
        "連續練習 " to arrayOf("Streak ", "連続練習 ", "연속 연습 "),
        " 天，繼續保持！" to arrayOf(" days, keep it up!", " 日、その調子！", " 일째, 계속 유지!"),
        "📖 整段練習 %d/%d" to arrayOf("📖 Paragraph %d/%d", "📖 段落練習 %d/%d", "📖 단락 연습 %d/%d"),
        "下一句 ▶" to arrayOf("Next ▶", "次の文 ▶", "다음 ▶"),
        "完成整段 ✓" to arrayOf("Finish ✓", "段落完了 ✓", "단락 완료 ✓"),
        "整段練習（自動分句逐句練）" to arrayOf("Paragraph (auto split, sentence by sentence)", "段落練習（自動分割で一文ずつ）", "단락 연습 (자동 분할, 문장별)"),
        "臨床常用題庫（點一下開始練）" to arrayOf("Clinical bank (tap to practice)", "臨床問題集（タップで練習）", "임상 문제집 (탭하여 연습)"),
        "字詞（名詞）" to arrayOf("Words (nouns)", "単語（名詞）", "단어 (명사)"),
        "字詞（動作）" to arrayOf("Words (verbs)", "単語（動作）", "단어 (동작)"),
        "短句" to arrayOf("Short sentences", "短い文", "짧은 문장"),
        "情境句" to arrayOf("Situational", "場面の文", "상황 문장"),
        "凝視停留模式" to arrayOf("Gaze-dwell mode", "視線停留モード", "시선 머무름 모드"),
        "眨眼掃描模式" to arrayOf("Blink-scan mode", "まばたきスキャンモード", "깜빡임 스캔 모드"),
        "視線" to arrayOf("Gaze", "視線", "시선"),
        "復健字卡（需加強）：%s" to arrayOf("Practice card (needs work): %s", "練習カード（要強化）：%s", "연습 카드 (보완 필요): %s"),
        "復健字卡：%s" to arrayOf("Practice card: %s", "練習カード：%s", "연습 카드: %s"),
        "🔴 紅字是這次說得不夠準的字，點字卡再聽一次重點練。" to arrayOf("🔴 Red words were less accurate this time — tap a card to listen and focus on them.", "🔴 赤字は今回不正確だった語。カードをタップして重点練習。", "🔴 빨간 글자는 부정확했던 단어 — 카드를 탭해 집중 연습."),
        "故事訓練模式 · 開發者" to arrayOf("Story training · Developer", "ストーリー訓練 · 開発者", "스토리 훈련 · 개발자"),
        "看 4 格圖卡編一段故事，AI 老師會即時評論並打分" to arrayOf("Make a story from 4 cards; the AI teacher comments and scores in real time.", "4コマから物語を作り、AI先生がリアルタイムで講評・採点。", "4컷으로 이야기를 만들면 AI 선생님이 실시간 평가·채점."),
        "切換到九宮格" to arrayOf("Switch to grid", "グリッドに切替", "그리드로 전환"),
        "切換到識字卡" to arrayOf("Switch to flashcards", "カードに切替", "카드로 전환"),
        "這次有傳達到嗎？" to arrayOf("Did it get through this time?", "今回は伝わった？", "이번엔 전달됐나요?"),
        "已連續 %d 次選錯，再 %d 次將呼叫家屬" to arrayOf("%d wrong picks in a row; %d more will call family", "連続%d回の誤選択、あと%d回で家族に連絡", "연속 %d회 오선택, %d회 더면 가족 호출"),
        "我目前需要協助，請聯絡我（VoiceWeaver 緊急通報）。" to arrayOf("I need help right now, please contact me (VoiceWeaver alert).", "今すぐ助けが必要です、連絡してください（VoiceWeaver 通報）。", "지금 도움이 필요해요, 연락해 주세요 (VoiceWeaver 알림)."),
        "搜尋圖卡" to arrayOf("Search cards", "カードを検索", "카드 검색"),
        "全部分類" to arrayOf("All categories", "すべてのカテゴリ", "모든 분류"),
        "基礎溝通" to arrayOf("Basic", "基本コミュニケーション", "기본 소통"),
        "日常情境" to arrayOf("Daily", "日常の場面", "일상 상황"),
        "照護資訊" to arrayOf("Care info", "ケア情報", "돌봄 정보"),
        "沒有符合的圖卡" to arrayOf("No matching cards", "一致するカードなし", "일치하는 카드 없음"),
        "上一張" to arrayOf("Previous", "前へ", "이전"),
        "是這個" to arrayOf("This one", "これ", "이거"),
        "下一張" to arrayOf("Next", "次へ", "다음"),
        "類別 %d / %d" to arrayOf("Category %d / %d", "カテゴリ %d / %d", "분류 %d / %d"),
        "這個類別還沒有圖卡" to arrayOf("No cards in this category yet", "このカテゴリにはまだカードがありません", "이 분류에 아직 카드가 없어요"),
        "自訂金額" to arrayOf("Custom amount", "金額を入力", "금액 입력"),
        "按 ✓ 開啟數字鍵盤輸入" to arrayOf("Press ✓ to open the number pad", "✓ で数字キーパッドを開く", "✓ 누르면 숫자 키패드"),
        "緊急 — 按 ✓ 會立即播報" to arrayOf("Emergency — ✓ speaks immediately", "緊急 — ✓ で即時発声", "긴급 — ✓ 누르면 즉시 발성"),
        "回類別" to arrayOf("Back to categories", "カテゴリへ戻る", "분류로 돌아가기"),
        "圖卡（點一下加進句子）" to arrayOf("Cards (tap to add to sentence)", "カード（タップで文に追加）", "카드 (탭하여 문장에 추가)"),
        "▌ 成績單 · 進步報表" to arrayOf("▌ Report · Progress", "▌ レポート · 進捗", "▌ 리포트 · 진척"),
        "匯出 CSV" to arrayOf("Export CSV", "CSV を書き出し", "CSV 내보내기"),
        "📄 匯出 CSV" to arrayOf("📄 Export CSV", "📄 CSV 書き出し", "📄 CSV 내보내기"),
        "匯出 PDF" to arrayOf("Export PDF", "PDF を書き出し", "PDF 내보내기"),
        "🖨 匯出 PDF" to arrayOf("🖨 Export PDF", "🖨 PDF 書き出し", "🖨 PDF 내보내기"),
        "🧭 行為數據（治療師評估用，全期累計）" to arrayOf("🧭 Behavior data (for therapists, all-time)", "🧭 行動データ（療法士評価用・通算）", "🧭 행동 데이터 (치료사 평가용, 전체 누적)"),
        "⏱ 反應" to arrayOf("⏱ Reaction", "⏱ 反応", "⏱ 반응"),
        "🎯 選句" to arrayOf("🎯 Pick rate", "🎯 文選択", "🎯 문장 선택"),
        "✏️ 修改" to arrayOf("✏️ Edits", "✏️ 修正", "✏️ 수정"),
        "🖼 圖卡" to arrayOf("🖼 Cards", "🖼 カード", "🖼 카드"),
        "反應 = 開 App 到第一次發聲的平均耗時 · 選句 = AI 第一候選被採用率 · " to arrayOf("Reaction = avg time from open to first speech · Pick = AI top-candidate adoption · ", "反応 = 起動から初発声までの平均 · 文選択 = AI第一候補の採用率 · ", "반응 = 실행~첫 발성 평균 · 문장 선택 = AI 1순위 채택률 · "),
        "修改 = 退回/刪卡累計 · 圖卡 = 圖卡輸入佔總輸入比例" to arrayOf("Edits = undo/delete total · Cards = card-input share", "修正 = 取消/削除の累計 · カード = カード入力の割合", "수정 = 취소/삭제 누적 · 카드 = 카드 입력 비율"),
        "🎯 練習" to arrayOf("🎯 Sessions", "🎯 練習", "🎯 연습"),
        "📈 平均" to arrayOf("📈 Average", "📈 平均", "📈 평균"),
        "💖 正向" to arrayOf("💖 Positive", "💖 前向き", "💖 긍정"),
        "📖 故事練習" to arrayOf("📖 Story practice", "📖 ストーリー練習", "📖 스토리 연습"),
        "傳送中…" to arrayOf("Sending…", "送信中…", "전송 중…"),
        "📤 匯出成績單到 Telegram" to arrayOf("📤 Send report to Telegram", "📤 レポートを Telegram へ", "📤 리포트를 Telegram으로"),
        "我們在這裡陪你" to arrayOf("We're here with you", "私たちがそばにいます", "우리가 곁에 있어요"),
        "正在聯繫家人…" to arrayOf("Contacting family…", "家族に連絡中…", "가족에게 연락 중…"),
        "開始即時視訊通話" to arrayOf("Start a live video call", "ビデオ通話を開始", "영상 통화 시작"),
        "改用電話打給家人" to arrayOf("Call family by phone instead", "電話で家族にかける", "전화로 가족에게 걸기"),
        "視訊連結已傳家人" to arrayOf("Video link sent to family", "ビデオリンクを家族に送信", "영상 링크 가족에게 전송"),
        "畫面已傳家人" to arrayOf("Screen sent to family", "画面を家族に送信", "화면 가족에게 전송"),
        "想對家人說（點一下傳出）" to arrayOf("Say to family (tap to send)", "家族に伝える（タップで送信）", "가족에게 (탭하여 전송)"),
        "我需要陪伴" to arrayOf("I need company", "そばにいてほしい", "곁에 있어줘"),
        "我很難過" to arrayOf("I'm very sad", "とても悲しい", "너무 슬퍼요"),
        "我想聽你說話" to arrayOf("I want to hear your voice", "あなたの声が聞きたい", "당신 목소리가 듣고 싶어요"),
        "我會撐住" to arrayOf("I'll hold on", "がんばって耐える", "버틸게요"),
        "請打給我" to arrayOf("Please call me", "電話してください", "전화해 주세요"),
        "錄音中…（約 6 秒後自動傳出）" to arrayOf("Recording… (auto-sends in ~6s)", "録音中…（約6秒後に自動送信）", "녹음 중… (약 6초 후 자동 전송)"),
        "🎤 傳語音訊息給家人" to arrayOf("🎤 Send a voice message to family", "🎤 家族に音声メッセージ", "🎤 가족에게 음성 메시지"),
        "1925 安心專線" to arrayOf("1925 helpline", "1925 相談ダイヤル", "1925 상담 전화"),
        // 設定頁 →「我的專屬發音」（關鍵字觸發捷徑）
        "我的專屬發音" to arrayOf("My phrase shortcuts", "フレーズショートカット", "문구 단축키"),
        "設定觸發詞與句子" to arrayOf("Trigger words and sentences", "トリガー語と文", "트리거 단어와 문장"),
        "講到那幾個字就說出整句，不經過語音轉文字" to arrayOf(
            "Say the trigger words and it speaks the whole sentence — no speech-to-text involved",
            "トリガー語を言うと文全体を読み上げます（音声認識は使いません）",
            "트리거 단어를 말하면 문장 전체를 말합니다 (음성 인식 미사용)"),
        "挑一句，然後把畫面上那幾個字唸三次。之後你講的話裡帶到那幾個字，App 就會說出整句——比對走聲波形狀，不經過語音轉文字，所以發音不標準也沒關係。" to arrayOf(
            "Pick a sentence, then say the highlighted words three times. After that, whenever those words appear in what you say, the app speaks the whole sentence. It matches the shape of your voice, not text, so unclear pronunciation is fine.",
            "文を選び、表示された語を3回言ってください。以降、その語が話の中に出れば文全体を読み上げます。文字ではなく声の波形で照合するので、発音が不明瞭でも大丈夫です。",
            "문장을 고르고 강조된 단어를 세 번 말하세요. 이후 그 단어가 말 속에 나오면 문장 전체를 말합니다. 글자가 아닌 음성 파형으로 대조하므로 발음이 불분명해도 괜찮습니다."),
        "再教一次" to arrayOf("Teach again", "もう一度教える", "다시 가르치기"),
        "再教一次（會加進原本的錄音，不會蓋掉）" to arrayOf(
            "Teach again — this adds to the existing recordings, it does not replace them",
            "もう一度教える — 既存の録音に追加され、置き換えません",
            "다시 가르치기 — 기존 녹음에 추가되며 대체하지 않습니다"),
        "雲端沒有可取回的專屬發音（需要先登入並在另一台裝置設定過）" to arrayOf(
            "Nothing to restore (sign in first, and set some up on another device)",
            "復元できる項目がありません（先にログインし、別の端末で設定してください）",
            "복원할 항목이 없습니다 (먼저 로그인하고 다른 기기에서 설정하세요)"),
        "已經是最新的，沒有需要取回的項目" to arrayOf(
            "Already up to date", "すでに最新です", "이미 최신 상태입니다"),
        "取回 %d 筆" to arrayOf("Restored %d", "%d 件を復元", "%d개 복원됨"),
        "更新 %d 筆的句子" to arrayOf(
            "Updated the sentence on %d", "%d 件の文を更新", "%d개의 문장을 업데이트함"),
        "雲端已刪除，本機同步移除 %d 筆" to arrayOf(
            "Removed %d that were deleted in the cloud",
            "クラウドで削除された %d 件をこの端末からも削除",
            "클라우드에서 삭제된 %d개를 이 기기에서도 제거"),
        "連不上雲端（請確認已登入並有網路）" to arrayOf(
            "Cannot reach the cloud (check you are signed in and online)",
            "クラウドに接続できません（ログインとネットワークを確認してください）",
            "클라우드에 연결할 수 없습니다 (로그인과 네트워크를 확인하세요)"),
        "取回 {n} 筆；另有 {total} 筆只有文字設定，要在這支手機錄過音才會生效" to arrayOf(
            "Restored {n}. Another {total} have text only — record them on this phone to activate.",
            "{n} 件を復元。他に {total} 件はテキストのみ — この端末で録音すると有効になります。",
            "{n}개 복원. 다른 {total}개는 텍스트만 있습니다 — 이 기기에서 녹음하면 활성화됩니다."),
        "VoiceWeaver 復健成績報告" to arrayOf(
            "VoiceWeaver rehab report", "VoiceWeaver リハビリ成績表", "VoiceWeaver 재활 리포트"),
        "總練習 {n} 次    平均 {total} / 100" to arrayOf(
            "{n} sessions    average {total} / 100",
            "練習 {n} 回    平均 {total} / 100",
            "연습 {n}회    평균 {total} / 100"),
        // 復健成績報告的 CSV 表頭
        "時間" to arrayOf("Time", "時刻", "시간"),
        "場景" to arrayOf("Place", "場面", "장소"),
        "目標句" to arrayOf("Target sentence", "目標文", "목표 문장"),
        "分數" to arrayOf("Score", "スコア", "점수"),
        "返回" to arrayOf("Back", "戻る", "뒤로"),
        "這是什麼？" to arrayOf("What is this?", "これは？", "이게 뭔가요?"),
        "收起說明" to arrayOf("Hide", "閉じる", "접기"),
        "加入新的" to arrayOf("Add new", "新規追加", "새로 추가"),
        "靈敏度" to arrayOf("Sensitivity", "感度", "민감도"),
        "嚴格" to arrayOf("Strict", "厳しめ", "엄격"),
        "標準" to arrayOf("Normal", "標準", "표준"),
        "寬鬆" to arrayOf("Relaxed", "ゆるめ", "느슨"),
        "測試看看" to arrayOf("Try it", "試す", "테스트"),
        "按住，講一整句話。命中的話會唸出來。" to arrayOf(
            "Hold and say a full sentence. If it matches, the app speaks it.",
            "長押しして文を話してください。一致すれば読み上げます。",
            "길게 눌러 문장을 말하세요. 일치하면 읽어 줍니다."),
        "還沒測過" to arrayOf("Not tested yet", "未テスト", "테스트 전"),
        "聽著…" to arrayOf("Listening…", "聞いています…", "듣는 중…"),
        "沒有聽到聲音" to arrayOf("Didn't hear anything", "音が聞こえません", "소리가 들리지 않습니다"),
        "✗ 沒有命中" to arrayOf("✗ No match", "✗ 一致なし", "✗ 일치 없음"),
        "最接近：%s" to arrayOf("Closest: %s", "最も近い：%s", "가장 근접: %s"),
        "需要麥克風權限才能錄音" to arrayOf(
            "Microphone permission is needed to record.", "録音にはマイクの許可が必要です。", "녹음하려면 마이크 권한이 필요합니다."),
        "剛才太小聲或太短了，這一次再錄一遍" to arrayOf(
            "That was too quiet or too short — let's try that one again.",
            "小さすぎるか短すぎました。もう一度録音しましょう。",
            "너무 작거나 짧았습니다. 한 번 더 녹음해 주세요."),
        "無法錄音：%s" to arrayOf("Could not record: %s", "録音できません：%s", "녹음할 수 없습니다: %s"),
        "未知錯誤" to arrayOf("unknown error", "不明なエラー", "알 수 없는 오류"),
        "請把「要聽的字」和「要說出口的話」都填好" to arrayOf(
            "Please fill in both the words to listen for and the sentence to say.",
            "「認識する語」と「読み上げる文」の両方を入力してください。",
            "'인식할 단어'와 '말할 문장'을 모두 입력해 주세요."),
        "無法建立捷徑：%s" to arrayOf("Couldn't set that up: %s", "設定できません：%s", "설정할 수 없습니다: %s"),
        "請重新錄一次" to arrayOf("please record again", "もう一度録音してください", "다시 녹음해 주세요"),
        "用你平常的方式說一次" to arrayOf(
            "Say it the way you normally would.", "いつもどおりに言ってください。", "평소처럼 말해 주세요."),
        "再說一次，這次慢一點" to arrayOf(
            "Now say it a little slower.", "今度は少しゆっくり言ってください。", "이번엔 조금 천천히 말해 주세요."),
        "再說一次，這次小聲一點" to arrayOf(
            "Now say it a little more quietly.", "今度は少し小さめに言ってください。", "이번엔 조금 작게 말해 주세요."),
        "最後一次，照你平常的方式" to arrayOf(
            "Last one — the way you normally would.", "最後にもう一度、いつもどおりに。", "마지막으로, 평소처럼."),
        "再說一次，這次快一點" to arrayOf(
            "Now say it a little faster.", "今度は少し速く言ってください。", "이번엔 조금 빠르게 말해 주세요."),
        "已啟用（%d）" to arrayOf("Active (%d)", "有効 (%d)", "활성 (%d)"),
        "「%s」已啟用" to arrayOf("\"%s\" is ready", "「%s」を有効化しました", "\"%s\" 활성화됨"),
        "自訂" to arrayOf("Custom", "カスタム", "사용자 지정"),
        "自己想一句" to arrayOf("Add my own", "自分で追加", "직접 추가"),
        "聽到：" to arrayOf("Listens for: ", "認識する語：", "인식 단어: "),
        "已啟用" to arrayOf("Active", "有効", "활성"),
        "錄音啟用" to arrayOf("Record to activate", "録音して有効化", "녹음하여 활성화"),
        "要聽的字" to arrayOf("Words to listen for", "認識する語", "인식할 단어"),
        "要說出口的話" to arrayOf("Sentence to speak", "読み上げる文", "말할 문장"),
        "請唸這幾個字" to arrayOf("Say these words", "この語を言ってください", "이 단어를 말하세요"),
        "錄好了，可以儲存。" to arrayOf("All done — you can activate it now.", "録音完了。有効化できます。", "녹음 완료. 활성화할 수 있습니다."),
        "重錄上一次" to arrayOf("Redo last recording", "直前の録音をやり直す", "마지막 녹음 다시"),
        "啟用" to arrayOf("Activate", "有効化", "활성화"),
        "取消" to arrayOf("Cancel", "キャンセル", "취소"),
        "刪除" to arrayOf("Delete", "削除", "삭제"),

        // SafetyGuard.EmergencyAlert.toTelegramMessage() 用。
        // 這是發給照顧者的通知，跟著介面語言走——照顧者與使用者共用同一台裝置，
        // 介面語言是目前唯一能拿到的線索。
        "🆘 VoiceWeaver 緊急通報" to arrayOf(
            "🆘 VoiceWeaver emergency alert", "🆘 VoiceWeaver 緊急通報", "🆘 VoiceWeaver 긴급 알림"),
        "使用者輸入觸發危機關鍵字，請立即聯絡。" to arrayOf(
            "The user's input triggered a crisis keyword. Please make contact immediately.",
            "利用者の入力が危機キーワードに該当しました。至急ご連絡ください。",
            "사용자 입력이 위기 키워드에 해당합니다. 즉시 연락해 주세요."),
        "🗣 輸入" to arrayOf("🗣 Input", "🗣 入力", "🗣 입력"),
        "📍 位置未知（GPS 權限未授予或定位失敗）" to arrayOf(
            "📍 Location unknown (no GPS permission, or the fix failed)",
            "📍 位置不明（GPS 権限がない、または測位に失敗）",
            "📍 위치 불명 (GPS 권한 없음 또는 측위 실패)"),
        "🏷 附近" to arrayOf("🏷 Nearby", "🏷 付近", "🏷 근처"),

        // SafetyGuard.DEFENSIVE_SYSTEM_PROMPT 用。英文沿用其他危機訊息的作法，
        // 不寫死某個國家的號碼——這個 App 的英文使用者不一定在美國，
        // 給錯號碼比不給號碼更糟。
        "我聽到了，建議聯絡家屬或撥打 1925 安心專線" to arrayOf(
            "I hear you. Please reach out to your family, or call your local crisis line.",
            "聞いています。ご家族に連絡するか、相談ダイヤルにお電話ください。",
            "듣고 있습니다. 가족에게 연락하시거나 상담 전화로 연락해 주세요."),
        "119 緊急" to arrayOf("119 emergency", "119 緊急", "119 긴급"),
        "🔒 為了你的安全，這個畫面需要家人確認後才能關閉\n（家人在訊息輸入 /end 即可結束）" to arrayOf("🔒 For your safety, this screen closes only after family confirms\n(family sends /end to finish)", "🔒 安全のため、家族の確認後に閉じます\n（家族が /end で終了）", "🔒 안전을 위해 가족 확인 후 닫힙니다\n(가족이 /end 입력 시 종료)"),
        "畫面與聲音僅安全傳給家人，不會公開。家人可用 /call 視訊、/camera 重拍。" to arrayOf("Screen and audio are sent privately to family only. Family can use /call and /camera.", "画面と音声は家族にのみ安全に送信。家族は /call /camera 利用可。", "화면과 소리는 가족에게만 안전 전송. 가족은 /call /camera 사용 가능."),
        "吸氣…" to arrayOf("Breathe in…", "吸って…", "들이쉬고…"),
        "吐氣…" to arrayOf("Breathe out…", "吐いて…", "내쉬고…"),
        "家人" to arrayOf("Family", "家族", "가족"),
        "AI 生圖" to arrayOf("AI image", "AI 画像生成", "AI 이미지"),
        "使用中的生圖服務" to arrayOf("Active image service", "使用中の画像サービス", "사용 중 이미지 서비스"),
        "想生成的圖片描述" to arrayOf("Describe the image", "生成したい画像の説明", "생성할 이미지 설명"),
        "例：溫馨的客廳，柔和光線，扁平插畫風" to arrayOf("e.g. cozy living room, soft light, flat illustration", "例：暖かいリビング、柔らかい光、フラットイラスト", "예: 아늑한 거실, 부드러운 빛, 플랫 일러스트"),
        "生成中…（雲端運算，約 10～30 秒）" to arrayOf("Generating… (cloud, ~10–30s)", "生成中…（クラウド、約10〜30秒）", "생성 중… (클라우드, 약 10~30초)"),
        "生成圖片" to arrayOf("Generate image", "画像を生成", "이미지 생성"),
        "生成的圖片" to arrayOf("Generated image", "生成された画像", "생성된 이미지"),
        "設為 App 背景" to arrayOf("Set as app background", "アプリの背景に設定", "앱 배경으로 설정"),
        "要新增服務商或金鑰，請到「設定 → API 金鑰管理 → AI 生圖」。同一服務商可放多把金鑰，生圖時自動備援。" to arrayOf("To add providers or keys, go to Settings → API keys → AI image. Multiple keys per provider auto-failover.", "提供元やキーの追加は 設定 → APIキー → AI画像 へ。同一提供元の複数キーは自動フェイルオーバー。", "공급자·키 추가는 설정 → API 키 → AI 이미지. 같은 공급자 여러 키는 자동 전환."),
        "尚無生圖設定，請到 API 金鑰管理新增" to arrayOf("No image setup yet — add one in API key manager", "画像設定なし — APIキー管理で追加", "이미지 설정 없음 — API 키 관리에서 추가"),
        "醫療相關內容，請確認" to arrayOf("Medical content — please confirm", "医療関連の内容、確認してください", "의료 관련 내용, 확인하세요"),
        "「%s」\n\n請患者或照護者確認句子正確（部位、意圖無誤）後再朗讀。" to arrayOf("\"%s\"\n\nPlease have the patient or caregiver confirm the sentence (body part, intent) before speaking.", "「%s」\n\n患者または介護者が文（部位・意図）を確認してから読み上げてください。", "\"%s\"\n\n환자나 보호자가 문장(부위·의도)을 확인한 후 읽어주세요."),
        "確認朗讀" to arrayOf("Confirm & speak", "確認して読み上げ", "확인 후 읽기"),
        "語音輸出已鎖定" to arrayOf("Voice output locked", "音声出力がロック中", "음성 출력 잠김"),
        "我知道了" to arrayOf("Got it", "了解", "알겠어요"),
        "是這個意思嗎？" to arrayOf("Is this what you mean?", "この意味で合ってる？", "이 뜻이 맞나요?"),
        "對，朗讀" to arrayOf("Yes, speak", "はい、読み上げ", "네, 읽기"),
        "再猜" to arrayOf("Guess again", "もう一度推測", "다시 추측"),
        "▌ 組合區（Combo Area）" to arrayOf("▌ Combo Area", "▌ 組み合わせエリア", "▌ 조합 영역"),
        "尚未選擇圖卡。請從下方類別挑選想表達的內容（賣出商品、收款、找零都可以混搭）。" to arrayOf("No cards yet. Pick from the categories below to express what you want.", "まだカード未選択。下のカテゴリから表現したい内容を選んでください。", "아직 카드 미선택. 아래 분류에서 표현할 내용을 골라주세요."),
        "▌ 確認再唸出來" to arrayOf("▌ Confirm, then speak", "▌ 確認してから読み上げ", "▌ 확인 후 읽기"),
        "接受" to arrayOf("Accept", "承認", "수락"),
        "重來" to arrayOf("Redo", "やり直し", "다시"),
        "說明：AI 已組好「%s」。確認後才會唸出來。" to arrayOf("Note: AI composed \"%s\". It speaks only after you confirm.", "説明：AIが「%s」を作成。確認後に読み上げます。", "설명: AI가 \"%s\"를 구성. 확인 후 읽습니다."),
        "秒" to arrayOf("s", "秒", "초"),
        "次" to arrayOf("×", "回", "회"),
        "分" to arrayOf("pt", "点", "점"),
        "嘗試 %d 次・平均 %s 分" to arrayOf("%d attempts · avg %s pts", "%d 回・平均 %s 点", "%d회 · 평균 %s점"),
        "✅ 已送到 Chat %s" to arrayOf("✅ Sent to Chat %s", "✅ Chat %s に送信", "✅ Chat %s 전송"),
        "❌ 送出失敗：%s" to arrayOf("❌ Send failed: %s", "❌ 送信失敗：%s", "❌ 전송 실패: %s"),
        "修改" to arrayOf("Edit", "修正", "수정"),
        "請說出你的故事…" to arrayOf("Tell your story…", "あなたの物語を話して…", "당신의 이야기를 말해보세요…"),
        "復健訓練模式 · 未啟用" to arrayOf("Training mode · disabled", "リハビリ訓練 · 未有効", "재활 훈련 · 비활성"),
        "請到「設定 → 開發者選項」打開後再回來。" to arrayOf("Enable it in Settings → Developer options, then come back.", "設定 → 開発者オプション で有効にしてから戻ってください。", "설정 → 개발자 옵션에서 켠 후 돌아오세요."),
        "設備 RAM 不足" to arrayOf("Not enough RAM", "RAM 不足", "RAM 부족"),
        "故事訓練會載入 LLM 評論模型，需要 8GB 以上 RAM。\n您的裝置目前判定為：%s。" to arrayOf("Story training loads an LLM model and needs 8GB+ RAM.\nYour device is detected as: %s.", "ストーリー訓練は LLM を読み込み、8GB 以上の RAM が必要です。\nお使いの端末判定：%s。", "스토리 훈련은 LLM을 불러오며 8GB 이상 RAM이 필요합니다.\n기기 판정: %s."),
        "▌ 圖片說故事" to arrayOf("▌ Tell a story from pictures", "▌ 絵で物語を話す", "▌ 그림으로 이야기하기"),
        "換一組" to arrayOf("Shuffle", "別の組", "다른 세트"),
        "請看圖說一段故事：「%s」" to arrayOf("Look and tell a story: \"%s\"", "絵を見て物語を：「%s」", "그림 보고 이야기: \"%s\""),
        "用文字或語音說出這個故事…" to arrayOf("Tell the story by text or voice…", "文字か音声で物語を…", "텍스트나 음성으로 이야기를…"),
        "語音輸入" to arrayOf("Voice input", "音声入力", "음성 입력"),
        "AI 評論中…" to arrayOf("AI reviewing…", "AI 講評中…", "AI 평가 중…"),
        "請 AI 評論" to arrayOf("Ask AI to review", "AI に講評を頼む", "AI 평가 요청"),
        "AI 老師" to arrayOf("AI teacher", "AI 先生", "AI 선생님"),
        "本次評分：%s / 100" to arrayOf("Score: %s / 100", "今回の評価：%s / 100", "점수: %s / 100"),
        "太棒了！您把這個故事完整說出來了，繼續保持 💪" to arrayOf("Great! You told the whole story — keep it up 💪", "素晴らしい！物語を最後まで話せました、その調子 💪", "훌륭해요! 이야기를 끝까지 말했어요, 계속 💪"),
        "說得不錯！下次可以再多提到「%s」等關鍵字 🌱" to arrayOf("Not bad! Next time, try keywords like \"%s\" 🌱", "いいね！次は「%s」などのキーワードも 🌱", "좋아요! 다음엔 \"%s\" 같은 키워드도 🌱"),
        "您願意開口練習就很棒了，再試一次，慢慢來 🌿" to arrayOf("Just practicing speaking is great — try again, take your time 🌿", "話す練習をするだけで立派です、もう一度ゆっくり 🌿", "말하는 연습만으로도 훌륭해요, 천천히 다시 🌿"),
        "API 金鑰管理" to arrayOf("API key manager", "API キー管理", "API 키 관리"),
        "🖼 AI 生圖（自訂 API）" to arrayOf("🖼 AI image (custom API)", "🖼 AI 画像（カスタム API）", "🖼 AI 이미지 (사용자 API)"),
        "加入" to arrayOf("Add", "追加", "추가"),
        "選供應商、填 API 金鑰即可。同一供應商可加多筆（多把金鑰），生圖時自動備援；勾選 ✓＝目前使用中。" to arrayOf("Pick a provider and enter the API key. Add multiple keys per provider for auto-failover; ✓ = active.", "提供元を選び API キーを入力。同一提供元に複数キー可（自動フェイルオーバー）；✓＝使用中。", "공급자 선택 후 API 키 입력. 공급자별 여러 키 가능(자동 전환); ✓ = 사용 중."),
        "💬 文字 LLM（多供應商）" to arrayOf("💬 Text LLM (multi-provider)", "💬 テキスト LLM（複数提供元）", "💬 텍스트 LLM (여러 공급자)"),
        "選供應商、填金鑰即可。可加多筆（含同供應商多把金鑰），重組句子時用 ✓ 那筆，失敗自動換下一筆；全部失敗才用手機本機 AI。" to arrayOf("Pick a provider and enter the key. Add multiple; the ✓ one is used first, failing over to the next; on-device AI is the last resort.", "提供元を選びキーを入力。複数可、✓ を優先し失敗で次へ、全滅時のみ端末内 AI。", "공급자 선택 후 키 입력. 여러 개 가능, ✓ 우선, 실패 시 다음, 모두 실패면 기기 내 AI."),
        "📍 定位與通報" to arrayOf("📍 Location & alerts", "📍 位置と通報", "📍 위치 및 알림"),
        "Google Places（POI 反查）" to arrayOf("Google Places (POI lookup)", "Google Places（POI 逆引き）", "Google Places (POI 조회)"),
        "Telegram Bot Token（緊急通報）" to arrayOf("Telegram Bot Token (alerts)", "Telegram Bot Token（緊急通報）", "Telegram Bot Token (긴급 알림)"),
        "家人電話（危機即時通話）" to arrayOf("Family phone (crisis call)", "家族の電話（危機時通話）", "가족 전화 (위기 통화)"),
        "視訊房伺服器（Jitsi，免登入免名字）" to arrayOf("Video server (Jitsi, no login/name)", "ビデオサーバー（Jitsi、ログイン・名前不要）", "영상 서버 (Jitsi, 로그인·이름 불필요)"),
        "🔒 所有金鑰僅存在這支手機（DataStore），不會上傳、也不會被打包進原始碼。\n首次啟動已從本機 .env 自動帶入；留空則用內建預設。改完即時生效。" to arrayOf("🔒 All keys stay on this phone (DataStore) — never uploaded or bundled in source.\nFirst launch imported from local .env; blank uses built-in defaults. Changes apply instantly.", "🔒 全てのキーはこの端末のみ（DataStore）。アップロードもソース同梱もしません。\n初回は .env から取込、空欄は既定値。変更は即時反映。", "🔒 모든 키는 이 기기에만(DataStore) 저장, 업로드·소스 포함 안 됨.\n첫 실행 시 .env에서 가져옴, 비우면 기본값. 변경 즉시 적용."),
        "供應商" to arrayOf("Provider", "提供元", "공급자"),
        "API 金鑰" to arrayOf("API key", "API キー", "API 키"),
        "此供應商免金鑰，直接可用" to arrayOf("This provider needs no key", "この提供元はキー不要", "이 공급자는 키 불필요"),
        "隱藏" to arrayOf("Hide", "隠す", "숨기기"),
        "顯示" to arrayOf("Show", "表示", "표시"),
        "使用中" to arrayOf("Active", "使用中", "사용 중"),
        "設為使用中" to arrayOf("Set active", "使用中にする", "사용으로 설정"),
        "（免金鑰）" to arrayOf(" (no key)", "（キー不要）", " (키 불필요)"),
        "預設：%s" to arrayOf("Default: %s", "既定：%s", "기본: %s"),
        "例 0912345678" to arrayOf("e.g. 0912345678", "例 0912345678", "예 0912345678"),
        "無障礙與提醒" to arrayOf("Accessibility & reminders", "アクセシビリティと通知", "접근성 및 알림"),
        "高對比模式" to arrayOf("High-contrast mode", "ハイコントラスト", "고대비 모드"),
        "純黑底 + 高彩文字，視力弱者用" to arrayOf("Pure black bg + vivid text, for low vision", "純黒背景＋高彩度文字、弱視向け", "순흑 배경＋고채도 글자, 약시용"),
        "超大字" to arrayOf("Extra-large text", "特大文字", "특대 글자"),
        "鼻控靈敏度" to arrayOf("Nose-control sensitivity", "鼻コントロール感度", "코 제어 민감도"),
        "靈敏度即時生效，數值越大頭轉一點點游標移越多。" to arrayOf("Applies instantly; higher = cursor moves more per head turn.", "即時反映；値が大きいほど少しの首の動きで大きく移動。", "즉시 적용; 값이 클수록 고개를 조금 돌려도 커서가 많이 이동."),
        "每日復健提醒" to arrayOf("Daily practice reminder", "毎日のリハビリ通知", "매일 재활 알림"),
        "每天固定時間通知來練習" to arrayOf("A daily reminder to practice", "毎日決まった時間に練習を通知", "매일 정해진 시간에 연습 알림"),
        "成績單自動推送家屬" to arrayOf("Auto-send report to family", "レポートを家族に自動送信", "리포트 가족 자동 전송"),
        "定期把成績單發到緊急聯絡人 Telegram" to arrayOf("Periodically send the report to the emergency contact on Telegram", "定期的にレポートを緊急連絡先の Telegram へ", "정기적으로 리포트를 긴급 연락처 Telegram으로"),
        "關閉" to arrayOf("Off", "オフ", "끔"),
        "每日" to arrayOf("Daily", "毎日", "매일"),
        "每週" to arrayOf("Weekly", "毎週", "매주"),
        "需先填下方「緊急聯絡人 Chat ID」。" to arrayOf("Fill in the emergency contact Chat ID below first.", "下の「緊急連絡先 Chat ID」を先に入力。", "아래 ‘긴급 연락처 Chat ID’를 먼저 입력."),
        "自訂 AAC 圖卡" to arrayOf("Custom AAC cards", "カスタム AAC カード", "맞춤 AAC 카드"),
        "圖示" to arrayOf("Icon", "アイコン", "아이콘"),
        "詞語" to arrayOf("Word", "語句", "단어"),
        "新增圖卡" to arrayOf("Add card", "カード追加", "카드 추가"),
        "清除全部自訂圖卡" to arrayOf("Clear all custom cards", "カスタムカードを全消去", "모든 맞춤 카드 삭제"),
        "無線連結電腦（掃 QR Code）" to arrayOf("Wireless PC link (scan QR)", "PC と無線連携（QR スキャン）", "PC 무선 연결 (QR 스캔)"),
        "電腦啟動語音中心後，點「顯示 QR Code」按鈕，再用下方「掃描」對準即可自動填入 IP。" to arrayOf("Start the voice center on the PC, tap “Show QR code”, then use “Scan” below to fill the IP automatically.", "PC でボイスセンターを起動し「QRコード表示」を押し、下の「スキャン」で IP を自動入力。", "PC에서 보이스 센터 실행 후 ‘QR 코드 표시’를 누르고 아래 ‘스캔’으로 IP 자동 입력."),
        "掃描電腦 QR Code" to arrayOf("Scan PC QR code", "PC の QR コードをスキャン", "PC QR 코드 스캔"),
        "緊急聯絡人 · Telegram Chat ID" to arrayOf("Emergency contact · Telegram Chat ID", "緊急連絡先 · Telegram Chat ID", "긴급 연락처 · Telegram Chat ID"),
        "儲存" to arrayOf("Save", "保存", "저장"),
        "回預設值" to arrayOf("Reset to default", "既定に戻す", "기본값으로"),
        "歷史紀錄管理" to arrayOf("History management", "履歴管理", "기록 관리"),
        "刪除所有重組歷史、復健紀錄、AAC 組合紀錄、故事訓練紀錄。DataStore 設定（風格、語言等）不會被清除。" to arrayOf("Delete all compose history, practice logs, AAC combos, and story records. DataStore settings (theme, language, etc.) are kept.", "再構成履歴・練習記録・AAC 組合せ・ストーリー記録を全削除。DataStore 設定（テーマ・言語等）は保持。", "재구성 기록·연습 기록·AAC 조합·스토리 기록 전체 삭제. DataStore 설정(테마·언어 등)은 유지."),
        "清除所有歷史紀錄" to arrayOf("Clear all history", "全履歴を消去", "모든 기록 삭제"),
        "一鍵重置" to arrayOf("One-tap reset", "ワンタップ初期化", "한 번에 초기화"),
        "將所有 App 設定（主題、語言、TTS 速率、Chat ID 等）回到出廠預設。**不會**刪除歷史紀錄。" to arrayOf("Reset all app settings (theme, language, TTS rate, Chat ID, etc.) to factory defaults. History is NOT deleted.", "全アプリ設定（テーマ・言語・TTS 速度・Chat ID 等）を初期化。履歴は削除しません。", "모든 앱 설정(테마·언어·TTS 속도·Chat ID 등)을 초기화. 기록은 삭제 안 함."),
        "重置為預設設定" to arrayOf("Reset to defaults", "既定に初期化", "기본값으로 초기화"),
        "匯入外部語料庫（JSON / CSV）" to arrayOf("Import external corpus (JSON / CSV)", "外部コーパスを取込（JSON / CSV）", "외부 말뭉치 가져오기 (JSON / CSV)"),
        "支援 .json（含完整欄位）或 .csv（家屬可用 Excel 編輯）。範例格式請見專案 README。" to arrayOf("Supports .json (full fields) or .csv (editable in Excel). See the project README for the format.", ".json（全項目）または .csv（Excel 編集可）に対応。形式は README を参照。", ".json(전체 필드) 또는 .csv(Excel 편집 가능) 지원. 형식은 README 참고."),
        "選擇檔案" to arrayOf("Choose file", "ファイルを選択", "파일 선택"),
        "▌ 進階設定" to arrayOf("▌ Advanced settings", "▌ 詳細設定", "▌ 고급 설정"),
        "確認刪除所有歷史紀錄？" to arrayOf("Delete all history?", "全履歴を削除？", "모든 기록 삭제?"),
        "這個動作無法復原。設定值（主題、Chat ID 等）會被保留。" to arrayOf("This can't be undone. Settings (theme, Chat ID, etc.) are kept.", "この操作は元に戻せません。設定（テーマ・Chat ID 等）は保持。", "되돌릴 수 없습니다. 설정(테마·Chat ID 등)은 유지."),
        "確認刪除" to arrayOf("Delete", "削除する", "삭제"),
        "確認重置所有設定？" to arrayOf("Reset all settings?", "全設定を初期化？", "모든 설정 초기화?"),
        "確認重置" to arrayOf("Reset", "初期化する", "초기화"),
        "提醒時間" to arrayOf("Reminder time", "通知時刻", "알림 시간"),
        "已取消" to arrayOf("Cancelled", "キャンセルしました", "취소됨"),
        "✅ 已匯入 %d 筆語料" to arrayOf("✅ Imported %d entries", "✅ %d 件を取込", "✅ %d개 가져옴"),
        "已新增 %d 張自訂圖卡（顯示在 AAC 板）" to arrayOf("%d custom cards added (shown on the AAC board)", "カスタムカード %d 枚追加（AAC ボードに表示）", "맞춤 카드 %d개 추가 (AAC 보드에 표시)"),
        "已設定：%s" to arrayOf("Set: %s", "設定済み：%s", "설정됨: %s"),
        "✅ 已套用 %s" to arrayOf("✅ Applied %s", "✅ %s を適用", "✅ %s 적용"),
        "預設值：%s（Tim Chien 測試帳號）" to arrayOf("Default: %s (Tim Chien test account)", "既定値：%s（Tim Chien テスト）", "기본값: %s (Tim Chien 테스트)"),
        "主題會回到「科技風」、Chat ID 會回到 %s。" to arrayOf("Theme returns to “Tech”, Chat ID returns to %s.", "テーマは「テック」、Chat ID は %s に戻ります。", "테마는 ‘테크’, Chat ID는 %s로 돌아갑니다."),
        "進階設定" to arrayOf("Advanced settings", "詳細設定", "고급 설정"),
        "開發者模式" to arrayOf("Developer mode", "開発者モード", "개발자 모드"),
        "使用模式（依嚴重程度）" to arrayOf("Usage mode (by severity)", "使用モード（重症度別）", "사용 모드 (중증도별)"),
        "雲端同步（與網頁共用）" to arrayOf("Cloud sync (shared with web)", "クラウド同期（ウェブと共有）", "클라우드 동기화 (웹과 공유)"),
        "已登入：%s" to arrayOf("Signed in: %s", "ログイン中：%s", "로그인: %s"),
        "未登入" to arrayOf("Not signed in", "未ログイン", "로그인 안 됨"),
        "Google 登入" to arrayOf("Sign in with Google", "Google でログイン", "Google 로그인"),
        "匿名" to arrayOf("Anonymous", "匿名", "익명"),
        "登出" to arrayOf("Log out", "ログアウト", "로그아웃"),
        "用同一個 Google 帳號登入手機與網頁，API 金鑰／供應商清單／Telegram 會自動雙向同步。" to arrayOf("Sign in with the same Google account on phone and web; API keys, providers, and Telegram sync both ways.", "スマホとウェブで同じ Google アカウントを使うと、API キー・提供元・Telegram が双方向同期。", "휴대폰과 웹에서 같은 Google 계정 사용 시 API 키·공급자·Telegram이 양방향 동기화."),
        "管理所有 API 金鑰（LLM / 定位 / 通報；生圖在「AI 生圖」內）" to arrayOf("Manage all API keys (LLM / location / alerts; image is under “AI image”)", "全 API キー管理（LLM／位置／通報；画像は「AI 画像」内）", "모든 API 키 관리 (LLM／위치／알림; 이미지는 ‘AI 이미지’)"),
        "進階功能" to arrayOf("Advanced features", "詳細機能", "고급 기능"),
        "進階設定（緊急聯絡人 / 清歷史 / 重置 / 匯入語料庫）" to arrayOf("Advanced (emergency contact / clear history / reset / import corpus)", "詳細（緊急連絡先／履歴消去／初期化／コーパス取込）", "고급 (긴급 연락처／기록 삭제／초기화／말뭉치 가져오기)"),
        "自訂背景" to arrayOf("Custom background", "カスタム背景", "맞춤 배경"),
        "自訂背景預覽" to arrayOf("Custom background preview", "カスタム背景プレビュー", "맞춤 배경 미리보기"),
        "尚未設定背景" to arrayOf("No background set", "背景未設定", "배경 미설정"),
        "選擇背景" to arrayOf("Choose background", "背景を選択", "배경 선택"),
        "字體倍率會套用到整個 App，包含 AAC、定位、設定與底部導覽。" to arrayOf("Font scale applies to the whole app, including AAC, location, settings and the bottom bar.", "文字倍率はアプリ全体（AAC・位置・設定・下部バー）に適用。", "글자 배율은 앱 전체(AAC·위치·설정·하단 바)에 적용."),
        // 「標準」原本有兩筆（字級預設 Normal／效能模式 Standard），後者會蓋掉前者，
        // 讓字級的「標準」在英文變成 Standard。效能模式走的是 string resource
        // （power_balanced），不需要這一筆，移除以解決撞鍵。
        "大" to arrayOf("Large", "大", "크게"),
        "特大" to arrayOf("Extra large", "特大", "특대"),
        "鼻子頭控" to arrayOf("Nose head-control", "鼻ヘッドコントロール", "코 헤드 컨트롤"),
        "前鏡頭追蹤鼻子／頭部方向移動游標；轉頭瞄準、停留即選取（Camera Mouse 式頭控）。" to arrayOf("Front camera tracks your nose/head to move the cursor; turn to aim, dwell to select (Camera-Mouse style).", "フロントカメラで鼻／頭を追跡しカーソル移動；向けて留めると選択（Camera Mouse 式）。", "전면 카메라로 코／머리를 추적해 커서 이동; 향하고 머무르면 선택 (Camera Mouse 방식)."),
        "轉頭移動游標，停在按鈕上約 1 秒會自動點擊（MediaPipe 鼻尖追蹤）。" to arrayOf("Turn your head to move the cursor; dwell ~1s on a button to click (MediaPipe nose tracking).", "首を向けてカーソル移動、ボタン上で約1秒留めると自動クリック（MediaPipe 鼻先追跡）。", "고개를 돌려 커서 이동, 버튼 위 약 1초 머무르면 자동 클릭 (MediaPipe 코끝 추적)."),
        "重新置中（頭部歸零）" to arrayOf("Recenter (reset head)", "再センタリング（頭部リセット）", "다시 중앙 정렬 (머리 초기화)"),
        "先用自然舒適的姿勢正對螢幕，再按此鍵把目前頭部方向設為中央；之後轉頭即可移動游標。" to arrayOf("Face the screen comfortably, then press this to set the current head direction as center; then turn to move the cursor.", "自然な姿勢で画面に正対し、このキーで現在の頭の向きを中央に設定；以後は首を向けて移動。", "편한 자세로 화면을 정면으로 본 뒤 이 버튼으로 현재 머리 방향을 중앙으로 설정; 이후 고개로 이동."),
        "本地端 AI 安全清理" to arrayOf("On-device AI safe cleanup", "端末内 AI の安全クリーンアップ", "기기 내 AI 안전 정리"),
        "推理後清除快取、暫存與背景模型記憶體；不刪除模型檔。" to arrayOf("After inference, clears cache, temp and background model memory; keeps model files.", "推論後にキャッシュ・一時・背景モデルのメモリを消去；モデルファイルは残す。", "추론 후 캐시·임시·백그라운드 모델 메모리 정리; 모델 파일은 유지."),
        "AAC 意圖確認大圖卡" to arrayOf("AAC intent big-card confirm", "AAC 意図確認の大カード", "AAC 의도 확인 큰 카드"),
        "AI 組句後跳出大圖卡（emoji + 句子）讓不識字使用者 ✓/✗ 確認；關閉時改精簡列，但仍需點擊才朗讀。" to arrayOf("After AI composes, a big card (emoji + sentence) lets non-readers confirm ✓/✗; off shows a compact row but still needs a tap to speak.", "AI 生成後、大カード（絵文字＋文）で非識字者が ✓/✗ 確認；オフでは簡易表示だがタップで発声。", "AI 구성 후 큰 카드(이모지＋문장)로 비문해자가 ✓/✗ 확인; 끄면 간단히 표시하되 탭해야 발성."),
        "意圖卡 AI 生成專屬圖" to arrayOf("AI-generated image on intent card", "意図カードに AI 生成画像", "의도 카드에 AI 생성 이미지"),
        "確認卡額外用 AI 生圖畫出意圖的專屬圖片（不識字更好懂）。會花幾秒、不擋確認鈕；建議搭配本機電腦生圖。" to arrayOf("The confirm card also draws an AI image of the intent (clearer for non-readers). Takes a few seconds, doesn't block the button; pairs well with a local PC.", "確認カードで意図の AI 画像も生成（非識字者に分かりやすい）。数秒かかるがボタンは塞がない；ローカル PC 併用推奨。", "확인 카드에 의도의 AI 이미지도 생성(비문해자에게 유용). 몇 초 걸리나 버튼은 막지 않음; 로컬 PC 권장."),
        "開發者" to arrayOf("Developer", "開発者", "개발자"),
        "開發者模式（GPT-SoVITS / 硬體監控 / 模擬定位 / 模型下載）" to arrayOf("Developer mode (GPT-SoVITS / HW monitor / mock location / model download)", "開発者モード（GPT-SoVITS／HW監視／擬似位置／モデル DL）", "개발자 모드 (GPT-SoVITS／HW 모니터／모의 위치／모델 다운로드)"),
        "返回開發者設定" to arrayOf("Back to developer settings", "開発者設定に戻る", "개발자 설정으로"),
        "GPT-SoVITS 實驗語音" to arrayOf("GPT-SoVITS experimental voice", "GPT-SoVITS 実験音声", "GPT-SoVITS 실험 음성"),
        "開發者模式專屬；可依句子自動切換中英文樣本。" to arrayOf("Developer-only; auto-switches CN/EN samples by sentence.", "開発者専用；文に応じて中英サンプルを自動切替。", "개발자 전용; 문장에 따라 중/영 샘플 자동 전환."),
        "聲紋克隆隱私知情同意" to arrayOf("Voice-clone privacy consent", "声紋クローンのプライバシー同意", "성문 복제 개인정보 동의"),
        "啟用 GPT-SoVITS 語音克隆前請確認：\n1. 參考音檔之聲音主體已同意其聲紋被用於本機語音合成。\n2. 合成語音僅供本人輔助溝通之個人非商業用途。\n3. 不得用合成語音冒充他人或散布誤導內容。\n4. 錄音與模型只存於你的手機與自己的電腦，不上傳雲端。" to arrayOf("Before enabling GPT-SoVITS voice cloning, confirm:\n1. The voice owner consents to their voiceprint being used for local synthesis.\n2. Synthesized voice is for your own personal, non-commercial communication aid only.\n3. Do not impersonate others or spread misleading content.\n4. Recordings and models stay only on your phone and your own computer, never uploaded.", "GPT-SoVITS 音声クローンを有効にする前に確認：\n1. 参照音声の本人が声紋の端末内合成利用に同意。\n2. 合成音声は本人の非商用・意思疎通補助のみ。\n3. 他人へのなりすまし・誤情報拡散は禁止。\n4. 録音とモデルは自分のスマホと PC のみに保存、クラウド非送信。", "GPT-SoVITS 음성 복제 사용 전 확인:\n1. 참조 음성 본인이 성문을 로컬 합성에 사용하는 데 동의.\n2. 합성 음성은 본인의 비상업적 의사소통 보조용만.\n3. 타인 사칭·오해 콘텐츠 유포 금지.\n4. 녹음과 모델은 본인 휴대폰과 PC에만 저장, 클라우드 미업로드."),
        "我已閱讀並同意上述條款" to arrayOf("I have read and agree to the above", "上記に同意します", "위 내용을 읽고 동의합니다"),
        "同意並啟用" to arrayOf("Agree & enable", "同意して有効化", "동의하고 사용"),
        "語言會依輸入文字自動切換（中／英／日／韓），不需手動選。" to arrayOf("Language auto-switches by input text (CN/EN/JA/KO); no manual choice needed.", "言語は入力文字で自動切替（中／英／日／韓）、手動選択不要。", "언어는 입력 문자로 자동 전환(중／영／일／한), 수동 선택 불필요."),
        "模型合成測試（多語言）" to arrayOf("Model synthesis test (multilingual)", "モデル合成テスト（多言語）", "모델 합성 테스트 (다국어)"),
        "合成中…" to arrayOf("Synthesizing…", "合成中…", "합성 중…"),
        "模型由電腦設定頁管理" to arrayOf("Models managed on the computer", "モデルは PC 設定で管理", "모델은 PC 설정에서 관리"),
        "在 Mac 開啟 http://127.0.0.1:9890 匯入 .ckpt / .pth / .wav 與參考文字。App 只負責呼叫目前電腦 server 載入的模型。" to arrayOf("On Mac open http://127.0.0.1:9890 to import .ckpt/.pth/.wav and reference text. The app only calls the model currently loaded on the computer server.", "Mac で http://127.0.0.1:9890 を開き .ckpt/.pth/.wav と参照文を取込。アプリは PC サーバーが読み込み中のモデルを呼ぶだけ。", "Mac에서 http://127.0.0.1:9890 열어 .ckpt/.pth/.wav와 참조 텍스트 가져오기. 앱은 PC 서버가 로드한 모델만 호출."),
        "用 Mac 跑 SoVITS（無線 / 有線）" to arrayOf("Run SoVITS on Mac (wireless / wired)", "Mac で SoVITS を実行（無線／有線）", "Mac에서 SoVITS 실행 (무선／유선)"),
        "GPT-SoVITS 要 Python + PyTorch，Android 內跑不了 .pth 權重。\n做法：Mac 開 server，手機用 Wi-Fi 或 USB 連過去。" to arrayOf("GPT-SoVITS needs Python + PyTorch; Android can't run .pth weights.\nHow: run a server on Mac, connect the phone via Wi-Fi or USB.", "GPT-SoVITS は Python + PyTorch が必要で、Android では .pth を実行不可。\n方法：Mac でサーバー起動、スマホを Wi-Fi か USB で接続。", "GPT-SoVITS는 Python + PyTorch 필요, Android는 .pth 실행 불가.\n방법: Mac에서 서버 실행, 휴대폰을 Wi-Fi나 USB로 연결."),
        "電腦清單" to arrayOf("Computer list", "PC 一覧", "컴퓨터 목록"),
        "USB 電腦" to arrayOf("USB computer", "USB PC", "USB 컴퓨터"),
        "電腦 %d" to arrayOf("Computer %d", "PC %d", "컴퓨터 %d"),
        "＋ 存目前位址" to arrayOf("＋ Save current address", "＋ 現在のアドレスを保存", "＋ 현재 주소 저장"),
        "尚無已存電腦。填好 API 位址後按「＋ 存目前位址」。" to arrayOf("No saved computers. Enter the API address then tap “＋ Save current address”.", "保存済み PC なし。API アドレス入力後「＋ 現在のアドレスを保存」。", "저장된 컴퓨터 없음. API 주소 입력 후 ‘＋ 현재 주소 저장’."),
        "刪除 %s" to arrayOf("Delete %s", "%s を削除", "%s 삭제"),
        "尚未測試連線" to arrayOf("Connection not tested", "接続未テスト", "연결 미테스트"),
        "已加入 Tailscale 電腦：%s（%s）" to arrayOf("Added Tailscale computer: %s (%s)", "Tailscale PC を追加：%s（%s）", "Tailscale 컴퓨터 추가: %s (%s)"),
        "測試連線" to arrayOf("Test connection", "接続テスト", "연결 테스트"),
        "角色模型" to arrayOf("Character models", "キャラクターモデル", "캐릭터 모델"),
        "顯示 %d/%d" to arrayOf("Showing %d/%d", "表示 %d/%d", "표시 %d/%d"),
        "電腦上尚無任何模型，請在 Mac 語音中心匯入後按上方「測試連線」重新整理。" to arrayOf("No models on the computer yet — import them in the Mac voice center, then tap “Test connection” above to refresh.", "PC にモデルがありません。Mac のボイスセンターで取込後、上の「接続テスト」で更新。", "PC에 모델이 없습니다. Mac 보이스 센터에서 가져온 뒤 위 ‘연결 테스트’로 새로고침."),
        "目前介面語言（%s）沒有對應模型。請在 Mac 語音中心匯入含「%s」標籤的模型。" to arrayOf("No model matches the current UI language (%s). In the Mac voice center, import a model tagged “%s”.", "現在の UI 言語（%s）に対応するモデルがありません。Mac のボイスセンターで「%s」タグ付きモデルを取込。", "현재 UI 언어(%s)에 맞는 모델이 없습니다. Mac 보이스 센터에서 ‘%s’ 태그 모델을 가져오세요."),
        "⏳ 正在切換到 %s…" to arrayOf("⏳ Switching to %s…", "⏳ %s に切替中…", "⏳ %s(으)로 전환 중…"),
        "✅ 已切換到 %s，ref/prompt 已自動跟上" to arrayOf("✅ Switched to %s; ref/prompt updated automatically", "✅ %s に切替、ref/prompt も自動更新", "✅ %s(으)로 전환, ref/prompt 자동 반영"),
        "切換 App 介面語言即可篩選對應聲音模型；切換約需數十秒（CPU 載權重），同語言內連續講不會重切。" to arrayOf("Switch the app UI language to filter matching voice models; switching takes tens of seconds (CPU loads weights); consecutive speech in the same language won't re-switch.", "アプリ UI 言語を切替で対応音声モデルを絞込；切替は数十秒（CPU が重み読込）、同一言語の連続発話は再切替なし。", "앱 UI 언어를 바꾸면 해당 음성 모델만 필터; 전환은 수십 초(CPU 가중치 로드), 같은 언어 연속 발화는 재전환 없음."),
        "複製啟動指令" to arrayOf("Copy start command", "起動コマンドをコピー", "시작 명령 복사"),
        "複製 USB 指令" to arrayOf("Copy USB command", "USB コマンドをコピー", "USB 명령 복사"),
        "複製查 IP" to arrayOf("Copy IP lookup", "IP 確認をコピー", "IP 조회 복사"),
        "電腦接手計畫：%s" to arrayOf("Offload plan: %s", "PC 引受プラン：%s", "PC 처리 계획: %s"),
        "重新量測" to arrayOf("Re-measure", "再計測", "재측정"),
        "有線 USB" to arrayOf("Wired USB", "有線 USB", "유선 USB"),
        "無線 Wi-Fi" to arrayOf("Wireless Wi-Fi", "無線 Wi-Fi", "무선 Wi-Fi"),
        "、延遲 %dms" to arrayOf(", latency %dms", "、遅延 %dms", ", 지연 %dms"),
        "連線：%s（%s%s）" to arrayOf("Link: %s (%s%s)", "接続：%s（%s%s）", "연결: %s (%s%s)"),
        "未連接電腦：全部運算由手機本機處理" to arrayOf("No computer connected: all compute runs on the phone", "PC 未接続：全ての計算をスマホで処理", "컴퓨터 미연결: 모든 연산을 휴대폰에서 처리"),
        "🔊 語音合成" to arrayOf("🔊 Voice synthesis", "🔊 音声合成", "🔊 음성 합성"),
        "🖼 生圖" to arrayOf("🖼 Image gen", "🖼 画像生成", "🖼 이미지 생성"),
        "💬 文字重組" to arrayOf("💬 Text compose", "💬 テキスト再構成", "💬 텍스트 재구성"),
        "由 %s 處理" to arrayOf("Handled by %s", "%s が処理", "%s 처리"),
        "系統 RAM" to arrayOf("System RAM", "システム RAM", "시스템 RAM"),
        "電量" to arrayOf("Battery", "バッテリー", "배터리"),
        "電池溫" to arrayOf("Batt. temp", "電池温度", "배터리 온도"),
        "網路" to arrayOf("Network", "ネットワーク", "네트워크"),
        "本 App CPU 趨勢" to arrayOf("App CPU trend", "本アプリ CPU 推移", "앱 CPU 추세"),
        "網路下載速度" to arrayOf("Network download speed", "ネット下り速度", "네트워크 다운로드 속도"),
        "Hugging Face 公開模型下載" to arrayOf("Hugging Face public model download", "Hugging Face 公開モデル DL", "Hugging Face 공개 모델 다운로드"),
        "貼上公開 .task 模型 URL" to arrayOf("Paste a public .task model URL", ".task モデルの公開 URL を貼付", "공개 .task 모델 URL 붙여넣기"),
        "不需要帳號或 API key；只有公開直連 URL 可下載。MediaPipe 推理目前會啟用 .task 格式。" to arrayOf("No account or API key needed; only public direct URLs work. MediaPipe currently uses the .task format.", "アカウントや API キー不要；公開直リンクのみ。MediaPipe は現在 .task 形式を使用。", "계정·API 키 불필요; 공개 직접 URL만. MediaPipe는 현재 .task 형식 사용."),
        "測試中" to arrayOf("Testing", "テスト中", "테스트 중"),
        "測試連線 " to arrayOf("Test connection", "接続テスト", "연결 테스트"),
        "下載 URL" to arrayOf("Download URL", "URL をダウンロード", "URL 다운로드"),
        "正在測試公開 URL..." to arrayOf("Testing public URL…", "公開 URL をテスト中…", "공개 URL 테스트 중…"),
        "未知" to arrayOf("Unknown", "不明", "알 수 없음"),
        "本地 AI 模型已就緒" to arrayOf("On-device AI model ready", "端末内 AI モデル準備完了", "기기 내 AI 모델 준비됨"),
        "尚未啟動模型載入" to arrayOf("Model loading not started", "モデル読込未開始", "모델 로드 미시작"),
        "效能監控與 Hugging Face 模型管理" to arrayOf("Performance monitor & Hugging Face models", "性能監視と Hugging Face モデル管理", "성능 모니터 및 Hugging Face 모델 관리"),
        "關閉時不取樣，避免額外耗電。" to arrayOf("When off, no sampling — saves battery.", "オフ時はサンプリングせず省電力。", "꺼두면 샘플링 안 함 — 배터리 절약."),
        "🖼 AI 生圖（輸入描述生成圖片、可設背景；本機電腦或雲端）" to arrayOf("🖼 AI image (describe to generate, set as background; local PC or cloud)", "🖼 AI 画像（説明から生成・背景設定；ローカル PC またはクラウド）", "🖼 AI 이미지 (설명으로 생성·배경 설정; 로컬 PC 또는 클라우드)"),
        "開啟 GPT-SoVITS 匯入與中英語音切換" to arrayOf("Open GPT-SoVITS import & CN/EN voice switch", "GPT-SoVITS 取込と中英音声切替を開く", "GPT-SoVITS 가져오기 및 중/영 음성 전환 열기"),
        "已永久保存 URI 權限" to arrayOf("URI permission saved permanently", "URI 権限を永続保存", "URI 권한 영구 저장"),
        "尚未匯入" to arrayOf("Not imported", "未取込", "가져오지 않음"),
        "匯入" to arrayOf("Import", "取込", "가져오기"),
        "開啟系統檔案頁" to arrayOf("Open system file picker", "システムのファイル画面を開く", "시스템 파일 화면 열기"),
        "使用預設資料夾" to arrayOf("Use default folder", "既定フォルダを使う", "기본 폴더 사용"),
        "測試中 " to arrayOf("Testing", "テスト中", "테스트 중"),
        "內容涉及高風險意圖，語音輸出已鎖定。請照護者協助確認。" to arrayOf("This may involve high-risk intent; voice output is locked. Please have a caregiver confirm.", "高リスクの意図の可能性があり、音声出力をロックしました。介護者に確認を依頼してください。", "고위험 의도가 있을 수 있어 음성 출력이 잠겼습니다. 보호자 확인을 요청하세요."),
        "偵測到高風險訊息，正在通報緊急聯絡人…" to arrayOf("High-risk message detected; notifying the emergency contact…", "高リスクのメッセージを検出、緊急連絡先へ通報中…", "고위험 메시지 감지, 긴급 연락처에 알리는 중…"),
        "⚠ 已通報緊急聯絡人。語音輸出已鎖定，請照護者協助。\n如有立即危險請撥打 1925 安心專線或 119。" to arrayOf("⚠ Emergency contact notified. Voice output is locked; please have a caregiver help.\nIf in immediate danger, call your local crisis line or emergency number.", "⚠ 緊急連絡先へ通報しました。音声出力はロック中、介護者の支援を。\n差し迫った危険があれば相談ダイヤルや緊急番号へ。", "⚠ 긴급 연락처에 알렸습니다. 음성 출력이 잠김, 보호자 도움을 받으세요.\n즉각적 위험 시 상담 전화나 긴급 번호로."),
        "⚠ 偵測到高風險訊息，但通報傳送失敗（%s）。請直接撥打 1925 安心專線或 119。" to arrayOf("⚠ High-risk message detected but the alert failed to send (%s). Please call your local crisis line or emergency number directly.", "⚠ 高リスクを検出しましたが通報の送信に失敗（%s）。相談ダイヤルや緊急番号へ直接。", "⚠ 고위험 감지, 그러나 알림 전송 실패(%s). 상담 전화나 긴급 번호로 직접 연락."),
        "⚠ 偵測到高風險訊息，語音輸出已鎖定。請撥打 1925 安心專線或 119。" to arrayOf("⚠ High-risk message detected; voice output is locked. Please call your local crisis line or emergency number.", "⚠ 高リスクを検出、音声出力をロック。相談ダイヤルや緊急番号へ。", "⚠ 고위험 감지, 음성 출력 잠김. 상담 전화나 긴급 번호로."),
        "鼻子頭控追蹤中：轉頭移動游標" to arrayOf("Nose head-tracking on: turn your head to move the cursor", "鼻ヘッドトラッキング中：首を向けてカーソル移動", "코 헤드 추적 중: 고개를 돌려 커서 이동"),
        "已重新置中：保持自然姿勢，轉頭即可移動游標" to arrayOf("Recentered: keep a natural posture; turn your head to move the cursor", "再センタリング完了：自然な姿勢で、首を向けて移動", "다시 중앙 정렬: 자연스러운 자세로, 고개를 돌려 이동"),
        "正在通知家人…" to arrayOf("Notifying family…", "家族に通知中…", "가족에게 알리는 중…"),
        "已把現場畫面與視訊連結傳給家人" to arrayOf("Sent the scene and a video link to family", "現場の画像とビデオリンクを家族に送信", "현장 화면과 영상 링크를 가족에게 전송"),
        "家人正在陪你 💙" to arrayOf("Family is with you 💙", "家族がそばにいます 💙", "가족이 곁에 있어요 💙"),
        "凝視選取：%s" to arrayOf("Gaze select: %s", "視線選択：%s", "시선 선택: %s"),
        "已通知家人，請稍等。你並不孤單，我們一起慢慢呼吸。" to arrayOf("Family has been notified — please wait. You're not alone; let's breathe slowly together.", "家族に通知しました、少しお待ちを。あなたは一人ではありません、一緒にゆっくり呼吸を。", "가족에게 알렸어요, 잠시만요. 혼자가 아니에요, 함께 천천히 숨 쉬어요."),
        "可點下方「開始即時視訊通話」與家人面對面。" to arrayOf("Tap “Start a live video call” below to see family face to face.", "下の「ビデオ通話を開始」で家族と対面できます。", "아래 ‘영상 통화 시작’으로 가족과 대면할 수 있어요."),
        "已把現場前後鏡頭畫面傳給家人，並送出視訊通話連結。" to arrayOf("Sent front & rear camera views to family, plus a video-call link.", "前後カメラの画像と通話リンクを家族に送信。", "전후 카메라 화면과 통화 링크를 가족에게 전송."),
        "已把視訊通話連結傳給家人。" to arrayOf("Sent a video-call link to family.", "通話リンクを家族に送信。", "통화 링크를 가족에게 전송."),
        "家人發起視訊通話，正在自動加入…" to arrayOf("Family started a video call; joining automatically…", "家族がビデオ通話を開始、自動参加中…", "가족이 영상 통화 시작, 자동 참여 중…"),
        "家人要求再次拍攝現場，拍攝中…" to arrayOf("Family asked for another photo of the scene; capturing…", "家族が現場の再撮影を要求、撮影中…", "가족이 현장 재촬영 요청, 촬영 중…"),
        "已再次把現場畫面傳給家人。" to arrayOf("Sent another photo of the scene to family.", "現場の画像を再度家族に送信。", "현장 화면을 다시 가족에게 전송."),
        "再次拍攝失敗（請確認相機權限）。" to arrayOf("Re-capture failed (check camera permission).", "再撮影に失敗（カメラ権限を確認）。", "재촬영 실패 (카메라 권한 확인)."),
        "家人已結束對話，好好照顧自己 💙" to arrayOf("Family ended the conversation. Take good care of yourself 💙", "家族が対話を終了しました。ご自愛を 💙", "가족이 대화를 종료했어요. 잘 챙기세요 💙"),
        "（未知指令：%s）可用 /call /camera /end" to arrayOf("(Unknown command: %s) Available: /call /camera /end", "（不明なコマンド：%s）利用可：/call /camera /end", "(알 수 없는 명령: %s) 사용 가능: /call /camera /end"),
        "（無法錄音，請確認麥克風權限）" to arrayOf("(Can't record — check microphone permission)", "（録音できません、マイク権限を確認）", "(녹음 불가 — 마이크 권한 확인)"),
        "語音太短，未送出。" to arrayOf("Voice message too short — not sent.", "音声が短すぎるため未送信。", "음성이 너무 짧아 전송 안 함."),
        "復健提醒" to arrayOf("Practice reminder", "リハビリ通知", "재활 알림"),
        "每日語音復健練習提醒" to arrayOf("Daily speech-practice reminder", "毎日の発話練習通知", "매일 언어 연습 알림"),
        "該練習語音復健囉 🎤" to arrayOf("Time for speech practice 🎤", "発話練習の時間です 🎤", "언어 연습 시간이에요 🎤"),
        "每天一點點，持續進步。點我開始今天的練習。" to arrayOf("A little each day adds up. Tap to start today's practice.", "毎日少しずつで着実に。タップで今日の練習を開始。", "매일 조금씩 꾸준히. 탭하면 오늘 연습 시작."),
        "請貼上公開 HTTPS 模型下載 URL" to arrayOf("Please paste a public HTTPS model download URL", "公開 HTTPS モデル DL URL を貼付してください", "공개 HTTPS 모델 다운로드 URL을 붙여넣으세요"),
        "請貼上 HTTPS 模型 URL" to arrayOf("Please paste an HTTPS model URL", "HTTPS モデル URL を貼付してください", "HTTPS 모델 URL을 붙여넣으세요"),
        "重組" to arrayOf("Compose", "再構成", "재구성"),
        "圖卡" to arrayOf("Cards", "カード", "카드"),

        // ── 功能介紹 ＋ 螢幕錄影 ──
        // 面板本身跟著介面語言走；影片裡的旁白與字幕一律英文（報告用途），
        // 所以單元名稱刻意不翻——那是影片的段落名，不是介面文字。
        "功能介紹" to arrayOf("Feature tour", "機能紹介", "기능 소개"),
        "正在錄製功能介紹…" to arrayOf("Recording the feature tour…", "機能紹介を録画中…", "기능 소개 녹화 중…"),
        "螢幕錄影" to arrayOf("Screen recording", "画面録画", "화면 녹화"),
        "功能介紹與螢幕錄影" to arrayOf(
            "Feature tour & screen recording", "機能紹介と画面録画", "기능 소개 및 화면 녹화"),
        "自動走過主要功能並錄影。全程不寫入資料、不送出任何訊息——危機介入與求救只是介紹，不會真的觸發。" to arrayOf(
            "Walks through the app on its own and records the screen. Nothing is written and no messages are sent — the crisis and SOS sections are described, not triggered.",
            "アプリを自動で一巡し、画面を録画します。データは書き込まれず、メッセージも送信されません（危機介入とSOSは説明のみ）。",
            "앱을 자동으로 한 바퀴 돌며 화면을 녹화합니다. 데이터는 기록되지 않고 메시지도 전송되지 않습니다(위기 개입과 SOS는 설명만)."),
        "全選" to arrayOf("Select all", "すべて選択", "전체 선택"),
        "清除" to arrayOf("Clear", "クリア", "지우기"),
        "個單元" to arrayOf("sections", "セクション", "섹션"),
        "英文旁白與字幕" to arrayOf(
            "Narration + subtitles (English)", "英語ナレーションと字幕", "영어 내레이션 및 자막"),
        "每一句都在開始錄影前先合成好，影片裡不會有等待。電腦用 CPU 跑 GPT-SoVITS 時，第一次每句約 10～30 秒；合成過的會留著，第二次幾乎不用等。" to arrayOf(
            "Every line is synthesised before recording starts, so the video has no waiting in it. With GPT-SoVITS on a computer CPU that takes roughly 10–30 s per line the first time; lines are cached, so a second run starts almost immediately.",
            "各行は録画開始前に合成されるため、動画に待ち時間は入りません。GPT-SoVITSをCPUで動かす場合、初回は1行あたり約10〜30秒。合成済みは保持されるので2回目はほぼ待ちません。",
            "각 문장은 녹화 시작 전에 합성되므로 영상에 대기 시간이 없습니다. 컴퓨터 CPU로 GPT-SoVITS를 돌리면 처음에는 문장당 약 10~30초 걸리며, 합성한 것은 보관되어 두 번째부터는 거의 기다리지 않습니다."),
        "把畫面錄成影片" to arrayOf(
            "Record the screen to a video file", "画面を動画ファイルに録画", "화면을 동영상 파일로 녹화"),
        "把旁白一起錄進影片" to arrayOf(
            "Include the narration in the video", "ナレーションを動画に含める", "내레이션을 영상에 포함"),
        "Android 規定 App 錄自己的畫面要你同意，會跳出系統視窗。旁白是直接取自 App 自己的音訊，不經過麥克風，所以不會錄到房間裡的聲音。影片會存到 Movies/VoiceWeaver，在相簿裡看得到。" to arrayOf(
            "Android only lets an app record its own screen with your permission — a system prompt will appear. The narration is taken straight from the app's own audio, not through the microphone, so nothing in the room is recorded.\nThe finished video is saved to Movies/VoiceWeaver and shows up in your gallery.",
            "Androidではアプリが自分の画面を録画するのに許可が必要で、システムの確認画面が出ます。ナレーションはアプリ自身の音声から直接取得し、マイクは使わないため周囲の音は入りません。\n動画は Movies/VoiceWeaver に保存され、ギャラリーに表示されます。",
            "Android에서는 앱이 자기 화면을 녹화하려면 권한이 필요해 시스템 창이 나타납니다. 내레이션은 앱 자체 오디오에서 직접 가져오며 마이크를 쓰지 않으므로 주변 소리는 녹음되지 않습니다.\n영상은 Movies/VoiceWeaver에 저장되어 갤러리에 표시됩니다."),
        "準備旁白中…" to arrayOf("Preparing narration…", "ナレーションを準備中…", "내레이션 준비 중…"),
        "句" to arrayOf("lines", "行", "문장"),
        "停止" to arrayOf("Stop", "停止", "중지"),
        // 自動演示注入危機視窗的內容。介面是英文、訊息卻是中文的話，
        // 錄出來就是一頁中英夾雜——這些一定要跟著介面語言走。
        "正在通知家人…" to arrayOf("Contacting your family…", "家族に連絡しています…", "가족에게 연락 중…"),
        "已通知家人" to arrayOf("Family notified", "家族に通知しました", "가족에게 알렸습니다"),
        "已通知家人 · 視訊隨時可開" to arrayOf(
            "Family notified · video call ready", "家族に通知済み・ビデオ通話可能", "가족에게 알림 · 영상통화 가능"),
        "我好害怕" to arrayOf("I am scared", "こわいです", "무서워요"),
        "我在路上了，你先坐下來，我十分鐘就到" to arrayOf(
            "I am on my way. Sit down for me — ten minutes.",
            "今向かっています。座って待っていて、あと10分です。",
            "지금 가는 중이에요. 앉아서 기다려요, 10분이면 도착해요."),
        "我好一點了" to arrayOf("I feel a little better", "少し落ち着きました", "조금 나아졌어요"),
        "我需要幫忙" to arrayOf("I need help", "助けてください", "도와주세요"),
        "把旁白一起錄進影片（同時產生一份無旁白版）" to arrayOf(
            "Include the narration — and save a silent copy too",
            "ナレーションを含める（無音版も同時に保存）",
            "내레이션 포함 — 무음 버전도 함께 저장"),
        "我想要喝一杯水" to arrayOf("I would like a glass of water", "水を一杯ください", "물 한 잔 주세요"),
        "旁白錄不起來（{n}/{total} 句）：連不到電腦端的 GPT-SoVITS。請確認語音中心開著、位址設定正確，再試一次。" to arrayOf(
            "Could not record the narration ({n} of {total} lines): the computer's GPT-SoVITS is unreachable. Check that the voice centre is running and the address is correct, then try again.",
            "ナレーションを録音できません（{n}/{total} 行）：PCのGPT-SoVITSに接続できません。ボイスセンターが起動しているか、アドレスが正しいか確認してから再試行してください。",
            "내레이션을 녹음할 수 없습니다({n}/{total} 문장): 컴퓨터의 GPT-SoVITS에 연결할 수 없습니다. 보이스 센터가 실행 중인지, 주소가 올바른지 확인한 뒤 다시 시도하세요."),
        "電腦端語音（GPT-SoVITS）沒有啟用，旁白會用手機的系統語音唸。要用你自己的角色語音，請先到設定把電腦端語音打開並連上。" to arrayOf(
            "Computer voice (GPT-SoVITS) is off, so the narration will use the phone's system voice. To hear your own voice, turn on the computer voice in Settings and connect first.",
            "PC音声（GPT-SoVITS）が無効なため、ナレーションは端末のシステム音声になります。自分の声を使うには設定でPC音声を有効にして接続してください。",
            "컴퓨터 음성(GPT-SoVITS)이 꺼져 있어 내레이션은 휴대폰 시스템 음성으로 재생됩니다. 본인 목소리를 쓰려면 설정에서 컴퓨터 음성을 켜고 연결하세요."),
        "開始" to arrayOf("Start", "開始", "시작"),
        "常用" to arrayOf("For you", "よく使う", "자주 사용"),
        "語音復健" to arrayOf("Rehab", "発話リハビリ", "언어 재활"),
        "報告" to arrayOf("Report", "レポート", "리포트"),
        "歷史" to arrayOf("History", "履歴", "기록"),
        "設定" to arrayOf("Settings", "設定", "설정"),

        // ── AacScreen 補遺 ──
        "清除" to arrayOf("Clear", "クリア", "지우기"),
        "嘗試調整搜尋條件或選擇其他分類" to arrayOf("Try different search terms or another category", "検索条件を変えるか別のカテゴリを選んでください", "검색어를 바꾸거나 다른 분류를 선택해 보세요"),
        "選擇操作" to arrayOf("Choose an action", "操作を選択", "동작 선택"),
        "加入句子" to arrayOf("Add to sentence", "文に追加", "문장에 추가"),

        // ── CustomApiFloatingWindow（API 金鑰管理浮窗）──
        "文字 LLM" to arrayOf("Text LLM", "テキスト LLM", "텍스트 LLM"),
        "填好金鑰就好，不用選要用哪家——系統會依實測成功率與速度自動挑，失敗自動換下一家，免費的優先用。想固定用某一家再點左邊圖釘。" to arrayOf(
            "Just enter keys — no need to pick one. The system chooses by measured success rate and speed, fails over automatically, and prefers free providers. Pin one on the left to lock it.",
            "キーを入れるだけでOK。成功率と速度で自動選択し、失敗時は次へ切替、無料を優先。固定したい場合は左のピンをタップ。",
            "키만 입력하면 됩니다. 성공률과 속도로 자동 선택, 실패 시 자동 전환, 무료 우선. 고정하려면 왼쪽 핀을 누르세요."),
        "語音合成" to arrayOf("Speech synthesis", "音声合成", "음성 합성"),
        "加幾家不同的聲音，系統會自動挑最穩最快的那家；某一家掛掉會自動換下一家。點喇叭可以先試聽。" to arrayOf(
            "Add several voices; the system picks the most stable and fastest one, and falls over to the next if one goes down. Tap the speaker to preview.",
            "複数の声を追加すると、最も安定して速いものを自動選択。1 つが落ちたら自動で次へ切替。スピーカーで試聴可。",
            "여러 목소리를 추가하면 가장 안정적이고 빠른 것을 자동 선택하며, 하나가 실패하면 다음으로 자동 전환. 스피커로 미리듣기."),
        "免金鑰，直接可用" to arrayOf("No key needed — works right away", "キー不要、すぐ使える", "키 불필요, 바로 사용"),
        "已釘選（固定優先）" to arrayOf("Pinned (always first)", "ピン留め済み（常に優先）", "고정됨 (항상 우선)"),
        "釘選以固定優先使用" to arrayOf("Pin to always use first", "ピン留めで常に優先", "고정하여 항상 우선 사용"),
        "・已釘選" to arrayOf(" · Pinned", "・ピン留め", " · 고정됨"),
        "試聽" to arrayOf("Preview", "試聴", "미리듣기"),
        "電腦位址" to arrayOf("Computer address", "PC アドレス", "PC 주소"),
        "聲音" to arrayOf("Voice", "声", "목소리"),
        "・只有英文" to arrayOf(" · English only", "・英語のみ", " · 영어만"),
        "GPT-SoVITS API 位址" to arrayOf(
            "GPT-SoVITS API address", "GPT-SoVITS API アドレス", "GPT-SoVITS API 주소"),
        "http://電腦IP:9880" to arrayOf(
            "http://<computer IP>:9880", "http://<PC の IP>:9880", "http://<컴퓨터 IP>:9880"),
        // 裝置端評分提示詞的 few-shot 範例評語（要跟著介面語言，模型會照抄範例的語言）
        "意思很清楚，很好！" to arrayOf(
            "Very clear meaning — nice work!", "意味がはっきり伝わっています、いいですね！", "의미가 아주 분명해요, 잘했어요!"),
        "沒有聽到聲音，請靠近麥克風再試一次" to arrayOf(
            "Didn't hear anything — move closer to the mic and try again",
            "音声を検出できませんでした。マイクに近づいてもう一度お試しください",
            "소리가 들리지 않았어요. 마이크에 가까이서 다시 시도해 주세요"),
        "低信心" to arrayOf("low confidence", "低信頼度", "낮은 신뢰도"),
        // 重組結果卡的引擎來源標示
        "電腦接手（本機/雲端備援待命）" to arrayOf(
            "Computer took over (on-device/cloud on standby)",
            "PC が処理（端末内／クラウドは待機）",
            "PC가 처리 (기기 내/클라우드 대기)"),
        "自我一致性 %d 取樣" to arrayOf(
            "self-consistency, %d samples", "自己一貫性 %d サンプル", "자기일관성 %d 표본"),
        "單次取樣（其餘取樣失敗）" to arrayOf(
            "single sample (the others failed)", "1 回のみ（他のサンプルは失敗）", "1회 표본 (나머지는 실패)"),
        "%s（%s／本地備援待命）" to arrayOf(
            "%s (%s / on-device on standby)", "%s（%s／端末内は待機）", "%s (%s / 기기 내 대기)"),
        "雲端 Whisper 轉寫" to arrayOf(
            "cloud Whisper transcription", "クラウド Whisper 文字起こし", "클라우드 Whisper 전사"),
        "直接聽音訊（%d 取樣，未經語音轉文字）" to arrayOf(
            "listened to the audio directly (%d samples, no speech-to-text)",
            "音声を直接聴取（%d サンプル、音声認識なし）",
            "오디오를 직접 청취 (%d 표본, 음성 인식 없음)"),
        // 復健跟讀走雲端 Whisper 的錯誤訊息
        "連不上雲端語音辨識，請檢查網路後再試一次" to arrayOf(
            "Can't reach cloud speech recognition — check your connection and try again",
            "クラウド音声認識に接続できません。通信を確認して再試行してください",
            "클라우드 음성 인식에 연결할 수 없습니다. 네트워크를 확인하고 다시 시도하세요"),
        "沒有可用的雲端語音轉寫供應商" to arrayOf(
            "No cloud speech-to-text provider available",
            "利用できるクラウド音声認識プロバイダーがありません",
            "사용 가능한 클라우드 음성 인식 제공자가 없습니다"),
        "雲端語音轉寫沒有回傳結果" to arrayOf(
            "Cloud speech-to-text returned nothing",
            "クラウド音声認識が結果を返しませんでした",
            "클라우드 음성 인식이 결과를 반환하지 않았습니다"),
        // SmartApiRouter.statusLabel（供應商實測狀態列）
        "尚未使用過" to arrayOf("Not used yet", "未使用", "아직 사용 안 함"),
        "成功率 %d%%" to arrayOf("%d%% success", "成功率 %d%%", "성공률 %d%%"),
        "・約 %dms" to arrayOf(" · ~%dms", "・約 %dms", " · 약 %dms"),
        "・暫時跳過（剛失敗）" to arrayOf(
            " · skipped for now (just failed)", "・一時スキップ（直前に失敗）", " · 잠시 건너뜀 (방금 실패)"),

        // ── SettingsScreen 補遺 ──
        "🎙 角色語音與雲端曲庫" to arrayOf("🎙 Voices & cloud library", "🎙 音声モデルとクラウドライブラリ", "🎙 음성 모델·클라우드 라이브러리"),
        "角色語音與雲端曲庫" to arrayOf("Voices & cloud library", "音声モデルとクラウドライブラリ", "음성 모델·클라우드 라이브러리"),
        "使用模式" to arrayOf("Usage mode", "使用モード", "사용 모드"),
        "依嚴重程度" to arrayOf("By severity", "重症度別", "중증도별"),
        "外觀" to arrayOf("Appearance", "外観", "모양"),
        "效能與硬體" to arrayOf("Performance & hardware", "性能とハードウェア", "성능 및 하드웨어"),
        "顯示與朗讀" to arrayOf("Display & speech", "表示と読み上げ", "표시 및 읽기"),
        "無障礙輔助功能" to arrayOf("Accessibility", "アクセシビリティ", "접근성"),
        "嚴重程度" to arrayOf("Severity", "重症度", "중증도"),
        "一般" to arrayOf("General", "一般", "일반"),
        "推理後自動清除快取與暫存。" to arrayOf("Auto-clears cache and temp files after inference.", "推論後にキャッシュと一時ファイルを自動消去。", "추론 후 캐시와 임시 파일 자동 정리."),
        "刪除背景" to arrayOf("Remove background", "背景を削除", "배경 삭제"),
        "啟用鼻子頭控" to arrayOf("Enable nose head-control", "鼻ヘッドコントロールを有効化", "코 헤드 컨트롤 켜기"),
        "轉頭移動游標，停留即選取。" to arrayOf("Turn your head to move the cursor; dwell to select.", "首でカーソル移動、留めると選択。", "고개로 커서 이동, 머무르면 선택."),
        "AAC 確認卡" to arrayOf("AAC confirm card", "AAC 確認カード", "AAC 확인 카드"),
        "意圖確認大圖卡" to arrayOf("Big intent-confirm card", "意図確認の大カード", "의도 확인 큰 카드"),
        "組句後跳出大圖卡供確認。" to arrayOf("Shows a big card to confirm after composing.", "文の生成後に大カードで確認。", "문장 구성 후 큰 카드로 확인."),
        "AI 生成專屬圖" to arrayOf("AI-generated image", "AI 生成画像", "AI 생성 이미지"),
        "需搭配電腦生圖服務。" to arrayOf("Requires the PC image service.", "PC の画像生成サービスが必要。", "PC 이미지 생성 서비스 필요."),
        "可依句子自動切換中英文樣本。" to arrayOf("Auto-switches CN/EN samples by sentence.", "文に応じて中英サンプルを自動切替。", "문장에 따라 중/영 샘플 자동 전환."),
        "☁️ 用 Colab 免費 GPU（ngrok 通道）" to arrayOf("☁️ Free Colab GPU (ngrok tunnel)", "☁️ Colab 無料 GPU（ngrok トンネル）", "☁️ Colab 무료 GPU (ngrok 터널)"),
        "用 Colab 免費 GPU 跑語音合成時填 ngrok 授權碼。存到雲端後，Colab 用下方配對碼自動取用，授權碼不必貼進 Colab。" to arrayOf(
            "Enter your ngrok authtoken to synthesize on a free Colab GPU. Once saved to the cloud, Colab fetches it with the pairing code below — no need to paste it into Colab.",
            "Colab の無料 GPU で音声合成する際に ngrok トークンを入力。クラウド保存後は下のペアリングコードで Colab が自動取得、貼り付け不要。",
            "Colab 무료 GPU로 합성 시 ngrok 토큰 입력. 클라우드 저장 후 Colab이 아래 페어링 코드로 자동 사용, 붙여넣기 불필요."),
        "ngrok 授權碼" to arrayOf("ngrok authtoken", "ngrok トークン", "ngrok 토큰"),
        "從 dashboard.ngrok.com 複製" to arrayOf("Copy from dashboard.ngrok.com", "dashboard.ngrok.com からコピー", "dashboard.ngrok.com에서 복사"),
        "固定網域（選填，建議）" to arrayOf("Static domain (optional, recommended)", "固定ドメイン（任意・推奨）", "고정 도메인 (선택, 권장)"),
        "例 voiceweaver.ngrok-free.app" to arrayOf("e.g. voiceweaver.ngrok-free.app", "例 voiceweaver.ngrok-free.app", "예 voiceweaver.ngrok-free.app"),
        "Colab 配對碼" to arrayOf("Colab pairing code", "Colab ペアリングコード", "Colab 페어링 코드"),
        "📋 複製" to arrayOf("📋 Copy", "📋 コピー", "📋 복사"),
        "授權碼只存在你的帳號與一份用配對碼保護的雲端文件。需登入雲端同步才會生效。" to arrayOf(
            "The token is stored only in your account and one cloud file protected by the pairing code. Cloud sign-in is required.",
            "トークンはあなたのアカウントと、ペアリングコードで保護された1つのクラウドファイルにのみ保存。クラウドログインが必要。",
            "토큰은 계정과 페어링 코드로 보호된 클라우드 문서에만 저장. 클라우드 로그인 필요."),
        "手機語音要用電腦還是 Colab 免費 GPU 合成？切到雲端後，角色清單直接來自 Drive 上的資料夾。" to arrayOf(
            "Synthesize on your PC or a free Colab GPU? In cloud mode the voice list comes straight from your Drive folder.",
            "合成は PC か Colab 無料 GPU か？クラウドでは音声リストが Drive のフォルダから直接読み込まれます。",
            "합성을 PC로 할까요, Colab 무료 GPU로 할까요? 클라우드 모드에선 목소리 목록이 Drive 폴더에서 바로 옵니다."),
        "☁️ 使用中（雲端）" to arrayOf("☁️ Active (cloud)", "☁️ 使用中（クラウド）", "☁️ 사용 중 (클라우드)"),
        "☁️ 改用雲端 Colab" to arrayOf("☁️ Switch to Colab cloud", "☁️ クラウド Colab に切替", "☁️ 클라우드 Colab으로 전환"),
        "💻 改回本機" to arrayOf("💻 Back to local PC", "💻 ローカルに戻す", "💻 로컬로 되돌리기"),
        "目前語音伺服器：" to arrayOf("Current voice server: ", "現在の音声サーバー：", "현재 음성 서버: "),
        "曲庫是空的，或伺服器未連線。" to arrayOf("Library is empty or the server is offline.", "ライブラリが空か、サーバー未接続。", "라이브러리가 비었거나 서버 미연결."),
        "雲端曲庫" to arrayOf("Cloud library", "クラウドライブラリ", "클라우드 라이브러리"),
        "讀取中…" to arrayOf("Loading…", "読み込み中…", "불러오는 중…"),
        "🔄 重新整理" to arrayOf("🔄 Refresh", "🔄 更新", "🔄 새로고침"),
        "整個雲端曲庫的角色（來自 Drive）。未下載的按「下載」請電腦/Colab 先抓到運算端，之後合成不用等。" to arrayOf(
            "All voices in the cloud library (from Drive). Tap \"Download\" to pre-fetch to the compute side so later synthesis is instant.",
            "クラウドライブラリの全音声（Drive から）。未取得は「ダウンロード」で計算側へ先読みし、以後の合成が速くなります。",
            "클라우드 라이브러리의 모든 목소리(Drive). '다운로드'로 연산 쪽에 미리 받아두면 이후 합성이 빠릅니다."),
        "✅ 已下載" to arrayOf("✅ Downloaded", "✅ 取得済み", "✅ 다운로드됨"),
        "☁️ 未下載" to arrayOf("☁️ Not downloaded", "☁️ 未取得", "☁️ 미다운로드"),
        "無情緒" to arrayOf("No emotion", "感情なし", "감정 없음"),
        "下載中…" to arrayOf("Downloading…", "ダウンロード中…", "다운로드 중…"),
        "⬇ 下載" to arrayOf("⬇ Download", "⬇ ダウンロード", "⬇ 다운로드"),
        "帳號" to arrayOf("Account", "アカウント", "계정"),
        "以訪客身份繼續" to arrayOf("Continue as guest", "ゲストとして続行", "게스트로 계속"),

        // ── VoiceWeaverCore（語音直入模式訊息）──
        "錄音失敗，請再試一次" to arrayOf("Recording failed, please try again", "録音に失敗しました、もう一度", "녹음 실패, 다시 시도하세요"),
        "（語音直接理解）" to arrayOf("(understood directly from voice)", "（音声から直接理解）", "(음성에서 바로 이해)"),
        "無法開始錄音，請確認麥克風權限" to arrayOf("Can't start recording — check microphone permission", "録音を開始できません。マイク権限を確認", "녹음을 시작할 수 없어요. 마이크 권한 확인"),

        // ── VoiceWeaverScreen 補遺 ──
        "說完了，請 AI 聽" to arrayOf("Done — let AI listen", "話し終えた、AI に聞かせる", "다 말했어요, AI에게 들려주기"),
        "直接說給 AI 聽（不轉文字）" to arrayOf("Speak directly to AI (no transcription)", "AI に直接話す（文字起こしなし）", "AI에게 바로 말하기 (텍스트 변환 없음)"),
        "正在錄音…說完按上面的按鈕。" to arrayOf("Recording… tap the button above when done.", "録音中…終わったら上のボタンを。", "녹음 중… 끝나면 위 버튼을 누르세요."),
        "咬字不清楚也沒關係，AI 會直接聽你的聲音判斷意思。" to arrayOf(
            "Unclear speech is fine — AI listens to your voice and infers the meaning.",
            "発音が不明瞭でも大丈夫。AI が声から意味を判断します。",
            "발음이 불분명해도 괜찮아요. AI가 목소리로 의미를 판단합니다."),
        "需要麥克風權限" to arrayOf("Microphone permission needed", "マイク権限が必要", "마이크 권한 필요"),
        "請允許麥克風權限以使用語音輸入功能。" to arrayOf("Allow microphone access to use voice input.", "音声入力にはマイク権限を許可してください。", "음성 입력을 위해 마이크 권한을 허용하세요."),
        "前往設定" to arrayOf("Open settings", "設定を開く", "설정 열기"),
        "用圖卡說話 → 到「圖卡」分頁" to arrayOf("Talk with cards → open the Cards tab", "カードで話す → 「カード」タブへ", "카드로 말하기 → '카드' 탭으로"),
        "三個都不對？重新生成" to arrayOf("None of the three? Regenerate", "3つとも違う？再生成", "셋 다 아니면? 다시 생성"),
        "換一個說法（%d/%d）" to arrayOf("Another phrasing (%d/%d)", "別の言い方（%d/%d）", "다른 표현 (%d/%d)"),
        "挑一句最接近的" to arrayOf("Pick the closest one", "いちばん近いものを選ぶ", "가장 가까운 문장을 고르세요"),
        "都不對？換一批聲音" to arrayOf("None fit? Try other voices", "合わない？別の声に", "다 아니면? 다른 목소리"),
        "收起" to arrayOf("Collapse", "閉じる", "접기"),
        "更多" to arrayOf("More", "もっと", "더 보기"),

        // ── 情境推薦的地點名（AacContextSuggester 的 Rule 鍵）──
        "便利商店" to arrayOf("Convenience store", "コンビニ", "편의점"),
        "速食店" to arrayOf("Fast food", "ファストフード", "패스트푸드"),
        "餐廳" to arrayOf("Restaurant", "レストラン", "식당"),
        "咖啡店" to arrayOf("Café", "カフェ", "카페"),
        "飲料店" to arrayOf("Drinks shop", "ドリンク店", "음료 가게"),
        "醫院" to arrayOf("Hospital", "病院", "병원"),
        "診所" to arrayOf("Clinic", "診療所", "의원"),
        "藥局" to arrayOf("Pharmacy", "薬局", "약국"),
        "超市" to arrayOf("Supermarket", "スーパー", "마트"),
        "百貨" to arrayOf("Department store", "デパート", "백화점"),
        "購物中心" to arrayOf("Shopping mall", "ショッピングモール", "쇼핑몰"),
        "服飾店" to arrayOf("Clothing store", "服屋", "옷 가게"),
        "車站" to arrayOf("Station", "駅", "역"),
        "捷運站" to arrayOf("Metro station", "地下鉄駅", "지하철역"),
        "機場" to arrayOf("Airport", "空港", "공항"),
        "停車場" to arrayOf("Car park", "駐車場", "주차장"),
        "加油站" to arrayOf("Petrol station", "ガソリンスタンド", "주유소"),
        "銀行" to arrayOf("Bank", "銀行", "은행"),
        "郵局" to arrayOf("Post office", "郵便局", "우체국"),
        "圖書館" to arrayOf("Library", "図書館", "도서관"),
        "旅館" to arrayOf("Hotel", "ホテル", "호텔"),
        "公園" to arrayOf("Park", "公園", "공원"),
        "廟宇" to arrayOf("Temple", "お寺", "사원"),
        "教堂" to arrayOf("Church", "教会", "교회"),
        "住家" to arrayOf("Home", "自宅", "집"),
        // ── 情境推薦的地點開場白（AacContextSuggester）──
        "在便利商店常用：" to arrayOf("Common at a convenience store:", "コンビニでよく使う：", "편의점에서 자주 쓰는 말:"),
        "在停車場常用：" to arrayOf("Common at the car park:", "駐車場でよく使う：", "주차장에서 자주 쓰는 말:"),
        "在公園常用：" to arrayOf("Common at the park:", "公園でよく使う：", "공원에서 자주 쓰는 말:"),
        "在加油站常用：" to arrayOf("Common at the petrol station:", "ガソリンスタンドでよく使う：", "주유소에서 자주 쓰는 말:"),
        "在咖啡店常用：" to arrayOf("Common at a café:", "カフェでよく使う：", "카페에서 자주 쓰는 말:"),
        "在圖書館常用：" to arrayOf("Common at the library:", "図書館でよく使う：", "도서관에서 자주 쓰는 말:"),
        "在家裡常用：" to arrayOf("Common at home:", "家でよく使う：", "집에서 자주 쓰는 말:"),
        "在廟裡常用：" to arrayOf("Common at the temple:", "お寺でよく使う：", "사원에서 자주 쓰는 말:"),
        "在捷運站常用：" to arrayOf("Common at the metro station:", "地下鉄駅でよく使う：", "지하철역에서 자주 쓰는 말:"),
        "在教堂常用：" to arrayOf("Common at church:", "教会でよく使う：", "교회에서 자주 쓰는 말:"),
        "在旅館常用：" to arrayOf("Common at the hotel:", "ホテルでよく使う：", "호텔에서 자주 쓰는 말:"),
        "在服飾店常用：" to arrayOf("Common at a clothing store:", "服屋でよく使う：", "옷 가게에서 자주 쓰는 말:"),
        "在機場常用：" to arrayOf("Common at the airport:", "空港でよく使う：", "공항에서 자주 쓰는 말:"),
        "在百貨常用：" to arrayOf("Common at a department store:", "デパートでよく使う：", "백화점에서 자주 쓰는 말:"),
        "在藥局常用：" to arrayOf("Common at the pharmacy:", "薬局でよく使う：", "약국에서 자주 쓰는 말:"),
        "在診所常用：" to arrayOf("Common at the clinic:", "診療所でよく使う：", "의원에서 자주 쓰는 말:"),
        "在購物中心常用：" to arrayOf("Common at a shopping mall:", "ショッピングモールでよく使う：", "쇼핑몰에서 자주 쓰는 말:"),
        "在超市常用：" to arrayOf("Common at the supermarket:", "スーパーでよく使う：", "마트에서 자주 쓰는 말:"),
        "在車站常用：" to arrayOf("Common at the station:", "駅でよく使う：", "역에서 자주 쓰는 말:"),
        "在速食店常用：" to arrayOf("Common at a fast-food place:", "ファストフードでよく使う：", "패스트푸드점에서 자주 쓰는 말:"),
        "在郵局常用：" to arrayOf("Common at the post office:", "郵便局でよく使う：", "우체국에서 자주 쓰는 말:"),
        "在醫院常用：" to arrayOf("Common at the hospital:", "病院でよく使う：", "병원에서 자주 쓰는 말:"),
        "在銀行常用：" to arrayOf("Common at the bank:", "銀行でよく使う：", "은행에서 자주 쓰는 말:"),
        "在飲料店常用：" to arrayOf("Common at a drinks shop:", "ドリンク店でよく使う：", "음료 가게에서 자주 쓰는 말:"),
        "在餐廳常用：" to arrayOf("Common at a restaurant:", "レストランでよく使う：", "식당에서 자주 쓰는 말:"),

        // ── enum displayName／description（SeverityMode・EyeControlMode・CameraLens・ReportRange・ConnectionMode・Currency）──
        "輕度" to arrayOf("Mild", "軽度", "경도"),
        "中度" to arrayOf("Moderate", "中等度", "중등도"),
        "重度" to arrayOf("Severe", "重度", "중증"),
        "鍵盤打字為主，完整功能" to arrayOf("Mostly keyboard typing, full features", "キーボード入力中心、全機能", "키보드 입력 위주, 전체 기능"),
        "超大圖卡預設展開，隱藏複雜設定" to arrayOf("Extra-large cards expanded by default; complex settings hidden", "特大カードを既定で展開、複雑な設定は非表示", "특대 카드 기본 펼침, 복잡한 설정 숨김"),
        "全螢幕識字卡逐張掃描 + 特大字體" to arrayOf("Full-screen flashcards scanned one by one + extra-large text", "全画面カードを1枚ずつスキャン＋特大文字", "전체 화면 카드 한 장씩 스캔 + 특대 글자"),
        "凝視停留" to arrayOf("Gaze dwell", "視線停留", "시선 머무름"),
        "眨眼掃描" to arrayOf("Blink scan", "まばたきスキャン", "깜빡임 스캔"),
        "後攝影機" to arrayOf("Rear camera", "背面カメラ", "후면 카메라"),
        "前攝影機" to arrayOf("Front camera", "前面カメラ", "전면 카메라"),
        "前後一起拍" to arrayOf("Both cameras", "前後同時撮影", "전후 동시 촬영"),
        "今日" to arrayOf("Today", "今日", "오늘"),
        "本月" to arrayOf("Month", "今月", "이번 달"),
        "本年度" to arrayOf("Year", "今年", "올해"),
        "無線 (Wi-Fi LAN)" to arrayOf("Wireless (Wi-Fi LAN)", "無線 (Wi-Fi LAN)", "무선 (Wi-Fi LAN)"),
        "有線 (USB)" to arrayOf("Wired (USB)", "有線 (USB)", "유선 (USB)"),
        "手機與電腦連同一個 Wi-Fi。API URL 填電腦 IP，例 http://192.168.1.x:9880" to arrayOf(
            "Phone and PC on the same Wi-Fi. Set the API URL to the PC's IP, e.g. http://192.168.1.x:9880",
            "スマホと PC を同じ Wi-Fi に。API URL に PC の IP、例 http://192.168.1.x:9880",
            "휴대폰과 PC를 같은 Wi-Fi에. API URL에 PC IP, 예 http://192.168.1.x:9880"),
        "USB 線接電腦。電腦先跑 adb reverse tcp:9880 tcp:9880；App 填 http://127.0.0.1:9880" to arrayOf(
            "Connect by USB. On the PC run adb reverse tcp:9880 tcp:9880 first; set the app to http://127.0.0.1:9880",
            "USB で PC に接続。PC で adb reverse tcp:9880 tcp:9880 を実行；アプリは http://127.0.0.1:9880",
            "USB로 PC 연결. PC에서 adb reverse tcp:9880 tcp:9880 실행; 앱은 http://127.0.0.1:9880"),
        "新台幣" to arrayOf("New Taiwan Dollar", "新台湾ドル", "신 타이완 달러"),
        "美元" to arrayOf("US Dollar", "米ドル", "미국 달러"),
        "日圓" to arrayOf("Japanese Yen", "日本円", "일본 엔"),
        "韓圜" to arrayOf("Korean Won", "韓国ウォン", "한국 원"),
        "人民幣" to arrayOf("Chinese Yuan", "人民元", "중국 위안"),
        "歐元" to arrayOf("Euro", "ユーロ", "유로"),

        // ─────────────────────────────────────────────────────────────────
        // 「你的情境」區塊：地點選單（VoiceWeaverScreen.locationPresetGroups）、
        // 拍照物件目錄／快速標籤（AacContextSuggester.VISION_CATALOG／VISION_PRESETS）、
        // 快速推薦語彙（AacContextSuggester.CATEGORY_RULES 的 temp() 卡片）。
        // 這些都是「顯示走這裡、比對走原字串」——資料模型（vault.visionTags／
        // vault.rawText／onToggle 的 key）全部維持繁體中文不動，只有 Text() 顯示
        // 時才查這張表。跟 temp() 卡片已有的 AacI18n（以 id 為鍵）分開維護，
        // 是因為同一個 id 在不同地點被重複使用、卻代表不同意思（例如 water 在
        // 「住家」是「我要喝水」、在「餐廳」是「白開水」），以 id 查表會查錯；
        // 這裡改以「字面本身」為鍵，天生不會有這個衝突。
        // ─────────────────────────────────────────────────────────────────

        // 地點選單：分組標題
        "照護與日常" to arrayOf("Care & daily life", "介護・日常", "돌봄·일상"),
        "餐飲與購物" to arrayOf("Food & shopping", "飲食・買い物", "식음료·쇼핑"),
        "交通與外出" to arrayOf("Transport & outings", "交通・外出", "교통·외출"),
        "公共服務" to arrayOf("Public services", "公共サービス", "공공 서비스"),

        // 地點選單：個別地點（醫院／診所／藥局／便利商店／超市／速食店／餐廳／咖啡店／
        // 飲料店／百貨／購物中心／服飾店／車站／捷運站／機場／停車場／加油站／銀行／
        // 郵局／圖書館／旅館／公園／廟宇／教堂／住家 已在上面某處有翻譯，這裡不重複加
        // ——mapOf 對重複 key 只會保留最後一筆，重複加只會讓後加的悄悄蓋掉原本
        // 可能已在別處使用的譯法）
        "牙科" to arrayOf("Dentist", "歯科", "치과"),
        "復健科" to arrayOf("Rehab clinic", "リハビリ科", "재활의학과"),
        "照護中心" to arrayOf("Care centre", "介護センター", "요양센터"),
        "量販店" to arrayOf("Warehouse store", "量販店", "대형마트"),
        "麵包店" to arrayOf("Bakery", "パン屋", "빵집"),
        "五金行" to arrayOf("Hardware store", "金物屋", "철물점"),
        "電子產品店" to arrayOf("Electronics store", "電気店", "전자제품 매장"),
        "公車站" to arrayOf("Bus stop", "バス停", "버스 정류장"),
        "計程車招呼站" to arrayOf("Taxi stand", "タクシー乗り場", "택시 승강장"),
        "公家機關" to arrayOf("Government office", "役所", "관공서"),
        "警察局" to arrayOf("Police station", "警察署", "경찰서"),
        "消防隊" to arrayOf("Fire station", "消防署", "소방서"),
        "學校" to arrayOf("School", "学校", "학교"),

        // 拍照物件：分類標題
        "飲食" to arrayOf("Food & drink", "飲食", "음식·음료"),
        "餐具與廚房" to arrayOf("Tableware & kitchen", "食器・キッチン", "식기·주방"),
        "醫療" to arrayOf("Medical", "医療", "의료"),
        "容器" to arrayOf("Containers", "容器", "용기"),
        "家具" to arrayOf("Furniture", "家具", "가구"),
        "電器與 3C" to arrayOf("Electronics", "家電・デジタル機器", "가전·전자기기"),
        "服飾" to arrayOf("Clothing", "衣類", "의류"),
        "文書與標示" to arrayOf("Documents & signs", "書類・表示", "문서·표지"),
        "場景物件" to arrayOf("Scene objects", "場面の物", "장면 속 사물"),
        "交通工具" to arrayOf("Vehicles", "乗り物", "탈것"),
        "人物關係" to arrayOf("People", "人物", "사람"),
        "自然" to arrayOf("Nature", "自然", "자연"),

        // 拍照物件：飲食
        "水杯" to arrayOf("Cup of water", "水のコップ", "물컵"),
        "茶杯" to arrayOf("Teacup", "湯呑み", "찻잔"),
        "咖啡杯" to arrayOf("Coffee cup", "コーヒーカップ", "커피잔"),
        "牛奶" to arrayOf("Milk", "牛乳", "우유"),
        "飲料" to arrayOf("Drink", "飲み物", "음료"),
        "結冰水" to arrayOf("Iced water", "氷水", "얼음물"),
        "珍珠奶茶" to arrayOf("Bubble tea", "タピオカミルクティー", "버블티"),
        "御飯糰" to arrayOf("Rice ball", "おにぎり", "삼각김밥"),
        "麵包" to arrayOf("Bread", "パン", "빵"),
        "法國麵包" to arrayOf("Baguette", "フランスパン", "바게트"),
        "貝果" to arrayOf("Bagel", "ベーグル", "베이글"),
        "可頌" to arrayOf("Croissant", "クロワッサン", "크루아상"),
        "飯碗" to arrayOf("Bowl of rice", "ご飯茶碗", "밥그릇"),
        "麵碗" to arrayOf("Bowl of noodles", "麺の丼", "국수 그릇"),
        "湯" to arrayOf("Soup", "スープ", "국"),
        "壽司" to arrayOf("Sushi", "寿司", "초밥"),
        "便當" to arrayOf("Lunch box", "弁当", "도시락"),
        "餃子" to arrayOf("Dumplings", "餃子", "만두"),
        "漢堡" to arrayOf("Burger", "ハンバーガー", "햄버거"),
        "薯條" to arrayOf("Fries", "フライドポテト", "감자튀김"),
        "披薩" to arrayOf("Pizza", "ピザ", "피자"),
        "熱狗" to arrayOf("Hot dog", "ホットドッグ", "핫도그"),
        "塔可" to arrayOf("Taco", "タコス", "타코"),
        "沙拉" to arrayOf("Salad", "サラダ", "샐러드"),
        "煎蛋" to arrayOf("Fried egg", "目玉焼き", "계란프라이"),
        "雞蛋" to arrayOf("Egg", "卵", "달걀"),
        "起司" to arrayOf("Cheese", "チーズ", "치즈"),
        "蘋果" to arrayOf("Apple", "リンゴ", "사과"),
        "香蕉" to arrayOf("Banana", "バナナ", "바나나"),
        "橘子" to arrayOf("Orange", "みかん", "귤"),
        "葡萄" to arrayOf("Grapes", "ぶどう", "포도"),
        "番茄" to arrayOf("Tomato", "トマト", "토마토"),
        "胡蘿蔔" to arrayOf("Carrot", "にんじん", "당근"),

        // 拍照物件：餐具與廚房
        "筷子" to arrayOf("Chopsticks", "箸", "젓가락"),
        "刀叉" to arrayOf("Knife & fork", "ナイフとフォーク", "나이프와 포크"),
        "湯匙" to arrayOf("Spoon", "スプーン", "숟가락"),
        "餐盤" to arrayOf("Plate", "皿", "접시"),
        "碗" to arrayOf("Bowl", "お椀", "그릇"),
        "鹽罐" to arrayOf("Salt shaker", "塩入れ", "소금통"),
        "醬油" to arrayOf("Soy sauce", "醤油", "간장"),
        "冰塊" to arrayOf("Ice", "氷", "얼음"),
        "外帶盒" to arrayOf("Takeout box", "テイクアウト容器", "포장 용기"),
        "餐巾紙" to arrayOf("Napkin", "紙ナプキン", "냅킨"),
        "抹布" to arrayOf("Cloth", "布巾", "행주"),
        "菜單" to arrayOf("Menu", "メニュー", "메뉴"),

        // 拍照物件：醫療
        "藥袋" to arrayOf("Medicine bag", "薬袋", "약봉지"),
        "針筒" to arrayOf("Syringe", "注射器", "주사기"),
        "聽診器" to arrayOf("Stethoscope", "聴診器", "청진기"),
        "體溫計" to arrayOf("Thermometer", "体温計", "체온계"),
        "OK 繃" to arrayOf("Band-aid", "絆創膏", "밴드"),
        "口罩" to arrayOf("Mask", "マスク", "마스크"),
        "消毒液" to arrayOf("Sanitiser", "消毒液", "소독제"),
        "拐杖" to arrayOf("Cane", "杖", "지팡이"),
        "輪椅" to arrayOf("Wheelchair", "車いす", "휠체어"),
        "病床" to arrayOf("Hospital bed", "病院のベッド", "병상"),
        "掛號單" to arrayOf("Registration form", "受付票", "접수표"),
        "看診單" to arrayOf("Consultation slip", "診察票", "진료표"),
        "醫療手套" to arrayOf("Medical gloves", "医療用手袋", "의료용 장갑"),
        "牙刷" to arrayOf("Toothbrush", "歯ブラシ", "칫솔"),
        "肥皂" to arrayOf("Soap", "石鹸", "비누"),
        "點滴" to arrayOf("IV drip", "点滴", "링거"),

        // 拍照物件：容器
        "購物袋" to arrayOf("Shopping bag", "買い物袋", "쇼핑백"),
        "背包" to arrayOf("Backpack", "リュック", "배낭"),
        "手提包" to arrayOf("Handbag", "ハンドバッグ", "핸드백"),
        "錢包" to arrayOf("Wallet", "財布", "지갑"),
        "公事包" to arrayOf("Briefcase", "ビジネスバッグ", "서류가방"),
        "紙箱" to arrayOf("Cardboard box", "段ボール箱", "종이 상자"),
        "信箱" to arrayOf("Mailbox", "郵便受け", "우편함"),
        "水桶" to arrayOf("Bucket", "バケツ", "양동이"),
        "垃圾桶" to arrayOf("Trash can", "ゴミ箱", "쓰레기통"),
        "籃子" to arrayOf("Basket", "かご", "바구니"),
        "購物車" to arrayOf("Shopping cart", "ショッピングカート", "쇼핑카트"),

        // 拍照物件：家具
        "沙發" to arrayOf("Sofa", "ソファ", "소파"),
        "椅子" to arrayOf("Chair", "椅子", "의자"),
        "床" to arrayOf("Bed", "ベッド", "침대"),
        "鏡子" to arrayOf("Mirror", "鏡", "거울"),
        "窗戶" to arrayOf("Window", "窓", "창문"),
        "門" to arrayOf("Door", "ドア", "문"),
        "盆栽" to arrayOf("Potted plant", "鉢植え", "화분"),
        "燈" to arrayOf("Lamp", "照明", "조명"),
        "時鐘" to arrayOf("Clock", "時計", "시계"),
        "浴缸" to arrayOf("Bathtub", "浴槽", "욕조"),
        "馬桶" to arrayOf("Toilet bowl", "便器", "변기"),
        "蓮蓬頭" to arrayOf("Showerhead", "シャワーヘッド", "샤워기"),

        // 拍照物件：電器與 3C
        "電視" to arrayOf("TV", "テレビ", "텔레비전"),
        "遙控器" to arrayOf("Remote control", "リモコン", "리모컨"),
        "手機" to arrayOf("Phone", "スマホ", "휴대폰"),
        "電腦" to arrayOf("Computer", "パソコン", "컴퓨터"),
        "鍵盤" to arrayOf("Keyboard", "キーボード", "키보드"),
        "滑鼠" to arrayOf("Mouse", "マウス", "마우스"),
        "印表機" to arrayOf("Printer", "プリンター", "프린터"),
        "相機" to arrayOf("Camera", "カメラ", "카메라"),
        "插座" to arrayOf("Power outlet", "コンセント", "콘센트"),
        "電池" to arrayOf("Battery", "電池", "건전지"),
        "Wi-Fi 機" to arrayOf("Wi-Fi router", "Wi-Fiルーター", "와이파이 공유기"),
        "電風扇" to arrayOf("Fan", "扇風機", "선풍기"),
        "冰箱" to arrayOf("Fridge", "冷蔵庫", "냉장고"),
        "瓦斯爐" to arrayOf("Gas stove", "ガスコンロ", "가스레인지"),
        "收音機" to arrayOf("Radio", "ラジオ", "라디오"),

        // 拍照物件：服飾
        "衣服" to arrayOf("Clothes", "服", "옷"),
        "襯衫" to arrayOf("Shirt", "シャツ", "셔츠"),
        "洋裝" to arrayOf("Dress", "ワンピース", "원피스"),
        "褲子" to arrayOf("Pants", "ズボン", "바지"),
        "外套" to arrayOf("Jacket", "上着", "외투"),
        "襪子" to arrayOf("Socks", "靴下", "양말"),
        "鞋" to arrayOf("Shoes", "靴", "신발"),
        "高跟鞋" to arrayOf("High heels", "ハイヒール", "하이힐"),
        "涼鞋" to arrayOf("Sandals", "サンダル", "샌들"),
        "帽子" to arrayOf("Hat", "帽子", "모자"),
        "禮帽" to arrayOf("Top hat", "シルクハット", "실크햇"),
        "眼鏡" to arrayOf("Glasses", "メガネ", "안경"),
        "墨鏡" to arrayOf("Sunglasses", "サングラス", "선글라스"),
        "手錶" to arrayOf("Watch", "腕時計", "손목시계"),
        "戒指" to arrayOf("Ring", "指輪", "반지"),
        "皮包" to arrayOf("Purse", "バッグ", "가방"),

        // 拍照物件：文書與標示
        "報紙" to arrayOf("Newspaper", "新聞", "신문"),
        "書" to arrayOf("Book", "本", "책"),
        "存摺" to arrayOf("Bankbook", "通帳", "통장"),
        "筆記本" to arrayOf("Notebook", "ノート", "노트"),
        "鉛筆" to arrayOf("Pencil", "鉛筆", "연필"),
        "原子筆" to arrayOf("Pen", "ボールペン", "볼펜"),
        "收據" to arrayOf("Receipt", "レシート", "영수증"),
        "信用卡" to arrayOf("Credit card", "クレジットカード", "신용카드"),
        "證件" to arrayOf("ID card", "身分証", "신분증"),
        "郵筒" to arrayOf("Mailbox (post)", "ポスト", "우체통"),
        "信封" to arrayOf("Envelope", "封筒", "봉투"),
        "包裹" to arrayOf("Parcel", "小包", "소포"),
        "車票" to arrayOf("Ticket", "切符", "승차권"),
        "標價" to arrayOf("Price tag", "値札", "가격표"),
        "看板" to arrayOf("Signboard", "看板", "간판"),
        "標示牌" to arrayOf("Sign", "標識", "표지판"),
        "廁所標示" to arrayOf("Toilet sign", "トイレの表示", "화장실 표지"),

        // 拍照物件：場景物件
        "商品架" to arrayOf("Shelf", "商品棚", "진열대"),
        "結帳台" to arrayOf("Checkout counter", "レジ", "계산대"),
        "微波爐" to arrayOf("Microwave", "電子レンジ", "전자레인지"),
        "冷藏櫃" to arrayOf("Fridge case", "冷蔵ケース", "냉장 진열대"),
        "月台" to arrayOf("Platform", "ホーム", "승강장"),
        "閘門" to arrayOf("Gate", "改札", "개찰구"),
        "油槍" to arrayOf("Fuel nozzle", "給油ノズル", "주유기"),
        "電梯" to arrayOf("Elevator", "エレベーター", "엘리베이터"),
        "手扶梯" to arrayOf("Escalator", "エスカレーター", "에스컬레이터"),
        "服務台" to arrayOf("Service desk", "サービスカウンター", "안내데스크"),
        "美食街" to arrayOf("Food court", "フードコート", "푸드코트"),
        "試衣間" to arrayOf("Fitting room", "試着室", "탈의실"),
        "長椅" to arrayOf("Bench", "ベンチ", "벤치"),
        "禮品包裝" to arrayOf("Gift wrap", "ギフト包装", "선물 포장"),
        "聖誕樹" to arrayOf("Christmas tree", "クリスマスツリー", "크리스마스 트리"),
        "蠟燭" to arrayOf("Candle", "ろうそく", "양초"),
        "香爐" to arrayOf("Incense burner", "香炉", "향로"),
        "燈籠" to arrayOf("Lantern", "提灯", "등롱"),

        // 拍照物件：交通工具
        "汽車" to arrayOf("Car", "車", "자동차"),
        "計程車" to arrayOf("Taxi", "タクシー", "택시"),
        "公車" to arrayOf("Bus", "バス", "버스"),
        "火車" to arrayOf("Train", "電車", "기차"),
        "捷運" to arrayOf("Metro", "地下鉄", "지하철"),
        "機車" to arrayOf("Scooter", "バイク", "오토바이"),
        "腳踏車" to arrayOf("Bicycle", "自転車", "자전거"),
        "飛機" to arrayOf("Airplane", "飛行機", "비행기"),
        "船" to arrayOf("Boat", "船", "배"),
        "太空船" to arrayOf("Spaceship", "宇宙船", "우주선"),
        "紅綠燈" to arrayOf("Traffic light", "信号機", "신호등"),
        "斑馬線" to arrayOf("Crosswalk", "横断歩道", "횡단보도"),

        // 拍照物件：人物關係
        "男性" to arrayOf("Man", "男性", "남성"),
        "女性" to arrayOf("Woman", "女性", "여성"),
        "老人" to arrayOf("Elderly person", "お年寄り", "노인"),
        "嬰兒" to arrayOf("Baby", "赤ちゃん", "아기"),
        "小孩" to arrayOf("Child", "子ども", "아이"),
        "醫生" to arrayOf("Doctor", "医師", "의사"),
        "警察" to arrayOf("Police officer", "警察官", "경찰관"),
        "工人" to arrayOf("Worker", "作業員", "작업자"),
        "廚師" to arrayOf("Chef", "料理人", "요리사"),
        "狗" to arrayOf("Dog", "犬", "개"),
        "貓" to arrayOf("Cat", "猫", "고양이"),
        "鳥" to arrayOf("Bird", "鳥", "새"),

        // 拍照物件：自然
        "樹" to arrayOf("Tree", "木", "나무"),
        "花" to arrayOf("Flower", "花", "꽃"),
        "玫瑰" to arrayOf("Rose", "バラ", "장미"),
        "草" to arrayOf("Grass", "草", "풀"),
        "太陽" to arrayOf("Sun", "太陽", "해"),
        "雲" to arrayOf("Cloud", "雲", "구름"),
        "雨" to arrayOf("Rain", "雨", "비"),
        "雪" to arrayOf("Snow", "雪", "눈"),
        "海" to arrayOf("Sea", "海", "바다"),
        "山" to arrayOf("Mountain", "山", "산"),
        "月亮" to arrayOf("Moon", "月", "달"),
        "星星" to arrayOf("Star", "星", "별"),

        // 情境特定物件（VISION_PRESETS，前面沒收錄到的）
        "點餐機" to arrayOf("Order kiosk", "注文機", "주문 키오스크"),
        "套餐" to arrayOf("Combo meal", "セット", "세트 메뉴"),
        "可樂" to arrayOf("Cola", "コーラ", "콜라"),
        "番茄醬" to arrayOf("Ketchup", "ケチャップ", "케첩"),
        "糖包" to arrayOf("Sugar packet", "砂糖", "설탕 봉지"),
        "外帶杯" to arrayOf("Takeout cup", "テイクアウトカップ", "테이크아웃 컵"),
        "杯套" to arrayOf("Cup sleeve", "カップホルダー", "컵 홀더"),
        "封口杯" to arrayOf("Sealed cup", "封をしたカップ", "밀봉 컵"),
        "吸管" to arrayOf("Straw", "ストロー", "빨대"),
        "衣架" to arrayOf("Hanger", "ハンガー", "옷걸이"),
        "時刻表" to arrayOf("Timetable", "時刻表", "시간표"),
        "行李箱" to arrayOf("Suitcase", "スーツケース", "여행 가방"),
        "悠遊卡" to arrayOf("EasyCard", "悠遊カード", "이지카드"),
        "顯示器" to arrayOf("Display screen", "表示画面", "디스플레이"),
        "便利屋" to arrayOf("Convenience kiosk", "コンビニ設備", "편의 시설"),
        "號碼牌" to arrayOf("Number ticket", "番号札", "번호표"),
        "櫃台" to arrayOf("Counter", "カウンター", "카운터"),
        "提款卡" to arrayOf("ATM card", "キャッシュカード", "현금카드"),
        "郵票" to arrayOf("Stamp", "切手", "우표"),
        "登機證" to arrayOf("Boarding pass", "搭乗券", "탑승권"),
        "安檢口" to arrayOf("Security checkpoint", "保安検査場", "보안 검색대"),
        "登機門" to arrayOf("Boarding gate", "搭乗口", "탑승구"),
        "護照" to arrayOf("Passport", "パスポート", "여권"),
        "繳費機" to arrayOf("Payment machine", "精算機", "정산기"),
        "出口" to arrayOf("Exit", "出口", "출구"),
        "房卡" to arrayOf("Room key card", "ルームキー", "객실 키"),
        "鞦韆" to arrayOf("Swing", "ブランコ", "그네"),
        "草地" to arrayOf("Lawn", "芝生", "잔디밭"),
        "石階" to arrayOf("Stone steps", "石段", "돌계단"),
        "供桌" to arrayOf("Offering table", "供物台", "제단"),
        "神像" to arrayOf("Deity statue", "神像", "신상"),
        "金紙" to arrayOf("Joss paper", "紙銭", "지전"),
        "十字架" to arrayOf("Cross", "十字架", "십자가"),
        "入口" to arrayOf("Entrance", "入口", "입구"),
        "藥盒" to arrayOf("Pill box", "薬箱", "약통"),

        // 快速推薦語彙（CATEGORY_RULES 的 temp() 卡片，短句／短詞）
        "水" to arrayOf("Water", "水", "물"),
        "想睡覺" to arrayOf("Sleepy", "眠い", "졸려요"),
        "要吃藥" to arrayOf("Need medicine", "薬が必要", "약이 필요해요"),
        "不舒服" to arrayOf("Not feeling well", "具合が悪い", "몸이 안 좋아요"),
        "香菸" to arrayOf("Cigarettes", "タバコ", "담배"),
        "多少錢" to arrayOf("How much?", "いくら？", "얼마예요?"),
        "悠遊卡付" to arrayOf("Pay with EasyCard", "悠遊カードで払う", "이지카드로 결제"),
        "袋子" to arrayOf("Bag", "袋", "봉지"),
        "我想點餐" to arrayOf("I'd like to order", "注文したいです", "주문하고 싶어요"),
        "不要洋蔥" to arrayOf("No onion", "玉ねぎ抜きで", "양파는 빼주세요"),
        "帶走" to arrayOf("Take away", "持ち帰り", "포장"),
        "內用" to arrayOf("Eat in", "店内で", "매장에서"),
        "去冰" to arrayOf("No ice", "氷なし", "얼음 빼고"),
        "兩個人" to arrayOf("Two people", "二人です", "두 명이요"),
        "麻煩您" to arrayOf("Excuse me, please", "お願いします", "부탁드려요"),
        "白開水" to arrayOf("Plain water", "お水", "맹물"),
        "買單" to arrayOf("Check, please", "お会計お願いします", "계산해 주세요"),
        "很好吃" to arrayOf("It's delicious", "美味しいです", "맛있어요"),
        "美式" to arrayOf("Americano", "アメリカーノ", "아메리카노"),
        "拿鐵" to arrayOf("Latte", "ラテ", "라테"),
        "熱的" to arrayOf("Hot", "ホット", "뜨겁게"),
        "微糖" to arrayOf("Less sugar", "甘さ控えめ", "설탕 적게"),
        "中杯" to arrayOf("Medium", "Mサイズ", "중간 사이즈"),
        "奶茶" to arrayOf("Milk tea", "ミルクティー", "밀크티"),
        "少冰" to arrayOf("Less ice", "氷少なめ", "얼음 적게"),
        "大杯" to arrayOf("Large", "Lサイズ", "큰 사이즈"),
        "我不舒服" to arrayOf("I don't feel well", "具合が悪いです", "몸이 안 좋아요"),
        "胸口痛" to arrayOf("Chest pain", "胸が痛いです", "가슴이 아파요"),
        "找家人" to arrayOf("Get my family", "家族を呼んでください", "가족을 불러 주세요"),
        "需要藥" to arrayOf("Need medicine", "薬が必要です", "약이 필요해요"),
        "護士小姐" to arrayOf("Nurse", "看護師さん", "간호사님"),
        "謝謝" to arrayOf("Thank you", "ありがとうございます", "감사합니다"),
        "掛號" to arrayOf("Registration", "受付", "접수"),
        "我要看醫生" to arrayOf("I need to see a doctor", "医師に診てほしいです", "진료 받고 싶어요"),
        "量體溫" to arrayOf("Take my temperature", "体温を測ってください", "체온 재주세요"),
        "拿藥" to arrayOf("Pick up medicine", "薬を受け取ります", "약 받으러 왔어요"),
        "感冒" to arrayOf("Cold (flu)", "風邪", "감기"),
        "推車" to arrayOf("Shopping cart", "カート", "카트"),
        "白米" to arrayOf("Rice", "お米", "쌀"),
        "結帳" to arrayOf("Checkout", "会計", "계산"),
        "可以試穿嗎" to arrayOf("Can I try it on?", "試着してもいいですか？", "입어 봐도 될까요?"),
        "中號" to arrayOf("Medium", "Mサイズ", "중간 사이즈"),
        "別的顏色" to arrayOf("Another colour", "別の色", "다른 색"),
        "美食街在哪" to arrayOf("Where's the food court?", "フードコートはどこですか？", "푸드코트 어디예요?"),
        "休息一下" to arrayOf("Take a break", "少し休みます", "잠깐 쉴게요"),
        "洗手間" to arrayOf("Restroom", "お手洗い", "화장실"),
        "我想試穿" to arrayOf("I'd like to try it on", "試着したいです", "입어보고 싶어요"),
        "買一張票" to arrayOf("One ticket, please", "切符を一枚ください", "표 한 장 주세요"),
        "哪個月台" to arrayOf("Which platform?", "何番線ですか？", "몇 번 승강장이에요?"),
        "誤點了" to arrayOf("It's delayed", "遅れています", "연착됐어요"),
        "怎麼轉乘" to arrayOf("How do I transfer?", "乗り換え方は？", "환승은 어떻게 해요?"),
        "轉乘" to arrayOf("Transfer", "乗り換え", "환승"),
        "登機門在哪裡" to arrayOf("Where's the boarding gate?", "搭乗口はどこですか？", "탑승구 어디예요?"),
        "行李" to arrayOf("Luggage", "荷物", "짐"),
        "請翻譯" to arrayOf("Please translate", "通訳をお願いします", "통역해 주세요"),
        "需要協助" to arrayOf("I need help", "手伝ってほしいです", "도움이 필요해요"),
        "找車" to arrayOf("Find my car", "車を探しています", "차를 찾고 있어요"),
        "繳停車費" to arrayOf("Pay for parking", "駐車料金を払います", "주차비 낼게요"),
        "出口在哪裡" to arrayOf("Where's the exit?", "出口はどこですか？", "출구 어디예요?"),
        "加滿" to arrayOf("Fill it up", "満タンで", "가득 채워 주세요"),
        "92 無鉛" to arrayOf("Regular unleaded", "レギュラー", "92 무연"),
        "95 無鉛" to arrayOf("Premium unleaded", "ハイオク", "95 무연"),
        "統編" to arrayOf("Tax ID number", "法人番号", "사업자 번호"),
        "提款" to arrayOf("Withdraw cash", "引き出し", "출금"),
        "存款" to arrayOf("Deposit", "預け入れ", "입금"),
        "匯款" to arrayOf("Transfer money", "振込", "송금"),
        "寄信" to arrayOf("Send a letter", "手紙を出します", "편지 부칠게요"),
        "寄包裹" to arrayOf("Send a parcel", "小包を送ります", "소포 보낼게요"),
        "買郵票" to arrayOf("Buy stamps", "切手を買います", "우표 살게요"),
        "請小聲一點" to arrayOf("Please keep it down", "静かにお願いします", "조용히 해주세요"),
        "我要借書" to arrayOf("I'd like to borrow a book", "本を借りたいです", "책 빌리고 싶어요"),
        "我要還書" to arrayOf("I'd like to return a book", "本を返したいです", "책 반납하고 싶어요"),
        "座位在哪裡" to arrayOf("Where's my seat?", "席はどこですか？", "자리 어디예요?"),
        "我要入住" to arrayOf("I'd like to check in", "チェックインします", "체크인할게요"),
        "找櫃台" to arrayOf("Find the front desk", "フロントを探しています", "프런트를 찾고 있어요"),
        "毛巾" to arrayOf("Towel", "タオル", "수건"),
        "退房時間" to arrayOf("Check-out time", "チェックアウト時間", "체크아웃 시간"),
        "坐一下" to arrayOf("Sit for a while", "少し座ります", "잠깐 앉을게요"),
        "一杯水" to arrayOf("A glass of water", "水を一杯", "물 한 잔"),
        "散步" to arrayOf("Take a walk", "散歩します", "산책할게요"),
        "拜拜" to arrayOf("Pray", "お参りします", "기도할게요"),
        "香" to arrayOf("Incense", "お線香", "향"),
        "添香油" to arrayOf("Make an offering", "お布施をします", "시주할게요"),
        "平安" to arrayOf("Peace and safety", "無事", "평안"),
        "想坐下" to arrayOf("Want to sit down", "座りたいです", "앉고 싶어요"),
        "我想安靜一下" to arrayOf("I'd like some quiet", "少し静かにしたいです", "조용히 있고 싶어요"),
        "我要喝水" to arrayOf("I want water", "水が飲みたいです", "물 마시고 싶어요"),
        "我要休息" to arrayOf("I need to rest", "休みたいです", "쉬고 싶어요"),
        "上廁所" to arrayOf("Use the toilet", "トイレに行きます", "화장실 갈게요"),
        "吃藥時間" to arrayOf("Medicine time", "薬を飲む時間", "약 먹을 시간"),
        "打給醫生" to arrayOf("Call the doctor", "医師に電話します", "의사에게 전화할게요"),
        "ATM" to arrayOf("ATM", "ATM", "ATM"),
        "保健食品" to arrayOf("Supplements", "健康食品", "건강식품"),
        "感冒藥" to arrayOf("Cold medicine", "風邪薬", "감기약"),
        "蔬菜" to arrayOf("Vegetables", "野菜", "채소"),
        "蛋糕" to arrayOf("Cake", "ケーキ", "케이크"),
        "車輛" to arrayOf("Vehicle", "車両", "차량"),
        "餐巾" to arrayOf("Napkin", "ナプキン", "냅킨"),

        // ── 設定頁的列舉字面（SettingsRepository 的 displayName / description）──
        // 這些字寫在 data 層的 enum 建構子裡，不是 composable，所以只能走 tr()。
        // AppLanguage 的 displayName（繁體中文／English／日本語／한국어）刻意不翻——
        // 語言選單本來就該用該語言自己的寫法，翻了反而找不到自己的語言。
        "科技風" to arrayOf("Tech", "テック", "테크"),
        "可愛風" to arrayOf("Cute", "キュート", "큐트"),
        "動漫風" to arrayOf("Anime", "アニメ", "애니메이션"),
        "簡約風" to arrayOf("Minimal", "ミニマル", "미니멀"),
        "省電" to arrayOf("Power saver", "省電力", "절전"),
        "強制規則引擎、關閉相機、不連續監聽" to arrayOf(
            "Rules engine only, camera off, no continuous listening",
            "ルールエンジンのみ・カメラオフ・常時リスニング無し",
            "규칙 엔진만, 카메라 끔, 상시 청취 없음"),
        "依硬體自動分流（建議）" to arrayOf(
            "Routes automatically by hardware (recommended)",
            "ハードウェアに応じて自動振り分け（推奨）",
            "하드웨어에 따라 자동 분배 (권장)"),
        "高效能" to arrayOf("Performance", "高性能", "고성능"),
        "優先 LLM 推理、允許連續監聽" to arrayOf(
            "Prefers LLM inference, allows continuous listening",
            "LLM 推論を優先・常時リスニング可",
            "LLM 추론 우선, 상시 청취 허용"),
        "自動（網路 → 雲端，否則本地）" to arrayOf(
            "Auto (online → cloud, otherwise on-device)",
            "自動（オンライン→クラウド、それ以外は端末内）",
            "자동 (온라인 → 클라우드, 아니면 기기 내)"),
        "僅本地（Qwen）" to arrayOf("On-device only (Qwen)", "端末内のみ（Qwen）", "기기 내에서만 (Qwen)"),
        "自動切換" to arrayOf("Auto switch", "自動切替", "자동 전환"),
        "中文語音" to arrayOf("Chinese voice", "中国語音声", "중국어 음성"),
        "英文語音" to arrayOf("English voice", "英語音声", "영어 음성"),

        // ── 生圖服務商名稱（ImageApi）──
        "本機電腦（Mac，免金鑰）" to arrayOf(
            "Your computer (Mac, no key)", "自分の PC（Mac・キー不要）", "내 컴퓨터 (Mac, 키 불필요)"),
        "Gemini 生圖" to arrayOf("Gemini Image", "Gemini 画像生成", "Gemini 이미지"),
        "Pollinations（免費）" to arrayOf("Pollinations (free)", "Pollinations（無料）", "Pollinations (무료)"),

        // ── 文字 LLM 服務商名稱（LlmApi）──
        "Ollama（跑在你的電腦）" to arrayOf(
            "Ollama (runs on your computer)", "Ollama（自分の PC で動作）", "Ollama (내 컴퓨터에서 실행)"),
        "LM Studio（跑在你的電腦）" to arrayOf(
            "LM Studio (runs on your computer)", "LM Studio（自分の PC で動作）", "LM Studio (내 컴퓨터에서 실행)"),

        // ── 語音合成服務商名稱與欄位說明（TtsApi）──
        "手機內建語音（免金鑰）" to arrayOf(
            "Phone built-in voice (no key)", "端末内蔵の音声（キー不要）", "휴대폰 내장 음성 (키 불필요)"),
        "GPT-SoVITS（跑在你的電腦）" to arrayOf(
            "GPT-SoVITS (runs on your computer)", "GPT-SoVITS（自分の PC で動作）", "GPT-SoVITS (내 컴퓨터에서 실행)"),
        "OpenAI 語音" to arrayOf("OpenAI Voice", "OpenAI 音声", "OpenAI 음성"),
        "Groq 語音" to arrayOf("Groq Voice", "Groq 音声", "Groq 음성"),
        "SiliconFlow 語音" to arrayOf("SiliconFlow Voice", "SiliconFlow 音声", "SiliconFlow 음성"),
        "Google 雲端語音" to arrayOf("Google Cloud Voice", "Google クラウド音声", "Google 클라우드 음성"),
        "Azure 語音" to arrayOf("Azure Voice", "Azure 音声", "Azure 음성"),
        "MiniMax 語音" to arrayOf("MiniMax Voice", "MiniMax 音声", "MiniMax 음성"),
        "Fritz-PlayAI 等；中文請改用 playai-tts-arabic 以外的多語模型" to arrayOf(
            "Fritz-PlayAI and others; for Chinese pick a multilingual model other than playai-tts-arabic",
            "Fritz-PlayAI など。中国語は playai-tts-arabic 以外の多言語モデルを選択",
            "Fritz-PlayAI 등. 중국어는 playai-tts-arabic 외의 다국어 모델을 사용"),
        "…:alex／…:anna／…:bella；也可填自己複製的音色 id" to arrayOf(
            "…:alex / …:anna / …:bella — or paste your own cloned voice id",
            "…:alex／…:anna／…:bella。自分でクローンした音色 id も可",
            "…:alex / …:anna / …:bella. 직접 복제한 음색 id도 가능"),
        "Voice ID（在 ElevenLabs 的 Voices 頁複製）" to arrayOf(
            "Voice ID (copy it from the Voices page on ElevenLabs)",
            "Voice ID（ElevenLabs の Voices ページからコピー）",
            "Voice ID (ElevenLabs Voices 페이지에서 복사)"),
        "cmn-TW-Wavenet-A／-B／-C（台灣中文）" to arrayOf(
            "cmn-TW-Wavenet-A / -B / -C (Taiwan Mandarin)",
            "cmn-TW-Wavenet-A／-B／-C（台湾中国語）",
            "cmn-TW-Wavenet-A / -B / -C (대만 중국어)"),
        "區域 Region" to arrayOf("Region", "リージョン", "지역 Region"),
        "例 eastasia、japaneast" to arrayOf(
            "e.g. eastasia, japaneast", "例：eastasia、japaneast", "예: eastasia, japaneast"),
        "模型即聲音：aura-2-thalia-en 等" to arrayOf(
            "The model is the voice: aura-2-thalia-en, etc.",
            "モデル＝音声：aura-2-thalia-en など",
            "모델이 곧 음성: aura-2-thalia-en 등"),
        "Voice ID（Cartesia 主控台複製）" to arrayOf(
            "Voice ID (copy it from the Cartesia console)",
            "Voice ID（Cartesia コンソールからコピー）",
            "Voice ID (Cartesia 콘솔에서 복사)"),
        "reference_id（留空＝用預設聲音）" to arrayOf(
            "reference_id (leave blank to use the default voice)",
            "reference_id（空欄＝既定の音声）",
            "reference_id (비워두면 기본 음성)"),
        "female-tianmei／male-qn-qingse 等" to arrayOf(
            "female-tianmei / male-qn-qingse, etc.",
            "female-tianmei／male-qn-qingse など",
            "female-tianmei / male-qn-qingse 등"),
        "在 MiniMax 主控台帳號資訊裡" to arrayOf(
            "Found under account info in the MiniMax console",
            "MiniMax コンソールのアカウント情報内",
            "MiniMax 콘솔의 계정 정보에 있음"),
        "Voice manifest 網址" to arrayOf(
            "Voice manifest URL", "Voice manifest の URL", "Voice manifest URL"),
        "PlayHT 主控台的 User ID" to arrayOf(
            "User ID from the PlayHT console",
            "PlayHT コンソールの User ID",
            "PlayHT 콘솔의 User ID"),
        "scott／lisa 等" to arrayOf("scott / lisa, etc.", "scott／lisa など", "scott / lisa 등"),

        // ── GPT-SoVITS 連線教學（SettingsScreen 的 USB／Wi-Fi 步驟）──
        // 「GPT-SoVITS 要 Python + PyTorch…」那句已在上面（約 338 行）有譯文，不重複加
        "① USB 線把手機接上 Mac，授權 USB 偵錯\n② Mac 雙擊「VoiceWeaver語音中心.app」(會自動 adb reverse 並啟動伺服器)\n③ 按「測試連線」綠燈 → 用「🎤 合成測試」確認授權角色音色" to arrayOf(
            "① Connect the phone to the Mac with a USB cable and allow USB debugging\n② On the Mac, double-click “VoiceWeaver Voice Center.app” (it runs adb reverse and starts the server)\n③ Tap “Test connection” for a green light → use “🎤 Synthesis test” to check the licensed voice",
            "① USB ケーブルでスマホを Mac に接続し、USB デバッグを許可\n② Mac で「VoiceWeaver語音中心.app」をダブルクリック（adb reverse とサーバー起動を自動実行）\n③「接続テスト」が緑になったら →「🎤 合成テスト」で許諾済みの音色を確認",
            "① USB 케이블로 휴대폰을 Mac에 연결하고 USB 디버깅 허용\n② Mac에서 ‘VoiceWeaver語音中心.app’ 더블클릭 (adb reverse 실행 및 서버 시작)\n③ ‘연결 테스트’ 초록불 확인 → ‘🎤 합성 테스트’로 라이선스 음색 확인"),
        "① 手機與 Mac 連同一個 Wi-Fi\n② Mac 雙擊「VoiceWeaver語音中心.app」啟動伺服器\n③ 看控制中心(自動開啟)顯示的區網 IP，上方 API URL 改成 http://<那個IP>:9880\n④ 按「測試連線」綠燈（外網可裝 Tailscale，改填 100.x IP）" to arrayOf(
            "① Put the phone and the Mac on the same Wi-Fi\n② On the Mac, double-click “VoiceWeaver Voice Center.app” to start the server\n③ Read the LAN IP shown in the control center (it opens automatically) and set the API URL above to http://<that IP>:9880\n④ Tap “Test connection” for a green light (for remote access install Tailscale and use the 100.x IP)",
            "① スマホと Mac を同じ Wi-Fi に接続\n②  Mac で「VoiceWeaver語音中心.app」をダブルクリックしてサーバーを起動\n③ 自動で開くコントロールセンターに表示される LAN IP を確認し、上の API URL を http://<その IP>:9880 に変更\n④「接続テスト」が緑になれば完了（外出先からは Tailscale を入れて 100.x の IP を入力）",
            "① 휴대폰과 Mac을 같은 Wi-Fi에 연결\n② Mac에서 ‘VoiceWeaver語音中心.app’을 더블클릭해 서버 시작\n③ 자동으로 열리는 컨트롤 센터에 표시된 LAN IP를 확인하고, 위 API URL을 http://<그 IP>:9880 으로 변경\n④ ‘연결 테스트’ 초록불 확인 (외부에서는 Tailscale 설치 후 100.x IP 입력)"),

        // ── 這一輪掃描補上的：都是已經包了 tr() 卻不在表裡的鍵。
        //    tr() 找不到鍵會原樣回傳中文，所以英日韓介面看到的一直是中文。 ──

        // 語音辨識供應商（CustomApiFloatingWindow）
        "語音辨識" to arrayOf("Speech recognition", "音声認識", "음성 인식"),
        "由上往下試，第一個成功就用它。把最聽得懂你的那家排到最上面。留空＝沿用文字 LLM 附帶的轉寫。" to arrayOf(
            "Tried from top to bottom; the first one that works is used. Put whichever understands you best at the top. Leave empty to use the transcription that comes with the text LLM.",
            "上から順に試し、最初に成功したものを使います。いちばん聞き取ってくれる所を先頭に。空欄なら文字 LLM 付属の書き起こしを使います。",
            "위에서부터 차례로 시도하여 처음 성공한 것을 사용합니다. 가장 잘 알아듣는 곳을 맨 위로 두세요. 비워 두면 텍스트 LLM에 딸린 전사를 사용합니다."),
        "已停用" to arrayOf("Disabled", "無効", "사용 안 함"),
        "往上移" to arrayOf("Move up", "上へ", "위로"),
        "往下移" to arrayOf("Move down", "下へ", "아래로"),
        "模型" to arrayOf("Model", "モデル", "모델"),

        // 診斷日誌
        "診斷日誌" to arrayOf("Diagnostic log", "診断ログ", "진단 로그"),
        "分享診斷日誌" to arrayOf("Share diagnostic log", "診断ログを共有", "진단 로그 공유"),
        "顯示全部" to arrayOf("Show all", "すべて表示", "전체 보기"),
        "只看問題" to arrayOf("Problems only", "問題のみ", "문제만"),
        "目前沒有紀錄。操作一次語音或朗讀之後回來看。" to arrayOf(
            "No entries yet. Use speech or read-aloud once, then come back.",
            "まだ記録がありません。音声または読み上げを一度使ってから戻ってきてください。",
            "아직 기록이 없습니다. 음성이나 읽어주기를 한 번 사용한 뒤 다시 확인하세요."),
        "檔案位置：Android/data/…/files/diagnostics/（保留 7 天）" to arrayOf(
            "File location: Android/data/…/files/diagnostics/ (kept for 7 days)",
            "保存先：Android/data/…/files/diagnostics/（7 日間保持）",
            "파일 위치: Android/data/…/files/diagnostics/ (7일간 보관)"),
        "每一次語音辨識、句子重組、語音合成都會記錄在手機本機。回報問題時請按「分享」把它傳出來。" to arrayOf(
            "Every speech recognition, sentence rebuild and speech synthesis is logged on this phone only. When reporting a problem, tap Share to send it.",
            "音声認識・文の再構成・音声合成のたびに、この端末内だけに記録します。問題を報告するときは「共有」で送ってください。",
            "음성 인식·문장 재구성·음성 합성이 있을 때마다 이 휴대폰에만 기록됩니다. 문제를 알릴 때 '공유'를 눌러 보내 주세요."),

        // 「AI 直接聽」音訊來源
        "AI 直接聽（不轉文字）" to arrayOf(
            "Let the AI listen directly (no transcription)",
            "AI が直接聴く（文字にしない）",
            "AI가 직접 듣기 (문자 변환 없음)"),
        "自動" to arrayOf("Automatic", "自動", "자동"),
        "只用電腦本機" to arrayOf("Computer only", "パソコンのみ", "컴퓨터만"),
        "只用雲端" to arrayOf("Cloud only", "クラウドのみ", "클라우드만"),
        "電腦端就緒就用電腦，否則用雲端。" to arrayOf(
            "Uses your computer when it is ready, otherwise the cloud.",
            "パソコンが使える時はパソコン、それ以外はクラウドを使います。",
            "컴퓨터가 준비되면 컴퓨터를, 아니면 클라우드를 사용합니다."),
        "免金鑰、不會用掉雲端額度，語音不離開你的區網。電腦沒開就會直接告訴你，不偷偷改用雲端。" to arrayOf(
            "No API key, no cloud quota used, and your voice never leaves your local network. If the computer is off it tells you plainly instead of quietly switching to the cloud.",
            "APIキー不要、クラウドの割り当ても消費せず、音声はローカルネットワークから出ません。パソコンが起動していなければ黙ってクラウドに切り替えず、そのまま知らせます。",
            "API 키가 필요 없고 클라우드 할당량도 쓰지 않으며, 음성이 내부 네트워크를 벗어나지 않습니다. 컴퓨터가 꺼져 있으면 몰래 클라우드로 바꾸지 않고 그대로 알려 줍니다."),
        "品質優先。注意：免費額度容易用完（每次會打 3 次 API）。" to arrayOf(
            "Quality first. Note: the free quota runs out quickly (each run makes 3 API calls).",
            "品質優先。注意：無料枠はすぐ尽きます（1 回につき API を 3 回呼びます）。",
            "품질 우선. 참고: 무료 할당량이 금방 소진됩니다(1회에 API를 3번 호출)."),
        "電腦本機需要在電腦上執行「音訊理解伺服器」（tools/GPT-SoVITS/start_audio_infer.sh，埠 9883）。" to arrayOf(
            "Computer mode needs the audio understanding server running on your computer (tools/GPT-SoVITS/start_audio_infer.sh, port 9883).",
            "パソコンで使うには「音声理解サーバー」を起動しておく必要があります（tools/GPT-SoVITS/start_audio_infer.sh、ポート 9883）。",
            "컴퓨터 모드는 컴퓨터에서 '오디오 이해 서버'가 실행 중이어야 합니다(tools/GPT-SoVITS/start_audio_infer.sh, 포트 9883)."),
        "電腦本機·直接聽音訊（免金鑰）" to arrayOf(
            "Your computer · listens to the audio directly (no API key)",
            "パソコン・音声を直接聴く（APIキー不要）",
            "내 컴퓨터 · 오디오를 직접 듣기 (API 키 불필요)"),

        // 字級預設
        "超大" to arrayOf("Huge", "特大", "아주 크게"),

        // 執行期訊息
        "沒有聽到聲音，請再說一次（或把手機拿近一點）" to arrayOf(
            "I didn't hear anything. Please say it again, or hold the phone closer.",
            "音が聞こえませんでした。もう一度話すか、端末を近づけてください。",
            "소리가 들리지 않았습니다. 다시 말하거나 휴대폰을 가까이 대 주세요."),
        "AI 回應逾時，請確認網路後再試一次。" to arrayOf(
            "The AI took too long to answer. Check your connection and try again.",
            "AI の応答がタイムアウトしました。通信状態を確認してもう一度お試しください。",
            "AI 응답 시간이 초과되었습니다. 네트워크를 확인한 뒤 다시 시도하세요."),
        "「AI 直接聽」失敗：%s" to arrayOf(
            "Direct AI listening failed: %s",
            "「AI が直接聴く」に失敗しました：%s",
            "'AI가 직접 듣기'에 실패했습니다: %s"),
        "語音辨識失敗：%s" to arrayOf(
            "Speech recognition failed: %s",
            "音声認識に失敗しました：%s",
            "음성 인식에 실패했습니다: %s"),
        "我的專屬發音（離線）" to arrayOf(
            "My own pronunciation (offline)",
            "わたし専用の発音（オフライン）",
            "내 전용 발음 (오프라인)"),

        // ── 看圖說故事：整個模組原本完全沒有走翻譯層 ──
        "賣一包檳榔給客人" to arrayOf("Sell a pack of betel nut to a customer", "客にビンロウを一袋売る", "손님에게 빈랑 한 봉지 팔기"),
        "覺得不舒服，找家人幫忙" to arrayOf("Feeling unwell, asking family for help", "具合が悪くて家族に助けを求める", "몸이 안 좋아 가족에게 도움 청하기"),
        "廟口買一杯結冰水" to arrayOf("Buy an iced drink by the temple", "廟の前で氷入りの飲み物を買う", "사당 앞에서 얼음물 사기"),
        "客人走到攤位" to arrayOf("A customer walks up to the stall", "客が屋台に来る", "손님이 노점에 온다"),
        "拿出包葉檳榔" to arrayOf("Take out the wrapped betel nut", "葉で包んだビンロウを出す", "잎에 싼 빈랑을 꺼낸다"),
        "客人付 100 元" to arrayOf("The customer pays 100 dollars", "客が 100 元払う", "손님이 100원을 낸다"),
        "找零 50 元給客人" to arrayOf("Give 50 dollars change", "50 元のおつりを渡す", "50원을 거슬러 준다"),
        "胸口悶悶的" to arrayOf("A tight feeling in the chest", "胸が苦しい", "가슴이 답답하다"),
        "拿起電話" to arrayOf("Pick up the phone", "電話を取る", "전화를 든다"),
        "決定去醫院" to arrayOf("Decide to go to the hospital", "病院へ行くことにする", "병원에 가기로 한다"),
        "家人陪同" to arrayOf("Family goes along", "家族が付き添う", "가족이 함께 간다"),
        "經過廟口" to arrayOf("Passing by the temple", "廟の前を通る", "사당 앞을 지난다"),
        "看到飲料攤" to arrayOf("Spot a drink stall", "飲み物の屋台を見つける", "음료 노점을 발견한다"),
        "拿出 50 元" to arrayOf("Take out 50 dollars", "50 元を出す", "50원을 꺼낸다"),
        "喝到結冰水" to arrayOf("Drink the iced water", "氷水を飲む", "얼음물을 마신다"),

        // ── 圖表與權限 ──
        "進步趨勢（平均分數）" to arrayOf("Progress trend (average score)", "上達の推移（平均スコア）", "향상 추이 (평균 점수)"),
        "正向情緒字眼次數" to arrayOf("Positive-word count", "前向きな言葉の回数", "긍정적 표현 횟수"),
        "尚無資料" to arrayOf("No data yet", "データがありません", "아직 데이터가 없습니다"),
        "授予權限" to arrayOf("Grant permission", "権限を許可", "권한 허용"),
        "需要相機權限" to arrayOf("Camera permission required", "カメラの権限が必要です", "카메라 권한이 필요합니다"),
        // 注意：既有的「關閉」是開關的 Off，語意不同不能共用同一個鍵——
        // Kotlin 的 mapOf 後者會覆蓋前者，共用會讓全部開關變成 "Close"。
        "關閉視窗" to arrayOf("Close", "閉じる", "닫기"),
        "對準電腦語音中心顯示的 QR Code" to arrayOf(
            "Point at the QR code shown by the Voice Hub on your computer",
            "パソコンのボイスハブに表示された QR コードに合わせてください",
            "컴퓨터의 보이스 허브에 표시된 QR 코드를 비추세요"),

        // ── 推給家人的成績單（Telegram）──
        "成績單" to arrayOf("Progress report", "成績表", "성적표"),
        "復健次數" to arrayOf("Practice sessions", "リハビリ回数", "재활 횟수"),
        "平均分數" to arrayOf("Average score", "平均スコア", "평균 점수"),
        "圖卡正確率" to arrayOf("Card accuracy", "カード正答率", "카드 정답률"),
        "正向情緒字眼" to arrayOf("Positive words", "前向きな言葉", "긍정적 표현"),
        "故事練習" to arrayOf("Story practice", "ストーリー練習", "이야기 연습"),
        "平均" to arrayOf("avg", "平均", "평균"),
        "尚無趨勢資料，繼續加油 💪" to arrayOf(
            "Not enough data for a trend yet — keep going 💪",
            "傾向を出すにはまだデータが足りません。その調子で 💪",
            "추세를 볼 만한 데이터가 아직 없습니다. 계속 힘내세요 💪"),
        "趨勢向上 📈 — 持續進步中！" to arrayOf(
            "Trending up 📈 — steady progress!",
            "上向きです 📈 — 着実に伸びています！",
            "상승세입니다 📈 — 꾸준히 좋아지고 있어요!"),
        "趨勢略降 📉 — 適度休息，明天再戰" to arrayOf(
            "Slightly down 📉 — take a rest and try again tomorrow",
            "やや下がりました 📉 — 少し休んで、また明日",
            "조금 내려갔습니다 📉 — 잠시 쉬고 내일 다시 해요"),
        "保持穩定 ✨" to arrayOf("Holding steady ✨", "安定しています ✨", "안정적으로 유지 중 ✨"),
        // 圖表 X 軸的月份標籤。英文只放數字會跟「12/3」那種日期標籤混淆，加個 M。
        "%d月" to arrayOf("M%d", "%d月", "%d월"),

        // ── GPT-SoVITS 匯入對話框 ──
        "匯入中文參考音檔" to arrayOf("Import Chinese reference audio", "中国語の参照音声を取り込む", "중국어 참조 음성 가져오기"),
        "匯入英文參考音檔" to arrayOf("Import English reference audio", "英語の参照音声を取り込む", "영어 참조 음성 가져오기"),
        "匯入中文模型資料夾" to arrayOf("Import Chinese model folder", "中国語モデルのフォルダを取り込む", "중국어 모델 폴더 가져오기"),
        "匯入英文模型資料夾" to arrayOf("Import English model folder", "英語モデルのフォルダを取り込む", "영어 모델 폴더 가져오기"),
        "選擇舊版模型資料夾" to arrayOf("Choose a legacy model folder", "旧版モデルのフォルダを選ぶ", "구버전 모델 폴더 선택"),
        "自動匯入整包資料夾" to arrayOf("Auto-import the whole folder", "フォルダ一式を自動で取り込む", "폴더 전체 자동 가져오기"),
        "這會開啟 Android 系統檔案頁。若不想進檔案頁，請先用模型資料夾或自動匯入整包。" to arrayOf(
            "This opens the Android file picker. If you would rather not, use the model folder or whole-folder auto-import instead.",
            "Android のファイル選択画面が開きます。使いたくない場合は、モデルフォルダかフォルダ一式の自動取り込みをお使いください。",
            "Android 파일 선택 화면이 열립니다. 원치 않으면 모델 폴더나 폴더 전체 자동 가져오기를 사용하세요."),
        "建議改用中文/英文模型資料夾。系統檔案頁若沒有關閉鈕，請用手機返回鍵離開。" to arrayOf(
            "The Chinese/English model folders are recommended instead. If the file picker has no close button, use the phone's back button to leave.",
            "中国語／英語のモデルフォルダをお勧めします。ファイル選択画面に閉じるボタンが無い場合は、端末の戻るボタンで出てください。",
            "중국어/영어 모델 폴더 사용을 권장합니다. 파일 선택 화면에 닫기 버튼이 없으면 휴대폰 뒤로 가기 버튼으로 나가세요."),
        "選包含 SoVITS_weights / GPT_weights 的中文授權模型資料夾。也可以把整包放到預設資料夾後自動掃描。" to arrayOf(
            "Choose the licensed Chinese model folder containing SoVITS_weights / GPT_weights. You can also drop the whole folder into the default location and let it scan automatically.",
            "SoVITS_weights / GPT_weights を含む、ライセンス済みの中国語モデルフォルダを選んでください。既定のフォルダに一式を置いて自動スキャンさせることもできます。",
            "SoVITS_weights / GPT_weights가 들어 있는 라이선스된 중국어 모델 폴더를 선택하세요. 기본 폴더에 통째로 넣어 자동 검색하게 할 수도 있습니다."),
        "選包含 SoVITS_weights / GPT_weights 的英文授權模型資料夾。也可以把整包放到預設資料夾後自動掃描。" to arrayOf(
            "Choose the licensed English model folder containing SoVITS_weights / GPT_weights. You can also drop the whole folder into the default location and let it scan automatically.",
            "SoVITS_weights / GPT_weights を含む、ライセンス済みの英語モデルフォルダを選んでください。既定のフォルダに一式を置いて自動スキャンさせることもできます。",
            "SoVITS_weights / GPT_weights가 들어 있는 라이선스된 영어 모델 폴더를 선택하세요. 기본 폴더에 통째로 넣어 자동 검색하게 할 수도 있습니다."),
        "你可以開系統檔案頁選最外層資料夾，也可以把整包放到預設資料夾後直接掃描。預設資料夾：%s" to arrayOf(
            "You can pick the top-level folder in the file picker, or drop the whole folder into the default location and scan it directly. Default folder: %s",
            "ファイル選択画面で最上位のフォルダを選ぶか、既定のフォルダに一式を置いてそのままスキャンできます。既定のフォルダ：%s",
            "파일 선택 화면에서 최상위 폴더를 고르거나, 기본 폴더에 통째로 넣고 바로 검색할 수 있습니다. 기본 폴더: %s"),

        // ── GPT-SoVITS 情緒名（顯示用；比對用的正規化鍵留在 LocalSovitsHelper 不動）──
        "中立" to arrayOf("Neutral", "ニュートラル", "중립"),
        "开心" to arrayOf("Happy", "うれしい", "기쁨"),
        "难过" to arrayOf("Sad", "かなしい", "슬픔"),
        "恐惧" to arrayOf("Fearful", "こわい", "두려움"),
        "吃惊" to arrayOf("Surprised", "おどろき", "놀람"),

        // ── 專屬發音總開關 ──
        "啟用專屬發音" to arrayOf("Use my own pronunciation", "わたし専用の発音を使う", "내 전용 발음 사용"),
        "關掉之後不會再做聲波比對，錄好的樣板都還留著。" to arrayOf(
            "Turning this off stops the waveform matching. Your recordings are kept.",
            "オフにすると波形照合を行いません。録音はそのまま残ります。",
            "끄면 파형 대조를 하지 않습니다. 녹음은 그대로 남습니다."),
    )
}
