package app.kolay.app.tools.netscan;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.Imaging;
import app.kolay.app.tools.scan.PagesScreen;
import app.kolay.app.tools.scan.ScanSession;
import app.kolay.app.ui.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * One scanner: connect (HTTPS with a pinned certificate, else LAN-only HTTP), settings from its capabilities, scan jobs.
 * Root of a {@link ScanSession} flow: pages go to the same pages → filter → export screens as the camera scanner, and
 * leaving this screen discards them.
 */
final class ScannerScreen extends Screen {
    private static final int[] DPIS = {150, 200, 300};
    private static final int A4_W = 2480, A4_H = 3508, LETTER_W = 2550, LETTER_H = 3300;   // 300ths of an inch
    private static final String[] SOURCE_LABELS = {"Cam", "Besleyici", "Çift taraflı"};
    private static final String[] MODE_LABELS = {"Renkli", "Gri", "Siyah-beyaz"};

    private final Device d;
    private final Store store;
    private ScanSession s;
    private volatile EsclClient client;
    private Escl.Caps caps;
    private Header header;
    private FrameLayout body;
    private LinearLayout bar;
    private TextView note, progress;
    private volatile boolean destroyed;
    private boolean scanning, cancelling;
    private long backArmedAt;
    // settings: source 0 platen / 1 feeder / 2 duplex, colour 0 colour / 1 grey / 2 black-and-white
    private int source = -1, dpi, mode, size;
    private String message = "";

    ScannerScreen(Device d, Store store) { this.d = d; this.store = store; }

    @Override public View create() {
        s = new ScanSession(c);
        s.camera = false;
        s.root = this;
        s.onChange = this::paintBar;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, d.name, "Bağlanıyor…", this::back);
        page.addView(header.view);
        body = new FrameLayout(c.a);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        bar = Ui.bottomBar(c);
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        connect();
        return page;
    }

    // ---- connecting -------------------------------------------------------------------------------------------

    private void connect() {
        caps = null;
        header.subtitle.setText("Bağlanıyor…");
        body.removeAllViews();
        body.addView(Ui.busy(c, "Bağlanıyor…", d.host), new FrameLayout.LayoutParams(-1, -1));
        bar.removeAllViews();
        EsclClient cl = new EsclClient(d, store, Lan::isLan);
        client = cl;
        c.io.execute(() -> {
            Escl.Caps got = null;
            IOException err = null;
            try { got = cl.connect(); } catch (IOException e) { err = e; }
            final Escl.Caps fc = got; final IOException fe = err;
            c.ui.post(() -> {
                if (destroyed || client != cl) return;
                if (fe instanceof Http.Aborted) return;
                if (fe instanceof EsclClient.TrustProblem) { trust(((EsclClient.TrustProblem) fe).mismatch); return; }
                if (fe instanceof Lan.NotLan) {
                    failed("Yerel ağ adresi değil", "Güvenlik için Ağ Tarayıcı yalnızca yerel ağdaki cihazlara (ör. 192.168.x.x, 10.x.x.x) bağlanır.");
                    return;
                }
                if (fe instanceof UnknownHostException) { failed("Adres bulunamadı", "“" + d.host + "” çözülemedi. IP adresini girmeyi dene."); return; }
                if (fe != null) {
                    failed("Tarayıcıya bağlanılamadı", "Tarayıcı açık ve aynı ağda mı? eSCL / AirScan destekliyor mu?\n(" + msg(fe) + ")");
                    return;
                }
                caps = fc;
                restoreSettings();
                if (source < 0) { failed("Desteklenmeyen tarayıcı", "Tarayıcı JPEG ya da PDF olarak tarama sunmuyor."); return; }
                form();
            });
        });
    }

    private static String msg(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "zaman aşımı";
        if (e instanceof java.net.ConnectException) return "bağlantı reddedildi";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void failed(String title, String text) {
        header.subtitle.setText("Bağlanılamadı");
        body.removeAllViews();
        body.addView(Ui.state(c, Icon.Kind.NETSCAN, title, text, "Yeniden dene", this::connect), new FrameLayout.LayoutParams(-1, -1));
        bar.removeAllViews();
    }

    /** Pinned certificate changed ({@code mismatch}) or a pinned scanner offers no HTTPS any more: no connection, no downgrade. */
    private void trust(boolean mismatch) {
        header.subtitle.setText("Güvenlik uyarısı");
        body.removeAllViews();
        LinearLayout col = Ui.state(c, Icon.Kind.KEY,
            mismatch ? "Tarayıcının sertifikası değişti" : "Şifreli bağlantı kurulamadı",
            mismatch ? "Bu adres ilk bağlantıda başka bir sertifika göstermişti. Araya başka bir cihaz girmiş olabilir; bağlantı kurulmadı. "
                + "Tarayıcıyı sıfırladıysan ya da yazılımını güncellediysen güveni sıfırlayıp yeniden bağlanabilirsin."
                : "Bu tarayıcıya daha önce şifreli (HTTPS) bağlanılmıştı; güvenlik için şifresiz bağlantıya geçilmez. "
                + "Tarayıcı açık mı? HTTPS'i bilerek kapattıysan güveni sıfırlayabilirsin.",
            null, null);
        LinearLayout acts = new LinearLayout(c.a);
        acts.setOrientation(LinearLayout.VERTICAL);
        acts.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        TextView forget = Ui.button(c, "Güveni sıfırla", Ui.Style.GHOST, null);
        forget.setTextColor(c.p.danger);
        forget.setOnClickListener(v -> {
            if (!"Eminim, sıfırla".contentEquals(forget.getText())) { forget.setText("Eminim, sıfırla"); Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK); return; }
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
            store.forgetPin(d.key);
            connect();
        });
        acts.addView(forget, Ui.lp(-1, c.dp(Ui.H_GHOST)));
        col.addView(acts, Ui.lp(-1, -2, 0, c.dp(18), 0, 0));
        body.addView(col, new FrameLayout.LayoutParams(-1, -1));
        bar.removeAllViews();
        TextView retry = Ui.button(c, "Yeniden dene", Ui.Style.PRIMARY, Icon.Kind.REFRESH);
        retry.setOnClickListener(v -> connect());
        bar.addView(retry, Ui.lp(-1, c.dp(Ui.H_BAR)));
    }

    // ---- settings ---------------------------------------------------------------------------------------------

    private Escl.Input input(int src) {
        Escl.Input in = src == 0 ? caps.platen : src == 1 ? caps.adf : caps.duplexInput();
        return in != null && in.format() != null ? in : null;
    }

    /** Resolutions offered for {@code in}: 150/200/300 where supported, else up to three supported ones near 200. */
    private static List<Integer> dpis(Escl.Input in) {
        List<Integer> out = new ArrayList<>();
        for (int v : DPIS) if (in.dpi(v)) out.add(v);
        if (!out.isEmpty()) return out;
        List<Integer> all = new ArrayList<>();
        for (int v : in.dpis) if (v >= 75 && v <= 600) all.add(v);
        if (all.isEmpty() && in.ranges.isEmpty()) { for (int v : DPIS) out.add(v); return out; }   // nothing listed: assume the usual
        all.sort((a, b) -> Math.abs(a - 200) != Math.abs(b - 200) ? Math.abs(a - 200) - Math.abs(b - 200) : a - b);
        for (int i = 0; i < Math.min(3, all.size()); i++) out.add(all.get(i));
        java.util.Collections.sort(out);
        return out;
    }

    /** eSCL colour mode for the UI choice; null when the input cannot do it. Grey / black-and-white fall back to colour + filter. */
    private static String esclMode(Escl.Input in, int m) {
        boolean rgb = in.modes.isEmpty() || in.modes.contains(Escl.RGB), gray = in.modes.contains(Escl.GRAY);
        if (m == 0) return rgb ? Escl.RGB : null;
        return gray ? Escl.GRAY : rgb ? Escl.RGB : null;
    }

    private void restoreSettings() {
        source = -1;
        String saved = store.settings(d.key);
        int sSrc = -1, sDpi = 300, sMode = 0, sSize = 0;   // no saved settings: 300 dpi when supported (see fit)
        if (saved != null) {
            String[] f = saved.split(",");
            try { sSrc = Integer.parseInt(f[0]); sDpi = Integer.parseInt(f[1]); sMode = Integer.parseInt(f[2]); sSize = Integer.parseInt(f[3]); }
            catch (RuntimeException ignored) { }
        }
        if (sSrc >= 0 && sSrc <= 2 && input(sSrc) != null) source = sSrc;
        else for (int i = 0; i <= 2 && source < 0; i++) if (input(i) != null) source = i;
        if (source < 0) return;
        mode = sMode >= 0 && sMode <= 2 ? sMode : 0;
        size = sSize == 1 ? 1 : 0;
        dpi = sDpi;
        fit();
    }

    /** Keeps resolution and colour valid for the current source. */
    private void fit() {
        Escl.Input in = input(source);
        List<Integer> ds = dpis(in);
        if (!ds.contains(dpi)) {
            int best = ds.get(0);
            for (int v : ds) if (Math.abs(v - 200) < Math.abs(best - 200)) best = v;
            dpi = best;
        }
        if (esclMode(in, mode) == null) for (int m = 0; m <= 2; m++) if (esclMode(in, m) != null) { mode = m; break; }
    }

    private void form() {
        Palette p = c.p;
        Escl.Input in = input(source);
        header.subtitle.setText((caps.model.isEmpty() ? d.host : caps.model) + (client.secure() ? " · HTTPS" : " · HTTP"));
        body.removeAllViews();
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));

        // connection security
        LinearLayout sec = Ui.card(c, p.surface, 14);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setGravity(Gravity.CENTER_VERTICAL);
        boolean tls = client.secure();
        FrameLayout tile = new FrameLayout(c.a);
        tile.setBackground(Ui.rounded(c, tls ? p.okSoft : p.warnSoft, 12));
        tile.addView(Ui.icon(c, Icon.Kind.KEY, tls ? p.ok : p.warn, 20), new FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER));
        sec.addView(tile, new LinearLayout.LayoutParams(c.dp(40), c.dp(40)));
        LinearLayout st = new LinearLayout(c.a);
        st.setOrientation(LinearLayout.VERTICAL);
        st.addView(Ui.text(c, tls ? "Şifreli bağlantı (HTTPS)" : "Şifresiz bağlantı (HTTP)", 15, p.ink, 600));
        String first = client.firstPin;
        String line = !tls ? "Tarayıcı HTTPS sunmuyor; yalnızca yerel ağda bağlanılır."
            : first != null ? "İlk bağlantıda güvenildi · parmak izi " + Tofu.pretty(first, 6) + "…"
            : "Sertifika ilk bağlantıdakiyle aynı";
        TextView sl = Ui.text(c, line, 13, p.muted, 400);
        sl.setLineSpacing(0, 1.15f);
        st.addView(sl, Ui.lp(-2, -2, 0, c.dp(3), 0, 0));
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(0, -2, 1);
        stl.setMarginStart(c.dp(14));
        sec.addView(st, stl);
        col.addView(sec, Ui.lp(-1, -2));

        // source
        List<Integer> srcs = new ArrayList<>();
        for (int i = 0; i <= 2; i++) if (input(i) != null) srcs.add(i);
        if (srcs.size() > 1) {
            String[] labels = new String[srcs.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = SOURCE_LABELS[srcs.get(i)];
            col.addView(Ui.label(c, "KAYNAK"), Ui.lp(-2, -2, c.dp(4), c.dp(18), 0, c.dp(8)));
            col.addView(Ui.segmented(c, labels, null, srcs.indexOf(source), i -> { source = srcs.get(i); fit(); form(); }), Ui.lp(-1, c.dp(44)));
        } else {
            col.addView(Ui.label(c, "KAYNAK: " + SOURCE_LABELS[source].toUpperCase(java.util.Locale.forLanguageTag("tr"))), Ui.lp(-2, -2, c.dp(4), c.dp(18), 0, 0));
        }

        // resolution
        List<Integer> ds = dpis(in);
        String[] dl = new String[ds.size()];
        for (int i = 0; i < dl.length; i++) dl[i] = ds.get(i) + " dpi";
        col.addView(Ui.label(c, "ÇÖZÜNÜRLÜK"), Ui.lp(-2, -2, c.dp(4), c.dp(18), 0, c.dp(8)));
        col.addView(Ui.segmented(c, dl, null, ds.indexOf(dpi), i -> { dpi = ds.get(i); form(); }), Ui.lp(-1, c.dp(44)));

        // colour
        List<Integer> ms = new ArrayList<>();
        for (int m = 0; m <= 2; m++) if (esclMode(in, m) != null) ms.add(m);
        String[] ml = new String[ms.size()];
        for (int i = 0; i < ml.length; i++) ml[i] = MODE_LABELS[ms.get(i)];
        col.addView(Ui.label(c, "RENK"), Ui.lp(-2, -2, c.dp(4), c.dp(18), 0, c.dp(8)));
        col.addView(Ui.segmented(c, ml, null, ms.indexOf(mode), i -> { mode = ms.get(i); form(); }), Ui.lp(-1, c.dp(44)));

        // paper
        col.addView(Ui.label(c, "KÂĞIT"), Ui.lp(-2, -2, c.dp(4), c.dp(18), 0, c.dp(8)));
        col.addView(Ui.segmented(c, new String[]{"A4", "Letter"}, null, size, i -> { size = i; form(); }), Ui.lp(-1, c.dp(44)));

        note = Ui.text(c, message, 14, p.danger, 500);
        note.setLineSpacing(0, 1.2f);
        note.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        col.addView(note, Ui.lp(-1, -2, c.dp(4), c.dp(18), c.dp(4), 0));

        sv.addView(col);
        body.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        paintBar();
    }

    private void paintBar() {
        if (scanning || caps == null || source < 0) return;
        bar.removeAllViews();
        int n = s.count();
        TextView scan = Ui.button(c, n == 0 ? "Tara" : source == 0 ? "Bir sayfa daha tara" : "Daha fazla tara",
            n == 0 ? Ui.Style.PRIMARY : Ui.Style.TONAL, Icon.Kind.NETSCAN);
        scan.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); scan(); });
        if (n == 0) { bar.addView(scan, Ui.lp(-1, c.dp(Ui.H_BAR))); return; }
        TextView pages = Ui.button(c, "Sayfalar (" + n + ")", Ui.Style.PRIMARY, Icon.Kind.CHEVRON);
        pages.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); c.nav.push(new PagesScreen(s)); });
        bar.addView(Ui.pair(c, scan, pages, Ui.H_BAR));
    }

    // ---- scanning ---------------------------------------------------------------------------------------------

    private void scan() {
        if (scanning || caps == null) return;
        EsclClient cl = client;
        Escl.Input in = input(source);
        boolean feeder = source > 0;
        String xml = Escl.settings(caps, in, feeder, source == 2, esclMode(in, mode), dpi, size == 1 ? LETTER_W : A4_W, size == 1 ? LETTER_H : A4_H);
        store.saveSettings(d.key, source + "," + dpi + "," + mode + "," + size);
        String gotMode = esclMode(in, mode);
        s.defaultFilter = mode == 2 ? Imaging.Filter.BW : mode == 1 && !Escl.GRAY.equals(gotMode) ? Imaging.Filter.GRAY : Imaging.Filter.ORIGINAL;
        final int renderDpi = dpi;
        scanning = true; cancelling = false; message = "";

        body.removeAllViews();
        LinearLayout busy = Ui.busy(c, feeder ? "Besleyiciden taranıyor…" : "Taranıyor…", "");
        progress = (TextView) busy.getChildAt(busy.getChildCount() - 1);
        progress.setText(s.count() == 0 ? "Tarayıcı hazırlanıyor" : s.count() + " sayfa hazır");
        body.addView(busy, new FrameLayout.LayoutParams(-1, -1));
        bar.removeAllViews();
        TextView stop = Ui.button(c, "Vazgeç", Ui.Style.GHOST, null);
        stop.setOnClickListener(v -> cancel());
        bar.addView(stop, Ui.lp(-1, c.dp(Ui.H_BAR)));

        c.io.execute(() -> {
            int n = 0;
            IOException err = null;
            boolean empty = false;
            try {
                if (feeder) {
                    Escl.Status stt = null;
                    try { stt = cl.status(); }
                    catch (EsclClient.TrustProblem | Http.Aborted x) { throw x; }
                    catch (IOException ignored) { }   // status is advisory; the job itself decides
                    empty = stt != null && stt.adfEmpty();
                }
                if (!empty) n = cl.scan(xml, feeder, new EsclClient.Pages() {
                    @Override public File file() { return s.newFile(); }
                    @Override public void got(File f, String type) throws IOException {
                        if (isPdf(f, type)) for (File pg : rasterize(f, renderDpi)) deliver(pg);
                        else deliver(f);
                    }
                });
            } catch (IOException e) { err = e; }
            final int got = n; final IOException fe = err; final boolean fEmpty = empty;
            c.ui.post(() -> {
                if (destroyed) return;
                scanning = false;
                if (fe instanceof EsclClient.TrustProblem) { trust(((EsclClient.TrustProblem) fe).mismatch); return; }
                if (fe instanceof Http.Aborted) message = "";
                else if (fe instanceof EsclClient.Busy) message = "Tarayıcı meşgul (başka bir iş ya da kopyalama sürüyor). Biraz sonra yeniden dene.";
                else if (fe != null) message = "Tarama başarısız: " + msg(fe);
                else if (fEmpty || (got == 0 && feeder)) message = "Besleyicide kâğıt yok. Sayfaları besleyiciye koyup yeniden dene.";
                else if (got == 0) message = "Tarayıcı sayfa göndermedi.";
                form();
                if (got > 0) {
                    Ui.haptic(body, HapticFeedbackConstants.CONFIRM);
                    if (feeder) c.nav.push(new PagesScreen(s));
                }
            });
        });
    }

    /** Scanning thread: hands a finished page file to the session (main thread). */
    private void deliver(File f) {
        c.ui.post(() -> {
            if (destroyed || s.destroyed()) { c.io.execute(f::delete); return; }
            s.addScanned(f);
            if (progress != null && scanning) Ui.swapText(progress, s.count() + " sayfa alındı");
        });
    }

    private static boolean isPdf(File f, String type) throws IOException {
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("pdf")) return true;
        byte[] b = new byte[4];
        try (InputStream in = new FileInputStream(f)) { return in.read(b) == 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F'; }
    }

    /** Scanners that only deliver PDF: every page rendered at the scan resolution (≤ 12 MP) into a JPEG page file. */
    private List<File> rasterize(File pdf, int dpi) throws IOException {
        List<File> out = new ArrayList<>();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(fd)) {
            for (int i = 0; i < r.getPageCount(); i++) {
                try (PdfRenderer.Page pg = r.openPage(i)) {
                    double scale = dpi / 72.0, px = pg.getWidth() * scale * pg.getHeight() * scale;
                    if (px > Imaging.FULL) scale *= Math.sqrt(Imaging.FULL / px);
                    Bitmap b = Bitmap.createBitmap(Math.max(1, (int) Math.round(pg.getWidth() * scale)), Math.max(1, (int) Math.round(pg.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                    try {
                        b.eraseColor(Color.WHITE);
                        pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        File f = s.newFile();
                        try (OutputStream o = new FileOutputStream(f)) {
                            if (!b.compress(Bitmap.CompressFormat.JPEG, 92, o)) throw new IOException("Sayfa dönüştürülemedi");
                        }
                        out.add(f);
                    } finally { b.recycle(); }
                }
            }
        } catch (OutOfMemoryError e) {
            throw new IOException("Bellek yetmedi");
        } finally {
            pdf.delete();
        }
        return out;
    }

    private void cancel() {
        if (!scanning || cancelling) return;
        cancelling = true;
        EsclClient cl = client;
        if (cl != null) cl.cancel();
        if (progress != null) Ui.swapText(progress, "İptal ediliyor…");
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------

    private void back() {
        if (scanning) { cancel(); return; }
        if (s.count() > 0 && SystemClock.uptimeMillis() - backArmedAt > 3000) {
            backArmedAt = SystemClock.uptimeMillis();
            Toast.makeText(c.a, s.count() + " sayfa silinecek. Çıkmak için tekrar geri dokun.", Toast.LENGTH_SHORT).show();
            return;
        }
        c.nav.pop();
    }

    @Override public boolean onBack() { back(); return true; }

    @Override public void onReturn() {
        if (s.closing) return;   // ExportScreen "Bitti": this screen is about to be popped as well
        s.onChange = this::paintBar;
        if (caps != null && !scanning) { message = ""; form(); }
    }

    @Override public void onDestroy() {
        destroyed = true;
        EsclClient cl = client;
        if (cl != null) cl.cancel();   // the scanning thread deletes the job on the scanner
        s.destroy();
    }
}
