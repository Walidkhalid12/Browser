package com.khalid.browser

import android.accounts.AccountManager
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.IntentFilter
import android.os.Looper
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import androidx.activity.addCallback
import androidx.compose.animation.core.LinearEasing
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity(), BrowserHost {
    private lateinit var st: BrowserState
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private var accountResult: CompletableDeferred<Result<Account>>? = null
    private val accountLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val email = r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        accountResult?.complete(
            if (r.resultCode == RESULT_OK && !email.isNullOrBlank())
                Result.success(Account(email, email.substringBefore('@')))
            else Result.failure(CancellationException(""))
        )
    }

    private var permDone: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val cb = permDone; permDone = null
        cb?.invoke(r.isNotEmpty() && r.values.any { it })
    }
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val words = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK && !words.isNullOrBlank()) st.open(words)
    }
    private var unlockDone: ((Boolean) -> Unit)? = null
    private val unlockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = unlockDone; unlockDone = null
        cb?.invoke(r.resultCode == RESULT_OK)
    }
    private var videoW = 0
    private var videoH = 0

    // ---------------- 1.0.1 ----------------
    @Volatile private var firstFrameDrawn = false
    private var pendingUpdate: java.io.File? = null
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        st.isDefault = isDefaultBrowser()
        if (st.isDefault) st.notify("Browser is now your default browser")
        else if (r.resultCode != RESULT_OK) openDefaultAppsSettings(quiet = true)
    }

    /** Work that used to run during the opening animation, now started right after it. */
    private fun afterIntro(prefs: android.content.SharedPreferences) {
        if (isRobolectric) return
        Looper.myQueue().addIdleHandler { runCatching { WebSettings.getDefaultUserAgent(this) }; false }
        // 1.0.5: every launch checks GitHub (at most every 30 min, only successful checks count).
        Bg.main.postDelayed({ autoCheck() }, 1500L)
        // Background check for new versions with an "App updates" notification (1.0.5: re-set up on every launch).
        Bg.main.postDelayed({
            UpdateJob.channel(this)
            UpdateJob.schedule(this)
            // Android 13+: ask once to show notifications, on a later launch (not on top of the first-launch cards).
            if (prefs.getBoolean("asked_default", false) && st.card == null) askNotificationsOnce()
        }, 4000L)
    }

    fun isDefaultBrowser(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) return@runCatching rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
        }
        val r = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        r?.activityInfo?.packageName == packageName
    }.getOrDefault(false)

    override fun makeDefault() {
        if (isDefaultBrowser()) { st.isDefault = true; st.notify("Browser is already your default"); return }
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                ownLaunchAt = android.os.SystemClock.uptimeMillis()
                runCatching { roleLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER)); return }
            }
        }
        openDefaultAppsSettings(quiet = false)
    }

    private fun openDefaultAppsSettings(quiet: Boolean) {
        // Older Android, or a phone that hides the system ask (some MIUI builds): the Default apps screen.
        val ok = runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }.isSuccess ||
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }.isSuccess
        if (ok) st.notify("Choose Browser under Browser app") else if (!quiet) st.notify("Set it in Settings, Apps, Default apps")
    }

    override fun backToCaller() { moveTaskToBack(true) }

    // 1.0.5: closing the home announcement only lasts until the app is opened again (not saved).
    override fun updateBannerClosed(version: String) {}

    /** 1.0.5: automatic check when the app opens or comes back, unless a successful check ran in the last 30 min. */
    private fun autoCheck() {
        if (!::st.isInitialized) return
        // A release the background check found while the app was away shows at once.
        if (st.update == null) Updater.cached(this, APP_VERSION)?.let { st.update = it }
        if (isRobolectric) return
        val last = getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE).getLong(Updater.K_CHECKED, 0L)
        if (Updater.due(last, System.currentTimeMillis())) checkUpdates(manual = false)
    }

    @Volatile private var checking = false

    override fun checkUpdates(manual: Boolean) {
        if (st.updateProgress >= 0f) { st.card = Card.Update; return }
        // 1.0.5: "Check for updates" always asks GitHub again; a release already found opens right away meanwhile.
        if (manual) { st.updateStatus = "Checking\u2026"; st.updateChecking = true; if (st.update != null) st.card = Card.Update }
        if (checking) return
        checking = true
        val app = applicationContext
        Thread {
            val r = runCatching { Updater.check(APP_VERSION) }.getOrElse { Updater.Check.Failed(Updater.reason(it)) }
            Updater.saveResult(app, r)
            Bg.main.post {
                checking = false
                val asked = st.updateChecking
                st.updateChecking = false
                when (r) {
                    is Updater.Check.Newer -> {
                        st.update = r.info
                        st.updateStatus = "Browser ${r.info.version} is available"
                        // The automatic check announces it with the home banner; a manual check opens the card.
                        if (manual || asked) st.card = Card.Update
                    }
                    is Updater.Check.UpToDate -> {
                        st.update = null
                        st.updateStatus = "You're up to date ($APP_VERSION)"
                        UpdateJob.clear(this)
                    }
                    is Updater.Check.Failed -> if (manual || asked) st.updateStatus = r.why
                }
            }
        }.apply { name = "browser-update"; isDaemon = true }.start()
    }

    override fun installUpdate() {
        val u = st.update ?: return
        if (st.updateProgress >= 0f) return
        st.updateProgress = 0f
        st.updateStatus = null
        val app = applicationContext
        Bg.run {
            val res = runCatching {
                val f = Updater.download(app, u) { p -> Bg.main.post { if (st.updateProgress >= 0f) st.updateProgress = p } }
                Updater.verify(app, f)?.let { why -> f.delete(); throw IllegalStateException(why) }
                f
            }
            Bg.main.post {
                res.onSuccess { f ->
                    if (!packageManager.canRequestPackageInstalls()) {
                        // First time only: Android asks to allow Browser to install apps. We continue on return.
                        pendingUpdate = f
                        st.notify("Turn on \"Allow from this source\", then come back")
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                            .onFailure { st.updateProgress = -1f; st.updateStatus = "Allow Browser to install apps in Settings" }
                    } else launchInstaller(f)
                }.onFailure { e ->
                    st.updateProgress = -1f
                    st.updateStatus = (e.message?.takeIf { it.isNotBlank() && e !is java.io.IOException } ?: "Download failed. Check your connection")
                }
            }
        }
    }

    /** Hand the checked APK to Android's own installer; the person confirms there. */
    private fun launchInstaller(f: java.io.File) {
        val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f) }.getOrNull()
        if (uri == null || !f.exists()) { st.updateProgress = -1f; st.updateStatus = "Download failed. Try again"; return }
        st.card = null
        st.updateProgress = -1f
        st.updateStatus = "Ready to install"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(view) } catch (e: ActivityNotFoundException) {
            @Suppress("DEPRECATION")
            runCatching { startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                .onFailure { st.notify("This phone has no app installer") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1.0.0: open the other preference files now; Android parses each on its own thread,
        // so they are ready (or nearly) by the time Store and SitePrefs read them below.
        getSharedPreferences("browser_data", Context.MODE_PRIVATE)
        getSharedPreferences("site_prefs", Context.MODE_PRIVATE)
        val prefs = getSharedPreferences("browser", Context.MODE_PRIVATE)
        // Keep the last crash so it can be copied and fixed (More shows it once).
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev !is CrashSaver) Thread.setDefaultUncaughtExceptionHandler(CrashSaver(crashFile, prev))
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = if (prefs.contains("dark")) prefs.getBoolean("dark", false) else systemDark
        // 1.0.1: real launch screen (Android 12+ system splash, same look on 8-11 through the compat library).
        // It stays until the first frame of the app is fully drawn, so the opening animation never starts
        // on a half-built screen, then fades out in 150 ms.
        val splash = installSplashScreen()
        val created = android.os.SystemClock.uptimeMillis()
        // Safety net: never hold the launch screen longer than 1.2 s, even if a frame is slow.
        if (!isRobolectric) splash.setKeepOnScreenCondition { !firstFrameDrawn && android.os.SystemClock.uptimeMillis() - created < 1200 }
        splash.setOnExitAnimationListener { v ->
            v.view.animate().alpha(0f).setDuration(150L).withEndAction { v.remove() }.start()
        }
        window.setBackgroundDrawable(ColorDrawable(if (dark) DARK_BG else AColor.WHITE))
        applyBars(dark)
        super.onCreate(savedInstanceState)
        // 1.0.1: on Android 8-11 a Back on the empty home used to close the app completely, so the next
        // open was a slow cold start. Now Browser just goes to the background (Android 12+ already does this).
        if (Build.VERSION.SDK_INT < 31) onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        runCatching { preferHighestRefreshRate() }

        val email = prefs.getString("account_email", null)
        val account = email?.let { Account(it, prefs.getString("account_name", it) ?: it) }
        AdBlock.init(applicationContext)
        st = BrowserState(dark, Store(this), this, account)
        // 1.0.3: a release found earlier (at launch or by the background check) shows its home banner right away.
        st.update = Updater.cached(this, APP_VERSION)
        currentState = st
        // Tabs come back exactly as they were (history and scroll) after the app was killed.
        // 1.0.0: the state files are read on a background thread (the app opens on home, so no page needs
        // them for the first frame); a WebView being created waits for them if they are not in yet.
        st.loadStateAsync(filesDir)
        // 1.0.4: the small built-in list blocks at once; the full filter lists load (or download, the first time) after it.
        Thread {
            Blocker.load(applicationContext)
            if (!isRobolectric) { android.os.SystemClock.sleep(1500); runCatching { AdBlock.load(applicationContext) } }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
        // 1.0.0: last crash report read off the main thread. It shows unless something newer already did
        // (before, a notice from the launch intent replaced it the same way).
        Bg.run {
            if (!crashFile.exists()) return@run
            val report = runCatching { crashFile.readText() }.getOrDefault("")
            crashFile.delete()
            if (report.isNotBlank()) Bg.main.post {
                if (st.notice == null) st.notify("Browser closed last time", "Copy report") { st.host.copy(report); st.notify("Report copied, send it to me") }
            }
        }
        st.textZoom = prefs.getInt("text_zoom", 100)
        st.webSignedIn = prefs.getBoolean("web_signed_in", false)
        // 0.5.0: the app opens on the home page; the last page waits behind "Back to previous tab".
        val linkIntent = intent?.action == Intent.ACTION_VIEW
        if (!linkIntent && savedInstanceState == null) st.leaveToHome()
        handleIntent(intent)
        ContextCompat.registerReceiver(this, doneReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        startDownloads()
        // Load the web engine while the app is idle, so the first page opens faster.
        // 1.0.1: only AFTER the opening animation. Before, this 100-300 ms job could land between two
        // frames of the animation (the "sometimes lags"); now it waits for the intro to finish.
        // Sign-in with a Google account is required once, on first launch.
        val gate = !isRobolectric

        setContent {
            // 1.0.1: one shared clock for the opening animation; starts on the first drawn frame.
            val intro = remember { IntroClock() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                firstFrameDrawn = true
                intro.t.animateTo(INTRO_MS.toFloat(), tween(INTRO_MS, easing = LinearEasing))
                intro.done = true
                st.introDone = true
                afterIntro(prefs)
            }
            LaunchedEffect(st.dark) {
                applyBars(st.dark)
                prefs.edit().putBoolean("dark", st.dark).apply()
                window.setBackgroundDrawable(ColorDrawable(if (st.dark) DARK_BG else AColor.WHITE))
            }
            LaunchedEffect(st.textZoom) { prefs.edit().putInt("text_zoom", st.textZoom).apply() }
            LaunchedEffect(st.webSignedIn) { prefs.edit().putBoolean("web_signed_in", st.webSignedIn).apply() }
            LaunchedEffect(st.account) {
                st.account?.let { prefs.edit().putString("account_email", it.email).putString("account_name", it.name).apply() }
            }
            val needSignIn = gate && (st.account == null || st.webSignInRequested)
            AnimatedContent(
                needSignIn,
                transitionSpec = { fadeIn(tween(320, easing = EaseOut)) togetherWith fadeOut(tween(200)) },
                label = "gate"
            ) { need ->
                CompositionLocalProvider(LocalIntro provides intro) {
                    if (need) SignInScreen(st) else BrowserApp(st)
                }
            }
            // 1.0.1: first launch only (after sign-in): offer to become the default browser, once.
            LaunchedEffect(needSignIn, st.introDone) {
                if (!isRobolectric && !needSignIn && st.introDone && !prefs.getBoolean("asked_default", false)) {
                    kotlinx.coroutines.delay(600)
                    prefs.edit().putBoolean("asked_default", true).apply()
                    if (!st.isDefault && st.card == null) st.card = Card.DefaultBrowser
                }
            }
        }
    }

    private val doneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (!::st.isInitialized || id !in st.store.downloads) return
            val d = queryDownloads(listOf(id)).firstOrNull() ?: return
            if (d.ok && (d.name.endsWith(".apk", true) || d.mime == APK)) st.notify("Downloaded ${d.name}", "Install") { downloadFile(id)?.let { installApk(it) } }
            else if (d.ok) st.notify("Downloaded ${d.name}", "Open") { openDownload(id) }
            else if (d.failed) st.notify("Download failed", "View") { st.openDownloads(Screen.Home) }
        }
    }

    /** 1.0.2: Browser's own downloads: messages when one ends, and downloads cut off earlier carry on. */
    private fun startDownloads() {
        DlEngine.init(this)
        DlEngine.webReady = true
        DlEngine.onEnd = { t ->
            if (st.store.downloads.contains(t.id)) {
                val id = t.id
                if (t.status == DlState.DONE && (t.name.endsWith(".apk", true) || t.mime == APK)) st.notify("Downloaded ${t.name}", "Install") { downloadFile(id)?.let { installApk(it) } }
                else if (t.status == DlState.DONE) st.notify("Downloaded ${t.name}", "Open") { openDownload(id) }
                else st.notify("Download failed", "View") { st.openDownloads(Screen.Home) }
            }
        }
        val cut = DlEngine.killedBySystem
        DlEngine.killedBySystem = false
        DlEngine.continueInterrupted()
        // MIUI and some other phones close apps in the background to save battery. Say so once, with a way out.
        if (cut && !st.store.flag("hint_battery") && !isRobolectric) {
            st.store.setFlag("hint_battery")
            st.notify("Your phone stopped a download. Allow Browser to run in the background", "Settings") {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        }
    }

    override fun onDestroy() {
        DlEngine.webReady = false
        DlEngine.onEnd = null
        runCatching { unregisterReceiver(doneReceiver) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        // 0.8.0: home-screen widget buttons. Opening from a widget or a link is not "coming back", so skip the home reset.
        if (i?.action in setOf(ACT_SEARCH, ACT_PRIVATE, ACT_DOWNLOADS, ACT_VOICE, ACT_UPDATE, Intent.ACTION_VIEW)) {
            leftByUser = false
            // 0.9.9: a widget or a link always lands on a clean screen
            st.quick = false; st.card = null
            if (i?.action != ACT_VOICE) { st.ai = null; st.voice = false }
        }
        when (i?.action) {
            ACT_SEARCH -> { st.viewing = null; if (st.active?.blank != true) st.newTab(); st.screen = Screen.Search; return }
            ACT_PRIVATE -> { st.viewing = null; st.newTab(private = true); st.screen = Screen.Search; return }
            ACT_DOWNLOADS -> { st.viewing = null; st.openDownloads(Screen.Home); return }
            // 1.0.3: the update notification opens the update card
            ACT_UPDATE -> {
                st.viewing = null
                UpdateJob.clear(this)
                if (st.update == null) st.update = Updater.cached(this, APP_VERSION)
                if (st.update != null) st.card = Card.Update else checkUpdates(manual = true)
                return
            }
            ACT_VOICE -> {
                st.viewing = null
                if (st.active?.blank != true) st.newTab()
                st.screen = Screen.Home
                window.decorView.post { voiceSearch() }
                return
            }
        }
        val data = i?.data ?: return
        if (i.action == Intent.ACTION_VIEW && (data.scheme == "http" || data.scheme == "https")) {
            val a = st.active
            if (a != null && a.blank) st.open(data.toString()) else st.openInNewTab(data.toString())
            // 1.0.1: Back on this page's first step returns to the app the link came from (like Chrome).
            st.active?.fromApp = true
        }
    }

    override fun onPause() {
        super.onPause()
        // A page playing audio or video keeps playing in the background (and in picture-in-picture).
        if (::st.isInitialized) {
            st.tabs.forEach { if (!(it.mediaPlaying && it.id == st.activeId)) it.view?.onPause() }
            st.saveTabs()
        }
        // 1.0.0: flush() blocks on disk I/O; done on the background thread.
        Bg.run { CookieManager.getInstance().flush() }
    }

    override fun onStop() {
        super.onStop()
        if (!::st.isInitialized) return
        runCatching { st.persistState(filesDir) }
        runCatching { WidgetStats.save(this) }
        // 1.0.0: widget redraw parses history JSON and talks to the launcher; done on the background thread.
        val app = applicationContext
        Bg.run { Widgets.refresh(app) }
        if (st.lockPrivate && st.tabs.any { it.private }) st.privateLocked = true
    }

    override fun onResume() {
        super.onResume()
        if (::st.isInitialized && leftByUser) {
            leftByUser = false
            val t = st.active
            if (!st.pip && t != null && !t.mediaPlaying && st.fullView == null) st.leaveToHome()
        }
        if (::st.isInitialized) { st.active?.view?.onResume(); st.isDefault = isDefaultBrowser() }
        pendingUpdate?.let { f ->
            pendingUpdate = null
            if (packageManager.canRequestPackageInstalls()) launchInstaller(f)
            else { st.updateProgress = -1f; st.updateStatus = "Allow \"Install unknown apps\" for Browser to update"; if (st.update != null) st.card = Card.Update }
        }
        // Back from "Install unknown apps": continue the install that was waiting.
        pendingInstall?.let { f ->
            pendingInstall = null
            if (packageManager.canRequestPackageInstalls()) installApk(f)
            else st.notify("Installing needs \"Allow from this source\"", "Settings") { installApk(f) }
        }
    }

    /** True when the person left the app themselves (home / recents), not when we opened another app. */
    private var leftByUser = false

    override fun onStart() {
        super.onStart()
        // 1.0.1: the "came back, show home" reset moved to onResume. onStart runs BEFORE onNewIntent, so a
        // widget tap first jumped to home and then to the widget's screen (two screen changes, one wasted
        // frame); in onResume the widget intent has already cleared leftByUser and opens straight away.
        // 1.0.5: coming back to the app (MIUI keeps it open in the background) also checks for updates.
        if (::st.isInitialized && st.introDone) Bg.main.postDelayed({ autoCheck() }, 800L)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Opening the installer, a share sheet or Settings from Browser is not leaving it.
        leftByUser = android.os.SystemClock.uptimeMillis() - ownLaunchAt > 1500
        if (!::st.isInitialized || Build.VERSION.SDK_INT < 26) return
        val t = st.active ?: return
        if (!t.mediaPlaying || t.blank) return
        // Going home with a video playing: keep watching in a small window.
        runCatching {
            val w = if (videoW > 0) videoW else 16; val h = if (videoH > 0) videoH else 9
            val r = android.util.Rational(w, h).let { q ->
                val v = q.toFloat()
                when { v > 2.39f -> android.util.Rational(239, 100); v < 0.42f -> android.util.Rational(42, 100); else -> q }
            }
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(r).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::st.isInitialized) st.pip = isInPictureInPictureMode
    }

    private fun applyBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
        else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    @Suppress("DEPRECATION")
    private fun preferHighestRefreshRate() {
        val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        d ?: return
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.modeId != cur.modeId) {
            window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        }
    }

    // ---------------- BrowserHost ----------------
    override fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            fileLauncher.launch(params.createIntent()); true
        } catch (e: Exception) {
            fileCallback = null; callback.onReceiveValue(null); false
        }
    }

    /**
     * 1.0.3: hand a real app link (phone, email, maps, Play Store, app deep link) to its app.
     * Web links never leave: an http/https target, or one another browser would open, comes back
     * to Browser (or its web fallback). Returns false when no app opened it.
     */
    override fun openExternal(url: String): Boolean = try {
        val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
            addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
        } else Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val fallback = intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val target = intent.data?.scheme?.lowercase()
        when {
            target == "http" || target == "https" -> { st.open(intent.dataString!!); true }
            intent.`package` != null && intent.`package` in LinkRouter.BROWSER_PACKAGES -> fallback?.let { st.open(it); true } ?: false
            opensInBrowser(intent) -> fallback?.let { st.open(it); true } ?: false
            else -> try { startActivity(intent); true }
            catch (e: ActivityNotFoundException) {
                // intent:// links often carry a web fallback
                fallback?.let { st.open(it); true } ?: false
            }
        }
    } catch (e: Exception) { false }

    /** True when the app that would open this is a web browser (other than Browser). */
    private fun opensInBrowser(i: Intent): Boolean = runCatching {
        val pkg = packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?: return@runCatching false
        if (pkg == packageName || pkg == "android") return@runCatching false
        if (pkg in LinkRouter.BROWSER_PACKAGES) return@runCatching true
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val browsers = packageManager.queryIntentActivities(web, android.content.pm.PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        // A browser that also owns this app link only counts if it is the generic web handler for this link too.
        pkg in browsers && i.data?.scheme?.lowercase() !in setOf("tel", "mailto", "sms", "smsto", "geo", "market")
    }.getOrDefault(false)

    override fun download(url: String, userAgent: String, contentDisposition: String, mime: String) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            st.notify("This page made the file itself, so it can't be saved yet"); return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            st.notify("This file can't be downloaded"); return
        }
        try {
            // 0.7.0: the same file already on its way is not started twice
            val running = queryDownloads(st.store.downloads.toList()).firstOrNull { it.running && it.src == url }
            if (running != null) {
                if (running.paused) resumeDownload(running.id)
                st.notify("Already downloading ${running.name}", "View") { st.openDownloads(Screen.Home) }; return
            }
            val name = DlName.of(url, contentDisposition, mime)
            val type = DlName.typeOf(name, mime)
            // 1.0.2: Browser downloads the file itself (several connections, pause and continue, keeps going
            // in the background) instead of handing it to the phone's download app. Same cookies, user agent
            // and page address as the web page, so files behind a sign-in still work.
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
            val referer = st.active?.url?.takeIf { it.startsWith("http") }.orEmpty()
            askNotificationsOnce()
            val t = DlEngine.add(this, url, name, type, userAgent, cookie, referer)
            st.store.addDownload(t.id)
            st.notify("Downloading ${t.name}", "View") { st.openDownloads(Screen.Home) }
        } catch (e: Exception) {
            st.notify("This file can't be downloaded")
        }
    }

    // 1.0.2: Android 13+ asks before showing notifications; asked once, at the first download.
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) DlService.sync(this)
    }
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || DlService.canNotify(this) || st.store.flag("asked_notif") || isRobolectric) return
        st.store.setFlag("asked_notif")
        runCatching { notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    private fun DlTask.toDl() = Dl(id, name, status, done, total, mime, url, if (finished > 0) finished else created, reason,
        if (status == DlState.RUNNING) speed else 0.0, note, resumable, own = true)

    override fun pauseDownload(id: Long) {
        DlEngine.pause(id)
        DlEngine.get(id)?.let { if (!it.resumable) st.notify("This site can't continue a download, so it will start over") }
    }
    override fun resumeDownload(id: Long) {
        if (!DlEngine.has(id)) return
        askNotificationsOnce()
        DlEngine.resume(id)
    }

    override fun queryDownloads(ids: List<Long>): List<Dl> {
        if (ids.isEmpty()) return emptyList()
        val found = HashMap<Long, Dl>()
        // 1.0.2: Browser's own downloads first; older ones still come from the phone's download app
        val old = ids.filter { id -> DlEngine.get(id)?.let { found[id] = it.toDl() } == null }
        if (old.isEmpty()) return ids.mapNotNull { found[it] }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(*old.toLongArray()))?.use { c ->
            val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val iDone = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val iMime = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val iUri = c.getColumnIndex(DownloadManager.COLUMN_URI)
            val iTime = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            val iReason = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                found[id] = Dl(
                    id, c.getString(iTitle) ?: "File", c.getInt(iStatus),
                    c.getLong(iDone), c.getLong(iTotal), c.getString(iMime) ?: "",
                    (if (iUri >= 0) c.getString(iUri) else null) ?: "", if (iTime >= 0) c.getLong(iTime) else 0L,
                    if (iReason >= 0) c.getInt(iReason) else 0
                )
            }
        }
        return ids.mapNotNull { found[it] }
    }

    override fun cancelDownload(id: Long) {
        if (DlEngine.has(id)) { DlEngine.remove(id); return }
        runCatching { (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
    }

    override fun openDownload(id: Long) {
        // 0.5.0: files open inside the browser; the viewer offers "Open with" for anything else.
        val f = downloadFile(id)
        if (f == null) st.notify("This file is gone from the phone") else st.viewing = f
    }

    override fun downloadFile(id: Long): FileItem? = runCatching {
        DlEngine.get(id)?.let { t ->
            val uri = DlEngine.fileUri(t) ?: return null
            return FileItem(uri, t.name, DlName.typeOf(t.name, t.mime), id)
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id) ?: return null
        val d = queryDownloads(listOf(id)).firstOrNull()
        val name = d?.name ?: "File"
        val type = DlName.typeOf(name, (dm.getMimeTypeForDownloadedFile(id) ?: d?.mime).orEmpty())
        FileItem(uri, name, type, id)
    }.getOrNull()

    override fun openWith(f: FileItem) {
        if (isApk(f)) { installApk(f); return }
        try {
            val type = DlName.typeOf(f.name, f.mime).ifBlank { "*/*" }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
        } catch (e: Exception) {
            st.notify("No app on this phone can open this file")
        }
    }

    // ---- 0.7.0: installing a downloaded app ----
    private fun isApk(f: FileItem) = f.mime == APK || f.name.endsWith(".apk", true)
    private var pendingInstall: FileItem? = null

    override fun installApk(f: FileItem) {
        if (!packageManager.canRequestPackageInstalls()) {
            // Android asks once: Browser must be allowed to install apps. We come back and continue.
            pendingInstall = f
            st.notify("Turn on \"Allow from this source\", then come back")
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { st.notify("Allow Browser to install apps in Settings") }
            return
        }
        st.notify("Opening installer…")
        Thread {
            // The installer reads a private copy shared through our FileProvider; works on every phone.
            val uri = runCatching {
                val dir = java.io.File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val safe = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".apk", true)) it else "$it.apk" }
                val out = java.io.File(dir, safe)
                contentResolver.openInputStream(f.uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", out)
            }.getOrNull()
            runOnUiThread {
                if (uri == null) { st.notify("Can't read this file. Try downloading it again"); return@runOnUiThread }
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(view) } catch (e: ActivityNotFoundException) {
                    @Suppress("DEPRECATION")
                    runCatching { startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        .onFailure { st.notify("This phone has no app installer") }
                }
            }
        }.start()
    }

    /** True for a moment after Browser itself opens another screen (installer, share, settings, file picker). */
    private var ownLaunchAt = 0L
    override fun startActivity(intent: Intent?, options: Bundle?) {
        ownLaunchAt = android.os.SystemClock.uptimeMillis(); super.startActivity(intent, options)
    }
    override fun startActivity(intent: Intent?) {
        ownLaunchAt = android.os.SystemClock.uptimeMillis(); super.startActivity(intent)
    }
    @Deprecated("framework")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ownLaunchAt = android.os.SystemClock.uptimeMillis(); super.startActivityForResult(intent, requestCode, options)
    }

    override fun shareFile(f: FileItem) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = f.mime.ifBlank { "*/*" }; putExtra(Intent.EXTRA_STREAM, f.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, null))
        }
    }

    override fun deleteDownload(id: Long) {
        if (DlEngine.has(id)) { DlEngine.remove(id); st.store.forgetDownloads(listOf(id)); return }
        runCatching { (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
        st.store.forgetDownloads(listOf(id))
    }

    override fun retryDownload(d: Dl) {
        // 1.0.2: continue from where it stopped when the site allows it
        if (DlEngine.has(d.id)) { resumeDownload(d.id); return }
        if (d.src.isBlank()) { st.notify("Open the page again to download this file"); return }
        deleteDownload(d.id)
        download(d.src, st.active?.view?.settings?.userAgentString ?: "", "attachment; filename=\"${d.name}\"", d.mime)
    }

    private val anyFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "File"
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        }
        val type = contentResolver.getType(uri).orEmpty().ifBlank { mimeOf(name) }
        st.viewing = FileItem(uri, name, type)
    }

    override fun pickAnyFile() {
        runCatching { anyFileLauncher.launch(arrayOf("*/*")) }.onFailure { st.notify("Can't open files on this phone") }
    }

    override fun copy(text: String) {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
        }
    }

    override fun share(url: String, title: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url); putExtra(Intent.EXTRA_SUBJECT, title)
            }, null))
        }
    }

    override fun openDownloads() {
        runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun pixelRow(x: Int, y: Int, w: Int, done: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < 26 || w <= 0) { done(null); return }
        runCatching {
            val bw = 24
            val bmp = android.graphics.Bitmap.createBitmap(bw, 1, android.graphics.Bitmap.Config.ARGB_8888)
            val dw = window.decorView.width; val dh = window.decorView.height
            val rect = android.graphics.Rect(x.coerceAtLeast(0), y.coerceIn(0, (dh - 1).coerceAtLeast(0)), (x + w).coerceAtMost(dw), (y + 1).coerceIn(1, dh.coerceAtLeast(1)))
            if (rect.isEmpty || dw <= 0) { bmp.recycle(); done(null); return }
            android.view.PixelCopy.request(window, rect, bmp, { r ->
                val c = if (r == android.view.PixelCopy.SUCCESS) dominant(bmp, 0, bw) else null
                bmp.recycle(); done(c)
            }, android.os.Handler(Looper.getMainLooper()))
        }.onFailure { done(null) }
    }

    override fun snapshot(x: Int, y: Int, w: Int, h: Int, scale: Float, done: (android.graphics.Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 26 || w <= 0 || h <= 0 || window.decorView.width <= 0) { done(null); return }
        runCatching {
            val dw = window.decorView.width; val dh = window.decorView.height
            val r = android.graphics.Rect(x.coerceAtLeast(0), y.coerceAtLeast(0), (x + w).coerceAtMost(dw), (y + h).coerceAtMost(dh))
            if (r.isEmpty) { done(null); return }
            val bmp = android.graphics.Bitmap.createBitmap((r.width() * scale).toInt().coerceAtLeast(1), (r.height() * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(window, r, bmp, { res ->
                if (res == android.view.PixelCopy.SUCCESS) done(bmp) else { bmp.recycle(); done(null) }
            }, android.os.Handler(Looper.getMainLooper()))
        }.onFailure { done(null) }
    }

    override suspend fun googleWebLogin(email: String): String? = kotlinx.coroutines.withTimeoutOrNull(45000) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            runCatching {
                val am = AccountManager.get(this@MainActivity)
                val acc = am.getAccountsByType("com.google").firstOrNull { it.name.equals(email, true) }
                    ?: android.accounts.Account(email, "com.google")
                val scope = "weblogin:service=mail&continue=" + Uri.encode("https://myaccount.google.com/")
                am.getAuthToken(acc, scope, null, this@MainActivity, { f ->
                    val url = runCatching { f.result.getString(AccountManager.KEY_AUTHTOKEN) }.getOrNull()
                    if (cont.isActive) cont.resumeWith(Result.success(url?.takeIf { it.startsWith("https://") }))
                }, android.os.Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
        }
    }

    override fun barIcons(lightStatus: Boolean, lightNav: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.isAppearanceLightStatusBars = lightStatus
        c.isAppearanceLightNavigationBars = lightNav
    }

    override fun setFullscreen(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------- 1.1.0 ----------------
    override fun haptic() { window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }

    override fun requestPerms(perms: Array<String>, done: (Boolean) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { done(true); return }
        permDone?.invoke(false)
        permDone = done
        runCatching { permLauncher.launch(missing.toTypedArray()) }.onFailure { permDone = null; done(false) }
    }

    override fun voiceSearch() { st.voice = true }

    override fun frost(done: (android.graphics.Bitmap?) -> Unit) {
        val d = window.decorView
        val w = d.width / 10; val h = d.height / 10
        if (w <= 2 || h <= 2 || android.os.Build.VERSION.SDK_INT < 26) { done(null); return }
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(window, b, { r ->
                if (r == android.view.PixelCopy.SUCCESS) Thread { boxBlur(b); runOnUiThread { done(b) } }.start() else done(null)
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { done(null) }
    }

    override fun systemVoice() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to search")
        runCatching { voiceLauncher.launch(i) }.onFailure { st.notify("Voice search isn't available on this phone") }
    }

    override fun printPage(view: android.webkit.WebView, title: String) {
        runCatching {
            val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            val name = title.ifBlank { "Page" }.take(60)
            pm.print(name, view.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
        }.onFailure { st.notify("Couldn't save this page") }
    }

    override fun addToHome(url: String, title: String, icon: android.graphics.Bitmap?) {
        runCatching {
            if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                st.notify("Your home screen doesn't support shortcuts"); return
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java)
            val bmp = homeIcon(title.ifBlank { hostOf(url) }, icon)
            val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "site:" + url.hashCode())
                .setShortLabel(title.ifBlank { hostOf(url) }.take(24))
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap(bmp))
                .setIntent(intent).build()
            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this, info, null)
        }.onFailure { st.notify("Couldn't add to home screen") }
    }

    /** Adaptive icon: the site's icon on a white disc, or its first letter in black. */
    private fun homeIcon(title: String, icon: android.graphics.Bitmap?): android.graphics.Bitmap {
        val size = 216
        val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(AColor.WHITE)
        if (icon != null && icon.width >= 16) {
            val s = 96f
            c.drawBitmap(icon, null, android.graphics.RectF(size / 2f - s / 2, size / 2f - s / 2, size / 2f + s / 2, size / 2f + s / 2),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        } else {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = AColor.BLACK; textSize = 84f; textAlign = android.graphics.Paint.Align.CENTER
                typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(this@MainActivity, R.font.inter) }.getOrNull()
                    ?.let { android.graphics.Typeface.create(it, android.graphics.Typeface.BOLD) } ?: android.graphics.Typeface.DEFAULT_BOLD
            }
            val y = size / 2f - (p.descent() + p.ascent()) / 2
            c.drawText(title.take(1).uppercase(), size / 2f, y, p)
        }
        return b
    }

    override fun unlockPrivate(done: (Boolean) -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!km.isDeviceSecure) { done(true); return }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle("Unlock private tabs")
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                prompt.authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { done(true) }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { done(false) }
                })
                return
            }
        }
        @Suppress("DEPRECATION")
        val i = km.createConfirmDeviceCredentialIntent("Unlock private tabs", null)
        if (i == null) { done(true); return }
        unlockDone = done
        runCatching { unlockLauncher.launch(i) }.onFailure { unlockDone = null; done(true) }
    }

    override fun mediaChanged() {
        // Nothing to start: the page keeps playing because hidden-tab pausing skips it (see onPause).
        if (!::st.isInitialized) return
        if (st.tabs.none { it.mediaPlaying }) { videoW = 0; videoH = 0 }
        else st.active?.takeIf { it.mediaPlaying }?.view?.evaluateJavascript(Scripts.VIDEO_SHAPE) { r ->
            val s = r?.trim('"').orEmpty()
            videoW = s.substringBefore(',').toIntOrNull() ?: 0
            videoH = s.substringAfter(',', "").toIntOrNull() ?: 0
        }
    }

    override fun videoShape(w: Int, h: Int) {
        if (w > 0 && h > 0) { videoW = w; videoH = h }
        requestedOrientation = when {
            w <= 0 || h <= 0 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            w > h -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun knownAccounts(): List<Account> = runCatching {
        AccountManager.get(this).getAccountsByType("com.google").map { Account(it.name, it.name.substringBefore('@')) }
    }.getOrDefault(emptyList())

    override suspend fun signIn(): Result<Account> {
        val d = CompletableDeferred<Result<Account>>()
        accountResult = d
        return try {
            // System Google account picker: no API key or OAuth client needed.
            val i = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"), null, null, null, null
            )
            accountLauncher.launch(i)
            d.await()
        } catch (e: ActivityNotFoundException) {
            Result.failure(IllegalStateException("Google accounts aren't available on this phone."))
        } finally {
            accountResult = null
        }
    }
}

@Composable
fun BrowserApp(st: BrowserState) {
    val pal = if (st.dark) DarkPal else LightPal
    val scope = rememberCoroutineScope()
    val sheets = remember {
        mapOf(
            Screen.Search to SheetAnim(0), Screen.Tabs to SheetAnim(1), Screen.More to SheetAnim(2),
            Screen.Downloads to SheetAnim(3), Screen.Lists to SheetAnim(4), Screen.Reader to SheetAnim(5)
        )
    }
    val goHome = remember { { st.screen = Screen.Home } }

    // Back inside a page goes back in its history; leaving full-screen video comes first.
    // Only the visible tab keeps working; hidden tabs pause their timers and animations.
    LaunchedEffect(st.activeId, st.tabs.size) {
        st.trimViews(3)
        st.tabs.forEach { t ->
            val on = t.id == st.activeId
            if (on) t.view?.onResume() else t.view?.onPause()
            // Only the visible page draws tiles ahead of the screen.
            t.view?.settings?.offscreenPreRaster = on
        }
        if (st.finding) { st.finding = false }
    }
    BackHandler(enabled = st.link != null) { st.link = null }
    // 1.0.1: Back while a page is open works like Chrome. Full-screen video and the find bar close first,
    // then exactly ONE step back in this tab's history (read live from the WebView, so it never relies on a
    // flag that can be a page behind). With no history left: back to the tab or app that opened the page,
    // else to home. It stays on as long as a page is in front, so Back can't fall through and close the app.
    BackHandler(enabled = st.link == null && st.screen == Screen.Home && (st.fullView != null || st.active?.blank == false)) {
        when {
            st.fullView != null -> st.fullCallback?.onCustomViewHidden()
            st.finding -> st.finding = false
            else -> st.pageBack()
        }
    }
    PredictiveBackHandler(enabled = st.link == null && st.screen != Screen.Home) { flow ->
        val sh = sheets[st.screen] ?: return@PredictiveBackHandler
        try {
            flow.collect { e -> sh.p.snapTo(1f - 0.35f * e.progress) }
            st.back()
        } catch (e: CancellationException) {
            scope.launch { sh.p.animateTo(1f, Motion.settle) }
            throw e
        }
    }

    // Cards and the permission ask close before anything else.
    BackHandler(enabled = st.card != null) { st.card = null }
    BackHandler(enabled = st.perm != null) { st.perm?.done?.invoke(false) }
    BackHandler(enabled = st.ai != null && !st.quick && !st.voice) { st.ai = null }
    BackHandler(enabled = st.quick) { st.quick = false }
    BackHandler(enabled = st.voice) { st.voice = false }

    // Any tab change shows the full bar again and reads the new page's colour.
    LaunchedEffect(st.activeId) { st.barCollapsed = false; st.active?.let { scheduleSample(it, 80) } }
    // Status and navigation bar icons follow the page colour while a page is in front, else the theme.
    val front = st.active?.takeIf { !it.blank && st.screen == Screen.Home }
    val statusLight = front?.let { (it.topColor ?: pal.bg).luminance() > 0.45f } ?: !st.dark
    val navLight = front?.let { (it.bottomColor ?: it.topColor ?: pal.bg).luminance() > 0.45f } ?: !st.dark
    LaunchedEffect(statusLight, navLight, st.dark) { st.host.barIcons(statusLight, navLight) }

    // A picture of the page for the Tabs grid, taken as the grid opens.
    LaunchedEffect(st.screen) { if (st.screen == Screen.Tabs) st.active?.let { captureThumb(it) } }

    // ---- theme change: crossfade from a snapshot that is only recorded when needed ----
    val capture = rememberGraphicsLayer()
    var recording by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(st.themeRequests) {
        if (st.themeRequests == 0) return@LaunchedEffect
        recording = true
        withFrameNanos { }; withFrameNanos { }
        val snap = runCatching { capture.toImageBitmap() }.getOrNull()
        recording = false
        frozen = snap
        fade.snapTo(0f)
        st.dark = !st.dark
        if (snap != null) fade.animateTo(1f, tween(320, easing = EaseOut))
        frozen = null
    }

    CompositionLocalProvider(LocalPal provides pal) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val img = frozen
                    when {
                        recording -> { capture.record { this@drawWithContent.drawContent() }; drawLayer(capture) }
                        img != null -> { drawContent(); drawImage(img, alpha = 1f - fade.value) }
                        else -> drawContent()
                    }
                }
                .background(pal.bg)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val m = sheets.values.maxOf { it.p.value }.coerceIn(0f, 1f)
                        if (m > 0.001f) drawRect(pal.bg.copy(alpha = 0.6f * m))
                    }
            ) { HomeScreen(st) }

            Sheet(st.screen == Screen.Tabs, sheets.getValue(Screen.Tabs), goHome) { TabsScreen(st) }
            Sheet(st.screen == Screen.Search, sheets.getValue(Screen.Search), goHome) {
                SearchScreen(st, st.screen == Screen.Search)
            }
            val moreUnder = st.screen == Screen.More || st.screen == Screen.Lists ||
                (st.screen == Screen.Downloads && st.downloadsBack == Screen.More)
            Sheet(moreUnder, sheets.getValue(Screen.More), goHome) { MoreScreen(st) }
            Sheet(st.screen == Screen.Lists, sheets.getValue(Screen.Lists), { st.screen = Screen.More }) { ListScreen(st) }
            Sheet(st.screen == Screen.Downloads, sheets.getValue(Screen.Downloads), { st.back() }) {
                DownloadsScreen(st, st.screen == Screen.Downloads)
            }
            Sheet(st.screen == Screen.Reader, sheets.getValue(Screen.Reader), goHome) { ReaderScreen(st) }
            FileViewer(st)
            AiScreen(st)
            PrivateLock(st)
            if (!st.pip) {
                val lift = if (st.screen == Screen.Home && st.active?.blank == true && st.backTab != null) 160.dp else 86.dp
                NoticePill(st, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = lift))
                LinkMenu(st)
                Cards(st)
                QuickSettings(st)
                VoiceScreen(st)
                PermCard(st)
            }

            st.fullView?.let { v -> androidx.compose.runtime.key(v) {
                AndroidView(
                    factory = { ctx ->
                        (v.parent as? android.view.ViewGroup)?.removeView(v)
                        android.widget.FrameLayout(ctx).apply { setBackgroundColor(AColor.BLACK); addView(v) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } }
        }
    }
}

class CrashSaver(private val f: java.io.File, private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching { f.writeText("Browser $APP_VERSION\n" + android.util.Log.getStackTraceString(e)) }
        next?.uncaughtException(t, e)
    }
}

