package com.khalid.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** 1.0.3: opens the app straight on the update card (from the update notification). */
const val ACT_UPDATE = "com.khalid.browser.action.UPDATE"

/**
 * 1.0.3: a background check for new versions, about twice a day, only with internet and when the battery
 * isn't low. Android runs it with other apps' jobs to save battery (JobScheduler, what WorkManager uses
 * underneath, without adding a library). A new release posts one "Browser X.Y.Z is available" notification.
 */
class UpdateJob : JobService() {
    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        thread = Thread {
            runCatching { checkAndNotify(app) }
            jobFinished(params, false)
        }.apply { priority = Thread.MIN_PRIORITY; start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { thread?.interrupt(); return true }

    companion object {
        private const val JOB_ID = 7201
        private const val NOTIF_ID = 7202
        const val CH_UPDATES = "app_updates"
        private const val EVERY_MS = 12L * 60 * 60 * 1000
        private const val FLEX_MS = 3L * 60 * 60 * 1000

        /** Set up the periodic check once; it survives restarts of the phone. */
        fun schedule(c: Context) {
            runCatching {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) return
                val info = JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
                    .setPeriodic(EVERY_MS, FLEX_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build()
                js.schedule(info)
            }
        }

        fun channel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A new version of Browser is out"
                }
            )
        }

        /** Network call; runs off the main thread. */
        fun checkAndNotify(c: Context) {
            val r = Updater.check(APP_VERSION) as? Updater.Check.Newer ?: return
            val prefs = c.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE)
            Updater.saveFound(c, r.json)
            prefs.edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
            if (!Updater.shouldNotify(r.info.version, APP_VERSION, prefs.getString(Updater.K_NOTIFIED, null))) return
            if (!DlService.canNotify(c)) return
            if (notify(c, r.info)) prefs.edit().putString(Updater.K_NOTIFIED, r.info.version).apply()
        }

        fun notify(c: Context, u: UpdateInfo): Boolean = runCatching {
            channel(c)
            val open = PendingIntent.getActivity(
                c, 72, Intent(ACT_UPDATE).setClass(c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(c, CH_UPDATES)
                .setSmallIcon(R.drawable.wi_dot).setColor(0xFF000000.toInt())
                .setContentTitle("Browser ${u.version} is available")
                .setContentText(u.name.ifBlank { "Tap to update" })
                .setContentIntent(open).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            NotificationManagerCompat.from(c).notify(NOTIF_ID, n)
            true
        }.getOrDefault(false)

        /** The update was installed or seen in the app: the notification is no longer needed. */
        fun clear(c: Context) { runCatching { NotificationManagerCompat.from(c).cancel(NOTIF_ID) } }
    }
}
