package com.example.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自動演示的螢幕錄影。
 *
 * 為什麼一定要是前景服務：Android 14 起，`getMediaProjection()` **必須**在一個
 * `foregroundServiceType="mediaProjection"` 的前景服務已經在跑之後才呼叫，否則直接
 * 丟 SecurityException。所以流程固定是「先啟動這個服務 → 服務裡才取 projection」，
 * 不能在 Activity 裡先拿 projection 再啟動服務。
 *
 * 聲音走麥克風。要錄到 App 自己播出來的旁白，正規做法是 AudioPlaybackCapture，
 * 但那條路 MediaRecorder 接不上（得自己 AudioRecord + MediaCodec + Muxer 混流），
 * 為了一個演示功能不值得。手機喇叭放旁白、麥克風收音，安靜的房間裡就夠用。
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecord"
        private const val CHANNEL_ID = "vw_screen_record"
        private const val NOTIF_ID = 4711

        const val ACTION_START = "com.example.demo.START_RECORD"
        const val ACTION_STOP = "com.example.demo.STOP_RECORD"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_W = "w"
        private const val EXTRA_H = "h"
        private const val EXTRA_DPI = "dpi"
        private const val EXTRA_AUDIO = "audio"

        /** 錄完的檔案（給 UI 顯示存到哪）。 */
        private val _lastFile = MutableStateFlow<File?>(null)
        val lastFile: StateFlow<File?> = _lastFile.asStateFlow()

        private val _recording = MutableStateFlow(false)
        val recording: StateFlow<Boolean> = _recording.asStateFlow()

        /** 只啟動前景服務（還不取 projection）——Android 14 要求的先後順序。 */
        fun startForegroundOnly(ctx: Context) {
            val i = Intent(ctx, ScreenRecordService::class.java)
            ctx.startForegroundService(i)
        }

        fun beginRecording(
            ctx: Context, resultCode: Int, data: Intent,
            widthPx: Int, heightPx: Int, dpi: Int, withAudio: Boolean,
        ) {
            val i = Intent(ctx, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_W, widthPx)
                putExtra(EXTRA_H, heightPx)
                putExtra(EXTRA_DPI, dpi)
                putExtra(EXTRA_AUDIO, withAudio)
            }
            ctx.startForegroundService(i)
        }

        fun stopRecording(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenRecordService::class.java).apply { action = ACTION_STOP })
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    // Android 14 起 createVirtualDisplay 之前一定要註冊 callback，否則丟例外。
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 使用者從系統列停止分享 → 把檔案收好，不要就這樣丟掉
            teardown()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "螢幕錄影", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startInForeground()
    }

    private fun startInForeground() {
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceWeaver")
            .setContentText("Recording the demo…")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                if (data != null) {
                    runCatching {
                        begin(
                            code, data,
                            intent.getIntExtra(EXTRA_W, 1080),
                            intent.getIntExtra(EXTRA_H, 1920),
                            intent.getIntExtra(EXTRA_DPI, 320),
                            intent.getBooleanExtra(EXTRA_AUDIO, true),
                        )
                    }.onFailure {
                        Log.e(TAG, "開始錄影失敗：${it.message}", it)
                        _recording.value = false
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> { teardown(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent, w: Int, h: Int, dpi: Int, withAudio: Boolean) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)?.also { it.registerCallback(projectionCallback, null) }
            ?: throw IllegalStateException("拿不到 MediaProjection")

        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val f = File(dir, "VoiceWeaver-Demo-$stamp.mp4")
        outFile = f

        // 編碼尺寸取偶數：H.264 不吃奇數寬高，某些機型會直接 configure 失敗。
        val vw = (w / 2) * 2
        val vh = (h / 2) * 2

        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(f.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(vw, vh)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8_000_000)
            if (withAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
            }
            prepare()
        }

        display = projection!!.createVirtualDisplay(
            "VoiceWeaverDemo", vw, vh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder!!.surface, null, null
        )
        recorder!!.start()
        _recording.value = true
        Log.i(TAG, "開始錄影 → ${f.absolutePath}")
    }

    private fun teardown() {
        // 這一段每一步都可能丟例外（沒錄到任何影格就 stop 一定失敗），
        // 但一步失敗不能擋住後面的釋放，否則 projection 會一直掛著。
        runCatching { recorder?.stop() }.onFailure { Log.w(TAG, "stop 失敗：${it.message}") }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        _recording.value = false
        _lastFile.value = outFile?.takeIf { it.exists() && it.length() > 0 }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
