package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
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

private const val TAG = "EyeGesturesOverlay"
private const val TRACKER_URL = "https://appassets.androidplatform.net/assets/eyegestures_tracker.html"

/**
 * 隱藏式 WebView overlay，使用 EyeGestures（Rust WASM + MediaPipe FaceMesh）進行眼動追蹤。
 *
 * - 完全背景運行（1×1 dp 不可見），不阻擋 Compose UI 觸控事件。
 * - 透過 [JavascriptInterface] 把凝視座標（0–1 正規化）和眨眼事件橋接回 Kotlin callback。
 * - 需要 CAMERA 權限（Android 端）和 WebChromeClient 授予 WebView 攝影機存取。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EyeGesturesOverlay(
    enabled: Boolean,
    onGaze: (x: Float, y: Float, calibrated: Boolean) -> Unit,
    onBlink: () -> Unit,
    onStatus: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    val context = LocalContext.current
    val webView = remember { buildEyeGesturesWebView(context, onGaze, onBlink, onStatus) }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier   // 呼叫方傳入 Modifier.size(1.dp) 讓 WebView 不佔畫面
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
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        addJavascriptInterface(
            AndroidBridge(onGaze, onBlink, onStatus),
            "AndroidBridge"
        )

        loadUrl(url)
    }
}

/** EyeGestures JS → Kotlin 橋接介面 */
private class AndroidBridge(
    private val onGaze: (Float, Float, Boolean) -> Unit,
    private val onBlink: () -> Unit,
    private val onStatus: (String) -> Unit
) {
    @JavascriptInterface
    fun onGaze(x: Float, y: Float, calibrated: Int) {
        onGaze(x, y, calibrated == 1)
    }

    @JavascriptInterface
    fun onBlink() {
        onBlink()
    }

    @JavascriptInterface
    fun onStatus(msg: String) {
        Log.d(TAG, "status: $msg")
        onStatus(msg)
    }

    @JavascriptInterface
    fun onError(msg: String) {
        Log.e(TAG, "error: $msg")
        onStatus("眼動錯誤：$msg")
    }
}
