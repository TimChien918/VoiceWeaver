package com.speakmylife.app.ai

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
