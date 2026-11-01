package com.speakmylife.app.work

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.speakmylife.app.data.DailyTrendCodec
import com.speakmylife.app.data.DailyTrendDay
import com.speakmylife.app.data.DailyTrendRow
import com.speakmylife.app.data.RehabLog
import com.speakmylife.app.ui.I18n
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
            putExtra(Intent.EXTRA_SUBJECT, I18n.tr("拾語者 復健成績報告"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 逐日趨勢表的欄位值。null（那天沒有該項資料）一律輸出空字串而不是 0——
     * 治療師在 Excel 畫圖時，0 會被當成真的數據點，把曲線拉到谷底。
     */
    private fun trendCells(r: DailyTrendRow): List<String> = listOf(
        r.day,
        r.avgReactionMs?.let { String.format(Locale.US, "%.1f", it / 1000.0) } ?: "",
        r.candTotal.toString(),
        r.candHitRate?.let { (it * 100).toInt().toString() } ?: "",
        r.rehabCount.toString(),
        r.rehabHitRate?.let { (it * 100).toInt().toString() } ?: "",
    )

    private fun trendHeader(): List<String> = listOf(
        I18n.tr("日期"), I18n.tr("平均反應時間（秒）"), I18n.tr("選句次數"),
        I18n.tr("選句命中率（%）"), I18n.tr("復健次數"), I18n.tr("復健命中率（%）"),
    )

    suspend fun exportCsv(
        context: Context,
        logs: List<RehabLog>,
        trend: List<DailyTrendDay> = emptyList(),
    ): Intent = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder("﻿")   // UTF-8 BOM，Excel 才不會中文亂碼
        // 每個文字欄位都要包引號並跳脫。原本只有目標句有包，場景卻是裸的——
        // 地點標籤是使用者自己打的（「台北, 大安」很正常），一個逗號就把
        // 那一列往後推一格，治療師打開來看到的分數會落在別的欄位。
        fun cell(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        sb.appendLine(listOf(
            I18n.tr("時間"), I18n.tr("場景"), I18n.tr("目標句"), I18n.tr("分數"),
        ).joinToString(",") { cell(it) })
        logs.sortedByDescending { it.timestamp }.forEach { l ->
            sb.appendLine(listOf(
                fmt.format(Date(l.timestamp)), l.locationTag, l.targetSentence, l.score.toString(),
            ).joinToString(",") { cell(it) })
        }
        // 第二段：復健軌跡（逐日）。空一行隔開，Excel 選取這一段就能直接插入折線圖。
        val rows = DailyTrendCodec.rows(trend, logs.map { it.timestamp to it.score })
        if (rows.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(cell(I18n.tr("復健軌跡（逐日）")))
            sb.appendLine(trendHeader().joinToString(",") { cell(it) })
            rows.forEach { r -> sb.appendLine(trendCells(r).joinToString(",") { cell(it) }) }
        }
        val file = File(dir(context), "Speak-My-Life_rehab_report.csv").apply { writeText(sb.toString()) }
        shareIntent(context, file, "text/csv")
    }

    suspend fun exportPdf(
        context: Context,
        logs: List<RehabLog>,
        trend: List<DailyTrendDay> = emptyList(),
    ): Intent = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
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
                c.drawText(I18n.tr("拾語者 復健成績報告"), margin, y, title); y += 30f
                c.drawText(I18n.tr("總練習 %1\$d 次    平均 %2\$d / 100").format(sorted.size, avg), margin, y, body); y += 26f
            }
            c.drawText(I18n.tr("時間"), margin, y, head)
            c.drawText(I18n.tr("目標句"), margin + 130f, y, head)
            c.drawText(I18n.tr("分數"), pageW - margin - 40f, y, head); y += lineH
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
        // 復健軌跡趨勢圖 + 逐日表（治療師列印出來追蹤用）
        val rows = DailyTrendCodec.rows(trend, logs.map { it.timestamp to it.score })
        if (rows.isNotEmpty()) drawTrendPages(doc, pageNo, rows, pageW, pageH, margin)
        val file = File(dir(context), "Speak-My-Life_rehab_report.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        shareIntent(context, file, "application/pdf")
    }

    /**
     * 趨勢頁：最近 30 個有資料的日子畫三張折線（反應時間、選句命中率、復健命中率），
     * 後面接完整的逐日表。回傳下一個可用的頁碼。
     */
    private fun drawTrendPages(
        doc: PdfDocument, firstPageNo: Int, rows: List<DailyTrendRow>,
        pageW: Int, pageH: Int, margin: Float,
    ): Int {
        var pageNo = firstPageNo
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
        val chartRows = rows.takeLast(30)
        val labels = chartRows.map { it.day.substring(5) }   // MM-dd
        val reaction = chartRows.map { r -> r.avgReactionMs?.let { it / 1000f } }
        val reactionMax = ((reaction.filterNotNull().maxOrNull() ?: 0f) * 1.2f).coerceAtLeast(1f)

        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        val c = page.canvas
        var y = margin + 10f
        c.drawText(I18n.tr("復健軌跡趨勢圖（逐日）"), margin, y, title); y += 30f
        val chartW = pageW - margin * 2
        val chartH = 160f
        drawLineChart(c, margin, y, chartW, chartH, I18n.tr("平均反應時間（秒）"), labels, reaction, reactionMax)
        y += chartH + 70f
        drawLineChart(c, margin, y, chartW, chartH, I18n.tr("選句命中率（%）"), labels,
            chartRows.map { r -> r.candHitRate?.let { it * 100f } }, 100f)
        y += chartH + 70f
        drawLineChart(c, margin, y, chartW, chartH, I18n.tr("復健命中率（%）"), labels,
            chartRows.map { r -> r.rehabHitRate?.let { it * 100f } }, 100f)
        doc.finishPage(page); pageNo++

        // 逐日表（可能跨頁）
        val head = Paint().apply { textSize = 9f; isFakeBoldText = true; isAntiAlias = true }
        val body = Paint().apply { textSize = 10f; isAntiAlias = true }
        val colX = floatArrayOf(0f, 80f, 175f, 245f, 335f, 405f)
        val lineH = 18f
        var idx = 0
        while (idx < rows.size) {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val cv = p.canvas
            var ty = margin + 10f
            trendHeader().forEachIndexed { i, h -> cv.drawText(h, margin + colX[i], ty, head) }
            ty += lineH
            while (idx < rows.size && ty < pageH - margin) {
                trendCells(rows[idx]).forEachIndexed { i, v -> cv.drawText(v, margin + colX[i], ty, body) }
                ty += lineH; idx++
            }
            doc.finishPage(p); pageNo++
        }
        return pageNo
    }

    /** 一張簡單折線圖：null 的日子不畫點，前後有資料的點才相連。 */
    private fun drawLineChart(
        c: android.graphics.Canvas,
        left: Float, top: Float, w: Float, h: Float,
        chartTitle: String, labels: List<String>, values: List<Float?>, yMax: Float,
    ) {
        val titleP = Paint().apply { textSize = 12f; isFakeBoldText = true; isAntiAlias = true }
        val grid = Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 1f }
        val line = Paint().apply {
            color = android.graphics.Color.rgb(0, 122, 255); strokeWidth = 2f; isAntiAlias = true
        }
        val dot = Paint().apply { color = android.graphics.Color.rgb(52, 199, 89); isAntiAlias = true }
        val small = Paint().apply { textSize = 8f; color = android.graphics.Color.DKGRAY; isAntiAlias = true }

        c.drawText(chartTitle, left, top, titleP)
        val axisW = 30f                       // 左側 Y 軸刻度的寬度
        val plotL = left + axisW
        val plotW = w - axisW
        val plotTop = top + 12f
        val bottom = plotTop + h
        for (i in 0..4) {
            val gy = bottom - h * i / 4f
            c.drawLine(plotL, gy, plotL + plotW, gy, grid)
            c.drawText(String.format(Locale.US, "%.0f", yMax * i / 4f), left, gy + 3f, small)
        }
        val n = values.size
        if (n == 0) return
        fun xAt(i: Int) = if (n > 1) plotL + i * plotW / (n - 1) else plotL + plotW / 2
        fun yAt(v: Float) = bottom - (v / yMax).coerceIn(0f, 1f) * h
        var prevX = 0f; var prevY = 0f; var hasPrev = false
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = xAt(i); val py = yAt(v)
            if (hasPrev) c.drawLine(prevX, prevY, x, py, line)
            c.drawCircle(x, py, 3f, dot)
            prevX = x; prevY = py; hasPrev = true
        }
        val every = ((n + 7) / 8).coerceAtLeast(1)
        labels.forEachIndexed { i, label ->
            if (i % every == 0 || i == n - 1) c.drawText(label, xAt(i) - 10f, bottom + 12f, small)
        }
    }
}
