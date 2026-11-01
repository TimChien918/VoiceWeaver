package com.speakmylife.app.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SharedPreferences 檔名改名（原名 VoiceWeaver 的 `vw_*` → `sml_*`）的一次性搬遷。
 *
 * 只處理「同一個 App 內」的舊檔：applicationId 改成 com.speakmylife.app 之後是全新的 App、
 * 全新的資料目錄，舊 App 的本機檔案本來就讀不到——那部分靠登入同一個 Google 帳號、
 * 由 Firebase 雲端同步取回。
 *
 * 規則：新檔是空的才把舊檔內容搬過去（不覆蓋新資料）；不論有沒有搬，搬完都刪掉舊檔，
 * 所以只會發生一次、而且冪等。
 */
object LegacyPrefsMigration {

    private val checked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 取得 [name] 這份 SharedPreferences；第一次開啟時順手把 [legacyName] 的舊資料搬過來。 */
    fun open(context: Context, name: String, legacyName: String): SharedPreferences {
        val ctx = context.applicationContext ?: context
        val prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (checked.add(name)) runCatching { migrate(ctx, prefs, legacyName) }
        return prefs
    }

    private fun migrate(ctx: Context, target: SharedPreferences, legacyName: String) {
        val dataDir = ctx.applicationInfo?.dataDir ?: return
        // 先看檔案在不在：直接 getSharedPreferences(legacyName) 會憑空建出一個空的舊檔
        if (!File(File(dataDir, "shared_prefs"), "$legacyName.xml").exists()) return
        val old = ctx.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        if (target.all.isEmpty()) {
            val e = target.edit()
            for ((k, v) in old.all) {
                when (v) {
                    is String -> e.putString(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
            // 寫入新檔失敗就先別刪舊檔，下次啟動再試
            if (!e.commit()) return
        }
        old.edit().clear().commit()
        ctx.deleteSharedPreferences(legacyName)
    }
}
