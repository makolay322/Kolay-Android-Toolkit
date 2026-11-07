package app.kolay.app.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Bottom sheets in the app's style (a plain Dialog with our own content): menus, confirmations, a text field, progress.
 * The card rises from the bottom edge with a grab handle; every dialog in the app is one of these.
 */
public final class Sheet {
    public final Dialog d;
    public final LinearLayout body;
    private final Ctx c;
    private final FrameLayout wrap;
    private boolean leaving;

    public Sheet(Ctx c, String title) {
        this.c = c;
        d = new Dialog(c.a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        body = new LinearLayout(c.a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(c.dp(20), c.dp(10), c.dp(20), c.dp(14));
        View handle = new View(c.a);
        handle.setBackground(Ui.rounded(c, c.p.line, 2));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(c.dp(36), c.dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL; hl.bottomMargin = c.dp(14);
        body.addView(handle, hl);
        if (title != null) {
            TextView t = Ui.text(c, title, 19, c.p.ink, 700);
            t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            body.addView(t, Ui.lp(-1, -2, c.dp(4), 0, 0, c.dp(12)));
        }
        ScrollView sv = new ScrollView(c.a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        sv.setBackground(c.p.dark ? Ui.outlined(c, c.p.surface, c.p.line, 28) : Ui.rounded(c, c.p.surface, 28));
        Ui.roundOutline(c, sv, 28);
        sv.setClipToOutline(true);
        wrap = new FrameLayout(c.a);
        wrap.setPadding(c.dp(8), 0, c.dp(8), c.dp(8));
        wrap.addView(sv, new FrameLayout.LayoutParams(-1, -2));
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.setWindowAnimations(0);           // our own rise below; the default zoom looks foreign
            w.setDimAmount(c.p.dark ? 0.6f : 0.4f);
        }
        d.setOnShowListener(x -> {
            if (!Ui.motion()) return;
            wrap.setTranslationY(c.dp(48)); wrap.setAlpha(0f);
            wrap.animate().translationY(0).alpha(1f).setDuration(Ui.D_SLOW).setInterpolator(Ui.EMPH).start();
        });
    }

    /** Shows the sheet unless the activity is going away (then {@link Dialog#isShowing()} stays false). */
    public Sheet show() {
        if (c.a.isFinishing() || c.a.isDestroyed()) return this;
        try { d.show(); } catch (WindowManager.BadTokenException ignored) { }   // window token already gone
        return this;
    }

    /** Slides the card down, then closes the dialog. */
    public void dismiss() {
        if (leaving || !d.isShowing()) { try { d.dismiss(); } catch (RuntimeException ignored) { } return; }
        leaving = true;
        Runnable gone = () -> { try { d.dismiss(); } catch (RuntimeException ignored) { } };
        if (!Ui.motion()) { gone.run(); return; }
        wrap.animate().translationY(c.dp(32)).alpha(0f).setDuration(Ui.D_FAST).setInterpolator(Ui.EXIT).withEndAction(gone).start();
    }

    /** A tappable row (icon may be null); closes the sheet before running {@code r}. */
    public TextView row(String label, Icon.Kind icon, boolean danger, Runnable r) {
        TextView t = Ui.text(c, label, 16, danger ? c.p.danger : c.p.ink, 500);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(c.dp(12), 0, c.dp(12), 0);
        t.setBackground(Ui.ripple(c, 14));
        if (icon != null) {
            Icon ic = new Icon(icon, danger ? c.p.danger : c.p.primary); ic.setBounds(0, 0, c.dp(22), c.dp(22));
            t.setCompoundDrawablesRelative(ic, null, null, null); t.setCompoundDrawablePadding(c.dp(16));
        }
        t.setClickable(true);
        t.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); dismiss(); r.run(); });
        body.addView(t, Ui.lp(-1, c.dp(52)));
        return t;
    }

    public TextView text(String s) {
        TextView t = Ui.text(c, s, 14, c.p.muted, 400);
        t.setLineSpacing(0, 1.2f);
        body.addView(t, Ui.lp(-1, -2, 0, 0, 0, c.dp(12)));
        return t;
    }

    /** Two buttons at the bottom: a ghost "cancel" and the main action. */
    public void actions(String cancel, String ok, boolean danger, Runnable onOk) {
        TextView no = Ui.button(c, cancel, Ui.Style.GHOST, null);
        no.setOnClickListener(v -> dismiss());
        TextView yes = Ui.button(c, ok, Ui.Style.PRIMARY, null);
        if (danger) { yes.setBackground(Ui.rounded(c, c.p.danger, Ui.R_BTN)); yes.setTextColor(c.p.dark ? c.p.bg : c.p.surface); }
        yes.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.CONFIRM); if (onOk != null) onOk.run(); });
        body.addView(Ui.pair(c, no, yes, Ui.H_GHOST), Ui.lp(-1, -2, 0, c.dp(8), 0, 0));
    }

    // ------------------------------------------------------------ ready-made sheets

    public static void menu(Ctx c, String title, String[] labels, Icon.Kind[] icons, IntConsumer pick) {
        Sheet s = new Sheet(c, title);
        for (int i = 0; i < labels.length; i++) { final int k = i; s.row(labels[i], icons == null ? null : icons[i], false, () -> pick.accept(k)); }
        s.show();
    }

    public static void confirm(Ctx c, String title, String msg, String ok, boolean danger, Runnable r) {
        Sheet s = new Sheet(c, title);
        if (msg != null) s.text(msg);
        s.actions("Vazgeç", ok, danger, () -> { s.dismiss(); r.run(); });
        s.show();
    }

    /** A text field; {@code r} gets the trimmed text and returns an error to show, or null to close. */
    public static void input(Ctx c, String title, String initial, String ok, java.util.function.Function<String, String> r) {
        input(c, title, "Ad", initial, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, ok, r);
    }

    /** Text field sheet with an explicit hint and input type (e.g. a page number). */
    public static void input(Ctx c, String title, String hint, String initial, int type, String ok, java.util.function.Function<String, String> r) {
        Sheet s = new Sheet(c, title);
        EditText e = Ui.input(c, hint, type);
        e.setText(initial);
        int dot = initial.lastIndexOf('.');
        e.setSelection(0, dot > 0 ? dot : initial.length());   // the name without its extension, as in other file managers
        s.body.addView(e, Ui.lp(-1, -2));
        TextView err = Ui.text(c, "", 13, c.p.danger, 500);
        err.setVisibility(View.GONE);
        s.body.addView(err, Ui.lp(-1, -2, c.dp(4), c.dp(8), 0, 0));
        Runnable go = () -> {
            String msg = r.apply(e.getText().toString().trim());
            if (msg == null) s.dismiss(); else { err.setText(msg); err.setVisibility(View.VISIBLE); }
        };
        e.setOnEditorActionListener((v, id, ev) -> { go.run(); return true; });
        s.actions("Vazgeç", ok, false, go);
        if (s.d.getWindow() != null) s.d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        s.show();
        e.requestFocus();
    }

    public static void info(Ctx c, String title, String msg) {
        Sheet s = new Sheet(c, title);
        s.text(msg);
        TextView ok = Ui.button(c, "Tamam", Ui.Style.TONAL, null);
        ok.setOnClickListener(v -> s.dismiss());
        s.body.addView(ok, Ui.lp(-1, c.dp(Ui.H_GHOST), 0, c.dp(4), 0, 0));
        s.show();
    }

    /** Toggle row (label + switch) for option sheets. */
    public LinearLayout toggle(String label, boolean on, Consumer<Boolean> change) {
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(c.dp(12), 0, c.dp(4), 0);
        TextView t = Ui.text(c, label, 16, c.p.ink, 500);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Toggle tg = new Toggle(c.a, c.p.track, c.p.primary, c.p.thumb, on);
        tg.onChange(change);
        row.addView(tg, new LinearLayout.LayoutParams(c.dp(52), c.dp(32)));
        body.addView(row, Ui.lp(-1, c.dp(50)));
        return row;
    }
}
