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
            // 1.0.5: a failed check (no internet yet, GitHub busy) is retried soon by Android instead of in 12 h.
            val ok = runCatching { checkAndNotify(app) }.getOrDefault(false)
            jobFinished(params, !ok)
        }.apply { priority = Thread.MIN_PRIORITY; start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { thread?.interrupt(); return true }

    companion object {
        // 1.0.5: a new job id, so phones coming from 1.0.3/1.0.4 drop the old 12-hour job (it also waited for a
        // battery that isn't low) and get the new one.
        private const val OLD_JOB_ID = 7201
        const val JOB_ID = 7203
        private const val NOTIF_ID = 7202
        /** 1.0.5: new channel id with high importance (the old "app_updates" channel was quieter and can't be changed). */
        const val CH_UPDATES = "app_updates_v2"
        private const val OLD_CH = "app_updates"
        const val EVERY_MS = 6L * 60 * 60 * 1000
        const val FLEX_MS = 2L * 60 * 60 * 1000

        /** The job this version wants: every ~6 hours with any internet, kept across restarts of the phone. */
        fun jobInfo(c: Context): JobInfo = JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
            .setPeriodic(EVERY_MS, FLEX_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(15L * 60 * 1000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()

        /**
         * 1.0.5: called on every launch, after the phone restarts and after the app is updated (MIUI can cancel
         * jobs when it clears the app). Sets the job up again when it's missing or different.
         */
        fun schedule(c: Context) {
            runCatching {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                runCatching { js.cancel(OLD_JOB_ID) }
                val want = jobInfo(c)
                val have = js.getPendingJob(JOB_ID)
                if (have != null && have.intervalMillis == want.intervalMillis && have.networkType == want.networkType &&
                    have.isPersisted && !have.isRequireBatteryNotLow) return
                js.schedule(want)
            }
        }

        fun channel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            runCatching { nm.deleteNotificationChannel(OLD_CH) }
            nm.createNotificationChannel(
                NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A new version of Browser is out"
                }
            )
        }

        /** Network call; runs off the main thread. Returns false when GitHub couldn't be reached (retry soon). */
        fun checkAndNotify(c: Context): Boolean {
            val r = Updater.check(APP_VERSION)
            Updater.saveResult(c, r)
            when (r) {
                is Updater.Check.Failed -> return false
                is Updater.Check.UpToDate -> { clear(c); return true }
                is Updater.Check.Newer -> { announce(c, r.info); return true }
            }
        }

        /** 2.0.0 (29): posts "Browser X is available" once per version. Used by the background job AND by the
         * checks the app makes when it opens or comes back (before, only the background job could post it). */
        fun announce(c: Context, u: UpdateInfo): Boolean {
            val prefs = c.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE)
            if (!Updater.shouldNotify(u.version, APP_VERSION, prefs.getString(Updater.K_NOTIFIED, null))) return false
            if (!DlService.canNotify(c) || !NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
            if (!notify(c, u)) return false
            prefs.edit().putString(Updater.K_NOTIFIED, u.version).apply()
            return true
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
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            NotificationManagerCompat.from(c).notify(NOTIF_ID, n)
            true
        }.getOrDefault(false)

        /** The update was installed or seen in the app: the notification is no longer needed. */
        fun clear(c: Context) { runCatching { NotificationManagerCompat.from(c).cancel(NOTIF_ID) } }
    }
}

/** 1.0.5: sets the background update check up again after the phone restarts and after Browser is updated. */
class UpdateBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            i.action != "android.intent.action.QUICKBOOT_POWERON") return
        runCatching { UpdateJob.channel(c); UpdateJob.schedule(c) }
    }
}
