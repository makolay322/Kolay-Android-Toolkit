package app.kolay.app.viewer;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Ui;
import java.util.ArrayList;
import java.util.List;

/** Word-like content (paragraph / picture / table blocks) in a recycling list that grows while the parser streams. */
final class DocPane extends FrameLayout {
    private static final float BODY = 15f;

    private final Ctx c;
    private final Pics pics;
    private final boolean plain;
    private final ArrayList<Docx.Block> blocks = new ArrayList<>();
    private final BaseAdapter adapter;
    private final ListView list;
    private int base = 22;

    /** {@code zip} may be null (no pictures); {@code plain} = text file (tighter spacing, no styles). */
    DocPane(Ctx c, Zip zip, boolean plain) {
        super(c.a);
        this.c = c; this.plain = plain;
        pics = zip == null ? null : new Pics(c, zip);
        list = new ListView(c.a);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setPadding(c.dp(Ui.GUTTER + 4), c.dp(8), c.dp(Ui.GUTTER + 4), c.dp(40));
        list.setClipToPadding(false);
        list.setFastScrollEnabled(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return blocks.size(); }
            @Override public Object getItem(int i) { return blocks.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 3; }
            @Override public int getItemViewType(int i) {
                Docx.Block b = blocks.get(i);
                return b instanceof Docx.Para ? 0 : b instanceof Docx.Image ? 1 : 2;
            }
            @Override public View getView(int i, View v, ViewGroup parent) {
                Docx.Block b = blocks.get(i);
                if (b instanceof Docx.Para) return para((Docx.Para) b, (TextView) v);
                if (b instanceof Docx.Image) return image((Docx.Image) b, (ImageView) v);
                return table((Docx.Table) b);
            }
        };
        list.setAdapter(adapter);
        addView(list, new FrameLayout.LayoutParams(-1, -1));
    }

    void setBase(int halfPts) { if (halfPts > 0) base = halfPts; }

    void add(List<Docx.Block> more) { blocks.addAll(more); adapter.notifyDataSetChanged(); }

    int size() { return blocks.size(); }

    void close() { if (pics != null) pics.close(); }

    // ---- paragraphs ------------------------------------------------------------------------

    private TextView para(Docx.Para p, TextView t) {
        if (t == null) {
            t = new TextView(c.a);
            t.setTextColor(c.p.ink);
            t.setLineSpacing(0, plain ? 1.1f : 1.25f);
            t.setIncludeFontPadding(true);
        }
        float size = BODY;
        boolean bold = false;
        int top = plain ? 0 : c.dp(4), bottom = plain ? 0 : c.dp(6);
        switch (p.heading) {
            case 0: size = 26; bold = true; top = c.dp(10); bottom = c.dp(10); break;
            case 1: size = 22; bold = true; top = c.dp(16); break;
            case 2: size = 19; bold = true; top = c.dp(12); break;
            case 3: size = 17; bold = true; top = c.dp(10); break;
            case 4: case 5: case 6: size = 16; bold = true; top = c.dp(8); break;
            default: break;
        }
        t.setTextSize(size);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setPadding(0, top, 0, bottom);
        t.setGravity(p.align == 1 ? Gravity.CENTER_HORIZONTAL : p.align == 2 ? Gravity.END : Gravity.START);
        if (!(p.cache instanceof CharSequence)) p.cache = spans(p, p.heading >= 0);
        t.setText((CharSequence) p.cache);
        return t;
    }

    private CharSequence spans(Docx.Para p, boolean heading) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (p.label != null) sb.append(p.label).append(' ');
        int labelEnd = sb.length();
        boolean colors = !c.p.dark;
        for (Docx.Run r : p.runs) {
            int s = sb.length();
            sb.append(r.text);
            int e = sb.length();
            if (s == e) continue;
            int style = (r.b ? Typeface.BOLD : 0) | (r.i ? Typeface.ITALIC : 0);
            if (style != 0) sb.setSpan(new StyleSpan(style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.u) sb.setSpan(new UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.s) sb.setSpan(new StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!heading && r.halfPts > 0 && r.halfPts != base) {
                float rel = Math.max(0.7f, Math.min(2.2f, r.halfPts / (float) base));
                sb.setSpan(new RelativeSizeSpan(rel), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (colors && r.color != 0 && luminance(r.color) < 0.85f)
                sb.setSpan(new ForegroundColorSpan(r.color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (p.label != null) {
            int indent = c.dp(18) * p.level;
            sb.setSpan(new LeadingMarginSpan.Standard(indent, indent + c.dp(22)), 0, sb.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            if (labelEnd > 0) sb.setSpan(new ForegroundColorSpan(c.p.muted), 0, labelEnd - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    static float luminance(int argb) {
        return (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF)) / 255f;
    }

    // ---- pictures --------------------------------------------------------------------------

    private ImageView image(Docx.Image im, ImageView v) {
        if (v == null) {
            v = new ImageView(c.a);
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.setPadding(0, c.dp(6), 0, c.dp(6));
        }
        int lw = list.getWidth() > 0 ? list.getWidth() : c.a.getResources().getDisplayMetrics().widthPixels;
        int avail = Math.max(1, lw - list.getPaddingLeft() - list.getPaddingRight());
        int w = avail, h = avail / 2;
        if (im.cx > 0 && im.cy > 0) {
            w = Math.min(avail, (int) (im.cx / 9525f * c.a.getResources().getDisplayMetrics().density));   // EMU → px at 96 dpi
            h = (int) (w * (im.cy / (double) im.cx));
        }
        boolean failed = pics.failed(im.part);
        v.setLayoutParams(new AbsListView.LayoutParams(-1, failed ? c.dp(52) : Math.min(h, c.dp(1600)) + c.dp(12)));
        v.setTag(im.part);
        ImageView view = v;
        Bitmap b = pics.get(im.part, w, () -> { if (im.part.equals(view.getTag())) adapter.notifyDataSetChanged(); });
        if (b != null) { v.setImageBitmap(b); v.setBackground(null); }
        else {
            v.setImageDrawable(failed ? new Icon(Icon.Kind.IMAGE, c.p.muted) : null);
            v.setBackground(Ui.rounded(c, c.p.surface2, 12));
        }
        return v;
    }

    // ---- tables ----------------------------------------------------------------------------

    private View table(Docx.Table t) {
        HorizontalScrollView hs = new HorizontalScrollView(c.a);
        hs.setPadding(0, c.dp(6), 0, c.dp(10));
        TableLayout tl = new TableLayout(c.a);
        tl.setBackgroundColor(c.p.line);
        tl.setPadding(1, 1, 1, 1);
        int cells = 0;
        for (ArrayList<ArrayList<Docx.Para>> row : t.rows) {
            TableRow tr = new TableRow(c.a);
            for (ArrayList<Docx.Para> cell : row) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                for (Docx.Para p : cell) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(spans(p, false));
                }
                TextView tv = Ui.text(c, sb, 14, c.p.ink, 400);
                tv.setMaxWidth(c.dp(260));
                tv.setPadding(c.dp(8), c.dp(6), c.dp(8), c.dp(6));
                tv.setBackgroundColor(c.p.surface);
                TableRow.LayoutParams lp = new TableRow.LayoutParams(-2, -1);
                lp.setMargins(0, 0, 1, 1);
                tr.addView(tv, lp);
                if (++cells > 4000) break;
            }
            tl.addView(tr);
            if (cells > 4000) break;
        }
        hs.addView(tl);
        return hs;
    }
}
