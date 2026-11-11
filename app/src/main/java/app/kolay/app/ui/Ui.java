package app.kolay.app.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;

/** Small view factory + motion helpers. Programmatic UI (no XML layouts). */
public final class Ui {
    private Ui() {}

    // Material-style easing curves.
    public static final Interpolator EMPH = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);   // emphasized decelerate: entering
    public static final Interpolator STD = new PathInterpolator(0.2f, 0f, 0f, 1f);         // standard
    public static final Interpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);   // leaving
    public static final Interpolator POP = new OvershootInterpolator(1.6f);

    /** Motion durations (ms): small state changes, most transitions, the largest moves. Nothing in the app runs longer. */
    public static final int D_FAST = 150, D_MED = 200, D_SLOW = 250;
    /** Delay between items of a staggered entrance (ms). */
    public static final int STAGGER = 30;

    /** Type scale (sp): display (home greeting), page title, card title, body, secondary, caption/label. */
    public static final float T_DISPLAY = 30, T_TITLE = 22, T_HEAD = 17, T_BODY = 15, T_SUB = 13, T_CAP = 12;
    /** Corner radii (dp): cards, buttons, inputs, icon tiles, chips. */
    public static final float R_CARD = 24, R_BTN = 16, R_INPUT = 14, R_TILE = 14, R_CHIP = 12;

    public enum Style { PRIMARY, TONAL, GHOST }

    /** Button heights (dp): bottom action bar, buttons inside content, low-emphasis ghost buttons. */
    public static final int H_BAR = 56, H_BTN = 52, H_GHOST = 48;
    /** Page side gutter and gap between cards / paired buttons (dp). */
    public static final int GUTTER = 16, GAP = 10;

    public static TextView text(Ctx c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c.a);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        t.setIncludeFontPadding(false);
        return t;
    }

    public static GradientDrawable rounded(Ctx c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(c.dp(radiusDp)); return d;
    }

    public static GradientDrawable outlined(Ctx c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = rounded(c, fill, radiusDp); d.setStroke(Math.max(1, c.dp(1)), stroke); return d;
    }

    /** Rounded background with touch ripple and a matching shadow outline. */
    public static void surface(Ctx c, View v, int fill, float radiusDp, boolean raised) {
        Drawable content = c.p.dark ? outlined(c, fill, c.p.line, radiusDp) : rounded(c, fill, radiusDp);
        GradientDrawable mask = rounded(c, Color.WHITE, radiusDp);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(c.p.ripple), content, mask));
        roundOutline(c, v, radiusDp);
        if (raised && !c.p.dark) softShadow(c, v, 3, c.p.shadow);
    }

    /** Rounded outline (for shadows and clipping) that follows the view's size. */
    public static void roundOutline(Ctx c, View v, float radiusDp) {
        final float r = c.dp(radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
    }

    /** Elevation with a tinted (not grey) shadow: reads as depth without dirtying the light theme. */
    public static void softShadow(Ctx c, View v, float elevationDp, int tint) {
        v.setElevation(c.dp(elevationDp));
        v.setOutlineAmbientShadowColor(tint);
        v.setOutlineSpotShadowColor(tint);
    }

    /** Ripple only (no fill): list rows and menu entries. */
    public static RippleDrawable ripple(Ctx c, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(c.p.ripple), null, rounded(c, Color.WHITE, radiusDp));
    }

    /** Linear gradient with rounded corners; {@code o} e.g. TL_BR for a diagonal wash. */
    public static GradientDrawable gradient(Ctx c, int from, int to, float radiusDp, GradientDrawable.Orientation o) {
        GradientDrawable d = new GradientDrawable(o, new int[]{from, to});
        d.setCornerRadius(c.dp(radiusDp));
        return d;
    }

    /** False when the user turned animations off (developer option scale 0 or the accessibility "remove animations"). */
    public static boolean motion() { return android.animation.ValueAnimator.areAnimatorsEnabled(); }

    /** Subtle scale-down while the finger is on the view. Does not consume the touch. */
    public static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).setInterpolator(STD).start(); break;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(D_MED).setInterpolator(POP).start(); break;
            }
            return false;
        });
    }

    /** Vivid icon tile: the tone's gradient, a white icon and a shadow in the tone's own color. */
    public static FrameLayout toneTile(Ctx c, Icon.Kind k, Palette.Tone t, float sizeDp, float iconDp, float radiusDp) {
        FrameLayout f = new FrameLayout(c.a);
        f.setBackground(gradient(c, t.a, t.b, radiusDp, GradientDrawable.Orientation.TL_BR));
        f.addView(icon(c, k, Color.WHITE, iconDp), new FrameLayout.LayoutParams(c.dp(iconDp), c.dp(iconDp), Gravity.CENTER));
        roundOutline(c, f, radiusDp);
        if (!c.p.dark) softShadow(c, f, 4, t.a);
        f.setLayoutParams(new ViewGroup.LayoutParams(c.dp(sizeDp), c.dp(sizeDp)));
        return f;
    }

    /** Soft icon tile: a pale fill with the icon in the matching deep color. */
    public static FrameLayout softTile(Ctx c, Icon.Kind k, int bg, int fg, float sizeDp, float iconDp, float radiusDp) {
        FrameLayout f = new FrameLayout(c.a);
        f.setBackground(rounded(c, bg, radiusDp));
        f.addView(icon(c, k, fg, iconDp), new FrameLayout.LayoutParams(c.dp(iconDp), c.dp(iconDp), Gravity.CENTER));
        f.setLayoutParams(new ViewGroup.LayoutParams(c.dp(sizeDp), c.dp(sizeDp)));
        return f;
    }

    /** Thin hairline between rows of a card. */
    public static View divider(Ctx c, int insetStartDp) {
        View v = new View(c.a);
        v.setBackgroundColor(c.p.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, c.dp(0.75f)));
        lp.setMarginStart(c.dp(insetStartDp));
        v.setLayoutParams(lp);
        return v;
    }

    /** Short message at the bottom of the page, in the app's style (replaces system toasts). */
    public static void toast(Ctx c, CharSequence msg) { Snack.show(c, msg, false); }

    /** Like {@link #toast} but stays a little longer (errors, longer sentences). */
    public static void toastLong(Ctx c, CharSequence msg) { Snack.show(c, msg, true); }

    public static void haptic(View v, int kind) { v.performHapticFeedback(kind); }

    public static ImageView icon(Ctx c, Icon.Kind k, int color, float sizeDp) {
        ImageView iv = new ImageView(c.a);
        iv.setImageDrawable(new Icon(k, color));
        iv.setLayoutParams(new ViewGroup.LayoutParams(c.dp(sizeDp), c.dp(sizeDp)));
        return iv;
    }

    /** Round tappable icon button (back, settings…). */
    public static FrameLayout iconButton(Ctx c, Icon.Kind k, String desc, Runnable onClick) {
        FrameLayout f = new FrameLayout(c.a);
        surface(c, f, c.p.surface2, 22, false);
        ImageView iv = icon(c, k, c.p.ink, 22);
        f.addView(iv, new FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER));
        f.setContentDescription(desc); f.setClickable(true);
        f.setOnClickListener(v -> { haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); onClick.run(); });
        press(f);
        f.setLayoutParams(new LinearLayout.LayoutParams(c.dp(44), c.dp(44)));
        return f;
    }

    public static TextView button(Ctx c, String label, Style st, Icon.Kind k) {
        TextView b = text(c, label, 16, 0, 600);
        b.setGravity(Gravity.CENTER); b.setClickable(true); b.setFocusable(true);
        b.setPadding(c.dp(20), 0, c.dp(20), 0);
        // fixed-height buttons: a long label shrinks (down to 11sp) instead of being cut off
        b.setMaxLines(1);
        b.setAutoSizeTextTypeUniformWithConfiguration(11, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        style(c, b, st, k);
        press(b);
        return b;
    }

    public static void style(Ctx c, TextView b, Style st, Icon.Kind k) {
        int fg = st == Style.PRIMARY ? c.p.onPrimary : st == Style.TONAL ? c.p.onPrimarySoft : c.p.primary;
        int bg = st == Style.PRIMARY ? c.p.primary : st == Style.TONAL ? c.p.primarySoft : 0;
        b.setTextColor(fg);
        if (st == Style.GHOST) b.setBackground(new RippleDrawable(ColorStateList.valueOf(c.p.ripple), null, rounded(c, Color.WHITE, 16)));
        else surface(c, b, bg, 16, false);
        if (k != null) {
            Drawable d = new Icon(k, fg); d.setBounds(0, 0, c.dp(20), c.dp(20));
            b.setCompoundDrawablesRelative(d, null, null, null); b.setCompoundDrawablePadding(c.dp(8));
        } else b.setCompoundDrawablesRelative(null, null, null, null);
    }

    /** Fade + rise entrance, used with a small per-item delay for staggered lists. */
    public static void appear(View v, long delayMs) {
        if (!motion()) { v.animate().cancel(); v.setAlpha(1f); v.setTranslationY(0); return; }
        v.setAlpha(0f); v.setTranslationY(v.getResources().getDisplayMetrics().density * 14);
        v.animate().alpha(1f).translationY(0).setStartDelay(delayMs).setDuration(D_SLOW).setInterpolator(EMPH).start();
    }

    /** Staggered {@link #appear} over a list of views, the delay capped so long lists do not trail. */
    public static void stagger(java.util.List<? extends View> views, long startMs) {
        long d = startMs; int i = 0;
        for (View v : views) { appear(v, d); if (++i < 8) d += STAGGER; }
    }

    /** Cross-fades a label's text with a tiny vertical slide. */
    public static void swapText(TextView t, CharSequence s) {
        if (t.getText().toString().contentEquals(s)) return;
        float d = t.getResources().getDisplayMetrics().density * 5;
        t.animate().alpha(0f).translationY(-d).setDuration(90).withEndAction(() -> {
            t.setText(s); t.setTranslationY(d);
            t.animate().alpha(1f).translationY(0).setDuration(D_FAST).setInterpolator(STD).start();
        }).start();
    }

    /** Uppercase section label above a card or list. */
    public static TextView label(Ctx c, String s) {
        TextView t = text(c, s, T_CAP, c.p.muted, 700);
        t.setLetterSpacing(0.12f);
        return t;
    }

    /** Content card: rounded surface (raised in light theme) with inner padding. */
    public static LinearLayout card(Ctx c, int fill, int padDp) {
        LinearLayout l = new LinearLayout(c.a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(c.dp(padDp), c.dp(padDp), c.dp(padDp), c.dp(padDp));
        surface(c, l, fill, R_CARD, fill == c.p.surface);
        return l;
    }

    /** Single-line text field in the shared input style. */
    public static EditText input(Ctx c, String hint, int inputType) {
        EditText e = new EditText(c.a);
        e.setInputType(inputType);
        e.setSingleLine(true); e.setHint(hint);
        e.setTextColor(c.p.ink); e.setHintTextColor(c.p.muted); e.setTextSize(15);
        e.setBackground(inputBg(c));
        e.setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14));
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setTypeface(Typeface.DEFAULT);   // password fields would otherwise switch to monospace
        int v = inputType & android.text.InputType.TYPE_MASK_VARIATION;
        if ((inputType & android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT
                && (v == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD || v == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
            e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());   // setSingleLine replaced the masking
        return e;
    }

    /** Input background: soft fill, accent outline while focused. */
    public static Drawable inputBg(Ctx c) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, outlined(c, c.p.surface2, c.p.primary, R_INPUT));
        d.addState(new int[0], rounded(c, c.p.surface2, R_INPUT));
        d.setEnterFadeDuration(D_FAST); d.setExitFadeDuration(D_FAST);
        return d;
    }

    /** Two equal-width views side by side with the standard gap. */
    public static LinearLayout pair(Ctx c, View a, View b, int heightDp) {
        LinearLayout row = new LinearLayout(c.a);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, c.dp(heightDp), 1), lb = new LinearLayout.LayoutParams(0, c.dp(heightDp), 1);
        lb.setMarginStart(c.dp(GAP));
        row.addView(a, la); row.addView(b, lb);
        return row;
    }

    /** Full-width navigation row: icon badge (soft tone {@code idx}), title and subtitle, chevron. */
    public static LinearLayout navRow(Ctx c, Icon.Kind icon, String title, String subtitle, int idx, Runnable open) {
        Palette p = c.p;
        LinearLayout row = new LinearLayout(c.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(c.dp(14), 0, c.dp(16), 0);
        surface(c, row, p.surface, 20, true);
        row.setClickable(true);
        row.addView(toneTile(c, icon, p.tone(idx), 46, 22, R_TILE), new LinearLayout.LayoutParams(c.dp(46), c.dp(46)));
        LinearLayout txt = new LinearLayout(c.a);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(c, title, 16, p.ink, 700);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(name);
        TextView sub = text(c, subtitle, T_SUB, p.muted, 400);
        sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(sub, lp(-1, -2, 0, c.dp(3), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.setMarginStart(c.dp(14));
        row.addView(txt, tl);
        row.addView(icon(c, Icon.Kind.CHEVRON, p.muted, 18), new LinearLayout.LayoutParams(c.dp(18), c.dp(18)));
        row.setContentDescription(title + ". " + subtitle);
        row.setOnClickListener(v -> { haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); open.run(); });
        press(row);
        return row;
    }

    /** Bottom action area: a full-width row with page gutters, sitting under scrolling content. */
    public static LinearLayout bottomBar(Ctx c) {
        LinearLayout l = new LinearLayout(c.a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(c.dp(GUTTER), c.dp(8), c.dp(GUTTER), c.dp(GUTTER));
        return l;
    }

    /**
     * Centered empty / error / permission state: icon badge, title, explanation and an optional button.
     * {@code btn} may be null for a purely informative state.
     */
    public static LinearLayout state(Ctx c, Icon.Kind icon, String title, String body, String btn, Runnable action) {
        LinearLayout l = new LinearLayout(c.a);
        l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
        l.setPadding(c.dp(28), c.dp(24), c.dp(28), c.dp(48));
        FrameLayout halo = new FrameLayout(c.a);
        halo.setBackground(oval(c.p.dark ? Palette.mix(c.p.bg, c.p.primarySoft, 0.55f) : c.p.primarySoft));
        FrameLayout badge = softTile(c, icon, c.p.surface, c.p.primary, 64, 30, 22);
        if (!c.p.dark) { roundOutline(c, badge, 22); softShadow(c, badge, 4, c.p.shadow); }
        else badge.setBackground(outlined(c, c.p.surface, c.p.line, 22));
        halo.addView(badge, new FrameLayout.LayoutParams(c.dp(64), c.dp(64), Gravity.CENTER));
        l.addView(halo, new LinearLayout.LayoutParams(c.dp(104), c.dp(104)));
        TextView t = text(c, title, 19, c.p.ink, 700); t.setGravity(Gravity.CENTER);
        l.addView(t, lp(-2, -2, 0, c.dp(18), 0, 0));
        if (body != null && !body.isEmpty()) {
            TextView b = text(c, body, 14, c.p.muted, 400); b.setGravity(Gravity.CENTER); b.setLineSpacing(0, 1.25f);
            l.addView(b, lp(-2, -2, 0, c.dp(8), 0, 0));
        }
        if (btn != null) {
            TextView go = button(c, btn, Style.PRIMARY, null);
            go.setMinWidth(c.dp(160));
            go.setOnClickListener(v -> { haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); action.run(); });
            l.addView(go, lp(-2, c.dp(H_BTN), 0, c.dp(22), 0, 0));
        }
        return l;
    }

    /** Centered busy state: the arc loader with a title and a muted line. */
    public static LinearLayout busy(Ctx c, String title, String body) {
        LinearLayout l = new LinearLayout(c.a);
        l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
        l.setPadding(c.dp(28), 0, c.dp(28), c.dp(48));
        l.addView(new Arc(c.a, c.p.primarySoft, c.p.primary, c.dp(5)), lp(c.dp(56), c.dp(56)));
        TextView t = text(c, title, 18, c.p.ink, 600); t.setGravity(Gravity.CENTER);
        l.addView(t, lp(-2, -2, 0, c.dp(22), 0, 0));
        if (body != null) {
            TextView b = text(c, body, 14, c.p.muted, 400); b.setGravity(Gravity.CENTER);
            l.addView(b, lp(-2, -2, 0, c.dp(6), 0, 0));
        }
        return l;
    }

    /**
     * Segmented control (two or more options); the selected one is filled. Tapping another calls {@code onPick}.
     * Each option is icon + label centred together; on a narrow screen or with a large font the label is cut with "…"
     * instead of running into the icon or the neighbouring option.
     */
    public static LinearLayout segmented(Ctx c, String[] labels, Icon.Kind[] icons, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout bar = new LinearLayout(c.a);
        bar.setBackground(rounded(c, c.p.surface2, 16));
        bar.setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4));
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == selected;
            int fg = on ? c.p.onPrimary : c.p.muted;
            LinearLayout item = new LinearLayout(c.a);
            item.setGravity(Gravity.CENTER);
            item.setPadding(c.dp(8), 0, c.dp(8), 0);
            if (icons != null && icons[i] != null) {
                ImageView ic = icon(c, icons[i], fg, 18);
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(c.dp(18), c.dp(18));
                il.setMarginEnd(c.dp(6));
                item.addView(ic, il);
            }
            TextView t = text(c, labels[i], 14, fg, on ? 700 : 500);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(t, new LinearLayout.LayoutParams(-2, -2));
            item.setClickable(true);
            item.setContentDescription(labels[i] + (on ? ", seçili" : ""));
            if (on) { item.setBackground(rounded(c, c.p.primary, 12)); roundOutline(c, item, 12); if (!c.p.dark) softShadow(c, item, 2, c.p.primary); }
            else {
                final int idx = i;
                item.setOnClickListener(v -> { haptic(v, HapticFeedbackConstants.CLOCK_TICK); onPick.accept(idx); });
                press(item);
            }
            bar.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return bar;
    }

    /** Copy confirmation on the button itself: "Kopyalandı" with a check for a moment, then back. */
    public static void flashCopied(Ctx c, TextView btn, Style st) {
        haptic(btn, HapticFeedbackConstants.CONFIRM);
        CharSequence before = btn.getText();
        if (!"Kopyalandı".contentEquals(before)) btn.setTag(before);
        btn.setText("Kopyalandı");
        style(c, btn, st, Icon.Kind.CHECK);
        btn.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).withEndAction(() -> btn.animate().scaleX(1f).scaleY(1f).setDuration(D_MED).setInterpolator(POP).start()).start();
        c.ui.postDelayed(() -> {
            if (btn.isAttachedToWindow() && "Kopyalandı".contentEquals(btn.getText())) {
                Object t = btn.getTag();
                btn.setText(t instanceof CharSequence ? (CharSequence) t : "Kopyala");
                style(c, btn, st, Icon.Kind.COPY);
            }
        }, 1600);
    }

    public static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(color); return d;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(l, t, r, b); return p;
    }
}
