package app.kolay.app.tools.halal;

import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import app.kolay.app.doc.TessScreen;
import app.kolay.app.tools.qr.QrCamera;
import app.kolay.app.ui.*;

/** İçindekiler mode: photograph the ingredient list, then crop it ({@link CropScreen}). The photo lives in memory only. */
final class LabelScreen extends CamScreen {
    private final String barcode, note;
    private TextView shoot;

    /** @param note why we are here (e.g. the barcode had no ingredients), shown under the camera; null for none */
    LabelScreen(String barcode, String note) { this.barcode = barcode; this.note = note; }

    @Override String title() { return "Helal Sorgu"; }
    @Override String subtitle() { return "İçindekiler yazısını net ve düz çek"; }
    @Override QrCamera.Mode mode() { return QrCamera.Mode.TEXT; }
    @Override boolean ocrMode() { return true; }

    @Override void buildFooter(LinearLayout f) {
        if (note != null) {
            TextView t = Ui.text(c, note, 13, c.p.ink, 500);
            t.setGravity(Gravity.CENTER); t.setLineSpacing(0, 1.2f);
            f.addView(t, Ui.lp(-1, -2, 0, 0, 0, c.dp(10)));
        }
        shoot = Ui.button(c, "Fotoğrafla ve sorgula", Ui.Style.PRIMARY, Icon.Kind.CAMERA);
        shoot.setOnClickListener(v -> {
            Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            if (!c.host.tessInstalled("deu")) {   // OCR data is downloaded after install: offer it before the photo
                pauseCamera();
                c.nav.push(new TessScreen("deu", null));
                return;
            }
            shoot.setEnabled(false);
            shoot.setText("Çekiliyor…");
            cam.capture();
        });
        f.addView(shoot, Ui.lp(-1, c.dp(Ui.H_BAR)));
    }

    @Override public void onStill(Bitmap still) {
        finish();
        Ui.haptic(footer, HapticFeedbackConstants.CONFIRM);
        c.nav.replace(new CropScreen(barcode, note, still));
    }
}
