package com.example.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
 * 聲音**不走麥克風**：旁白是 App 自己播出去的，用 AudioPlaybackCapture 直接接起來
 * （見 DemoRecorder），錄到的是乾淨的原始音訊，不會混進房間雜音。
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

        /** 錄完之後檔案在相簿裡的位置（給 UI 顯示存到哪）；失敗為 null。 */
        private val _lastPath = MutableStateFlow<String?>(null)
        val lastPath: StateFlow<String?> = _lastPath.asStateFlow()

        private val _recording = MutableStateFlow(false)
        val recording: StateFlow<Boolean> = _recording.asStateFlow()

        /** 畫面是否**已經真的**開始寫進檔案（不是「已呼叫 start」）。 */
        private val _capturing = MutableStateFlow(false)
        val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

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
    private var recorder: DemoRecorder? = null
    private var outFile: File? = null
    private var silentFile: File? = null

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
                NotificationChannel(CHANNEL_ID, com.example.ui.I18n.tr("螢幕錄影"), NotificationManager.IMPORTANCE_LOW)
            )
        }
        startInForeground()
    }

    private fun startInForeground() {
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceWeaver")
            .setContentText(com.example.ui.I18n.tr("正在錄製功能介紹…"))
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

        // 先寫進 App 私有的快取，停止時再複製到相簿看得到的 Movies/VoiceWeaver。
        // 直接寫 MediaStore 的 fd 也可以，但中途被砍時會留下一個 pending 的空項目
        // 掛在相簿裡；寫本地檔、成功才發布，失敗就什麼都不會出現。
        val f = File(cacheDir, "demo-recording.mp4")
        runCatching { f.delete() }
        outFile = f
        // 有旁白時，同一次錄影再多出一份無聲版（簡報時自己口頭講解用）。
        // 影像只編碼一次、同一批樣本寫進兩個 muxer，第二份幾乎不花成本，
        // 也不必再錄一次、再等一次旁白預錄。
        val silent = if (withAudio && DemoRecorder.canCapturePlayback())
            File(cacheDir, "demo-recording-silent.mp4").also { runCatching { it.delete() } } else null
        silentFile = silent

        _capturing.value = false
        val r = DemoRecorder(projection!!, w, h, dpi, withAudio, f, silent) { _capturing.value = true }
        r.start()
        recorder = r
        _recording.value = true
        Log.i(TAG, "開始錄影（音訊=${if (withAudio && DemoRecorder.canCapturePlayback()) "App 播放直錄" else "無"}）")
    }

    /**
     * 把錄好的檔案發布到相簿的 Movies/VoiceWeaver，回使用者看得到的顯示名稱。
     *
     * 放 MediaStore 而不是 Android/data/<套件>/files——後者在檔案管理員裡幾乎找不到，
     * 而這支影片的用途就是「拿出去給別人看」。
     */
    private fun publish(src: File, suffix: String = ""): String? {
        val name = "VoiceWeaver-Demo-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + suffix + ".mp4"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VoiceWeaver")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@runCatching null
            contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            src.delete()
            "Movies/VoiceWeaver/$name"
        }.getOrElse {
            Log.e(TAG, "發布到相簿失敗：${it.message}")
            null
        }
    }

    private fun teardown() {
        // 每一步都可能丟例外（沒錄到任何影格時尤其），但一步失敗不能擋住後面的釋放，
        // 否則 MediaProjection 會一直掛著、狀態列的錄影圖示也不會消失。
        val done = runCatching { recorder?.stop() }.getOrNull()
        recorder = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        _recording.value = false
        _capturing.value = false
        // 兩份一起發布。無聲版沒錄到東西就安靜跳過（例如 Android 10 以下沒有音訊擷取）。
        val hasSilent = silentFile?.takeIf { it.exists() && it.length() > 0 }
        _lastPath.value = done?.let { publish(it, if (hasSilent != null) "-narrated" else "") }
        hasSilent?.let { publish(it, "-silent") }
        silentFile = null
        if (done != null && _lastPath.value == null) {
            // 發布失敗也不要把檔案丟掉，至少留在快取裡還救得回來
            Log.w(TAG, "影片留在 ${done.absolutePath}")
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
