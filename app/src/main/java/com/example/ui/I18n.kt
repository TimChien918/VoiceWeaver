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
        "取消" to arrayOf("Cancel", "キャンセル", "취소"),
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
    )
}
