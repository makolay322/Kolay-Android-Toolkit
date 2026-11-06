package app.kolay.app.tools.apps;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.kolay.app.ui.*;
import org.json.JSONArray;

/**
 * A real browser (WebView) that the app-update sources drive: it opens the sites' own pages like a phone browser would,
 * reads them with small DOM scripts and catches the file download. Normally it works out of sight; when a site wants a
 * human check, a download does not start by itself, or the user should pick something, the page is shown over the
 * screen so the user can tap it. One request at a time, main thread only.
 */
@SuppressLint("SetJavaScriptEnabled")
final class Browser {
    interface Done { void ok(String value); void fail(String why); }
    interface Got { void file(String url, String userAgent, String referer); }

    private static final String DETECT = """
        (function(){var t=document.title||'';
        var ch=/just a moment|bir dakika|attention required|checking your browser|verify you are human|security check/i.test(t)
          ||!!document.querySelector('#challenge-form,#challenge-running,#cf-challenge-running,iframe[src*="challenges.cloudflare.com"]');
        return ch?'1':'0';})()""";

    private final Ctx c;
    private final WebView web;
    private final LinearLayout pane;
    private final TextView paneText;

    private int token;               // bumps on every new request; stale callbacks compare against it
    private String js;               // read script of the current eval (null in download mode)
    private Done done;
    private Got got;
    private String hopJs; private int hopsLeft;
    private String[] hosts = {};
    private long started; private boolean shownForChallenge, userMode;
    private int emptyTries;
    private String lastPage = "";

    Browser(Ctx c, FrameLayout parent) {
        this.c = c;
        web = new WebView(c.a);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return r.isForMainFrame() && !allowed(r.getUrl());
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap fav) { lastPage = url; }
            @Override public void onPageFinished(WebView v, String url) { schedule(700); }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (got == null) return;
            Got g = got; String ref = lastPage;
            finish();
            g.file(url, ua, ref);
        });

        pane = new LinearLayout(c.a);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundColor(c.p.bg);
        LinearLayout bar = new LinearLayout(c.a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(c.dp(Ui.GUTTER), c.dp(10), c.dp(8), c.dp(10));
        paneText = Ui.text(c, "", 14, c.p.ink, 500);
        paneText.setLineSpacing(0, 1.2f);
        bar.addView(paneText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = Ui.button(c, "Vazgeç", Ui.Style.GHOST, null);
        close.setOnClickListener(v -> cancel("İptal edildi"));
        bar.addView(close, new LinearLayout.LayoutParams(-2, c.dp(Ui.H_GHOST)));
        pane.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        pane.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        pane.setVisibility(View.INVISIBLE);   // laid out (so pages render and scripts run) but not shown
        parent.addView(pane, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Main-frame navigation stays on the source's own sites and file links; ad redirects and app-store intents are dropped. */
    private boolean allowed(Uri u) {
        if (!"https".equals(u.getScheme())) return false;
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
        for (String a : hosts) if (h.equals(a) || h.endsWith("." + a)) return true;
        String p = u.getPath() == null ? "" : u.getPath().toLowerCase();
        return p.endsWith(".apk") || p.endsWith(".xapk") || p.endsWith(".apkm") || p.endsWith(".apks");
    }

    /** Loads {@code url}, waits until it is a real page (not a bot check), runs {@code script} and returns its string result. */
    void eval(String url, String[] hosts, String script, Done d) {
        begin(hosts);
        js = script; done = d;
        web.loadUrl(url);
    }

    /**
     * Opens {@code url} and waits for a file download. After each page, {@code hop} (a script returning the next URL to open,
     * or nothing) moves on up to {@code hops} times; if no download starts, the page is shown and the user taps the link.
     */
    void download(String url, String[] hosts, String hop, int hops, Got g, Done failed) {
        begin(hosts);
        got = g; done = failed; hopJs = hop; hopsLeft = hops;
        web.loadUrl(url);
    }

    private void begin(String[] h) {
        token++;
        hosts = h; js = null; got = null; done = null; hopJs = null;
        started = System.currentTimeMillis(); shownForChallenge = false; userMode = false; emptyTries = 0;
        int t = token;
        c.ui.postDelayed(() -> { if (t == token && !userMode && !shownForChallenge) cancel("Site yanıt vermedi"); }, 60_000);
    }

    private void schedule(long ms) {
        int t = token;
        c.ui.postDelayed(() -> { if (t == token) check(); }, ms);
    }

    private void check() {
        if (done == null && got == null) return;
        int t = token;
        web.evaluateJavascript(DETECT, v -> {
            if (t != token) return;
            if ("\"1\"".equals(v)) {   // bot check still on screen: give the browser a moment, then let the user see it
                if (!shownForChallenge && System.currentTimeMillis() - started > 8000) {
                    shownForChallenge = true;
                    show("Site bir doğrulama istiyor. Gerekirse kutucuğa dokun; sonrası otomatik devam eder.");
                    int tt = token;
                    c.ui.postDelayed(() -> { if (tt == token) cancel("Doğrulama tamamlanmadı"); }, 180_000);
                }
                schedule(1500);
                return;
            }
            if (shownForChallenge && !userMode) { shownForChallenge = false; hide(); }
            if (js != null) read(t); else if (got != null && !userMode) hop(t);
        });
    }

    private void read(int t) {
        web.evaluateJavascript(js, v -> {
            if (t != token) return;
            String s = unquote(v);
            if ((s == null || s.isEmpty()) && emptyTries++ < 3) { schedule(1200); return; }   // late-rendered content
            Done d = done;
            finish();
            if (d != null) d.ok(s);
        });
    }

    private void hop(int t) {
        // A short pause first: many download pages start the file by themselves after a moment.
        c.ui.postDelayed(() -> {
            if (t != token || got == null) return;
            if (hopsLeft <= 0 || hopJs == null) { askUser(); return; }
            web.evaluateJavascript(hopJs, v -> {
                if (t != token || got == null) return;
                String next = unquote(v);
                if (next != null && next.startsWith("https://")) { hopsLeft--; web.loadUrl(next); }
                else askUser();
            });
        }, 2200);
    }

    private void askUser() {
        if (userMode) return;
        userMode = true;
        show("İndirme kendiliğinden başlamadı. Sayfadaki indirme bağlantısına dokun; dosya inince kurulum otomatik sürer.");
        int t = token;
        c.ui.postDelayed(() -> { if (t == token) cancel("İndirme başlatılmadı"); }, 240_000);
    }

    private void show(String msg) {
        paneText.setText(msg);
        if (pane.getVisibility() != View.VISIBLE) { pane.setVisibility(View.VISIBLE); Ui.appear(pane, 0); }
    }

    private void hide() { pane.setVisibility(View.INVISIBLE); }

    /** Stops the current request and reports {@code why} to its caller. */
    void cancel(String why) {
        Done d = done;
        finish();
        web.stopLoading();
        if (d != null) d.fail(why);
    }

    private void finish() {
        token++;
        js = null; got = null; done = null; hopJs = null; userMode = false; shownForChallenge = false;
        hide();
    }

    boolean showing() { return pane.getVisibility() == View.VISIBLE; }

    void destroy() {
        token++;
        done = null; got = null;
        web.stopLoading();
        web.destroy();
    }

    /** evaluateJavascript hands back a JSON literal; turn a JSON string into its text, anything else into null. */
    private static String unquote(String v) {
        if (v == null || v.equals("null") || !v.startsWith("\"")) return null;
        try { return new JSONArray("[" + v + "]").getString(0); } catch (Exception e) { return null; }
    }
}
