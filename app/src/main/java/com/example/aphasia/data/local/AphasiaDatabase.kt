package com.example.aphasia.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 失語症溝通引擎的資料庫。
 *
 * **刻意與既有的 `aphasia_assist_database` 分開**，理由不只是架構乾淨：
 * 那個資料庫用 `fallbackToDestructiveMigration(true)`，schema 一改就整個清空。
 * 對紀錄類的資料（復健日誌）那是可接受的取捨，但這裡存的是
 * 聲學樣板與個人語句庫——使用者要錄好幾次、系統要重新校準門檻才能重建，
 * 沒有理由讓它們繼承那個政策。
 *
 * 所以這裡 `exportSchema = true` 並且**不加** destructive fallback：
 * 未來改 schema 就必須寫 migration，忘了寫會在開發階段直接爆炸，而不是在使用者手機上默默清空。
 */
@Database(
    entities = [AcousticTemplateEntity::class, PhraseEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AphasiaDatabase : RoomDatabase() {
    abstract fun acousticTemplateDao(): AcousticTemplateDao
    abstract fun phraseDao(): PhraseDao

    companion object {
        const val NAME = "aphasia_engine.db"

        @Volatile private var instance: AphasiaDatabase? = null

        fun get(context: Context): AphasiaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AphasiaDatabase::class.java,
                    NAME,
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        /**
         * v1 → v2：關鍵字觸發。
         *
         * 手寫 migration 而不是 destructive fallback——這張表存的是使用者
         * 錄好幾次才建立起來的樣板，清掉就要全部重錄。
         *
         * 既有樣板一律標成 Whole：它們是用「整段比對」的前提錄的
         *（錄什麼就是全部），改判成 Keyword 會讓它們在長語音裡到處誤觸發。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                // ADD COLUMN 的 DEFAULT 必須跟 Entity 上 @ColumnInfo(defaultValue=...) 一字不差。
                // Room 在 migration 之後會逐欄比對，包含預設值——對不上就丟
                // 「Migration didn't properly handle」並在啟動時直接閃退。
                //
                // 所以「既有樣板要標成 Whole」不能靠 column default 表達
                //（那會把新欄位的預設值也一起改掉），要用 UPDATE 明確改既有列。
                connection.execSQL(
                    "ALTER TABLE acoustic_templates ADD COLUMN match_mode TEXT NOT NULL DEFAULT 'Keyword'"
                )
                connection.execSQL(
                    "ALTER TABLE acoustic_templates ADD COLUMN keyword TEXT NOT NULL DEFAULT ''"
                )
                // 既有樣板是用「整段比對」的前提錄的（錄什麼就是全部）。
                // 讓它們留在 Keyword 會在長語音裡到處誤觸發。
                connection.execSQL("UPDATE acoustic_templates SET match_mode = 'Whole'")
                connection.execSQL("UPDATE acoustic_templates SET keyword = label WHERE keyword = ''")
            }
        }

        /** 給測試用的記憶體內資料庫。 */
        fun inMemory(context: Context): AphasiaDatabase =
            Room.inMemoryDatabaseBuilder(context, AphasiaDatabase::class.java)
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
