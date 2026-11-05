package app.kolay.app.tools.halal;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import app.kolay.app.tools.qr.QrCamera;
import app.kolay.app.ui.*;

/**
 * Shared camera page of the halal tool: header, camera card with torch, permission / error state and a footer slot.
 * The camera runs only while the screen is on top and in the foreground; frames are never stored.
 */
abstract class CamScreen extends Screen implements QrCamera.Listener {
    private static final int REQ_CAMERA = 72;

    protected QrCamera cam;
    protected LinearLayout footer;
    private FrameLayout stage, cameraCard, torchBtn;
    private LinearLayout statePanel;
    private TextView stateTitle, stateText, stateBtn;
    private ImageView torchIcon;
    private boolean shown, asked, finished;

    abstract String title();
    abstract String subtitle();
    abstract QrCamera.Mode mode();
    /** True on the label (OCR) page, false on the barcode page; drives the mode switch under the header. */
    abstract boolean ocrMode();
    /** Fill the footer below the camera (hint text, buttons). */
    abstract void buildFooter(LinearLayout footer);
    /** Back button / header arrow: both modes return to the home screen. The camera is already stopped. */
    void leave() { c.nav.pop(); }

    @Override public View create() {
        Palette p = c.p;
        LinearLayout page = new LinearLayout(c.a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(new Header(c, title(), subtitle(), this::back).view);

        page.addView(modeBar(), Ui.lp(-1, c.dp(44), c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(10)));

        stage = new FrameLayout(c.a);
        stage.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(12));

        cameraCard = new FrameLayout(c.a);
        Ui.surface(c, cameraCard, p.surface2, 22, false);
        cameraCard.setClipToOutline(true);
        TextureView tv = new TextureView(c.a);
        cameraCard.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        if (mode() == QrCamera.Mode.PRODUCT) cameraCard.addView(new Guide(), new FrameLayout.LayoutParams(-1, -1));
        cam = new QrCamera(c.a, tv, this, mode());
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
            lp.setMargins(0, 0, c.dp(16), c.dp(16));
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
        statePanel.setVisibility(View.GONE);
        stage.addView(statePanel, new FrameLayout.LayoutParams(-1, -1));
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        footer = new LinearLayout(c.a);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(Ui.GUTTER));
        buildFooter(footer);
        page.addView(footer);
        return page;
    }

    /**
     * Barkod | İçindekiler: two equal modes of the tool, not a main page and a sub page. Switching replaces the screen,
     * so Back from either always returns to the home screen.
     */
    private View modeBar() {
        return Ui.segmented(c, new String[]{"Barkod", "İçindekiler"}, new Icon.Kind[]{Icon.Kind.BARCODE, Icon.Kind.CAMERA}, ocrMode() ? 1 : 0, i -> {
            finish();
            if (i == 0) c.nav.replace(new ScanScreen(null));
            else c.nav.replace(new LabelScreen(null, null));
        });
    }

    private void back() { cam.stop(); leave(); }

    /** Call once the screen has what it came for: keeps the camera from restarting on resume. */
    protected final void finish() { finished = true; cam.stop(); paintTorch(false); }

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override public void onShown() { shown = true; ensureCamera(); }
    @Override public void onResume() { if (shown) ensureCamera(); }
    @Override public void onPause() { cam.stop(); paintTorch(false); }
    /** Before pushing another screen on top (which gets the lifecycle calls from then on). */
    protected final void pauseCamera() { cam.stop(); paintTorch(false); }
    @Override public void onReturn() { ensureCamera(); }
    @Override public void onDestroy() { cam.stop(); }
    @Override public boolean onBack() { back(); return true; }

    private boolean granted() { return c.a.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private void ensureCamera() {
        if (finished || !shown) return;
        if (granted()) {
            statePanel.setVisibility(View.GONE);
            cameraCard.setVisibility(View.VISIBLE);
            footer.setVisibility(View.VISIBLE);
            cam.start();
        } else if (!asked) {
            asked = true;
            c.a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            showState("Kamera izni gerekli", "Kamerayı kullanmak için erişim izni ver.",
                c.a.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ? "İzin ver" : "Ayarları aç", this::askOrSettings);
        }
    }

    private void askOrSettings() {
        if (c.a.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            c.a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            c.a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.a.getPackageName(), null)));
        }
    }

    @Override public void onPermission(int req, String[] perms, int[] results) {
        if (req == REQ_CAMERA) ensureCamera();
    }

    @Override public void onError(String message) {
        showState("Kamera kullanılamıyor", message, "Tekrar dene", this::ensureCamera);
    }

    @Override public void onCode(String text) {}

    private void showState(String title, String text, String btn, Runnable action) {
        cam.stop();
        cameraCard.setVisibility(View.GONE);
        footer.setVisibility(View.GONE);
        stateTitle.setText(title); stateText.setText(text); stateBtn.setText(btn);
        stateBtn.setOnClickListener(v -> action.run());
        if (statePanel.getVisibility() != View.VISIBLE) { statePanel.setVisibility(View.VISIBLE); Ui.appear(statePanel, 0); }
    }

    // ---- torch + guide --------------------------------------------------------------------------

    private void paintTorch(boolean on) {
        if (torchBtn == null) return;
        torchBtn.setBackground(Ui.rounded(c, on ? c.p.primary : c.p.scrim, 26));
        torchIcon.setImageDrawable(new Icon(Icon.Kind.FLASH, on ? c.p.onPrimary : Color.WHITE));
    }

    /** Wide corner brackets: a purely visual aim guide for a barcode (the whole frame is decoded). */
    private final class Guide extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Guide() {
            super(c.a);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(c.dp(4)); paint.setColor(Color.WHITE);
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth() * 0.80f, h = w * 0.5f, arm = h * 0.22f;
            float l = (getWidth() - w) / 2f, t = (getHeight() - h) / 2f, r = l + w, b = t + h;
            cv.drawLines(new float[]{
                l, t + arm, l, t,  l, t, l + arm, t,
                r - arm, t, r, t,  r, t, r, t + arm,
                r, b - arm, r, b,  r, b, r - arm, b,
                l + arm, b, l, b,  l, b, l, b - arm}, paint);
        }
    }
}
