package app.kolay.app.viewer;

import android.graphics.*;
import android.text.*;
import android.text.style.*;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import app.kolay.app.ui.Ctx;
import java.util.HashMap;
import java.util.HashSet;

/** Slides in a zoomable vertical list; each slide is parsed when it scrolls into view and drawn on a canvas. */
final class SlidePane extends FrameLayout {
    private static final float EMU_PER_PT = 12700f;

    private final Ctx c;
    private final Pptx deck;
    private final Pics pics;
    private final LruCache<Integer, Pptx.Slide> slides = new LruCache<>(40);
    private final HashSet<Integer> loading = new HashSet<>(), broken = new HashSet<>();
    private final ListView list;
    private final TextView pill;
    private volatile boolean closed;

    SlidePane(Ctx c, Pptx deck, Zip zip) {
        super(c.a);
        this.c = c; this.deck = deck;
        pics = new Pics(c, zip);
        list = new ListView(c.a);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(c.dp(12));
        list.setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(64));
        list.setClipToPadding(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return deck.slides.size(); }
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                SlideView sv = v instanceof SlideView ? (SlideView) v : new SlideView();
                sv.bind(i);
                return sv;
            }
        });
        ZoomLayout zoom = new ZoomLayout(c.a);
        zoom.addView(list, new FrameLayout.LayoutParams(-1, -1));
        addView(zoom, new FrameLayout.LayoutParams(-1, -1));
        pill = ViewerScreen.pill(c, () -> ViewerScreen.askNumber(c, "Slayta git", deck.slides.size(), n -> list.setSelection(n - 1)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = c.dp(14);
        addView(pill, lp);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int f, int n, int total) {
                int p = f;
                View ch = list.getChildAt(0);
                if (ch != null && ch.getBottom() < list.getHeight() / 3 && p + 1 < total) p++;
                pill.setText("Slayt " + Math.min(total, p + 1) + " / " + total);
            }
        });
        pill.setText("Slayt 1 / " + deck.slides.size());
    }

    void close() { closed = true; pics.close(); }

    private Pptx.Slide slide(int i) {
        Pptx.Slide s = slides.get(i);
        if (s != null || broken.contains(i) || !loading.add(i)) return s;
        c.io.execute(() -> {
            Pptx.Slide out = null;
            try { if (!closed) out = deck.slide(i); } catch (Exception | OutOfMemoryError ignored) {}
            Pptx.Slide done = out;
            c.ui.post(() -> {
                loading.remove(i);
                if (closed) return;
                if (done == null) broken.add(i); else slides.put(i, done);
                for (int k = 0; k < list.getChildCount(); k++) list.getChildAt(k).invalidate();
            });
        });
        return null;
    }

    private final class SlideView extends View {
        private int index = -1, laidWidth;
        private Pptx.Slide drawn;
        private final HashMap<Pptx.Shape, StaticLayout> layouts = new HashMap<>();
        private final Paint fill = new Paint(), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Rect dst = new Rect();

        SlideView() { super(c.a); setElevation(c.dp(1)); }

        void bind(int i) { if (i != index) { index = i; drawn = null; layouts.clear(); invalidate(); } }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, (int) (w * (deck.cy / (double) deck.cx)));
        }

        @Override protected void onDraw(Canvas cv) {
            Pptx.Slide s = slide(index);
            if (s == null) {
                cv.drawColor(c.p.surface2);
                tp.setColor(c.p.muted); tp.setTextSize(c.dp(14)); tp.setTextAlign(Paint.Align.CENTER);
                cv.drawText(broken.contains(index) ? "Bu slayt okunamadı" : "…", getWidth() / 2f, getHeight() / 2f, tp);
                tp.setTextAlign(Paint.Align.LEFT);
                return;
            }
            if (s != drawn || laidWidth != getWidth()) { drawn = s; laidWidth = getWidth(); layouts.clear(); }
            float k = getWidth() / (float) deck.cx;
            cv.drawColor(s.bg);
            boolean darkBg = DocPane.luminance(s.bg) < 0.45f;
            cv.save();
            cv.clipRect(0, 0, getWidth(), getHeight());
            for (Pptx.Shape sh : s.shapes) {
                r.set(sh.x * k, sh.y * k, (sh.x + sh.w) * k, (sh.y + sh.h) * k);
                if (sh.fill != 0) { fill.setColor(sh.fill); cv.drawRect(r, fill); }
                if (sh.image != null) {
                    Bitmap b = pics.get(sh.image, (int) r.width(), this::invalidate);
                    if (b != null) { dst.set((int) r.left, (int) r.top, (int) r.right, (int) r.bottom); cv.drawBitmap(b, null, dst, bmp); }
                    else if (!pics.failed(sh.image)) { fill.setColor(0x14000000); cv.drawRect(r, fill); }
                }
                if (!sh.paras.isEmpty()) {
                    StaticLayout l = layouts.get(sh);
                    if (l == null) { l = layout(sh, k, sh.fill != 0 ? DocPane.luminance(sh.fill) < 0.45f : darkBg); layouts.put(sh, l); }
                    float inset = 91440 * k, top = 45720 * k;
                    float y = r.top + top, free = r.height() - 2 * top - l.getHeight();
                    if (sh.anchor == 1) y += Math.max(0, free / 2); else if (sh.anchor == 2) y += Math.max(0, free);
                    cv.save();
                    cv.translate(r.left + inset, y);
                    l.draw(cv);
                    cv.restore();
                }
            }
            cv.restore();
        }

        private StaticLayout layout(Pptx.Shape sh, float k, boolean onDark) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int def = onDark ? Color.WHITE : 0xFF1A1A1A;
            for (Pptx.TPara p : sh.paras) {
                if (sb.length() > 0) sb.append('\n');
                int start = sb.length();
                if (p.bullet != null) sb.append(p.bullet).append(' ');
                float firstPx = 0;
                for (Docx.Run run : p.runs) {
                    int s = sb.length();
                    sb.append(run.text);
                    int e = sb.length();
                    float px = Math.max(1f, run.halfPts / 2f * EMU_PER_PT * k * sh.fontScale);
                    if (firstPx == 0) firstPx = px;
                    if (s == e) continue;
                    sb.setSpan(new AbsoluteSizeSpan(Math.round(px)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    int style = (run.b ? Typeface.BOLD : 0) | (run.i ? Typeface.ITALIC : 0);
                    if (style != 0) sb.setSpan(new StyleSpan(style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (run.u) sb.setSpan(new UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (run.s) sb.setSpan(new StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(run.color != 0 ? run.color : def), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (p.bullet != null && firstPx > 0) {
                    sb.setSpan(new AbsoluteSizeSpan(Math.round(firstPx)), start, start + p.bullet.length() + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(def), start, start + p.bullet.length() + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                int indent = Math.round(p.level * 457200 * k * 0.6f);
                if (indent > 0) sb.setSpan(new LeadingMarginSpan.Standard(indent), start, sb.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                Layout.Alignment a = p.align == 1 ? Layout.Alignment.ALIGN_CENTER : p.align == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
                if (a != Layout.Alignment.ALIGN_NORMAL) sb.setSpan(new AlignmentSpan.Standard(a), start, sb.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            }
            tp.setTextSize(18 * EMU_PER_PT * k);
            tp.setColor(def);
            int width = Math.max(1, Math.round(sh.w * k - 2 * 91440 * k));
            return StaticLayout.Builder.obtain(sb, 0, sb.length(), tp, width).setIncludePad(false).build();
        }
    }
}
