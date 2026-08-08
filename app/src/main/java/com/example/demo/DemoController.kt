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

    data class Step(
        /** 字幕／旁白（英文）。空字串＝這一步只換畫面不講話。 */
        val cap: String,
        /** 要切到的頁面；null＝留在原地。 */
        val route: String? = null,
        /** 額外停留毫秒（在旁白講完之後）。 */
        val holdMs: Long = 0,
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
            Step("VoiceWeaver — a communication aid for people with aphasia."),
            Step("It turns broken words into whole sentences, and speaks them in a familiar voice."),
            Step("Here is a walkthrough of the main features."),
        )),
        Unit("compose", "💬", "Sentence reconstruction", 24, steps = listOf(
            Step("A person with aphasia can often produce only isolated words.", route = "main"),
            Step("They type or speak the fragments they can manage."),
            Step("One tap rebuilds them into a natural sentence."),
            Step("Three candidates are offered, so the user is never forced to accept a wrong guess.", holdMs = 800),
            Step("Before anything is spoken aloud, the intent is confirmed on a large picture card."),
        )),
        Unit("aac", "🖼", "Picture cards (AAC)", 22, steps = listOf(
            Step("For users who cannot type at all, the picture board is the way in.", route = "aac"),
            Step("Cards are colour-coded by part of speech and grouped by situation.", holdMs = 900),
            Step("Tapping cards builds a sentence buffer that can be spoken or rewritten."),
            Step("Card size scales up for low vision and unsteady hands.", holdMs = 900),
        )),
        Unit("customcards", "📷", "Custom photo cards", 12, steps = listOf(
            Step("Generic icons mean little to an older adult.", route = "aac"),
            Step("A family member photographs the person's own things — their cup, their grandchild."),
            Step("Recognising your own belongings is what builds trust in the device."),
        )),
        Unit("acoustic", "🎤", "Personal pronunciation model", 18, steps = listOf(
            Step("Aphasic speech breaks ordinary speech recognition.", route = "phrase_shortcuts"),
            Step("So the user records their own way of saying a few key words."),
            Step("The app keeps an acoustic template per keyword and matches it with dynamic time warping."),
            Step("It recognises the person in front of it, not an average speaker.", holdMs = 700),
        )),
        Unit("voice", "🗣", "Personal voice (GPT-SoVITS)", 20, steps = listOf(
            Step("Losing speech often means losing your own voice as well.", route = "settings"),
            Step("A GPT-SoVITS model is trained from a short recording made before the illness."),
            Step("Models live in the user's own Google Drive and sync to phone, web and desktop."),
            Step("The sentence is then spoken in the person's own voice, with the right emotion."),
        )),
        Unit("rehab", "🏥", "Speech rehabilitation", 20, steps = listOf(
            Step("Beyond communication, the app doubles as a daily practice tool.", route = "rehab"),
            Step("A clinical phrase bank works offline, with no API key required.", holdMs = 900),
            Step("The user hears the target sentence, then reads it back."),
            Step("Each attempt is scored per syllable, so progress is visible.", holdMs = 900),
        )),
        Unit("training", "📖", "Guided training", 14, steps = listOf(
            Step("Repeating single sentences is not enough for milder cases.", route = "training"),
            Step("Guided exercises ask the user to organise meaning on their own.", holdMs = 900),
            Step("The AI comments the way a therapist would, and gives a score."),
        )),
        Unit("report", "📊", "Progress report", 18, steps = listOf(
            Step("Everything the user does feeds a report their therapist can read.", route = "report"),
            Step("Sessions, average score, streak, and use of positive language.", holdMs = 900),
            Step("Behavioural metrics show how the person is coping, not just how they scored."),
            Step("The report exports for a clinical visit."),
        )),
        Unit("history", "🕘", "History", 10, steps = listOf(
            Step("Every sentence the person managed to say is kept.", route = "history"),
            Step("Frequent ones can be starred and reused instantly.", holdMs = 900),
        )),
        Unit("crisis", "🆘", "Crisis intervention", 18,
            note = "Described only — nothing is sent.", steps = listOf(
            Step("Losing speech carries a real risk of depression and self-harm.", route = "main"),
            Step("Every sentence is screened for crisis signals before it is spoken."),
            Step("If one is found the app does not simply refuse — it stays with the person:"),
            Step("a breathing circle, a few large buttons, and the family contacted automatically."),
            Step("Nothing is sent during this demo."),
        )),
        Unit("severe", "🔒", "Severe mode", 16, steps = listOf(
            Step("For the most severely affected, a whole grid of cards is still too much.", route = "aac"),
            Step("Severe mode turns the device into a single-card board that scans on its own."),
            Step("Tapping speaks the card and adds it to a clue trail the family can read."),
            Step("Leaving the mode needs a caregiver PIN, so the patient cannot get lost in the app."),
        )),
        Unit("a11y", "♿", "Accessibility & languages", 14, steps = listOf(
            Step("Text scales across the whole app, including inside settings.", route = "settings"),
            Step("The interface runs in Traditional Chinese, English, Japanese and Korean."),
            Step("Voice output follows the same language."),
        )),
        Unit("cloud", "☁️", "Cloud sync", 12, steps = listOf(
            Step("Phone, web and desktop share one account.", route = "settings"),
            Step("Voice models stay in the user's own Google Drive, never on a developer server."),
            Step("Nothing here leaves the user's own account."),
        )),
        Unit("outro", "🏁", "Closing card", 8, steps = listOf(
            Step("VoiceWeaver — giving words back, in your own voice.", holdMs = 1200),
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

    fun stop() {
        job?.cancel()
        job = null
        reset()
    }

    private fun reset() {
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
        /** 唸一句並在唸完時回呼（由 ViewModel 提供）。 */
        speak: (String, () -> kotlin.Unit) -> kotlin.Unit,
        /** 只合成進快取、不出聲。 */
        prewarm: (String, () -> kotlin.Unit) -> kotlin.Unit,
        onReady: suspend () -> kotlin.Unit = {},
        onFinish: () -> kotlin.Unit = {},
    ) {
        if (_active.value) return
        val units = UNITS.filter { it.id in ids }
        if (units.isEmpty()) return

        _active.value = true
        job = scope.launch {
            try {
                if (narrate) {
                    val lines = units.flatMap { u -> u.steps.map { it.cap } }
                        .filter { it.isNotBlank() }.distinct()
                    _prep.value = 0 to lines.size
                    lines.forEachIndexed { i, line ->
                        _prep.value = i to lines.size
                        awaitCallback { done -> prewarm(line, done) }
                    }
                    _prep.value = lines.size to lines.size
                    _prep.value = null
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
                        _caption.value = if (narrate) s.cap else ""
                        if (narrate && s.cap.isNotBlank()) awaitCallback { done -> speak(s.cap, done) } else delay(1200)
                        if (s.holdMs > 0) delay(s.holdMs)
                    }
                }
            } finally {
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
