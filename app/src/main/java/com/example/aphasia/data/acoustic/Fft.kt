package com.example.aphasia.data.acoustic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 原地 radix-2 Cooley–Tukey FFT。純 Kotlin，沒有 Android 相依，可以用 JVM 測試跑。
 *
 * 沒有引外部 DSP 函式庫：這裡只需要 2 的冪次長度的正向 FFT，
 * 為此拉一包相依（多半還帶 native .so）不划算。
 */
internal object Fft {

    /** 預先算好的旋轉因子，避免每幀重算 sin/cos。key 是 FFT 長度。 */
    private val twiddleCache = HashMap<Int, Pair<FloatArray, FloatArray>>()

    /**
     * @param real 長度必須是 2 的冪次，會被就地覆寫
     * @param imag 同上，呼叫前通常全為 0
     */
    fun transform(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n == imag.size) { "real/imag length mismatch" }
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, got $n" }
        if (n == 1) return

        // 位元反轉重排
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }

        val (cosTable, sinTable) = twiddles(n)
        var len = 2
        while (len <= n) {
            val step = n / len
            val half = len / 2
            var i = 0
            while (i < n) {
                var k = 0
                for (t in 0 until half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val ur = real[i + t]
                    val ui = imag[i + t]
                    val vr = real[i + t + half] * wr - imag[i + t + half] * wi
                    val vi = real[i + t + half] * wi + imag[i + t + half] * wr
                    real[i + t] = ur + vr
                    imag[i + t] = ui + vi
                    real[i + t + half] = ur - vr
                    imag[i + t + half] = ui - vi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun twiddles(n: Int): Pair<FloatArray, FloatArray> = synchronized(twiddleCache) {
        twiddleCache.getOrPut(n) {
            val half = n / 2
            val c = FloatArray(half)
            val s = FloatArray(half)
            for (k in 0 until half) {
                val angle = -2.0 * PI * k / n
                c[k] = cos(angle).toFloat()
                s[k] = sin(angle).toFloat()
            }
            c to s
        }
    }

    /** 大於等於 [x] 的最小 2 的冪次。 */
    fun nextPowerOfTwo(x: Int): Int {
        var n = 1
        while (n < x) n = n shl 1
        return n
    }
}
