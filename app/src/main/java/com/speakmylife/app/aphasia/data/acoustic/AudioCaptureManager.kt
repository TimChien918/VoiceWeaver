package com.speakmylife.app.aphasia.data.acoustic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.speakmylife.app.ai.DiagnosticLog
import com.speakmylife.app.aphasia.domain.model.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 16 kHz 單聲道 PCM16 錄音。
 *
 * 為什麼不用既有的 [com.speakmylife.app.ai.AudioRecorderManager]：那條路是
 * MediaRecorder → AAC，有損壓縮過的波形不能拿來算 MFCC——編碼器的心理聲學模型
 * 丟掉的正是頻譜細節，而那就是 MFCC 要看的東西。這裡必須拿到未經處理的取樣值。
 *
 * 兩者並存，不互相取代：AAC 那條給「錄下來給人聽／傳給伺服器」用，這條給聲學比對用。
 */
class AudioCaptureManager(private val context: Context) {

    private val _liveAmplitude = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val liveAmplitude: SharedFlow<Float> = _liveAmplitude.asSharedFlow()

    private val recording = AtomicBoolean(false)

    /** 目前是否正在錄音。UI 用來擋重複觸發。 */
    val isRecording: Boolean get() = recording.get()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 錄一段音，直到 [maxDurationMs] 或 [shouldStop] 回傳 true。
     *
     * @param shouldStop 每個緩衝區讀完後呼叫一次，讓 UI 可以做「放開結束」。
     *   預設錄滿為止。
     */
    suspend fun record(
        maxDurationMs: Long = DEFAULT_MAX_MS,
        shouldStop: () -> Boolean = { false },
    ): Result<Utterance> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext Result.failure(SecurityException("RECORD_AUDIO permission not granted"))
        }
        if (!recording.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Already recording"))
        }
        try {
            runCatching { captureBlocking(maxDurationMs, shouldStop) }
                .onFailure { DiagnosticLog.e(DiagnosticLog.Cat.STT, "PCM 錄音失敗", t = it) }
        } finally {
            recording.set(false)
        }
    }

    @SuppressLint("MissingPermission")   // hasPermission() 已在上面擋掉
    private suspend fun captureBlocking(maxDurationMs: Long, shouldStop: () -> Boolean): Utterance {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        check(minBuffer > 0) { "AudioRecord.getMinBufferSize failed: $minBuffer" }
        // 放大到 4 倍：太貼近下限時，UI 執行緒一卡就掉樣本，
        // 而掉樣本對 DTW 是不可見的破壞——距離會莫名變大，但沒有任何錯誤訊息。
        val bufferSize = minBuffer * 4

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,  // 這個 source 才會套用回音消除與降噪
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord init failed (state=${recorder.state})"
        }

        val maxSamples = (SAMPLE_RATE * maxDurationMs / 1000).toInt()
        val collected = ArrayList<ShortArray>()
        var total = 0
        val chunk = ShortArray(minBuffer)

        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not start (another app may hold the mic)"
            }
            while (total < maxSamples && !shouldStop()) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                    if (read < 0) error("AudioRecord.read returned $read")
                    break
                }
                collected.add(chunk.copyOf(read))
                total += read
                _liveAmplitude.tryEmit(peakOf(chunk, read))
                yield()   // 讓取消請求進得來，否則按下停止要等錄滿
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            _liveAmplitude.tryEmit(0f)
        }

        val samples = FloatArray(minOf(total, maxSamples))
        var idx = 0
        outer@ for (part in collected) {
            for (s in part) {
                if (idx >= samples.size) break@outer
                samples[idx++] = s / 32768f     // PCM16 → [-1, 1)
            }
        }
        DiagnosticLog.i(
            DiagnosticLog.Cat.STT,
            "PCM 錄音完成 ${samples.size} samples (${samples.size * 1000L / SAMPLE_RATE} ms)",
        )
        return Utterance(samples, SAMPLE_RATE)
    }

    private fun peakOf(buf: ShortArray, len: Int): Float {
        var peak = 0
        for (i in 0 until len) {
            val v = abs(buf[i].toInt())
            if (v > peak) peak = v
        }
        return peak / 32768f
    }

    companion object {
        const val SAMPLE_RATE = Utterance.SAMPLE_RATE_HZ
        const val DEFAULT_MAX_MS = 4_000L
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
