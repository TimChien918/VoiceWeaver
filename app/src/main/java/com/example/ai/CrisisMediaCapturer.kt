package com.example.ai

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 危機介入用的媒體擷取：前/後鏡頭照片（一次性快照）。
 *
 * 用途：偵測到高風險詞語時，靜默擷取現場（前鏡頭=使用者、後鏡頭=環境），
 * 透過 Telegram 傳給家人，讓家人快速掌握狀況。即時聲音/影像改用視訊通話（網頁跳轉），不在此錄音。
 *
 * 隱私：只在危機觸發當下擷取一次，檔案存 cacheDir，傳完即由呼叫端刪除。
 */
class CrisisMediaCapturer(private val context: Context) {

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    /** 擷取單一鏡頭照片存成 JPEG 檔；失敗回 null。 */
    suspend fun capturePhoto(facing: Int, outName: String): File? = withContext(Dispatchers.Main) {
        if (!hasCamera()) { Log.w(TAG, "無相機權限"); return@withContext null }
        val provider = runCatching {
            suspendCancellableCoroutine<ProcessCameraProvider?> { cont ->
                val f = ProcessCameraProvider.getInstance(context)
                f.addListener({ cont.resume(runCatching { f.get() }.getOrNull()) }, ContextCompat.getMainExecutor(context))
            }
        }.getOrNull() ?: return@withContext null

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        val owner = runCatching { ProcessLifecycleOwner.get() }.getOrNull() ?: return@withContext null

        val bound = runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, imageCapture)
            true
        }.getOrDefault(false)
        if (!bound) { Log.w(TAG, "綁定鏡頭失敗 facing=$facing"); return@withContext null }

        val out = File(context.cacheDir, outName)
        val opts = ImageCapture.OutputFileOptions.Builder(out).build()
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            imageCapture.takePicture(
                opts, ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { cont.resume(true) }
                    override fun onError(e: ImageCaptureException) { Log.e(TAG, "拍照失敗", e); cont.resume(false) }
                }
            )
        }
        runCatching { provider.unbindAll() }
        if (ok && out.length() > 0) out else null
    }

    /** 同時擷取後鏡頭（環境）與前鏡頭（使用者），回傳成功的檔案清單。 */
    suspend fun captureBothCameras(): List<File> {
        val files = mutableListOf<File>()
        capturePhoto(CameraSelector.LENS_FACING_BACK, "crisis_back_${stamp()}.jpg")?.let { files += it }
        capturePhoto(CameraSelector.LENS_FACING_FRONT, "crisis_front_${stamp()}.jpg")?.let { files += it }
        return files
    }

    // ─── 語音訊息：使用者主動錄一段短語音傳給家人 ───────────────────────────────

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null

    private fun hasMic() = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /** 開始錄製語音訊息（AAC/m4a）。回傳是否成功。 */
    fun startVoice(): Boolean {
        if (!hasMic()) { Log.w(TAG, "無麥克風權限"); return false }
        stopVoice()
        val out = File(context.cacheDir, "crisis_voice_${stamp()}.m4a")
        return runCatching {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                      else @Suppress("DEPRECATION") MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(out.absolutePath)
            rec.prepare(); rec.start()
            recorder = rec; audioFile = out
            true
        }.getOrElse { Log.e(TAG, "語音錄製啟動失敗", it); false }
    }

    /** 停止錄製並回傳語音檔（太短或失敗回 null）。 */
    fun stopVoice(): File? {
        val rec = recorder ?: return null
        return runCatching {
            rec.stop(); rec.release(); recorder = null
            audioFile?.takeIf { it.exists() && it.length() > 0 }
        }.getOrElse { runCatching { rec.release() }; recorder = null; null }
    }

    private fun stamp() = System.nanoTime()

    companion object { private const val TAG = "CrisisMedia" }
}
