package com.khalid.browser

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// =====================================================================
// 1.0.2: downloads belong to Browser now. They run in a foreground service with its own notification,
// so they keep going when you leave the app or lock the screen, and they can be paused and continued.
// =====================================================================

object DlEngine {
    private const val MAX_ACTIVE = 3
    private const val MAX_ATTEMPTS = 8
    private val tasks = LinkedHashMap<Long, DlTask>()
    private val runs = HashMap<Long, DlRun>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dl-save").apply { isDaemon = true } }
    private lateinit var app: Context
    @Volatile private var loaded = false
    @Volatile private var saveQueued = false
    @Volatile var online = true; private set
    /** True while the browser screen is open; then cookies are read fresh from the web view. */
    @Volatile var webReady = false
    /** Called on the main thread when a download finishes or fails (the open app shows a message). */
    var onEnd: ((DlTask) -> Unit)? = null
    /** Set when downloads were cut off by the phone (not by the user): the app shows a one-time hint. */
    @Volatile var killedBySystem = false
    private var lastId = 0L

    @Synchronized fun init(c: Context) {
        if (loaded) return
        app = c.applicationContext
        val f = AtomicFile(File(app.filesDir, "downloads.json"))
        runCatching {
            val (list, boot) = DlTask.listFromJson(String(f.readFully()))
            list.forEach { tasks[it.id] = it }
            // the app was closed by the phone while downloading (not after a reboot): MIUI and others do this
            if (list.any { it.wasActive } && boot == bootCount()) killedBySystem = true
        }
        loaded = true
        watchNetwork()
    }

    private fun bootCount(): Int = runCatching { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    fun has(id: Long) = synchronized(this) { tasks.containsKey(id) }
    fun get(id: Long): DlTask? = synchronized(this) { tasks[id] }
    fun all(): List<DlTask> = synchronized(this) { tasks.values.toList() }
    fun active(): List<DlTask> = all().filter { it.status == DlState.RUNNING || it.status == DlState.QUEUED || (it.status == DlState.PAUSED && (it.reason == DlWhy.RETRY || it.reason == DlWhy.NETWORK)) }
    fun busy() = active().isNotEmpty()

    fun add(c: Context, url: String, name: String, mime: String, ua: String, cookie: String, referer: String): DlTask {
        init(c)
        val t = synchronized(this) {
            var id = System.currentTimeMillis() * 1000
            if (id <= lastId) id = lastId + 1
            lastId = id
            DlTask(id, url, uniqueName(name), mime, ua, cookie, referer, System.currentTimeMillis()).also { tasks[id] = it }
        }
        save()
        kick()
        return t
    }

    /** "file.zip" twice becomes "file (1).zip", so two downloads never write over each other. */
    private fun uniqueName(n: String): String {
        val names = tasks.values.filter { it.status != DlState.FAILED }.map { it.name.lowercase() }.toSet()
        if (n.lowercase() !in names) return n
        val dot = n.lastIndexOf('.').takeIf { it > 0 } ?: n.length
        var i = 1
        while (true) { val c = n.substring(0, dot) + " ($i)" + n.substring(dot); if (c.lowercase() !in names) return c; i++ }
    }

    fun pause(id: Long) {
        val t = get(id) ?: return
        synchronized(this) {
            if (t.status == DlState.DONE || t.status == DlState.FAILED) return
            t.status = DlState.PAUSED; t.reason = DlWhy.USER; t.speed = 0.0
            runs[id]?.stop()
        }
        save(); kick()
    }

    fun resume(id: Long) {
        val t = get(id) ?: return
        synchronized(this) {
            if (t.status == DlState.DONE || t.status == DlState.RUNNING) return
            if (t.status == DlState.FAILED && t.reason == DlWhy.CHANGED) { t.segs.clear(); t.probed = false; t.restarts = 0 }
            t.status = DlState.QUEUED; t.reason = 0; t.attempts = 0; t.retryNow = true
        }
        save(); kick()
    }

    fun pauseAll() = active().forEach { pause(it.id) }
    fun resumeAll() = all().filter { it.status == DlState.PAUSED }.forEach { resume(it.id) }

    /** Stops the download and deletes the file. */
    fun remove(id: Long) {
        val t = synchronized(this) { tasks.remove(id) } ?: return
        synchronized(this) { runs.remove(id)?.stop() }
        io.execute {
            Thread.sleep(300)
            partFile(t).delete()
            if (t.uri.startsWith("content://media")) runCatching { app.contentResolver.delete(Uri.parse(t.uri), null, null) }
            else if (t.path.isNotBlank()) File(t.path).delete()
        }
        save(); kick()
    }

    /** Keeps the file, drops the row. */
    fun forget(id: Long) {
        val t = get(id) ?: return
        if (t.status != DlState.DONE) { remove(id); return }
        synchronized(this) { tasks.remove(id) }
        save()
    }

    /** The file a finished download saved, for the viewer, "Open with" and "Share". */
    fun fileUri(t: DlTask): Uri? = when {
        t.status != DlState.DONE -> null
        t.uri.startsWith("content://") -> Uri.parse(t.uri)
        t.path.isNotBlank() && File(t.path).exists() -> runCatching { FileProvider.getUriForFile(app, "${app.packageName}.files", File(t.path)) }.getOrNull()
        else -> null
    }

    private fun partDir(): File = (app.getExternalFilesDir("partial") ?: File(app.filesDir, "partial")).apply { mkdirs() }
    private fun partFile(t: DlTask) = File(partDir(), "${t.id}.part")

    /** Starts what is waiting, up to 3 at once, and keeps the service (and its notification) in step. */
    fun kick() {
        if (!loaded) return
        val start = ArrayList<DlTask>()
        synchronized(this) {
            val now = runs.size
            val waiting = tasks.values.filter { it.status == DlState.QUEUED && it.id !in runs }.sortedBy { it.created }
            if (online) waiting.take((MAX_ACTIVE - now).coerceAtLeast(0)).forEach { t ->
                t.status = DlState.RUNNING; t.reason = 0; t.retryNow = false; start.add(t)
            } else waiting.forEach { t -> t.status = DlState.PAUSED; t.reason = DlWhy.NETWORK }
        }
        start.forEach { launch(it) }
        DlService.sync(app)
    }

    private fun launch(t: DlTask) {
        val run = DlRun(t, partFile(t),
            cookieFor = { u -> if (webReady) runCatching { android.webkit.CookieManager.getInstance().getCookie(u) }.getOrNull() else null },
            onTick = { DlService.tick(app) }, onSave = { save() })
        synchronized(this) { runs[t.id] = run }
        Thread({ finish(t, run, run.execute()) }, "dl-${t.id}").apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    private fun finish(t: DlTask, run: DlRun, end: DlRun.End) {
        var published = true
        if (end is DlRun.End.Done) published = publish(t)
        synchronized(this) {
            if (runs[t.id] === run) runs.remove(t.id)
            if (tasks[t.id] !== t) return@synchronized
            when {
                end is DlRun.End.Done && published -> { t.status = DlState.DONE; t.reason = 0; t.finished = System.currentTimeMillis(); t.segs.clear(); t.saved = null }
                end is DlRun.End.Done -> { t.status = DlState.FAILED; t.reason = DlWhy.CANT_SAVE }
                t.status == DlState.PAUSED && t.reason == DlWhy.USER -> {}
                end is DlRun.End.Stopped -> if (t.status == DlState.RUNNING) { t.status = DlState.PAUSED; t.reason = DlWhy.USER }
                end is DlRun.End.Retry -> {
                    if (!online || !hasNetwork()) { t.status = DlState.PAUSED; t.reason = DlWhy.NETWORK }
                    else if (++t.attempts > MAX_ATTEMPTS) { t.status = DlState.FAILED; t.reason = DlWhy.CONNECTION }
                    else {
                        t.status = DlState.PAUSED; t.reason = DlWhy.RETRY
                        main.postDelayed({ retryIfWaiting(t) }, DlMath.backoffMs(t.attempts - 1))
                    }
                }
                end is DlRun.End.Failed -> { t.status = DlState.FAILED; t.reason = end.code }
            }
        }
        if (t.status == DlState.DONE) partFile(t).delete()
        save()
        if (t.status == DlState.DONE || t.status == DlState.FAILED) {
            DlService.ended(app, t)
            main.post { onEnd?.invoke(t) }
        }
        kick()
    }

    private fun retryIfWaiting(t: DlTask) {
        synchronized(this) {
            if (tasks[t.id] !== t || t.status != DlState.PAUSED || t.reason != DlWhy.RETRY) return
            t.status = DlState.QUEUED
        }
        kick()
    }

    /** Moves the finished file into the phone's Downloads folder. */
    private fun publish(t: DlTask): Boolean {
        val part = partFile(t)
        if (!part.exists()) return false
        val type = DlName.typeOf(t.name, t.mime).ifBlank { "application/octet-stream" }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = app.contentResolver
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, t.name)
                    put(MediaStore.Downloads.MIME_TYPE, type)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    if (t.finalUrl.isNotBlank() || t.url.isNotBlank()) put(MediaStore.Downloads.DOWNLOAD_URI, t.url)
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
                try {
                    cr.openOutputStream(uri, "w")!!.use { out -> part.inputStream().use { it.copyTo(out, 1 shl 20) } }
                    cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { runCatching { cr.delete(uri, null, null) }; throw e }
                // the name the phone actually gave it (it adds " (1)" when the name is taken)
                runCatching { cr.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { t.name = it } } }
                t.uri = uri.toString()
            } else {
                val dir = (app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "Download")).apply { mkdirs() }
                var out = File(dir, t.name)
                var i = 1
                while (out.exists()) { out = File(dir, t.name.substringBeforeLast('.', t.name) + " ($i)" + t.name.substringAfterLast('.', "").let { if (it.isBlank() || it == t.name) "" else ".$it" }); i++ }
                if (!part.renameTo(out)) { part.copyTo(out); part.delete() }
                t.path = out.absolutePath; t.name = out.name
            }
            true
        }.getOrDefault(false)
    }

    // ---- saving the list (never on the main thread, at most a few times a second) ----
    fun save() {
        if (!loaded || saveQueued) return
        saveQueued = true
        io.execute {
            saveQueued = false
            val json = synchronized(this) { DlTask.listToJson(tasks.values, bootCount()) }
            val f = AtomicFile(File(app.filesDir, "downloads.json"))
            runCatching { val o = f.startWrite(); try { o.write(json.toByteArray()); f.finishWrite(o) } catch (e: Exception) { f.failWrite(o) } }
            Thread.sleep(250)
        }
    }

    /** Downloads that were going when the app was closed carry on by themselves. */
    fun continueInterrupted() {
        all().filter { it.wasActive && it.status == DlState.PAUSED && it.reason == DlWhy.INTERRUPTED }.forEach { it.wasActive = false; resume(it.id) }
    }

    // ---- network: wait when it's gone, continue when it's back ----
    private fun hasNetwork(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private fun watchNetwork() {
        online = hasNetwork()
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null
                override fun onAvailable(network: Network) {
                    val switched = current != null && current != network
                    current = network; online = true
                    main.post {
                        // Wi-Fi to mobile data (or back): reconnect at once instead of waiting for the old line to time out
                        if (switched) synchronized(this@DlEngine) { runs.values.forEach { it.stop(retry = true) } }
                        val back = synchronized(this@DlEngine) {
                            tasks.values.filter { it.status == DlState.PAUSED && (it.reason == DlWhy.NETWORK || it.reason == DlWhy.RETRY) }
                                .onEach { it.status = DlState.QUEUED; it.attempts = 0 }
                        }
                        if (back.isNotEmpty()) save()
                        kick()
                    }
                }
                override fun onLost(network: Network) {
                    if (network != current) return
                    current = null
                    main.postDelayed({ if (current == null) { online = hasNetwork(); if (!online) { synchronized(this@DlEngine) { runs.values.forEach { it.stop(retry = true) } }; DlService.sync(app) } } }, 1500)
                }
            })
        }
    }
}

/** The foreground service: keeps Browser alive while files download, with an ongoing notification. */
class DlService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DlEngine.init(this)
        channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            A_PAUSE -> if (id > 0) DlEngine.pause(id) else DlEngine.pauseAll()
            A_RESUME -> if (id > 0) DlEngine.resume(id) else DlEngine.resumeAll()
            A_CANCEL -> if (id > 0) DlEngine.remove(id) else DlEngine.active().forEach { DlEngine.remove(it.id) }
            // null intent = the phone restarted this service after closing it: carry on
            null -> DlEngine.continueInterrupted()
        }
        // Started with startForegroundService: Android wants startForeground every time, even if we stop right after.
        if (!foreground()) return START_NOT_STICKY
        refresh()
        return START_STICKY
    }

    private fun foreground(): Boolean {
        val n = progressNotification(this)
        if (inForeground) return true
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            inForeground = true; true
        } catch (e: Exception) {
            // Android didn't allow it from the background: downloads still go on while the app is open
            stopSelf(); false
        }
    }

    private fun refresh() {
        if (!DlEngine.busy()) { stop(); return }
        if (!foreground()) return
        if (canNotify(this)) runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, progressNotification(this)) }
        locks(DlEngine.all().any { it.status == DlState.RUNNING })
    }

    private fun stop() {
        locks(false)
        if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        postPausedNote(this)
        stopSelf()
    }

    /** CPU and Wi-Fi stay awake only while bytes are actually coming in. */
    @SuppressLint("WakelockTimeout")
    private fun locks(on: Boolean) {
        if (on) {
            if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Browser:download").apply { setReferenceCounted(false) }
            if (wifi == null) runCatching {
                @Suppress("DEPRECATION")
                wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Browser:download").apply { setReferenceCounted(false) }
            }
            if (wake?.isHeld == false) wake?.acquire(TimeUnit.HOURS.toMillis(6))
            if (wifi?.isHeld == false) runCatching { wifi?.acquire() }
        } else {
            if (wake?.isHeld == true) wake?.release()
            if (wifi?.isHeld == true) runCatching { wifi?.release() }
        }
    }

    override fun onDestroy() {
        locks(false); instance = null
        super.onDestroy()
    }

    init { instance = this }

    companion object {
        const val A_PAUSE = "com.khalid.browser.dl.PAUSE"
        const val A_RESUME = "com.khalid.browser.dl.RESUME"
        const val A_CANCEL = "com.khalid.browser.dl.CANCEL"
        const val A_SYNC = "com.khalid.browser.dl.SYNC"
        const val EXTRA_ID = "id"
        private const val NOTIF_ID = 7101
        private const val PAUSED_ID = 7102
        private const val CH_RUN = "downloads"
        private const val CH_DONE = "downloads_done"
        @Volatile private var instance: DlService? = null
        @Volatile private var lastTick = 0L

        fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Starts the service when something is downloading, and updates or ends it otherwise. */
        fun sync(c: Context) {
            val s = instance
            if (s != null) { Handler(Looper.getMainLooper()).post { s.refresh() }; return }
            if (!DlEngine.busy()) { Handler(Looper.getMainLooper()).post { postPausedNote(c) }; return }
            runCatching { ContextCompat.startForegroundService(c, Intent(c, DlService::class.java).setAction(A_SYNC)) }
        }

        /** The notification follows the download about once a second (more often would waste battery). */
        fun tick(c: Context) {
            val now = System.currentTimeMillis()
            if (now - lastTick < 1000) return
            lastTick = now
            val s = instance ?: return
            Handler(Looper.getMainLooper()).post { if (s.inForeground && canNotify(c)) runCatching { NotificationManagerCompat.from(c).notify(NOTIF_ID, progressNotification(c)) } }
        }

        fun channels(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_RUN, "Downloads in progress", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openApp(c: Context, code: Int): PendingIntent = PendingIntent.getActivity(c, code,
            Intent(ACT_DOWNLOADS).setClass(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun act(c: Context, action: String, id: Long, code: Int): PendingIntent {
            val i = Intent(c, DlService::class.java).setAction(action).putExtra(EXTRA_ID, id)
            return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getService(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun base(c: Context, ch: String) = NotificationCompat.Builder(c, ch)
            .setSmallIcon(R.drawable.wi_down).setColor(0xFF000000.toInt()).setContentIntent(openApp(c, 70))

        fun progressNotification(c: Context): Notification {
            val act = DlEngine.active()
            val running = act.filter { it.status == DlState.RUNNING }
            val b = base(c, CH_RUN).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            val speed = running.sumOf { it.speed }
            val done = act.sumOf { it.done }
            val total = if (act.all { it.total > 0 }) act.sumOf { it.total } else -1L
            val one = act.singleOrNull()
            b.setContentTitle(one?.name ?: "Downloading ${act.size} files")
            val line = when {
                running.isEmpty() && act.any { it.reason == DlWhy.NETWORK } -> "Waiting for network"
                running.isEmpty() -> "Retrying soon"
                else -> buildString {
                    append(if (total > 0) "${fmtBytes(done)} of ${fmtBytes(total)}" else fmtBytes(done))
                    if (speed > 1024) {
                        append("  ·  ${fmtBytes(speed.toLong())}/s")
                        if (total > done) append("  ·  " + DlName.timeLeft(((total - done) / speed).toLong()))
                    }
                }
            }
            b.setContentText(line)
            if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt(), false) else b.setProgress(0, 0, running.isNotEmpty())
            val id = one?.id ?: -1L
            b.addAction(0, if (one == null) "Pause all" else "Pause", act(c, A_PAUSE, id, 71))
            b.addAction(0, if (one == null) "Cancel all" else "Cancel", act(c, A_CANCEL, id, 72))
            return b.build()
        }

        /** When everything is paused by you, a quiet note with Resume stays until you continue. */
        fun postPausedNote(c: Context) {
            if (!canNotify(c)) return
            val nm = NotificationManagerCompat.from(c)
            val paused = DlEngine.all().filter { it.status == DlState.PAUSED && it.reason == DlWhy.USER }
            if (paused.isEmpty() || DlEngine.busy()) { nm.cancel(PAUSED_ID); return }
            val one = paused.singleOrNull()
            val n = base(c, CH_RUN).setSilent(true).setOnlyAlertOnce(true)
                .setContentTitle(one?.name ?: "${paused.size} downloads paused")
                .setContentText("Paused  ·  " + (one?.let { t -> if (t.total > 0) "${t.done * 100 / t.total}%" else fmtBytes(t.done) } ?: "tap Resume to continue"))
                .addAction(0, "Resume", act(c, A_RESUME, one?.id ?: -1L, 73))
                .build()
            runCatching { nm.notify(PAUSED_ID, n) }
        }

        /** "Downloaded" or "Download failed", tap to open Downloads. */
        fun ended(c: Context, t: DlTask) {
            if (!canNotify(c)) return
            val ok = t.status == DlState.DONE
            val n = base(c, CH_DONE).setAutoCancel(true)
                .setContentTitle(if (ok) t.name else "Download failed")
                .setContentText(if (ok) "Downloaded  ·  ${fmtBytes(t.total.coerceAtLeast(0))}" else t.name + DlName.failReason(t.reason).let { if (it.isBlank()) "" else "  ·  $it" })
                .build()
            runCatching { NotificationManagerCompat.from(c).notify((t.id % 100000).toInt() + 8000, n) }
        }
    }
}
