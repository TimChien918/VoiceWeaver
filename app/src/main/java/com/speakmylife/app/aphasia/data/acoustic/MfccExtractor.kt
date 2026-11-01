package com.speakmylife.app.aphasia.data.acoustic

import com.speakmylife.app.aphasia.domain.model.FeatureSequence
import com.speakmylife.app.aphasia.domain.model.Utterance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * MFCC 特徵抽取：預強調 → 分幀加窗 → FFT → mel 濾波組 → log → DCT-II。
 *
 * 純 Kotlin、無 Android 相依，可用 JVM 單元測試驗證。
 * 沒有走 TFLite——這條管線是固定的 DSP，沒有可學參數，包成模型只會多一層開銷。
 */
class MfccExtractor(
    private val config: Config = Config(),
) {

    data class Config(
        val sampleRateHz: Int = Utterance.SAMPLE_RATE_HZ,
        val frameLengthMs: Int = 25,
        val frameShiftMs: Int = 10,
        val melFilterCount: Int = 26,
        /** 保留的倒頻譜係數個數（含 c0 之後才被丟掉，見 [includeC0]） */
        val cepstralCount: Int = 13,
        val lowFreqHz: Float = 20f,
        val highFreqHz: Float = 8_000f,
        val preEmphasis: Float = 0.97f,
        /**
         * c0 只反映整體響度。DTW 要比的是「發音長什麼樣」，
         * 而同一個人不同次錄音的音量本來就會差，留著 c0 等於把音量差當成發音差。
         */
        val includeC0: Boolean = false,
        /**
         * 倒頻譜平均正規化。**預設關閉**，這是量測後的決定，不是遺漏。
         *
         * CMN 扣掉的是「整段的平均」，所以它會依整段內容而變：
         * 短樣板（只有關鍵字）減掉的是關鍵字自己的平均，長句子減掉的是
         * 整句話的平均。兩邊減掉的東西不同 → 同一個音在兩邊變成不同的向量，
         * 關鍵字比對直接失效。實測（合成訊號，見 AcousticPipelineTest）：
         *
         *   CMN=true   有關鍵字 11.9 / 沒關鍵字  7.6   ← 大小關係是反的，不可用
         *   CMN=false  有關鍵字  0.2 / 沒關鍵字 18.9   ← 分離 76 倍
         *
         * 而 CMN 原本要解的「音量差」其實已經由 [includeC0] = false 解掉了：
         * 兩種設定下「音量差 5 倍」的距離都是 0.00001。
         *
         * 它還有一個作用是消掉麥克風／房間的固定通道效應，那個作用是真的，
         * 但登錄與使用都在同一支手機上，通道差本來就很小——用它換掉關鍵字
         * 比對不划算。整段比對模式若之後遇到跨裝置的情況，可以個別打開。
         */
        val cepstralMeanNormalization: Boolean = false,
        /** 加上一階差分，讓 DTW 也看得到「變化的方向」，對區分相近的音有幫助 */
        val includeDelta: Boolean = true,
        /** 前後靜音修剪的門檻，相對於全段最大幀能量 */
        val silenceTrimRatio: Float = 0.02f,
    ) {
        val frameLength: Int get() = sampleRateHz * frameLengthMs / 1000
        val frameShift: Int get() = sampleRateHz * frameShiftMs / 1000
        val fftSize: Int get() = Fft.nextPowerOfTwo(frameLength)
        /** 每幀最終維度 */
        val dimension: Int
            get() {
                val base = if (includeC0) cepstralCount else cepstralCount - 1
                return if (includeDelta) base * 2 else base
            }
    }

    private val hamming: FloatArray = FloatArray(config.frameLength) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (config.frameLength - 1))).toFloat()
    }

    private val melFilters: Array<FloatArray> = buildMelFilterBank()

    private val dctMatrix: Array<FloatArray> = Array(config.cepstralCount) { k ->
        FloatArray(config.melFilterCount) { m ->
            (cos(PI * k * (m + 0.5) / config.melFilterCount) *
                sqrt(2.0 / config.melFilterCount)).toFloat()
        }
    }

    fun extract(utterance: Utterance): FeatureSequence {
        require(utterance.sampleRateHz == config.sampleRateHz) {
            "Expected ${config.sampleRateHz} Hz, got ${utterance.sampleRateHz} Hz"
        }
        val emphasized = preEmphasize(utterance.samples)
        val (raw, energies) = frameAndTransform(emphasized)
        val frames = trimSilence(raw, energies)
        if (frames.isEmpty()) return FeatureSequence(emptyArray(), config.dimension)

        if (config.cepstralMeanNormalization) applyCmn(frames)
        val out = if (config.includeDelta) withDelta(frames) else frames
        return FeatureSequence(out, config.dimension)
    }

    // ── 各步驟 ────────────────────────────────────────────────────────────

    private fun preEmphasize(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        val y = FloatArray(x.size)
        y[0] = x[0]
        for (i in 1 until x.size) y[i] = x[i] - config.preEmphasis * x[i - 1]
        return y
    }

    /**
     * @return 特徵幀，以及每幀的能量（平行陣列，只給靜音修剪用）。
     *   刻意回傳而不是存成欄位——存欄位會讓 extract() 不能並行呼叫。
     */
    private fun frameAndTransform(signal: FloatArray): Pair<Array<FloatArray>, FloatArray> {
        val len = config.frameLength
        val shift = config.frameShift
        if (signal.size < len) return emptyArray<FloatArray>() to FloatArray(0)

        val frameCount = (signal.size - len) / shift + 1
        val fftSize = config.fftSize
        val result = ArrayList<FloatArray>(frameCount)
        val energies = FloatArray(frameCount)

        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val power = FloatArray(fftSize / 2 + 1)
        val melEnergies = FloatArray(config.melFilterCount)

        for (f in 0 until frameCount) {
            val offset = f * shift
            var energy = 0f
            java.util.Arrays.fill(real, 0f)
            java.util.Arrays.fill(imag, 0f)
            for (i in 0 until len) {
                val s = signal[offset + i]
                energy += s * s
                real[i] = s * hamming[i]
            }
            energies[f] = energy / len

            Fft.transform(real, imag)
            for (i in power.indices) power[i] = real[i] * real[i] + imag[i] * imag[i]

            for (m in 0 until config.melFilterCount) {
                var sum = 0f
                val filter = melFilters[m]
                for (i in filter.indices) sum += power[i] * filter[i]
                // 加 floor 避免 log(0)；1e-10 大約是 -100 dB，遠低於任何真實語音
                melEnergies[m] = ln(max(sum, 1e-10f))
            }

            val cep = FloatArray(config.cepstralCount)
            for (k in 0 until config.cepstralCount) {
                var sum = 0f
                val row = dctMatrix[k]
                for (m in 0 until config.melFilterCount) sum += melEnergies[m] * row[m]
                cep[k] = sum
            }
            result.add(if (config.includeC0) cep else cep.copyOfRange(1, cep.size))
        }
        return result.toTypedArray() to energies
    }

    /**
     * 砍掉前後的靜音幀。
     *
     * 對 DTW 這一步比看起來重要：使用者按下與放開按鈕的反應時間每次都不同，
     * 前後多出來的靜音會被 DTW 當成需要對齊的內容，直接汙染距離。
     */
    private fun trimSilence(frames: Array<FloatArray>, frameEnergies: FloatArray): Array<FloatArray> {
        if (frames.isEmpty() || frameEnergies.isEmpty()) return frames
        val peak = frameEnergies.max()
        if (peak <= 0f) return frames
        val threshold = peak * config.silenceTrimRatio

        var start = 0
        while (start < frames.size && frameEnergies[start] < threshold) start++
        var end = frames.size - 1
        while (end > start && frameEnergies[end] < threshold) end--
        if (start >= end) return frames  // 整段都在門檻下，交給上層的靜音閘去擋

        // 前後各留 2 幀，避免把塞音的起音（本來就低能量）切掉
        val s = max(0, start - 2)
        val e = minOf(frames.size - 1, end + 2)
        return frames.copyOfRange(s, e + 1)
    }

    private fun applyCmn(frames: Array<FloatArray>) {
        if (frames.isEmpty()) return
        val dim = frames[0].size
        val mean = FloatArray(dim)
        for (frame in frames) for (d in 0 until dim) mean[d] += frame[d]
        for (d in 0 until dim) mean[d] /= frames.size
        for (frame in frames) for (d in 0 until dim) frame[d] -= mean[d]
    }

    /** 一階差分，窗寬 ±2 幀的迴歸式。邊界用夾取而不是補零，避免製造假的變化。 */
    private fun withDelta(frames: Array<FloatArray>): Array<FloatArray> {
        val n = frames.size
        val dim = frames[0].size
        val denom = 2f * (1 + 4)  // 2 * Σ k², k = 1..2
        return Array(n) { t ->
            val out = FloatArray(dim * 2)
            frames[t].copyInto(out, 0)
            for (d in 0 until dim) {
                var sum = 0f
                for (k in 1..2) {
                    val ahead = frames[minOf(n - 1, t + k)][d]
                    val behind = frames[max(0, t - k)][d]
                    sum += k * (ahead - behind)
                }
                out[dim + d] = sum / denom
            }
            out
        }
    }

    private fun buildMelFilterBank(): Array<FloatArray> {
        val bins = config.fftSize / 2 + 1
        val melLow = hzToMel(config.lowFreqHz)
        val melHigh = hzToMel(minOf(config.highFreqHz, config.sampleRateHz / 2f))
        val points = FloatArray(config.melFilterCount + 2) { i ->
            melToHz(melLow + (melHigh - melLow) * i / (config.melFilterCount + 1))
        }
        val binOf = IntArray(points.size) { i ->
            ((points[i] / (config.sampleRateHz / 2f)) * (bins - 1)).toInt().coerceIn(0, bins - 1)
        }
        return Array(config.melFilterCount) { m ->
            val filter = FloatArray(bins)
            val left = binOf[m]
            val center = binOf[m + 1]
            val right = binOf[m + 2]
            for (i in left until center) {
                if (center > left) filter[i] = (i - left).toFloat() / (center - left)
            }
            for (i in center until right) {
                if (right > center) filter[i] = (right - i).toFloat() / (right - center)
            }
            if (center in filter.indices) filter[center] = 1f
            filter
        }
    }

    companion object {
        fun hzToMel(hz: Float): Float = (2595.0 * log10(1.0 + hz / 700.0)).toFloat()
        fun melToHz(mel: Float): Float = (700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)).toFloat()

        /** 給上層做音量檢查用，避免各處各自實作 */
        fun peakOf(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f
    }
}
