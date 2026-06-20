package com.example.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalAiMaintenanceManager(private val context: Context) {

    suspend fun cleanCachesAndBackground(engine: InferenceEngine?): CleanupReport = withContext(Dispatchers.IO) {
        var deletedBytes = 0L
        var deletedFiles = 0

        fun deleteFile(file: File) {
            if (!file.exists()) return
            val size = file.length()
            if (file.delete()) {
                deletedBytes += size
                deletedFiles += 1
            }
        }

        fun deleteDirContents(dir: File?) {
            dir?.listFiles().orEmpty().forEach { file ->
                val before = file.totalSize()
                if (file.deleteRecursively()) {
                    deletedBytes += before
                    deletedFiles += 1
                }
            }
            dir?.mkdirs()
        }

        runCatching { deleteDirContents(context.cacheDir) }
        runCatching { deleteDirContents(context.codeCacheDir) }

        val modelDir = MediaPipeLLMEngine.workingModelFile(context).parentFile
        modelDir?.listFiles().orEmpty().forEach { file ->
            if (file.name != MediaPipeLLMEngine.MODEL_FILENAME &&
                (file.name.endsWith(".tmp") ||
                 file.name.endsWith(".copying") ||
                 file.name.contains("xnnpack_cache"))
            ) {
                deleteFile(file)
            }
        }

        (engine as? HybridInferenceEngine)?.fallbackEngine
            ?.let { it as? MediaPipeLLMEngine }
            ?.release()
        (engine as? MediaPipeLLMEngine)?.release()

        System.gc()
        Runtime.getRuntime().gc()

        val report = CleanupReport(deletedFiles, deletedBytes)
        Log.i(TAG, "Local AI cleanup complete: $report")
        report
    }

    private fun File.totalSize(): Long =
        if (isFile) length() else walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val TAG = "LocalAiMaintenance"
    }
}

data class CleanupReport(
    val deletedFiles: Int,
    val deletedBytes: Long
) {
    val deletedMb: Long get() = deletedBytes / (1024 * 1024)
}
