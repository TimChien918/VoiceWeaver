package com.speakmylife.app

import com.speakmylife.app.aphasia.data.acoustic.DtwMatcher
import com.speakmylife.app.aphasia.data.acoustic.MfccExtractor
import com.speakmylife.app.aphasia.data.local.VectorCodec
import com.speakmylife.app.aphasia.domain.model.Utterance
import org.junit.Test

/**
 * 產生「網頁版移植對照」的基準數字。
 *
 * 網頁端把整條 MFCC + DTW 管線移植成 JS（js/acoustic.js）。兩邊必須算出
 * **同一組數字**，否則手機錄的樣板在網頁上比對不起來——而樣板是跨裝置同步的。
 *
 * 難的地方是精度：Kotlin 用 Float（32 位元），JS 的 Number 是 64 位元。
 * JS 那邊全程用 Float32Array + Math.fround() 收斂，這支測試印出基準值供比對。
 *
 * 訊號用固定種子的 LCG 產生，兩邊逐位元相同——不能用亂數，否則比的是兩段
 * 不同的音，數字對不上也看不出是移植錯了還是輸入不同。
 */
class AcousticParityTest {

    /** 與 JS 端同一個 LCG（Numerical Recipes 參數），保證兩邊產生完全一樣的訊號。 */
    private fun signal(seed: Int, lengthMs: Int, freqHz: Double, noise: Double): FloatArray {
        val n = Utterance.SAMPLE_RATE_HZ * lengthMs / 1000
        val out = FloatArray(n)
        var s = seed
        for (i in 0 until n) {
            s = s * 1664525 + 1013904223
            val r = ((s ushr 8) and 0xFFFF) / 65535.0 * 2.0 - 1.0
            val t = i.toDouble() / Utterance.SAMPLE_RATE_HZ
            val tone = 0.6 * Math.sin(2 * Math.PI * freqHz * t) +
                       0.3 * Math.sin(2 * Math.PI * freqHz * 2 * t) +
                       0.1 * Math.sin(2 * Math.PI * freqHz * 3 * t)
            // 前後各 100ms 靜音，讓 trimSilence 這一步也進到比對範圍
            val env = if (t < 0.1 || t > (lengthMs / 1000.0 - 0.1)) 0.0 else 1.0
            out[i] = ((tone * env) + noise * r * env).toFloat()
        }
        return out
    }

    @Test
    fun `印出基準數字給 JS 端對照`() {
        val mfcc = MfccExtractor()
        val a = mfcc.extract(Utterance(signal(12345, 600, 220.0, 0.05)))
        val b = mfcc.extract(Utterance(signal(999, 600, 233.0, 0.05)))   // 稍微不同的音
        val c = mfcc.extract(Utterance(signal(12345, 600, 220.0, 0.05))) // 與 a 完全一樣

        println("PARITY dim=${a.frames.firstOrNull()?.size ?: 0}")
        println("PARITY a.frames=${a.frameCount}")
        println("PARITY b.frames=${b.frameCount}")
        // 印前 3 幀的前 4 維，位數夠多才看得出精度差異
        for (f in 0 until minOf(3, a.frameCount)) {
            val row = a.frames[f].take(4).joinToString(",") { "%.9g".format(it) }
            println("PARITY a.frame[$f]=$row")
        }
        println("PARITY dtw(a,a)=${"%.9g".format(DtwMatcher.distance(a, a))}")
        println("PARITY dtw(a,c)=${"%.9g".format(DtwMatcher.distance(a, c))}")
        println("PARITY dtw(a,b)=${"%.9g".format(DtwMatcher.distance(a, b))}")

        val sub = DtwMatcher.subsequenceDistance(a, b)
        println("PARITY sub(a in b)=${sub?.let { "%.9g@%d-%d".format(it.distance, it.startFrame, it.endFrame) } ?: "null"}")

        val thr = DtwMatcher.calibrateThreshold(listOf(a, b, c))
        println("PARITY threshold=${thr?.let { "%.9g".format(it) } ?: "null"}")
        println("PARITY factor(300,100)=${"%.9g".format(DtwMatcher.keywordThresholdFactor(300, 100))}")
        println("PARITY factor(100,100)=${"%.9g".format(DtwMatcher.keywordThresholdFactor(100, 100))}")

        // 這一段是「網頁能不能讀手機的樣板」的關鍵：blob 格式必須互通
        val blob = VectorCodec.encodeBundle(listOf(a, b))
        val b64 = java.util.Base64.getEncoder().encodeToString(blob)
        println("PARITY bundle.bytes=${blob.size}")
        println("PARITY bundle.b64.head=${b64.take(64)}")
        println("PARITY bundle.b64.len=${b64.length}")
    }
}
