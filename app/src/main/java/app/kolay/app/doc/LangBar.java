package app.kolay.app.doc;

import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Ui;
import java.util.List;
import java.util.function.Consumer;

/**
 * Multi-select row of OCR languages (at least one stays on). A language that is not on the phone yet shows a small
 * download mark; the caller decides when to offer the download ({@link TessScreen}).
 */
public final class LangBar {
    public final LinearLayout view;
    private final Ctx c;
    private final Consumer<String> onChange;
    private String combo;

    public LangBar(Ctx c, String combo, Consumer<String> onChange) {
        this.c = c; this.onChange = onChange;
        this.combo = Langs.split(combo).isEmpty() ? Langs.CODES[0] : Langs.join(Langs.split(combo));
        view = new LinearLayout(c.a);
        paint();
    }

    public String combo() { return combo; }

    /** Re-reads the installed state (e.g. after returning from the download screen). */
    public void refresh() { paint(); }

    private void paint() {
        view.removeAllViews();
        List<String> on = Langs.split(combo);
        for (int i = 0; i < Langs.CODES.length; i++) {
            String code = Langs.CODES[i];
            boolean sel = on.contains(code), have = c.host.tessInstalled(code);
            int fg = sel ? c.p.onPrimary : c.p.ink;
            LinearLayout chip = new LinearLayout(c.a);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(c.dp(6), 0, c.dp(6), 0);
            Ui.surface(c, chip, sel ? c.p.primary : c.p.surface2, 14, false);
            if (!have) {
                ImageView ic = Ui.icon(c, Icon.Kind.DOWNLOAD, sel ? c.p.onPrimary : c.p.muted, 16);
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(c.dp(16), c.dp(16));
                il.setMarginEnd(c.dp(4));
                chip.addView(ic, il);
            }
            TextView t = Ui.text(c, Langs.name(code), 14, fg, sel ? 700 : 500);
            t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(t);
            chip.setClickable(true);
            chip.setContentDescription(Langs.name(code) + (sel ? ", seçili" : "") + (have ? "" : ", indirilmedi"));
            chip.setOnClickListener(v -> {
                List<String> now = Langs.split(combo);
                if (now.contains(code)) { if (now.size() == 1) return; now.remove(code); } else now.add(code);
                Ui.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
                combo = Langs.join(now);
                paint();
                onChange.accept(combo);
            });
            Ui.press(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, c.dp(40), 1);
            if (i > 0) lp.setMarginStart(c.dp(8));
            view.addView(chip, lp);
        }
    }
}
