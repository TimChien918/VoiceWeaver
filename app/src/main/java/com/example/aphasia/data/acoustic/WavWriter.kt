package com.example.aphasia.data.acoustic

import com.example.aphasia.domain.model.Utterance
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 [Utterance] 寫成 16-bit PCM WAV。
 *
 * 為什麼需要它：聲學捷徑比對必須拿未壓縮的波形（MFCC 看的正是有損編碼會丟掉的
 * 頻譜細節），所以收音改成 PCM。但沒命中捷徑時還是要把同一段錄音送給音訊模型，
 * 而那些 API 吃的是檔案——WAV 只是 44 位元組表頭加上原始取樣值，
 * 不需要 MediaCodec，也不會再損失一次。
 *
 * 檔案大約 32 KB/秒（16 kHz 單聲道 16-bit），5 秒約 160 KB。
 * 比 AAC 大，但一次只送幾秒，換來的是同一份資料兩邊共用、不必編碼兩次。
 */
object WavWriter {

    private const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    fun write(utterance: Utterance, target: File): File {
        val samples = utterance.samples
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

        val byteRate = utterance.sampleRateHz * CHANNELS * BITS_PER_SAMPLE / 8

        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataBytes)          // 整個檔案扣掉 "RIFF" 與這個欄位本身
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)                       // PCM 的 fmt chunk 長度
        buf.putShort(1)                      // audioFormat = 1 (PCM)
        buf.putShort(CHANNELS.toShort())
        buf.putInt(utterance.sampleRateHz)
        buf.putInt(byteRate)
        buf.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())  // blockAlign
        buf.putShort(BITS_PER_SAMPLE.toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataBytes)

        for (s in samples) {
            // 夾取後再轉：正規化過程可能讓少數取樣略微超過 ±1，
            // 直接乘會整數溢位變成反相的爆音。
            val clamped = s.coerceIn(-1f, 1f)
            buf.putShort((clamped * 32767f).toInt().toShort())
        }

        target.parentFile?.mkdirs()
        target.writeBytes(buf.array())
        return target
    }
}
