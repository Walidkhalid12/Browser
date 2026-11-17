package com.khalid.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =====================================================================
// v0.4: simple, clean sheets. A screen fades in while rising a few dp; closing is the reverse,
// a little quicker. No morphing, no stretch, no bounce. Drag-down and predictive back drive the
// same progress value, so every gesture stays smooth and interruptible.
// =====================================================================

internal fun clamp01(x: Float) = x.coerceIn(0f, 1f)
internal fun smoothStep(e0: Float, e1: Float, x: Float): Float {
    val k = clamp01((x - e0) / (e1 - e0)); return k * k * (3f - 2f * k)
}

/** Standard "emphasized decelerate"-style curve: quick start, soft landing. */
val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)
val EaseIn = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
object Motion {
    val open = tween<Float>(280, easing = EaseOut)
    val close = tween<Float>(200, easing = EaseOut)
    val settle = tween<Float>(220, easing = EaseOut)
}

/** Progress of the sheet an element lives in, or null outside sheets. Read only inside draw/layer blocks. */
val LocalReveal = staticCompositionLocalOf<(() -> Float)?> { null }

class SheetAnim(val order: Int = 0) {
    val p = Animatable(0f)
}
private fun warmOrder(s: SheetAnim) = s.order

/** Drag down at the top of any list in the sheet to close it. */
private class DragToClose(
    val sheet: SheetAnim, val scope: CoroutineScope, val height: () -> Float, val dismiss: () -> Unit,
) : NestedScrollConnection {
    var dragging = false
    private fun drag(dy: Float) {
        val nv = (sheet.p.value - dy / (height() * 0.9f)).coerceIn(0f, 1f)
        scope.launch { sheet.p.snapTo(nv) }
    }
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (dragging && available.y < 0f && source == NestedScrollSource.UserInput) {
            drag(available.y)
            if (sheet.p.value >= 0.999f) dragging = false
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f) {
            dragging = true; drag(available.y)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        dragging = false
        if (sheet.p.value < 0.8f || available.y > 1800f) dismiss()
        else sheet.p.animateTo(1f, Motion.settle)
        return available
    }
}

@Composable
fun Sheet(
    open: Boolean,
    sheet: SheetAnim,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(open) { sheet.p.animateTo(if (open) 1f else 0f, if (open) Motion.open else Motion.close) }
    val shown by remember(open) { derivedStateOf { open || sheet.p.value > 0.001f } }
    // compose once shortly after launch so the first open is instant
    // 1.0.1: before, all six sheets were composed together 0.5 s after launch, in the middle of the opening
    // animation (one big frame = the "sometimes lags"). Now each waits until the opening is over, then they
    // are built one per ~120 ms.
    var warm by remember { mutableStateOf(false) }
    val intro = LocalIntro.current
    LaunchedEffect(Unit) {
        if (intro != null) { snapshotFlow { intro.done }.first { it }; delay(120L + warmOrder(sheet) * 120L) } else delay(500)
        warm = true
    }
    if (!(warm || shown)) return

    var h by remember { mutableFloatStateOf(1f) }
    val drag = remember(sheet) { DragToClose(sheet, scope, { h }, onDismiss) }
    val reveal = remember(sheet) { { sheet.p.value } }

    Box(
        Modifier
            .fillMaxSize()
            .layout { m, c ->
                val pl = m.measure(c)
                layout(pl.width, pl.height) { if (shown) pl.place(0, 0) }
            }
            .onSizeChanged { h = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                val t = sheet.p.value
                alpha = clamp01(t * 1.25f)
                translationY = (1f - t) * 28.dp.toPx()
            }
            .background(pal.bg)
            .pointerInput(Unit) { detectTapGestures { } }
            .nestedScroll(drag)
    ) {
        CompositionLocalProvider(LocalReveal provides reveal) { content() }
    }
}
