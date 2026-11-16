package com.khalid.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Find in page: replaces the bar while open. */
@Composable
fun FindBar(st: BrowserState, tab: Tab, c: Ink, modifier: Modifier) {
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(q) {
        if (q.isBlank()) { tab.view?.clearMatches(); st.findCount = 0; st.findAt = 0 } else tab.view?.findAllAsync(q)
    }
    fun close() { tab.view?.clearMatches(); st.finding = false }
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)
            .height(54.dp).clip(RoundedCornerShape(27.dp)).background(c.fill).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) T("Find in page", 16.sp, 500, c.dim)
            BasicTextField(
                q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 16.sp, color = c.ink),
                cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { tab.view?.findNext(true) })
            )
        }
        if (q.isNotBlank()) T(if (st.findCount == 0) "0" else "${st.findAt}/${st.findCount}", 14.sp, 600, c.dim, Modifier.padding(horizontal = 8.dp))
        Box(Modifier.squish(0.9f) { tab.view?.findNext(false) }.size(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.graphicsLayer { rotationZ = -90f }) { Glyph(G.Chevron, c.ink, 16.dp, 2.dp) }
        }
        Box(Modifier.squish(0.9f) { tab.view?.findNext(true) }.size(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.graphicsLayer { rotationZ = 90f }) { Glyph(G.Chevron, c.ink, 16.dp, 2.dp) }
        }
        Box(Modifier.squish(0.9f) { close() }.size(44.dp).clip(CircleShape).background(c.ink), contentAlignment = Alignment.Center) {
            Glyph(G.Close, c.base, 14.dp, 2.dp)
        }
    }
}

/** Most visited sites under the dot on a new tab. */
@Composable
fun TopSites(st: BrowserState, modifier: Modifier) {
    val pal = LocalPal.current
    val sites = remember(st.store.history.size) { st.topSites(4) }
    if (sites.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        sites.forEachIndexed { i, e ->
            Column(
                Modifier.enterUp(i, 260).squish(0.92f) { st.open(e.url) }.width(64.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(pal.soft), contentAlignment = Alignment.Center) {
                    T(e.title.take(1).uppercase(), 18.sp, 700)
                }
                Spacer(Modifier.height(8.dp))
                T(e.title.substringBefore('.').take(10), 12.sp, 500, pal.fg.copy(alpha = 0.6f))
            }
        }
    }
}
