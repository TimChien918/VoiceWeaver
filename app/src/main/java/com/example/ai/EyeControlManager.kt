package com.example.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs

enum class EyeInteractionMode {
    GAZE_DWELL,
    BLINK_SCAN
}

data class EyeControlUiState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val mode: EyeInteractionMode = EyeInteractionMode.GAZE_DWELL,
    val scanIndex: Int = 0,
    val gazeX: Float = 0.5f,
    val gazeY: Float = 0.5f,
    val dwellProgress: Float = 0f,
    val message: String = "眼動控制未啟動",
    val lastBlinkAt: Long = 0L
)

class EyeControlManager(
    private val context: Context,
    private val onBlink: () -> Unit,
    private val onGaze: (x: Float, y: Float) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var faceLandmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var isRunning = false
    private var lastBlinkAt = 0L
    private var smoothedGazeX = 0.5f
    private var smoothedGazeY = 0.5f

    suspend fun start(): Result<Unit> = withContext(Dispatchers.Default) {
        if (isRunning) return@withContext Result.success(Unit)
        if (!hasCameraPermission()) {
            val msg = "需要相機權限才能啟用眼動控制"
            onStatus(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        ensureFaceModelAsset().getOrElse {
            val msg = "缺少 MediaPipe 模型：請放入 assets/models/face_landmarker.task"
            onStatus(msg)
            return@withContext Result.failure(it)
        }

        runCatching {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(ASSET_FACE_MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)

            val provider = getCameraProvider()
            cameraProvider = provider
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor) { proxy -> analyze(proxy) }
                }

            withContext(Dispatchers.Main) {
                provider.unbindAll()
                provider.bindToLifecycle(
                    ProcessLifecycleOwner.get(),
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analyzer
                )
            }
            isRunning = true
            onStatus("眼動控制已啟動：凝視字卡停留即可選取")
        }
    }

    suspend fun stop() = withContext(Dispatchers.Main) {
        isRunning = false
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        runCatching { faceLandmarker?.close() }
        faceLandmarker = null
        onStatus("眼動控制已停止")
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val bitmap = proxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = faceLandmarker?.detect(mpImage)
            val face = result?.faceLandmarks()?.firstOrNull()
            if (face != null) {
                estimateGaze(face)?.let { (x, y) ->
                    smoothedGazeX = smooth(smoothedGazeX, x)
                    smoothedGazeY = smooth(smoothedGazeY, y)
                    onGaze(smoothedGazeX, smoothedGazeY)
                }
                if (isBlink(face)) {
                    val now = System.currentTimeMillis()
                    if (now - lastBlinkAt > 900L) {
                        lastBlinkAt = now
                        onStatus("偵測到眨眼確認")
                        onBlink()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eye analysis failed: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    private fun isBlink(face: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Boolean {
        fun eyeRatio(top: Int, bottom: Int, left: Int, right: Int): Float {
            val vertical = abs(face[top].y() - face[bottom].y())
            val horizontal = abs(face[left].x() - face[right].x()).coerceAtLeast(0.001f)
            return vertical / horizontal
        }
        val left = eyeRatio(159, 145, 33, 133)
        val right = eyeRatio(386, 374, 362, 263)
        return left < 0.18f && right < 0.18f
    }

    private fun estimateGaze(
        face: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Pair<Float, Float>? {
        if (face.size <= 477) return null

        fun avgX(indices: IntArray): Float = indices.map { face[it].x() }.average().toFloat()
        fun avgY(indices: IntArray): Float = indices.map { face[it].y() }.average().toFloat()
        fun ratio(value: Float, min: Float, max: Float): Float =
            ((value - min) / (max - min).coerceAtLeast(0.001f)).coerceIn(0f, 1f)

        val leftIrisX = avgX(intArrayOf(468, 469, 470, 471, 472))
        val rightIrisX = avgX(intArrayOf(473, 474, 475, 476, 477))
        val leftEyeX = ratio(leftIrisX, face[33].x(), face[133].x())
        val rightEyeX = ratio(rightIrisX, face[362].x(), face[263].x())

        val leftIrisY = avgY(intArrayOf(468, 469, 470, 471, 472))
        val rightIrisY = avgY(intArrayOf(473, 474, 475, 476, 477))
        val leftEyeY = ratio(leftIrisY, face[159].y(), face[145].y())
        val rightEyeY = ratio(rightIrisY, face[386].y(), face[374].y())

        // 前鏡頭畫面通常左右鏡像；反轉 X 比較接近使用者眼睛往右看、游標也往右。
        val rawX = 1f - ((leftEyeX + rightEyeX) / 2f)
        val rawY = (leftEyeY + rightEyeY) / 2f
        val sensitivity = 1.55f
        val x = ((rawX - 0.5f) * sensitivity + 0.5f).coerceIn(0f, 1f)
        val y = ((rawY - 0.5f) * sensitivity + 0.5f).coerceIn(0f, 1f)
        return x to y
    }

    private fun smooth(old: Float, new: Float): Float = old * 0.72f + new * 0.28f

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                continuation.resume(future.get())
            }, ContextCompat.getMainExecutor(context))
        }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun ensureFaceModelAsset(): Result<Unit> = runCatching {
        context.assets.open(ASSET_FACE_MODEL).use { input ->
            check(input.available() > 100_000) { "Face landmarker asset is too small" }
        }
    }

    companion object {
        private const val TAG = "EyeControlManager"
        private const val ASSET_FACE_MODEL = "models/face_landmarker.task"
    }
}
