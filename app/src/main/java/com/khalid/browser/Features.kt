package com.khalid.browser

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import java.net.URLEncoder
import java.util.Locale

const val APP_VERSION = "1.0.5"

// =====================================================================
// Search shortcuts: "@yt cats" searches YouTube, "@w", "@gh", "@maps", "@x"
// =====================================================================
data class Shortcut(val key: String, val name: String, val base: String)

object Shortcuts {
    val all: List<Shortcut> get() = listOf(
        Shortcut("yt", "YouTube", "https://www.youtube.com/results?search_query="),
        Shortcut("w", "Wikipedia", "https://${wikiLang()}.wikipedia.org/w/index.php?search="),
        Shortcut("gh", "GitHub", "https://github.com/search?q="),
        Shortcut("maps", "Maps", "https://www.google.com/maps/search/"),
        Shortcut("x", "X", "https://x.com/search?q="),
    )
    private fun wikiLang(): String = Locale.getDefault().language.ifBlank { "en" }.let { if (it == "iw") "he" else if (it == "in") "id" else it }

    /** "@yt cats" -> (YouTube, "cats"). Words may be empty while typing. */
    fun parse(input: String): Pair<Shortcut, String>? {
        val s = input.trimStart()
        if (!s.startsWith("@")) return null
        val key = s.drop(1).substringBefore(' ').lowercase()
        val sc = all.firstOrNull { it.key == key } ?: return null
        return sc to s.drop(1 + key.length).trim()
    }

    fun url(input: String): String? {
        val (sc, q) = parse(input) ?: return null
        if (q.isBlank()) return null
        return sc.base + URLEncoder.encode(q, "UTF-8")
    }
}

// =====================================================================
// Inline autocomplete: "you" -> "you|tube.com" (the suffix is shown selected)
// =====================================================================
fun completeHost(typed: String, hosts: List<String>): String? {
    if (typed.isEmpty() || typed.contains(' ') || typed.contains('/') || typed.startsWith("@")) return null
    val t = typed.lowercase().removePrefix("https://").removePrefix("http://")
    if (t.isEmpty()) return null
    val prefix = typed.length - t.length // keep a typed scheme as is
    for (h in hosts) {
        val host = h.lowercase()
        val cands = if (t.startsWith("www.")) listOf(host, "www.$host") else listOf(host)
        for (c in cands) if (c.length > t.length && c.startsWith(t)) return c.substring(typed.length - prefix)
    }
    return null
}

// =====================================================================
// Translate
// =====================================================================
fun deviceLang(): String {
    val l = Locale.getDefault()
    return when (l.language) {
        "iw" -> "he"; "in" -> "id"; "ji" -> "yi"
        "zh" -> if (l.country == "TW" || l.country == "HK" || l.script == "Hant") "zh-TW" else "zh-CN"
        "" -> "en"
        else -> l.language
    }
}

fun translateUrl(url: String, lang: String = deviceLang()): String =
    "https://translate.google.com/translate?sl=auto&tl=$lang&u=" + URLEncoder.encode(url, "UTF-8")

fun translateTextUrl(text: String, lang: String = deviceLang()): String =
    "https://translate.google.com/?sl=auto&tl=$lang&op=translate&text=" + URLEncoder.encode(text.take(4000), "UTF-8")

// =====================================================================
// Ad and tracker blocker: a bundled host list, matched by host suffix in a HashSet
// =====================================================================
object Blocker {
    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile var loaded = false
        private set

    fun load(ctx: Context) {
        if (loaded) return
        hosts = runCatching {
            ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()
            }
        }.getOrDefault(hashSetOf())
        loaded = true
    }

    /** For tests: use this list instead of the asset. */
    fun use(list: Collection<String>) { hosts = list.map { it.lowercase() }.toHashSet(); loaded = true }

    /** True when the host or any parent domain is listed ("a.b.doubleclick.net" -> "doubleclick.net"). */
    fun blocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        val set = hosts
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return h in set
        }
    }

    val size get() = hosts.size
}

// =====================================================================
// Per-site memory: desktop mode, text size, shields off, granted permissions (by host)
// =====================================================================
class SitePrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("site_prefs", Context.MODE_PRIVATE)
    private fun k(host: String) = host.lowercase().removePrefix("www.")

    fun desktop(host: String): Boolean? = if (host.isBlank() || !p.contains("d:" + k(host))) null else p.getBoolean("d:" + k(host), false)
    fun setDesktop(host: String, on: Boolean) { if (host.isNotBlank()) p.edit().putBoolean("d:" + k(host), on).apply() }

    fun zoom(host: String): Int? = if (host.isBlank() || !p.contains("z:" + k(host))) null else p.getInt("z:" + k(host), 100)
    fun setZoom(host: String, z: Int) { if (host.isNotBlank()) p.edit().putInt("z:" + k(host), z).apply() }

    /** Blocking (ads, trackers, cookie banners) is on unless turned off for this site. */
    fun shields(host: String): Boolean = host.isBlank() || !p.getBoolean("s:" + k(host), false)
    fun setShields(host: String, on: Boolean) { if (host.isNotBlank()) p.edit().putBoolean("s:" + k(host), !on).apply() }

    fun perms(host: String): Set<String> = p.getStringSet("p:" + k(host), emptySet())?.toSet() ?: emptySet()
    fun grant(host: String, what: String) { p.edit().putStringSet("p:" + k(host), perms(host) + what).apply() }
    fun revoke(host: String, what: String) { p.edit().putStringSet("p:" + k(host), perms(host) - what).apply() }
}

// =====================================================================
// Saved WebView state <-> bytes, so tabs come back after the app is killed
// =====================================================================
fun Bundle.toBytes(): ByteArray? = runCatching {
    val p = Parcel.obtain()
    try { p.writeBundle(this); p.marshall() } finally { p.recycle() }
}.getOrNull()

fun bundleOf(bytes: ByteArray): Bundle? = runCatching {
    val p = Parcel.obtain()
    try {
        p.unmarshall(bytes, 0, bytes.size); p.setDataPosition(0)
        p.readBundle(Bundle::class.java.classLoader)
    } finally { p.recycle() }
}.getOrNull()

fun originOf(url: String): String? = runCatching {
    val u = Uri.parse(url)
    val s = u.scheme ?: return null; val h = u.host ?: return null
    if (s != "http" && s != "https") return null
    if (u.port > 0) "$s://$h:${u.port}" else "$s://$h"
}.getOrNull()

// =====================================================================
// Page scripts
// =====================================================================
object Scripts {
    /** CSS that hides common cookie / consent frameworks. */
    const val COOKIE_CSS = """
#onetrust-banner-sdk,#onetrust-consent-sdk,.onetrust-pc-dark-filter,#ot-sdk-btn-floating,
#CybotCookiebotDialog,#CybotCookiebotDialogBodyUnderlay,#CookiebotWidget,
.qc-cmp2-container,#qc-cmp2-container,.qc-cmp-ui-container,
#didomi-host,#didomi-notice,.didomi-popup-backdrop,
#truste-consent-track,.truste_box_overlay,.truste_overlay,#consent_blackbar,#trustarc-banner-overlay,
.fc-consent-root,.fc-dialog-overlay,
[id^="sp_message_container"],.message-overlay[class*="sp_"],
#usercentrics-root,#usercentrics-cmp-ui,
.cc-window.cc-banner,.cc-window.cc-floating,.cc-grower,
#cookie-banner,#cookieBanner,.cookie-banner,#cookie-notice,.cookie-notice,#cookie-law-info-bar,
#cookie-consent,.cookie-consent,#cookieConsent,#cookie-bar,.cookie-bar,#cookies-banner,.cookies-banner,
#gdpr-banner,.gdpr-banner,#cmpbox,#cmpbox2,.cmpboxBG,#cookiescript_injected,.cky-consent-container,.cky-overlay,
.osano-cm-window,#cmplz-cookiebanner-container,.cmplz-cookiebanner,#tarteaucitronRoot,.evidon-banner,#_evidon_banner
{display:none!important}
html.sp-message-open,body.sp-message-open,body.didomi-popup-open,body.qc-cmp-ui-showing,body.cmp-open,
html.cookie-consent-open,body.cookie-consent-open,body.cky-modal-open{overflow:auto!important}
"""

    /** Clicks "reject" / "necessary only" when there is exactly one such button. */
    const val COOKIE_JS = """
(function(){
 var ids=['#onetrust-reject-all-handler','#CybotCookiebotDialogBodyButtonDecline','#didomi-notice-disagree-button',
 '.fc-cta-do-not-consent','#truste-consent-required','.cmpboxbtnno','#cookiescript_reject','.cky-btn-reject',
 '#wt-cli-reject-btn','.cmplz-deny','[data-cookiefirst-action=reject]','#tarteaucitronAllDenied2','.osano-cm-denyAll',
 'button[data-testid=uc-deny-all-button]'];
 var rx=/^(reject all|reject all cookies|decline all|decline|reject|refuse all|only necessary|necessary only|use necessary cookies only|only essential|essential only|accept only essential cookies|continue without accepting|tout refuser|refuser|continuer sans accepter|alle ablehnen|ablehnen|nur notwendige|rechazar todo|rechazar|rifiuta tutto|rifiuta|alles weigeren|weigeren|\u0631\u0641\u0636 \u0627\u0644\u0643\u0644|\u0631\u0641\u0636)$/i;
 var done=false;
 function run(){
  if(done)return;
  for(var i=0;i<ids.length;i++){var b=document.querySelector(ids[i]);if(b){try{b.click();done=true;return;}catch(e){}}}
  var cs=document.querySelectorAll('[id*=cookie i],[class*=cookie i],[id*=consent i],[class*=consent i],[id*=gdpr i],[class*=gdpr i]');
  var hits=[];
  for(var j=0;j<cs.length&&j<60;j++){var bs=cs[j].querySelectorAll('button,[role=button],a.button,input[type=button]');
   for(var k=0;k<bs.length;k++){var t=(bs[k].innerText||bs[k].value||'').replace(/\s+/g,' ').trim();
    if(rx.test(t)&&hits.indexOf(bs[k])<0)hits.push(bs[k]);}}
  if(hits.length==1){try{hits[0].click();done=true;}catch(e){}}
 }
 if(document.readyState=='loading')document.addEventListener('DOMContentLoaded',run);else run();
 setTimeout(run,1200);setTimeout(run,3000);setTimeout(run,6000);
})();
"""


    /**
     * 1.0.4: the ad blocker's page side. Runs at document start (in every frame) when blocking is on:
     * - hides ad boxes with element-hiding CSS from the filter lists: this site's rules at once, then the
     *   generic rules for the class and id names the page really uses, as elements appear;
     * - stops unwanted pop-ups: window.open to an ad address, pop-ups from a tap that didn't hit a link or
     *   button ("click anywhere opens an ad"), a second pop-up from the same tap, and invisible full-page links.
     */
    val ADBLOCK_JS = """
(function(){
 var H=location.hostname,css='';
 try{css=BrowserApp.css(H)}catch(e){}
 var st=document.createElement('style');st.textContent=css;
 function put(){var r=document.head||document.documentElement;if(r&&st.parentNode!==r)r.appendChild(st);}
 put();
 var seen=Object.create(null),q=[],pend=[],timer=0;
 function add(n){if(n&&n.length<120&&!seen[n]){seen[n]=1;q.push(n);}}
 function grab(el){if(el.nodeType!==1)return;if(el.id&&typeof el.id=='string')add('#'+el.id);var c=el.classList;if(c)for(var i=0;i<c.length;i++)add('.'+c[i]);}
 function flush(){if(!q.length)return;var n=q.join(' ');q=[];var g='';try{g=BrowserApp.gcss(H,n)}catch(e){}if(g)st.appendChild(document.createTextNode(g));put();}
 function scan(root){grab(root);if(root.querySelectorAll){var l=root.querySelectorAll('[id],[class]');for(var i=0;i<l.length&&i<8000;i++)grab(l[i]);}}
 function run(){timer=0;var p=pend;pend=[];for(var i=0;i<p.length;i++)scan(p[i]);flush();}
 function later(){if(!timer)timer=setTimeout(run,200);}
 try{new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type=='attributes')pend.push(m.target);else{var a=m.addedNodes;for(var j=0;j<a.length;j++)if(a[j].nodeType===1)pend.push(a[j]);}}
  if(pend.length>400)pend=[document.documentElement];later();}).observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['class','id']});}catch(e){}
 pend.push(document.documentElement);later();
 document.addEventListener('DOMContentLoaded',function(){pend=[document.documentElement];run();});
 function base(h){var p=String(h).split('.');return p.slice(-2).join('.');}
 function abs(u){try{return new URL(u,location.href).href}catch(e){return ''}}
 function cross(u){try{var x=new URL(u,location.href);return x.protocol.indexOf('http')===0&&base(x.hostname)!==base(H);}catch(e){return false}}
 function overlay(n){try{var r=n.getBoundingClientRect();if(r.width*r.height<innerWidth*innerHeight*0.5)return false;var s=getComputedStyle(n);
  return parseFloat(s.opacity)<0.1||((s.position=='fixed'||s.position=='absolute')&&!(n.textContent||'').trim()&&!n.querySelector('img,video,canvas,svg,picture'));}catch(e){return false}}
 function intended(el){for(var n=el,k=0;n&&n.nodeType===1&&k<10;n=n.parentElement,k++){var t=n.tagName;
  if(t=='HTML'||t=='BODY')return false;
  if(t=='A'||t=='BUTTON'||t=='INPUT'||t=='SELECT'||t=='LABEL'||t=='SUMMARY'||n.getAttribute('role')=='button'||n.getAttribute('role')=='link'||n.hasAttribute('onclick'))return !overlay(n);
  try{if(getComputedStyle(n).cursor=='pointer')return !overlay(n);}catch(e){}}
  return false;}
 var down=null,downAt=0,opened=0,link='';
 addEventListener('pointerdown',function(e){down=e.target;downAt=Date.now();opened=0;link='';},true);
 addEventListener('touchstart',function(e){if(Date.now()-downAt>50){down=e.target;downAt=Date.now();opened=0;link='';}},true);
 var wo=window.open;
 try{window.open=function(u){var url=u==null?'':String(u);
  if(url){var a=abs(url);try{if(a&&BrowserApp.adUrl(a))return null;}catch(e){}
   if(a&&a===link){link='';opened++;return wo.apply(window,arguments);}
   if(cross(url)&&Date.now()-downAt<1500&&(opened>0||!intended(down))){try{BrowserApp.popupBlocked()}catch(e){}return null;}}
  opened++;return wo.apply(window,arguments);};}catch(e){}
 document.addEventListener('click',function(e){var t=e.target,a=t&&t.closest?t.closest('a[href]'):null;if(!a)return;var h=a.href;if(!h||h.indexOf('http')!==0)return;
  var bad=false;try{bad=BrowserApp.adUrl(h)}catch(x){}
  if(!bad&&cross(h)&&overlay(a)){bad=true;try{BrowserApp.popupBlocked()}catch(x){}}
  if(bad){e.preventDefault();e.stopImmediatePropagation();return;}
  link=h;opened++;},true);
})();
"""

    /** Runs at document start: media reports (background audio / PiP) and, when shields are on, cookie banners. */
    val DOC_START = """
(function(){
 if(window.__bx)return;window.__bx=1;
 function playing(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++){var e=m[i];if(!e.paused&&!e.ended&&e.readyState>2&&!e.muted)return true;}return false;}
 var last=null;function report(){var p=playing();if(p!==last){last=p;try{BrowserApp.media(p);}catch(e){}}}
 ['play','playing','pause','ended','emptied','volumechange'].forEach(function(n){document.addEventListener(n,report,true);});
 try{
  var dh=Object.getOwnPropertyDescriptor(Document.prototype,'hidden'),dv=Object.getOwnPropertyDescriptor(Document.prototype,'visibilityState');
  Object.defineProperty(document,'hidden',{configurable:true,get:function(){return playing()?false:dh.get.call(document);}});
  Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){return playing()?'visible':dv.get.call(document);}});
  document.addEventListener('visibilitychange',function(e){if(playing())e.stopImmediatePropagation();},true);
 }catch(e){}
 var on=false;try{on=BrowserApp.shields(location.hostname);}catch(e){}
 if(on){
  var s=document.createElement('style');s.textContent=${org.json.JSONObject.quote(COOKIE_CSS)};
  (document.head||document.documentElement).appendChild(s);
  $COOKIE_JS
  $ADBLOCK_JS
 }
})();
"""

    /** Fallback when document-start scripts are not supported: the same, injected after load. */
    val LATE = DOC_START

    /** Ask the page whether a video is playing and its shape. Returns "w,h" or "". */
    const val VIDEO_SHAPE = """(function(){var v=document.fullscreenElement;v=v&&(v.tagName=='VIDEO'?v:v.querySelector('video'));
if(!v){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){if(!vs[i].paused){v=vs[i];break;}}}
return v&&v.videoWidth?v.videoWidth+','+v.videoHeight:'';})()"""

    const val PAUSE_MEDIA = "document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})"
    const val PLAY_MEDIA = "(function(){var m=document.querySelector('video,audio');if(m)m.play();})()"
    const val SELECTION = "(function(){return window.getSelection?window.getSelection().toString():''})()"

    /** Reader mode: the page's main article as JSON {title, site, by, blocks:[{t,v}]}. */
    const val READER = """
(function(){
 function txt(e){return (e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();}
 function sc(e){var ps=e.querySelectorAll('p'),s=0;for(var i=0;i<ps.length;i++){var l=txt(ps[i]).length;if(l>40)s+=l;}return s;}
 var cands=document.querySelectorAll('article,main,[role=main],[itemprop=articleBody],.post-content,.entry-content,.article-body,.article-content,.story-body,#content,.content');
 var best=null,score=0;
 for(var i=0;i<cands.length;i++){var s=sc(cands[i]);if(s>score){score=s;best=cands[i];}}
 if(!best||score<400){var ps=document.querySelectorAll('p'),m=new Map();
  for(var i=0;i<ps.length;i++){var p=ps[i].parentElement;if(!p)continue;var l=txt(ps[i]).length;if(l<40)continue;m.set(p,(m.get(p)||0)+l);}
  m.forEach(function(v,k){if(v>score){score=v;best=k;}});}
 if(!best)best=document.body;
 var h1=document.querySelector('h1');var title=(h1&&txt(h1))||document.title||'';
 var out=[],els=best.querySelectorAll('h1,h2,h3,h4,p,li,blockquote,pre,img');
 for(var i=0;i<els.length&&out.length<800;i++){var e=els[i],tg=e.tagName;
  if(e.closest('nav,footer,aside,form,figcaption,[aria-hidden=true],.share,.social,.related,.comments,.newsletter,.ad,.advert'))continue;
  if(tg=='IMG'){var s=e.currentSrc||e.src||e.getAttribute('data-src')||'';var w=e.naturalWidth||e.width||0;
   if(/^https?:/.test(s)&&(w==0||w>=160))out.push({t:'img',v:s});continue;}
  if(tg=='P'&&e.closest('blockquote'))continue;
  if(tg=='LI'&&e.querySelector('p,li'))continue;
  var t=txt(e);if(!t)continue;
  if(tg.charAt(0)=='H'){if(tg=='H1'&&t==title)continue;out.push({t:'h',v:t});}
  else if(tg=='LI')out.push({t:'li',v:t});
  else if(tg=='BLOCKQUOTE')out.push({t:'q',v:t});
  else if(tg=='PRE')out.push({t:'pre',v:t});
  else out.push({t:'p',v:t});
 }
 var site=(document.querySelector('meta[property="og:site_name"]')||{}).content||location.hostname.replace(/^www\./,'');
 var by=(document.querySelector('meta[name=author]')||{}).content||'';
 return JSON.stringify({title:title,site:site,by:by,blocks:out});
})()
"""
}

/** A reader-mode article. */
data class Article(val url: String, val title: String, val site: String, val by: String, val blocks: List<Pair<String, String>>)

fun parseArticle(url: String, raw: String?): Article? = runCatching {
    if (raw.isNullOrBlank() || raw == "null") return null
    val s = org.json.JSONTokener(raw).nextValue() as? String ?: return null
    val o = org.json.JSONObject(s)
    val a = o.getJSONArray("blocks")
    val blocks = ArrayList<Pair<String, String>>()
    for (i in 0 until a.length()) {
        val b = a.getJSONObject(i)
        val t = b.getString("t"); val v = b.getString("v")
        if (t == "img" && blocks.lastOrNull()?.second == v) continue
        blocks.add(t to v)
    }
    val words = blocks.filter { it.first == "p" }.sumOf { it.second.length }
    if (words < 200) return null
    Article(url, o.optString("title"), o.optString("site"), o.optString("by"), blocks)
}.getOrNull()
