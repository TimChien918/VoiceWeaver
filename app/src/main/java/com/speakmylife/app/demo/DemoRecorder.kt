package com.speakmylife.app.demo

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
    /**
     * 另外輸出一份**沒有聲音**的同一支影片（給簡報時自己口頭講解用）。
     *
     * 影像只編碼一次：同一批編好的 H.264 樣本同時寫進兩個 muxer，
     * 一個帶音訊、一個不帶。等於白拿第二個版本，不必再錄一次、也不必再等一次預錄。
     * 傳 null 就只輸出一份。
     */
    private val silentFile: File? = null,
    /** 第一張畫面真的寫進檔案時呼叫一次。演示要等到這時才開演，否則開頭會被吃掉。 */
    private val onFirstFrame: () -> Unit = {},
) {
    companion object {
        private const val TAG = "DemoRecorder"
        private const val SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 128_000
        private const val TIMEOUT_US = 10_000L

        // 等 EOS 的上限。stop() 只 join(3000) 就會往下 release codec，
        // 所以這裡一定要比它短，否則執行緒還在用的東西就被收掉了。
        private const val EOS_WAIT_MS = 2_000L

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

    // 無聲版：只有影像那一軌。它比主檔早一步就能開始（不必等音訊格式），
    // 但為了兩支影片起點一致，仍然用同一個 ptsBase。
    private var muxerB: MediaMuxer? = null
    private var videoTrackB = -1
    private var muxerBStarted = false

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
        muxerB = silentFile?.let {
            runCatching { MediaMuxer(it.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
                .onFailure { e -> Log.w(TAG, "無聲版建立失敗，只輸出一份：${e.message}") }
                .getOrNull()
        }

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
            "SpeakMyLifeDemo", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )

        if (wantAudio) startAudio()

        running.set(true)
        videoThread = Thread({ drain(vc, isVideo = true) }, "sml-rec-video").apply { start() }
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
        audioThread = Thread({ pumpAudio(rec, ac, minBuf, realRate, realCh) }, "sml-rec-audio").apply { start() }
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

                // 這一整塊 PCM **一個位元組都不能丟**。
                //
                // 之前寫成「拿不到輸入緩衝就跳過這一塊」——編碼器忙的時候那一塊就
                // 直接消失了，剩下的片段黏在一起。時間軸長度沒變（是我自己用取樣數
                // 算的），但內容少了一截，播出來就是被加速、被切碎、聽不懂；
                // 而喇叭當下放的是完整的，所以「播的時候正常、錄起來很怪」。
                //
                // 另外 put(buf, 0, n) 沒看過緩衝容量：n 比容量大就丟
                // BufferOverflowException，整條音訊執行緒當場陣亡，之後全是無聲。
                // 改成分塊寫、拿不到緩衝就先抽輸出再重試，不前進、也不丟。
                var off = 0
                while (off < n && running.get()) {
                    val inIdx = ac.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx < 0) {
                        while (drainOnce(ac, isVideo = false) == WROTE) { /* 騰出空間再試 */ }
                        continue
                    }
                    // 停止的瞬間 buffer 可能已經被回收 → 這裡拿得到 null。
                    val ib = ac.getInputBuffer(inIdx)
                    if (ib == null) { ac.queueInputBuffer(inIdx, 0, 0, 0, 0); continue }
                    ib.clear()
                    // 對齊到整數個取樣，免得一個取樣被切成兩半（左右聲道錯位＝雜音）
                    val room = (ib.remaining() / bytesPerFrame) * bytesPerFrame
                    val chunk = minOf(room, n - off)
                    if (chunk <= 0) { ac.queueInputBuffer(inIdx, 0, 0, 0, 0); continue }
                    ib.put(buf, off, chunk)
                    val pts = startUs + frames * 1_000_000L / rate
                    ac.queueInputBuffer(inIdx, 0, chunk, pts, 0)
                    frames += chunk / bytesPerFrame
                    off += chunk
                    while (drainOnce(ac, isVideo = false) == WROTE) { /* 順便抽乾 */ }
                }
            }
            // 收尾：送一個 end-of-stream，把編碼器裡剩下的資料吐乾淨
            val inIdx = ac.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val pts = (if (startUs < 0) System.nanoTime() / 1000 else startUs) +
                    frames * 1_000_000L / rate
                ac.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // drain() 的無限迴圈在音訊這一邊沒有出口（那個 signalEndOfInputStream
            // 分支只給影像用），拿不到 EOS 就會一直卡著，然後被 stop() 從底下抽掉
            // codec。這裡要的本來就只是「抽乾，但有期限」。
            awaitEos(ac, isVideo = false)
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
                awaitEos(codec, isVideo)
                return
            }
        }
    }

    /**
     * 等這一軌吐出 EOS，但**不會無限等**。
     *
     * 原本這裡是 `while (drainOnce(...) != EOS) {}`。編碼器不一定會給 EOS——
     * VirtualDisplay 先被收掉、codec 進了 error state、送 EOS 那一格排不進去，
     * 都會讓它永遠不來。而 stop() 只 join(3000) 就往下走，接著 release() 這顆
     * codec；此時這條執行緒還卡在 dequeueOutputBuffer 上，對已釋放的 codec 操作
     * 是直接在 native 層掛掉，整個 App 收掉。
     * 給它一個期限，過了就認賠離開——寧可影片尾巴少幾格，也不要當掉。
     */
    private fun awaitEos(codec: MediaCodec, isVideo: Boolean) {
        val deadline = System.currentTimeMillis() + EOS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (drainOnce(codec, isVideo) == EOS) return
        }
        Log.w(TAG, "等不到 ${if (isVideo) "影像" else "音訊"} EOS，直接收尾")
    }

    /** 抽一次輸出。回傳 NOTHING / WROTE / EOS。 */
    private fun drainOnce(codec: MediaCodec, isVideo: Boolean): Int {
        val info = MediaCodec.BufferInfo()
        // codec 進 error state（機型差異、資源被搶走）時這裡會丟。讓它冒到
        // 呼叫端的迴圈裡只會變成無窮重試，所以在這裡就當成 EOS 收掉。
        val idx = try {
            codec.dequeueOutputBuffer(info, TIMEOUT_US)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "編碼器已失效（${if (isVideo) "影像" else "音訊"}）：${e.message}")
            return EOS
        }
        when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                synchronized(muxLock) {
                    val track = muxer.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    val ready = videoTrack >= 0 && (!wantAudio || audioTrack >= 0)
                    if (ready && !muxerStarted) { muxer.start(); muxerStarted = true }
                    // 無聲版只收影像那一軌，加完就能開始
                    if (isVideo) muxerB?.let { mb ->
                        runCatching {
                            videoTrackB = mb.addTrack(codec.outputFormat)
                            mb.start(); muxerBStarted = true
                        }.onFailure { Log.w(TAG, "無聲版起不來：${it.message}") }
                    }
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
                                if (info.presentationTimeUs >= 0) {
                                    writeSample(track, out, info)
                                    // 同一份影像樣本再寫一次給無聲版。writeSample 會動到
                                    // buffer 的 position/limit，所以要重設過才能再寫一次。
                                    if (isVideo && muxerBStarted && videoTrackB >= 0) {
                                        muxerB?.let { mb ->
                                            out.position(info.offset)
                                            out.limit(info.offset + info.size)
                                            runCatching { mb.writeSampleData(videoTrackB, out, info) }
                                        }
                                    }
                                }
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
        // MP4 的索引（moov）是在 stop() 的時候才寫進去的。stop() 失敗＝檔案有內容
        // 但放不出來，而長度是好幾十 MB——上層用「存在且非空」判斷就會把它當成
        // 錄好了，發布到相簿裡變成一支點開是壞的影片。壞掉就直接刪，寧可少一份。
        var mainOk = false
        var silentOk = false
        synchronized(muxLock) {
            if (muxerStarted) {
                mainOk = runCatching { muxer.stop() }
                    .onFailure { Log.w(TAG, "muxer.stop 失敗：${it.message}") }.isSuccess
                muxerStarted = false
            }
            runCatching { muxer.release() }
            if (muxerBStarted) {
                silentOk = runCatching { muxerB?.stop() }
                    .onFailure { Log.w(TAG, "無聲版 stop 失敗：${it.message}") }.isSuccess
                muxerBStarted = false
            }
            runCatching { muxerB?.release() }
            muxerB = null
        }
        if (!mainOk) runCatching { outFile.delete() }
        if (!silentOk) silentFile?.let { runCatching { it.delete() } }
        return outFile.takeIf { mainOk && it.exists() && it.length() > 0 }
    }
}
