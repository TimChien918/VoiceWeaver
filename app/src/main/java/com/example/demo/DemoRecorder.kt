package com.example.demo

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 演示錄影：畫面 ＋ **App 自己播出來的聲音**，寫成一個 MP4。
 *
 * 為什麼不用 MediaRecorder：它的音訊只能挑 AudioSource（麥克風那一類）。
 * 要錄旁白就只能讓喇叭放、麥克風收——房間的雜音、鍵盤聲、迴音全部一起錄進去，
 * 而且音量受環境影響。Android 10 起有 AudioPlaybackCapture 可以直接把
 * 「這個 App 播出去的音訊」接起來，但 MediaRecorder 接不上它，所以這裡自己做：
 *
 *   畫面：VirtualDisplay → MediaCodec(H264) 的輸入 Surface → MediaMuxer
 *   聲音：AudioRecord(playback capture) → MediaCodec(AAC) → MediaMuxer
 *
 * 換來的是乾淨的原始音訊，完全不開麥克風。
 * Android 10 以下沒有這個 API，就只錄畫面（不退回麥克風——那正是要避免的東西）。
 */
class DemoRecorder(
    private val projection: MediaProjection,
    private val widthPx: Int,
    private val heightPx: Int,
    private val dpi: Int,
    private val withAudio: Boolean,
    private val outFile: File,
    /** 第一張畫面真的寫進檔案時呼叫一次。演示要等到這時才開演，否則開頭會被吃掉。 */
    private val onFirstFrame: () -> Unit = {},
) {
    companion object {
        private const val TAG = "DemoRecorder"
        private const val SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 128_000
        private const val TIMEOUT_US = 10_000L

        // drainOnce 的三種結果。原本只回 Boolean（是不是 EOS），呼叫端就分不出
        // 「這次有抽到東西」和「暫時沒有東西」——想抽乾也寫不出正確的迴圈條件。
        private const val NOTHING = 0    // 這次沒有輸出（先讓出去，等下再來）
        private const val WROTE = 1      // 抽到並處理了一筆
        private const val EOS = 2        // 收到 end-of-stream，這一軌結束

        /** 這台裝置能不能把 App 自己的聲音直接錄進去。 */
        fun canCapturePlayback(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private lateinit var muxer: MediaMuxer
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val muxLock = Any()

    // 時間原點。**兩軌一定要共用同一個**，這是 A/V 同步的關鍵。
    //
    // Surface 輸入的影像編碼器給的 presentationTimeUs 是 CLOCK_MONOTONIC 的奈秒/1000，
    // 直接寫進 muxer 影片長度會爆掉（實測 mvhd 溢位成 214748 秒），所以一定要歸零。
    // 但如果兩軌**各自**歸零就會出另一個更難查的問題：錄影開始到編碼器吐出第一張畫面
    // 中間有一段（實測約 1.9 秒），那段時間音訊已經在錄了。各自歸零＝把音訊往前挪了
    // 1.9 秒，整支影片的旁白都比畫面早。
    //
    // 所以：原點取「第一張畫面」的時間戳，音訊也用同一個時鐘（System.nanoTime），
    // 比原點早的音訊直接丟掉——那是畫面還不存在時的前導，本來就不該留。
    @Volatile private var ptsBase = -1L

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var audioRecord: AudioRecord? = null

    private val running = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    /** 音軌要不要等——沒有音訊時 muxer 只等影像那一軌。 */
    private val wantAudio get() = withAudio && canCapturePlayback()

    fun start() {
        // 編碼尺寸取偶數：H.264 不吃奇數寬高，有些機型會直接 configure 失敗。
        val w = (widthPx / 2) * 2
        val h = (heightPx / 2) * 2

        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = vc.createInputSurface()
        vc.start()
        videoCodec = vc

        display = projection.createVirtualDisplay(
            "VoiceWeaverDemo", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )

        if (wantAudio) startAudio()

        running.set(true)
        videoThread = Thread({ drain(vc, isVideo = true) }, "vw-rec-video").apply { start() }
    }

    private fun startAudio() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            // 旁白走 MediaPlayer（USAGE_MEDIA），系統 TTS 依引擎可能落在 UNKNOWN／
            // ASSISTANCE_ACCESSIBILITY。三種都收，免得換一支引擎就沒聲音。
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()

        // ⚠️ 編碼器一定要用 AudioRecord **實際**拿到的取樣率與聲道數，不能用我們要求的。
        // 播放擷取（REMOTE_SUBMIX）是照裝置的混音率走的，多半是 48000；
        // 我們要 44100、它給 48000，卻還是標成 44100 寫進檔案，播出來就會整段
        // 變慢、音調變低——聽起來就是「聲音很奇怪」。
        val realRate = rec.sampleRate
        val realCh = rec.channelCount
        Log.i(TAG, "音訊擷取：${realRate} Hz / ${realCh} 聲道（要求 $SAMPLE_RATE / 1）")
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, realRate, realCh).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
        }
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        ac.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        ac.start()

        audioRecord = rec
        audioCodec = ac
        rec.startRecording()
        audioThread = Thread({ pumpAudio(rec, ac, minBuf, realRate, realCh) }, "vw-rec-audio").apply { start() }
    }

    /**
     * PCM → AAC 編碼器；同一條執行緒順便把編好的資料抽出去寫檔。
     *
     * 時間戳用「累計取樣數」算，不是用讀到的當下時間。read() 是一陣一陣回來的
     * （有時卡一下、有時連兩包），拿 wall clock 當時間戳等於把這些抖動寫進音軌，
     * 播出來會忽快忽慢、有顫音。從第一次讀到的時刻起算，之後每一包都是
     * 「起點 + 已累計取樣 / 取樣率」——間隔一定均勻，也和影像共用同一個時鐘原點。
     */
    private fun pumpAudio(rec: AudioRecord, ac: MediaCodec, bufSize: Int, rate: Int, channels: Int) {
        val buf = ByteArray(bufSize)
        val bytesPerFrame = 2 * channels          // PCM 16-bit
        var startUs = -1L
        var frames = 0L
        try {
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (startUs < 0) startUs = System.nanoTime() / 1000
                val inIdx = ac.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    // 停止的瞬間 buffer 可能已經被回收 → 這裡拿到 null。以前用 !! 直接炸，
                    // 音訊執行緒帶著例外結束，最後那一段 EOS 也就沒送出去。
                    val ib = ac.getInputBuffer(inIdx)
                    if (ib == null) { ac.queueInputBuffer(inIdx, 0, 0, 0, 0); continue }
                    ib.clear(); ib.put(buf, 0, n)
                    val pts = startUs + frames * 1_000_000L / rate
                    ac.queueInputBuffer(inIdx, 0, n, pts, 0)
                    frames += n / bytesPerFrame
                }
                // 抽乾，不是抽一次。抽不乾的話編碼器輸出會塞住，下一輪 read 被拖慢，
                // 擷取就會掉資料——聽起來是斷斷續續的破音。
                while (drainOnce(ac, isVideo = false) == WROTE) { /* 抽到沒有為止 */ }
            }
            // 收尾：送一個 end-of-stream，把編碼器裡剩下的資料吐乾淨
            val inIdx = ac.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val pts = (if (startUs < 0) System.nanoTime() / 1000 else startUs) +
                    frames * 1_000_000L / rate
                ac.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(ac, isVideo = false)
        } catch (t: Throwable) {
            Log.w(TAG, "音訊擷取結束：${t.message}")
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        while (true) {
            if (drainOnce(codec, isVideo) == EOS) return
            if (!running.get() && isVideo) {
                // 影像沒有「送 EOS」的入口（輸入是 Surface），只能用 signalEndOfInputStream
                runCatching { codec.signalEndOfInputStream() }
                while (drainOnce(codec, isVideo) != EOS) { /* 抽到 EOS 為止 */ }
                return
            }
        }
    }

    /** 抽一次輸出。回傳 NOTHING / WROTE / EOS。 */
    private fun drainOnce(codec: MediaCodec, isVideo: Boolean): Int {
        val info = MediaCodec.BufferInfo()
        val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                synchronized(muxLock) {
                    val track = muxer.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    val ready = videoTrack >= 0 && (!wantAudio || audioTrack >= 0)
                    if (ready && !muxerStarted) { muxer.start(); muxerStarted = true }
                }
            }
            idx >= 0 -> {
                val out = codec.getOutputBuffer(idx)
                if (out != null && info.size > 0 &&
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    synchronized(muxLock) {
                        if (muxerStarted) {
                            val track = if (isVideo) videoTrack else audioTrack
                            if (track >= 0) {
                                if (isVideo && ptsBase < 0) {
                                    ptsBase = info.presentationTimeUs
                                    runCatching { onFirstFrame() }
                                }
                                // 影像還沒出過任何一張 → 這段音訊是畫面出現前的前導，丟掉。
                                // 留著就得把它塞在負的時間軸上，播放器的行為各家不同。
                                if (ptsBase < 0) {
                                    codec.releaseOutputBuffer(idx, false)
                                    return WROTE   // 有處理（丟掉），可以繼續抽下一筆
                                }
                                info.presentationTimeUs -= ptsBase
                                if (info.presentationTimeUs >= 0) writeSample(track, out, info)
                            }
                        }
                        // muxer 還沒 start（另一軌的格式還沒來）就丟掉這幾格。
                        // 緩存起來聽起來比較好，但那幾格是開頭的黑畫面／靜音，
                        // 留著反而讓兩軌起點對不齊。
                    }
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return EOS
                return WROTE
            }
        }
        return if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) WROTE else NOTHING
    }

    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        runCatching { muxer.writeSampleData(track, buf, info) }
            .onFailure { Log.w(TAG, "寫入失敗：${it.message}") }
    }

    /** 停止並收尾。回傳檔案（沒錄到任何東西時回 null）。 */
    fun stop(): File? {
        if (!running.getAndSet(false)) return null
        runCatching { audioRecord?.stop() }
        runCatching { audioThread?.join(3000) }
        runCatching { videoThread?.join(3000) }
        runCatching { display?.release() }; display = null
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }; videoCodec = null
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }; audioCodec = null
        runCatching { audioRecord?.release() }; audioRecord = null
        synchronized(muxLock) {
            if (muxerStarted) {
                runCatching { muxer.stop() }.onFailure { Log.w(TAG, "muxer.stop 失敗：${it.message}") }
                muxerStarted = false
            }
            runCatching { muxer.release() }
        }
        return outFile.takeIf { it.exists() && it.length() > 0 }
    }
}
