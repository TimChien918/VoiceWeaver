package com.example.future

/**
 * VTuber 發音教學的 Wrapper 介面。**這層只定義契約，不放具體實作**——
 * Cubism Android SDK 是商業 SDK，要簽 License 才能 link `.aar`，
 * 所以這裡只把 App 端會用到的方法挖出來，等 SDK 接進來再實作。
 *
 * 唇形同步整合路徑（未來工作）：
 *  1. 把 Live2D 的 `GLSurfaceView` 包進 [androidx.compose.ui.viewinterop.AndroidView]
 *  2. App 的 TtsModule 在 `onUtteranceProgressListener` 拿到當前音素 → 餵 [setMouthOpenness]
 *  3. 高耗能模式才啟動（會吃 GPU）；Saver mode 直接 [release]
 */
interface VTuberLipSync {
    /** SDK 初始化（讀模型檔、建立 GL context） */
    fun initialize(modelAssetPath: String)

    /**
     * 設定當前嘴形開合度。
     * @param value 0f（閉嘴）~ 1f（最大張口）
     */
    fun setMouthOpenness(value: Float)

    /** 釋放 GL / 模型資源 */
    fun release()
}

/**
 * 空殼實作，避免 App 主流程因為缺 SDK 而 NPE。
 * 真實 SDK 接好後，把 [com.example.MainActivity] 的注入指到 `CubismVTuberAdapter`（要新開檔案）。
 */
object NoopVTuberLipSync : VTuberLipSync {
    override fun initialize(modelAssetPath: String) = Unit
    override fun setMouthOpenness(value: Float) = Unit
    override fun release() = Unit
}
