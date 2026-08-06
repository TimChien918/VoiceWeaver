package com.example.aphasia.data.local

import android.content.Context
import android.media.MediaPlayer
import com.example.ai.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 預先合成好的句庫音檔（`assets/phrase_audio/<id>.m4a`）。
 *
 * 為什麼要預合成：句庫的 30 句是固定的，每次現場合成等於讓使用者為同一句話
 * 反覆等十幾秒，而且需要電腦端的 GPT-SoVITS 開著。預先產好之後，
 * 命中捷徑就能立刻播出——離線、零延遲、免額度。
 *
 * 自訂句子沒有預合成檔，[play] 會回 false，呼叫端再走一般的 TTS。
 */
class PrebuiltAudio(context: Context) {

    private val appContext = context.applicationContext

    /** 有哪些 id 有預合成檔。啟動時列一次就好，之後只查記憶體。 */
    private val available: Set<String> by lazy {
        runCatching {
            appContext.assets.list(DIR).orEmpty()
                .filter { it.endsWith(EXT) }
                .map { it.removeSuffix(EXT) }
                .toSet()
        }.onFailure {
            DiagnosticLog.w(DiagnosticLog.Cat.TTS, "預合成音檔目錄讀取失敗", it.toString())
        }.getOrDefault(emptySet())
    }

    fun has(id: String?): Boolean = id != null && id in available

    /**
     * 播出預合成的音檔。
     *
     * @return false 表示沒有這個 id 的檔案或播放失敗，呼叫端應改走一般 TTS。
     *   刻意不在失敗時自己遞迴到 TTS——這一層只管「有沒有現成的可以播」，
     *   要不要降級是呼叫端的決定。
     */
    suspend fun play(id: String?): Boolean {
        if (!has(id)) return false
        // 一定要離開主執行緒：openFd 與 prepare() 都是阻塞的磁碟 I/O，
        // 而這支是從 viewModelScope（Main）呼叫的。留在主執行緒上
        // 遇到冷啟動或大檔就會 ANR，而且症狀是「按了整個畫面卡住」，
        // 完全看不出跟播音檔有關。
        return withContext(Dispatchers.IO) {
            runCatching {
                suspendCancellableCoroutine { cont ->
                val player = MediaPlayer()
                val done = AtomicBoolean(false)
                fun finish(ok: Boolean) {
                    if (done.compareAndSet(false, true)) {
                        runCatching { player.release() }
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                cont.invokeOnCancellation { finish(false) }
                try {
                    appContext.assets.openFd("$DIR/$id$EXT").use { fd ->
                        player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                    player.setOnCompletionListener { finish(true) }
                    // 播放失敗一定要收掉，否則這個 coroutine 會永遠掛著，
                    // UI 的「說話中」也永遠不會消失。
                    player.setOnErrorListener { _, what, extra ->
                        DiagnosticLog.w(DiagnosticLog.Cat.TTS, "預合成音檔播放錯誤", "id=$id what=$what extra=$extra")
                        finish(false); true
                    }
                    player.prepare()
                    player.start()
                } catch (e: Exception) {
                    DiagnosticLog.w(
                        DiagnosticLog.Cat.TTS, "預合成音檔無法播放",
                        "id=$id ${e.javaClass.simpleName}: ${e.message}",
                    )
                    finish(false)
                }
                }
            }.getOrDefault(false)
        }
    }

    companion object {
        private const val DIR = "phrase_audio"
        private const val EXT = ".m4a"
    }
}
