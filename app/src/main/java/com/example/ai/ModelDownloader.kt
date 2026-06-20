package com.example.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 直接從公開 URL 下載 MediaPipe .task 模型，不需任何帳號或 API 金鑰。
 * 就像電腦執行 `wget URL` 一樣：有網路就能下載。
 *
 * 下載流程：
 *   1. GET MODEL_URL → 自動追蹤 HTTP 重定向（最多 6 次）
 *   2. 若回傳 ZIP 則自動解壓取出 .task / .bin 檔，否則直接儲存
 *   3. 支援中斷續傳（Range header），重試不用從頭來過
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        /**
         * 公開下載 URL 清單（依序嘗試，無需帳號或 API 金鑰）
         *
         * 採用 HuggingFace litert-community 的 Qwen2.5-1.5B-Instruct（Apache 2.0 授權，**非 gated**）。
         * 已實測可直接下載（302 → 公開 CDN 200），不像 Gemma 需接受 Google 條款。
         *
         * 模型為 MediaPipe LLM Inference 相容的 .task 格式（q8 量化，約 1.6 GB）。
         * 全部失敗時 UI 會顯示 ADB 指令，讓使用者手動安裝任何相容 .task。
         */
        val CANDIDATE_URLS = listOf(
            // Qwen2.5-1.5B q8 / KV1280（最小，~1.6 GB）
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task",
            // 備用：multi-prefill 版本
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        )

        private const val BUFFER_SIZE = 65_536   // 64 KB
        private const val CONNECT_TIMEOUT = 20_000
        private const val READ_TIMEOUT = 60_000
    }

    /**
     * 依序嘗試 [CANDIDATE_URLS]，成功後自動儲存並解壓縮。
     * 所有 URL 皆為公開資源，不需任何帳號或 API 金鑰。
     *
     * @param onProgress (progress 0-100, downloadedMb, totalMb)
     */
    suspend fun download(
        onProgress: (progress: Int, downloadedMb: Long, totalMb: Long) -> Unit
    ): Result<Unit> = downloadFromUrls(CANDIDATE_URLS, onProgress)

    suspend fun downloadFromUrl(
        url: String,
        onProgress: (progress: Int, downloadedMb: Long, totalMb: Long) -> Unit
    ): Result<Unit> {
        val cleanUrl = normalizeModelUrl(url)
        if (!cleanUrl.startsWith("https://huggingface.co/") && !cleanUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("請貼上公開 HTTPS 模型下載 URL"))
        }
        return downloadFromUrls(listOf(cleanUrl), onProgress)
    }

    suspend fun probeUrl(url: String): Result<ModelProbeResult> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeModelUrl(url)
        if (!cleanUrl.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("請貼上 HTTPS 模型 URL"))
        }
        var currentUrl = cleanUrl
        var conn: HttpURLConnection? = null
        try {
            repeat(6) {
                if (conn != null) return@repeat
                val c = openConn(currentUrl, resumeFrom = 0L, rangeProbe = true)
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location")
                        ?: throw IOException("重定向無 Location header")
                    c.disconnect()
                    currentUrl = location
                } else {
                    conn = c
                }
            }
            val finalConn = conn ?: throw IOException("重定向次數過多")
            val code = finalConn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }
            val total = parseTotalBytes(finalConn)
            val type = finalConn.contentType.orEmpty()
            finalConn.disconnect()
            Result.success(
                ModelProbeResult(
                    normalizedUrl = cleanUrl,
                    sizeMb = if (total > 0L) total / (1024 * 1024) else 0L,
                    contentType = type,
                    isTaskLikely = cleanUrl.substringBefore('?').endsWith(".task") || type.contains("octet")
                )
            )
        } catch (e: Exception) {
            conn?.disconnect()
            Result.failure(e)
        }
    }

    private fun normalizeModelUrl(url: String): String =
        url.trim()
            .replace("/blob/main/", "/resolve/main/")
            .replace("/blob/master/", "/resolve/master/")

    private suspend fun downloadFromUrls(
        urls: List<String>,
        onProgress: (progress: Int, downloadedMb: Long, totalMb: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetFile = MediaPipeLLMEngine.workingModelFile(context)
        val dir = targetFile.parentFile?.also { it.mkdirs() }
            ?: throw IOException("無法建立模型目錄")
        val tempFile   = File(dir, "model_dl.tmp")

        var lastError: Exception = IOException("無可用的下載 URL")

        // 依序嘗試每個候選 URL
        for ((index, url) in urls.withIndex()) {
            Log.i(TAG, "Trying URL ${index + 1}/${urls.size}: $url")
            try {
                onProgress(0, 0, 0)

                tryFetch(url, tempFile) { p, dl, total ->
                    onProgress((p * 0.85).toInt(), dl, total)
                }

                if (!isActive) throw CancellationException("cancelled")

                // 解壓縮或直接改名
                onProgress(88, tempFile.length() / (1024 * 1024), tempFile.length() / (1024 * 1024))
                val extracted = tryExtractZip(tempFile, targetFile)
                if (extracted) {
                    tempFile.delete()
                } else {
                    if (!tempFile.renameTo(targetFile)) throw IOException("檔案重新命名失敗")
                }

                if (!targetFile.exists() || targetFile.length() < 100_000L) {
                    throw IOException("模型檔案不完整（${targetFile.length()} bytes）")
                }

                val sizeMb = targetFile.length() / (1024 * 1024)
                onProgress(100, sizeMb, sizeMb)
                Log.i(TAG, "Model ready: ${targetFile.absolutePath} (${sizeMb}MB)")
                return@withContext Result.success(Unit)   // 成功 → 直接回傳

            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled — partial file kept for resume")
                throw e   // 取消不重試
            } catch (e: Exception) {
                Log.w(TAG, "URL ${index + 1} failed: ${e.message}")
                lastError = e
                tempFile.delete()
                targetFile.delete()
                // 繼續嘗試下一個 URL
            }
        }

        // 所有 URL 皆失敗
        Log.e(TAG, "All download URLs failed")
        Result.failure(lastError)
    }

    private suspend fun tryFetch(
        url: String,
        temp: File,
        onProgress: (Int, Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val resumeBytes = if (temp.exists()) temp.length() else 0L
        var currentUrl = url

        var conn: HttpURLConnection? = null
        repeat(6) {
            if (conn != null) return@repeat
            val c = openConn(currentUrl, resumeBytes)
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                    ?: throw IOException("重定向無 Location header")
                c.disconnect()
                currentUrl = location
            } else {
                conn = c
            }
        }

        val finalConn = conn ?: throw IOException("重定向次數過多")
        val code = finalConn.responseCode
        val isResume = code == HttpURLConnection.HTTP_PARTIAL

        if (code != HttpURLConnection.HTTP_OK && !isResume) {
            val errBody = runCatching { finalConn.errorStream?.reader()?.readText() ?: "" }.getOrDefault("")
            finalConn.disconnect()
            throw IOException(when (code) {
                401, 403 -> "HTTP $code 需要授權（嘗試下一個來源）"
                404      -> "HTTP 404 不存在（嘗試下一個來源）"
                else     -> "HTTP $code：${errBody.take(80)}"
            })
        }

        val contentLen = finalConn.contentLengthLong
        val totalBytes = if (isResume) resumeBytes + contentLen else contentLen.coerceAtLeast(0L)
        Log.i(TAG, "Downloading: ${totalBytes / 1_000_000}MB, resume=${resumeBytes / 1_000_000}MB")

        finalConn.inputStream.use { input ->
            FileOutputStream(temp, isResume).use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                var downloaded = if (isResume) resumeBytes else 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    if (!isActive) throw CancellationException("cancelled")
                    out.write(buf, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        onProgress(
                            (downloaded * 100 / totalBytes).toInt(),
                            downloaded / (1024 * 1024),
                            totalBytes / (1024 * 1024)
                        )
                    }
                }
            }
        }
        finalConn.disconnect()
    }

    /** @return true if extracted .task/.bin from zip */
    private fun tryExtractZip(zipFile: File, targetFile: File): Boolean = try {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name.endsWith(".task") || entry.name.endsWith(".bin"))) {
                    Log.i(TAG, "Extracting from zip: ${entry.name}")
                    FileOutputStream(targetFile).use { out ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (zip.read(buf).also { read = it } != -1) out.write(buf, 0, read)
                    }
                    return true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "Not a zip or extraction failed: ${e.message}")
        false
    }

    private fun parseTotalBytes(conn: HttpURLConnection): Long {
        val range = conn.getHeaderField("Content-Range")
        if (!range.isNullOrBlank()) return range.substringAfter('/').toLongOrNull() ?: conn.contentLengthLong
        return conn.contentLengthLong
    }

    private fun openConn(url: String, resumeFrom: Long, rangeProbe: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT
            readTimeout    = READ_TIMEOUT
            setRequestProperty("User-Agent", "VoiceWeaver/1.0 Android")
            if (rangeProbe) setRequestProperty("Range", "bytes=0-0")
            else if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            connect()
        }
}

data class ModelProbeResult(
    val normalizedUrl: String,
    val sizeMb: Long,
    val contentType: String,
    val isTaskLikely: Boolean
)
