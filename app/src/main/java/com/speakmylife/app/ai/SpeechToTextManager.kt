package com.speakmylife.app.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 一個語言對應的 Vosk 聲學模型。
 *
 * Vosk 的模型是單一語言的——拿中文模型聽英文只會得到一串亂碼，不是「準確度差一點」
 * 而是完全不能用。四個小模型全部內建進 APK（共約 211MB），使用者切介面語言之後
 * 不必連網也不必等下載；網路來源只當內建檔缺損時的自救管道。
 */
data class VoskModelSpec(
    val dirName: String,
    val assetZip: String,
    val url: String,
    val displayName: String,
) {
    companion object {
        fun forLanguage(lang: com.speakmylife.app.data.AppLanguage): VoskModelSpec = when (lang) {
            com.speakmylife.app.data.AppLanguage.En -> of("vosk-model-small-en-us-0.15", "英文")
            com.speakmylife.app.data.AppLanguage.Ja -> of("vosk-model-small-ja-0.22", "日文")
            com.speakmylife.app.data.AppLanguage.Ko -> of("vosk-model-small-ko-0.22", "韓文")
            else -> of("vosk-model-small-cn-0.22", "中文")
        }

        private fun of(dir: String, name: String) = VoskModelSpec(
            dirName = dir,
            assetZip = "models/$dir.zip",
            url = "https://alphacephei.com/vosk/models/$dir.zip",
            displayName = name,
        )
    }
}

sealed class SttState {
    object Idle : SttState()
    /** 首次使用時下載/載入離線語音模型 */
    data class Preparing(val message: String) : SttState()
    object Listening : SttState()
    object Processing : SttState()
    data class Error(val message: String) : SttState()
}

/**
 * 純裝置端離線語音辨識（Vosk / Kaldi）。
 *
 * 設計理由：本專案部分裝置（如 ASUS I005DC, Android 13）的系統語音辨識服務
 * 只剩離線 SODA / TTS 兩種，且未安裝 zh 離線語言包，線上 GoogleRecognitionService
 * 又被停用（serviceComponent is not RecognitionService）→ 一律 ERROR_CLIENT(#5) 或
 * LANGUAGE_PACK_ERROR(#12)。
 *
 * 因此改用 Vosk：自帶 AudioRecord 即時聽寫，模型由公開 URL 下載一次（~44MB，免帳號），
 * 之後完全離線、不依賴任何 Google 語音服務或語言包。
 *
 * 辨識結果（中文）回填碎詞欄，再連同 GPS / 相機標籤交給 Qwen LLM 重組成完整繁體句。
 */
class SpeechToTextManager(
    private val context: Context,
    /**
     * @param alternatives 除了最佳解之外的其他候選（N-best），供重組 AI 依情境挑選。
     * @param utterance 這次辨識用的原始波形（16 kHz PCM）。
     *   給聲學捷徑比對用——**辨識與捷徑必須共用同一次錄音**，
     *   麥克風一次只能被一個人拿著，不可能請使用者講兩遍。
     */
    private val onResult: (
        text: String,
        words: List<WordConf>,
        alternatives: List<String>,
        utterance: com.speakmylife.app.aphasia.domain.model.Utterance?,
    ) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var model: Model? = null
    /** 目前載入的是哪個語言的模型；語言換了就要重載。 */
    private var loadedModelDir: String? = null
    private var captureJob: kotlinx.coroutines.Job? = null
    private val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 這次辨識累積的 PCM（16-bit）。給聲學捷徑比對用。 */
    private val pcm = java.util.ArrayList<Short>(SAMPLE_RATE * 4)
    private val collected = StringBuilder()
    private val collectedWords = mutableListOf<WordConf>()
    private val collectedAlternatives = mutableListOf<String>()

    private val _sttState = MutableStateFlow<SttState>(SttState.Idle)
    val sttState: StateFlow<SttState> = _sttState.asStateFlow()

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) { /* 即時片段，暫不顯示 */ }

        override fun onResult(hypothesis: String?) {
            // 一段語音（自然停頓）結束 → 累積文字與逐字信心
            Log.d(TAG, "Vosk onResult raw: $hypothesis")
            val parsed = parseResult(hypothesis)
            if (parsed.text.isNotBlank()) collected.append(parsed.text)
            collectedWords.addAll(parsed.words)
            collectedAlternatives.addAll(parsed.alternatives)
        }

        override fun onFinalResult(hypothesis: String?) {
            Log.d(TAG, "Vosk onFinalResult raw: $hypothesis")
            val parsed = parseResult(hypothesis)
            if (parsed.text.isNotBlank()) collected.append(parsed.text)
            collectedWords.addAll(parsed.words)
            collectedAlternatives.addAll(parsed.alternatives)
            val finalText = collected.toString().trim()
            val finalWords = collectedWords.toList()
            // 去重、剔除跟最佳解一樣的，最多留 3 個給 LLM 判斷
            val finalAlts = collectedAlternatives
                .map { it.trim() }
                .filter { it.isNotBlank() && it != finalText }
                .distinct()
                .take(MAX_ALTERNATIVES)
            collected.clear()
            collectedWords.clear()
            collectedAlternatives.clear()
            // vosk-model-small-cn 是簡體普通話模型，輸出一律簡體。使用者是台灣人，
            // 碎詞欄、歷史紀錄、復健比對都該是繁體，所以在這裡統一轉一次，
            // 讓這個 manager 對外的約定就是「中文回傳繁體」，各呼叫端不必各轉一遍。
            //
            // 但只有中文能轉：日文的漢字會被繁簡表改成中文字形（「読む」→「讀む」這類），
            // 等於把辨識對的結果弄壞。英文/韓文雖然不含漢字、轉了也不會變，
            // 但一樣沒有理由多跑一趟。
            val isZh = com.speakmylife.app.ui.I18n.lang == com.speakmylife.app.data.AppLanguage.ZhTW
            fun localize(s: String) = if (isZh) ChineseConverter.toTraditional(context, s) else s
            // 波形要在這裡取：pcm 緩衝區在下一次 beginRecognition 就會被清掉。
            val utterance = capturedUtterance()
            if (finalText.isNotEmpty()) onResult(
                localize(finalText),
                finalWords,
                finalAlts.map(::localize),
                utterance,
            )
            _sttState.value = SttState.Idle
        }

        override fun onError(e: Exception?) {
            Log.e(TAG, "Vosk recognition error", e)
            _sttState.value = SttState.Error("辨識失敗：${e?.message ?: "未知錯誤"}")
        }

        override fun onTimeout() {
            stopRequested.set(true)
        }
    }

    fun startListening() {
        val s = _sttState.value
        if (s is SttState.Listening || s is SttState.Preparing) return

        // 介面語言決定要用哪個聲學模型。Vosk 的模型是單一語言的，
        // 拿中文模型去聽英文只會得到一串亂碼——以前這裡寫死中文，
        // 所以介面切成英文/日文/韓文之後，語音轉文字實際上是壞的。
        val target = VoskModelSpec.forLanguage(com.speakmylife.app.ui.I18n.lang)
        if (model != null && loadedModelDir == target.dirName) {
            beginRecognition()
            return
        }
        // 語言換了就得換模型：舊的先關掉，否則兩份聲學模型同時佔記憶體
        if (model != null) {
            stopRequested.set(true)
            runCatching { captureJob?.cancel() }
            captureJob = null
            runCatching { model?.close() }
            model = null
            loadedModelDir = null
        }

        _sttState.value = SttState.Preparing("正在準備${target.displayName}語音模型...")
        scope.launch {
            try {
                val modelDir = ensureModelReady(target) { pct ->
                    _sttState.value = SttState.Preparing("下載${target.displayName}語音模型 $pct%")
                }
                val loaded = Model(modelDir.absolutePath)
                withContext(Dispatchers.Main) {
                    model = loaded
                    loadedModelDir = target.dirName
                    beginRecognition()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vosk model prepare failed", e)
                withContext(Dispatchers.Main) {
                    _sttState.value = SttState.Error("語音模型準備失敗：${e.message}")
                }
            }
        }
    }

    /**
     * 收音迴圈。**不用 Vosk 的 SpeechService**：它自己開 AudioRecord，
     * 外面拿不到原始波形，而聲學捷徑必須看波形。
     *
     * 麥克風一次只能被一個人拿著，所以這裡自己開一次 AudioRecord，
     * 同時餵給 Vosk 和 PCM 緩衝區。使用者只講一遍，兩條路都拿得到料。
     */
    private fun beginRecognition() {
        val m = model ?: return
        stopRequested.set(false)
        collected.clear()
        collectedWords.clear()
        collectedAlternatives.clear()
        pcm.clear()

        captureJob?.cancel()
        captureJob = scope.launch {
            var recorder: AudioRecord? = null
            var recognizer: Recognizer? = null
            try {
                // setWords(true) → 結果 JSON 帶 result[] 逐字 conf
                // setMaxAlternatives(N) → 結果 JSON 改帶 alternatives[]，前 N 名一起回。
                //   失語症口齒不清時第一名常聽錯，正確的常在第 2、3 名；
                //   多拿幾個交給 LLM 用情境挑。
                recognizer = Recognizer(m, SAMPLE_RATE.toFloat()).apply {
                    setWords(true)
                    setMaxAlternatives(N_BEST)
                }

                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minBuffer > 0) { "getMinBufferSize failed: $minBuffer" }
                @SuppressLint("MissingPermission")   // 呼叫端已檢查 RECORD_AUDIO
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    // 放大到 4 倍：太貼近下限時 UI 一卡就掉樣本，
                    // 而掉樣本對辨識與 DTW 都是不可見的破壞。
                    minBuffer * 4,
                )
                recorder = rec
                check(rec.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord init failed (state=${rec.state})"
                }
                rec.startRecording()
                check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord did not start (another app may hold the mic)"
                }
                withContext(Dispatchers.Main) { _sttState.value = SttState.Listening }
                Log.i(TAG, "Vosk listening started (own AudioRecord)")

                val chunk = ShortArray(minBuffer)
                while (!stopRequested.get() && pcm.size < MAX_SAMPLES) {
                    val read = rec.read(chunk, 0, chunk.size)
                    if (read < 0) error("AudioRecord.read returned $read")
                    if (read == 0) { yield(); continue }

                    for (i in 0 until read) pcm.add(chunk[i])
                    // acceptWaveForm 回 true＝Vosk 判定一段語音結束（自然停頓）
                    val ended = recognizer.acceptWaveForm(chunk, read)
                    if (ended) {
                        val r = recognizer.result
                        withContext(Dispatchers.Main) { voskListener.onResult(r) }
                    }
                    yield()
                }

                val finalJson = recognizer.finalResult
                withContext(Dispatchers.Main) { voskListener.onFinalResult(finalJson) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "capture loop failed", e)
                DiagnosticLog.e(DiagnosticLog.Cat.STT, "Vosk 收音失敗", t = e)
                withContext(Dispatchers.Main) {
                    _sttState.value = SttState.Error("無法啟動麥克風：${e.message}")
                }
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { recognizer?.close() }
            }
        }
    }

    /**
     * 這一次辨識的原始波形。onFinalResult 之前呼叫才有意義。
     *
     * 回 null 表示短到不可能是一次發聲——聲學比對拿到那種東西只會浪費時間。
     */
    private fun capturedUtterance(): com.speakmylife.app.aphasia.domain.model.Utterance? {
        if (pcm.size < SAMPLE_RATE / 10) return null      // 不足 100 ms
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        return com.speakmylife.app.aphasia.domain.model.Utterance(samples, SAMPLE_RATE)
    }

    fun stopListening() {
        if (_sttState.value is SttState.Listening) {
            _sttState.value = SttState.Processing
        }
        // 迴圈看到旗標就收尾，並在最後送出 finalResult
        stopRequested.set(true)
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopRequested.set(true)
        captureJob?.cancel()
        captureJob = null
        model?.close()
        model = null
        _sttState.value = SttState.Idle
    }

    /** 一次辨識的解析結果：最佳解文字、逐字信心、其餘 N-best 候選。 */
    private data class ParsedHypothesis(
        val text: String,
        val words: List<WordConf>,
        val alternatives: List<String>
    )

    /**
     * 解析 Vosk 結果。開了 setMaxAlternatives 之後 JSON 結構會變成
     * `{"alternatives":[{"text":..,"confidence":..,"result":[..]}, ...]}`，
     * 第一筆是最佳解；沒開或舊格式時退回原本的 `{"text":..,"result":[..]}`。
     */
    private fun parseResult(hypothesis: String?): ParsedHypothesis {
        val empty = ParsedHypothesis("", emptyList(), emptyList())
        if (hypothesis.isNullOrBlank()) return empty
        val trimmed = hypothesis.trim()
        if (!trimmed.startsWith("{")) {
            // 部分版本直接回傳純文字，無逐字信心
            return ParsedHypothesis(trimmed.replace(" ", "").trim(), emptyList(), emptyList())
        }
        return try {
            val o = JSONObject(trimmed)

            // N-best 格式：alternatives[0] 為最佳解，其餘為候選
            o.optJSONArray("alternatives")?.let { alts ->
                if (alts.length() == 0) return empty
                val best = alts.optJSONObject(0) ?: return empty
                val bestText = best.optString("text").replace(" ", "").trim()
                val others = mutableListOf<String>()
                for (i in 1 until alts.length()) {
                    alts.optJSONObject(i)?.optString("text")
                        ?.replace(" ", "")?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(others::add)
                }
                return ParsedHypothesis(bestText, parseWords(best), others)
            }

            // 舊/單一結果格式
            val text = o.optString("text").ifBlank { o.optString("partial") }
                .replace(" ", "").trim()
            ParsedHypothesis(text, parseWords(o), emptyList())
        } catch (e: Exception) {
            ParsedHypothesis(trimmed.replace(" ", "").trim(), emptyList(), emptyList())
        }
    }

    private fun parseWords(o: JSONObject): List<WordConf> {
        val words = mutableListOf<WordConf>()
        o.optJSONArray("result")?.let { arr ->
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                val word = w.optString("word").trim()
                if (word.isNotEmpty()) {
                    words.add(WordConf(word, w.optDouble("conf", 1.0).toFloat()))
                }
            }
        }
        return words
    }

    // ─── 模型管理（下載 + 解壓 + 驗證）────────────────────────────────

    /**
     * 準備 Vosk 模型。順序刻意是「先看已解壓的 → 再看 APK 內建 → 最後才上網」。
     *
     * 內建那一步是重點：以前這支只會上網下載，等於「從沒連過網的手機完全不能語音辨識」。
     * 對一個給失語症患者用的溝通工具，那是致命的——他可能正需要在沒有網路的地方講話。
     * 模型才 42MB，而 APK 裡本來就放了 1.5GB 的 ondevice-llm.task，多這一份不痛不癢。
     * 網路下載保留為後備（內建檔缺損時仍能自救）。
     */
    private fun ensureModelReady(spec: VoskModelSpec, onProgress: (Int) -> Unit): File {
        val baseDir = File(context.filesDir, "vosk").also { it.mkdirs() }
        val modelRoot = File(baseDir, spec.dirName)
        if (isModelValid(modelRoot)) return modelRoot

        val zip = File(baseDir, "model.zip")

        // ① APK 內建：完全離線可用，不需要任何網路
        val fromAsset = runCatching {
            onProgress(0)
            context.assets.open(spec.assetZip).use { input ->
                FileOutputStream(zip).use { out -> input.copyTo(out, 65_536) }
            }
            onProgress(60)
            unzip(zip, baseDir)
            zip.delete()
            onProgress(100)
            isModelValid(modelRoot)
        }.getOrElse {
            Log.w(TAG, "內建 Vosk 模型解壓失敗（${spec.dirName}），改用網路下載：${it.message}")
            runCatching { zip.delete() }
            false
        }
        if (fromAsset) {
            Log.i(TAG, "已使用 APK 內建的 Vosk 模型：${spec.dirName}（離線可用）")
            return modelRoot
        }

        // ② 後備：上網抓（內建檔缺損時自救）
        downloadFile(spec.url, zip, onProgress)
        unzip(zip, baseDir)
        zip.delete()

        if (isModelValid(modelRoot)) return modelRoot
        // 容錯：若內層資料夾名稱不同，尋找含 am + conf 的有效模型目錄
        baseDir.walkTopDown().firstOrNull {
            it.isDirectory && File(it, "am").exists() && File(it, "conf").exists()
        }?.let { return it }

        throw IllegalStateException("解壓後找不到有效的 Vosk 模型目錄")
    }

    private fun isModelValid(dir: File) =
        dir.isDirectory && File(dir, "am").exists() && File(dir, "conf").exists()

    private fun downloadFile(url: String, target: File, onProgress: (Int) -> Unit) {
        var conn = open(url)
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 6) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = open(loc)
            redirects++
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("下載失敗 HTTP $code")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(65_536)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    done += read
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        conn.disconnect()
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "SpeakMyLife/1.0 Android")
        connect()
    }

    private fun unzip(zip: File, destDir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { o ->
                        val buf = ByteArray(65_536)
                        var r: Int
                        while (zin.read(buf).also { r = it } != -1) o.write(buf, 0, r)
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    companion object {
        private const val TAG = "SpeechToTextManager"
        /** Hz。AudioRecord 要 Int，Vosk 的 Recognizer 要 Float。 */
        private const val SAMPLE_RATE = 16_000
        /**
         * 一次辨識最多收多久（樣本數，60 秒）。
         * 保險絲：使用者忘了按停止時，避免無上限地吃記憶體。
         */
        private const val MAX_SAMPLES = SAMPLE_RATE * 60
        /** 向 Vosk 要幾個候選；要太多會拖慢解碼，4 個足以涵蓋大部分聽錯的情況。 */
        private const val N_BEST = 4
        /** 實際往上送給重組 AI 的候選數（扣掉最佳解本身）。 */
        private const val MAX_ALTERNATIVES = 3
    }
}
