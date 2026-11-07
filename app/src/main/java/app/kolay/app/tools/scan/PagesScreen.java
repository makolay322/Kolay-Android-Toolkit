package app.kolay.app.tools.scan;

import android.graphics.Outline;
import android.view.*;
import android.widget.*;
import app.kolay.app.tools.ocr.ProgressScreen;
import app.kolay.app.ui.*;
import app.kolay.app.doc.Imaging;
import java.util.ArrayList;
import java.util.List;

/**
 * The pages as a compact grid: long-press and drag to reorder; rotate, crop and delete on each page; tap a page for its
 * filter; one button sets the filter of all pages. Then save, read as text or as a medical report. Back and "Sayfa ekle"
 * return to the source below (camera, scanner or the documents screen) with the pages kept.
 */
public final class PagesScreen extends Screen {
    private static final int COLS = 3;

    private final ScanSession s;
    private Header header;
    private LinearLayout list, chooser;
    private FrameLayout body;
    private ScrollView scroll;
    private HorizontalScrollView chooserScroll;
    private TextView save, text, medical, filterAll;
    private ScanSession.Page confirmDelete;
    private boolean readText;
    private final List<View> tiles = new ArrayList<>();   // index = page index at the last refresh
    private ScanSession.Page dragging;
    private View dropTarget;
    private float dragX, dragY;
    private int edgeDir;
    private final Runnable edgeTick = new Runnable() {
        @Override public void run() {
            if (edgeDir == 0 || dragging == null) return;
            scroll.scrollBy(0, edgeDir * c.dp(10));
            hover(dragX, dragY);
            c.ui.postDelayed(this, 16);
        }
    };

    public PagesScreen(ScanSession s) { this.s = s; }

    /** {@code readText}: go straight on to "Metne çevir" once shown (pictures shared from another app). */
    PagesScreen(ScanSession s, boolean readText) { this.s = s; this.readText = readText; }

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "Sayfalar", "", () -> c.nav.pop());
        page.addView(header.view);

        filterAll = Ui.button(c, "", Ui.Style.TONAL, Icon.Kind.SPARKLE);
        filterAll.setTextSize(14);
        filterAll.setSingleLine(true);
        filterAll.setEllipsize(android.text.TextUtils.TruncateAt.END);
        filterAll.setPadding(c.dp(14), 0, c.dp(14), 0);
        filterAll.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            chooserScroll.setVisibility(chooserScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        page.addView(filterAll, Ui.lp(-1, c.dp(44), c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(8)));
        chooserScroll = new HorizontalScrollView(c.a);
        chooserScroll.setHorizontalScrollBarEnabled(false);
        chooserScroll.setClipToPadding(false);
        chooserScroll.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        chooserScroll.setVisibility(View.GONE);
        chooser = new LinearLayout(c.a);
        chooserScroll.addView(chooser);
        page.addView(chooserScroll, Ui.lp(-1, -2, 0, 0, 0, c.dp(10)));

        body = new FrameLayout(c.a);
        scroll = new ScrollView(c.a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnDragListener(this::onDrag);
        list = new LinearLayout(c.a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        scroll.addView(list);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bb = Ui.bottomBar(c);
        TextView more = action("Sayfa ekle", Ui.Style.TONAL, Icon.Kind.PLUS);
        more.setOnClickListener(v -> c.nav.pop());
        save = action("Kaydet", Ui.Style.PRIMARY, Icon.Kind.DOWNLOAD);
        save.setOnClickListener(v -> { if (usable(v)) c.nav.push(new ExportScreen(s)); });
        text = action("Metne çevir", Ui.Style.TONAL, Icon.Kind.DOC);
        text.setOnClickListener(v -> { if (usable(v)) ProgressScreen.open(c, s, true, false); });
        medical = action("Tıbbi rapor", Ui.Style.TONAL, Icon.Kind.SPARKLE);
        medical.setOnClickListener(v -> { if (usable(v)) ProgressScreen.open(c, s, false, false); });
        LinearLayout row = new LinearLayout(c.a);
        TextView[] all = {more, text, medical, save};
        for (int i = 0; i < all.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) lp.setMarginStart(c.dp(6));
            row.addView(all[i], lp);
        }
        bb.addView(row, new LinearLayout.LayoutParams(-1, -2));
        page.addView(bb, new LinearLayout.LayoutParams(-1, -2));
        s.onChange = this::refresh;
        refresh();
        return page;
    }

    /** Compact bottom action: icon above a short label (two lines at most, so large fonts wrap instead of cutting). */
    private TextView action(String label, Ui.Style st, Icon.Kind k) {
        TextView b = Ui.button(c, label, st, k);
        b.setTextSize(12);
        b.setMaxLines(2);
        b.setMinHeight(c.dp(58));
        b.setPadding(c.dp(2), c.dp(8), c.dp(2), c.dp(8));
        b.setCompoundDrawablesRelative(null, b.getCompoundDrawablesRelative()[0], null, null);
        b.setCompoundDrawablePadding(c.dp(4));
        return b;
    }

    private boolean usable(View v) {
        if (s.pages.isEmpty()) return false;
        if (!s.allReady()) { Ui.toast(c, "Sayfalar hazırlanıyor, bir an bekle"); return false; }
        Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
        return true;
    }

    @Override public void onShown() {
        if (readText) { readText = false; ProgressScreen.open(c, s, true, false); }   // waits for the pages itself
        for (int i = 0; i < list.getChildCount(); i++) Ui.appear(list.getChildAt(i), 40L * Math.min(i, 8));
    }

    @Override public void onReturn() { s.onChange = this::refresh; refresh(); }

    private void refresh() {
        if (dragging != null) return;   // the views stay put while a page is dragged; refreshed when the drag ends
        int n = s.pages.size();
        header.subtitle.setText(n == 0 ? "Sayfa kalmadı" : n + " sayfa · sıralamak için basılı tutup sürükle");
        for (TextView b : new TextView[]{save, text, medical}) { b.setEnabled(n > 0); b.setAlpha(n > 0 ? 1f : 0.45f); }
        filterAll.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (n == 0) chooserScroll.setVisibility(View.GONE);
        Imaging.Filter cur = common();
        String name = "Filtre (tümü): " + (cur != null ? cur.label : "Karışık");
        filterAll.setText(name);
        filterAll.setContentDescription("Tüm sayfaların filtresi: " + (cur != null ? cur.label : "karışık") + ". Değiştirmek için dokun");
        paintChooser(cur);
        int y = scroll.getScrollY();
        list.removeAllViews();
        tiles.clear();
        body.removeAllViews();
        if (n == 0) {
            LinearLayout st = Ui.state(c, Icon.Kind.DOCSCAN, "Sayfa kalmadı", "Geri dönüp yeni sayfa ekleyebilirsin.", "Sayfa ekle", () -> c.nav.pop());
            body.addView(st, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < n; i += COLS) {
            LinearLayout row = new LinearLayout(c.a);
            for (int j = 0; j < COLS; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (j > 0) lp.setMarginStart(c.dp(8));
                View v = i + j < n ? tile(i + j) : new Space(c.a);
                if (i + j < n) tiles.add(v);
                row.addView(v, lp);
            }
            list.addView(row, Ui.lp(-1, -2, 0, i == 0 ? 0 : c.dp(8), 0, 0));
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** The filter all readable pages share, or null when they differ. */
    private Imaging.Filter common() {
        Imaging.Filter f = null;
        for (ScanSession.Page p : s.pages) {
            if (p.broken) continue;
            if (f == null) f = p.filter; else if (f != p.filter) return null;
        }
        return f != null ? f : s.defaultFilter;
    }

    private void paintChooser(Imaging.Filter cur) {
        chooser.removeAllViews();
        Imaging.Filter[] fs = Imaging.Filter.values();
        for (int i = 0; i < fs.length; i++) {
            Imaging.Filter f = fs[i];
            boolean on = f == cur;
            TextView t = Ui.text(c, f.label, 14, on ? c.p.onPrimarySoft : c.p.ink, on ? 700 : 500);
            t.setGravity(Gravity.CENTER);
            t.setPadding(c.dp(14), 0, c.dp(14), 0);
            Ui.surface(c, t, on ? c.p.primarySoft : c.p.surface, 14, !on);
            t.setClickable(true);
            t.setContentDescription(f.label + (on ? ", seçili" : "") + ", tüm sayfalara uygula");
            t.setOnClickListener(v -> {
                Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
                chooserScroll.setVisibility(View.GONE);
                s.filterAll(f);   // refreshes through onChange
            });
            Ui.press(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, c.dp(40));
            if (i > 0) lp.setMarginStart(c.dp(8));
            chooser.addView(t, lp);
        }
    }

    private View tile(int i) {
        Palette p = c.p;
        ScanSession.Page pg = s.pages.get(i);
        boolean usable = pg.ready && !pg.broken;
        LinearLayout card = new LinearLayout(c.a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(6));
        Ui.surface(c, card, p.surface, 16, true);
        FrameLayout th = new FrameLayout(c.a) {   // 3:4, like a page
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w) * 4 / 3, MeasureSpec.EXACTLY));
            }
        };
        th.setBackground(Ui.rounded(c, p.surface2, 10));
        final float r = c.dp(10);
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
            th.addView(new Arc(c.a, p.primarySoft, p.primary, c.dp(3)), new FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.CENTER));
        }
        th.setClickable(true);
        th.setContentDescription("Sayfa " + (i + 1) + (usable ? ", filtre: " + pg.filter.label + ". Filtre için dokun, taşımak için basılı tutup sürükle" : ""));
        th.setOnClickListener(v -> { if (pg.ready && !pg.broken) c.nav.push(new FilterScreen(s, pg)); });
        th.setOnLongClickListener(v -> startDrag(card, pg));
        card.addView(th, new LinearLayout.LayoutParams(-1, -2));

        String st = pg.broken ? "Okunamadı" : !pg.ready ? "Hazırlanıyor…" : pg.filter.label;
        TextView cap = Ui.text(c, (i + 1) + " · " + st, 12, pg.broken ? p.danger : p.muted, 500);
        cap.setSingleLine(true);
        cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(cap, Ui.lp(-1, -2, c.dp(2), c.dp(6), c.dp(2), c.dp(6)));

        LinearLayout tools = new LinearLayout(c.a);
        tools.addView(tool(Icon.Kind.REFRESH, "Döndür", usable, false, () -> { pg.rotation = (pg.rotation + 90) % 360; s.edited(pg); refresh(); }));
        tools.addView(tool(Icon.Kind.CROP, "Kırp", usable, true, () -> { if (pg.ready && !pg.broken) c.nav.push(new CropScreen(s, pg)); }));
        View del = tool(Icon.Kind.TRASH, confirmDelete == pg ? "Silmeyi onayla" : "Sil", true, true, () -> delete(pg));
        if (confirmDelete == pg) del.setBackground(Ui.rounded(c, p.dangerSoft, 10));
        tools.addView(del);
        card.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private View tool(Icon.Kind k, String desc, boolean enabled, boolean gap, Runnable action) {
        FrameLayout f = new FrameLayout(c.a);
        Ui.surface(c, f, c.p.surface2, 10, false);
        ImageView iv = Ui.icon(c, k, "Silmeyi onayla".equals(desc) ? c.p.danger : c.p.ink, 18);
        f.addView(iv, new FrameLayout.LayoutParams(c.dp(18), c.dp(18), Gravity.CENTER));
        f.setContentDescription(desc);
        f.setEnabled(enabled);
        f.setAlpha(enabled ? 1f : 0.35f);
        if (enabled) {
            f.setClickable(true);
            f.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK); action.run(); });
            Ui.press(f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, c.dp(36), 1);
        if (gap) lp.setMarginStart(c.dp(4));
        f.setLayoutParams(lp);
        return f;
    }

    // ---- drag to reorder ----------------------------------------------------------------------

    private boolean startDrag(View card, ScanSession.Page pg) {
        if (dragging != null || s.pages.size() < 2 || !s.pages.contains(pg)) return false;
        if (!card.startDragAndDrop(null, new View.DragShadowBuilder(card), pg, 0)) return false;
        Ui.haptic(card, HapticFeedbackConstants.LONG_PRESS);
        confirmDelete = null;
        chooserScroll.setVisibility(View.GONE);
        dragging = pg;
        card.setAlpha(0.35f);
        return true;
    }

    /** Drag events reach the scroll view (the tiles take none); x / y are relative to its visible area. */
    private boolean onDrag(View v, DragEvent e) {
        if (!(e.getLocalState() instanceof ScanSession.Page)) return false;   // not one of our pages
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION:
                dragX = e.getX(); dragY = e.getY();
                hover(dragX, dragY);
                int zone = c.dp(64), h = scroll.getHeight();
                int d = dragY < zone ? -1 : dragY > h - zone ? 1 : 0;
                if (d != edgeDir) { edgeDir = d; c.ui.removeCallbacks(edgeTick); if (d != 0) c.ui.post(edgeTick); }
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                edgeDir = 0;
                break;
            case DragEvent.ACTION_DROP:
                int from = s.pages.indexOf(dragging), to = tiles.indexOf(dropTarget);
                if (from >= 0 && to >= 0 && from != to && to < s.pages.size()) s.move(from, to);   // refresh waits for ENDED
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                if (dragging != null) {
                    dragging = null; dropTarget = null; edgeDir = 0;
                    c.ui.removeCallbacks(edgeTick);
                    if (!s.destroyed()) refresh();
                }
                break;
            default:
                break;
        }
        return true;
    }

    /** Marks the tile under (x, y) of the scroll view as the drop place. */
    private void hover(float x, float y) {
        android.graphics.Rect rc = new android.graphics.Rect();
        int cy = Math.round(y) + scroll.getScrollY();
        View hit = null;
        for (View t : tiles) {
            t.getDrawingRect(rc);
            scroll.offsetDescendantRectToMyCoords(t, rc);
            if (rc.contains(Math.round(x), cy)) { hit = t; break; }
        }
        if (hit == null) return;   // in a gap: keep the last place
        int self = s.pages.indexOf(dragging);
        if (self >= 0 && self < tiles.size() && tiles.get(self) == hit) hit = null;
        if (hit == dropTarget) return;
        if (dropTarget != null) dropTarget.setForeground(null);
        dropTarget = hit;
        if (hit != null) {
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setCornerRadius(c.dp(16));
            ring.setStroke(c.dp(3), c.p.primary);
            hit.setForeground(ring);
            Ui.haptic(hit, HapticFeedbackConstants.CLOCK_TICK);
        }
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


    @Override public void onDestroy() {
        c.ui.removeCallbacks(edgeTick);
        edgeDir = 0;
        if (s.onChange != null && !s.destroyed()) s.onChange = null;
    }
}
