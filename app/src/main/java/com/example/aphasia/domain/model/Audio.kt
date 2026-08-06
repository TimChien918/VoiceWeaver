package com.example.aphasia.domain.model

/**
 * 一段擷取到的原始語音。
 *
 * 刻意用 PCM16 而非壓縮格式：MFCC 需要未經有損編碼的波形，
 * 既有的 AudioRecorderManager 走 MediaRecorder→AAC，對聲學比對不可用。
 */
data class Utterance(
    /** 單聲道 PCM16，已正規化到 [-1, 1] */
    val samples: FloatArray,
    val sampleRateHz: Int = SAMPLE_RATE_HZ,
    /** 錄音時的峰值振幅，用來擋靜音誤觸發 */
    val peakAmplitude: Float = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f,
) {
    val durationMs: Long get() = samples.size * 1000L / sampleRateHz

    override fun equals(other: Any?): Boolean =
        this === other || (other is Utterance &&
            samples.contentEquals(other.samples) && sampleRateHz == other.sampleRateHz)

    override fun hashCode(): Int = samples.contentHashCode() * 31 + sampleRateHz

    companion object {
        /** 16 kHz 是語音辨識與 MFCC 的通用取樣率，也是 Vosk 模型的輸入要求 */
        const val SAMPLE_RATE_HZ = 16_000
    }
}

/**
 * MFCC 特徵序列：[frames][coefficients]。
 * 這是 DTW 實際比對的對象——不是原始波形，波形長度與音量差異太大。
 */
data class FeatureSequence(
    val frames: Array<FloatArray>,
    val coefficientCount: Int = frames.firstOrNull()?.size ?: 0,
) {
    val frameCount: Int get() = frames.size

    override fun equals(other: Any?): Boolean =
        this === other || (other is FeatureSequence &&
            frames.size == other.frames.size &&
            frames.indices.all { frames[it].contentEquals(other.frames[it]) })

    override fun hashCode(): Int = frames.fold(7) { acc, f -> acc * 31 + f.contentHashCode() }
}
