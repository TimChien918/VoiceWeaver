package com.example.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.data.CameraLens
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Task 2.2: VisionCaptureManager (單次無預覽視覺端側物件辨識組件)
 * 遵循 「Just-in-Time 且結束釋放」之極致效能架構。不綁定常規視覺 Preview 視窗。
 */
class VisionCaptureManager(private val context: Context) {

    private val cameraExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }

    // 初始化本機輕量化物件偵測與分類引擎 (ML Kit Thin Module)
    private val objectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE) // 靜態單張影格模式
            .enableMultipleObjects()                                  // 多物件框選
            .enableClassification()                                   // 類別自動標籤
            .build()
        ObjectDetection.getClient(options)
    }

    /**
     * 影像標籤器：物件偵測的預設分類器只有 5 個粗類（Fashion good / Food / Home good /
     * Place / Plant），對 AAC 完全不夠用——病人需要的是「水杯」「藥」「輪椅」這種具體詞。
     * ImageLabeling 有 400+ 個具體標籤，是這個場景真正該用的 API；物件偵測只留著補位。
     * 門檻 0.6：寧可少講幾個，也不要把不確定的東西講進病人的句子裡。
     */
    private val imageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MIN_LABEL_CONFIDENCE)
                .build()
        )
    }

    /**
     * 靜默啟動相機、擷取單張影像、實施 ML 辨識，隨後「立即自動執行 unbindAll()」關閉硬體。
     */
    suspend fun captureAndAnalyzeSingleFrame(
        lifecycleOwner: LifecycleOwner? = null,
        lens: CameraLens = CameraLens.Back
    ): List<String> = withContext(Dispatchers.Default) {
        if (lens == CameraLens.Both) {
            val backTags = captureOneLens(lifecycleOwner, CameraLens.Back)
            val frontTags = captureOneLens(lifecycleOwner, CameraLens.Front)
            return@withContext (backTags + frontTags).distinct().take(6)
        }
        return@withContext captureOneLens(lifecycleOwner, lens)
    }

    private suspend fun captureOneLens(
        lifecycleOwner: LifecycleOwner? = null,
        lens: CameraLens
    ): List<String> = withContext(Dispatchers.Default) {
        if (!hasCameraPermission()) {
            // 不回「影像權限未核准」當標籤——那會被當成「病人看到的東西」餵進重組 prompt。
            Log.w("VisionCaptureManager", "沒有相機權限，回傳空辨識結果")
            return@withContext emptyList()
        }

        // 若無傳入 LifecycleOwner，採用全域進程生命週期 ProcessLifecycleOwner (無預覽背景相機經典方案)
        val targetLifecycleOwner = lifecycleOwner ?: try {
            ProcessLifecycleOwner.get()
        } catch (e: Exception) {
            Log.e("VisionCaptureManager", "Failed to retrieve ProcessLifecycleOwner", e)
            return@withContext emptyList()
        }

        var cameraProvider: ProcessCameraProvider? = null
        try {
            // 1. 初始化 ProcessCameraProvider 異步核心
            cameraProvider = suspendCancellableCoroutine { continuation ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        continuation.resume(future.get())
                    } catch (e: Exception) {
                        Log.e("VisionCaptureManager", "Get ProcessCameraProvider failed", e)
                        continuation.resume(null)
                    }
                }, ContextCompat.getMainExecutor(context))
            }

            if (cameraProvider == null) {
                return@withContext emptyList()
            }

            // 2. 設置 ImageCapture 單次擷取管道
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // 延遲優先
                .build()

            // 3. 指定使用者選擇的前/後鏡頭
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lens.lensFacing ?: CameraSelector.LENS_FACING_BACK)
                .build()

            // 確保主執行緒操作相機綁定
            val isBound = withContext(Dispatchers.Main) {
                try {
                    cameraProvider.unbindAll() // 確保乾淨
                    cameraProvider.bindToLifecycle(
                        targetLifecycleOwner,
                        cameraSelector,
                        imageCapture
                    )
                    true
                } catch (e: Exception) {
                    Log.e("VisionCaptureManager", "Failed to bind CameraX lifecycle. Emulation mode active.", e)
                    false
                }
            }

            if (!isBound) {
                // 綁不到實體相機（模擬器 / 相機被占用）→ 回空清單。
                // 絕不編造看到的東西：這是 AAC 溝通輔具，捏造的物品會被組進病人「說出口」的句子。
                Log.w("VisionCaptureManager", "無法綁定相機，回傳空辨識結果（不編造）")
                return@withContext emptyList()
            }

            // 4. 單格快照擷取與分析並寫入協程回復
            val detectedTags = suspendCancellableCoroutine<List<String>> { continuation ->
                imageCapture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                            try {
                                @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                    val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                    analyzeImage(inputImage) { tags ->
                                        imageProxy.close()
                                        continuation.resume(tags)
                                    }
                                } else {
                                    imageProxy.close()
                                    continuation.resume(emptyList())
                                }
                            } catch (e: Exception) {
                                Log.e("VisionCaptureManager", "Processing image proxy error", e)
                                imageProxy.close()
                                continuation.resume(emptyList())
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("VisionCaptureManager", "Camera image capture error", exception)
                            continuation.resume(emptyList())
                        }
                    }
                )
            }

            return@withContext detectedTags

        } catch (e: Exception) {
            Log.e("VisionCaptureManager", "Vision analysis pipe exception", e)
            return@withContext emptyList()
        } finally {
            // CRITICAL: 擷取程序一經了結，在此處 unbindAll()，關閉鏡頭，熄滅硬體電源指示，保證省電環保！
            withContext(Dispatchers.Main) {
                try {
                    cameraProvider?.unbindAll()
                    Log.i("VisionCaptureManager", ">>> [CRITICAL RELEASE] unbindAll() successfully triggered, camera hardware shut down immediately.")
                } catch (e: Exception) {
                    Log.e("VisionCaptureManager", "Error inside unbindAll final release", e)
                }
            }
        }
    }

    /**
     * 同一張影格同時跑「影像標籤」與「物件偵測」，合併去重後回傳。
     * - 影像標籤（主力）：400+ 具體標籤，認得出杯子、藥、輪椅這類 AAC 真正需要的詞。
     * - 物件偵測（補位）：只有 5 個粗類，但偶爾標籤器沒抓到時能補一兩個大方向。
     * 兩邊都以信心度排序，只留 [MIN_LABEL_CONFIDENCE] 以上、最多 [MAX_TAGS] 個。
     */
    private fun analyzeImage(inputImage: InputImage, onDone: (List<String>) -> Unit) {
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Float>>())
        val pending = java.util.concurrent.atomic.AtomicInteger(2)

        fun finishOne() {
            if (pending.decrementAndGet() == 0) {
                val merged = results
                    .filter { VisionLabelZh.isUseful(it.first) }
                    .sortedByDescending { it.second }
                    .map { (label, conf) -> VisionLabelZh.toZh(label) to conf }
                    .distinctBy { it.first }
                    .take(MAX_TAGS)
                    .map { (label, conf) -> "$label (${"%.0f".format(conf * 100)}%)" }
                onDone(merged)
            }
        }

        imageLabeler.process(inputImage)
            .addOnSuccessListener { labels ->
                labels.forEach { results.add(it.text to it.confidence) }
            }
            .addOnFailureListener { e ->
                Log.e("VisionCaptureManager", "ImageLabeling 失敗", e)
            }
            .addOnCompleteListener { finishOne() }

        objectDetector.process(inputImage)
            .addOnSuccessListener { objects ->
                objects.flatMap { it.labels }
                    .filter { it.confidence >= MIN_LABEL_CONFIDENCE }
                    .forEach { results.add(it.text to it.confidence) }
            }
            .addOnFailureListener { e ->
                Log.e("VisionCaptureManager", "ObjectDetection 失敗", e)
            }
            .addOnCompleteListener { finishOne() }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /** 低於此信心度的標籤一律丟掉——寧可少講，也不要把猜的東西講進病人的句子。 */
        private const val MIN_LABEL_CONFIDENCE = 0.6f
        private const val MAX_TAGS = 5
    }
}
