package com.speakmylife.app.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 一鍵自動演示（做報告／簡報用）。
 *
 * 與網頁版同一套設計，但這裡刻意**只做導覽與旁白，不偽造任何畫面內容**。
 * 網頁版可以直接塞 DOM 讓結果看起來像真的跑過一次；Compose 沒有對應的東西，
 * 硬要模擬就得去動各畫面的內部狀態——那會把演示程式碼纏進整個 App，
 * 之後任何一個畫面改版都可能讓演示壞掉，而且很容易不小心寫到真的資料。
 * 所以這裡走的是真的畫面、真的資料，旁白負責講解。
 *
 * 三個不會妥協的點：
 * ① 演示期間不寫入任何資料，也不送出任何訊息（不碰緊急通報／危機介入的送出路徑）。
 *    會動到的設定（字級、嚴重度、高對比）開始前快照、結束一定還原。
 * ② 旁白在**開始錄影之前**全部合成完。GPT-SoVITS 在電腦 CPU 上一句要 10～30 秒，
 *    邊演邊合成等於把幾十分鐘的空白錄進影片裡。
 * ③ 字幕與旁白跟著按下開始時的介面語言（中英日韓，見 [demoLang]），途中不變。
 *    2027 TEYI 世界青少年發明展的演示是在評審面前或錄影播放的，評審看哪種語言的畫面，
 *    就該聽到同一種語言的解說。段落與用語與網頁版 js/demo.js 對齊。
 */
object DemoController {

    // 每一步的最短停留。沒有下限的話動作做完就立刻跳下一步——十幾個單元五十幾步
    // 會在一分鐘內衝完，觀眾連畫面變了什麼都看不清楚。有旁白時通常是旁白比較長
    // （下限很少生效）；純演示時它就是實際節奏。
    private const val MIN_STEP_NARRATED_MS = 3200L
    private const val MIN_STEP_SILENT_MS = 4200L

    // 旁白唸完之後，畫面再多停一下才換。零間隔看起來像「話還沒講完就跳掉」——
    // 最後一個字的尾音、加上人腦讀完字幕需要的一點時間，都落在這一段裡。
    private const val TAIL_PAD_MS = 900L

    // 等外部回呼的上限。旁白音檔、系統 TTS、畫面動作都可能不回來（引擎被回收、
    // MediaPlayer 卡在 prepare、某個 onStage 自己拋在 delay 裡）。演示是無人看顧
    // 跑完整段錄影的，任何一個永遠等下去的地方都會變成一支卡住的長影片。
    // 演示裡「按下重組」之後要轉多久。取一次雲端重組的實測中位數——
    // 這一步的重點就是讓觀眾看到真實的等待，所以它不該比真的短。
    private const val RECONSTRUCT_WAIT_MS = 4_500L

    // 專屬聲音「合成」的停頓。音檔是預錄好的，所以其實不必等，但真的按下去
    // 是要等電腦跑 10～30 秒的——瞬間出聲會讓觀眾以為這個 App 是即時的。
    // 停的長度是縮短的，但「要等」這件事本身不是演的。
    private const val VOICE_SYNTH_DELAY_MS = 2_000L

    // ── 預錄旁白的重試 ──────────────────────────────────────────────────
    //
    // 一段旁白大約 60 句，每句要電腦合成 10～30 秒。USB（adb reverse）是直通的，
    // 幾乎不會掉；但走 Wi-Fi／Tailscale／ngrok(Colab) 只要**其中一句**遇到一次
    // 抖動，原本的寫法就整支中止——而且是在使用者等完全部 60 句之後才中止。
    // 體感就是「只有插線才錄得起來」。
    //
    // 一次失敗不代表這條線不能用，所以每句重試。退避是為了讓隧道有時間重建：
    // 剛掉包的下一秒通常還是掉包。
    private const val PRERECORD_ATTEMPTS = 3
    private const val PRERECORD_BACKOFF_MS = 1_500L

    // 捲動動畫跑完要一點時間。不等就開始講，觀眾看到的是「畫面還在滑」而旁白
    // 已經在講那個東西了；而且太早檢查可見性也會誤報。
    private const val FOCUS_SETTLE_MS = 650L

    // 切頁有轉場動畫（約 300ms）。切完立刻捲動或改狀態的話，動到的是還在淡出的舊頁面，
    // 或新頁面的捲動狀態還沒建好——捲動訊號就這樣漏掉，錄影池回報「看不見」。
    private const val NAV_SETTLE_MS = 350L

    private const val SPEAK_TIMEOUT_MS = 20_000L
    private const val CLIP_TIMEOUT_MS = 60_000L
    private const val STAGE_TIMEOUT_MS = 30_000L

    /**
     * 給 UI 估時用：一步大概多久。
     * 英文約每秒 2.6 個字；中日韓沒有空白分詞，改數字元（與網頁版同一組語速）。
     */
    fun stepMillis(cap: String, narrated: Boolean): Long {
        if (!narrated) return MIN_STEP_SILENT_MS
        val cjkRate = when (com.speakmylife.app.ai.LocalSovitsHelper.detectLang(cap)) {
            com.speakmylife.app.ai.LocalSovitsHelper.VoiceLang.ZH -> 4.2
            com.speakmylife.app.ai.LocalSovitsHelper.VoiceLang.JA -> 6.5
            com.speakmylife.app.ai.LocalSovitsHelper.VoiceLang.KO -> 5.5
            else -> 0.0
        }
        val ms = if (cjkRate > 0) cap.count { !it.isWhitespace() } / cjkRate * 1000
                 else cap.split(Regex("\\s+")).count { it.isNotBlank() } / 2.6 * 1000
        return maxOf(MIN_STEP_NARRATED_MS, ms.toLong()) + TAIL_PAD_MS
    }

    /** 勾選的單元總長（秒）。
     *
     *  含 stageMs：畫面動作裡自己 delay 的時間（例如危機那段在對話之間刻意停頓）。
     *  不算進去的話，有大量畫面動作的單元會被嚴重低估——使用者是靠這個數字
     *  決定要不要按下錄影的。 */
    fun estimateSeconds(ids: Set<String>, narrated: Boolean): Int =
        (UNITS.filter { it.id in ids }
            .flatMap { it.steps }
            .sumOf {
                // 有 focus 的步驟開講前要先捲、再等捲動停下來，那段時間也錄在影片裡。
                val settle = (if (it.focus != null) FOCUS_SETTLE_MS else 0L) +
                    (if (it.route != null) NAV_SETTLE_MS else 0L)
                maxOf(stepMillis(it.cap, narrated), it.stageMs) + it.holdMs + settle
            } / 1000).toInt()

    /**
     * 演示可以對畫面做的事。由 MainActivity 用 ViewModel 實作。
     *
     * 沒有這一層的話，演示就只是「切頁面＋旁白」——錄出來會變成旁白在講
     * 「AI 給了三個候選」，畫面卻從頭到尾沒動過，觀眾看不到旁白說的東西。
     */
    interface Stage {
        fun type(text: String)
        fun candidates(list: List<String>)
        fun pick(index: Int)
        /** 圖卡：切分類。 */
        fun aacCategory(key: String?)
        /** 圖卡：把幾張卡放進句子緩衝（傳 AacItem 的 id）。 */
        fun aacSentence(ids: List<String>)
        /** 復健：帶一句目標句進練習畫面。 */
        fun rehabTarget(sentence: String)
        /** 復健：把一次評分結果放上評分卡（樣本；不錄音、不呼叫 AI、不寫日誌）。 */
        fun rehabResult(score: Int, feedback: String, fluency: Int, prosody: Int, heard: String, wrong: List<String>)
        /** 情境感知：主畫面情境卡的地點與鏡頭標籤（只改畫面狀態，不定位、不開相機、不存檔）。 */
        fun context(place: String, seen: List<String>)
        /** 無障礙：字級倍率。 */
        fun fontScale(scale: Float)
        /** 無障礙：高對比。 */
        fun highContrast(on: Boolean)
        /** 字級與高對比放回演示開始前的值（無障礙段落演完、下一段開始前用）。 */
        fun restoreDisplay()
        /** 嚴重度模式（Mild / Moderate / Severe）——重度會把圖卡換成全螢幕大卡。 */
        fun severity(name: String)
        /** 主畫面捲到結果那一段（候選句在下面，不捲就看不到）。 */
        fun scrollToResult()
        /** 把某個位置露出來（"top"／"sos"／"context"／"input"／"result"）。 */
        fun focusOn(target: String)
        /** 上一次 focusOn 之後，那個位置到底有沒有在畫面上。 */
        fun focusWasVisible(target: String): Boolean
        /** 切成「正在重組」的等待畫面（真的那顆 Loading 元件）。 */
        fun reconstructing()
        /** 把「合成中…」指示亮起來／熄掉。 */
        fun synthesizing(on: Boolean)
        /** 視覺按壓某顆按鈕（tag；null＝放開）。不會真的觸發它的動作。 */
        fun press(tag: String?)
        /** 讓 App 把這句話唸出來（系統 TTS，即時，照介面語言），唸完呼叫 done。
         *  樣本句與旁白同一種語言（＝演示開始時的介面語言），所以照介面語言唸就對。 */
        fun speakAloud(text: String, done: () -> kotlin.Unit)
        /** 危機對話視窗：開啟。不會通報任何人。 */
        fun crisisOpen(status: String)
        /** 危機對話視窗：加一則訊息（who: "me" 病患／"family" 家人／其他 系統）。 */
        fun crisisSay(who: String, text: String)
        /** 危機對話視窗：更新上方狀態列。 */
        fun crisisStatus(status: String)
        /** 危機對話視窗：關閉。 */
        fun crisisClose()
        /** 安全閘門：顯示「發聲已鎖定」畫面（傳 null 收起來）。不會通報任何人。 */
        fun safetyLock(message: String?)
        /** 安全閘門：顯示醫療／疼痛內容的確認卡（傳 null 收起來）。不會真的唸出或記錄。 */
        fun safetyConfirm(text: String?)
        /** 打開設定裡的子面板（"voice"＝角色語音與雲端曲庫；null＝關掉）。 */
        fun openPanel(key: String?)
        /** 演示開始前先快照會存檔的設定。 */
        fun snapshotSettings()
        /** 把快照的設定放回去。**只在演示結束時呼叫一次**，不能混進 clear()。 */
        fun restoreSettings()
        fun clear()
    }

    data class Step(
        /** 字幕／旁白（英文）。空字串＝這一步只換畫面不講話。 */
        val cap: String,
        /** 要切到的頁面；null＝留在原地。 */
        val route: String? = null,
        /** 額外停留毫秒（在旁白講完之後）。 */
        val holdMs: Long = 0,
        /** 這一步要對畫面做什麼；null＝只切頁面。 */
        val onStage: (suspend (Stage) -> kotlin.Unit)? = null,
        /** onStage 裡大約會自己等多久（估時用；不影響實際執行）。 */
        val stageMs: Long = 0,
        /**
         * 這一步要用**專屬聲音**（GPT-SoVITS）唸的句子。
         *
         * 只有「我的專屬聲音」那一段用得到：那一段整段都在講這個功能，
         * 用系統機器音唸等於在示範這個功能不存在。和旁白分開預錄、用不同角色。
         */
        val voiceSample: String? = null,
        /**
         * 這一步在講畫面上的哪個地方（"top"／"sos"／"context"／"input"／"result"）。
         *
         * 錄影池的核心：旁白開始講之前，演示會先把這個地方捲出來，捲完再確認
         * 它真的在畫面上，不在就留一筆警告。以前是每發現一次「旁白在講某個
         * 東西、畫面上卻沒有它」就補一次 scroll——補到第三次就該把它變成規則。
         * null＝這一步沒有特定對象（片頭、純解說），不做任何事。
         */
        val focus: String? = null,
        /** 這一步要蓋上的標題卡（片頭／片尾用）；null＝維持上一步的狀態。 */
        val card: TitleCard? = null,
        /** 這一步把標題卡收掉（片頭最後一句要露出 App 畫面）。 */
        val hideCard: Boolean = false,
    )

    /**
     * 示範用的樣本（各語言一份；固定內容，不呼叫任何 AI）。
     *
     * 跟網頁版 js/demo.js 的 sample() 同一份文字：同一場比賽、同一組評審，
     * 兩邊演示講的例子不一樣會被問「哪個才是真的」。
     */
    internal class Sample(
        val fragments: String,
        val place: String,
        val seen: List<String>,
        val candidates: List<String>,
        val medical: String,
        val rehab: String,
        val heard: String,
        val wrong: List<String>,
        val rehabFb: String,
        val crisis: String,
        val crisisLog: List<String>,
        val crisisQuick: List<String>,
        val own: String,
        val sim: String,
        val lockMsg: String,
    )

    internal fun sample(l: String): Sample = when (l) {
        "zh" -> Sample(
            fragments = "水  休息  現在",
            place = "醫院復健科候診區", seen = listOf("水杯", "病床"),
            candidates = listOf("我想喝點水，然後休息一下。", "可以給我一杯水嗎？我現在想躺下來休息。", "我口渴了，喝完水想先睡一下。"),
            medical = "我的頭很痛，需要吃止痛藥。",
            rehab = "我想喝一杯水。", heard = "我想喝一背水", wrong = listOf("杯"),
            rehabFb = "節奏很清楚，「杯」的聲調再上揚一點會更好。",
            crisis = "好累  不想活了",
            crisisLog = listOf("📍 目前位置已傳給家人", "📹 視訊連結已傳給家人", "我馬上過去，先跟著圓圈慢慢呼吸。"),
            crisisQuick = listOf("我好害怕", "請快來", "我現在安全"),
            own = "謝謝你們一直陪著我，我好多了。",
            sim = "已通知家人（演示模擬，未實際送出）",
            lockMsg = "偵測到高風險訊息，已改為危機介入（演示模擬，未實際送出）",
        )
        "ja" -> Sample(
            fragments = "水  休む  今",
            place = "リハビリ科の待合室", seen = listOf("コップ", "ベッド"),
            candidates = listOf("お水を飲んで、少し休みたいです。", "お水を一杯もらえますか？今すぐ横になりたいです。", "のどが渇きました。水を飲んだら少し眠りたいです。"),
            medical = "頭がとても痛いです。痛み止めが必要です。",
            rehab = "お水を一杯ください。", heard = "お水を一敗ください", wrong = listOf("杯"),
            rehabFb = "リズムはとても良いです。「一杯」をもう少しはっきり言ってみましょう。",
            crisis = "つかれた  生きたくない",
            crisisLog = listOf("📍 現在地を家族に送信しました", "📹 ビデオ通話のリンクを家族に送信しました", "すぐ行くよ。丸に合わせてゆっくり呼吸してね。"),
            crisisQuick = listOf("こわい", "すぐ来て", "いまは大丈夫"),
            own = "いつもそばにいてくれてありがとう。だいぶ良くなりました。",
            sim = "家族に通知しました（デモの再現・実際には送信していません）",
            lockMsg = "危険な内容を検知したため、危機介入に切り替えました（デモの再現・実際には送信していません）",
        )
        "ko" -> Sample(
            fragments = "물  쉬다  지금",
            place = "재활의학과 대기실", seen = listOf("컵", "병상"),
            candidates = listOf("물 좀 마시고 잠깐 쉬고 싶어요.", "물 한 잔 주시겠어요? 지금 눕고 싶어요.", "목이 말라요. 물 마시고 좀 자고 싶어요."),
            medical = "머리가 너무 아파요. 진통제가 필요해요.",
            rehab = "물 한 잔 주세요.", heard = "물 한 장 주세요", wrong = listOf("잔"),
            rehabFb = "리듬이 아주 좋아요. “잔”을 조금 더 또렷하게 말해 보세요.",
            crisis = "지쳤어  살기 싫어",
            crisisLog = listOf("📍 현재 위치를 가족에게 보냈습니다", "📹 영상 통화 링크를 가족에게 보냈습니다", "지금 갈게. 원을 따라 천천히 숨 쉬어."),
            crisisQuick = listOf("무서워요", "빨리 와 주세요", "지금은 괜찮아요"),
            own = "늘 곁에 있어 줘서 고마워요. 많이 좋아졌어요.",
            sim = "가족에게 알렸습니다(데모 재현 · 실제로 보내지 않음)",
            lockMsg = "위험한 내용이 감지되어 위기 개입으로 전환했습니다(데모 재현 · 실제로 보내지 않음)",
        )
        else -> Sample(
            fragments = "water  rest  now",
            place = "Rehab clinic waiting area", seen = listOf("cup", "hospital bed"),
            candidates = listOf("I'd like some water, then I need to rest.", "Could I have a glass of water? I want to lie down now.", "I'm thirsty — after some water I'd like a nap."),
            medical = "My head really hurts. I need a painkiller.",
            rehab = "I would like a glass of water.", heard = "I would like a grass of water", wrong = listOf("glass"),
            rehabFb = "Clear rhythm. Watch the “l” in “glass” — try it a little slower.",
            crisis = "tired  don't want  to live",
            crisisLog = listOf("📍 Location sent to family", "📹 Video link sent to family", "I'm on my way. Breathe with the circle."),
            crisisQuick = listOf("I'm scared", "Please come", "I'm safe now"),
            own = "Thank you for staying with me. I feel much better.",
            sim = "Family notified (demo simulation — nothing sent)",
            lockMsg = "High-risk message detected — switched to crisis intervention (demo simulation — nothing sent)",
        )
    }

    data class Unit(
        val id: String,
        val icon: String,
        val title: String,
        val note: String? = null,
        val steps: List<Step>,
    )

    /** 片頭／片尾卡的內容；null＝沒有卡片。 */
    data class TitleCard(val main: String, val sub: String)

    /** 品牌字（標題卡）。旁白裡英日韓只唸 Speak My Life——英文語音碰到漢字會跳過或唸成亂碼。 */
    private const val BRAND = "拾語者 Speak My Life"

    /**
     * 這一次演示的語言（"zh"／"en"／"ja"／"ko"）。
     *
     * 跟著**按下開始那一刻**的介面語言走，途中不變——評審看的是中文介面就聽中文旁白，
     * 錄給國外評審的就先把介面切成英文再錄。以前旁白一律英文，中文評審得一邊看中文畫面、
     * 一邊讀英文字幕。
     */
    fun demoLang(): String = when (com.speakmylife.app.ui.I18n.lang) {
        com.speakmylife.app.data.AppLanguage.ZhTW -> "zh"
        com.speakmylife.app.data.AppLanguage.Ja -> "ja"
        com.speakmylife.app.data.AppLanguage.Ko -> "ko"
        else -> "en"
    }

    /** 目前介面語言下的單元清單（演示挑選面板、估時用）。 */
    val UNITS: List<Unit> get() = units(demoLang())

    /**
     * 演示單元。順序＝設計書的敘事線，與網頁版（js/demo.js）同樣七段：
     *   開場（拾語者是什麼、為誰）→ ① 語意織句與情境補位 → ② 多模態無障礙
     *   → ③ AI 語音復健與復健軌跡 → ④ 語意防護與危機介入 → ⑤ 擬真聲紋與情感語音 → 收尾。
     * 文字逐句對齊網頁版；每一步的畫面動作都是 App 裡真的存在的畫面或狀態。
     */
    internal fun units(l: String): List<Unit> {
        fun c(zh: String, en: String, ja: String, ko: String) = when (l) {
            "zh" -> zh; "ja" -> ja; "ko" -> ko; else -> en
        }
        val s = sample(l)
        val tagline = c("中風失語者的 AI 溝通與居家復健系統",
            "AI communication & home rehabilitation for stroke survivors with aphasia",
            "脳卒中後の失語症の方のための AI コミュニケーションと在宅リハビリ",
            "뇌졸중 실어증 환자를 위한 AI 의사소통과 재택 재활")
        return listOf(
            Unit("intro", "🎬", c("開場：拾語者是什麼", "Opening: what Speak My Life is", "オープニング：拾語者とは", "오프닝: Speak My Life란"), steps = listOf(
                Step(c("全球每年約有一千兩百萬人新發生中風，其中約三成在急性期伴隨失語症。",
                    "Every year about twelve million people have a stroke, and roughly a third of them develop aphasia in the acute phase.",
                    "世界では毎年およそ1,200万人が脳卒中を発症し、その約3割が急性期に失語症を伴います。",
                    "전 세계에서 해마다 약 1,200만 명이 뇌졸중을 겪고, 그중 약 3분의 1이 급성기에 실어증을 동반합니다."),
                    route = "main", onStage = { it.clear() }, card = TitleCard(BRAND, tagline)),
                Step(c("失語症、言語失用症、吶語症——他們心裡很清楚想說什麼，卻說不出口。",
                    "Aphasia, apraxia of speech, dysarthria — they know exactly what they want to say, but the words won't come out.",
                    "失語症、発語失行、構音障害。言いたいことははっきりしているのに、言葉にできないのです。",
                    "실어증, 말실행증, 마비말장애. 하고 싶은 말은 분명한데 입 밖으로 나오지 않습니다."),
                    card = TitleCard(BRAND, c("失語症 · 言語失用症 · 吶語症", "Aphasia · Apraxia of speech · Dysarthria",
                        "失語症 · 発語失行 · 構音障害", "실어증 · 말실행증 · 마비말장애"))),
                Step(c("拾語者 Speak My Life，是為中風失語者打造的 AI 溝通與居家復健系統。",
                    "Speak My Life is an AI communication and home-rehabilitation system for stroke survivors with aphasia.",
                    "拾語者 Speak My Life は、脳卒中後の失語症の方のための AI コミュニケーション・在宅リハビリシステムです。",
                    "Speak My Life(拾語者)는 뇌졸중 실어증 환자를 위한 AI 의사소통·재택 재활 시스템입니다."),
                    card = TitleCard(BRAND, tagline)),
                Step(c("它把散落的碎詞拾起，重新組成自然、有溫度的句子，再由患者本人確認後說出口。",
                    "It picks up scattered words and weaves them into natural, warm sentences — spoken only after the patient confirms them.",
                    "ばらばらの言葉を拾い上げ、自然で温かい文に組み直し、ご本人が確認してから声にします。",
                    "흩어진 단어를 주워 자연스럽고 따뜻한 문장으로 엮고, 환자 본인이 확인한 뒤에야 소리 내어 말합니다.")),
                Step(c("接下來，依五大功能模組實際操作一遍。", "Let's walk through its five core modules.",
                    "それでは、5つの主要モジュールを順にご覧ください。", "이제 다섯 가지 핵심 모듈을 차례로 보겠습니다."),
                    hideCard = true),
            )),
            Unit("semantic", "💬", c("① 語意織句引擎・情境感知補位", "① Semantic sentence engine & context", "① 意味織文エンジンと状況補完", "① 의미 문장 엔진과 상황 보완"), steps = listOf(
                // 先捲到輸入框再打字：主畫面上面壓著「快速求救」與整張情境卡，輸入框在折線下面。
                Step(c("模組一，語意織句引擎。患者只要打出、說出或點出幾個碎詞，例如「水、休息、現在」。",
                    "Module one: the semantic sentence engine. The patient only needs a few fragments — typed, spoken or tapped — like “water, rest, now”.",
                    "モジュール1、意味織文エンジン。入力は、打つ・話す・タップするなどした断片的な言葉だけ。たとえば「水、休む、今」。",
                    "모듈 1, 의미 문장 엔진. 환자는 “물, 쉬다, 지금”처럼 단어 몇 개만 입력하거나 말하거나 누르면 됩니다."),
                    route = "main", focus = "input",
                    onStage = { it.clear(); typeSlowly(it, s.fragments) }, stageMs = s.fragments.length * 70L),
                // 捲到主畫面的情境卡（"context"）。地點與鏡頭標籤只放進
                // 畫面狀態，不寫進設定、不觸發定位／相機（結束時還原成演示前的情境）。
                Step(c("情境感知補位：GPS 判斷他正在醫院的復健科候診區，",
                    "Context awareness fills in the gaps: GPS places him in a rehab clinic waiting area,",
                    "状況認識が足りない部分を補います。GPSで、リハビリ科の待合室にいることがわかり、",
                    "상황 인식이 빈칸을 채웁니다. GPS로 재활의학과 대기실에 있다는 것을 알고,"),
                    focus = "context", onStage = { delay(300); it.context(s.place, emptyList()) }),
                Step(c("相機也辨識出眼前的水杯和病床。", "and the camera recognises the cup and the bed in front of him.",
                    "カメラが目の前のコップとベッドを認識します。", "카메라는 눈앞의 컵과 병상을 알아봅니다."),
                    focus = "context", onStage = { delay(300); it.context(s.place, s.seen) }),
                // 候選句是固定樣本（錄影途中被 LLM 逾時毀掉整支影片不能接受），
                // 但**等待不能假**：轉的是真的那顆 Loading 元件、轉真實長度。
                Step(c("大型語言模型把碎詞和情境織在一起，一次生成三個符合當下情境的候選句。",
                    "A large language model weaves the fragments and context together into three candidate sentences that fit the moment.",
                    "大規模言語モデルが言葉と状況を織り合わせ、その場に合った候補文を3つ生成します。",
                    "대규모 언어 모델이 단어와 상황을 엮어, 지금 상황에 맞는 후보 문장 세 개를 만듭니다."),
                    focus = "result", onStage = {
                        it.press("reconstruct"); delay(320)
                        it.press(null); delay(200)
                        it.reconstructing()
                        delay(RECONSTRUCT_WAIT_MS)
                        it.candidates(s.candidates); delay(300)
                        it.scrollToResult()
                    }, holdMs = 800, stageMs = RECONSTRUCT_WAIT_MS + 1500L),
                // 瀏覽到第三句再挑回第一句：只挑同一句的話畫面不會動。
                Step(c("這就是語意確認機制：點選候選句只是選定、不會發聲，選哪一句由患者自己決定。",
                    "This is the semantic confirmation step: tapping a candidate only selects it — the patient decides which one is right.",
                    "ここが意味確認の仕組みです。候補をタップしても選ぶだけで、声にはなりません。どれにするかはご本人が決めます。",
                    "이것이 의미 확인 장치입니다. 후보를 눌러도 선택만 될 뿐 소리는 나지 않고, 어떤 문장인지는 환자가 직접 정합니다."),
                    focus = "result", onStage = { delay(300); it.pick(1); delay(1300); it.pick(2); delay(1300); it.pick(0) },
                    holdMs = 500, stageMs = 2900L),
                Step(c("確認是這一句，按下朗讀，才真正說出口。", "Only when he confirms and presses Speak is the sentence said out loud.",
                    "これだと確かめて「読み上げ」を押したときだけ、声に出します。", "이 문장이 맞다고 확인하고 읽기를 눌러야 비로소 소리로 나옵니다."),
                    focus = "result", onStage = { delay(300); speakDemoSentence(it, s.candidates.first()) },
                    holdMs = 600, stageMs = VOICE_SYNTH_DELAY_MS + 1500L),
                // 確認卡不是每一句都會出現：它擋的是醫療／疼痛類（SafetyGuard 的 confirm 等級）。
                // 所以換一句真的會觸發它的樣本，旁白也照實說「醫療或疼痛相關」。
                // 用的是真的 SpeakGate.NeedConfirm 對話框，演示只改狀態、不按它的按鈕。
                Step(c("跟醫療或疼痛有關的句子，還會再跳出大圖示確認卡，按「對」才唸出——把語言的選擇權還給患者。",
                    "Anything about pain or medicine gets an extra large-print confirmation card. Nothing is said until he taps Yes — the choice of words stays with the patient.",
                    "痛みや医療に関わる文には、大きな確認カードがもう一度出ます。「はい」を押すまで読み上げません。言葉を選ぶ権利は、ご本人に返します。",
                    "통증이나 의료와 관련된 문장은 큰 확인 카드가 한 번 더 뜨고, “네”를 눌러야 읽어 줍니다. 말을 고르는 권리를 환자에게 돌려줍니다."),
                    onStage = {
                        delay(400); it.safetyConfirm(s.medical)
                        delay(4_000); it.safetyConfirm(null)
                        speakAndWait(it, s.medical)
                    }, holdMs = 600, stageMs = 4_400L + 2_500L),
            )),
            Unit("a11y", "♿", c("② 多模態無障礙介面", "② Multimodal accessible interface", "② マルチモーダル・アクセシブルUI", "② 멀티모달 접근성 인터페이스"), steps = listOf(
                Step(c("模組二，多模態無障礙介面。中風常伴隨偏癱與視力退化，所以介面依輕、中、重度動態調整。",
                    "Module two: a multimodal, adaptive interface. Strokes often bring paralysis and low vision, so the interface adapts to mild, moderate and severe needs.",
                    "モジュール2、マルチモーダルな適応型インターフェース。脳卒中は片まひや視力低下を伴うことが多いため、軽度・中度・重度に合わせて画面が変わります。",
                    "모듈 2, 멀티모달 적응형 인터페이스. 뇌졸중은 편마비나 시력 저하를 동반하는 경우가 많아, 경도·중등도·중증에 맞춰 화면이 바뀝니다."),
                    route = "aac", onStage = { it.clear(); it.severity("Mild"); it.aacCategory(null) }),
                Step(c("中度：圖卡放大成特大字，複雜的設定先收起來。",
                    "Moderate: picture cards grow to extra-large print, and complex settings are tucked away.",
                    "中度：絵カードは特大サイズになり、複雑な設定は隠れます。",
                    "중등도: 그림 카드가 아주 크게 바뀌고, 복잡한 설정은 숨겨집니다."),
                    onStage = { delay(300); it.severity("Moderate"); delay(600); it.aacCategory("needs") }, stageMs = 900L),
                Step(c("重度：全螢幕一次只出現一張大卡，自動逐張掃描，只要一個「是」的動作就能表達。",
                    "Severe: one large card fills the screen and scans automatically — a single “yes” action is enough to communicate.",
                    "重度：全画面に大きなカードが1枚ずつ表示され、自動で切り替わります。「はい」の動作ひとつで伝えられます。",
                    "중증: 큰 카드가 한 장씩 전체 화면에 나타나 자동으로 넘어가고, “예” 동작 하나만으로 의사를 전할 수 있습니다."),
                    onStage = { delay(300); it.severity("Severe") }, holdMs = 1200, stageMs = 300L),
                Step(c("選過的卡留成一串線索，熟悉的家人一看就懂。",
                    "Chosen cards stay as a trail of clues that family can read at a glance.",
                    "選んだカードは手がかりとして並び、家族はひと目で理解できます。",
                    "고른 카드는 단서처럼 남아 가족이 한눈에 알아볼 수 있습니다."),
                    onStage = {
                        it.aacSentence(listOf("f_water_cup")); delay(900)
                        it.aacSentence(listOf("f_water_cup", "n_rest")); delay(900)
                        it.aacSentence(listOf("f_water_cup", "n_rest", "md_medicine"))
                    }, holdMs = 800, stageMs = 1800L),
                // 鼻控／停留／掃描在「進階設定 › 無障礙與提醒」：鼻控靈敏度、高對比、超大字都在那一張卡。
                // 演示不真的打開前鏡頭（要權限、會拍到人），只帶到真正的設定位置講解。
                Step(c("手不方便時，前鏡頭追蹤鼻尖來移動游標，停留約一秒就自動點擊——鼻控加上停留點選。",
                    "If the hands can't help, the front camera tracks the tip of the nose to move a cursor, and resting on a button for a second clicks it — nose control with dwell selection.",
                    "手が使えなくても、前面カメラが鼻先を追ってカーソルを動かし、ボタンの上で約1秒止まると自動でクリックします。鼻操作と停留クリックです。",
                    "손을 쓰기 어려우면 전면 카메라가 코끝을 따라 커서를 움직이고, 버튼 위에 1초쯤 머물면 자동으로 누릅니다. 코 조작과 머무름 선택입니다."),
                    route = "settings_extended", onStage = { it.clear(); it.severity("Mild") }),
                Step(c("連指標都無法移動的人，可以用掃描模式：畫面逐一高亮，按一下大開關就選取。",
                    "For those who can't move a pointer at all, switch scanning highlights each item in turn; one press of a big switch selects it.",
                    "ポインタも動かせない方には、スキャン操作。画面が順に強調表示され、大きなスイッチを一度押せば選べます。",
                    "포인터조차 움직이기 어려운 분은 스캔 모드를 씁니다. 화면이 하나씩 강조되고, 큰 스위치를 한 번 누르면 선택됩니다."),
                    holdMs = 900),
                Step(c("開關也可以是刻意的長眨眼，自然眨眼不會誤觸。",
                    "The switch can even be a deliberate long blink — natural blinking won't trigger it.",
                    "スイッチは意図的な長いまばたきでも構いません。自然なまばたきでは反応しません。",
                    "스위치는 의도적인 긴 눈 깜빡임이어도 됩니다. 자연스러운 깜빡임에는 반응하지 않습니다."),
                    holdMs = 600),
                Step(c("再加上高對比與超大字，視力退化的長輩也看得清楚。",
                    "Add high contrast and extra-large type, and even low vision is no barrier.",
                    "さらに高コントラストと特大文字で、視力が落ちた方にも見やすくします。",
                    "여기에 고대비와 큰 글씨를 더해 시력이 약한 분도 잘 볼 수 있습니다."),
                    onStage = { delay(300); it.highContrast(true); delay(1200); it.fontScale(1.6f) },
                    holdMs = 900, stageMs = 1500L),
            )),
            Unit("rehab", "🏥", c("③ AI 語音復健・復健軌跡", "③ AI speech rehab & progress tracking", "③ AI音声リハビリと推移グラフ", "③ AI 언어 재활과 재활 추이"), steps = listOf(
                // 復健頁的題庫就是四級（PracticeLevel），題目跟著介面語言換成英日韓。
                Step(c("模組三，AI 語音復健。內建中、英、日、韓四種語言的四級題庫，從名詞、動作到情境句，由淺入深。",
                    "Module three: AI speech rehabilitation. A four-level exercise bank in Chinese, English, Japanese and Korean, from single nouns up to situational sentences.",
                    "モジュール3、AI音声リハビリ。中国語・英語・日本語・韓国語の4言語・4段階の問題集を内蔵し、名詞から場面の文まで段階的に練習できます。",
                    "모듈 3, AI 언어 재활. 중국어·영어·일본어·한국어 4개 언어, 4단계 문제은행이 들어 있어 명사부터 상황 문장까지 단계적으로 연습합니다."),
                    route = "rehab", onStage = { it.restoreDisplay(); it.clear() }),
                Step(c("切換語言，題目和語音辨識也跟著換，沒有網路也能練。",
                    "Switch the language and both the items and speech recognition follow — and it all works offline.",
                    "言語を切り替えると、問題も音声認識も切り替わります。オフラインでも練習できます。",
                    "언어를 바꾸면 문항과 음성 인식도 함께 바뀌고, 인터넷 없이도 연습할 수 있습니다."),
                    holdMs = 800),
                Step(c("患者先聽示範，再跟著唸一次。", "The patient listens to the model sentence, then repeats it.",
                    "お手本を聞いてから、まねして言います。", "환자는 시범 문장을 듣고 따라 말합니다."),
                    onStage = { it.rehabTarget(s.rehab); delay(500); speakAndWait(it, s.rehab) }, stageMs = 3_000L),
                // 分數是固定樣本（演示不錄音、不呼叫評分 AI、不寫復健日誌），
                // 但顯示用的是真的評分卡：總分、流暢度／語調分項、錯誤內容。
                Step(c("AI 即時回饋語意、流暢度與語調三項分數，標出說錯的字，並記下開口前停頓了幾秒。",
                    "AI feedback arrives instantly: scores for meaning, fluency and intonation, the mispronounced word highlighted, and how long he paused before speaking.",
                    "AIがすぐにフィードバック。意味・流暢さ・抑揚の3項目を採点し、言い間違えた語を示し、話し始めるまでの時間も記録します。",
                    "AI가 바로 피드백합니다. 의미·유창성·억양 세 항목 점수와 잘못 말한 단어, 말하기 전 멈춘 시간까지 기록합니다."),
                    onStage = { delay(400); it.rehabResult(82, s.rehabFb, 78, 74, s.heard, s.wrong) }, holdMs = 1200),
                Step(c("跟讀的錄音評分完立即刪除，只留下文字與分數。",
                    "The recording is discarded right after scoring; only text and scores are kept.",
                    "音声は採点後すぐに破棄され、残るのは文字と点数だけです。",
                    "녹음은 채점 직후 바로 삭제되고, 글자와 점수만 남습니다.")),
                // 成績單頁的「復健軌跡趨勢圖」畫的是這台手機真的資料；沒用過的話就是「尚無資料」——不造假。
                Step(c("每一次的反應時間與命中率，會自動轉成復健軌跡趨勢圖。",
                    "Every response time and hit rate is turned automatically into a rehabilitation trajectory chart.",
                    "毎回の反応時間と正答率は、自動でリハビリ推移グラフになります。",
                    "매번의 반응 시간과 적중률은 자동으로 재활 추이 그래프가 됩니다."),
                    route = "report", holdMs = 800),
                Step(c("語言治療師看得出進步的趨勢，也能匯出 CSV、PDF，或推送給家人。",
                    "Speech therapists can follow the trend, export it as CSV or PDF, or send it to the family.",
                    "言語聴覚士は改善の傾向を確認でき、CSVやPDFへの書き出し、家族への送信もできます。",
                    "언어치료사는 변화 추세를 확인하고 CSV·PDF로 내보내거나 가족에게 보낼 수 있습니다.")),
            )),
            Unit("crisis", "🆘", c("④ 語意防護・危機介入", "④ Semantic safeguard & crisis intervention", "④ 意味ガードと危機介入", "④ 의미 보호와 위기 개입"), steps = listOf(
                Step(c("模組四，語意防護。每一句話在說出口之前，都會先檢查語意。",
                    "Module four: the semantic safeguard. Every sentence is checked for meaning before it is spoken.",
                    "モジュール4、意味ガード。すべての文は、声にする前に意味をチェックします。",
                    "모듈 4, 의미 보호. 모든 문장은 말하기 전에 의미를 먼저 검사합니다."),
                    route = "main", focus = "input",
                    onStage = { it.clear(); typeSlowly(it, s.crisis) }, stageMs = s.crisis.length * 70L),
                // 真的「發聲已鎖定」與真的危機視窗，但**只改畫面狀態**：
                // 不呼叫 checkAndSpeak、不碰 TelegramClient、不開鏡頭、不撥號、不建視訊房。
                Step(c("偵測到求救或自傷的語意時，不會唸出來，而是立刻啟動危機介入。",
                    "When it detects a cry for help or self-harm, it does not speak — it starts crisis intervention instead.",
                    "助けを求める言葉や自傷の意図を検知すると、読み上げずに危機介入を始めます。",
                    "도움 요청이나 자해 의도를 감지하면 읽지 않고 즉시 위기 개입을 시작합니다."),
                    focus = "result", onStage = {
                        it.press("reconstruct"); delay(320); it.press(null); delay(200)
                        it.safetyLock(s.lockMsg)
                    }, holdMs = 600, stageMs = 520L),
                Step(c("系統自動把 GPS 定位傳給指定的家人、開啟視訊連結，畫面換成跟著圓圈呼吸的引導。",
                    "It automatically sends the GPS location to the designated family member, opens a video link, and switches to a calming breathing guide.",
                    "指定した家族にGPSの位置を自動で送り、ビデオ通話のリンクを開き、画面は呼吸ガイドに切り替わります。",
                    "지정된 가족에게 GPS 위치를 자동으로 보내고 영상 통화 링크를 열며, 화면은 호흡 안내로 바뀝니다."),
                    onStage = {
                        delay(400); it.safetyLock(null); delay(300)
                        it.crisisOpen(s.sim)
                        delay(700); it.crisisSay("sys", s.crisisLog[0])
                        delay(900); it.crisisSay("sys", s.crisisLog[1])
                        delay(1100); it.crisisSay("family", s.crisisLog[2])
                    }, holdMs = 1400, stageMs = 3_400L),
                // 危機視窗下方就是依所在國家的求救專線（台灣是 119 與 1925），使用者自己關不掉。
                Step(c("下方可以一鍵直撥 119 和 1925 安心專線；這個畫面要家人確認後才會關閉。",
                    "Below, one tap dials emergency services or a crisis line — 119 and 1925 in Taiwan. The screen closes only after the family responds.",
                    "下のボタンで救急や相談窓口にワンタップで電話できます（台湾では119と1925）。この画面は家族が確認するまで閉じません。",
                    "아래 버튼으로 응급 번호와 위기 상담 전화에 바로 걸 수 있습니다(대만은 119와 1925). 이 화면은 가족이 확인해야 닫힙니다."),
                    onStage = { delay(800); it.crisisSay("me", s.crisisQuick[0]) }, holdMs = 1000),
                Step(c("平常在任何畫面都按得到求救鈕，還有一鍵發聲的快速求救。演示中的通報都已攔截，不會真的送出。",
                    "A help button is always within reach, along with one-tap emergency phrases. In this demo every alert is intercepted — nothing is actually sent.",
                    "ふだんもSOSボタンはどの画面からでも押せ、ワンタップで話せる緊急フレーズもあります。このデモでは通報はすべて止めてあり、実際には送信されません。",
                    "평소에도 어느 화면에서든 SOS 버튼을 누를 수 있고, 한 번에 말하는 긴급 문구도 있습니다. 이 데모의 알림은 모두 차단되어 실제로 보내지지 않습니다."),
                    focus = "sos", onStage = {
                        it.crisisClose(); it.clear()
                        delay(900); it.press("sos_0"); delay(400); it.press(null)
                    }, holdMs = 600, stageMs = 1300L),
            )),
            Unit("voice", "🗣", c("⑤ 擬真聲紋・情感語音", "⑤ Personal voice & emotional speech", "⑤ 本人の声と感情音声", "⑤ 실제 같은 목소리와 감정 음성"), steps = listOf(
                // 只切到 "settings" 是一頁設定清單；要真的打開「角色語音與雲端曲庫」子面板。
                Step(c("模組五，擬真聲紋。失去語言的同時，很多人也失去了自己的聲音。",
                    "Module five: a voice that sounds like you. Losing speech often means losing one's own voice, too.",
                    "モジュール5、本人そっくりの声。言葉を失うと、自分の声も失ってしまうことが多いのです。",
                    "모듈 5, 실제 같은 목소리. 말을 잃으면 자기 목소리도 함께 잃는 경우가 많습니다."),
                    route = "settings", onStage = { it.openPanel(null); delay(500); it.openPanel("voice") }, stageMs = 500L),
                Step(c("拾語者用 GPT-SoVITS，從病發前的錄音或家人的聲音，複製出專屬聲音模型，存在使用者自己的 Google Drive。",
                    "Speak My Life uses GPT-SoVITS to clone a personal voice from recordings made before the stroke, or from a family member, and keeps it in the user's own Google Drive.",
                    "GPT-SoVITSで、発症前の録音や家族の声から専用の音声モデルを作り、ご本人のGoogleドライブに保存します。",
                    "GPT-SoVITS로 발병 전 녹음이나 가족의 목소리에서 전용 음성 모델을 만들어 사용자 본인의 Google 드라이브에 저장합니다."),
                    holdMs = 600),
                // 面板上方就是聲紋同意狀態列（已同意／尚未同意＋撤回同意）。演示不改同意狀態。
                Step(c("但聲紋一定要先經過本人或法定代理人同意；隨時可以撤回，撤回後立刻停用。",
                    "But a voiceprint is used only with consent from its owner or a legal guardian — and consent can be withdrawn at any time, taking effect immediately.",
                    "ただし声紋は、本人または法定代理人の同意があって初めて使えます。同意はいつでも撤回でき、撤回すればすぐに停止します。",
                    "단, 성문은 본인이나 법정대리인의 동의가 있어야만 쓸 수 있고, 언제든 철회할 수 있으며 철회하면 즉시 사용이 중지됩니다."),
                    holdMs = 800),
                // 用**專屬聲音**（另一個角色的 GPT-SoVITS 預錄檔）唸一句；情緒由 TtsModule
                // 依句子內容自動挑參考音檔（LocalSovitsHelper.detectEmotion）。
                Step(c("說出口時，還會依句子的情緒，切換喜、怒、哀、樂的語氣。",
                    "And when it speaks, the tone follows the feeling of each sentence — happy, angry, sad or cheerful.",
                    "話すときは、文の感情に合わせて喜怒哀楽の口調を切り替えます。",
                    "말할 때는 문장의 감정에 맞춰 기쁨, 분노, 슬픔, 즐거움의 어조를 바꿉니다."),
                    onStage = { delay(400); speakInOwnVoice(it, s.own); it.openPanel(null) },
                    holdMs = 600, stageMs = VOICE_SYNTH_DELAY_MS + 3000L, voiceSample = s.own),
            )),
            Unit("outro", "🏁", c("收尾", "Closing", "エンディング", "마무리"), steps = listOf(
                Step(c("拾語者：從發聲走向語意重建；AI 協助，但由患者決定；介面隨能力調整；",
                    "Speak My Life goes beyond pronunciation to rebuilding meaning. AI assists, but the patient decides. The interface adapts to each ability.",
                    "発声練習から意味の再構築へ。AIは手助けし、決めるのはご本人。画面は能力に合わせて変わります。",
                    "발성을 넘어 의미를 다시 세우고, AI는 돕되 결정은 환자가 하며, 화면은 능력에 맞춰 바뀝니다."),
                    onStage = { it.clear() },
                    card = TitleCard(BRAND, c("從發聲走向語意重建 · AI 協助，由患者決定 · 介面隨能力調整",
                        "Beyond pronunciation, to meaning · AI assists, the patient decides · Adapts to each ability",
                        "発声から意味の再構築へ · AIが手助けし、本人が決める · 能力に合わせて変わる画面",
                        "발성을 넘어 의미로 · AI는 돕고 결정은 환자가 · 능력에 맞춰 바뀌는 화면"))),
                Step(c("跨裝置持續復健；復健歷程可追蹤；兼顧安全與隱私——聲紋需經同意、錄音評分後刪除、危機訊息只傳給指定家人。",
                    "Rehab continues across devices. Progress is tracked over time. And safety and privacy come first: voiceprints need consent, recordings are deleted after scoring, and crisis alerts go only to chosen family.",
                    "端末をまたいでリハビリを継続し、経過を記録し、安全とプライバシーを守ります。声紋は同意制、録音は採点後に削除、危機の通知は指定した家族だけに届きます。",
                    "기기를 넘나들며 재활을 이어 가고, 과정을 기록하며, 안전과 개인정보를 지킵니다. 성문은 동의가 필요하고, 녹음은 채점 후 삭제되며, 위기 알림은 지정한 가족에게만 갑니다."),
                    card = TitleCard(BRAND, c("跨裝置持續復健 · 復健歷程可追蹤 · 兼顧安全與隱私",
                        "Rehab across devices · Progress you can track · Safety and privacy built in",
                        "端末をまたいで続くリハビリ · 経過を追える · 安全とプライバシー",
                        "기기를 넘나드는 재활 · 추적 가능한 과정 · 안전과 개인정보 보호"))),
                Step(c("讓失語症患者即使無法說話，也能被世界聽見。拾語者 Speak My Life。",
                    "So that people with aphasia, even when they cannot speak, can still be heard by the world. Speak My Life.",
                    "失語症の方が、たとえ話せなくても、世界に声を届けられるように。拾語者 Speak My Life。",
                    "실어증 환자가 말할 수 없어도 세상에 들릴 수 있도록. Speak My Life."),
                    holdMs = 1200,
                    card = TitleCard(BRAND, c("讓失語症患者即使無法說話，也能被世界聽見",
                        "Even without speech, heard by the world",
                        "話せなくても、世界に声が届くように",
                        "말할 수 없어도 세상에 들리도록"))),
            )),
        )
    }

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()

    /** 片頭／片尾卡；非 null 時蓋住整個畫面。 */
    private val _titleCard = MutableStateFlow<TitleCard?>(null)
    val titleCard: StateFlow<TitleCard?> = _titleCard.asStateFlow()

    /** 旁白預先合成的進度；null＝沒有在準備。 */
    private val _prep = MutableStateFlow<Pair<Int, Int>?>(null)
    val prep: StateFlow<Pair<Int, Int>?> = _prep.asStateFlow()

    private var job: Job? = null

    // 「同一時間只准一個聲音」。畫面動作與旁白是並行跑的（畫面才不會等旁白講完
    // 才動），但**聲音不能並行**——App 的示範發聲會直接疊在旁白上，變成兩個人同時講話。
    // 這把鎖就是發言權：旁白拿著的時候示範發聲要排隊，反之亦然。
    // 不用「打斷旁白」的做法，那會把句子切一半；排隊只是晚幾秒，聽起來自然得多。
    private val audioFloor = kotlinx.coroutines.sync.Mutex()

    /** 已預錄好的旁白：句子 → 音檔。跨次執行保留，第二次跑就不用再合成。 */
    private val clips = mutableMapOf<String, java.io.File>()

    /** 上一次預錄用的是哪個角色。換角色就要整批重錄，否則會播到上一個人的聲音。 */
    private var clipsVoice: String = ""

    /** 快取檔可能被 TtsModule 的 LRU 清掉 → 播之前再確認一次檔案還在。 */
    private fun usableClip(line: String): java.io.File? =
        clips[line]?.takeIf { it.exists() && it.length() > 0 }
            ?: run { clips.remove(line); null }

    /**
     * 預錄一句，失敗就重試。
     *
     * 為什麼要重試：走 Wi-Fi／Tailscale／ngrok(Colab) 時，掉一句的原因幾乎都是
     * 暫時的（隧道剛冷啟動、手機剛從休眠醒來、DERP 中繼換路徑），不是「這條線
     * 不能用」。原本沒有重試，於是六十句裡掉一句就整支中止——而且是在使用者
     * 等完全部六十句之後。USB 幾乎不會遇到，所以看起來像「只有插線才能錄」。
     *
     * 退避是必要的：剛掉包的下一秒通常還是掉包，立刻重打只是把三次機會一次用完。
     *
     * @return 錄好的檔案；重試用盡仍失敗則 null（由呼叫端決定要不要中止）。
     */
    internal suspend fun prerecordWithRetry(
        line: String,
        record: (String, (java.io.File?) -> kotlin.Unit) -> kotlin.Unit,
        /** 還在跑嗎。使用者按停止就不要再重試。抽成參數是為了讓測試能驅動它。 */
        stillRunning: () -> Boolean = { _active.value },
    ): java.io.File? {
        repeat(PRERECORD_ATTEMPTS) { attempt ->
            if (!stillRunning()) return null          // 使用者已經按了停止
            val f = suspendCancellableCoroutine<java.io.File?> { cont ->
                var resumed = false
                record(line) { file ->
                    if (!resumed) { resumed = true; if (cont.isActive) cont.resume(file) }
                }
            }
            if (f != null && f.exists() && f.length() > 0) return f
            if (attempt < PRERECORD_ATTEMPTS - 1) {
                // 線性退避就夠了：合成本身要 10～30 秒，等待成本相對很小。
                kotlinx.coroutines.delay(PRERECORD_BACKOFF_MS * (attempt + 1))
            }
        }
        return null
    }
    private var player: android.media.MediaPlayer? = null

    /** 讓 App 唸一句並等它唸完。不等的話畫面會跳到下一段，聲音才姍姍來遲蓋在別段上。
     *
     *  上限 20 秒：系統 TTS 的 onDone 不是保證會來的（引擎被系統回收、語音包正在
     *  更新、utterance 被丟掉都不會回呼）。沒有上限的話演示會永遠卡在這一步，
     *  而錄影還開著——使用者拿到的是一支停在某個畫面錄了十分鐘的影片。 */
    private suspend fun speakAndWait(stage: Stage, text: String) {
        audioFloor.withLock {
            kotlinx.coroutines.withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
                suspendCancellableCoroutine<kotlin.Unit> { cont ->
                    var resumed = false
                    val done = {
                        if (!resumed) { resumed = true; if (cont.isActive) cont.resume(kotlin.Unit) }
                    }
                    stage.speakAloud(text, done)
                }
            }
        }
    }

    /**
     * 演示裡「App 把句子唸出來」那一下：亮出合成指示 → 停 2 秒 → 用系統 TTS 唸。
     *
     * **和旁白完全獨立。** 走的是 App 自己的發聲路徑，不碰旁白的預錄快取——
     * 借用那份快取的話，這句話會用旁白角色的聲音唸出來，聽起來就變成旁白
     * 在唸它，而不是 App 在講話。
     *
     * 那 2 秒是給「合成需要時間」用的。真的按下去要等電腦跑 10～30 秒，
     * 瞬間出聲會讓觀眾以為這個 App 是即時的；停的長度縮短了，但「要等」
     * 這件事本身不是演的。
     */
    /**
     * 用**專屬聲音**（GPT-SoVITS 預錄檔）唸一句。給「我的專屬聲音」那一段用。
     *
     * 那一段整段都在講這個功能，用系統機器音唸等於在示範它不存在。
     * 角色刻意不是旁白那個，觀眾才聽得出這是「他自己的聲音」而不是旁白又講了一句。
     * 合不出來才退回系統 TTS——寧可音色不對，也不能整段沒有聲音。
     */
    private suspend fun speakInOwnVoice(stage: Stage, text: String) {
        stage.synthesizing(true)
        try { delay(VOICE_SYNTH_DELAY_MS) } finally { stage.synthesizing(false) }
        val clip = voiceClips[text]?.takeIf { it.exists() && it.length() > 0 }
        if (clip != null) playClip(clip) else speakAndWait(stage, text)
    }

    /** 專屬聲音的預錄檔（和旁白那份分開：不同角色，不能互相覆蓋）。 */
    private val voiceClips = mutableMapOf<String, java.io.File>()

    private suspend fun speakDemoSentence(stage: Stage, text: String) {
        stage.synthesizing(true)
        try { delay(VOICE_SYNTH_DELAY_MS) } finally { stage.synthesizing(false) }
        speakAndWait(stage, text)
    }

    /** 逐字打進輸入框。一次貼上看起來像作弊，逐字打才看得懂使用者在做什麼。 */
    private suspend fun typeSlowly(stage: Stage, text: String) {
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            stage.type(sb.toString())
            delay(70)
        }
    }

    /** 播一段預錄好的旁白，等它真的播完。
     *
     *  用 prepareAsync 而不是 prepare()：這裡跑在主執行緒上，而 prepare() 會同步
     *  讀檔＋解碼標頭。錄影中主執行緒被擋住 = 那幾十毫秒的畫面直接掉幀。 */
    private suspend fun playClip(file: java.io.File) = audioFloor.withLock {
      kotlinx.coroutines.withTimeoutOrNull(CLIP_TIMEOUT_MS) {
      suspendCancellableCoroutine<kotlin.Unit> { cont ->
        var resumed = false
        val finish = {
            if (!resumed) { resumed = true; if (cont.isActive) cont.resume(kotlin.Unit) }
        }
        runCatching {
            player?.release()
            player = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { finish() }
                setOnErrorListener { _, _, _ -> finish(); true }
                setOnPreparedListener { runCatching { it.start() }.onFailure { finish() } }
                prepareAsync()
            }
        }.onFailure { finish() }     // 播不出來也要放行，不然整段演示卡在這裡
        cont.invokeOnCancellation { runCatching { player?.release() }; player = null }
      }
      }
    }

    fun stop() {
        job?.cancel()
        job = null
        reset()
    }

    private fun reset() {
        runCatching { player?.release() }; player = null
        _active.value = false
        _caption.value = ""
        _progress.value = ""
        _titleCard.value = null
        _prep.value = null
    }

    /**
     * 跑一次演示。
     *
     * @param ids       勾選的單元 id
     * @param narrate   true＝旁白＋字幕（演示開始時的介面語言）；false＝只演示、不出聲也不出字幕
     * @param navigate  切頁面（由 MainActivity 提供，必須在主執行緒呼叫）
     * @param onReady   旁白備妥、可以開始錄影了（錄影在這之後才開始，影片裡才沒有等待）
     * @param onFinish  演示結束（不論正常結束或中斷）
     */
    fun start(
        scope: CoroutineScope,
        ids: Set<String>,
        narrate: Boolean,
        /** 這次旁白用的角色名。換角色時整批快取要作廢。 */
        voiceTag: String,
        navigate: (String) -> kotlin.Unit,
        /** 對畫面動手的入口（由 MainActivity 用 ViewModel 實作）。 */
        stage: Stage,
        /** 預錄一句旁白成音檔（錄影開始前做）。合不出來回 null。 */
        prerecord: (String, (java.io.File?) -> kotlin.Unit) -> kotlin.Unit,
        /** 預錄「專屬聲音」示範句——用的是**另一個角色**，不是旁白那個。 */
        prerecordVoice: (String, (java.io.File?) -> kotlin.Unit) -> kotlin.Unit = { _, d -> d(null) },
        /** 沒有預錄檔時的退路：照一般流程唸（會是系統語音）。 */
        speakFallback: (String, () -> kotlin.Unit) -> kotlin.Unit,
        onReady: suspend () -> kotlin.Unit = {},
        onFinish: () -> kotlin.Unit = {},
        /** 有旁白錄不起來（連不到電腦端語音）→ 中止並回報，缺幾句 / 共幾句。 */
        onNarrationMissing: (Int, Int) -> kotlin.Unit = { _, _ -> },
    ) {
        if (_active.value) return
        // 語言在這一刻定下：之後即使介面語言被改，整支影片的旁白也不會中途換語言
        val units = units(demoLang()).filter { it.id in ids }
        if (units.isEmpty()) return

        stage.snapshotSettings()   // 演示會動字級／語言／嚴重度，結束一定還原
        _active.value = true
        job = scope.launch {
            try {
                if (narrate) {
                    // 先把每一句旁白**錄成檔案**。演示時直接播那個檔，不再走一次 TTS——
                    // 那條路每次都會重新判語言、可能切模型，也可能在任何一步掉回系統機器音。
                    // 錄好的檔案留著，第二次跑完全不用合成。
                    // 換過角色就把整批快取作廢——不然舊句子會用上一個角色的聲音播，
                    // 同一支影片裡兩種音色交錯出現。
                    if (voiceTag != clipsVoice) { clips.clear(); voiceClips.clear(); clipsVoice = voiceTag }
                    // **只錄旁白。** 演示裡「App 自己唸出來」那幾句不在這裡——
                    // 它們走的是 App 自己的發聲路徑（系統 TTS），和旁白是兩回事。
                    // 混進來的話會被用旁白角色的聲音合成，聽起來就像旁白在唸那句話，
                    // 而且缺一句還會擋住整個錄影。
                    val lines = units.flatMap { u -> u.steps.map { it.cap } }
                        .filter { it.isNotBlank() }.distinct()
                    val todo = lines.filter { usableClip(it) == null }
                    _prep.value = 0 to todo.size
                    todo.forEachIndexed { i, line ->
                        _prep.value = i to todo.size
                        prerecordWithRetry(line, prerecord)?.let { clips[line] = it }
                    }
                    // 補漏輪：跑完整批之後，把還缺的再收一次。
                    // 隧道剛拉起來的那幾句最容易掉，等整批跑完連線通常已經穩了——
                    // 這一輪常常就把它們補回來，省掉使用者重跑六十句的時間。
                    val stillMissing = todo.filter { clips[it]?.exists() != true }
                    if (stillMissing.isNotEmpty()) {
                        _prep.value = 0 to stillMissing.size
                        stillMissing.forEachIndexed { i, line ->
                            _prep.value = i to stillMissing.size
                            prerecordWithRetry(line, prerecord)?.let { clips[line] = it }
                        }
                    }
                    // 專屬聲音的示範句另外錄一輪（不同角色，所以不能混在同一批）。
                    // 錄不起來不中止——退回系統 TTS 仍然演得下去，只是音色不對；
                    // 而旁白少一句是真的不能開錄（整支影片的解說會斷掉）。
                    val samples = units.flatMap { u -> u.steps.mapNotNull { it.voiceSample } }
                        .distinct().filter { voiceClips[it]?.exists() != true }
                    samples.forEachIndexed { i, line ->
                        _prep.value = (todo.size + i) to (todo.size + samples.size)
                        prerecordWithRetry(line, prerecordVoice)?.let { voiceClips[line] = it }
                    }
                    _prep.value = null

                    // 少一句都不開錄。使用者要的是自己的聲音；用系統機器音頂替，
                    // 只會在錄完整支影片之後才被發現。
                    val missing = lines.count { usableClip(it) == null }
                    if (missing > 0) {
                        onNarrationMissing(missing, lines.size)
                        return@launch
                    }
                }

                onReady()

                val total = units.sumOf { it.steps.size }
                var done = 0
                for (u in units) {
                    // 標題卡只活在宣告它的單元裡：換單元一定先收掉，免得蓋住下一段的畫面
                    _titleCard.value = null
                    for (s in u.steps) {
                        done++
                        _progress.value = "${u.icon} ${u.title} · $done/$total"
                        if (s.hideCard) _titleCard.value = null
                        s.card?.let { _titleCard.value = it }
                        s.route?.let { navigate(it); delay(NAV_SETTLE_MS) }
                        // ── 錄影池 ──
                        // 旁白開始講之前，先把這一步要講的地方露出來並確認看得見。
                        // 放在旁白之前而不是和它並行：話一出口畫面就該已經對了。
                        s.focus?.let { f ->
                            stage.focusOn(f)
                            delay(FOCUS_SETTLE_MS)
                            if (!stage.focusWasVisible(f)) {
                                android.util.Log.w(
                                    "DemoController",
                                    "錄影池：這一步要講「$f」，但畫面沒有確認它看得見" +
                                        "（不在畫面上，或這個頁面根本沒在聽捲動訊號）— 「${s.cap.take(48)}」"
                                )
                            }
                        }
                        // 畫面動作與旁白同時跑：旁白在講的時候畫面已經在動，
                        // 而不是講完才動（那樣看起來像投影片配旁白）。
                        val staging = s.onStage?.let { act -> launch { runCatching { act(stage) } } }
                        _caption.value = if (narrate) s.cap else ""
                        val t0 = System.currentTimeMillis()
                        if (narrate && s.cap.isNotBlank()) {
                            val clip = usableClip(s.cap)
                            if (clip != null) playClip(clip)
                            // 退路也要拿發言權，否則它會和示範發聲疊在一起
                            else audioFloor.withLock {
                                kotlinx.coroutines.withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
                                    awaitCallback { done -> speakFallback(s.cap, done) }
                                }
                            }
                            delay(TAIL_PAD_MS)      // 講完再讓畫面多停一下
                        }
                        // 畫面動作也要有上限，理由同上：卡住的是整支影片，不只這一步。
                        staging?.let {
                            if (kotlinx.coroutines.withTimeoutOrNull(STAGE_TIMEOUT_MS) { it.join() } == null) {
                                it.cancel()
                            }
                        }
                        if (s.holdMs > 0) delay(s.holdMs)
                        // 補到最短停留；旁白比下限長時這裡是 0，不會拖慢。
                        val floor = if (narrate) MIN_STEP_NARRATED_MS else MIN_STEP_SILENT_MS
                        val left = floor - (System.currentTimeMillis() - t0)
                        if (left > 0) delay(left)
                    }
                }
            } finally {
                runCatching { stage.clear() }   // 演示放上去的碎詞／候選句不留給使用者
                // 字級／語言／嚴重度是寫進 DataStore 的，中途按返回鍵中止也要還原——
                // 不然使用者會停在 2.2 倍字級的韓文介面，而且不知道怎麼弄回來。
                runCatching { stage.restoreSettings() }
                reset()
                onFinish()
            }
        }
    }

    /**
     * 把「傳一個 callback 進去、完成時被叫」的舊式 API 包成 suspend。
     * 回呼可能被呼叫超過一次（失敗 fallback 路徑會補叫），所以要自己防重入，
     * 對已經 resume 過的 continuation 再 resume 一次會直接崩潰。
     */
    private suspend fun awaitCallback(block: (() -> kotlin.Unit) -> kotlin.Unit) =
        suspendCancellableCoroutine<kotlin.Unit> { cont ->
            var resumed = false
            block {
                if (!resumed) { resumed = true; if (cont.isActive) cont.resume(kotlin.Unit) }
            }
        }
}
