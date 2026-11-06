package app.kolay.app.tools.scan;

import android.graphics.Outline;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;

/** The scanned pages: order (up / down), rotate, crop, filter, delete; then save. Back returns to the camera with the pages kept. */
public final class PagesScreen extends Screen {
    private final ScanSession s;
    private Header header;
    private LinearLayout list;
    private FrameLayout body;
    private ScrollView scroll;
    private TextView save;
    private ScanSession.Page confirmDelete;

    public PagesScreen(ScanSession s) { this.s = s; }

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "Sayfalar", "", () -> c.nav.pop());
        page.addView(header.view);
        body = new FrameLayout(c.a);
        scroll = new ScrollView(c.a);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(c.a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        scroll.addView(list);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bb = Ui.bottomBar(c);
        TextView more = Ui.button(c, "Sayfa ekle", Ui.Style.TONAL, Icon.Kind.PLUS);
        more.setOnClickListener(v -> c.nav.pop());
        save = Ui.button(c, "Kaydet", Ui.Style.PRIMARY, Icon.Kind.DOWNLOAD);
        save.setOnClickListener(v -> {
            if (s.pages.isEmpty()) return;
            if (!s.allReady()) { Toast.makeText(c.a, "Sayfalar hazırlanıyor, bir an bekle", Toast.LENGTH_SHORT).show(); return; }
            Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            c.nav.push(new ExportScreen(s));
        });
        bb.addView(Ui.pair(c, more, save, Ui.H_BAR));
        page.addView(bb, new LinearLayout.LayoutParams(-1, -2));
        s.onChange = this::refresh;
        refresh();
        return page;
    }

    @Override public void onShown() {
        for (int i = 0; i < list.getChildCount(); i++) Ui.appear(list.getChildAt(i), 40L * Math.min(i, 8));
    }

    @Override public void onReturn() { s.onChange = this::refresh; refresh(); }

    private void refresh() {
        int n = s.pages.size();
        header.subtitle.setText(n == 0 ? "Sayfa kalmadı" : n + " sayfa · sırala, kırp, filtre seç");
        save.setEnabled(n > 0); save.setAlpha(n > 0 ? 1f : 0.45f);
        int y = scroll.getScrollY();
        list.removeAllViews();
        body.removeAllViews();
        if (n == 0) {
            LinearLayout st = s.camera ? Ui.state(c, Icon.Kind.DOCSCAN, "Sayfa kalmadı", "Kameraya dönüp yeni sayfa çekebilirsin.", "Kameraya dön", () -> c.nav.pop())
                : Ui.state(c, Icon.Kind.NETSCAN, "Sayfa kalmadı", "Tarayıcıya dönüp yeni sayfa tarayabilirsin.", "Tarayıcıya dön", () -> c.nav.pop());
            body.addView(st, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < n; i++) list.addView(row(i), Ui.lp(-1, -2, 0, i == 0 ? 0 : c.dp(12), 0, 0));
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private View row(int i) {
        Palette p = c.p;
        ScanSession.Page pg = s.pages.get(i);
        LinearLayout card = Ui.card(c, p.surface, 12);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(c.a);
        FrameLayout th = new FrameLayout(c.a);
        th.setBackground(Ui.rounded(c, p.surface2, 12));
        final float r = c.dp(12);
        th.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        th.setClipToOutline(true);
        if (pg.thumb != null) {
            ImageView iv = new ImageView(c.a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(pg.thumb);
            th.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else if (pg.broken) {
            th.addView(Ui.icon(c, Icon.Kind.IMAGE, p.danger, 24), new FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.CENTER));
        } else {
            th.addView(new Arc(c.a, p.primarySoft, p.primary, c.dp(3)), new FrameLayout.LayoutParams(c.dp(26), c.dp(26), Gravity.CENTER));
        }
        th.setClickable(!pg.broken && pg.ready);
        th.setContentDescription("Sayfa " + (i + 1) + ", kırp");
        th.setOnClickListener(v -> crop(pg));
        top.addView(th, new LinearLayout.LayoutParams(c.dp(84), c.dp(112)));

        LinearLayout right = new LinearLayout(c.a);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(Ui.text(c, "Sayfa " + (i + 1), 16, p.ink, 600));
        String st = pg.broken ? "Görüntü okunamadı" : !pg.ready ? "Kenarlar aranıyor…" : pg.filter.label + (pg.rotation != 0 ? " · " + pg.rotation + "°" : "");
        right.addView(Ui.text(c, st, 13, pg.broken ? p.danger : p.muted, 400), Ui.lp(-2, -2, 0, c.dp(3), 0, 0));
        LinearLayout tools = new LinearLayout(c.a);
        boolean usable = pg.ready && !pg.broken;
        tools.addView(tool(Icon.Kind.SEND, 0, "Yukarı taşı", i > 0, () -> s.move(i, i - 1)));
        tools.addView(tool(Icon.Kind.SEND, 180, "Aşağı taşı", i < s.pages.size() - 1, () -> s.move(i, i + 1)));
        tools.addView(tool(Icon.Kind.REFRESH, 0, "Döndür", usable, () -> { pg.rotation = (pg.rotation + 90) % 360; s.edited(pg); refresh(); }));
        tools.addView(tool(Icon.Kind.CROP, 0, "Kırp", usable, () -> crop(pg)));
        tools.addView(tool(Icon.Kind.SPARKLE, 0, "Filtre", usable, () -> filter(pg)));
        View del = tool(Icon.Kind.TRASH, 0, confirmDelete == pg ? "Silmeyi onayla" : "Sil", true, () -> delete(pg));
        if (confirmDelete == pg) del.setBackground(Ui.rounded(c, p.dangerSoft, 12));
        tools.addView(del);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -2, 1);
        rl.setMarginStart(c.dp(12));
        top.addView(right, rl);
        card.addView(top);
        card.addView(tools, Ui.lp(-1, -2, 0, c.dp(10), 0, 0));
        return card;
    }

    private View tool(Icon.Kind k, float rotation, String desc, boolean enabled, Runnable action) {
        FrameLayout f = new FrameLayout(c.a);
        Ui.surface(c, f, c.p.surface2, 12, false);
        ImageView iv = Ui.icon(c, k, "Silmeyi onayla".equals(desc) ? c.p.danger : c.p.ink, 20);
        iv.setRotation(rotation);
        f.addView(iv, new FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER));
        f.setContentDescription(desc);
        f.setEnabled(enabled);
        f.setAlpha(enabled ? 1f : 0.35f);
        if (enabled) {
            f.setClickable(true);
            f.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK); action.run(); });
            Ui.press(f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, c.dp(44), 1);
        lp.setMarginEnd(c.dp(6));
        f.setLayoutParams(lp);
        return f;
    }

    private void delete(ScanSession.Page pg) {
        if (confirmDelete != pg) {
            confirmDelete = pg;
            refresh();
            c.ui.postDelayed(() -> { if (confirmDelete == pg) { confirmDelete = null; if (!s.destroyed()) refresh(); } }, 3000);
            return;
        }
        confirmDelete = null;
        s.remove(pg);
    }

    private void crop(ScanSession.Page pg) { if (pg.ready && !pg.broken) c.nav.push(new CropScreen(s, pg)); }
    private void filter(ScanSession.Page pg) { if (pg.ready && !pg.broken) c.nav.push(new FilterScreen(s, pg)); }

    @Override public void onDestroy() { if (s.onChange != null && !s.destroyed()) s.onChange = null; }
}
