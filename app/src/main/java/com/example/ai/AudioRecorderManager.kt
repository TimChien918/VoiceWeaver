package com.example.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 錄音管線：產出可直接餵給「音訊原生 LLM」的檔案。
 *
 * 為什麼要有這條路：失語症患者的構音常常不標準，STT（Vosk/Whisper）是拿正常語音
 * 訓練的，會把病人的話壓成一段錯誤的文字，語調、停頓、語音錯誤（paraphasia）
 * 這些真正帶著意義的線索全被丟掉——後面的 LLM 只能對著壞掉的文字硬猜。
 * 讓模型直接「聽」原始音訊可以保留這些資訊。
 *
 * 格式要看「誰要聽這段音訊」，兩邊接受的容器不一樣，用錯會被打回票：
 * - [Format.AAC_ADTS]：裸 AAC 串流（audio/aac）。Gemini 的 inline 音訊只收
 *   wav/mp3/aiff/aac/ogg/flac，**不收 audio/mp4**，所以原生音訊理解走這個。
 * - [Format.M4A]：MPEG-4 容器（audio/mp4）。Whisper 轉寫端點只收
 *   [flac mp3 mp4 mpeg mpga m4a ogg opus wav webm]，**不收裸 .aac**——
 *   實測 Groq 直接回 HTTP 400 file must be one of the following types。
 *
 * 兩者都是 16kHz 單聲道 AAC 編碼，差別只在外面那層容器。
 */
class AudioRecorderManager(private val context: Context) {

    /** 錄音容器。選錯會被服務端以「不支援的格式」擋掉，見類別註解。 */
    enum class Format(val ext: String) {
        /** 裸 AAC 串流；給 Gemini 原生音訊理解。 */
        AAC_ADTS("aac"),
        /** MPEG-4 容器；給 Whisper 轉寫端點。 */
        M4A("m4a")
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var isRecording = false
    private var startedAtMs = 0L
    private var peakPollJob: Job? = null
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 上一次錄音的峰值音量（0..32767）與長度（ms）。用途見 [SpeechNoiseGuard]——
     * 沒出聲的錄音不該送去辨識，Whisper 會對靜音幻覺出一整段話。
     *
     * 峰值一定要**邊錄邊輪詢**取最大值，不能只在停止前讀一次：
     * getMaxAmplitude() 回的是「距離上次呼叫以來」的最大值，而且 Android 文件
     * 明寫「setAudioSource() 之後的第一次呼叫一律回 0」。只在停止前讀一次的話，
     * 那次就是第一次呼叫 → 永遠拿到 0 → 每一次跟讀都會被誤判成沒出聲。
     * （這不是推測，是實機拍到的：放 TTS 給麥克風收，peak 還是 0。）
     */
    var lastPeakAmplitude: Int = 0
        private set
    var lastDurationMs: Long = 0L
        private set

    /**
     * 動態啟動錄音
     * @return 如果錄音順利啟動，回傳該暫存檔案的完整路徑實體，否則回傳 null。
     */
    suspend fun startRecording(format: Format = Format.AAC_ADTS): String? = withContext(Dispatchers.IO) {
        if (!hasAudioPermission()) {
            Log.e("AudioRecorderManager", "RECORD_AUDIO permission is missing!")
            return@withContext null
        }

        if (isRecording) {
            Log.w("AudioRecorderManager", "Already recording!")
            return@withContext currentOutputFile?.absolutePath
        }

        try {
            // 建立高安全性之 cache 緩存暫存檔
            val fileName = "aphasia_voice_${System.currentTimeMillis()}.${format.ext}"
            val tempFile = File(context.cacheDir, fileName)
            currentOutputFile = tempFile

            // 實例化 MediaRecorder (調用 API 31+ 新構造以健全架構)
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                // VOICE_RECOGNITION：套用系統的語音前處理（降噪／AGC），
                // 比 MIC 更適合近距離人聲，對含糊的發音幫助明顯。
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(
                    when (format) {
                        Format.AAC_ADTS -> MediaRecorder.OutputFormat.AAC_ADTS
                        Format.M4A -> MediaRecorder.OutputFormat.MPEG_4
                    }
                )
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(32000)
                setAudioSamplingRate(16000)
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            startedAtMs = System.currentTimeMillis()
            lastPeakAmplitude = 0
            lastDurationMs = 0L
            runCatching { recorder.maxAmplitude }   // 第一次固定回 0，先丟掉（見上方註解）
            peakPollJob = pollScope.launch {
                while (isActive) {
                    delay(PEAK_POLL_INTERVAL_MS)
                    val amp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    if (amp > lastPeakAmplitude) lastPeakAmplitude = amp
                }
            }
            Log.i("AudioRecorderManager", "Recording started successfully. Output: ${tempFile.absolutePath}")
            tempFile.absolutePath

        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to start MediaRecorder", e)
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            null
        }
    }

    /**
     * 終止錄音，回傳檔案暫存路徑
     * @return 錄製成功的音檔絕對路徑
     */
    suspend fun stopRecording(): String? = withContext(Dispatchers.IO) {
        if (!isRecording || mediaRecorder == null) {
            Log.w("AudioRecorderManager", "Recording was not active")
            return@withContext null
        }

        peakPollJob?.cancel()
        peakPollJob = null
        lastDurationMs = System.currentTimeMillis() - startedAtMs
        return@withContext try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            Log.i("AudioRecorderManager", "Recording stopped successfully.")
            currentOutputFile?.absolutePath
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error stopping MediaRecorder (clip may be too short)", e)
            null
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }

    private companion object {
        /** 100ms 夠密——一次跟讀至少幾百毫秒，不會整段錯過音量高點。 */
        const val PEAK_POLL_INTERVAL_MS = 100L
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
