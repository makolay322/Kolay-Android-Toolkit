package app.kolay.app.tools.scan;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.Imaging;
import app.kolay.app.doc.QuadDetector;
import app.kolay.app.ui.*;

/**
 * Page outline editor: the original picture with four draggable corners (a magnifier follows the finger), prefilled with
 * the detected outline. "Tümü" selects the whole picture, "Algıla" runs the detection again, "Döndür" turns the result.
 */
final class CropScreen extends Screen {
    private static final long VIEW_PX = 2_500_000L;

    private final ScanSession s;
    private final ScanSession.Page pg;
    private float[] quad;          // normalised, original orientation
    private int rotation;
    private Bitmap bmp;
    private QuadView qv;
    private FrameLayout stage;
    private TextView apply;
    private boolean destroyed, detecting;

    CropScreen(ScanSession s, ScanSession.Page pg) {
        this.s = s; this.pg = pg;
        quad = pg.quad.clone(); rotation = pg.rotation;
    }

    @Override public View create() {
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Kırp", "Köşeleri sayfanın köşelerine sürükle", () -> c.nav.pop()).view);
        stage = new FrameLayout(c.a);
        stage.addView(new Arc(c.a, c.p.primarySoft, c.p.primary, c.dp(4)), new FrameLayout.LayoutParams(c.dp(40), c.dp(40), Gravity.CENTER));
        page.addView(stage, Ui.lp(-1, 0, c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(8)));
        ((LinearLayout.LayoutParams) stage.getLayoutParams()).weight = 1;

        LinearLayout tools = new LinearLayout(c.a);
        tools.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        TextView all = small("Tümü", Icon.Kind.SCAN, () -> { quad = QuadDetector.full(); qv.invalidate(); });
        TextView auto = small("Algıla", Icon.Kind.SPARKLE, this::redetect);
        TextView rot = small("Döndür", Icon.Kind.REFRESH, () -> { rotation = (rotation + 90) % 360; qv.invalidate(); });
        tools.addView(all, new LinearLayout.LayoutParams(0, c.dp(Ui.H_GHOST), 1));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, c.dp(Ui.H_GHOST), 1); l2.setMarginStart(c.dp(8));
        tools.addView(auto, l2);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, c.dp(Ui.H_GHOST), 1); l3.setMarginStart(c.dp(8));
        tools.addView(rot, l3);
        page.addView(tools);

        LinearLayout bb = Ui.bottomBar(c);
        apply = Ui.button(c, "Uygula", Ui.Style.PRIMARY, Icon.Kind.CHECK);
        apply.setOnClickListener(v -> {
            if (!convex(quad)) { Toast.makeText(c.a, "Köşeler çapraz: çerçeveyi düzelt", Toast.LENGTH_SHORT).show(); return; }
            Ui.haptic(v, HapticFeedbackConstants.CONFIRM);
            if (s.pages.contains(pg)) {
                pg.quad = quad.clone(); pg.rotation = rotation;
                s.edited(pg);
            }
            c.nav.pop();
        });
        bb.addView(apply, Ui.lp(-1, c.dp(Ui.H_BAR)));
        page.addView(bb, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private TextView small(String label, Icon.Kind k, Runnable r) {
        TextView b = Ui.button(c, label, Ui.Style.TONAL, k);
        b.setTextSize(14);
        b.setPadding(c.dp(8), 0, c.dp(8), 0);
        b.setOnClickListener(v -> { if (bmp == null) return; Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK); r.run(); });
        return b;
    }

    @Override public void onShown() {
        java.io.File f = pg.file;
        c.io.execute(() -> {
            Bitmap b = null;
            try { b = Imaging.decode(f, VIEW_PX); } catch (Exception | OutOfMemoryError ignored) { }
            final Bitmap out = b;
            c.ui.post(() -> {
                if (destroyed) { if (out != null) out.recycle(); return; }
                if (out == null) { Toast.makeText(c.a, "Görüntü açılamadı", Toast.LENGTH_SHORT).show(); c.nav.pop(); return; }
                bmp = out;
                stage.removeAllViews();
                qv = new QuadView();
                stage.addView(qv, new FrameLayout.LayoutParams(-1, -1));
                qv.setAlpha(0f); qv.animate().alpha(1f).setDuration(200).start();
            });
        });
    }

    private void redetect() {
        if (detecting) return;
        detecting = true;
        Bitmap b = bmp;
        c.io.execute(() -> {
            float[] q = null;
            try { q = new QuadDetector().detect(b); } catch (Exception ignored) { }
            final float[] r = q;
            c.ui.post(() -> {
                detecting = false;
                if (destroyed) { b.recycle(); return; }   // onDestroy left it to us
                if (r == null) { Toast.makeText(c.a, "Sayfa kenarı bulunamadı", Toast.LENGTH_SHORT).show(); return; }
                quad = r; qv.invalidate();
            });
        });
    }

    static boolean convex(float[] q) {
        float sign = 0;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2, cc = ((i + 2) % 4) * 2;
            float cr = (q[b] - q[a]) * (q[cc + 1] - q[b + 1]) - (q[b + 1] - q[a + 1]) * (q[cc] - q[b]);
            if (Math.abs(cr) < 1e-6f) return false;
            if (sign == 0) sign = Math.signum(cr); else if (Math.signum(cr) != sign) return false;
        }
        return true;
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (qv != null) qv.setVisibility(View.GONE);
        Bitmap b = bmp; bmp = null;
        if (b != null && !detecting) b.recycle();   // a running detection still reads it and recycles it when done
    }

    /** Picture (shown with the chosen rotation) + outline + corner handles + magnifier while dragging. */
    private final class QuadView extends View {
        private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), dim = new Paint();
        private final Path path = new Path(), clip = new Path();
        private final float handle = c.dp(11), slop = c.dp(40), loupe = c.dp(56), inset = c.dp(14);
        private float scale, ox, oy, rw, rh, touchX;
        private int drag = -1;

        QuadView() {
            super(c.a);
            line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(c.dp(2.5f)); line.setColor(c.p.primary);
            dot.setColor(c.p.primary);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(c.dp(3)); ring.setColor(Color.WHITE);
            dim.setColor(c.p.scrim);
        }

        private void fit() {
            boolean swap = rotation % 180 != 0;
            rw = swap ? bmp.getHeight() : bmp.getWidth(); rh = swap ? bmp.getWidth() : bmp.getHeight();
            float w = getWidth() - 2 * inset, h = getHeight() - 2 * inset;
            scale = Math.min(w / rw, h / rh);
            ox = (getWidth() - rw * scale) / 2f; oy = (getHeight() - rh * scale) / 2f;
        }

        /** Original normalised → view. */
        private float vx(int i) { float[] r = rot(quad[i * 2], quad[i * 2 + 1]); return ox + r[0] * rw * scale; }
        private float vy(int i) { float[] r = rot(quad[i * 2], quad[i * 2 + 1]); return oy + r[1] * rh * scale; }

        private float[] rot(float u, float v) {
            switch (rotation) {
                case 90: return new float[]{1 - v, u};
                case 180: return new float[]{1 - u, 1 - v};
                case 270: return new float[]{v, 1 - u};
                default: return new float[]{u, v};
            }
        }

        private float[] unrot(float u, float v) {
            switch (rotation) {
                case 90: return new float[]{v, 1 - u};
                case 180: return new float[]{1 - u, 1 - v};
                case 270: return new float[]{1 - v, u};
                default: return new float[]{u, v};
            }
        }

        private void content(Canvas cv, boolean withHandles) {
            cv.save();
            cv.translate(ox, oy); cv.scale(scale, scale);
            if (rotation == 90) { cv.translate(rw, 0); cv.rotate(90); }
            else if (rotation == 180) { cv.translate(rw, rh); cv.rotate(180); }
            else if (rotation == 270) { cv.translate(0, rh); cv.rotate(270); }
            cv.drawBitmap(bmp, 0, 0, img);
            cv.restore();
            path.reset();
            for (int i = 0; i < 4; i++) { if (i == 0) path.moveTo(vx(i), vy(i)); else path.lineTo(vx(i), vy(i)); }
            path.close();
            cv.save();
            cv.clipRect(ox, oy, ox + rw * scale, oy + rh * scale);
            path.setFillType(Path.FillType.INVERSE_EVEN_ODD);
            cv.drawPath(path, dim);
            cv.restore();
            path.setFillType(Path.FillType.WINDING);
            cv.drawPath(path, line);
            if (withHandles) for (int i = 0; i < 4; i++) {
                cv.drawCircle(vx(i), vy(i), handle, dot);
                cv.drawCircle(vx(i), vy(i), handle, ring);
            }
        }

        @Override protected void onDraw(Canvas cv) {
            if (bmp == null || bmp.isRecycled()) return;
            fit();
            content(cv, true);
            if (drag < 0) return;
            // magnifier in the upper corner away from the finger
            float r = loupe, cx = touchX < getWidth() / 2f ? getWidth() - r - inset : r + inset, cy = r + inset;
            float px = vx(drag), py = vy(drag);
            cv.save();
            clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW);
            cv.clipPath(clip);
            cv.drawColor(c.p.surface2);
            cv.translate(cx, cy); cv.scale(2.5f, 2.5f); cv.translate(-px, -py);
            content(cv, false);
            cv.restore();
            cv.drawCircle(cx, cy, r, ring);
            cv.drawLine(cx - c.dp(8), cy, cx + c.dp(8), cy, line);
            cv.drawLine(cx, cy - c.dp(8), cx, cy + c.dp(8), line);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (bmp == null || scale <= 0) return true;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    drag = -1; float best = slop;
                    for (int i = 0; i < 4; i++) {
                        float d = (float) Math.hypot(x - vx(i), y - vy(i));
                        if (d < best) { best = d; drag = i; }
                    }
                    if (drag >= 0) { getParent().requestDisallowInterceptTouchEvent(true); Ui.haptic(this, HapticFeedbackConstants.CLOCK_TICK); }
                    touchX = x;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (drag < 0) return true;
                    touchX = x;
                    float u = Math.max(0, Math.min(1, (x - ox) / (rw * scale))), v = Math.max(0, Math.min(1, (y - oy) / (rh * scale)));
                    float[] o = unrot(u, v);
                    quad[drag * 2] = o[0]; quad[drag * 2 + 1] = o[1];
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    drag = -1; invalidate(); return true;
            }
            return true;
        }
    }
}
