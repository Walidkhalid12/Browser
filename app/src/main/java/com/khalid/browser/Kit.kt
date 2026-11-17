@file:OptIn(ExperimentalTextApi::class)

package com.khalid.browser

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- palette ----------
@Immutable
/** raised = cards and panels that sit above the page (one step lighter than bg in dark mode). */
data class Pal(val bg: Color, val fg: Color, val soft: Color, val faint: Color, val dark: Boolean, val raised: Color = bg)

val LightPal = Pal(Color.White, Color.Black, Color(0xFFEEEEEE), Color(0xFFD9D9D9), false)
/**
 * 1.0.1 dark palette: a soft near-black base instead of pure #000 (no glowing grey edge on LCD screens like
 * the Redmi Note 9), off-white text instead of pure #FFF (less glare, still 17:1 contrast), and grey steps
 * that rise evenly: base 0A -> raised 141414 -> soft 1E1E1E -> faint 333333.
 */
const val DARK_BG = 0xFF0A0A0A.toInt()
val DarkPal = Pal(Color(0xFF0A0A0A), Color(0xFFEDEDED), Color(0xFF1E1E1E), Color(0xFF333333), true, Color(0xFF141414))
val LocalPal = staticCompositionLocalOf { LightPal }

// ---------- type ----------
private fun inter(w: Int) = Font(
    R.font.inter, FontWeight(w), FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(w))
)
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

@Composable
fun T(
    text: String, size: TextUnit = 17.sp, weight: Int = 500, color: Color = LocalPal.current.fg,
    modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, tracking: Float = -0.01f
) = BasicText(
    text, modifier,
    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size, color = color, textAlign = align, letterSpacing = tracking.em),
    maxLines = 1
)

@Composable
fun T(text: AnnotatedString, size: TextUnit = 17.sp, color: Color = LocalPal.current.fg, modifier: Modifier = Modifier) = BasicText(
    text, modifier,
    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight(400), fontSize = size, color = color, letterSpacing = (-0.01f).em),
    maxLines = 1
)

// ---------- motion ----------
// v0.4 motion: simple, clean, critically damped. No overshoot anywhere.
// v0.3: stiffer = faster, still viscous.
object Springs {
    val soft = spring<Float>(dampingRatio = 1f, stiffness = 500f)
    val press = spring<Float>(dampingRatio = 1f, stiffness = 1800f)
    val release = spring<Float>(dampingRatio = 1f, stiffness = 700f)
}

/** Tap with a soft liquid press: gently sinks under the finger and flows back, no overshoot. */
fun Modifier.squish(scale: Float = 0.96f, haptic: Boolean = true, onLongPress: (() -> Unit)? = null, onClick: () -> Unit): Modifier = this.then(
    Modifier.squishImpl(scale, haptic, onLongPress, onClick)
)

@Composable
private fun Modifier.squishImplC(scale: Float, haptic: Boolean, onLongPress: (() -> Unit)?, onClick: () -> Unit): Modifier {
    val a = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val h = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    return graphicsLayer { scaleX = a.value; scaleY = a.value }
        .pointerInput(onLongPress != null) {
            detectTapGestures(
                onLongPress = if (onLongPress == null) null else { _ ->
                    h.performHapticFeedback(HapticFeedbackType.LongPress); long?.invoke()
                },
                onPress = {
                    scope.launch { a.animateTo(scale, Springs.press) }
                    tryAwaitRelease()
                    scope.launch { a.animateTo(1f, Springs.release) }
                },
                onTap = {
                    if (haptic) h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    click()
                }
            )
        }
}

private fun Modifier.squishImpl(scale: Float, haptic: Boolean, onLongPress: (() -> Unit)?, onClick: () -> Unit): Modifier =
    composed { squishImplC(scale, haptic, onLongPress, onClick) }

/**
 * Staggered liquid entrance. Inside a sheet it is driven by the sheet's own progress
 * (no coroutine per item, and it rewinds as the sheet flows closed); elsewhere it plays once.
 */
@Composable
fun Modifier.enterUp(index: Int, baseDelay: Int = 40, step: Int = 32): Modifier {
    val reveal = LocalReveal.current
    // 1.0.1: during the opening, every element reads ONE shared clock that starts after the first frame is
    // on screen. Before, each element ran its own timer from the first composition, so a slow first frame
    // ate the start of the animation and it jumped.
    val intro = LocalIntro.current
    val shared = remember { intro != null && !intro.done }
    if (reveal == null && shared && intro != null) {
        val start = (baseDelay + index * step).toFloat().coerceAtMost((INTRO_MS - 300).toFloat())
        return graphicsLayer {
            val v = if (intro.done) 1f else EaseOut.transform(((intro.t.value - start) / 300f).coerceIn(0f, 1f))
            alpha = v
            translationY = (1f - v) * 10.dp.toPx()
        }
    }
    if (reveal != null) {
        val start = (0.15f + index * 0.04f).coerceAtMost(0.5f)
        return graphicsLayer {
            val v = smoothStep(start, start + 0.5f, reveal())
            alpha = v
            translationY = (1f - v) * 10.dp.toPx()
        }
    }
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((baseDelay + index * step).toLong())
        a.animateTo(1f, tween(300, easing = EaseOut))
    }
    return graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 10.dp.toPx()
    }
}


/** 1.0.1: one clock for the whole opening animation (milliseconds since the first frame was drawn). */
class IntroClock {
    val t = androidx.compose.animation.core.Animatable(0f)
    var done by androidx.compose.runtime.mutableStateOf(false)
}
const val INTRO_MS = 900
val LocalIntro = androidx.compose.runtime.staticCompositionLocalOf<IntroClock?> { null }

/** 1.0.1: text that can wrap onto several lines (T is always one line). */
@Composable
fun TM(
    text: String, size: TextUnit = 15.sp, weight: Int = 500, color: Color = LocalPal.current.fg,
    modifier: Modifier = Modifier, center: Boolean = false, maxLines: Int = 8
) = BasicText(
    text, modifier,
    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size, color = color,
        textAlign = if (center) TextAlign.Center else TextAlign.Start, letterSpacing = (-0.01f).em, lineHeight = size * 1.35f),
    maxLines = maxLines
)
