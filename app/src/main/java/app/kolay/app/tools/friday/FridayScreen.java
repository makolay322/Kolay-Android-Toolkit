package app.kolay.app.tools.friday;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;
import java.io.OutputStream;
import java.util.List;
import java.util.Random;

/** Preview of the generated card with new-image / new-hadith / save / share. Nothing is stored until Save or Share. */
final class FridayScreen extends Screen {
    private final Random rnd = new Random();
    private long seed = rnd.nextLong();
    private List<String[]> hadiths;
    private int hadith;
    private Bitmap bmp;
    private Uri saved;          // set once the current image was written to Pictures/Kolay
    private int gen;            // guards against stale renders finishing out of order
    private boolean destroyed;
    private ImageView preview;
    private Arc loader;
    /** Text colour choices for the card; 0 = the theme's own colour. */
    private static final int[] INKS = {0, 0xFFFFFFFF, 0xFFF5ECD7, 0xFFD4AF37, 0xFF111111, 0xFF0E2340, 0xFF0B5D3B, 0xFF6D1A2E};
    private static final String[] INK_NAMES = {"Otomatik", "Beyaz", "Krem", "Altın", "Siyah", "Lacivert", "Yeşil", "Bordo"};
    private int ink;
    private final View[] swatches = new View[INKS.length];

    @Override public View create() {
        hadiths = Hadiths.all(c.a);
        hadith = rnd.nextInt(hadiths.size());
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "Cuma Mesajı", "Rastgele görsel · hadis · 2:1", () -> c.nav.pop()).view);

        // 2:1 preview, as large as the space allows, with rounded corners and a loader until the first render.
        FrameLayout box = new FrameLayout(c.a);
        box.setPadding(c.dp(Ui.GUTTER), c.dp(4), c.dp(Ui.GUTTER), c.dp(8));
        FrameLayout frame = new FrameLayout(c.a) {
            @Override protected void onMeasure(int w, int h) {
                int width = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h) * FridayArt.W / FridayArt.H);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(width * FridayArt.H / FridayArt.W, MeasureSpec.EXACTLY));
            }
        };
        Ui.surface(c, frame, c.p.surface2, 22, true);
        frame.setClipToOutline(true);
        loader = new Arc(c.a, c.p.primarySoft, c.p.primary, c.dp(4));
        frame.addView(loader, new FrameLayout.LayoutParams(c.dp(40), c.dp(40), Gravity.CENTER));
        preview = new ImageView(c.a);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setContentDescription("Cuma görseli önizlemesi");
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        box.addView(frame, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        page.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout f = new LinearLayout(c.a);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(c.dp(Ui.GUTTER), c.dp(4), c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));

        // Text colour swatches; the choice is remembered.
        ink = c.a.getSharedPreferences("friday", 0).getInt("ink", 0);
        LinearLayout inks = new LinearLayout(c.a);
        for (int i = 0; i < INKS.length; i++) {
            final int col = INKS[i];
            View sw = col == 0 ? Ui.text(c, "A", Ui.T_SUB, c.p.ink, 700) : new View(c.a);
            if (col == 0) ((TextView) sw).setGravity(Gravity.CENTER);
            sw.setContentDescription("Yazı rengi: " + INK_NAMES[i]);
            sw.setOnClickListener(v -> {
                if (ink == col) return;
                ink = col; markInk();
                c.a.getSharedPreferences("friday", 0).edit().putInt("ink", col).apply();
                render();
            });
            swatches[i] = sw;
            FrameLayout cell = new FrameLayout(c.a);
            cell.addView(sw, new FrameLayout.LayoutParams(c.dp(30), c.dp(30), Gravity.CENTER));
            inks.addView(cell, new LinearLayout.LayoutParams(0, c.dp(40), 1));
        }
        markInk();
        f.addView(inks, Ui.lp(-1, -2, 0, 0, 0, c.dp(6)));

        TextView fresh = Ui.button(c, "Yeni görsel", Ui.Style.TONAL, Icon.Kind.REFRESH);
        fresh.setOnClickListener(v -> { seed = rnd.nextLong(); nextHadith(); render(); });
        TextView other = Ui.button(c, "Başka hadis", Ui.Style.TONAL, Icon.Kind.DOC);
        other.setOnClickListener(v -> { nextHadith(); render(); });
        f.addView(Ui.pair(c, fresh, other, Ui.H_GHOST));

        TextView save = Ui.button(c, "Kaydet", Ui.Style.TONAL, Icon.Kind.DOWNLOAD);
        save.setOnClickListener(v -> persist(false));
        TextView share = Ui.button(c, "Paylaş", Ui.Style.PRIMARY, Icon.Kind.SHARE);
        share.setOnClickListener(v -> persist(true));
        f.addView(Ui.pair(c, save, share, Ui.H_BAR), Ui.lp(-1, -2, 0, c.dp(Ui.GAP), 0, 0));

        TextView note = Ui.text(c, "Hadisler Diyanet'in Hadislerle İslâm eserinden alınmıştır.", 12, c.p.muted, 400);
        note.setGravity(Gravity.CENTER); note.setLineSpacing(0, 1.15f);
        f.addView(note, Ui.lp(-1, -2, c.dp(8), c.dp(12), c.dp(8), 0));
        page.addView(f, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    @Override public void onShown() { render(); }

    /** Round swatches; the selected one gets a thick primary ring. */
    private void markInk() {
        for (int i = 0; i < INKS.length; i++) {
            boolean on = INKS[i] == ink;
            android.graphics.drawable.GradientDrawable d = Ui.rounded(c, INKS[i] == 0 ? c.p.surface2 : INKS[i], 15);
            d.setStroke(c.dp(on ? 3 : 1), on ? c.p.primary : c.p.line);
            swatches[i].setBackground(d);
            swatches[i].setSelected(on);
        }
    }

    private void nextHadith() {
        int n = hadiths.size();
        if (n < 2) return;
        int next = rnd.nextInt(n - 1);
        hadith = next >= hadith ? next + 1 : next;   // never the same twice in a row
    }

    private void render() {
        final int my = ++gen;
        final long s = seed; final String[] h = hadiths.get(hadith); final int k = ink;
        c.io.execute(() -> {
            Bitmap b;
            try { b = FridayArt.render(s, h[0], h[1], k); }
            catch (Throwable t) { return; }
            c.ui.post(() -> {
                if (destroyed || my != gen) { b.recycle(); return; }
                Bitmap old = bmp;
                bmp = b; saved = null;
                preview.setImageBitmap(b);
                if (loader.getVisibility() == View.VISIBLE) {
                    loader.setVisibility(View.GONE);
                    preview.setAlpha(0f); preview.animate().alpha(1f).setDuration(220).start();
                }
                if (old != null) old.recycle();     // only after the view no longer shows it
            });
        });
    }

    /** Writes the card into Pictures/Kolay once (then reuses the Uri), optionally opening the share sheet. */
    private void persist(boolean share) {
        Bitmap b = bmp;
        if (b == null) return;
        if (saved != null) { done(saved, share, false); return; }
        c.io.execute(() -> {
            Uri out = null; String err = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "Cuma_" + System.currentTimeMillis() + ".jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kolay");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                out = c.a.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (out == null) throw new Exception("Dosya oluşturulamadı");
                try (OutputStream os = c.a.getContentResolver().openOutputStream(out)) {
                    if (!b.compress(Bitmap.CompressFormat.JPEG, 94, os)) throw new Exception("Kaydedilemedi");
                }
                ContentValues d = new ContentValues();
                d.put(MediaStore.Images.Media.IS_PENDING, 0);
                c.a.getContentResolver().update(out, d, null, null);
            } catch (Throwable t) {
                if (out != null) try { c.a.getContentResolver().delete(out, null, null); } catch (Exception ignored) { }
                out = null; err = t.getMessage() == null ? "Kaydedilemedi" : t.getMessage();
            }
            final Uri u = out; final String e = err; final Bitmap target = b;
            c.ui.post(() -> {
                if (destroyed) return;
                if (u == null) { Ui.toast(c, e); return; }
                if (bmp == target) saved = u;     // ignore if the card changed while saving
                done(u, share, true);
            });
        });
    }

    private void done(Uri u, boolean share, boolean fresh) {
        if (share) c.a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null));
        else Ui.toast(c, fresh ? "Pictures/Kolay klasörüne kaydedildi" : "Zaten kaydedildi");
    }

    @Override public void onDestroy() {
        destroyed = true; gen++;
        if (preview != null) preview.setImageDrawable(null);
        if (bmp != null) { bmp.recycle(); bmp = null; }
    }
}
