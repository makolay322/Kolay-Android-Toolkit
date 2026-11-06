package app.kolay.app.tools.scan;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.PhotoPick;
import app.kolay.app.ui.*;
import java.util.ArrayList;
import java.util.List;

/**
 * First page of the scanner: live camera with the detected page outline, manual shutter, optional auto capture (outline
 * steady for ~1 s), torch, gallery import and a strip of the pages so far. A source of the documents flow: leaving it keeps the pages ({@link DocsScreen} owns them).
 * The camera runs only while this screen is on top and the app is in the foreground.
 */
final class CameraScreen extends Screen implements DocCamera.Listener {
    private static final int REQ_CAMERA = 73, REQ_GALLERY = 74;
    private static final long STEADY_MS = 1000, COOLDOWN_MS = 1500;

    private final ScanSession s;
    private DocCamera cam;
    private Header header;
    private FrameLayout cameraCard, torchBtn;
    private LinearLayout statePanel, strip, footer;
    private HorizontalScrollView stripScroll;
    private TextView stateTitle, stateText, stateBtn, hint, autoChip, done;
    private ImageView torchIcon;
    private View dim;
    private Overlay overlay;
    private OrientationEventListener orient;
    private int deviceRotation;
    private boolean shown, asked, away, autoOn, armed = true;
    private long steadySince, lastShot;
    private float[] smooth, shotQuad, pendingHint;   // smooth: the stabiliser's shown outline (its array) or null
    private final QuadStabilizer stab = new QuadStabilizer();
    private final float[] lastShown = new float[8];
    private boolean shooting;

    CameraScreen(ScanSession s) { this.s = s; }

    @Override public View create() {
        Palette p = c.p;
        autoOn = c.a.getSharedPreferences("scan", 0).getBoolean("auto", false);
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        header = new Header(c, "Belge Tarayıcı", subtitle(), this::back);
        page.addView(header.view);

        FrameLayout stage = new FrameLayout(c.a);
        stage.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(10));
        cameraCard = new FrameLayout(c.a);
        Ui.surface(c, cameraCard, p.surface2, 22, false);
        cameraCard.setClipToOutline(true);
        TextureView tv = new TextureView(c.a);
        cameraCard.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        cam = new DocCamera(c.a, tv, this);
        overlay = new Overlay();
        cameraCard.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        dim = new View(c.a);   // shutter cue: a short dark dim, no flash
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        cameraCard.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        autoChip = Ui.text(c, "", 13, Color.WHITE, 600);
        autoChip.setGravity(Gravity.CENTER);
        autoChip.setPadding(c.dp(12), 0, c.dp(12), 0);
        autoChip.setClickable(true);
        autoChip.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            autoOn = !autoOn; armed = true; steadySince = 0;
            c.a.getSharedPreferences("scan", 0).edit().putBoolean("auto", autoOn).apply();
            paintAuto();
        });
        Ui.press(autoChip);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-2, c.dp(36), Gravity.TOP | Gravity.START);
        al.setMargins(c.dp(12), c.dp(12), 0, 0);
        cameraCard.addView(autoChip, al);
        paintAuto();

        hint = Ui.text(c, "", 13, Color.WHITE, 500);
        hint.setPadding(c.dp(12), c.dp(7), c.dp(12), c.dp(7));
        hint.setBackground(Ui.rounded(c, p.scrim, 14));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        hl.setMargins(c.dp(12), 0, c.dp(76), c.dp(16));
        cameraCard.addView(hint, hl);

        if (cam.hasTorch()) {
            torchBtn = new FrameLayout(c.a);
            torchIcon = new ImageView(c.a);
            torchBtn.addView(torchIcon, new FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.CENTER));
            torchBtn.setContentDescription("Fener"); torchBtn.setClickable(true);
            paintTorch(false);
            torchBtn.setOnClickListener(v -> {
                Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
                cam.setTorch(!cam.torchOn());
                paintTorch(cam.torchOn());
            });
            Ui.press(torchBtn);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(c.dp(52), c.dp(52), Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, c.dp(12), c.dp(12));
            cameraCard.addView(torchBtn, lp);
        }
        stage.addView(cameraCard, new FrameLayout.LayoutParams(-1, -1));

        statePanel = new LinearLayout(c.a);
        statePanel.setOrientation(LinearLayout.VERTICAL);
        statePanel.setGravity(Gravity.CENTER);
        statePanel.setPadding(c.dp(28), 0, c.dp(28), c.dp(48));
        FrameLayout badge = new FrameLayout(c.a);
        badge.setBackground(Ui.rounded(c, p.primarySoft, 22));
        badge.addView(Ui.icon(c, Icon.Kind.CAMERA, p.onPrimarySoft, 30), new FrameLayout.LayoutParams(c.dp(30), c.dp(30), Gravity.CENTER));
        statePanel.addView(badge, new LinearLayout.LayoutParams(c.dp(68), c.dp(68)));
        stateTitle = Ui.text(c, "", 18, p.ink, 600);
        stateTitle.setGravity(Gravity.CENTER);
        statePanel.addView(stateTitle, Ui.lp(-2, -2, 0, c.dp(18), 0, 0));
        stateText = Ui.text(c, "", 14, p.muted, 400);
        stateText.setGravity(Gravity.CENTER); stateText.setLineSpacing(0, 1.25f);
        statePanel.addView(stateText, Ui.lp(-2, -2, 0, c.dp(8), 0, 0));
        stateBtn = Ui.button(c, "", Ui.Style.PRIMARY, null);
        stateBtn.setMinWidth(c.dp(160));
        statePanel.addView(stateBtn, Ui.lp(-2, c.dp(Ui.H_BTN), 0, c.dp(22), 0, 0));
        TextView galleryAlt = Ui.button(c, "Galeriden seç", Ui.Style.GHOST, Icon.Kind.IMAGE);
        galleryAlt.setOnClickListener(v -> openGallery());
        statePanel.addView(galleryAlt, Ui.lp(-2, c.dp(Ui.H_GHOST), 0, c.dp(8), 0, 0));
        statePanel.setVisibility(View.GONE);
        stage.addView(statePanel, new FrameLayout.LayoutParams(-1, -1));
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        stripScroll = new HorizontalScrollView(c.a);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setClipToPadding(false);
        stripScroll.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), 0);
        strip = new LinearLayout(c.a);
        stripScroll.addView(strip);
        stripScroll.setVisibility(View.GONE);
        page.addView(stripScroll, Ui.lp(-1, c.dp(64), 0, 0, 0, c.dp(10)));

        footer = new LinearLayout(c.a);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        FrameLayout gallery = Ui.iconButton(c, Icon.Kind.IMAGE, "Galeriden ekle", this::openGallery);
        footer.addView(gallery, new LinearLayout.LayoutParams(c.dp(52), c.dp(52)));
        FrameLayout mid = new FrameLayout(c.a);
        Shutter shutter = new Shutter();
        shutter.setContentDescription("Fotoğraf çek");
        shutter.setOnClickListener(v -> shoot(false));
        Ui.press(shutter);
        mid.addView(shutter, new FrameLayout.LayoutParams(c.dp(72), c.dp(72), Gravity.CENTER));
        footer.addView(mid, new LinearLayout.LayoutParams(0, c.dp(76), 1));
        done = Ui.button(c, "Bitti", Ui.Style.PRIMARY, Icon.Kind.CHECK);
        done.setOnClickListener(v -> openPages());
        footer.addView(done, new LinearLayout.LayoutParams(-2, c.dp(52)));
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        refresh();
        return page;
    }

    private String subtitle() {
        int n = s.pages.size();
        return n == 0 ? "Sayfayı çerçeveye al ve çek" : n + " sayfa · devam et ya da Bitti'ye dokun";
    }

    private void paintAuto() {
        autoChip.setText(autoOn ? "Otomatik çekim: açık" : "Otomatik çekim: kapalı");
        autoChip.setBackground(Ui.rounded(c, autoOn ? c.p.primary : c.p.scrim, 18));
        autoChip.setTextColor(autoOn ? c.p.onPrimary : Color.WHITE);
    }

    private void paintTorch(boolean on) {
        if (torchBtn == null) return;
        torchBtn.setBackground(Ui.rounded(c, on ? c.p.primary : c.p.scrim, 26));
        torchIcon.setImageDrawable(new Icon(Icon.Kind.FLASH, on ? c.p.onPrimary : Color.WHITE));
    }

    /** Page strip + counter + Bitti; called whenever the session changes while this screen is on top. */
    private void refresh() {
        if (header == null) return;
        Ui.swapText(header.subtitle, subtitle());
        int n = s.pages.size();
        done.setEnabled(n > 0);
        done.setAlpha(n > 0 ? 1f : 0.45f);
        done.setText(n > 0 ? "Bitti (" + n + ")" : "Bitti");
        strip.removeAllViews();
        for (int i = 0; i < n; i++) {
            ScanSession.Page pg = s.pages.get(i);
            FrameLayout cell = new FrameLayout(c.a);
            cell.setBackground(Ui.rounded(c, c.p.surface2, 10));
            final float r = c.dp(10);
            cell.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            cell.setClipToOutline(true);
            if (pg.thumb != null) {
                ImageView iv = new ImageView(c.a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(pg.thumb);
                cell.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            } else if (!pg.broken) {
                cell.addView(new Arc(c.a, c.p.primarySoft, c.p.primary, c.dp(2)), new FrameLayout.LayoutParams(c.dp(18), c.dp(18), Gravity.CENTER));
            } else {
                cell.addView(Ui.icon(c, Icon.Kind.IMAGE, c.p.danger, 18), new FrameLayout.LayoutParams(c.dp(18), c.dp(18), Gravity.CENTER));
            }
            TextView num = Ui.text(c, String.valueOf(i + 1), 11, Color.WHITE, 700);
            num.setPadding(c.dp(5), c.dp(1), c.dp(5), c.dp(1));
            num.setBackground(Ui.rounded(c, c.p.scrim, 8));
            FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            nl.setMargins(c.dp(3), 0, 0, c.dp(3));
            cell.addView(num, nl);
            cell.setClickable(true);
            cell.setContentDescription("Sayfa " + (i + 1));
            cell.setOnClickListener(v -> openPages());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(c.dp(46), c.dp(62));
            if (i > 0) lp.setMarginStart(c.dp(8));
            strip.addView(cell, lp);
        }
        stripScroll.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (n > 0) stripScroll.post(() -> stripScroll.fullScroll(View.FOCUS_RIGHT));
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------

    @Override public void onShown() {
        shown = true;
        s.onChange = this::refresh;
        ensureCamera();
    }

    @Override public void onResume() { if (shown && !away) ensureCamera(); }

    @Override public void onPause() { release(); }

    @Override public void onReturn() {
        away = false;
        s.onChange = this::refresh;
        refresh();
        ensureCamera();
    }

    @Override public void onDestroy() { release(); }

    /** Camera, torch, orientation sensor off. */
    private void release() {
        cam.stop();
        paintTorch(false);
        if (orient != null) orient.disable();
        stab.reset(); smooth = null; shooting = false;
        overlay.invalidate();
    }

    private boolean granted() { return c.a.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private void ensureCamera() {
        if (!shown || away) return;
        if (granted()) {
            statePanel.setVisibility(View.GONE);
            cameraCard.setVisibility(View.VISIBLE);
            if (orient == null) orient = new OrientationEventListener(c.a) {
                @Override public void onOrientationChanged(int o) {
                    if (o == ORIENTATION_UNKNOWN) return;
                    deviceRotation = ((o + 45) / 90 * 90) % 360;
                }
            };
            if (orient.canDetectOrientation()) orient.enable();
            hint.setText("Sayfa aranıyor…");
            cam.start();
        } else if (!asked) {
            asked = true;
            c.a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            showState("Kamera izni gerekli", "Belgeyi çekmek için kamera izni ver. Galeriden de sayfa ekleyebilirsin.",
                c.a.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ? "İzin ver" : "Ayarları aç", this::askOrSettings);
        }
    }

    private void askOrSettings() {
        if (c.a.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
            c.a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        else c.a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.a.getPackageName(), null)));
    }

    @Override public void onPermission(int req, String[] perms, int[] results) { if (req == REQ_CAMERA) ensureCamera(); }

    private void showState(String title, String text, String btn, Runnable action) {
        release();
        cameraCard.setVisibility(View.GONE);
        stateTitle.setText(title); stateText.setText(text); stateBtn.setText(btn);
        stateBtn.setOnClickListener(v -> action.run());
        if (statePanel.getVisibility() != View.VISIBLE) { statePanel.setVisibility(View.VISIBLE); Ui.appear(statePanel, 0); }
    }

    // ---- camera callbacks -----------------------------------------------------------------------------------

    @Override public void onQuad(float[] q) {
        long now = SystemClock.uptimeMillis();
        float[] m = stab.update(q, now);   // median of recent frames; held briefly through failed frames
        if (m == null) {
            if (smooth != null) {
                smooth = null; steadySince = 0; armed = true;
                if (!shooting) hint.setText("Sayfa aranıyor…");
            }
            overlay.invalidate();
            return;
        }
        if (smooth == null || QuadStabilizer.dist(m, lastShown) > 0.025f) steadySince = now;
        System.arraycopy(m, 0, lastShown, 0, 8);
        smooth = m;
        if (!armed && shotQuad != null && QuadStabilizer.dist(m, shotQuad) > 0.12f) armed = true;   // a new page was put down
        overlay.invalidate();
        if (shooting || q == null) return;   // a held outline neither shoots nor changes the hint
        long steady = now - steadySince;
        if (autoOn && armed && steady >= STEADY_MS && now - lastShot > COOLDOWN_MS) { shoot(true); return; }
        hint.setText(autoOn ? (armed ? "Sabit tut…" : "Sonraki sayfayı yerleştir") : "Sayfa bulundu");
    }

    private void shoot(boolean auto) {
        if (shooting || cameraCard.getVisibility() != View.VISIBLE) return;
        if (!cam.capture(deviceRotation)) { if (!auto) Toast.makeText(c.a, "Kamera hazır değil", Toast.LENGTH_SHORT).show(); return; }
        shooting = true;
        armed = false;
        lastShot = SystemClock.uptimeMillis();
        // the still is turned by the phone's rotation relative to the (portrait) preview; turn the live outline along
        pendingHint = smooth == null ? null : app.kolay.app.doc.QuadDetector.rotate(smooth, deviceRotation);
        shotQuad = smooth == null ? null : smooth.clone();
        hint.setText("Çekiliyor…");
        Ui.haptic(overlay, HapticFeedbackConstants.VIRTUAL_KEY);
        dim.animate().cancel();
        dim.setAlpha(0.3f);
        dim.animate().alpha(0f).setDuration(180).setInterpolator(Ui.EXIT).start();
        cameraCard.animate().cancel();
        cameraCard.setScaleX(0.97f); cameraCard.setScaleY(0.97f);
        cameraCard.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(Ui.EXIT).start();
    }

    @Override public void onJpeg(byte[] jpeg) {
        shooting = false;
        lastShot = SystemClock.uptimeMillis();
        Ui.haptic(overlay, HapticFeedbackConstants.CONFIRM);
        hint.setText(autoOn ? "Sonraki sayfayı yerleştir" : "Kaydedildi · sonraki sayfa");
        s.addJpeg(jpeg, pendingHint);
        pendingHint = null;
        done.animate().cancel();
        done.setScaleX(1.08f); done.setScaleY(1.08f);
        done.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Ui.EXIT).start();
    }

    @Override public void onCaptureFailed() {
        shooting = false;
        hint.setText("Çekilemedi, tekrar dene");
    }

    @Override public void onError(String message) {
        showState("Kamera kullanılamıyor", message, "Tekrar dene", this::ensureCamera);
    }

    // ---- navigation -----------------------------------------------------------------------------------------

    private void leaveForChild() { away = true; release(); }

    private void openPages() {
        if (s.pages.isEmpty()) return;
        leaveForChild();
        c.nav.push(new PagesScreen(s));
    }

    /** The camera is released before the picker opens and only comes back once the picker has returned. */
    private void openGallery() {
        leaveForChild();
        try { c.a.startActivityForResult(PhotoPick.intent(true), REQ_GALLERY); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(c.a, "Galeri açılamadı", Toast.LENGTH_SHORT).show();
            away = false;
            ensureCamera();
        }
    }

    @Override public void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_GALLERY) return;
        List<Uri> uris = result == android.app.Activity.RESULT_OK ? PhotoPick.uris(data) : new ArrayList<>();
        if (uris.isEmpty()) { away = false; ensureCamera(); return; }   // cancelled: camera back (start is idempotent)
        s.addUris(uris);
        c.nav.push(new PagesScreen(s));
    }

    private void back() {
        release();
        c.nav.pop();
    }

    @Override public boolean onBack() { back(); return true; }

    // ---- views ----------------------------------------------------------------------------------------------

    /** The detected outline over the preview (letterboxed like the preview itself). */
    private final class Overlay extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), bars = new Paint();
        private final Path path = new Path();
        Overlay() {
            super(c.a);
            stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(c.dp(3)); stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(c.p.primary);
            fill.setColor((c.p.primary & 0x00FFFFFF) | 0x33000000);
            bars.setColor(Color.BLACK);
        }
        @Override protected void onDraw(Canvas cv) {
            float vw = getWidth(), vh = getHeight();
            float[] cs = cam.content();
            float k = Math.min(vw / cs[0], vh / cs[1]);
            float w = cs[0] * k, h = cs[1] * k, ox = (vw - w) / 2, oy = (vh - h) / 2;
            if (cameraCard.getVisibility() == VISIBLE) {   // letterbox bars in black, like a camera viewfinder
                if (oy > 0) { cv.drawRect(0, 0, vw, oy, bars); cv.drawRect(0, oy + h, vw, vh, bars); }
                if (ox > 0) { cv.drawRect(0, 0, ox, vh, bars); cv.drawRect(ox + w, 0, vw, vh, bars); }
            }
            float[] q = smooth;
            if (q == null) return;
            path.reset();
            for (int i = 0; i < 4; i++) {
                float x = ox + q[i * 2] * w, y = oy + q[i * 2 + 1] * h;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            boolean steady = autoOn && armed && steadySince != 0 && SystemClock.uptimeMillis() - steadySince > STEADY_MS / 2;
            stroke.setColor(steady ? c.p.ok : c.p.primary);
            cv.drawPath(path, fill);
            cv.drawPath(path, stroke);
        }
    }

    /** Round shutter: filled disc inside a ring. */
    private final class Shutter extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shutter() {
            super(c.a);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(c.dp(4)); ring.setColor(c.p.primary);
            disc.setColor(c.p.primary);
            setClickable(true);
        }
        @Override protected void onDraw(Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f, cx = getWidth() / 2f, cy = getHeight() / 2f;
            cv.drawCircle(cx, cy, r - c.dp(2), ring);
            cv.drawCircle(cx, cy, r - c.dp(9), disc);
        }
    }
}
