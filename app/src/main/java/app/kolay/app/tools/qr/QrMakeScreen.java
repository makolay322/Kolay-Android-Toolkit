package app.kolay.app.tools.qr;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import app.kolay.app.ui.*;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.OutputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Makes a QR code from text/link, Wi-Fi credentials or a contact card. The preview updates while typing; nothing is written
 * anywhere until Save or Share. Tapping the code turns the screen brightness up so another phone reads it easily.
 */
final class QrMakeScreen extends Screen {
    private static final int TEXT = 0, WIFI = 1, CONTACT = 2;
    private static final int QUIET = 4;          // quiet-zone modules around the code
    private static final int EXPORT_PX = 1080;

    private int mode = TEXT;
    private FrameLayout modeHolder, form;
    private ImageView preview;
    private TextView previewMsg, save, share;
    private EditText text, ssid, pass, name, phone, email, org;
    private QrPayload.Security sec = QrPayload.Security.WPA;
    private boolean hidden, bright, destroyed;
    private String content = "";
    private BitMatrix matrix;
    private final Runnable rebuild = this::rebuild;

    @Override public View create() {
        Palette p = c.p;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, "QR Oluştur", "Metin, Wi-Fi veya kişi kartı", () -> c.nav.pop()).view);

        LinearLayout col = new LinearLayout(c.a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));

        modeHolder = new FrameLayout(c.a);
        col.addView(modeHolder, Ui.lp(-1, c.dp(48)));
        paintModes();

        // preview: always black on white (scanners need the contrast, whatever the theme)
        LinearLayout pc = Ui.card(c, p.surface, 16);
        pc.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout box = new FrameLayout(c.a);
        box.setBackground(Ui.rounded(c, Color.WHITE, 14));
        int side = Math.min(c.a.getResources().getDisplayMetrics().widthPixels - c.dp(2 * Ui.GUTTER + 32), c.dp(260));
        preview = new ImageView(c.a);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        previewMsg = Ui.text(c, "", 14, 0xFF64727C, 500);   // sits on the always-white code box, so not a theme color
        previewMsg.setGravity(Gravity.CENTER);
        previewMsg.setPadding(c.dp(24), 0, c.dp(24), 0);
        box.addView(previewMsg, new FrameLayout.LayoutParams(-1, -1));
        box.setContentDescription("QR kodu; dokununca ekran parlaklığı artar");
        box.setOnClickListener(v -> toggleBright());
        pc.addView(box, new LinearLayout.LayoutParams(side, side));
        TextView tip = Ui.text(c, "Okutmayı kolaylaştırmak için koda dokun: ekran parlaklığı artar", 12, p.muted, 400);
        tip.setGravity(Gravity.CENTER);
        pc.addView(tip, Ui.lp(-1, -2, 0, c.dp(10), 0, 0));
        col.addView(pc, Ui.lp(-1, -2, 0, c.dp(12), 0, 0));

        form = new FrameLayout(c.a);
        col.addView(form, Ui.lp(-1, -2, 0, c.dp(Ui.GAP), 0, 0));

        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = Ui.bottomBar(c);
        save = Ui.button(c, "Kaydet", Ui.Style.TONAL, Icon.Kind.DOWNLOAD);
        save.setOnClickListener(v -> export(false));
        share = Ui.button(c, "Paylaş", Ui.Style.PRIMARY, Icon.Kind.SHARE);
        share.setOnClickListener(v -> export(true));
        bar.addView(Ui.pair(c, save, share, Ui.H_BAR));
        page.addView(bar);

        paintForm();
        return page;
    }

    private void paintModes() {
        modeHolder.removeAllViews();
        modeHolder.addView(Ui.segmented(c, new String[]{"Metin", "Wi-Fi", "Kişi"}, null, mode, i -> {
            mode = i; paintModes(); paintForm();
        }), new FrameLayout.LayoutParams(-1, -1));
    }

    private EditText field(LinearLayout into, String label, String hint, int type) {
        TextView l = Ui.text(c, label, 13, c.p.muted, 500);
        into.addView(l, Ui.lp(-2, -2, c.dp(2), into.getChildCount() == 0 ? 0 : c.dp(12), 0, c.dp(6)));
        EditText e = Ui.input(c, hint, type);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) { schedule(); }
        });
        into.addView(e, Ui.lp(-1, -2));
        return e;
    }

    private void paintForm() {
        form.removeAllViews();
        LinearLayout f = Ui.card(c, c.p.surface, 16);
        int plain = InputType.TYPE_CLASS_TEXT;
        if (mode == TEXT) {
            text = field(f, "Metin veya bağlantı", "https://… ya da herhangi bir metin", plain | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            text.setSingleLine(false); text.setMaxLines(6); text.setGravity(Gravity.TOP | Gravity.START);
        } else if (mode == WIFI) {
            ssid = field(f, "Ağ adı (SSID)", "örn. Ev_WiFi", plain | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            pass = field(f, "Şifre", "Wi-Fi şifresi", plain | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            TextView l = Ui.text(c, "Güvenlik", 13, c.p.muted, 500);
            f.addView(l, Ui.lp(-2, -2, c.dp(2), c.dp(12), 0, c.dp(6)));
            FrameLayout secHolder = new FrameLayout(c.a);
            f.addView(secHolder, Ui.lp(-1, c.dp(44)));
            paintSec(secHolder);
            LinearLayout hr = new LinearLayout(c.a);
            hr.setGravity(Gravity.CENTER_VERTICAL);
            hr.addView(Ui.text(c, "Gizli ağ", 15, c.p.ink, 500), new LinearLayout.LayoutParams(0, -2, 1));
            Toggle t = new Toggle(c.a, c.p.track, c.p.primary, c.p.thumb, hidden);
            t.onChange(on -> { hidden = on; schedule(); });
            hr.addView(t);
            f.addView(hr, Ui.lp(-1, -2, 0, c.dp(14), 0, 0));
            TextView w = Ui.text(c, "Kaydet/Paylaş seçersen görselde şifre de yer alır.", 12, c.p.muted, 400);
            f.addView(w, Ui.lp(-1, -2, c.dp(2), c.dp(12), 0, 0));
        } else {
            name = field(f, "Ad Soyad", "örn. Ayşe Yılmaz", plain | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            phone = field(f, "Telefon", "+49 …", InputType.TYPE_CLASS_PHONE);
            email = field(f, "E-posta", "isteğe bağlı", plain | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            org = field(f, "Kurum", "isteğe bağlı", plain | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        }
        form.addView(f, new FrameLayout.LayoutParams(-1, -2));
        rebuild();
    }

    private void paintSec(FrameLayout holder) {
        holder.removeAllViews();
        QrPayload.Security[] all = QrPayload.Security.values();
        holder.addView(Ui.segmented(c, new String[]{"WPA/WPA2/3", "WEP", "Açık"}, null, sec.ordinal(), i -> {
            sec = all[i]; paintSec(holder); schedule();
        }), new FrameLayout.LayoutParams(-1, -1));
    }

    private static String s(EditText e) { return e == null ? "" : e.getText().toString(); }

    private void schedule() { c.ui.removeCallbacks(rebuild); c.ui.postDelayed(rebuild, 180); }

    /** Builds the payload for the current mode; empty means "nothing to encode yet". */
    private String payload() {
        if (mode == TEXT) return s(text).trim();
        if (mode == WIFI) return s(ssid).isEmpty() ? "" : QrPayload.wifi(s(ssid), s(pass), sec, hidden);
        return s(name).trim().isEmpty() && s(phone).trim().isEmpty() ? "" : QrPayload.vcard(s(name), s(phone), s(email), s(org));
    }

    private void rebuild() {
        if (destroyed) return;
        content = payload();
        matrix = null;
        String msg = null;
        if (content.isEmpty()) msg = mode == WIFI ? "Ağ adını yaz" : mode == CONTACT ? "Ad veya telefon yaz" : "Metin ya da bağlantı yaz";
        else {
            try { matrix = encode(content); }
            catch (WriterException | IllegalArgumentException e) { msg = "Bu içerik bir QR koda sığmıyor; kısalt"; }
        }
        Bitmap old = preview.getDrawable() instanceof BitmapDrawable ? ((BitmapDrawable) preview.getDrawable()).getBitmap() : null;
        if (matrix != null) {
            BitmapDrawable d = new BitmapDrawable(c.a.getResources(), render(matrix, 1));
            d.setFilterBitmap(false); d.setAntiAlias(false);   // crisp modules when scaled up
            preview.setImageDrawable(d);
        } else preview.setImageDrawable(null);
        if (old != null) old.recycle();
        previewMsg.setText(msg == null ? "" : msg);
        previewMsg.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        float a = matrix == null ? 0.5f : 1f;
        save.setAlpha(a); share.setAlpha(a);
        save.setEnabled(matrix != null); share.setEnabled(matrix != null);
    }

    private static BitMatrix encode(String s) throws WriterException {
        Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
        h.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        h.put(EncodeHintType.MARGIN, 0);
        return new QRCodeWriter().encode(s, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, h);
    }

    /** {@code scale} pixels per module, plus the quiet zone. */
    private static Bitmap render(BitMatrix m, int scale) {
        int n = m.getWidth(), size = (n + 2 * QUIET) * scale;
        int[] px = new int[size * size];
        java.util.Arrays.fill(px, Color.WHITE);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            if (!m.get(x, y)) continue;
            for (int dy = 0; dy < scale; dy++) {
                int row = ((y + QUIET) * scale + dy) * size + (x + QUIET) * scale;
                java.util.Arrays.fill(px, row, row + scale, Color.BLACK);
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }

    private void toggleBright() {
        if (matrix == null) return;
        bright = !bright;
        applyBright();
        Ui.haptic(preview, HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void applyBright() {
        WindowManager.LayoutParams lp = c.a.getWindow().getAttributes();
        lp.screenBrightness = bright ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        c.a.getWindow().setAttributes(lp);
    }

    /** Writes a PNG into Pictures/Kolay (the share sheet needs a content Uri), optionally sharing it. */
    private void export(boolean doShare) {
        BitMatrix m = matrix;
        if (m == null) return;
        Ui.haptic(doShare ? share : save, HapticFeedbackConstants.VIRTUAL_KEY);
        c.io.execute(() -> {
            Uri out = null; String err = null;
            Bitmap b = null;
            try {
                b = render(m, Math.max(1, EXPORT_PX / (m.getWidth() + 2 * QUIET)));
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "QR_" + System.currentTimeMillis() + ".png");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kolay");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                out = c.a.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (out == null) throw new Exception("Dosya oluşturulamadı");
                try (OutputStream os = c.a.getContentResolver().openOutputStream(out)) {
                    if (!b.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new Exception("Kaydedilemedi");
                }
                ContentValues d = new ContentValues();
                d.put(MediaStore.Images.Media.IS_PENDING, 0);
                c.a.getContentResolver().update(out, d, null, null);
            } catch (Throwable t) {
                if (out != null) try { c.a.getContentResolver().delete(out, null, null); } catch (Exception ignored) { }
                out = null; err = t.getMessage() == null ? "Kaydedilemedi" : t.getMessage();
            } finally { if (b != null) b.recycle(); }
            final Uri u = out; final String e = err;
            c.ui.post(() -> {
                if (destroyed) return;
                if (u == null) { Ui.toast(c, e); return; }
                if (doShare) c.a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null));
                else Ui.toast(c, "Pictures/Kolay klasörüne kaydedildi");
            });
        });
    }

    @Override public void onPause() { if (bright) { bright = false; applyBright(); } }
    @Override public void onDestroy() {
        destroyed = true;
        c.ui.removeCallbacks(rebuild);
        if (bright) { bright = false; applyBright(); }
        if (preview != null && preview.getDrawable() instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) preview.getDrawable()).getBitmap();
            preview.setImageDrawable(null); b.recycle();
        }
    }
}
