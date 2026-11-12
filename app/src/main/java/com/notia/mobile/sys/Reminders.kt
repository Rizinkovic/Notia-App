package com.notia.mobile.sys

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.Store
import java.util.Calendar

object Reminders {
    const val CH_TASKS = "task_alarm"
    const val CH_READ = "reading"
    const val ACT_TASK = "com.notia.mobile.TASK"
    const val ACT_READ = "com.notia.mobile.READING"
    const val ACT_DONE = "com.notia.mobile.TASK_DONE"
    const val ACT_SNOOZE = "com.notia.mobile.TASK_SNOOZE"
    private const val READ_HOUR = 19

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TASKS, if (Store.lang == "fr") "Alarmes de tâches" else "Task alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            // rings like an alarm so a due task is hard to miss
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM) ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_READ, if (Store.lang == "fr") "Rappels de lecture" else "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun enabled(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun openExactSettings(a: Activity) {
        try { if (Build.VERSION.SDK_INT >= 31) a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.packageName))) } catch (e: Exception) {}
    }

    fun openNotificationSettings(a: Activity) {
        try { a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)) } catch (e: Exception) {}
    }

    private fun pi(ctx: Context, action: String, key: String, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("notia://$action/$key")).apply(extras)
        return PendingIntent.getBroadcast(ctx, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Returns true when the alarm will fire at the exact time. */
    fun schedule(ctx: Context, id: String, due: Long): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, ACT_TASK, id) { putExtra("id", id) }
        return if (canExact(ctx)) { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p); true }
        else { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p); false }
    }

    fun cancel(ctx: Context, id: String) { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACT_TASK, id) { putExtra("id", id) }) }

    fun topBook() = Store.books.filter { it.pct in 0.02..0.97 }.maxByOrNull { it.time }

    /** Daily 19:00 nudge for the most-read unfinished book. */
    fun scheduleReading(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, ACT_READ, "daily")
        if (topBook() == null) { am.cancel(p); return }
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, READ_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis() + 60_000) c.add(Calendar.DAY_OF_YEAR, 1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, p)
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (t in Store.tasks) if (!t.done && t.due != null && t.due!! > now) { t.armed = true; schedule(ctx, t.id, t.due!!) } else if (t.armed) t.armed = false
        scheduleReading(ctx)
    }

    /** Asks for the notification permission on Android 13+, then calls back with whether it is usable. */
    fun ask(a: MainActivityLike, cb: (Boolean) -> Unit) {
        if (hasPermission(a.activity)) { cb(enabled(a.activity)); return }
        a.requestNotifPermission { cb(it && enabled(a.activity)) }
    }

    private fun action(ctx: Context, act: String, taskId: String, label: String, icon: Int): Notification.Action {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(act).setData(Uri.parse("notia://$act/$taskId")).putExtra("id", taskId)
        val p = PendingIntent.getBroadcast(ctx, (act + taskId).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, icon), label, p).build()
    }

    /** Task alarm: rings, vibrates and offers Done and Snooze 10 min. */
    fun showTask(ctx: Context, taskId: String, title: String) {
        val fr = Store.lang == "fr"
        show(ctx, CH_TASKS, taskId.hashCode(), if (fr) "Tâche à terminer" else "Time to finish your task", title) { b ->
            b.setCategory(Notification.CATEGORY_REMINDER)
            b.addAction(action(ctx, ACT_DONE, taskId, if (fr) "Terminé" else "Done", R.drawable.ic_check))
            b.addAction(action(ctx, ACT_SNOOZE, taskId, if (fr) "Reporter 10 min" else "Snooze 10 min", R.drawable.ic_clock))
        }
    }

    fun show(ctx: Context, channel: String, id: Int, title: String, text: String, extra: (Notification.Builder) -> Unit = {}) {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else @Suppress("DEPRECATION") Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
        extra(b)
        if (hasPermission(ctx)) ctx.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
}

/** Small seam so Reminders can ask for the permission without depending on the full activity. */
interface MainActivityLike {
    val activity: Activity
    fun requestNotifPermission(cb: (Boolean) -> Unit)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.ensureLoaded(ctx)
        val fr = Store.lang == "fr"
        when (intent.action) {
            Reminders.ACT_TASK -> {
                val t = Store.tasks.firstOrNull { it.id == intent.getStringExtra("id") } ?: return
                if (t.done) return
                t.armed = false
                Reminders.showTask(ctx, t.id, t.title)
                Store.flush(); Store.changed()
            }
            Reminders.ACT_DONE, Reminders.ACT_SNOOZE -> {
                val t = Store.tasks.firstOrNull { it.id == intent.getStringExtra("id") } ?: return
                ctx.getSystemService(NotificationManager::class.java).cancel(t.id.hashCode())
                if (intent.action == Reminders.ACT_DONE) { t.done = true; t.armed = false }
                else { val at = System.currentTimeMillis() + 10 * 60_000L; t.due = at; t.armed = true; t.done = false; Reminders.schedule(ctx, t.id, at) }
                Store.flush(); Store.changed()
            }
            Reminders.ACT_READ -> {
                val b = Reminders.topBook()
                if (b != null) Reminders.show(ctx, Reminders.CH_READ, 7001, "Notia",
                    if (fr) "Terminer « ${b.title} » ce soir ? Vous en avez lu ${Math.round(b.pct * 100)} %." else "Finish \"${b.title}\" tonight? You are ${Math.round(b.pct * 100)}% through.")
                Reminders.scheduleReading(ctx)
            }
        }
    }
}

/** Alarms are cleared on reboot and on app update; this restores them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Store.ensureLoaded(ctx)
        Reminders.channels(ctx)
        Reminders.rescheduleAll(ctx)
        Store.flush()
    }
}
