package app.kolay.app.tools.scan;

import android.graphics.Bitmap;
import android.graphics.Outline;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.Imaging;
import app.kolay.app.ui.*;

/** Filter choice for one page with a live preview; apply to this page or to all pages (also the default for new ones). */
final class FilterScreen extends Screen {
    private static final long PREVIEW_PX = 1_000_000L;

    private final ScanSession s;
    private final ScanSession.Page pg;
    private Imaging.Filter chosen;
    private Bitmap base, shown;            // straightened page without filter; the filtered preview on screen
    private final Bitmap[] smalls = new Bitmap[Imaging.Filter.values().length];
    private ImageView preview;
    private Arc loader;
    private LinearLayout options;
    private int gen;
    private boolean destroyed;

    FilterScreen(ScanSession s, ScanSession.Page pg) { this.s = s; this.pg = pg; chosen = pg.filter; }

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        int idx = s.pages.indexOf(pg) + 1;
        page.addView(new Header(c, "Filtre", "Sayfa " + idx, () -> c.nav.pop()).view);

        FrameLayout box = new FrameLayout(c.a);
        Ui.surface(c, box, c.p.surface2, 22, false);
        box.setClipToOutline(true);
        loader = new Arc(c.a, c.p.primarySoft, c.p.primary, c.dp(4));
        box.addView(loader, new FrameLayout.LayoutParams(c.dp(40), c.dp(40), Gravity.CENTER));
        preview = new ImageView(c.a);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8));
        box.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        page.addView(box, Ui.lp(-1, 0, c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(12)));
        ((LinearLayout.LayoutParams) box.getLayoutParams()).weight = 1;

        HorizontalScrollView hs = new HorizontalScrollView(c.a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        options = new LinearLayout(c.a);
        hs.addView(options);
        page.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        paintOptions();

        LinearLayout bb = Ui.bottomBar(c);
        TextView all = Ui.button(c, "Tüm sayfalara", Ui.Style.TONAL, Icon.Kind.LAYERS);
        all.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
            s.filterAll(chosen);
            c.nav.pop();
        });
        TextView one = Ui.button(c, "Uygula", Ui.Style.PRIMARY, Icon.Kind.CHECK);
        one.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
            if (s.pages.contains(pg) && pg.filter != chosen) { pg.filter = chosen; s.edited(pg); }
            c.nav.pop();
        });
        bb.addView(Ui.pair(c, all, one, Ui.H_BAR));
        page.addView(bb, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private void paintOptions() {
        options.removeAllViews();
        Imaging.Filter[] fs = Imaging.Filter.values();
        for (int i = 0; i < fs.length; i++) {
            Imaging.Filter f = fs[i];
            boolean on = f == chosen;
            LinearLayout item = new LinearLayout(c.a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(8));
            Ui.surface(c, item, on ? c.p.primarySoft : c.p.surface, 16, !on);
            FrameLayout th = new FrameLayout(c.a);
            th.setBackground(Ui.rounded(c, c.p.surface2, 10));
            final float r = c.dp(10);
            th.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            th.setClipToOutline(true);
            if (smalls[i] != null) {
                ImageView iv = new ImageView(c.a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(smalls[i]);
                th.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            }
            item.addView(th, new LinearLayout.LayoutParams(c.dp(64), c.dp(84)));
            TextView t = Ui.text(c, f.label, 12, on ? c.p.onPrimarySoft : c.p.ink, on ? 700 : 500);
            t.setGravity(Gravity.CENTER);
            item.addView(t, Ui.lp(c.dp(76), -2, 0, c.dp(6), 0, 0));
            item.setClickable(true);
            item.setContentDescription(f.label + (on ? ", seçili" : ""));
            item.setOnClickListener(v -> {
                if (f == chosen) return;
                Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
                chosen = f;
                paintOptions();
                showChosen();
            });
            Ui.press(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.setMarginStart(c.dp(8));
            options.addView(item, lp);
        }
    }

    @Override public void onShown() {
        final java.io.File file = pg.file; final float[] q = pg.quad.clone(); final int rot = pg.rotation;
        c.io.execute(() -> {
            Bitmap b = null;
            Bitmap[] sm = new Bitmap[smalls.length];
            try {
                b = Imaging.render(file, q, rot, Imaging.Filter.ORIGINAL, PREVIEW_PX);
                float k = 260f / Math.max(b.getWidth(), b.getHeight());
                Imaging.Filter[] fs = Imaging.Filter.values();
                for (int i = 0; i < fs.length; i++) {
                    Bitmap t = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
                    if (t == b) t = b.copy(Bitmap.Config.ARGB_8888, true);   // same size: never filter the base itself
                    else if (!t.isMutable()) { Bitmap m = t.copy(Bitmap.Config.ARGB_8888, true); t.recycle(); t = m; }
                    Imaging.apply(t, fs[i]);
                    sm[i] = t;
                }
            } catch (Exception | OutOfMemoryError e) {
                if (b != null) { b.recycle(); b = null; }
            }
            final Bitmap out = b;
            c.ui.post(() -> {
                if (destroyed || out == null) {
                    if (out != null) out.recycle();
                    for (Bitmap t : sm) if (t != null) t.recycle();
                    if (!destroyed) { Ui.toast(c, "Önizleme hazırlanamadı"); c.nav.pop(); }
                    return;
                }
                base = out;
                System.arraycopy(sm, 0, smalls, 0, sm.length);
                paintOptions();
                showChosen();
            });
        });
    }

    private void showChosen() {
        if (base == null) return;
        final int my = ++gen;
        final Imaging.Filter f = chosen;
        final Bitmap src = base;
        loader.setVisibility(View.VISIBLE);
        c.io.execute(() -> {
            Bitmap b = null;
            try {
                synchronized (src) {
                    if (src.isRecycled()) return;
                    b = src.copy(Bitmap.Config.ARGB_8888, true);
                }
                Imaging.apply(b, f);
            } catch (Exception | OutOfMemoryError e) { if (b != null) { b.recycle(); b = null; } }
            final Bitmap out = b;
            c.ui.post(() -> {
                if (destroyed || my != gen) { if (out != null) out.recycle(); return; }
                loader.setVisibility(View.GONE);
                if (out == null) return;
                Bitmap old = shown;
                shown = out;
                preview.setImageBitmap(out);
                if (old != null) old.recycle();
            });
        });
    }

    @Override public void onDestroy() {
        destroyed = true; gen++;
        if (preview != null) preview.setImageDrawable(null);
        options.removeAllViews();
        if (shown != null) { shown.recycle(); shown = null; }
        for (int i = 0; i < smalls.length; i++) if (smalls[i] != null) { smalls[i].recycle(); smalls[i] = null; }
        Bitmap b = base; base = null;
        if (b != null) synchronized (b) { b.recycle(); }
    }
}
