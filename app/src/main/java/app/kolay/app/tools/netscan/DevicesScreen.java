package app.kolay.app.tools.netscan;

import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import app.kolay.app.tools.scan.ScanSession;
import app.kolay.app.ui.*;

/** Scanners found on the local network (mDNS) plus addresses entered by hand. Discovery runs only while this screen is on top. */
public final class DevicesScreen extends Screen {
    private static final long QUIET_MS = 8000;

    private final ScanSession s;

    private Store store;
    private Discovery disco;
    private Header header;
    private LinearLayout foundBox, manualBox;
    private EditText input;
    private long startedAt;
    private boolean shown;

    /** @param s the document flow the scanned pages go to */
    public DevicesScreen(ScanSession s) { this.s = s; }

    @Override public View create() {
        Palette p = c.p;
        store = new Store(c.a);
        disco = new Discovery(c, this::paintFound);

        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "Ağ Tarayıcı", "Yerel ağda tarayıcı aranıyor…", () -> c.nav.pop());
        page.addView(header.view);

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));

        col.addView(Ui.label(c, "BULUNAN TARAYICILAR"), Ui.lp(-2, -2, c.dp(4), c.dp(4), 0, c.dp(8)));
        foundBox = new LinearLayout(c.a);
        foundBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(foundBox, Ui.lp(-1, -2));

        col.addView(Ui.label(c, "ADRESLE BAĞLAN"), Ui.lp(-2, -2, c.dp(4), c.dp(22), 0, c.dp(8)));
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = Ui.input(c, "ör. 192.168.1.20", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener((v, id, e) -> { if (id == EditorInfo.IME_ACTION_GO) { manual(); return true; } return false; });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView go = Ui.button(c, "Bağlan", Ui.Style.TONAL, null);
        go.setOnClickListener(v -> manual());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, c.dp(Ui.H_GHOST));
        gl.setMarginStart(c.dp(Ui.GAP));
        row.addView(go, gl);
        col.addView(row, Ui.lp(-1, -2));
        manualBox = new LinearLayout(c.a);
        manualBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(manualBox, Ui.lp(-1, -2));

        TextView help = Ui.text(c, "Yalnızca eSCL / AirScan (Mopria) destekleyen tarayıcılar çalışır; yalnızca WSD destekleyen tarayıcılar desteklenmez. "
            + "Telefon ve tarayıcı aynı ağda olmalı. Bağlantı önce şifreli (HTTPS) denenir; şifresiz bağlantı yalnızca yerel ağ adreslerine yapılır.", 13, p.muted, 400);
        help.setLineSpacing(0, 1.2f);
        col.addView(help, Ui.lp(-1, -2, c.dp(4), c.dp(22), c.dp(4), 0));

        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        paintFound();
        paintManual();
        return page;
    }

    @Override public void onShown() {
        shown = true;
        start();
        for (int i = 0; i < foundBox.getChildCount(); i++) Ui.appear(foundBox.getChildAt(i), 40L * Math.min(i, 6));
    }

    @Override public void onResume() { if (shown) start(); }
    @Override public void onPause() { disco.stop(); }
    @Override public void onReturn() { paintManual(); start(); }
    @Override public void onDestroy() { disco.stop(); }

    private void start() {
        if (disco.running()) return;
        startedAt = android.os.SystemClock.uptimeMillis();
        disco.start();
        paintFound();
        c.ui.postDelayed(this::paintFound, QUIET_MS + 100);
    }

    private void paintFound() {
        if (foundBox == null) return;
        foundBox.removeAllViews();
        Palette p = c.p;
        int n = disco.found.size();
        for (int i = 0; i < n; i++) {
            Device d = disco.found.get(i);
            String sub = d.host + (d.httpsPort > 0 ? " · HTTPS" : "");
            foundBox.addView(row(i, d, sub, null), Ui.lp(-1, -2, 0, i == 0 ? 0 : c.dp(10), 0, 0));
        }
        boolean searching = disco.running() && android.os.SystemClock.uptimeMillis() - startedAt < QUIET_MS;
        if (n == 0 || searching) {
            LinearLayout r = new LinearLayout(c.a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(c.dp(4), c.dp(n == 0 ? 4 : 12), c.dp(4), c.dp(4));
            if (searching) {
                r.addView(new Arc(c.a, p.primarySoft, p.primary, c.dp(2.5f)), new LinearLayout.LayoutParams(c.dp(18), c.dp(18)));
                TextView t = Ui.text(c, "Aranıyor…", 14, p.muted, 500);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.setMarginStart(c.dp(10));
                r.addView(t, tl);
            } else {
                TextView t = Ui.text(c, "Tarayıcı bulunamadı. Tarayıcı açık ve aynı Wi-Fi ağında mı? Adresini aşağıya elle de girebilirsin.", 14, p.muted, 400);
                t.setLineSpacing(0, 1.2f);
                r.addView(t, new LinearLayout.LayoutParams(-1, -2));
            }
            foundBox.addView(r, Ui.lp(-1, -2));
        }
        header.subtitle.setText(n > 0 ? n + " tarayıcı bulundu" : searching ? "Yerel ağda tarayıcı aranıyor…" : "Ağda tarayıcı bulunamadı");
    }

    private void paintManual() {
        manualBox.removeAllViews();
        int i = 0;
        for (String spec : store.hosts()) {
            Device d = Device.manual(spec);
            if (d == null) continue;
            View del = Ui.iconButton(c, Icon.Kind.TRASH, "Listeden kaldır", () -> { store.removeHost(spec); paintManual(); });
            manualBox.addView(row(100 + i, d, "Elle eklendi", del), Ui.lp(-1, -2, 0, c.dp(10), 0, 0));
            i++;
        }
    }

    private View row(int idx, Device d, String sub, View trailing) {
        Palette p = c.p;
        LinearLayout card = Ui.card(c, p.surface, 14);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout tile = new FrameLayout(c.a);
        tile.setBackground(Ui.rounded(c, p.softBg(idx), 12));
        tile.addView(Ui.icon(c, Icon.Kind.NETSCAN, p.softFg(idx), 20), new FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER));
        card.addView(tile, new LinearLayout.LayoutParams(c.dp(40), c.dp(40)));
        LinearLayout t = new LinearLayout(c.a);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, d.name, 16, p.ink, 600);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(name);
        TextView s = Ui.text(c, sub, 13, p.muted, 400);
        s.setSingleLine(true); s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(s, Ui.lp(-2, -2, 0, c.dp(2), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(14)); tl.setMarginEnd(c.dp(8));
        card.addView(t, tl);
        card.addView(trailing != null ? trailing : Ui.icon(c, Icon.Kind.CHEVRON, p.muted, 20));
        card.setClickable(true);
        card.setContentDescription(d.name);
        card.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); open(d); });
        Ui.press(card);
        return card;
    }

    private void manual() {
        Device d = Device.manual(input.getText().toString());
        if (d == null) { Toast.makeText(c.a, "Geçerli bir adres gir (ör. 192.168.1.20)", Toast.LENGTH_SHORT).show(); return; }
        InputMethodManager im = (InputMethodManager) c.a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        store.addHost(d.spec());
        input.setText("");
        paintManual();
        open(d);
    }

    private void open(Device d) {
        disco.stop();
        c.nav.push(new ScannerScreen(d, store, s));
    }
}
