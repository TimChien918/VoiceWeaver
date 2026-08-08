package com.example.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** 給 UI 估時用：一步大概多久（英文約每秒 2.6 個字）。 */
    fun stepMillis(cap: String, narrated: Boolean): Long {
        if (!narrated) return MIN_STEP_SILENT_MS
        val words = cap.split(Regex("\\s+")).count { it.isNotBlank() }
        return maxOf(MIN_STEP_NARRATED_MS, (words / 2.6 * 1000).toLong()) + TAIL_PAD_MS
    }

    /** 勾選的單元總長（秒）。 */
    fun estimateSeconds(ids: Set<String>, narrated: Boolean): Int =
        (UNITS.filter { it.id in ids }
            .flatMap { it.steps }
            .sumOf { stepMillis(it.cap, narrated) + it.holdMs } / 1000).toInt()

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
            Step("One tap turns the fragments into a whole sentence.",
                onStage = { it.candidates(SAMPLE_CANDIDATES) }, holdMs = 600),
            Step("Three versions, never one. A wrong guess put in your mouth is worse than silence.",
                onStage = { it.pick(1); delay(1200); it.pick(2); delay(1200); it.pick(0) }),
            Step("Only then is it said out loud, in the person's own voice.", holdMs = 600),
        )),
        Unit("aac", "🖼", "Picture cards (AAC)", 22, steps = listOf(
            Step("Some people cannot type at all. For them, the board is the way in.", route = "aac"),
            Step("Cards are coloured by part of speech, and grouped by where you are.", holdMs = 900),
            Step("Tap by tap, a sentence takes shape, ready to speak or to rewrite."),
            Step("Everything scales up, for tired eyes and unsteady hands.", holdMs = 900),
        )),
        Unit("customcards", "📷", "Custom photo cards", 12, steps = listOf(
            Step("A stock drawing of a cup means nothing to someone's grandmother.", route = "aac"),
            Step("So the family photographs her cup. Her chair. Her grandson."),
            Step("Recognising your own things is where trust begins."),
        )),
        Unit("acoustic", "🎤", "Personal pronunciation model", 18, steps = listOf(
            Step("Aphasic speech breaks ordinary speech recognition. Every time.", route = "phrase_shortcuts"),
            Step("So the app learns this person's own way of saying a few key words."),
            Step("It keeps an acoustic template, and matches it with dynamic time warping."),
            Step("It listens to the person in front of it, not to an average speaker.", holdMs = 700),
        )),
        Unit("voice", "🗣", "Personal voice (GPT-SoVITS)", 20, steps = listOf(
            Step("Losing speech often means losing your voice as well.", route = "settings"),
            Step("A short recording made before the illness is enough to rebuild it."),
            Step("The model lives in their own Drive, and follows them to phone, web and desktop."),
            Step("So the sentence comes out sounding like them, with the right feeling."),
        )),
        Unit("rehab", "🏥", "Speech rehabilitation", 20, steps = listOf(
            Step("Communication is today. Recovery is the long game.", route = "rehab"),
            Step("A clinical phrase bank works offline, with no API key.", holdMs = 900),
            Step("Hear the target sentence. Say it back."),
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
        Unit("crisis", "🆘", "Crisis intervention", 18,
            note = "Described only — nothing is sent.", steps = listOf(
            Step("Losing language carries a real risk of depression and self-harm.", route = "main"),
            Step("So every sentence is screened before it is ever spoken."),
            Step("When something is found, the app does not refuse and walk away."),
            Step("It stays. A breathing circle, a few large buttons, and the family already contacted."),
            Step("Nothing is sent during this demo."),
        )),
        Unit("severe", "🔒", "Severe mode", 16, steps = listOf(
            Step("For the most severely affected, a whole grid is still too much.", route = "aac"),
            Step("So the device becomes one card at a time, scanning on its own."),
            Step("A tap speaks it, and leaves a trail the family can read."),
            Step("Getting out needs a caregiver PIN. Nobody gets lost in the app."),
        )),
        Unit("a11y", "♿", "Accessibility & languages", 14, steps = listOf(
            Step("Text scales across the whole app, settings included.", route = "settings"),
            Step("Traditional Chinese, English, Japanese, Korean."),
            Step("The voice follows the language."),
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

    /** 已預錄好的旁白：句子 → 音檔。跨次執行保留，第二次跑就不用再合成。 */
    private val clips = mutableMapOf<String, java.io.File>()
    private var player: android.media.MediaPlayer? = null

    /** 逐字打進輸入框。一次貼上看起來像作弊，逐字打才看得懂使用者在做什麼。 */
    private suspend fun typeSlowly(stage: Stage, text: String) {
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            stage.type(sb.toString())
            delay(70)
        }
    }

    /** 播一段預錄好的旁白，等它真的播完。 */
    private suspend fun playClip(file: java.io.File) = suspendCancellableCoroutine<kotlin.Unit> { cont ->
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
                prepare()
                start()
            }
        }.onFailure { finish() }     // 播不出來也要放行，不然整段演示卡在這裡
        cont.invokeOnCancellation { runCatching { player?.release() }; player = null }
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

        _active.value = true
        job = scope.launch {
            try {
                if (narrate) {
                    // 先把每一句旁白**錄成檔案**。演示時直接播那個檔，不再走一次 TTS——
                    // 那條路每次都會重新判語言、可能切模型，也可能在任何一步掉回系統機器音。
                    // 錄好的檔案留著，第二次跑完全不用合成。
                    val lines = units.flatMap { u -> u.steps.map { it.cap } }
                        .filter { it.isNotBlank() }.distinct()
                    val todo = lines.filter { it !in clips }
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
                    val missing = lines.count { it !in clips }
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
                            val clip = clips[s.cap]
                            if (clip != null) playClip(clip)
                            else awaitCallback { done -> speakFallback(s.cap, done) }
                            delay(TAIL_PAD_MS)      // 講完再讓畫面多停一下
                        }
                        staging?.join()
                        if (s.holdMs > 0) delay(s.holdMs)
                        // 補到最短停留；旁白比下限長時這裡是 0，不會拖慢。
                        val floor = if (narrate) MIN_STEP_NARRATED_MS else MIN_STEP_SILENT_MS
                        val left = floor - (System.currentTimeMillis() - t0)
                        if (left > 0) delay(left)
                    }
                }
            } finally {
                runCatching { stage.clear() }   // 演示放上去的碎詞／候選句不留給使用者
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
