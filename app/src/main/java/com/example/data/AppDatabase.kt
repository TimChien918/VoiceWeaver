package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SpeechRecord::class, RehabLog::class, AacComboLog::class, StoryTrainingLog::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun speechRecordDao(): SpeechRecordDao
    abstract fun rehabLogDao(): RehabLogDao
    abstract fun aacComboLogDao(): AacComboLogDao
    abstract fun storyTrainingLogDao(): StoryTrainingLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aphasia_assist_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
