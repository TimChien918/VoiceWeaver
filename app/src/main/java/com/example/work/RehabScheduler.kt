package com.example.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.ai.ReportAggregator
import com.example.ai.ReportRange
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import com.example.net.TelegramClient
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 背景排程：每日復健提醒通知 + 成績單定期推送家屬 Telegram。
 * 用 WorkManager 週期任務（最小週期 15 分鐘，這裡用天/週），App 不在前景也會跑。
 */
object RehabScheduler {
    const val CHANNEL_ID = "voiceweaver_reminders"
    private const val WORK_REMINDER = "vw_daily_reminder"
    private const val WORK_REPORT_PUSH = "vw_report_push"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "復健提醒", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "每日語音復健練習提醒" }
                )
            }
        }
    }

    /** 計算「現在到下一個指定小時」的延遲毫秒（用於對齊每日提醒時間）。 */
    private fun delayUntilHour(hour: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    fun scheduleDailyReminder(context: Context, enabled: Boolean, hour: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK_REMINDER); return }
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilHour(hour), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_REMINDER, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun scheduleReportPush(context: Context, frequency: String) {
        val wm = WorkManager.getInstance(context)
        val days = when (frequency) { "daily" -> 1L; "weekly" -> 7L; else -> 0L }
        if (days <= 0L) { wm.cancelUniqueWork(WORK_REPORT_PUSH); return }
        val req = PeriodicWorkRequestBuilder<ReportPushWorker>(days, TimeUnit.DAYS)
            .setInitialDelay(delayUntilHour(20), TimeUnit.MILLISECONDS)   // 晚上 8 點推
            .build()
        wm.enqueueUniquePeriodicWork(WORK_REPORT_PUSH, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

/** 每日提醒：發一則通知請使用者來練習。 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        RehabScheduler.ensureChannel(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return Result.success()   // 沒授權就安靜略過

        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, RehabScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("該練習語音復健囉 🎤")
            .setContentText("每天一點點，持續進步。點我開始今天的練習。")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(1001, notif) }
        return Result.success()
    }
}

/** 成績單定期推送：彙整本週成績 → 發 Telegram 給家屬。 */
class ReportPushWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).settings.first()
        val chat = settings.emergencyChatId.ifBlank { return Result.success() }
        val db = AppDatabase.getDatabase(applicationContext)
        val aggregator = ReportAggregator(db.rehabLogDao(), db.aacComboLogDao(), db.storyTrainingLogDao())
        val range = if (settings.reportPushFrequency == "daily") ReportRange.Today else ReportRange.Month
        val snapshot = aggregator.build(range)
        TelegramClient.sendEmergency(snapshot.toTelegramText(), chat)
        return Result.success()
    }
}
