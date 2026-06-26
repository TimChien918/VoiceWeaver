package com.example.work

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.data.RehabLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成績報告匯出：把復健日誌輸出成 CSV（給治療師用 Excel 開）或 PDF（列印/存檔），
 * 寫到 cache/reports 後用 FileProvider 產生可分享的 Intent。
 */
object ReportExporter {

    private fun dir(context: Context): File =
        File(context.cacheDir, "reports").apply { mkdirs() }

    private fun shareIntent(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VoiceWeaver 復健成績報告")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun exportCsv(context: Context, logs: List<RehabLog>): Intent = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN)
        val sb = StringBuilder("﻿")   // UTF-8 BOM，Excel 才不會中文亂碼
        sb.appendLine("時間,場景,目標句,分數")
        logs.sortedByDescending { it.timestamp }.forEach { l ->
            val safe = l.targetSentence.replace("\"", "\"\"")
            sb.appendLine("${fmt.format(Date(l.timestamp))},${l.locationTag},\"$safe\",${l.score}")
        }
        val file = File(dir(context), "rehab_report.csv").apply { writeText(sb.toString()) }
        shareIntent(context, file, "text/csv")
    }

    suspend fun exportPdf(context: Context, logs: List<RehabLog>): Intent = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN)
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 20f; isFakeBoldText = true }
        val head = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val body = Paint().apply { textSize = 11f }
        val pageW = 595; val pageH = 842; val margin = 40f; val lineH = 20f
        val sorted = logs.sortedByDescending { it.timestamp }
        val avg = if (sorted.isEmpty()) 0 else sorted.map { it.score }.average().toInt()

        var idx = 0; var pageNo = 1
        while (idx < sorted.size || pageNo == 1) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val c = page.canvas
            var y = margin + 10f
            if (pageNo == 1) {
                c.drawText("VoiceWeaver 復健成績報告", margin, y, title); y += 30f
                c.drawText("總練習：${sorted.size} 次   平均分：$avg / 100", margin, y, body); y += 26f
            }
            c.drawText("時間", margin, y, head)
            c.drawText("目標句", margin + 130f, y, head)
            c.drawText("分數", pageW - margin - 40f, y, head); y += lineH
            while (idx < sorted.size && y < pageH - margin) {
                val l = sorted[idx]
                c.drawText(fmt.format(Date(l.timestamp)), margin, y, body)
                c.drawText(l.targetSentence.take(18), margin + 130f, y, body)
                c.drawText(l.score.toString(), pageW - margin - 40f, y, body)
                y += lineH; idx++
            }
            doc.finishPage(page); pageNo++
            if (sorted.isEmpty()) break
        }
        val file = File(dir(context), "rehab_report.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        shareIntent(context, file, "application/pdf")
    }
}
