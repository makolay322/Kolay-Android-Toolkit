package app.kolay.app.tools.friday;

import android.graphics.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.util.Random;

/**
 * Draws the Friday card entirely with Canvas (no image assets): a gradient, a geometric Islamic pattern, a crescent,
 * stars and a plain mosque silhouette (no window lights), with the hadith in a translucent panel. The same seed always
 * gives the same background. Only dark themes exist: half near-black, half deep mature colors.
 * The composition is mirror-symmetric about the vertical centre line: pattern grid, crescents, sparkles, mosque and panel
 * are all centred or drawn in mirrored pairs, and every text line is centred.
 */
final class FridayArt {
    private FridayArt() {}

    private static final class Theme {
        final int top, bottom, accent, text, panel, mosque;
        Theme(int top, int bottom, int accent, int text, int panel, int mosque) {
            this.top = lift(top); this.bottom = lift(bottom); this.accent = accent; this.text = text; this.panel = panel; this.mosque = mosque;
        }
    }

    /** Brightens a background color (x1.7 + 16 per channel) while keeping its hue, so the cards are dark but not murky. */
    private static int lift(int c) {
        int r = Math.min(255, Math.round(((c >> 16) & 255) * 1.7f) + 16), g = Math.min(255, Math.round(((c >> 8) & 255) * 1.7f) + 16), b = Math.min(255, Math.round((c & 255) * 1.7f) + 16);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static final Theme[] DARK = {
        new Theme(0xFF030607, 0xFF0A1F1E, 0xFFD4AF37, 0xFFF5F1E6, 0x66000000, 0xFF020807),
        new Theme(0xFF05050A, 0xFF1B1038, 0xFFE8C872, 0xFFF3EEFF, 0x66000000, 0xFF020208),
        new Theme(0xFF000000, 0xFF10202B, 0xFF7FD8E8, 0xFFEAF6F8, 0x66000000, 0xFF03070A),
        new Theme(0xFF0B0B0B, 0xFF241A0A, 0xFFFFC857, 0xFFFFF4DC, 0x66000000, 0xFF050300),
    };
    /** Deep, mature colors (never bright): emerald, navy, burgundy, plum, olive. */
    private static final Theme[] MATURE = {
        new Theme(0xFF0B3D2E, 0xFF04201A, 0xFFD9B44A, 0xFFF4EFE0, 0x66000000, 0xFF021410),
        new Theme(0xFF0E2340, 0xFF050E1F, 0xFFC9A85C, 0xFFEEF2F8, 0x66000000, 0xFF030812),
        new Theme(0xFF4A1523, 0xFF1F0912, 0xFFE0B96B, 0xFFF8EEE8, 0x66000000, 0xFF140509),
        new Theme(0xFF2E1A47, 0xFF130A21, 0xFFD8B86A, 0xFFF1EAF7, 0x66000000, 0xFF0B0615),
        new Theme(0xFF3B3A1F, 0xFF1A190B, 0xFFD6B25E, 0xFFF6F1DD, 0x66000000, 0xFF0F0F05),
    };

    /** Output size: square. */
    static final int W = 1080, H = 1080;

    /** @return a new square bitmap; the caller owns it */
    static Bitmap render(long seed, String hadith, String source) {
        final int w = W, h = H;
        final float u = 1f;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Random r = new Random(seed);
        boolean dark = r.nextBoolean();
        Theme t = dark ? DARK[r.nextInt(DARK.length)] : MATURE[r.nextInt(MATURE.length)];

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, t.top, t.bottom, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);

        pattern(cv, w, h, t, r);
        moon(cv, w, h, t, r);
        sparkles(cv, w, h, t, r);
        mosque(cv, w, h, t, r);
        panelAndText(cv, w, h, u, t, hadith, source);
        return bmp;
    }

    // ---- background ---------------------------------------------------------------------------

    private static void pattern(Canvas cv, int w, int h, Theme t, Random r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, h / 350f));
        p.setColor(t.accent);
        p.setAlpha(34);
        float s = h / (6f + r.nextInt(4));
        boolean stars = r.nextBoolean();
        int n = (int) Math.ceil(w / 2f / s) + 1;      // columns at w/2 + k·s, k = -n…n: the grid is centred, so it mirrors
        for (float y = -s / 2; y < h + s; y += s) {
            for (int k = -n; k <= n; k++) {
                float x = w / 2f + k * s;
                if (stars) {
                    float d = s * 0.40f;
                    cv.drawRect(x - d, y - d, x + d, y + d, p);
                    cv.save(); cv.rotate(45, x, y); cv.drawRect(x - d, y - d, x + d, y + d, p); cv.restore();
                } else {
                    cv.drawCircle(x, y, s * 0.5f, p);
                    if (k < n) cv.drawCircle(x + s / 2, y + s / 2, s * 0.5f, p);   // offset row: w/2 ± (j+½)·s, also mirrored
                }
            }
        }
    }

    /** Two small crescents flanking the title in the top strip, mirror images of each other (both open toward it). */
    private static void moon(Canvas cv, int w, int h, Theme t, Random r) {
        float cy = h * (0.042f + r.nextFloat() * 0.006f);
        float off = moonOffset(w);
        crescent(cv, w / 2f - off, cy, h * 0.026f, 1, t);
        crescent(cv, w / 2f + off, cy, h * 0.026f, -1, t);
    }

    /** Distance of each crescent from the centre: just outside the title, whose width doesn't scale with w. */
    private static float moonOffset(int w) {
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(46);
        tp.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        return tp.measureText(TITLE) / 2f + 60;
    }

    private static void crescent(Canvas cv, float cx, float cy, float rad, float dir, Theme t) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(cx, cy, rad * 2.4f, (t.accent & 0x00FFFFFF) | 0x30000000, t.accent & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, rad * 2.4f, g);
        Path outer = new Path(), inner = new Path();
        outer.addCircle(cx, cy, rad, Path.Direction.CW);
        inner.addCircle(cx + dir * rad * 0.42f, cy - rad * 0.12f, rad * 0.82f, Path.Direction.CW);
        outer.op(inner, Path.Op.DIFFERENCE);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(t.accent);
        cv.drawPath(outer, f);
        cv.drawPath(star(cx + dir * rad * 0.95f, cy - rad * 0.05f, rad * 0.30f, rad * 0.13f, 5), f);
    }

    /** Random sparkles in the top strip beside the crescents, left half, each drawn again at its mirror position on the right. */
    private static void sparkles(Canvas cv, int w, int h, Theme t, Random r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int n = 22 + r.nextInt(12);
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * w / 2f, y = h * (0.012f + r.nextFloat() * 0.06f), s = 1.5f + r.nextFloat() * 3f * h / 1000f;
            boolean star = r.nextInt(3) == 0;
            int alpha = 90 + r.nextInt(150);
            if (x > w / 2f - moonOffset(w) - 90) continue;                     // keep clear of the crescents and the title
            p.setColor(t.accent);
            p.setAlpha(alpha);
            for (float xx : new float[]{x, w - x}) {
                if (star) cv.drawPath(star(xx, y, s * 2.4f, s * 0.7f, 4), p);
                else cv.drawCircle(xx, y, s, p);
            }
        }
    }

    /** Domes and minarets along the bottom edge, scaled down and faint: a quiet accent behind the hadith panel. */
    private static void mosque(Canvas cv, int w, int h, Theme t, Random r) {
        cv.saveLayerAlpha(0, 0, w, h, 70);                        // one layer, so overlapping shapes stay uniformly faint
        cv.scale(0.7f, 0.7f, w / 2f, h);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(t.mosque);
        float cx = w / 2f;                                          // centred: the silhouette mirrors about the axis
        float mw = h * 0.45f;
        float bodyTop = h * 0.928f;
        float dr = mw * 0.21f;

        cv.drawRect(cx - mw / 2, bodyTop, cx + mw / 2, h, p);      // prayer hall
        dome(cv, p, cx, bodyTop, dr, dr * 1.15f);                  // main dome
        dome(cv, p, cx - mw * 0.36f, bodyTop + h * 0.012f, dr * 0.55f, dr * 0.65f);
        dome(cv, p, cx + mw * 0.36f, bodyTop + h * 0.012f, dr * 0.55f, dr * 0.65f);
        cv.drawRect(cx - 2f, bodyTop - dr * 1.15f - h * 0.035f, cx + 2f, bodyTop - dr * 1.1f, p);   // finial
        cv.drawCircle(cx, bodyTop - dr * 1.15f - h * 0.035f, h * 0.007f, p);

        int pairs = r.nextBoolean() ? 1 : 2;
        for (int i = 0; i < pairs; i++) {
            float off = mw / 2 + h * (0.0525f + 0.15f * i);
            float tip = h * (0.775f + 0.03f * r.nextFloat());
            minaret(cv, p, cx - off, tip, h);
            minaret(cv, p, cx + off, tip, h);
        }
        cv.restore();
    }

    private static void dome(Canvas cv, Paint p, float cx, float baseY, float rx, float ry) {
        cv.drawArc(new RectF(cx - rx, baseY - ry, cx + rx, baseY + ry), 180, 180, true, p);
    }

    private static void minaret(Canvas cv, Paint p, float x, float tipY, int h) {
        float half = h * 0.0135f, cone = h * 0.05f;
        float shaftTop = tipY + cone;
        cv.drawRect(x - half, shaftTop, x + half, h, p);
        cv.drawRect(x - half * 2f, shaftTop + h * 0.07f, x + half * 2f, shaftTop + h * 0.082f, p);   // balcony
        Path c = new Path();
        c.moveTo(x - half * 1.5f, shaftTop); c.lineTo(x, tipY); c.lineTo(x + half * 1.5f, shaftTop); c.close();
        cv.drawPath(c, p);
        cv.drawCircle(x, tipY - h * 0.006f, h * 0.0045f, p);
    }

    // ---- text ---------------------------------------------------------------------------------

    /** Upper limit for the hadith text size (px at u = 1); slightly above the 46 px title. */
    private static final float HADITH_MAX = 64f;
    private static final String TITLE = "Cumamız Mübarek Olsun";

    private static void panelAndText(Canvas cv, int w, int h, float u, Theme t, String hadith, String source) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextAlign(Paint.Align.CENTER);

        tp.setColor(t.text);
        tp.setTextSize(46 * u);
        tp.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        tp.setShadowLayer(14 * u, 0, 3 * u, 0xB3000000);   // keeps the title legible over sparkles and the moon's glow
        cv.drawText(TITLE, w / 2f, h * 0.062f, tp);
        tp.clearShadowLayer();

        // The panel is the focus, centred below the thin title strip: 75% of the area of the earlier 0.95·w × 0.87·h
        // panel (both sides × √0.75, about 0.823·w × 0.753·h), same centre (0.525·h); the pattern and mosque show
        // faintly through it and around it.
        final float k = (float) Math.sqrt(0.75);
        float pw = w * 0.95f * k, ph = h * 0.87f * k, cy = h * 0.525f;
        float pl = (w - pw) / 2f, pr = pl + pw, pt = cy - ph / 2f, pb = cy + ph / 2f;
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setColor(t.panel);
        float rad = 28 * u;
        cv.drawRoundRect(new RectF(pl, pt, pr, pb), rad, rad, pp);
        pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(3 * u); pp.setColor(t.accent); pp.setAlpha(150);
        cv.drawRoundRect(new RectF(pl, pt, pr, pb), rad, rad, pp);

        float pad = 36 * u;
        float inner = (pr - pl) - 2 * pad;

        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setColor(t.accent);
        tp.setTextSize(22 * u);
        tp.setLetterSpacing(0.18f);
        float labelY = pt + pad + 14 * u;
        cv.drawText("HADÎS-İ ŞERÎF", w / 2f, labelY, tp);
        tp.setLetterSpacing(0f);

        // source line sits at the bottom of the panel; the hadith is fitted in between, largest size that fits
        tp.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        tp.setTextSize(30 * u);
        float srcY = pb - pad * 0.7f;
        cv.drawText("— " + source, w / 2f, srcY, tp);

        float top = labelY + 26 * u, bottom = srcY - 50 * u;
        TextPaint hp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        hp.setColor(t.text);
        hp.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        String body = "“" + hadith + "”";
        // Largest size at which the balanced, unhyphenated layout fits the panel height and no single word is wider
        // than a line (so words are never split), capped at HADITH_MAX so short hadiths don't balloon.
        String[] words = body.split("\\s+");
        StaticLayout lay = null;
        for (float size = HADITH_MAX * u; size >= 24 * u; size -= 2 * u) {
            hp.setTextSize(size);
            float widest = 0;
            for (String wd : words) widest = Math.max(widest, hp.measureText(wd));
            if (widest > inner) continue;
            lay = StaticLayout.Builder.obtain(body, 0, body.length(), hp, Math.round(inner))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.18f)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
            if (lay.getHeight() <= bottom - top) break;
        }
        cv.save();
        cv.translate(pl + pad, top + Math.max(0, (bottom - top - lay.getHeight()) / 2f));
        lay.draw(cv);
        cv.restore();
    }

    // ---- shapes -------------------------------------------------------------------------------

    private static Path star(float cx, float cy, float outer, float innerR, int points) {
        Path s = new Path();
        for (int i = 0; i < points * 2; i++) {
            double a = -Math.PI / 2 + i * Math.PI / points;
            float rr = (i % 2 == 0) ? outer : innerR;
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) s.moveTo(x, y); else s.lineTo(x, y);
        }
        s.close();
        return s;
    }
}
