package app.kolay.app.viewer;

import android.graphics.*;
import android.view.*;
import android.widget.OverScroller;
import app.kolay.app.ui.Ctx;

/**
 * Spreadsheet grid drawn directly on a canvas: frozen column letters and row numbers, only visible cells are drawn,
 * two-axis scrolling with fling, pinch zoom, tap selects a cell. Rows may keep arriving while it is shown.
 */
final class CellGrid extends View {
    interface OnCell { void picked(int row, int col); }

    private final Ctx c;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), head = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(), fill = new Paint();
    private final OverScroller scroller;
    private final GestureDetector gd;
    private final ScaleGestureDetector sg;
    private Sheet sheet;
    private float[] colX = {0};   // left edge of each column (unscaled px), colX[cols] = total width
    private float scale = 1f, sx, sy;
    private int selR = -1, selC = -1;
    private final float rowH, baseText, pad;
    OnCell onCell;

    CellGrid(Ctx c) {
        super(c.a);
        this.c = c;
        rowH = c.dp(30); baseText = c.dp(13.5f); pad = c.dp(6);
        line.setColor(c.p.line); line.setStrokeWidth(Math.max(1, c.dp(0.6f)));
        head.setColor(c.p.muted); head.setTextAlign(Paint.Align.CENTER);
        text.setColor(c.p.ink);
        scroller = new OverScroller(c.a);
        gd = new GestureDetector(c.a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { scroller.forceFinished(true); return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { scrollTo2(sx + dx, sy + dy); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxX(), 0, (int) maxY());
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) { tap(e.getX(), e.getY()); return true; }
        });
        sg = new ScaleGestureDetector(c.a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float ns = Math.max(0.5f, Math.min(3f, scale * d.getScaleFactor())), f = ns / scale;
                float fx = d.getFocusX() - hw(), fy = d.getFocusY() - hh();
                sx = (sx + fx) * f - fx; sy = (sy + fy) * f - fy;
                scale = ns;
                scrollTo2(sx, sy);
                return true;
            }
        });
    }

    void setSheet(Sheet s) {
        sheet = s; sx = sy = 0; selR = selC = -1; scroller.forceFinished(true);
        measureColumns();
        invalidate();
    }

    /** Width per column from the file's own widths, else from the longest text in the first rows (clamped). */
    void measureColumns() {
        Sheet s = sheet;
        if (s == null) return;
        int cols = Math.max(s.cols, 1);
        float[] w = new float[cols];
        float[] fw = s.widths;
        text.setTextSize(baseText);
        int rows = Math.min(s.rowCount(), 300);
        for (int col = 0; col < cols; col++) {
            if (col < fw.length && fw[col] > 0) { w[col] = Math.max(c.dp(24), Math.min(c.dp(400), c.dp(fw[col] * 6.2f + 6))); continue; }
            float m = c.dp(56);
            for (int r = 0; r < rows; r++) {
                String v = s.get(r, col);
                if (v != null) m = Math.max(m, text.measureText(v, 0, Math.min(v.length(), 60)) + 2 * pad);
            }
            w[col] = Math.min(m, c.dp(260));
        }
        colX = new float[cols + 1];
        for (int i = 0; i < cols; i++) colX[i + 1] = colX[i] + w[i];
    }

    private float hw() { int rows = sheet == null ? 1 : Math.max(1, sheet.rowCount()); return (c.dp(18) + String.valueOf(rows).length() * c.dp(8)) * scale; }
    private float hh() { return rowH * scale; }
    private float maxX() { return Math.max(0, colX[colX.length - 1] * scale + hw() - getWidth()); }
    private float maxY() { return Math.max(0, (sheet == null ? 0 : sheet.rowCount()) * rowH * scale + hh() - getHeight() + c.dp(40)); }

    private void scrollTo2(float x, float y) {
        sx = Math.max(0, Math.min(maxX(), x));
        sy = Math.max(0, Math.min(maxY(), y));
        invalidate();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) { sx = scroller.getCurrX(); sy = scroller.getCurrY(); scrollTo2(sx, sy); postInvalidateOnAnimation(); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sg.onTouchEvent(e);
        if (!sg.isInProgress()) gd.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private void tap(float x, float y) {
        if (sheet == null || x < hw() || y < hh()) return;
        int r = (int) ((y - hh() + sy) / (rowH * scale));
        int col = colAt((x - hw() + sx) / scale);
        if (col < 0 || r >= sheet.rowCount()) return;
        selR = r; selC = col;
        invalidate();
        if (onCell != null) onCell.picked(r, col);
    }

    private int colAt(float ux) {
        int lo = 0, hi = colX.length - 2;
        if (hi < 0 || ux < 0 || ux >= colX[colX.length - 1]) return -1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (colX[mid] <= ux) lo = mid; else hi = mid - 1; }
        return lo;
    }

    @Override protected void onDraw(Canvas cv) {
        cv.drawColor(c.p.surface);
        if (sheet == null) return;
        float hw = hw(), hh = hh(), rh = rowH * scale, w = getWidth(), h = getHeight();
        text.setTextSize(baseText * scale); head.setTextSize(baseText * 0.85f * scale);
        int rows = sheet.rowCount(), cols = colX.length - 1;
        int r0 = (int) (sy / rh), r1 = Math.min(rows - 1, (int) ((sy + h - hh) / rh));
        int c0 = Math.max(0, colAt(sx / scale)), c1 = colAt((sx + w - hw) / scale);
        if (c1 < 0) c1 = cols - 1;
        float base = (rh - text.ascent() - text.descent()) / 2;
        cv.save();
        cv.clipRect(hw, hh, w, h);
        if (selR >= 0) {
            fill.setColor(c.p.primarySoft);
            cv.drawRect(hw + colX[selC] * scale - sx, hh + selR * rh - sy, hw + colX[selC + 1] * scale - sx, hh + (selR + 1) * rh - sy, fill);
        }
        for (int r = r0; r <= r1; r++) {
            float y = hh + r * rh - sy;
            for (int col = c0; col <= c1; col++) {
                String v = sheet.get(r, col);
                if (v == null) continue;
                float x = hw + colX[col] * scale - sx, cw = (colX[col + 1] - colX[col]) * scale - 2 * pad * scale;
                if (cw <= 0) continue;
                int n = text.breakText(v, true, cw, null);
                int nl = v.indexOf('\n');
                if (nl >= 0 && nl < n) n = nl;
                float tw = sheet.numeric(r, col) ? text.measureText(v, 0, n) : 0;
                float tx = sheet.numeric(r, col) ? x + (colX[col + 1] - colX[col]) * scale - pad * scale - tw : x + pad * scale;
                cv.drawText(v, 0, n, tx, y + base, text);
            }
        }
        for (int r = r0; r <= r1 + 1; r++) { float y = hh + r * rh - sy; cv.drawLine(hw, y, w, y, line); }
        for (int col = c0; col <= c1 + 1 && col <= cols; col++) { float x = hw + colX[col] * scale - sx; cv.drawLine(x, hh, x, h, line); }
        cv.restore();
        // frozen headers
        fill.setColor(c.p.surface2);
        cv.drawRect(0, 0, w, hh, fill);
        cv.drawRect(0, 0, hw, h, fill);
        float hb = (rh - head.ascent() - head.descent()) / 2;
        cv.save(); cv.clipRect(hw, 0, w, hh);
        for (int col = c0; col <= c1; col++) {
            float x = hw + colX[col] * scale - sx;
            head.setColor(col == selC ? c.p.primary : c.p.muted);
            cv.drawText(Sheet.colName(col), x + (colX[col + 1] - colX[col]) * scale / 2, hb, head);
            cv.drawLine(x, 0, x, hh, line);
        }
        cv.restore();
        cv.save(); cv.clipRect(0, hh, hw, h);
        for (int r = r0; r <= r1; r++) {
            float y = hh + r * rh - sy;
            head.setColor(r == selR ? c.p.primary : c.p.muted);
            cv.drawText(String.valueOf(r + 1), hw / 2, y + hb, head);
            cv.drawLine(0, y, hw, y, line);
        }
        cv.restore();
        cv.drawLine(0, hh, w, hh, line);
        cv.drawLine(hw, 0, hw, h, line);
    }
}
