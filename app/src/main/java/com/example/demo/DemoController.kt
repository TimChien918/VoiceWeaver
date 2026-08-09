package com.example.demo

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
 * ② 旁白在**開始錄影之前**全部合成完。GPT-SoVITS 在電腦 CPU 上一句要 10～30 秒，
 *    邊演邊合成等於把幾十分鐘的空白錄進影片裡。
 * ③ 字幕與旁白一律英文——這支影片是要拿去做報告的。
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

    private const val SPEAK_TIMEOUT_MS = 20_000L
    private const val CLIP_TIMEOUT_MS = 60_000L
    private const val STAGE_TIMEOUT_MS = 30_000L

    /** 給 UI 估時用：一步大概多久（英文約每秒 2.6 個字）。 */
    fun stepMillis(cap: String, narrated: Boolean): Long {
        if (!narrated) return MIN_STEP_SILENT_MS
        val words = cap.split(Regex("\\s+")).count { it.isNotBlank() }
        return maxOf(MIN_STEP_NARRATED_MS, (words / 2.6 * 1000).toLong()) + TAIL_PAD_MS
    }

    /** 勾選的單元總長（秒）。
     *
     *  含 stageMs：畫面動作裡自己 delay 的時間（例如危機那段在對話之間刻意停頓）。
     *  不算進去的話，有大量畫面動作的單元會被嚴重低估——使用者是靠這個數字
     *  決定要不要按下錄影的。 */
    fun estimateSeconds(ids: Set<String>, narrated: Boolean): Int =
        (UNITS.filter { it.id in ids }
            .flatMap { it.steps }
            .sumOf { maxOf(stepMillis(it.cap, narrated), it.stageMs) + it.holdMs } / 1000).toInt()

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
        /** 無障礙：字級倍率。 */
        fun fontScale(scale: Float)
        /** 無障礙：介面語言（zh / en / ja / ko）。 */
        fun language(tag: String)
        /** 嚴重度模式（Mild / Moderate / Severe）——重度會把圖卡換成全螢幕大卡。 */
        fun severity(name: String)
        /** 主畫面捲到結果那一段（候選句在下面，不捲就看不到）。 */
        fun scrollToResult()
        /** 主畫面捲回最上面（緊急片語那張卡在第一個）。 */
        fun scrollToTop()
        /** 切成「正在重組」的等待畫面（真的那顆 Loading 元件）。 */
        fun reconstructing()
        /** 視覺按壓某顆按鈕（tag；null＝放開）。不會真的觸發它的動作。 */
        fun press(tag: String?)
        /** 讓 App 把這句話唸出來（系統 TTS，即時），唸完呼叫 done。 */
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
    )

    /** 示範用的碎詞與候選句（固定樣本，不呼叫任何 AI）。 */
    private val SAMPLE_FRAGMENTS = "water  rest  now"
    private val SAMPLE_CANDIDATES = listOf(
        "I would like some water, and I need to rest now.",
        "Can I have water? I want to lie down.",
        "Water please. I am tired.",
    )

    data class Unit(
        val id: String,
        val icon: String,
        val title: String,
        /** 純演示（無旁白）時的估計秒數。 */
        val estSec: Int,
        val note: String? = null,
        val steps: List<Step>,
    )

    /** 片頭／片尾卡的內容；null＝沒有卡片。 */
    data class TitleCard(val main: String, val sub: String)

    val UNITS: List<Unit> = listOf(
        // 順序＝一條敘事線，不是功能清單：
        //   先讓人理解失語症 → 把碎詞變成句子 → 用他自己的聲音講出來
        //   → 那如果出事了呢？求救 → 危機介入與家人對話
        //   → 回到日常的輸入方式（專屬發音、圖卡、自訂卡、重度模式）
        //   → 它會愈用愈像這個人 → 無障礙與四語言
        //   → 長期的復健與成績單 → 跨裝置同步 → 收尾。
        // 語音合成的下一段刻意接求救：剛讓觀眾聽到「他的聲音」，
        // 緊接著問「那講不出話又出事的時候呢」，情緒是連著的。
        Unit("intro", "🎬", "Title & overview", 12, steps = listOf(
            Step("Imagine knowing exactly what you want to say, and not one word will come out."),
            Step("That is aphasia. The thought is intact. The bridge to speech is broken."),
            Step("VoiceWeaver rebuilds that bridge."),
        )),
        Unit("compose", "💬", "Sentence reconstruction", 24, steps = listOf(
            Step("What comes out is fragments. Water. Rest. Now.", route = "main",
                onStage = { it.clear() }),
            Step("The user types, or says, whatever they can manage.",
                onStage = { typeSlowly(it, SAMPLE_FRAGMENTS) }),
            // 候選句是固定樣本（錄影途中被 LLM 逾時或網路一抖毀掉整支影片是不能
            // 接受的），但**等待不能假**：按下去之後真的要轉，而且轉的是真的那顆
            // Loading 元件、轉真實長度。直接跳出結果會讓觀眾以為這個 App 是即時的。
            Step("One tap turns the fragments into a whole sentence.",
                onStage = {
                    it.scrollToResult(); delay(700)          // 先捲到按鈕看得到的位置
                    it.press("reconstruct"); delay(320)      // 按下去（視覺）
                    it.press(null); delay(200)
                    it.reconstructing()                      // 真的進入等待狀態
                    delay(RECONSTRUCT_WAIT_MS)               // 一次雲端重組實際要這麼久
                    it.candidates(SAMPLE_CANDIDATES); delay(300)
                    it.scrollToResult()                      // 再捲到結果
                }, holdMs = 700, stageMs = RECONSTRUCT_WAIT_MS + 1500L),
            Step("Three versions, never one. A wrong guess put in your mouth is worse than silence.",
                onStage = { delay(300); it.pick(1); delay(1500); it.pick(2); delay(1500); it.pick(0) }, holdMs = 500),
            Step("The person picks the one that is actually theirs.",
                onStage = { it.pick(0) }, holdMs = 700),
            Step("Only then is it said out loud.",
                onStage = { delay(300); speakAndWait(it, SAMPLE_CANDIDATES.first()) }, holdMs = 600),
        )),
        Unit("voice", "🗣", "Personal voice (GPT-SoVITS)", 20, steps = listOf(
            // 只切到 "settings" 是不夠的：那是一頁設定清單，旁白在講聲音模型，
            // 畫面上卻連「聲音」兩個字都看不到。要真的把子面板打開。
            Step("Losing speech often means losing your voice as well.", route = "settings",
                onStage = { it.openPanel(null) }),
            Step("A short recording made before the illness is enough to rebuild it.",
                onStage = { delay(400); it.openPanel("voice") }, stageMs = 400L),
            Step("The model lives in their own Drive, and follows them to phone, web and desktop.",
                holdMs = 600),
            Step("So the sentence comes out sounding like them, with the right feeling.",
                onStage = {
                    delay(400); speakAndWait(it, SAMPLE_CANDIDATES.first())
                    it.openPanel(null)    // 收面板，別把它留給下一個單元
                }, holdMs = 600),
        )),
        Unit("sos", "🚨", "Emergency SOS", 22, steps = listOf(
            Step("Now the other half of the problem: what happens when something goes wrong.",
                route = "main", onStage = { it.clear() }),
            Step("In an emergency, building a sentence word by word is far too slow.", holdMs = 600),
            // 旁白說的是「在最上面」，所以要往上捲。原本呼叫 scrollToResult()
            // 是捲到最底下——講的和演的剛好相反。
            Step("So a row of fixed phrases sits at the top of the home screen, always there.",
                onStage = { it.scrollToTop() }, holdMs = 900),
            Step("One tap, and the phone says it out loud straight away.",
                onStage = { speakAndWait(it, com.example.ui.I18n.tr("我需要幫忙")) }, holdMs = 600),
            Step("The same tap can message the family with the current location.",
                holdMs = 900),
        )),
        Unit("crisis", "🆘", "Crisis intervention", 34, steps = listOf(
            Step("Losing language carries a real risk of depression and self-harm.", route = "main",
                onStage = { it.clear() }),
            Step("So every sentence is screened before it is ever spoken.",
                onStage = { typeSlowly(it, "I do not want to go on") }, holdMs = 500),
            Step("When something is found, the app refuses to say it, and says so plainly.",
                onStage = { it.safetyLock(com.example.ui.I18n.tr("偵測到高風險訊息，正在通報緊急聯絡人…")) }, holdMs = 1200),
            Step("But refusing is not enough. It opens a window and stays with the person.",
                onStage = {
                    delay(600); it.safetyLock(null); delay(300)
                    it.crisisOpen(com.example.ui.I18n.tr("正在通知家人…"))
                }, holdMs = 1200),
            Step("A breathing circle to slow them down, warm colours, and only a few large buttons.",
                holdMs = 1600),
            Step("The family is contacted automatically — no button to find, no words to type.",
                onStage = {
                    it.crisisStatus(com.example.ui.I18n.tr("已通知家人"))
                    it.crisisSay("sys", com.example.ui.I18n.tr("已通知家人，請稍等。你並不孤單，我們一起慢慢呼吸。"))
                }, holdMs = 1400),
            Step("The person can send a short message with one tap.",
                onStage = { delay(500); it.crisisSay("me", com.example.ui.I18n.tr("我好害怕")) }, holdMs = 1200),
            Step("And the family answers, inside the same window.",
                onStage = { delay(900); it.crisisSay("family", com.example.ui.I18n.tr("我在路上了，你先坐下來，我十分鐘就到")) }, holdMs = 1600),
            Step("Photos, a voice clip, or a live video call are one tap away if the family asks for them.",
                onStage = { it.crisisStatus(com.example.ui.I18n.tr("已通知家人 · 視訊隨時可開")) }, holdMs = 1400),
            Step("The window cannot be closed by the person alone. Only when they say they are better.",
                onStage = { delay(700); it.crisisSay("me", com.example.ui.I18n.tr("我好一點了")) }, holdMs = 1400),
            Step("Then it closes gently, and the app goes back to being an ordinary app.",
                onStage = { delay(900); it.crisisClose(); it.clear() }, holdMs = 900),
        )),
        Unit("acoustic", "🎤", "Personal pronunciation model", 18, steps = listOf(
            Step("Aphasic speech breaks ordinary speech recognition. Every time.", route = "phrase_shortcuts"),
            Step("So the app learns this person's own way of saying a few key words."),
            Step("It keeps an acoustic template, and matches it with dynamic time warping."),
            Step("It listens to the person in front of it, not to an average speaker.", holdMs = 700),
        )),
        Unit("aac", "🖼", "Picture cards (AAC)", 22, steps = listOf(
            Step("Some people cannot type at all. For them, the board is the way in.", route = "aac",
                onStage = { it.clear(); it.aacCategory(null) }),
            Step("Cards are coloured by part of speech, and grouped by where you are.",
                onStage = { it.aacCategory("food_drink"); delay(1200); it.aacCategory("medical") }, holdMs = 600, stageMs = 1500L),
            Step("Tap by tap, a sentence takes shape, ready to speak or to rewrite.",
                onStage = {
                    it.aacCategory("food_drink")
                    it.aacSentence(listOf("f_water_cup")); delay(900)
                    it.aacSentence(listOf("f_water_cup", "f_warm_water_cup")); delay(900)
                    it.aacSentence(listOf("f_water_cup", "f_warm_water_cup", "f_tea"))
                }),
            Step("Everything scales up, for tired eyes and unsteady hands.",
                onStage = { it.fontScale(1.4f); delay(1400); it.fontScale(1.8f) }, holdMs = 700, stageMs = 1600L),
        )),
        Unit("customcards", "📷", "Custom photo cards", 12, steps = listOf(
            Step("A stock drawing of a cup means nothing to someone's grandmother.", route = "aac",
                onStage = { it.fontScale(1.0f); it.aacCategory("people") }),
            Step("So the family photographs her cup. Her chair. Her grandson."),
            Step("Recognising your own things is where trust begins."),
        )),
        Unit("severe", "🔒", "Severe mode", 16, steps = listOf(
            Step("For the most severely affected, a whole grid is still too much.", route = "aac",
                onStage = { it.clear(); it.severity("Mild") }),
            Step("So the device becomes one card at a time, scanning on its own.",
                onStage = { it.severity("Severe") }, holdMs = 1400, stageMs = 400L),
            Step("A tap speaks it, and leaves a trail the family can read.", holdMs = 1400),
            Step("Getting out needs a caregiver PIN. Nobody gets lost in the app.",
                onStage = { delay(1200); it.severity("Mild") }),
        )),
        Unit("learning", "🧠", "It learns this person", 20, steps = listOf(
            Step("No two people with aphasia are the same, so the app refuses to stay generic.",
                route = "aac", onStage = { it.clear(); it.aacCategory(null) }),
            Step("The cards you reach for most rise to the top, weighted by time of day and by place.",
                onStage = { it.aacCategory("food_drink"); delay(1200); it.aacCategory(null) }, holdMs = 800),
            Step("Which candidate you accept teaches it which phrasing sounds like you.", holdMs = 800),
            Step("Your pronunciation model, your cards, your voice — it drifts towards you over time.",
                holdMs = 800),
        )),
        Unit("a11y", "♿", "Accessibility & languages", 14, steps = listOf(
            Step("Many users are elderly, and stroke often takes part of the visual field with it.", route = "settings",
                onStage = { it.fontScale(1.0f) }),
            Step("So the text scales all the way up, across every screen, settings included.",
                onStage = { it.fontScale(1.4f); delay(1500); it.fontScale(1.8f); delay(1500); it.fontScale(2.2f) },
                holdMs = 900),
            Step("Nothing is cut off and nothing overlaps, even at the largest size.",
                onStage = { delay(1200); it.fontScale(1.0f) }, holdMs = 500),
            Step("The whole interface runs in Traditional Chinese, English, Japanese and Korean.",
                onStage = { it.language("en"); delay(1800); it.language("ja"); delay(1800); it.language("ko") },
                holdMs = 900),
            Step("The voice follows the language — the same person, speaking four of them.",
                onStage = { delay(1000); it.language("zh") }, holdMs = 600),
        )),
        Unit("rehab", "🏥", "Speech rehabilitation", 20, steps = listOf(
            Step("Communication is today. Recovery is the long game.", route = "rehab"),
            Step("A clinical phrase bank works offline, with no API key.", holdMs = 900),
            Step("Hear the target sentence. Say it back.",
                onStage = { it.rehabTarget(com.example.ui.I18n.tr("我想要喝一杯水")) }, holdMs = 700),
            Step("Every attempt is scored syllable by syllable, so progress is something you can see.", holdMs = 900),
        )),
        Unit("training", "📖", "Guided training", 14, steps = listOf(
            Step("For milder cases, repeating single sentences is not enough.", route = "training"),
            Step("Guided exercises ask them to organise the meaning themselves.", holdMs = 900),
            Step("The AI answers the way a therapist would, and gives a score."),
        )),
        Unit("report", "📊", "Progress report", 18, steps = listOf(
            Step("Everything the person does quietly becomes a report.", route = "report"),
            Step("Sessions, average score, streak, and how much positive language they use.", holdMs = 900),
            Step("Behavioural numbers show how they are coping, not only how they scored."),
            Step("It exports for the next clinic visit."),
        )),
        Unit("history", "🕘", "History", 10, steps = listOf(
            Step("Every sentence they managed to say is kept.", route = "history"),
            Step("The ones that matter get starred, and come back with one tap.", holdMs = 900),
        )),
        Unit("cloud", "☁️", "Cloud sync", 12, steps = listOf(
            Step("Phone, web and desktop share one account.", route = "settings"),
            Step("The voice models stay in the user's own Google Drive. Never on our server."),
            Step("Nothing here leaves their account."),
        )),
        Unit("outro", "🏁", "Closing card", 8, steps = listOf(
            Step("Aphasia takes the words. It does not take the person.", holdMs = 1200),
        )),
    )

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
                    stage.speakAloud(text) {
                        if (!resumed) { resumed = true; if (cont.isActive) cont.resume(kotlin.Unit) }
                    }
                }
            }
        }
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
     * @param narrate   true＝英文旁白＋字幕；false＝只演示、不出聲也不出字幕
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
        /** 沒有預錄檔時的退路：照一般流程唸（會是系統語音）。 */
        speakFallback: (String, () -> kotlin.Unit) -> kotlin.Unit,
        onReady: suspend () -> kotlin.Unit = {},
        onFinish: () -> kotlin.Unit = {},
        /** 有旁白錄不起來（連不到電腦端語音）→ 中止並回報，缺幾句 / 共幾句。 */
        onNarrationMissing: (Int, Int) -> kotlin.Unit = { _, _ -> },
    ) {
        if (_active.value) return
        val units = UNITS.filter { it.id in ids }
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
                    if (voiceTag != clipsVoice) { clips.clear(); clipsVoice = voiceTag }
                    val lines = units.flatMap { u -> u.steps.map { it.cap } }
                        .filter { it.isNotBlank() }.distinct()
                    val todo = lines.filter { usableClip(it) == null }
                    _prep.value = 0 to todo.size
                    todo.forEachIndexed { i, line ->
                        _prep.value = i to todo.size
                        val f = suspendCancellableCoroutine<java.io.File?> { cont ->
                            var resumed = false
                            prerecord(line) { file ->
                                if (!resumed) { resumed = true; if (cont.isActive) cont.resume(file) }
                            }
                        }
                        if (f != null && f.exists() && f.length() > 0) clips[line] = f
                    }
                    _prep.value = todo.size to todo.size
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
                    for (s in u.steps) {
                        done++
                        _progress.value = "${u.icon} ${u.title} · $done/$total"
                        // 片頭／片尾卡蓋住畫面；其他單元一定要先把卡收掉
                        _titleCard.value = when (u.id) {
                            "intro" -> TitleCard("VoiceWeaver", "Communication support for people with aphasia")
                            "outro" -> TitleCard("VoiceWeaver", "Giving words back — in your own voice")
                            else -> null
                        }
                        s.route?.let { navigate(it) }
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
