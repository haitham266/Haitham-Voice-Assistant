package com.haitham.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.haitham.voiceassistant.MainActivity
import com.haitham.voiceassistant.R
import com.haitham.voiceassistant.model.AssistantStatus

/** All notifications of the app (the ongoing "assistant is listening" one and alerts). */
object AssistantNotifications {
    const val CHANNEL_STATUS = "assistant_status"
    const val CHANNEL_ALERTS = "assistant_alerts"
    const val ID_FOREGROUND = 1001
    const val ID_BOOT = 1002
    const val ID_ALERT = 1003

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "حالة المساعد", NotificationManager.IMPORTANCE_LOW).apply {
                description = "إشعار دائم يوضح أن الميكروفون يستمع لكلمة التنبيه"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "تنبيهات المساعد", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "تفعيل المساعد بعد إعادة التشغيل، وفتح التطبيق"
            }
        )
    }

    private fun openAppIntent(context: Context, extra: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extra != null) i.putExtra(extra, true)
        return PendingIntent.getActivity(context, extra.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context, action.hashCode(),
            Intent(context, VoiceForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun foreground(context: Context, status: AssistantStatus, detail: String?, wakePhrase: String): Notification {
        val text = when (status) {
            AssistantStatus.WAITING_WAKE_WORD -> "قل «$wakePhrase» ثم الأمر"
            AssistantStatus.WAITING_DIRECT_COMMAND -> "قل «اتصل بـ…» مباشرة"
            else -> detail?.takeIf { it.isNotBlank() } ?: status.label
        }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("المساعد الصوتي: ${status.label}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "إيقاف المساعد", serviceAction(context, VoiceForegroundService.ACTION_STOP))
            .build()
    }

    /** After reboot on Android 11+: the system forbids starting the microphone from the background. */
    fun bootReminder(context: Context) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("فعّل المساعد الصوتي")
            .setContentText("أُعيد تشغيل الهاتف. اضغط هنا لتشغيل الاستماع لكلمة التنبيه.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.EXTRA_START_ASSISTANT))
            .build()
        notify(context, ID_BOOT, n)
    }

    /** Used when Android blocks opening an activity from the background. */
    fun tapToOpen(context: Context, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("المساعد الصوتي")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, ID_ALERT, n)
    }

    fun notify(context: Context, id: Int, n: Notification) {
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { /* POST_NOTIFICATIONS denied */ }
    }
}
