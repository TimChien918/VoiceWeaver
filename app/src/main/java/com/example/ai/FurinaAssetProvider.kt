package com.example.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * **Furina 預設語音 / 背景圖片自動匯入器。**
 *
 * 之前的痛點：使用者要進「設定 → 開發者模式 → GPT-SoVITS → 匯入中文模型資料夾」逐個點 4 個按鈕、
 * 用 Android 系統檔案頁找檔案、再回來測試 — 學習曲線太高，每換一台機器都要重來。
 *
 * 解法：把預設參考音檔 (`ref_zh.wav`, `ref_en.wav`) 跟 prompt 文字內建在 `assets/voices/furina/`，
 * 第一次 App 啟動時自動複製到外部儲存區（`externalMediaDirs[0]/VoiceWeaver/voices/furina/`），
 * 然後把 `file://` URI 寫回 [com.example.data.SettingsRepository]。
 *
 * 設計選擇：
 *  - 不直接讀 `assets/` 目錄 — GPT-SoVITS API 要的是 `ref_audio_path` 檔系路徑，assets 在 ZIP 裡讀不出來
 *  - 不覆蓋已有設定 — 使用者如果之後手動換成自己錄的版本，這個 provider 不會把它清掉
 *  - 背景圖同樣處理 — 放在 `assets/backgrounds/furina/bg.png`，自動寫到 [com.example.data.AppSettings.customBackgroundUri]
 *  - **版權注意**：使用者需替換成自己有權使用的角色錄音才能取得對應音色
 *    （assets/voices/furina/README.md 有說明）。
 *
 * 呼叫方式：在 [com.example.ai.VoiceWeaverViewModel] 的 init 區塊或第一次需要 TTS 前呼叫 [ensureExtracted]；
 * 之後 [chineseRefAudioPath] / [englishRefAudioPath] / [backgroundImagePath] 會回實體檔案路徑。
 */
object FurinaAssetProvider {

    private const val TAG = "FurinaAsset"

    private const val ASSET_DIR_VOICE = "voices/furina"
    private const val ASSET_DIR_BG    = "backgrounds/furina"

    private const val VOICE_REF_ZH = "ref_zh.wav"
    private const val VOICE_REF_EN = "ref_en.wav"
    private const val VOICE_PROMPT_ZH = "prompt_zh.txt"
    private const val VOICE_PROMPT_EN = "prompt_en.txt"
    private const val BG_FILE = "bg.png"

    @Volatile private var extractedRoot: File? = null

    /**
     * 把 bundled 的 Furina 資源解出到外部儲存區（idempotent）。
     * 已存在 + 大小不變的檔案不會被覆蓋；使用者自訂的會原封不動。
     */
    fun ensureExtracted(context: Context): File {
        extractedRoot?.let { return it }
        synchronized(this) {
            extractedRoot?.let { return it }
            val root = File(
                context.externalMediaDirs.firstOrNull() ?: context.filesDir,
                "VoiceWeaver"
            )
            val voiceDir = File(root, "voices/furina").apply { mkdirs() }
            val bgDir    = File(root, "backgrounds/furina").apply { mkdirs() }
            // 逐檔複製：assets 拿到的 InputStream 我們不知道長度，所以照單全收一次。
            copyAssetIfAbsent(context, "$ASSET_DIR_VOICE/$VOICE_REF_ZH",    File(voiceDir, VOICE_REF_ZH))
            copyAssetIfAbsent(context, "$ASSET_DIR_VOICE/$VOICE_REF_EN",    File(voiceDir, VOICE_REF_EN))
            copyAssetIfAbsent(context, "$ASSET_DIR_VOICE/$VOICE_PROMPT_ZH", File(voiceDir, VOICE_PROMPT_ZH))
            copyAssetIfAbsent(context, "$ASSET_DIR_VOICE/$VOICE_PROMPT_EN", File(voiceDir, VOICE_PROMPT_EN))
            copyAssetIfAbsent(context, "$ASSET_DIR_BG/$BG_FILE",            File(bgDir, BG_FILE))
            Log.i(TAG, "Furina assets extracted to ${root.absolutePath}")
            extractedRoot = root
            return root
        }
    }

    /** 中文參考音檔絕對路徑。給 GPT-SoVITS API 的 `ref_audio_path` 用。*/
    fun chineseRefAudioPath(context: Context): String =
        File(ensureExtracted(context), "voices/furina/$VOICE_REF_ZH").absolutePath

    fun englishRefAudioPath(context: Context): String =
        File(ensureExtracted(context), "voices/furina/$VOICE_REF_EN").absolutePath

    /** 中文逐字稿（給 SoVITS prompt_text 用，提升音色一致性）*/
    fun chinesePromptText(context: Context): String =
        runCatching { File(ensureExtracted(context), "voices/furina/$VOICE_PROMPT_ZH").readText().trim() }
            .getOrDefault("")

    fun englishPromptText(context: Context): String =
        runCatching { File(ensureExtracted(context), "voices/furina/$VOICE_PROMPT_EN").readText().trim() }
            .getOrDefault("")

    /** 中文參考音檔的 `file://` URI 字串，供寫進 SettingsRepository.gptSovitChineseVoiceUri。*/
    fun chineseRefAudioUri(context: Context): String =
        Uri.fromFile(File(chineseRefAudioPath(context))).toString()

    fun englishRefAudioUri(context: Context): String =
        Uri.fromFile(File(englishRefAudioPath(context))).toString()

    /** 背景圖檔案 URI 字串。*/
    fun backgroundImageUri(context: Context): String =
        Uri.fromFile(File(ensureExtracted(context), "backgrounds/furina/$BG_FILE")).toString()

    private fun copyAssetIfAbsent(context: Context, assetPath: String, target: File) {
        if (target.exists() && target.length() > 0) return
        runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }.onFailure { Log.w(TAG, "Failed to copy asset $assetPath: ${it.message}") }
    }
}
