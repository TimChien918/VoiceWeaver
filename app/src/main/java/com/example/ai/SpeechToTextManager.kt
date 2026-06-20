package com.example.ai

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
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

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
    private val onResult: (text: String, words: List<WordConf>) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private val collected = StringBuilder()
    private val collectedWords = mutableListOf<WordConf>()

    private val _sttState = MutableStateFlow<SttState>(SttState.Idle)
    val sttState: StateFlow<SttState> = _sttState.asStateFlow()

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) { /* 即時片段，暫不顯示 */ }

        override fun onResult(hypothesis: String?) {
            // 一段語音（自然停頓）結束 → 累積文字與逐字信心
            Log.d(TAG, "Vosk onResult raw: $hypothesis")
            val (text, words) = parseResult(hypothesis)
            if (text.isNotBlank()) collected.append(text)
            collectedWords.addAll(words)
        }

        override fun onFinalResult(hypothesis: String?) {
            Log.d(TAG, "Vosk onFinalResult raw: $hypothesis")
            val (text, words) = parseResult(hypothesis)
            if (text.isNotBlank()) collected.append(text)
            collectedWords.addAll(words)
            val finalText = collected.toString().trim()
            val finalWords = collectedWords.toList()
            collected.clear()
            collectedWords.clear()
            if (finalText.isNotEmpty()) onResult(finalText, finalWords)
            _sttState.value = SttState.Idle
        }

        override fun onError(e: Exception?) {
            Log.e(TAG, "Vosk recognition error", e)
            _sttState.value = SttState.Error("辨識失敗：${e?.message ?: "未知錯誤"}")
        }

        override fun onTimeout() {
            speechService?.stop()
        }
    }

    fun startListening() {
        val s = _sttState.value
        if (s is SttState.Listening || s is SttState.Preparing) return

        if (model != null) {
            beginRecognition()
            return
        }

        // 首次使用：準備模型（下載 + 載入）
        _sttState.value = SttState.Preparing("正在準備中文語音模型...")
        scope.launch {
            try {
                val modelDir = ensureModelReady { pct ->
                    _sttState.value = SttState.Preparing("下載中文語音模型 $pct%")
                }
                val loaded = Model(modelDir.absolutePath)
                withContext(Dispatchers.Main) {
                    model = loaded
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

    private fun beginRecognition() {
        val m = model ?: return
        try {
            // 每次重新建立 SpeechService，避免重用造成 AudioRecord 狀態異常
            speechService?.shutdown()
            speechService = null
            collected.clear()
            collectedWords.clear()

            // setWords(true) → 結果 JSON 帶 result[] 逐字 conf
            val recognizer = Recognizer(m, SAMPLE_RATE).apply { setWords(true) }
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(voskListener)
            _sttState.value = SttState.Listening
            Log.i(TAG, "Vosk listening started")
        } catch (e: Exception) {
            Log.e(TAG, "beginRecognition failed", e)
            _sttState.value = SttState.Error("無法啟動麥克風：${e.message}")
        }
    }

    fun stopListening() {
        if (_sttState.value is SttState.Listening) {
            _sttState.value = SttState.Processing
        }
        // stop() 會觸發 onFinalResult 回填結果
        speechService?.stop()
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        _sttState.value = SttState.Idle
    }

    /** 解析 Vosk 結果 → (純文字, 逐字信心列表) */
    private fun parseResult(hypothesis: String?): Pair<String, List<WordConf>> {
        if (hypothesis.isNullOrBlank()) return "" to emptyList()
        val trimmed = hypothesis.trim()
        if (!trimmed.startsWith("{")) {
            // 部分版本直接回傳純文字，無逐字信心
            return trimmed.replace(" ", "").trim() to emptyList()
        }
        return try {
            val o = JSONObject(trimmed)
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
            val text = o.optString("text").ifBlank { o.optString("partial") }
                .replace(" ", "").trim()
            text to words
        } catch (e: Exception) {
            trimmed.replace(" ", "").trim() to emptyList()
        }
    }

    // ─── 模型管理（下載 + 解壓 + 驗證）────────────────────────────────

    private fun ensureModelReady(onProgress: (Int) -> Unit): File {
        val baseDir = File(context.filesDir, "vosk").also { it.mkdirs() }
        val modelRoot = File(baseDir, MODEL_DIR_NAME)
        if (isModelValid(modelRoot)) return modelRoot

        val zip = File(baseDir, "model.zip")
        downloadFile(MODEL_URL, zip, onProgress)
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
        setRequestProperty("User-Agent", "VoiceWeaver/1.0 Android")
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
        private const val SAMPLE_RATE = 16000.0f
        private const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"
        // 公開模型，免帳號（已 curl 驗證 200 / application/zip / ~44MB）
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    }
}
