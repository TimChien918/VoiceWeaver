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
import kotlin.math.PI

/**
 * 1‑Euro 濾波器：靜止時很穩（抖動小），移動時很跟手（延遲低）。
 * 比固定 EMA 更適合眼控游標——這是讓「義眼滑鼠」實用的關鍵之一。
 */
private class OneEuroFilter(
    var minCutoff: Float = 1.2f,
    var beta: Float = 0.015f,
    var dCutoff: Float = 1.0f
) {
    private var xPrev = Float.NaN
    private var dxPrev = 0f
    private var tPrev = 0L
    private fun alpha(cutoff: Float, dt: Float): Float {
        val r = 2.0 * PI * cutoff * dt
        return (r / (r + 1.0)).toFloat()
    }
    fun filter(x: Float, tNanos: Long): Float {
        if (xPrev.isNaN()) { xPrev = x; tPrev = tNanos; return x }
        val dt = ((tNanos - tPrev).coerceAtLeast(1L)) / 1e9f
        tPrev = tNanos
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * dxPrev
        dxPrev = dxHat
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * xPrev
        xPrev = xHat
        return xHat
    }
    fun reset() { xPrev = Float.NaN; dxPrev = 0f }
}

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

    // ── 凝視濾波 / 校準狀態 ───────────────────────────────────────
    private val euroX = OneEuroFilter()
    private val euroY = OneEuroFilter()
    // 中性中心（隱式校準）：使用者平均視線約等於螢幕中央 → 用很慢的 EMA 追蹤，
    // 之後以「相對中性中心的偏移」對應到螢幕，免除每人/相機位置的系統性偏差。
    private var neutralX = Float.NaN
    private var neutralY = Float.NaN
    @Volatile private var recenterRequested = false
    // X/Y 靈敏度可不同（垂直眼動幅度較小，給高一點）
    @Volatile var sensitivityX = 2.4f
    @Volatile var sensitivityY = 3.0f

    // ── 眨眼偵測狀態（以個人睜眼基線為準，避免向下看被誤判）─────────
    private var openBaseline = 0.30f      // 睜眼時的眼高比基線（持續學習）
    private var eyesClosed = false        // 需「先睜開再閉」才算一次眨眼
    private var lastBlinkAt = 0L

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

    /** 使用者請求「置中校準」：下一個睜眼影格的視線會被設為螢幕中央。 */
    fun recenter() { recenterRequested = true }

    private fun analyze(proxy: ImageProxy) {
        try {
            val bitmap = proxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = faceLandmarker?.detect(mpImage)
            val face = result?.faceLandmarks()?.firstOrNull()
            if (face == null || face.size <= 477) {
                // 沒偵測到臉 → 不動游標、重置濾波，避免亂跳
                euroX.reset(); euroY.reset()
                return
            }
            val raw = estimateGazeRaw(face) ?: return
            val (rawX, rawY, openness) = raw

            // 持續學習睜眼基線（只在「明顯睜開」時往上修，向下看也不會降太多）
            if (openness > openBaseline) openBaseline += (openness - openBaseline) * 0.05f
            else openBaseline += (openness - openBaseline) * 0.005f
            openBaseline = openBaseline.coerceIn(0.12f, 0.5f)

            val closedNow = openness < openBaseline * 0.45f       // 低於基線 45% 視為閉眼
            val openEnough = openness > openBaseline * 0.70f      // 高於 70% 視為睜開

            // ── 眨眼（確認鍵）：需「先睜開→閉上」一個完整循環，避免向下凝視被誤判 ──
            val now = System.currentTimeMillis()
            if (closedNow && !eyesClosed && openEnoughRecently && now - lastBlinkAt > 700L) {
                eyesClosed = true
                lastBlinkAt = now
                onStatus("偵測到眨眼確認")
                onBlink()
            }
            if (openEnough) { eyesClosed = false; openEnoughRecently = true }

            // ── 凝視游標：閉眼/半閉時「凍結」游標（gating），不更新位置避免跳動 ──
            if (closedNow) { euroX.reset(); euroY.reset(); return }

            // 隱式校準：用很慢的 EMA 追蹤中性中心；或使用者按了置中。
            if (recenterRequested || neutralX.isNaN()) {
                neutralX = rawX; neutralY = rawY; recenterRequested = false
            } else {
                neutralX += (rawX - neutralX) * 0.004f
                neutralY += (rawY - neutralY) * 0.004f
            }

            val cx = ((rawX - neutralX) * sensitivityX + 0.5f).coerceIn(0f, 1f)
            val cy = ((rawY - neutralY) * sensitivityY + 0.5f).coerceIn(0f, 1f)
            val t = System.nanoTime()
            onGaze(euroX.filter(cx, t), euroY.filter(cy, t))
        } catch (e: Exception) {
            Log.w(TAG, "Eye analysis failed: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    @Volatile private var openEnoughRecently = false

    private fun eyeOpenness(
        face: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Float {
        fun ratio(top: Int, bottom: Int, left: Int, right: Int): Float {
            val vertical = abs(face[top].y() - face[bottom].y())
            val horizontal = abs(face[left].x() - face[right].x()).coerceAtLeast(0.001f)
            return vertical / horizontal
        }
        return (ratio(159, 145, 33, 133) + ratio(386, 374, 362, 263)) / 2f
    }

    /** 回傳 (rawX, rawY, openness)。rawX/rawY 為虹膜在眼角內的比例（對頭部平移免疫）。 */
    private fun estimateGazeRaw(
        face: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Triple<Float, Float, Float>? {
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

        // 前鏡頭左右鏡像 → 反轉 X，使用者往右看游標也往右。
        val rawX = 1f - ((leftEyeX + rightEyeX) / 2f)
        val rawY = (leftEyeY + rightEyeY) / 2f
        return Triple(rawX, rawY, eyeOpenness(face))
    }

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
