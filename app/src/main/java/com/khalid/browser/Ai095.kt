package com.khalid.browser

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// =====================================================================
// 0.9.5: AI mode in Browser's own UI. Google AI Mode is asked in a hidden page,
// the answer is read out of it and drawn here. Google's UI is never shown.
// =====================================================================

data class AiBlock(val kind: Char, val text: String) // 'h' heading, 'p' paragraph, 'l' list item
class AiSource(val url: String, val title: String)

class AiTurn(val q: String, val ask: String) {
    var state by mutableStateOf(0) // 0 thinking, 1 answered, 2 failed
    var blocks by mutableStateOf(listOf<AiBlock>())
    var images by mutableStateOf(listOf<String>())
    var sources by mutableStateOf(listOf<AiSource>())
    var error by mutableStateOf("")
    var tries by mutableStateOf(0)
    var useQ = false
}

class AiChat { val turns = mutableStateListOf<AiTurn>() }

/** Ask (or follow up). A follow-up carries the earlier question so the answer keeps context. */
fun BrowserState.askAi(q: String) {
    val text = q.trim()
    if (text.isEmpty()) return
    val chat = ai ?: AiChat().also { ai = it }
    val prev = chat.turns.lastOrNull()
    val ask = if (prev == null) text else "Earlier I asked: \"${prev.q}\". Follow-up question: $text"
    chat.turns.add(AiTurn(text, ask))
    screen = Screen.Home
}

private val NOISE = setOf(
    "ai mode", "all", "images", "videos", "news", "shopping", "more", "tools", "short videos", "web", "forums", "maps",
    "show more", "show less", "show all", "feedback", "thank you", "sign in", "share", "copy", "listen", "learn more",
    "ask anything", "ask a follow up", "ask a follow-up", "upload", "dive deeper in ai mode", "start new search", "new",
    "ai responses may include mistakes. learn more", "ai responses may include mistakes.", "ai can make mistakes, so double-check responses",
    "thinking", "searching", "creating a layout", "ai mode asked", "ai mode reply", "ai mode response", "ai mode replied", "you said", "you asked", "your question", "response", "answer", "sources", "related", "people also ask", "good response", "bad response", "report", "history", "privacy", "terms"
)

/** Reads the page text in order (block by block), plus pictures and links that come after the question. */
private fun extractJs(q: String): String {
    val qq = JSONObject.quote(q)
    return """(function(Q){try{
if(location.host.indexOf('consent.')==0)return JSON.stringify({consent:1});
var root=document.querySelector('[role=main]')||document.getElementById('rcnt')||document.body;
[].slice.call(document.querySelectorAll('button,[role=button],div[jsaction]')).forEach(function(b){if(b.__hk)return;var t=(b.innerText||'').trim().toLowerCase();
 if(t=='show more'||t=='show all'||t=='read more'||t=='expand'){var r=b.getBoundingClientRect();if(r.width>0){b.__hk=1;try{b.click();}catch(e){}}}});
var bad='header,nav,footer,form,textarea,script,style,noscript,svg,button,[role=button],[role=navigation],[role=dialog],[role=tooltip],[aria-hidden="true"]';
function norm(t){return (t||'').replace(/\s+/g,' ').trim().toLowerCase();}
var nq=norm(Q),qEl=null;
var dc=new WeakMap();function disp(e){var d=dc.get(e);if(d===undefined){d=getComputedStyle(e).display;dc.set(e,d);}return d;}
function blockOf(e){while(e&&e!==root){var d=disp(e);if(d!='inline'&&d!='inline-block'&&d!='contents'&&d!='inline-flex')return e;e=e.parentElement;}return root;}
var lines=[],cur=null,curB=null,w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null),n;
while(n=w.nextNode()){var t=n.nodeValue;if(!t||!t.trim())continue;var p=n.parentElement;if(!p||p.closest(bad))continue;
 var r=p.getBoundingClientRect();if(r.width<3||r.height<3)continue;var cs=getComputedStyle(p);if(cs.visibility=='hidden'||cs.opacity=='0')continue;
 var bk=blockOf(p);
 if(bk!==curB){if(cur&&norm(cur.t)==nq&&nq)qEl=curB;curB=bk;var tag=bk.tagName.toLowerCase();
  var k=(tag=='li'||bk.closest('li'))?'l':((/^h[1-6]$/.test(tag)||bk.getAttribute('role')=='heading')?'h':'p');
  if(k=='p'&&bk.children.length==1&&/^(STRONG|B)$/.test(bk.firstElementChild.tagName)&&norm(bk.innerText)==norm(bk.firstElementChild.innerText)&&norm(bk.innerText).length<90)k='h';
  cur={k:k,t:''};lines.push(cur);}
 cur.t+=t;}
if(cur&&norm(cur.t)==nq&&nq)qEl=curB;
function after(e){return !qEl||(qEl.compareDocumentPosition(e)&4);}
var imgs=[],is=root.querySelectorAll('img');for(var i=0;i<is.length&&imgs.length<8;i++){var m=is[i];if(m.closest('header,nav,footer')||!after(m))continue;var r=m.getBoundingClientRect();
 var s=m.currentSrc||m.src||'';if(r.width<90||r.height<70)continue;if(!(s.indexOf('http')==0||s.indexOf('data:image')==0))continue;if(/favicon|logo/i.test(s))continue;if(imgs.indexOf(s)<0)imgs.push(s);}
var src=[],hosts={},as=root.querySelectorAll('a[href]');for(var i=0;i<as.length&&src.length<6;i++){var a=as[i];if(!after(a))continue;var h=a.href;var m2=h.match(/[?&](q|url)=(http[^&]+)/);if(h.indexOf('google.')>0&&m2)h=decodeURIComponent(m2[2]);
 var hh=h.split('/')[2]||'';if(h.indexOf('http')!=0||/google\./.test(hh)||hosts[hh])continue;var tt=(a.innerText||'').trim().split('\n')[0];if(tt.length<3)tt=hh;hosts[hh]=1;src.push({u:h,t:tt.slice(0,80)});}
return JSON.stringify({b:lines.map(function(l){return {k:l.k,t:l.t.replace(/\s+/g,' ').trim()};}),img:imgs,src:src});}catch(err){return JSON.stringify({e:''+err});}})($qq)"""
}

/** Types the follow-up into the page's own box so Google keeps the conversation. */
private fun followJs(q: String): String = """(function(Q){try{
var c=[].slice.call(document.querySelectorAll('textarea,[contenteditable=true],input[type=text],input[type=search]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;});
var ta=c.pop();if(!ta)return 'no';ta.focus();
if(ta.isContentEditable){ta.textContent=Q;}else{var pr=ta.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(pr,'value').set.call(ta,Q);}
ta.dispatchEvent(new Event('input',{bubbles:true}));
var b=[].slice.call(document.querySelectorAll('button,[role=button]')).filter(function(x){var l=(x.getAttribute('aria-label')||'').toLowerCase();var r=x.getBoundingClientRect();return r.width>0&&/send|submit|ask/.test(l);}).pop();
if(b){b.click();return 'btn';}
ta.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));return 'enter';}catch(e){return 'no';}})(${JSONObject.quote(q)})"""

private suspend fun WebView.js(code: String): String? = suspendCancellableCoroutine { c ->
    runCatching { evaluateJavascript(code) { r -> if (c.isActive) c.resume(r) } }.onFailure { if (c.isActive) c.resume(null) }
}

private val END = listOf("ai responses may include mistakes", "ai can make mistakes", "ask anything", "ask a follow", "dive deeper", "generative ai is experimental")
private val LABEL = Regex("^((you said|you asked|ai mode (said|replied|reply|response|asked|answer)|ai (said|replied))\\s*:?|(your question|ai mode|response|answer)\\s*:)\\s*", RegexOption.IGNORE_CASE)

private fun norm(t: String) = t.replace(Regex("\\s+"), " ").trim().lowercase()

/** Returns (answer length, consent page?). Keeps only what comes after the question, without Google's labels. */
private fun parse(raw: String?, turn: AiTurn): Pair<Int, Boolean> {
    val s = runCatching { JSONTokener(raw ?: return 0 to false).nextValue() as? String }.getOrNull() ?: return 0 to false
    val o = runCatching { JSONObject(s) }.getOrNull() ?: return 0 to false
    if (o.has("consent")) return -1 to true
    val b = o.optJSONArray("b") ?: return 0 to false
    val all = (0 until b.length()).map { val e = b.getJSONObject(it); AiBlock(e.optString("k", "p").firstOrNull() ?: 'p', e.optString("t")) }
    val qs = setOf(norm(turn.q), norm(turn.ask))
    // everything up to (and including) the last time the question appears is the page around the answer
    var start = 0
    all.forEachIndexed { i, bl ->
        val n = norm(bl.text)
        val stripped = norm(LABEL.replace(bl.text, ""))
        if (n in qs || stripped in qs) start = i + 1
    }
    val srcTitles = (o.optJSONArray("src") ?: JSONArray()).let { a -> (0 until a.length()).flatMap { listOf(norm(a.getJSONObject(it).optString("t")), norm(hostOf(a.getJSONObject(it).optString("u")).removePrefix("www."))) }.toSet() }
    val out = ArrayList<AiBlock>()
    for (bl in all.drop(start)) {
        val n = norm(bl.text)
        if (END.any { n.startsWith(it) }) break
        val text = LABEL.replace(bl.text.trim(), "").trim()
        val tn = norm(text)
        if (tn.length < 2 || tn in NOISE || tn in qs) continue
        if (tn.startsWith("earlier i asked") || tn.startsWith("follow-up question")) continue
        if (Regex("^\\+\\s?\\d+$").matches(tn)) continue
        if (tn in srcTitles && tn.length < 40) continue
        if (out.lastOrNull()?.text == text) continue
        out.add(AiBlock(bl.kind, text))
    }
    turn.blocks = out
    val imgs = o.optJSONArray("img") ?: JSONArray()
    turn.images = (0 until imgs.length()).map { imgs.getString(it) }
    val src = o.optJSONArray("src") ?: JSONArray()
    turn.sources = (0 until src.length()).map { AiSource(src.getJSONObject(it).optString("u"), src.getJSONObject(it).optString("t")) }
    return out.sumOf { it.text.length } to false
}

/**
 * Asks and follows the answer as it is written. It shows once the text has settled,
 * then keeps reading for a while so a late part of the answer is never cut off.
 */
private suspend fun runTurn(wv: WebView, turn: AiTurn, followUp: Boolean, life: androidx.lifecycle.Lifecycle? = null) {
    turn.state = 0
    turn.blocks = emptyList(); turn.images = emptyList(); turn.sources = emptyList()
    var asked = false
    if (followUp && isAiUrl(wv.url.orEmpty())) {
        val r = wv.js(followJs(turn.q)).orEmpty()
        if (!r.contains("no")) {
            // check the page took the question
            repeat(8) {
                delay(600)
                val seen = wv.js("(function(){return (document.body.innerText||'').toLowerCase().split(${JSONObject.quote(norm(turn.q))}).length-1})()")?.toIntOrNull() ?: 0
                if (seen >= 1) { asked = true; return@repeat }
            }
        }
    }
    if (!asked) { wv.loadUrl(aiUrl(turn.ask)); delay(1800) } else turn.useQ = true
    var start = System.currentTimeMillis()
    var last = -1; var stable = 0
    while (System.currentTimeMillis() - start < 60_000) {
        // 1.0.0: no JS polling of the hidden page while the app is in the background.
        // The time spent away does not count toward the 60 s limit, so the answer is still read on return.
        if (life != null && !life.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            val away = System.currentTimeMillis()
            runCatching { wv.onPause() }
            life.awaitStarted()
            runCatching { wv.onResume() }
            start += System.currentTimeMillis() - away
        }
        val (len, consent) = parse(wv.js(extractJs(if (turn.useQ) turn.q else turn.ask)), turn)
        if (consent) { turn.error = "Google needs you to accept its terms once. Open Google in a tab, accept, then try again."; turn.state = 2; return }
        if (len >= 40 && len == last) stable++ else stable = 0
        last = len
        if (turn.state == 0 && stable >= 5) turn.state = 1       // ~4 s without change: show it
        if (turn.state == 1 && stable >= 14) return              // ~11 s more without change: finished
        delay(800)
    }
    if (turn.blocks.isNotEmpty()) turn.state = 1
    else { turn.error = "AI mode didn't answer this time."; turn.state = 2 }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AiScreen(st: BrowserState) {
    val chat = st.ai ?: return
    val pal = LocalPal.current
    var web by remember { mutableStateOf<WebView?>(null) }
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // the newest question drives the hidden page; it keeps reading even after the answer shows
    val newest = chat.turns.lastOrNull()
    LaunchedEffect(newest, newest?.tries, web) {
        val w = web ?: return@LaunchedEffect
        val t = newest ?: return@LaunchedEffect
        if (t.state == 2) return@LaunchedEffect
        if (t.state == 1 && t.tries == 0 && t.blocks.isNotEmpty()) return@LaunchedEffect
        runTurn(w, t, followUp = chat.turns.size > 1 && t.tries == 0, life = life)
    }
    // 1.0.0: closing the AI screen stops the hidden page and frees it completely (stop, detach, destroy).
    DisposableEffect(Unit) {
        onDispose {
            web?.let { w ->
                runCatching { w.stopLoading(); w.loadUrl("about:blank") }
                runCatching { (w.parent as? android.view.ViewGroup)?.removeView(w) }
                runCatching { w.destroy() }
            }
            web = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        // the hidden page doing the asking, fully covered by our screen
        if (!isRobolectric) AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    webViewClient = WebViewClient()
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(pal.bg).pointerInput(Unit) { detectTapGestures { } })
        AiChatView(st, chat)
    }
}

@Composable
private fun AiChatView(st: BrowserState, chat: AiChat) {
    val pal = LocalPal.current
    val list = rememberLazyListState()
    LaunchedEffect(chat.turns.size) { if (chat.turns.isNotEmpty()) list.animateScrollToItem(chat.turns.size - 1) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.squish(0.9f) { st.ai = null }.size(44.dp).clip(CircleShape).background(pal.soft), contentAlignment = Alignment.Center) {
                Glyph(G.Close, pal.fg, 14.dp, 2.dp)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp)
        ) {
            itemsIndexed(chat.turns, key = { i, _ -> i }) { _, turn -> TurnView(st, turn) }
        }
        T("Generated by Google AI mode", 11.sp, 500, pal.fg.copy(alpha = 0.4f), Modifier.fillMaxWidth().padding(bottom = 6.dp), align = TextAlign.Center)
        FollowUpBar(st, busy = chat.turns.lastOrNull()?.state == 0)
    }
}

@Composable
private fun TurnView(st: BrowserState, turn: AiTurn) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth()) {
        P(turn.q, 26.sp, 700, lineHeight = 1.2f, modifier = Modifier.enterUp(0, 40))
        Spacer(Modifier.height(18.dp))
        when (turn.state) {
            0 -> AiLoader(Modifier.fillMaxWidth().height(300.dp))
            2 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                P(turn.error, 16.sp, 500, pal.fg.copy(alpha = 0.7f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Try again", true) { turn.error = ""; turn.state = 0; turn.tries++ }
                    Pill("Open in Google", false) { st.ai = null; st.openLinkInNewTab(aiUrl(turn.ask), st.privateMode) }
                }
            }
            else -> Column(
                Modifier.pointerInput(turn) { detectTapGestures(onLongPress = {
                    st.host.copy(turn.blocks.joinToString("\n\n") { it.text }); st.notify("Answer copied")
                }) },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (turn.images.isNotEmpty()) LazyRow(Modifier.enterUp(1, 40), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(turn.images) { AiImage(it, Modifier.height(170.dp).width(230.dp)) }
                }
                turn.blocks.forEachIndexed { i, b ->
                    val m = Modifier.enterUp(2 + i.coerceAtMost(14), 60, 40)
                    when (b.kind) {
                        'h' -> P(b.text, 19.sp, 700, lineHeight = 1.3f, modifier = m.padding(top = 6.dp))
                        'l' -> Row(m) {
                            Box(Modifier.padding(top = 10.dp, end = 12.dp).size(6.dp).clip(CircleShape).background(pal.fg))
                            P(b.text, 16.sp, 400, lineHeight = 1.55f, modifier = Modifier.weight(1f))
                        }
                        else -> P(b.text, 16.sp, 400, lineHeight = 1.55f, modifier = m)
                    }
                }
                if (turn.sources.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    T("Sources", 13.sp, 600, pal.fg.copy(alpha = 0.5f))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(turn.sources) { s ->
                            Column(
                                Modifier.squish(0.95f) { st.ai = null; st.openLinkInNewTab(s.url, st.privateMode) }
                                    .width(170.dp).clip(RoundedCornerShape(18.dp)).background(pal.soft).padding(12.dp)
                            ) {
                                T(hostOf(s.url).removePrefix("www."), 11.sp, 600, pal.fg.copy(alpha = 0.5f))
                                Spacer(Modifier.height(3.dp))
                                P(s.title, 13.sp, 600, maxLines = 2, lineHeight = 1.3f)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(
        Modifier.squish(0.95f, onClick = onClick).height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (primary) pal.fg else pal.soft).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) { T(text, 14.sp, 600, if (primary) pal.bg else pal.fg) }
}

/** The dot thinking: it breathes while three small dots circle it, and the words below change as it works. */
@Composable
fun AiLoader(modifier: Modifier) {
    val pal = LocalPal.current
    val inf = rememberInfiniteTransition(label = "ai")
    val spin by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = EaseOut), RepeatMode.Reverse), label = "br")
    val words = listOf("Thinking", "Searching the web", "Reading sources", "Writing the answer")
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1900); step = (step + 1).coerceAtMost(words.size - 1) } }
    val fade by animateFloatAsState(1f, tween(1), label = "f")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(150.dp)) {
            val c = center
            val r = size.minDimension
            drawCircle(pal.fg, r * (0.17f + 0.025f * breathe), c)
            for (k in 0..2) {
                val a = 2 * PI * (spin + k / 3f)
                val orbit = r * (0.34f + 0.03f * sin(2 * PI * (spin * 2 + k / 3f)).toFloat())
                val p = Offset(c.x + (orbit * cos(a)).toFloat(), c.y + (orbit * sin(a)).toFloat())
                drawCircle(pal.fg.copy(alpha = 0.35f + 0.25f * k), r * (0.035f + 0.012f * k), p)
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.animation.Crossfade(step, animationSpec = tween(350), label = "w") { s ->
            T(words[s], 15.sp, 500, pal.fg.copy(alpha = 0.55f * fade), align = TextAlign.Center)
        }
    }
}

@Composable
private fun FollowUpBar(st: BrowserState, busy: Boolean) {
    val pal = LocalPal.current
    var q by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun send() { val t = q.trim(); if (t.isEmpty() || busy) return; q = ""; focus.clearFocus(); st.askAi(t) }
    Row(
        Modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(pal.soft).padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (q.isEmpty()) T("Ask a follow-up", 16.sp, 500, pal.fg.copy(alpha = 0.45f))
            BasicTextField(
                q, { q = it }, Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 16.sp, color = pal.fg),
                cursorBrush = SolidColor(pal.fg), maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
        }
        Spacer(Modifier.width(8.dp))
        val on = q.isNotBlank() && !busy
        Box(
            Modifier.squish(0.9f) { if (q.isBlank()) st.voice = true else send() }
                .size(56.dp).clip(CircleShape).background(if (on) pal.fg else pal.soft),
            contentAlignment = Alignment.Center
        ) {
            if (q.isBlank()) Glyph(G.Mic, pal.fg, 20.dp, 1.8.dp)
            else Box(Modifier.graphicsLayer { rotationZ = -90f }) { Glyph(G.Arrow, if (on) pal.bg else pal.fg, 20.dp, 2.2.dp) }
        }
    }
}

/** 1.0.0: capped by memory (16 MB) as well as count, so a few huge pictures can't fill the heap. */
private val aiImages = object : android.util.LruCache<String, ImageBitmap>(16 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
}

@Composable
private fun AiImage(src: String, modifier: Modifier) {
    val pal = LocalPal.current
    val img by produceState(aiImages.get(src), src) {
        if (value != null || isRobolectric) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (src.startsWith("data:")) android.util.Base64.decode(src.substringAfter(","), android.util.Base64.DEFAULT)
                else {
                    val c = java.net.URL(src).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 6000; c.readTimeout = 8000
                    android.webkit.CookieManager.getInstance().getCookie(src)?.let { c.setRequestProperty("Cookie", it) }
                    try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                }
                // 1.0.0: decoded at a reduced size when the picture is far bigger than its 230x170 dp tile
                // (it is cropped to the tile, so the result on screen is the same; it never goes below 800x600 px).
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 800 && o.outHeight / (s * 2) >= 600) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                    ?.asImageBitmap()?.also { aiImages.put(src, it) }
            }.getOrNull()
        }
    }
    val b = img
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(pal.soft)) {
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

// ---------------- most visited, under the dot: three at a time, swipe for more ----------------
@Composable
fun SiteRow(st: BrowserState, modifier: Modifier) {
    val pal = LocalPal.current
    val sites = remember(st.store.history.size) { st.topSites(12) }
    if (sites.isEmpty()) return
    val pages = sites.chunked(3)
    val pager = rememberPagerState { pages.size }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 48.dp)) { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                pages[p].forEachIndexed { i, e ->
                    Column(
                        Modifier.enterUp(i, 200).squish(0.9f) { st.open(e.url) }.width(72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(pal.soft), contentAlignment = Alignment.Center) {
                            T(e.title.removePrefix("www.").take(1).uppercase(), 17.sp, 700)
                        }
                        Spacer(Modifier.height(6.dp))
                        T(e.title.removePrefix("www.").substringBefore('.').take(10), 11.sp, 500, pal.fg.copy(alpha = 0.55f), align = TextAlign.Center)
                    }
                }
                repeat(3 - pages[p].size) { Spacer(Modifier.width(72.dp)) }
            }
        }
        if (pages.size > 1) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size) { d ->
                    val w by animateFloatAsState(if (d == pager.currentPage) 16f else 5f, tween(220, easing = EaseOut), label = "pd")
                    Box(Modifier.height(5.dp).width(w.dp).clip(CircleShape).background(pal.fg.copy(alpha = if (d == pager.currentPage) 1f else 0.3f)))
                }
            }
        }
    }
}

@Suppress("unused") private val keepDp: Dp = 0.dp
