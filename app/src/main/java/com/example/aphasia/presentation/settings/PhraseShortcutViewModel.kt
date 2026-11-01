package com.example.aphasia.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aphasia.data.local.PhraseLibrarySource
import com.example.aphasia.data.acoustic.TemplateMatcher
import com.example.aphasia.data.repository.AcousticTemplateRepositoryImpl
import com.example.aphasia.di.AphasiaGraph
import com.example.aphasia.domain.model.AcousticTemplate
import com.example.aphasia.domain.model.MatchMode
import com.example.aphasia.domain.model.Utterance
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「我的專屬發音」設定：從內建清單挑一句，唸幾次關鍵字就啟用。
 *
 * 之後只要講的話裡帶到那幾個字（前後說了什麼都不影響），就會播出整句話。
 * 比對走聲波形狀，完全不經過語音轉文字。
 */
class PhraseShortcutViewModel(
    private val graph: AphasiaGraph,
    private val library: PhraseLibrarySource,
    private val settings: com.example.data.SettingsRepository? = null,
) : ViewModel() {

    /**
     * 靈敏度。乘在自動校準出來的門檻上。
     *
     * 做成可調而不是我調一個常數：每個人、每句話的發音穩定度差很多，
     * 實測同一個人講同一句距離會在 3.6～7.7 之間跳，而不同人的分布只會更不一樣。
     * 一個寫死的數字不可能同時適合所有人，而猜錯的代價是使用者講不出話。
     */
    enum class Sensitivity(val gain: Float, val labelKey: String) {
        Strict(0.85f, "嚴格"),
        Normal(1.0f, "標準"),
        Loose(1.35f, "寬鬆"),
        ;
        companion object {
            fun nearest(gain: Float) = entries.minByOrNull { kotlin.math.abs(it.gain - gain) } ?: Normal
        }
    }

    /**
     * 每次錄音的語速指示。門檻由這幾次彼此的距離校準，全用同一個語速錄會太緊。
     *
     * [instructionKey] 是繁中原文，實際顯示時走 [com.example.ui.I18n.tr]——
     * 這是給使用者讀的字，不能寫死英文。畫面其他部分都跟著介面語言，
     * 只有這一行是英文會看起來像沒做完。
     */
    enum class Pace(val instructionKey: String) {
        Natural("用你平常的方式說一次"),
        Slow("再說一次，這次慢一點"),
        Quick("再說一次，這次快一點"),
        // 實測同一個人講同一句，距離會在 3.6 到 7.7 之間跳——3 次錄音蓋不住。
        // 多這兩次是為了涵蓋音量與狀況的變化，那是實測看到的主要變異來源。
        Soft("再說一次，這次小聲一點"),
        Again("最後一次，照你平常的方式"),
        ;
        val instruction: String get() = com.example.ui.I18n.tr(instructionKey)
    }

    /**
     * 一次測試的結果。
     *
     * 刻意帶著距離與門檻的原始數字：使用者要判斷「差一點」還是「差很多」，
     * 而我要靠這些數字校準 KEYWORD_THRESHOLD_FACTOR——目前那個 0.75
     * 是合成訊號訂出來的，還沒有用真實錄音驗證過。
     */
    data class TestResult(
        val scores: List<TemplateMatcher.Score>,
        /** 太短或太安靜，根本沒錄到聲音。 */
        val notSpeech: Boolean = false,
    ) {
        val best: TemplateMatcher.Score? get() = scores.firstOrNull()
        val hit: Boolean get() = best?.passed == true
    }

    data class UiState(
        val library: List<PhraseLibrarySource.Entry> = emptyList(),
        /** 專屬發音總開關（預設開；關掉只是不比對，樣板都還在）。 */
        val acousticEnabled: Boolean = true,
        val enrolled: List<AcousticTemplate> = emptyList(),
        /** 正在登錄的那一筆。null＝在清單畫面。 */
        val recordingFor: PhraseLibrarySource.Entry? = null,
        /** 不是 null 表示這次是「補錄」既有的捷徑，不是新建。 */
        val addingTo: AcousticTemplate? = null,
        /**
         * 登錄畫面要顯示哪個圖示。
         *
         * 不能直接用 recordingFor.id：補錄時那是樣板的數字 id，不是句庫的鍵，
         * 查不到圖會變成空白——而使用者正需要那張圖確認自己選對了句子。
         */
        val enrollIconKey: String? = null,
        val takes: List<Utterance> = emptyList(),
        val recording: Boolean = false,
        val saving: Boolean = false,
        val amplitude: Float = 0f,
        val message: String? = null,
        val justSaved: String? = null,
        /** 自訂項目：使用者自己打的關鍵字與句子 */
        val customKeyword: String = "",
        val customSentence: String = "",
        /** 測試模式：正在錄一句話來看會不會命中。 */
        val testing: Boolean = false,
        val testResult: TestResult? = null,
        /** 說明文字要不要展開。預設收起——它佔掉整個上半部，而且只有第一次需要讀。 */
        val helpExpanded: Boolean = false,
        val sensitivity: Sensitivity = Sensitivity.Normal,
        /** 正在從雲端取回。 */
        val syncing: Boolean = false,
    ) {
        val paces: List<Pace> get() = Pace.entries
        val nextPace: Pace? get() = paces.getOrNull(takes.size)
        val canSave: Boolean get() = !saving &&
            // 補錄只要一次就有價值（比對取最小距離，多一次錄音只會提高召回率）；
            // 新建則需要足夠的樣本才估得出門檻。
            takes.size >= (if (addingTo != null) 1 else AcousticTemplate.MIN_ENROLLMENT_TAKES)
        /** 已啟用的關鍵字（用來在清單上打勾）。 */
        val enrolledKeywords: Set<String> get() = enrolled.map { it.keyword.lowercase() }.toSet()
        val categories: List<String> get() = library.map { it.category }.distinct()
        val isCustom: Boolean get() = recordingFor?.id == CUSTOM_ID
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val stopRequested = AtomicBoolean(false)
    private var recordJob: Job? = null

    init {
        graph.audioCapture.liveAmplitude
            .onEach { _state.value = _state.value.copy(amplitude = it) }
            .launchIn(viewModelScope)
        viewModelScope.launch {
            val entries = library.load()
            _state.value = _state.value.copy(library = entries)
            // 分類是後來才加的欄位，Room migration 只能給預設值 Custom
            //（SQL 讀不到語句庫那份 asset）。這裡才是第一個同時握有
            // 「既有樣板」與「語句庫」的地方，用觸發詞把分類對回去——
            // 否則使用者雲端硬碟裡的舊檔案會永遠卡在 Custom 資料夾。
            runCatching {
                (graph.templateRepository as AcousticTemplateRepositoryImpl).backfillCategories { kw ->
                    entries.firstOrNull { it.matches(kw) }?.category
                }
            }
        }
        // 校準公式改過，舊樣板的門檻還是用舊公式算的（實測有一筆膨脹到 21.9，
        // 把所有聲音都吞掉）。錄音都還在，所以直接重算，不必請使用者重錄。
        viewModelScope.launch {
            runCatching {
                (graph.templateRepository as AcousticTemplateRepositoryImpl).recalibrateAll()
            }
        }
        viewModelScope.launch {
            graph.templateRepository.observeAll().collect {
                _state.value = _state.value.copy(enrolled = it)
            }
        }
        settings?.let { repo ->
            viewModelScope.launch {
                repo.settings.collect { s ->
                    _state.value = _state.value.copy(
                        sensitivity = Sensitivity.nearest(s.shortcutSensitivity),
                        acousticEnabled = s.acousticMatchEnabled,
                    )
                }
            }
        }
        // 一進畫面就自動跟雲端對一次。
        //
        // 沒有這一步的話，「同步」其實只有單向：手機錄完會自動上傳，
        // 但照顧者在電腦上打好的句子要使用者自己想到去按「從雲端取回」——
        // 而他多半不知道有那顆按鈕，只會覺得電腦上設的東西沒有進到手機。
        // 靜音模式：真的有變動才出聲，沒登入或已是最新就不吵人。
        viewModelScope.launch { runCatching { syncFromCloud(silent = true) } }
    }

    /**
     * 從雲端取回專屬發音。
     *
     * 連錄音波形一起拉——換手機不必重錄，而錄音正是這個功能最花力氣的部分
     *（一句要錄 5 次）。本機已經有同一個觸發詞的會跳過，不覆蓋：
     * 本機那筆是照這支麥克風校準的，雲端來的未必更好。
     *
     * 兩個來源都讀：Firestore（即時、免 Drive 授權）與使用者自己 Drive 的
     * `VoiceWeaver/AcousticModels/`。兩邊會不同步——網頁版沒 Drive 授權時
     * 只寫得到 Firestore，而使用者可能自己把 Drive 那份搬過帳號。
     */
    /**
     * @param silent 開畫面時的自動同步用。沒登入、雲端沒東西、已經是最新的——
     *   這些都不是使用者做錯什麼，跳一個訊息只會變成每次進畫面都要關掉的東西。
     *   真的有變動才說話。
     */
    fun syncFromCloud(silent: Boolean = false) {
        if (_state.value.syncing) return
        _state.value = _state.value.copy(syncing = true, message = null)
        com.example.ai.CloudSync.pullAcousticTemplates { firestore ->
            viewModelScope.launch {
                val repo = graph.templateRepository as AcousticTemplateRepositoryImpl
                val snap = runCatching { repo.pullFromClouds(firestore) }.getOrDefault(
                    AcousticTemplateRepositoryImpl.CloudSnapshot(firestore.templates, false))
                val r = runCatching { repo.importFromCloud(snap.templates, snap.authoritative) }
                    .getOrDefault(AcousticTemplateRepositoryImpl.ImportResult(0, 0, 0))
                val note = when {
                    // 讀不到雲端跟「雲端是空的」要講不一樣的話：前者按了也沒用，
                    // 後者是真的還沒在別台設定過。
                    !snap.authoritative && snap.templates.isEmpty() ->
                        com.example.ui.I18n.tr("連不上雲端（請確認已登入並有網路）")
                    snap.templates.isEmpty() && r.removed == 0 ->
                        com.example.ui.I18n.tr("雲端沒有可取回的專屬發音（需要先登入並在另一台裝置設定過）")
                    !r.changed && r.needsRecording == 0 ->
                        com.example.ui.I18n.tr("已經是最新的，沒有需要取回的項目")
                    r.removed > 0 && r.imported == 0 && r.updated == 0 ->
                        com.example.ui.I18n.tr("雲端已刪除，本機同步移除 %d 筆").format(r.removed)
                    r.needsRecording > 0 ->
                        com.example.ui.I18n.tr("取回 %1\$d 筆；另有 %2\$d 筆只有文字設定，要在這支手機錄過音才會生效")
                            .format(r.imported + r.updated, r.needsRecording)
                    r.updated > 0 && r.imported == 0 ->
                        com.example.ui.I18n.tr("更新 %d 筆的句子").format(r.updated)
                    else -> com.example.ui.I18n.tr("取回 %d 筆").format(r.imported + r.updated)
                }
                _state.value = _state.value.copy(
                    syncing = false,
                    // 自動同步時只有「真的動了東西」才出聲
                    message = if (silent && !r.changed) _state.value.message else note,
                )
            }
        }
    }

    fun setSensitivity(value: Sensitivity) {
        viewModelScope.launch { settings?.setShortcutSensitivity(value.gain) }
        // 樂觀更新：DataStore 回流有延遲，等它會讓按鈕看起來沒反應
        _state.value = _state.value.copy(sensitivity = value)
    }

    /** 專屬發音總開關。 */
    fun setAcousticEnabled(on: Boolean) {
        _state.value = _state.value.copy(acousticEnabled = on)   // 立即回饋，不等 DataStore
        settings?.let { repo -> viewModelScope.launch { repo.setAcousticMatchEnabled(on) } }
    }

    // ── 選一句 ──────────────────────────────────────────────────────────

    fun beginEnrollment(entry: PhraseLibrarySource.Entry) {
        _state.value = _state.value.copy(
            recordingFor = entry, addingTo = null, enrollIconKey = entry.id,
            takes = emptyList(), message = null, justSaved = null,
        )
    }

    /**
     * 補錄：把新錄音加進既有捷徑。
     *
     * 「唸了一樣的卻沒反應」的正解。實測同一個人講同一句，距離會在 3.6 到 7.7
     * 之間跳——3 次錄音蓋不住真實的組內變異。比對取的是與任一次錄音的最小距離，
     * 所以多錄只會提高召回率。
     */
    fun beginAddTakes(template: AcousticTemplate) {
        _state.value = _state.value.copy(
            recordingFor = PhraseLibrarySource.Entry(
                template.id.toString(), "", template.keyword, template.spokenPhrase,
            ),
            addingTo = template,
            enrollIconKey = _state.value.library
                .firstOrNull { it.keyword.equals(template.keyword, ignoreCase = true) }?.id,
            takes = emptyList(), message = null, justSaved = null,
        )
    }

    /** 自訂一句：使用者自己決定關鍵字與要說出口的話。 */
    fun beginCustom() {
        _state.value = _state.value.copy(
            recordingFor = PhraseLibrarySource.Entry(CUSTOM_ID, "Custom", "", ""),
            addingTo = null, enrollIconKey = null,
            takes = emptyList(), customKeyword = "", customSentence = "",
            message = null, justSaved = null,
        )
    }

    fun setCustomKeyword(v: String) { _state.value = _state.value.copy(customKeyword = v) }
    fun setCustomSentence(v: String) { _state.value = _state.value.copy(customSentence = v) }

    fun cancelEnrollment() {
        cancelTake()
        _state.value = _state.value.copy(
            recordingFor = null, addingTo = null, enrollIconKey = null,
            takes = emptyList(), message = null,
        )
    }

    // ── 錄音 ────────────────────────────────────────────────────────────

    fun startTake() {
        val s = _state.value
        if (s.recording || s.saving || s.nextPace == null) return
        if (!graph.audioCapture.hasPermission()) {
            _state.value = s.copy(message = com.example.ui.I18n.tr("需要麥克風權限才能錄音"))
            return
        }
        stopRequested.set(false)
        _state.value = s.copy(recording = true, message = null)
        recordJob = viewModelScope.launch {
            val result = graph.audioFeatureRepository.recordUntil(MAX_TAKE_MS) { stopRequested.get() }
            _state.value = result.fold(
                onSuccess = { utterance ->
                    // 太短或太安靜的一次會把校準出來的門檻整個拉歪，
                    // 而症狀要等到之後「怎麼都比對不到」才會顯現。
                    if (utterance.durationMs < MIN_TAKE_MS || utterance.peakAmplitude < MIN_PEAK) {
                        _state.value.copy(
                            recording = false, amplitude = 0f,
                            message = com.example.ui.I18n.tr("剛才太小聲或太短了，這一次再錄一遍"),
                        )
                    } else {
                        _state.value.copy(
                            recording = false, amplitude = 0f, takes = _state.value.takes + utterance,
                        )
                    }
                },
                onFailure = {
                    _state.value.copy(
                        recording = false, amplitude = 0f,
                        message = com.example.ui.I18n.tr("無法錄音：%s").format(it.message ?: com.example.ui.I18n.tr("未知錯誤")),
                    )
                },
            )
        }
    }

    fun stopTake() = stopRequested.set(true)

    fun cancelTake() {
        stopRequested.set(true)
        recordJob?.cancel()
        _state.value = _state.value.copy(recording = false, amplitude = 0f)
    }

    fun undoLastTake() {
        _state.value = _state.value.copy(takes = _state.value.takes.dropLast(1), message = null)
    }

    // ── 存檔 ────────────────────────────────────────────────────────────

    fun save() {
        val s = _state.value
        val entry = s.recordingFor ?: return
        if (!s.canSave) return

        val keyword = (if (s.isCustom) s.customKeyword else entry.keyword).trim()
        val sentence = (if (s.isCustom) s.customSentence else entry.sentence).trim()
        if (keyword.isBlank() || sentence.isBlank()) {
            _state.value = s.copy(message = com.example.ui.I18n.tr("請把「要聽的字」和「要說出口的話」都填好"))
            return
        }

        _state.value = s.copy(saving = true, message = null)
        viewModelScope.launch {
            val repo = graph.templateRepository as AcousticTemplateRepositoryImpl
            val target = s.addingTo
            val result = runCatching {
                if (target != null) repo.addTakes(target.id, s.takes)
                else repo.enroll(
                    label = keyword,
                    keyword = keyword,
                    spokenPhrase = sentence,
                    // 一律關鍵字模式：使用者講「I need some water」時，
                    // 樣板只要對上其中的「need water」就算命中。
                    matchMode = MatchMode.Keyword,
                    takes = s.takes,
                    // 語句庫那一筆的分類跟著存下來，雲端會照它開資料夾。
                    // 自訂的 entry 帶的是 "Custom"（見 startCustom）。
                    category = entry.category,
                )
            }
            _state.value = result.fold(
                onSuccess = {
                    _state.value.copy(
                        recordingFor = null, addingTo = null, enrollIconKey = null,
                        takes = emptyList(), saving = false, justSaved = keyword,
                    )
                },
                onFailure = { error ->
                    _state.value.copy(
                        saving = false,
                        // 校準失敗代表幾次錄音彼此差太多。硬給一個預設門檻會讓
                        // 這個捷徑的行為完全無法預測，寧可要使用者重錄。
                        message = com.example.ui.I18n.tr("無法建立捷徑：%s").format(error.message ?: com.example.ui.I18n.tr("請重新錄一次")),
                    )
                },
            )
        }
    }

    // ── 測試 ────────────────────────────────────────────────────────────

    /**
     * 錄一句話，看會不會命中已啟用的捷徑。
     *
     * 存在的理由：登錄完之後使用者無從得知它到底管不管用，只能回主畫面碰運氣。
     * 在這裡當場測，命中就唸出來，沒命中也看得到差多少。
     */
    fun startTest() {
        val s = _state.value
        if (s.testing || s.recording || s.enrolled.isEmpty()) return
        if (!graph.audioCapture.hasPermission()) {
            _state.value = s.copy(message = com.example.ui.I18n.tr("需要麥克風權限才能錄音"))
            return
        }
        stopRequested.set(false)
        _state.value = s.copy(testing = true, testResult = null, message = null)
        recordJob = viewModelScope.launch {
            val recorded = graph.audioFeatureRepository.recordUntil(TEST_MAX_MS) { stopRequested.get() }
            val utterance = recorded.getOrElse {
                _state.value = _state.value.copy(
                    testing = false, amplitude = 0f,
                    message = com.example.ui.I18n.tr("無法錄音：%s")
                        .format(it.message ?: com.example.ui.I18n.tr("未知錯誤")),
                )
                return@launch
            }
            if (utterance.durationMs < MIN_TAKE_MS || utterance.peakAmplitude < MIN_PEAK) {
                _state.value = _state.value.copy(
                    testing = false, amplitude = 0f, testResult = TestResult(emptyList(), notSpeech = true),
                )
                return@launch
            }

            val repo = graph.templateRepository as AcousticTemplateRepositoryImpl
            val scores = runCatching { repo.diagnose(utterance) }.getOrDefault(emptyList())
            val result = TestResult(scores)
            _state.value = _state.value.copy(testing = false, amplitude = 0f, testResult = result)

            // 命中就真的唸出來——測試要測的是完整行為，不是只看數字
            result.best?.takeIf { it.passed }?.let {
                graph.speechRepository.speak(it.template.spokenPhrase)
            }
        }
    }

    fun stopTest() = stopRequested.set(true)

    fun toggleHelp() {
        _state.value = _state.value.copy(helpExpanded = !_state.value.helpExpanded)
    }

    fun delete(template: AcousticTemplate) {
        viewModelScope.launch { graph.templateRepository.delete(template.id) }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, justSaved = null)
    }

    companion object {
        const val CUSTOM_ID = "__custom__"
        const val MAX_TAKE_MS = 4_000L
        /** 測試時可以講一整句，所以比登錄長。 */
        const val TEST_MAX_MS = 6_000L
        const val MIN_TAKE_MS = 250L
        const val MIN_PEAK = 0.02f
    }
}
