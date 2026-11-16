package com.khalid.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.webkit.MimeTypeMap
import android.widget.VideoView
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

// =====================================================================
// 0.5.0 FILES: downloads (and any file on the phone) open right inside the browser
// =====================================================================

fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.US)) ?: ""

enum class FileKind { Image, Video, Audio, Pdf, Text, Web, Apk, Other }

private val textExt = setOf("txt", "md", "json", "xml", "csv", "log", "js", "css", "kt", "java", "py", "c", "cpp", "h", "sh", "yml", "yaml", "ini", "conf", "srt", "vtt", "ts", "gradle", "properties", "toml", "sql")

fun kindOf(name: String, mime: String): FileKind {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    val m = mime.lowercase(Locale.US).ifBlank { mimeOf(name) }
    return when {
        ext in setOf("html", "htm", "xhtml", "svg", "mht", "mhtml") || m == "text/html" || m == "image/svg+xml" -> FileKind.Web
        m.startsWith("image/") -> FileKind.Image
        m.startsWith("video/") -> FileKind.Video
        m.startsWith("audio/") || m == "application/ogg" -> FileKind.Audio
        m == "application/pdf" || ext == "pdf" -> FileKind.Pdf
        m == "application/vnd.android.package-archive" || ext == "apk" -> FileKind.Apk
        m.startsWith("text/") || m == "application/json" || m == "application/xml" || m == "application/javascript" || ext in textExt -> FileKind.Text
        else -> FileKind.Other
    }
}

/** Short label for a file group, used by the Downloads filters. */
fun groupOf(name: String, mime: String): String = when (kindOf(name, mime)) {
    FileKind.Image -> "Images"
    FileKind.Video -> "Videos"
    FileKind.Audio -> "Audio"
    FileKind.Pdf, FileKind.Text, FileKind.Web -> "Docs"
    FileKind.Apk -> "Apps"
    FileKind.Other -> "Other"
}

@Composable
fun FileViewer(st: BrowserState) {
    val f = st.viewing
    var last by remember { mutableStateOf<FileItem?>(null) }
    if (f != null) last = f
    BackHandler(enabled = f != null) { st.viewing = null }
    AnimatedVisibility(
        f != null,
        enter = fadeIn(tween(200)) + slideInVertically(tween(280, easing = EaseOut)) { it / 6 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 6 }
    ) {
        val item = last ?: return@AnimatedVisibility
        key(item.uri) { ViewerBody(st, item) }
    }
}

@Composable
private fun ViewerBody(st: BrowserState, f: FileItem) {
    val pal = LocalPal.current
    val kind = remember(f) { kindOf(f.name, f.mime) }
    val media = kind == FileKind.Image || kind == FileKind.Video
    val bg = if (media) androidx.compose.ui.graphics.Color.Black else pal.bg
    val fg = if (media) androidx.compose.ui.graphics.Color.White else pal.fg
    var chrome by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(bg).pointerInput(Unit) { detectTapGestures { } }) {
        Box(Modifier.fillMaxSize()) {
            when (kind) {
                FileKind.Image -> ImageView(f) { chrome = !chrome }
                FileKind.Video, FileKind.Audio -> MediaView(st, f, kind == FileKind.Audio, fg) { chrome = !chrome }
                FileKind.Pdf -> PdfView(st, f)
                FileKind.Text -> TextView(f)
                FileKind.Web -> WebFileView(f)
                FileKind.Apk, FileKind.Other -> NoPreview(st, f, kind)
            }
        }
        // top bar
        AnimatedVisibility(chrome, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Row(
                Modifier.fillMaxWidth()
                    .background(if (media) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f) else pal.bg)
                    .statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.squish(0.9f) { st.viewing = null }.size(46.dp).clip(CircleShape).background(fg), contentAlignment = Alignment.Center) {
                    Glyph(G.Close, bg, 16.dp, 2.2.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(f.name, 15.sp, 600, fg)
                    T(f.name.substringAfterLast('.', "file").uppercase(Locale.US) + "  ·  " + kind.name.lowercase(Locale.US).replace("other", "file"), 12.sp, 500, fg.copy(alpha = 0.55f))
                }
                Box(Modifier.squish(0.9f) { st.host.shareFile(f) }.size(46.dp), contentAlignment = Alignment.Center) { Glyph(G.Share, fg, 20.dp) }
                Box(
                    Modifier.squish(0.94f) { st.host.openWith(f) }.height(40.dp).clip(RoundedCornerShape(20.dp)).background(fg.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) { T("Open with", 13.sp, 600, fg) }
            }
        }
    }
}

// ---------------- image: pinch to zoom, double tap ----------------
private fun decodeSampled(ctx: android.content.Context, f: FileItem, maxPx: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / (s * 2) >= maxPx || o.outHeight / (s * 2) >= maxPx) s *= 2
    ctx.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
}.getOrNull()

@Composable
private fun ImageView(f: FileItem, onTap: () -> Unit) {
    val ctx = LocalContext.current
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(f.uri) {
        val b = withContext(Dispatchers.IO) { decodeSampled(ctx, f, 2600) }
        if (b == null) failed = true else img = b.asImageBitmap()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(1f) }
    val i = img
    if (i == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T(if (failed) "Can't show this picture" else "", 15.sp, 600, androidx.compose.ui.graphics.Color.White) }; return }
    Image(
        i, f.name,
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                    val to = if (scale > 1.05f) 1f else 2.5f
                    scope.launch { anim.snapTo(scale); anim.animateTo(to, tween(220, easing = EaseOut)) { scale = value }; if (to == 1f) pan = Offset.Zero }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, p, z, _ ->
                    scale = (scale * z).coerceIn(1f, 6f)
                    pan = if (scale <= 1.01f) Offset.Zero else pan + p
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
        contentScale = ContentScale.Fit
    )
}

// ---------------- video and audio: own controls ----------------
private fun fmtTime(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun MediaView(st: BrowserState, f: FileItem, audio: Boolean, fg: androidx.compose.ui.graphics.Color, onTap: () -> Unit) {
    val pal = LocalPal.current
    var view by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var dur by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(view) {
        while (true) {
            view?.let { v -> runCatching { if (seeking == null) pos = v.currentPosition; playing = v.isPlaying; if (v.duration > 0) dur = v.duration } }
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { view?.stopPlayback() } } }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } }, contentAlignment = Alignment.Center) {
        if (!isRobolectric) AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    val v = VideoView(ctx)
                    addView(v, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
                    v.setVideoURI(f.uri)
                    v.setOnPreparedListener { mp -> dur = mp.duration; v.start() }
                    v.setOnCompletionListener { playing = false }
                    v.setOnErrorListener { _, _, _ -> error = true; true }
                    view = v
                }
            },
            modifier = if (audio) Modifier.size(1.dp) else Modifier.fillMaxSize()
        )
        if (audio) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(150.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                T("♪", 54.sp, 700, pal.bg)
            }
            Spacer(Modifier.height(20.dp))
            T(f.name.substringBeforeLast('.'), 17.sp, 600, modifier = Modifier.padding(horizontal = 30.dp))
        }
        if (error) T("This file can't play here. Try Open with.", 15.sp, 600, fg)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.navigationBarsPadding().padding(14.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp))
                .background(fg.copy(alpha = if (audio) 0.08f else 0.16f)).padding(start = 8.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.squish(0.9f) { view?.let { v -> if (v.isPlaying) v.pause() else v.start(); playing = !playing } }
                    .size(48.dp).clip(CircleShape).background(fg),
                contentAlignment = Alignment.Center
            ) { PlayGlyph(playing, if (audio) pal.bg else androidx.compose.ui.graphics.Color.Black) }
            Spacer(Modifier.width(12.dp))
            T(fmtTime(seeking?.let { (it * dur).toInt() } ?: pos), 12.sp, 600, fg)
            Spacer(Modifier.width(10.dp))
            var w by remember { mutableIntStateOf(1) }
            val frac = seeking ?: if (dur > 0) pos.toFloat() / dur else 0f
            Box(
                Modifier.weight(1f).height(36.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
                    .pointerInput(dur) {
                        detectTapGestures { o -> if (dur > 0) { val t = (o.x / w).coerceIn(0f, 1f); view?.seekTo((t * dur).toInt()); pos = (t * dur).toInt() } }
                    }
                    .pointerInput(dur) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> seeking = (o.x / w).coerceIn(0f, 1f) },
                            onDragEnd = { seeking?.let { t -> view?.seekTo((t * dur).toInt()); pos = (t * dur).toInt() }; seeking = null },
                            onDragCancel = { seeking = null }
                        ) { ch, dx -> ch.consume(); seeking = ((seeking ?: 0f) + dx / w).coerceIn(0f, 1f) }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.25f)))
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(fg))
            }
            Spacer(Modifier.width(10.dp))
            T(fmtTime(dur), 12.sp, 600, fg.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun PlayGlyph(playing: Boolean, c: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val s = size.width
        if (playing) {
            drawRoundRect(c, Offset(s * 0.18f, s * 0.1f), androidx.compose.ui.geometry.Size(s * 0.22f, s * 0.8f), androidx.compose.ui.geometry.CornerRadius(s * 0.06f))
            drawRoundRect(c, Offset(s * 0.6f, s * 0.1f), androidx.compose.ui.geometry.Size(s * 0.22f, s * 0.8f), androidx.compose.ui.geometry.CornerRadius(s * 0.06f))
        } else {
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(s * 0.24f, s * 0.08f); lineTo(s * 0.92f, s * 0.5f); lineTo(s * 0.24f, s * 0.92f); close() }
            drawPath(p, c)
        }
    }
}

// ---------------- pdf ----------------
private class Pdf(val r: PdfRenderer, val fd: android.os.ParcelFileDescriptor) { val lock = Mutex() }

@Composable
private fun PdfView(st: BrowserState, f: FileItem) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var pdf by remember { mutableStateOf<Pdf?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(f.uri) {
        pdf = withContext(Dispatchers.IO) {
            runCatching {
                val fd = ctx.contentResolver.openFileDescriptor(f.uri, "r")!!
                Pdf(PdfRenderer(fd), fd)
            }.getOrNull()
        }
        if (pdf == null) failed = true
    }
    DisposableEffect(Unit) { onDispose { pdf?.let { p -> runCatching { p.r.close(); p.fd.close() } } } }
    val p = pdf
    if (p == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (failed) T("Can't open this PDF here. Try Open with.", 15.sp, 600) }; return }
    val count = remember(p) { p.r.pageCount }
    val list = rememberLazyListState()
    var widthPx by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    Box(Modifier.fillMaxSize().onSizeChanged { widthPx = it.width }) {
        LazyColumn(
            Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 1.8f }) },
            state = list,
            contentPadding = PaddingValues(top = 96.dp, bottom = 120.dp, start = 10.dp, end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items((0 until count).toList(), key = { it }) { i -> PdfPage(p, i, widthPx) }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp)
                .clip(RoundedCornerShape(20.dp)).background(pal.fg).padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            // 1.0.0: derived, so scrolling only recomposes this label when the page number changes
            val at by remember(list, count) { derivedStateOf { (list.firstVisibleItemIndex + 1).coerceAtMost(count) } }
            T("$at / $count", 14.sp, 700, pal.bg)
        }
    }
}

@Composable
private fun PdfPage(p: Pdf, i: Int, widthPx: Int) {
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var ratio by remember { mutableFloatStateOf(1.414f) }
    LaunchedEffect(i, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            p.lock.withLock {
                runCatching {
                    p.r.openPage(i).use { page ->
                        ratio = page.height.toFloat() / page.width
                        val w = (widthPx * 1.5f).toInt().coerceAtMost(2200)
                        val b = Bitmap.createBitmap(w, (w * ratio).toInt(), Bitmap.Config.ARGB_8888)
                        b.eraseColor(android.graphics.Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color.White)) {
        bmp?.let { Image(it, "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

// ---------------- text ----------------
@Composable
private fun TextView(f: FileItem) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(f.uri) {
        lines = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openInputStream(f.uri)!!.use { s ->
                    val buf = ByteArray(1_000_000); var n = 0
                    while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    String(buf, 0, n, Charsets.UTF_8).lines()
                }
            }.getOrDefault(listOf("Can't read this file."))
        }
    }
    val code = !f.name.endsWith(".txt", true) && !f.name.endsWith(".md", true)
    val style = TextStyle(fontFamily = if (code) FontFamily.Monospace else Inter, fontSize = if (code) 13.sp else 15.sp, color = pal.fg, lineHeight = if (code) 19.sp else 23.sp)
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 100.dp, bottom = 60.dp, start = 18.dp, end = 18.dp)) {
            items(lines ?: emptyList()) { l -> BasicText(l.ifEmpty { " " }, style = style) }
        }
    }
}

// ---------------- web files (html, svg) ----------------
@Composable
private fun WebFileView(f: FileItem) {
    if (isRobolectric) return
    AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.allowContentAccess = true
                settings.javaScriptEnabled = false
                settings.builtInZoomControls = true; settings.displayZoomControls = false
                settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                loadUrl(f.uri.toString())
            }
        },
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp)
    )
}

// ---------------- anything else ----------------
@Composable
private fun NoPreview(st: BrowserState, f: FileItem, kind: FileKind) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxSize().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(pal.fg), contentAlignment = Alignment.Center) {
            T(f.name.substringAfterLast('.', "file").take(4).uppercase(Locale.US), 20.sp, 700, pal.bg)
        }
        Spacer(Modifier.height(20.dp))
        T(f.name, 17.sp, 600)
        Spacer(Modifier.height(6.dp))
        T(if (kind == FileKind.Apk) "An app for Android" else "Browser can't show this kind of file", 14.sp, 500, pal.fg.copy(alpha = 0.55f))
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier.squish(0.96f) { st.host.openWith(f) }.height(56.dp).clip(RoundedCornerShape(28.dp)).background(pal.fg).padding(horizontal = 30.dp),
            contentAlignment = Alignment.Center
        ) { T(if (kind == FileKind.Apk) "Install" else "Open with…", 16.sp, 600, pal.bg) }
    }
}

/** Small picture of an image or video download (0.7.0: videos too, with their length). */
private val thumbCache = object : android.util.LruCache<Long, ImageBitmap>(60) {}
/** Video lengths in ms, by download id. */
val mediaDur = androidx.compose.runtime.mutableStateMapOf<Long, Long>()

private fun videoFrame(ctx: android.content.Context, f: FileItem, sizePx: Int, id: Long): Bitmap? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, f.uri)
        val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (ms > 0) mediaDur[id] = ms
        val at = (ms * 1000 / 10).coerceAtMost(3_000_000L) // a frame a little way in, not the black first one
        val opt = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(at, opt, sizePx, sizePx) else r.getFrameAtTime(at, opt)
    } finally { runCatching { r.release() } }
}.getOrNull()

@Composable
fun DownloadThumb(st: BrowserState, d: Dl, sizePx: Int): ImageBitmap? {
    val ctx = LocalContext.current
    var b by remember(d.id) { mutableStateOf(thumbCache.get(d.id)) }
    LaunchedEffect(d.id, d.ok) {
        val kind = kindOf(d.name, d.mime)
        if (b != null || !d.ok || (kind != FileKind.Image && kind != FileKind.Video) || isRobolectric) return@LaunchedEffect
        b = withContext(Dispatchers.IO) {
            val f = st.host.downloadFile(d.id) ?: return@withContext null
            (if (kind == FileKind.Image) decodeSampled(ctx, f, sizePx) else videoFrame(ctx, f, sizePx, d.id))?.asImageBitmap()
        }?.also { thumbCache.put(d.id, it) }
    }
    return b
}

fun fmtDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
