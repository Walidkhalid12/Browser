package com.khalid.browser

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrain
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStarted
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 1.0.0 performance: one low-priority background thread for disk work that used to run on the
 * main thread (tab state files, the crash report, widget redraws, cookie flush). A single thread
 * keeps the writes in order, so a read queued after a write always sees the finished file.
 */
object Bg {
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "browser-io").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    val main = Handler(Looper.getMainLooper())

    fun run(block: () -> Unit) { runCatching { io.execute { runCatching(block) } } }
}

/** Suspends while the app is in the background (below STARTED); returns at once when it is visible. */
suspend fun Lifecycle.awaitStarted() {
    if (currentState.isAtLeast(Lifecycle.State.STARTED)) return
    try { withStarted { } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
}

// ---- 1.0.0: size modifiers that read an animated value in the layout phase, not in composition ----
// They measure exactly like Modifier.size / width / fillMaxWidth(fraction), so the look is unchanged,
// but an animation frame only re-runs layout instead of recomposing the whole screen.

/** Like Modifier.size(dp) with an animated value. */
fun androidx.compose.ui.Modifier.sizeOf(dp: () -> androidx.compose.ui.unit.Dp): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.layout { m, c ->
        val px = dp().roundToPx().coerceAtLeast(0)
        val p = m.measure(c.constrain(androidx.compose.ui.unit.Constraints(px, px, px, px)))
        layout(p.width, p.height) { p.place(0, 0) }
    })

/** Like Modifier.width(dp) with an animated value. */
fun androidx.compose.ui.Modifier.widthOf(dp: () -> androidx.compose.ui.unit.Dp): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.layout { m, c ->
        val px = dp().roundToPx().coerceAtLeast(0)
        val p = m.measure(c.constrain(androidx.compose.ui.unit.Constraints(px, px, 0, androidx.compose.ui.unit.Constraints.Infinity)))
        layout(p.width, p.height) { p.place(0, 0) }
    })

/** Like Modifier.fillMaxWidth(fraction) with an animated fraction. */
fun androidx.compose.ui.Modifier.fillWidthOf(fraction: () -> Float): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.layout { m, c ->
        val cc = if (c.hasBoundedWidth) {
            val w = (c.maxWidth * fraction()).roundToInt().coerceIn(c.minWidth, c.maxWidth)
            c.copy(minWidth = w, maxWidth = w)
        } else c
        val p = m.measure(cc)
        layout(p.width, p.height) { p.place(0, 0) }
    })
