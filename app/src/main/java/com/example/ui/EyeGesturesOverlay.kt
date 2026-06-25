package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler

private const val TAG = "NoseTrackerOverlay"
private const val TRACKER_URL = "https://appassets.androidplatform.net/assets/nose_tracker.html"

/**
 * 隱藏式 WebView overlay，使用 MediaPipe FaceMesh 進行「鼻子／頭部」定位（Camera Mouse 式頭控）。
 *
 * - 鼻尖相對雙眼中心的位移（臉寬正規化）映射到游標座標，與距離無關、比虹膜凝視穩定準確。
 * - 透過 [JavascriptInterface] 把游標座標（0–1 正規化）和眨眼事件橋接回 Kotlin callback。
 * - 需要 CAMERA 權限（Android 端）和 WebChromeClient 授予 WebView 攝影機存取。
 * - WebView 需保持 VISIBLE（即使移到畫面外），否則 requestAnimationFrame 會被暫停、停止送幀。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EyeGesturesOverlay(
    enabled: Boolean,
    onGaze: (x: Float, y: Float, calibrated: Boolean) -> Unit,
    onBlink: () -> Unit,
    onStatus: (String) -> Unit = {},
    onReady: (WebView?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    val context = LocalContext.current
    val webView = remember { buildEyeGesturesWebView(context, onGaze, onBlink, onStatus) }

    DisposableEffect(webView) {
        onReady(webView)              // 把 WebView 交給呼叫方（重新置中要用 evaluateJavascript）
        onDispose {
            onReady(null)             // 清掉呼叫方持有的參考，避免操作已銷毀的 WebView
            webView.destroy()
        }
    }

    // WebView 必須保持 VISIBLE（rAF 才會持續送幀）；由呼叫方用 Modifier 把它移到畫面外/縮到極小。
    AndroidView(
        factory = { webView.apply { visibility = View.VISIBLE } },
        modifier = modifier
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildEyeGesturesWebView(
    context: Context,
    onGaze: (Float, Float, Boolean) -> Unit,
    onBlink: () -> Unit,
    onStatus: (String) -> Unit
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .setDomain("appassets.androidplatform.net")
        .addPathHandler("/assets/", AssetsPathHandler(context))
        .build()

    val dm = context.resources.displayMetrics
    val url = "$TRACKER_URL?w=${dm.widthPixels}&h=${dm.heightPixels}"

    return WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            allowFileAccess = false
            // ES module + dynamic import + WASM 需要此旗標
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url) ?: super.shouldInterceptRequest(view, request)
        }

        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // 授予 WebView 攝影機存取（已在 Android 端取得 CAMERA 權限）
                Log.d(TAG, "onPermissionRequest: ${request.resources.toList()}")
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    request.grant(request.resources)
                    Log.d(TAG, "camera permission GRANTED to WebView")
                } else {
                    request.deny()
                }
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val level = when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "E"
                    ConsoleMessage.MessageLevel.WARNING -> "W"
                    else -> "D"
                }
                Log.println(
                    if (level == "E") Log.ERROR else if (level == "W") Log.WARN else Log.DEBUG,
                    "EG_JS",
                    "${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                )
                return true
            }
        }

        addJavascriptInterface(
            AndroidBridge(
                gazeCb = onGaze,
                blinkCb = onBlink,
                statusCb = onStatus
            ),
            "AndroidBridge"
        )

        loadUrl(url)
    }
}

/** EyeGestures JS → Kotlin 橋接介面
 *
 * 注意：建構子參數改名（gazeCb/blinkCb/statusCb），避免與 @JavascriptInterface 方法同名
 * 導致 Kotlin 在方法內呼叫自身（無限遞迴）。
 */
private class AndroidBridge(
    private val gazeCb: (Float, Float, Boolean) -> Unit,
    private val blinkCb: () -> Unit,
    private val statusCb: (String) -> Unit
) {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @JavascriptInterface
    fun onGaze(x: Float, y: Float, calibrated: Int) {
        // StateFlow.value = ... 是執行緒安全的，可直接呼叫
        gazeCb(x, y, calibrated == 1)
    }

    @JavascriptInterface
    fun onBlink() {
        // Navigation 必須在主執行緒
        mainHandler.post { blinkCb() }
    }

    @JavascriptInterface
    fun onStatus(msg: String) {
        Log.d(TAG, "EG status: $msg")
        mainHandler.post { statusCb(msg) }
    }

    @JavascriptInterface
    fun onError(msg: String) {
        Log.e(TAG, "EG error: $msg")
        mainHandler.post { statusCb("眼動錯誤：$msg") }
    }
}
