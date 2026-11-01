package com.speakmylife.app.ai

/**
 * Drive 曲庫根資料夾「改名搬遷」的決策邏輯（純函式，不碰網路，方便單元測試）。
 *
 * 產品從 VoiceWeaver 改名為 Speak-My-Life 後，根資料夾也跟著改名。三端（Android、網頁、
 * 電腦端／Colab）用同一套規則：
 *  1. 先找新名 `Speak-My-Life` → 有就用（舊的 `VoiceWeaver` 即使也在，也不去動它）。
 *  2. 沒有新的、但有舊的 `VoiceWeaver` → 把舊資料夾**改名**成 `Speak-My-Life`
 *     （drive.file 範圍允許改這個 Google Cloud 專案自己建立的檔案）。
 *  3. 改名失敗 → 直接沿用舊資料夾。絕對不能因為改名失敗就另建一個空的新資料夾，
 *     那會讓使用者的曲庫「看起來消失」。
 *  4. 兩個都沒有 → [create] 為 true 才建新的。
 */
object DriveRootResolver {

    enum class Action {
        /** 已經有新名資料夾。 */
        USE_NEW,
        /** 舊資料夾成功改名為新名。 */
        RENAMED_LEGACY,
        /** 舊資料夾改名失敗，沿用舊名。 */
        USE_LEGACY,
        /** 兩個都沒有，新建了一個。 */
        CREATED,
        /** 兩個都沒有，且不允許建立（或建立失敗）。 */
        NONE,
    }

    data class Result(val id: String?, val action: Action)

    /**
     * @param findNew    找新名根資料夾的 id；沒有回 null
     * @param findLegacy 找舊名根資料夾的 id；沒有回 null（只有找不到新名時才會被呼叫）
     * @param rename     把指定 id 的資料夾改成新名；成功回 true
     * @param createNew  建立新名根資料夾，回 id；失敗回 null（只有 create=true 才會被呼叫）
     */
    fun resolve(
        create: Boolean,
        findNew: () -> String?,
        findLegacy: () -> String?,
        rename: (String) -> Boolean,
        createNew: () -> String?,
    ): Result {
        findNew()?.let { return Result(it, Action.USE_NEW) }
        val legacy = findLegacy()
        if (legacy != null) {
            val ok = runCatching { rename(legacy) }.getOrDefault(false)
            return Result(legacy, if (ok) Action.RENAMED_LEGACY else Action.USE_LEGACY)
        }
        if (!create) return Result(null, Action.NONE)
        val id = createNew() ?: return Result(null, Action.NONE)
        return Result(id, Action.CREATED)
    }
}
