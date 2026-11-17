#!/usr/bin/env python3
"""
1.0.4: tests the ad blocker's page script (Scripts.ADBLOCK_JS in Features.kt) in a real Chromium, the same
engine as Android's WebView. A local test server serves the pages; window.BrowserApp is a stand-in for the
app's bridge. Checks element hiding (at start and for elements added later) and pop-up blocking.

Run: python3 tools/test_adblock_page.py   (needs: pip install playwright && playwright install chromium)
"""
import http.server, re, sys, threading, pathlib, functools, tempfile
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
src = (ROOT / "app/src/main/java/com/khalid/browser/Features.kt").read_text()
JS = re.search(r'val ADBLOCK_JS = """(.*?)"""', src, re.S).group(1)

STUB = r"""
window.__log = [];
window.BrowserApp = {
  css: function(h){ return '.ad-top{display:none!important}\n'; },
  gcss: function(h, names){ return names.split(' ').filter(function(n){ return n=='.ad-box' || n=='#sponsored'; })
           .map(function(n){ return n+'{display:none!important}\n'; }).join(''); },
  adUrl: function(u){ var bad = /\/\/ads\.example\//.test(u); if (bad) __log.push('ad '+u); return bad; },
  popupBlocked: function(){ __log.push('popup'); }
};
"""

PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width"></head>
<body style="margin:0;font:16px sans-serif">
<div class="ad-top" style="height:50px">AD TOP</div>
<div class="ad-box" style="height:50px">AD BOX</div>
<div id="sponsored" style="height:50px">SPONSORED</div>
<p class="content" id="text" style="height:60px">Plain article text, not a link.</p>
<button id="btn" style="height:50px;width:200px">Sign in</button>
<button id="adbtn" style="height:50px;width:200px">Ad button</button>
<a id="adlink" href="https://ads.example/landing" style="display:block;height:40px">ad link</a>
<a id="good" href="https://news.example/story" target="_blank" style="display:block;height:40px">real link</a>
<div style="height:800px"></div>
<script>
 window.__res = [];
 document.getElementById('btn').onclick = function(e){ e.stopPropagation(); __res.push(!!window.open('https://accounts.example/login')); __res.push(!!window.open('https://other.example/second')); };
 document.getElementById('adbtn').onclick = function(e){ e.stopPropagation(); __res.push(!!window.open('https://ads.example/pop')); };
 setTimeout(function(){ var d=document.createElement('div'); d.className='ad-box late'; d.id='late'; d.textContent='LATE AD'; document.body.appendChild(d); }, 300);
</script></body></html>"""

OVERLAY = """<!doctype html><html><head><meta name=viewport content="width=device-width"></head><body style="margin:0">
<p style="height:400px">Video page</p>
<a id="ov" href="https://scam.example/win" target="_blank" style="position:fixed;inset:0;opacity:0;z-index:9"></a>
</body></html>"""

fails = []
def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)))
    if not ok: fails.append(name)

def main():
    d = pathlib.Path(tempfile.mkdtemp())
    (d / "page.html").write_text(PAGE); (d / "overlay.html").write_text(OVERLAY)
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(d))
    http.server.SimpleHTTPRequestHandler.log_message = lambda *a: None
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), h)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_port}"
    with sync_playwright() as p:
        b = p.chromium.launch()
        ctx = b.new_context(viewport={"width": 393, "height": 851}, has_touch=True)
        ctx.route(re.compile(r"^https?://(?!127\.0\.0\.1).*"), lambda r: r.fulfill(status=200, body="<p>outside</p>", content_type="text/html"))
        ctx.add_init_script(STUB + "\n" + JS)
        opened = []
        pg = ctx.new_page(); pg.on("popup", lambda x: opened.append(x.url)); pg.goto(base + "/page.html"); pg.wait_for_timeout(700)
        vis = lambda sel: pg.eval_on_selector(sel, "e => getComputedStyle(e).display != 'none'")
        check("site rule hides .ad-top at document start", not vis(".ad-top"))
        check("generic rules hide .ad-box and #sponsored", not vis(".ad-box") and not vis("#sponsored"))
        check("ad added later is hidden too", not vis("#late"))
        check("normal content stays visible", vis("#text") and vis("#btn"))

        # "Click anywhere opens an ad": the page opens a window from a tap on plain text.
        pg.evaluate("document.addEventListener('click', function(){ __res.push(!!window.open('https://popunder.example/x')); })")
        pg.tap("#text"); pg.wait_for_timeout(300)
        res = pg.evaluate("__res")
        check("pop-up from a tap on plain text is blocked", res == [False] and len(opened) == 0, (res, len(opened)))

        pg.evaluate("__res = []"); pg.tap("#btn"); pg.wait_for_timeout(500)
        res = pg.evaluate("__res")
        check("pop-up from a real button opens (sign-in windows keep working)", res[:1] == [True] and len(opened) == 1, (res, len(opened)))
        check("a second pop-up from the same tap is blocked", res[1:2] == [False], res)

        pg.evaluate("__res = []"); n = len(opened); pg.tap("#adbtn"); pg.wait_for_timeout(300)
        check("window.open to an ad address is blocked, even from a button", pg.evaluate("__res") == [False] and len(opened) == n)

        url = pg.url; pg.tap("#adlink"); pg.wait_for_timeout(300)
        check("tapping a link to an ad address is stopped", pg.url == url, pg.url)

        n = len(opened); pg.tap("#good"); pg.wait_for_timeout(600)
        check("a normal new-tab link still opens", len(opened) == n + 1, opened[n:])

        pg2 = ctx.new_page(); pg2.on("popup", lambda x: opened.append(x.url))
        pg2.goto(base + "/overlay.html"); pg2.wait_for_timeout(300)
        n = len(opened); pg2.mouse.click(200, 200); pg2.wait_for_timeout(500)
        check("invisible full-page link (click-anywhere ad) is stopped", len(opened) == n and "popup" in pg2.evaluate("__log"), (len(opened) - n, pg2.evaluate("__log")))
        b.close()
    srv.shutdown()
    print(f"{11 - len(fails)} of 11 page checks passed")
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
