package com.example.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Task 2.3: AudioRecorderManager (單純錄音管線)
 * 進行高保真 (MPEG_4 / AAC) 錄製，並將檔案放置於暫存資料夾供 Whisper 模型分析。
 * 運作於 Dispatchers.IO，確保不阻礙 Compose Main UI Thread。
 */
class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var isRecording = false

    /**
     * 動態啟動錄音
     * @return 如果錄音順利啟動，回傳該暫存檔案的完整路徑實體，否則回傳 null。
     */
    suspend fun startRecording(): String? = withContext(Dispatchers.IO) {
        if (!hasAudioPermission()) {
            Log.e("AudioRecorderManager", "RECORD_AUDIO permission is missing!")
            return@withContext null
        }

        if (isRecording) {
            Log.w("AudioRecorderManager", "Already recording!")
            return@withContext currentOutputFile?.absolutePath
        }

        try {
            // 建立高安全性之 cache 緩存暫存檔
            val fileName = "aphasia_whisper_input_${System.currentTimeMillis()}.m4a"
            val tempFile = File(context.cacheDir, fileName)
            currentOutputFile = tempFile

            // 實例化 MediaRecorder (調用 API 31+ 新構造以健全架構)
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            Log.i("AudioRecorderManager", "Recording started successfully. Output: ${tempFile.absolutePath}")
            tempFile.absolutePath

        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to start MediaRecorder", e)
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            null
        }
    }

    /**
     * 終止錄音，回傳檔案暫存路徑
     * @return 錄製成功的音檔絕對路徑
     */
    suspend fun stopRecording(): String? = withContext(Dispatchers.IO) {
        if (!isRecording || mediaRecorder == null) {
            Log.w("AudioRecorderManager", "Recording was not active")
            return@withContext null
        }

        return@withContext try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            Log.i("AudioRecorderManager", "Recording stopped successfully.")
            currentOutputFile?.absolutePath
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error stopping MediaRecorder (clip may be too short)", e)
            null
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
