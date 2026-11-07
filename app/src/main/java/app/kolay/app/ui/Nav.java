package app.kolay.app.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;

/**
 * Screen stack with animated transitions.
 *  push    – new page slides in from the right while the old one parallaxes away
 *  pop     – the reverse
 *  replace – cross-fade with a gentle scale (used inside a flow, e.g. pick → progress → result)
 */
public final class Nav {
    private final Ctx c;
    private final FrameLayout root;
    private final ArrayList<Screen> stack = new ArrayList<>();
    private boolean busy;
    private Runnable pending;  // last request that arrived mid-transition; runs when it ends

    public Nav(Ctx c, FrameLayout root) {
        this.c = c; this.root = root; c.nav = this; c.root = root;
        // Edge-to-edge: the window draws under the bars; each page frame is padded by the bar (and keyboard) insets here.
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, b;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = in.getInsets(android.view.WindowInsets.Type.ime());
                l = bars.left; t = bars.top; r = bars.right; b = Math.max(bars.bottom, ime.bottom);
            } else { l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop(); r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom(); }
            if (c.insets.left != l || c.insets.top != t || c.insets.right != r || c.insets.bottom != b) {
                c.insets.set(l, t, r, b);
                for (Screen s : stack) pad(s);
            }
            return in;
        });
    }

    /** Pads a page frame by the insets, or lets an edge-to-edge page handle them. */
    private void pad(Screen s) {
        if (s.edgeToEdge()) { s.view.setPadding(0, 0, 0, 0); s.onInsets(); }
        else s.view.setPadding(c.insets.left, c.insets.top, c.insets.right, c.insets.bottom);
    }

    /** Status bar icon color for the page now on top. */
    private void bars(Screen s) { if (s != null) c.lightBars(s.darkStatusIcons()); }

    public Screen top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    private Screen build(Screen s) {
        s.attach(c);
        View v = s.create();
        FrameLayout frame = new FrameLayout(c.a);
        frame.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        s.view = frame;
        pad(s);
        return s;
    }

    public void push(Screen next) {
        if (busy) { pending = () -> push(next); return; }
        busy = true;
        Screen prev = top();
        build(next);
        stack.add(next);
        View v = next.view;
        root.addView(v, root.getChildCount() - snacks());
        bars(next);
        float shift = c.dp(48);
        if (prev == null) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(Ui.D_MED).withEndAction(() -> done(next)).start();
            return;
        }
        v.setAlpha(0f); v.setTranslationX(shift);
        v.animate().alpha(1f).translationX(0).setDuration(Ui.D_SLOW).setInterpolator(Ui.EMPH).withEndAction(() -> done(next)).start();
        View pv = prev.view;
        pv.animate().alpha(0f).translationX(-shift * 0.4f).setDuration(Ui.D_MED).setInterpolator(Ui.STD)
            .withEndAction(() -> pv.setVisibility(View.GONE)).start();
    }

    public boolean pop() {
        if (busy) { pending = this::pop; return true; }
        if (stack.size() < 2) return false;
        busy = true;
        Screen gone = stack.remove(stack.size() - 1);
        Screen back = top();
        View gv = gone.view, bv = back.view;
        float shift = c.dp(48);
        bars(back);
        bv.setVisibility(View.VISIBLE); bv.setAlpha(0f); bv.setTranslationX(-shift * 0.4f);
        bv.animate().alpha(1f).translationX(0).setDuration(Ui.D_SLOW).setInterpolator(Ui.EMPH).start();
        gv.animate().alpha(0f).translationX(shift).setDuration(Ui.D_MED).setInterpolator(Ui.EXIT).withEndAction(() -> {
            root.removeView(gv); gone.onDestroy(); back.onReturn(); finish();
        }).start();
        return true;
    }

    /**
     * Back to {@code target} in one step: the screens between it and the top are removed without animation, the top one
     * leaves like a normal pop. No-op when {@code target} is not on the stack or already on top.
     */
    public void popTo(Screen target) {
        if (busy) { pending = () -> popTo(target); return; }
        int i = stack.indexOf(target);
        if (i < 0 || i == stack.size() - 1) return;
        while (stack.size() > i + 2) {
            Screen s = stack.remove(i + 1);
            root.removeView(s.view);
            s.onDestroy();
        }
        pop();
    }

    public void replace(Screen next) {
        if (busy) { pending = () -> replace(next); return; }
        busy = true;
        Screen old = stack.isEmpty() ? null : stack.remove(stack.size() - 1);
        build(next);
        stack.add(next);
        View v = next.view;
        root.addView(v, root.getChildCount() - snacks());
        bars(next);
        v.setAlpha(0f); v.setScaleX(0.97f); v.setScaleY(0.97f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(40).setDuration(Ui.D_SLOW).setInterpolator(Ui.EMPH).withEndAction(() -> done(next)).start();
        if (old != null) {
            View ov = old.view;
            ov.animate().alpha(0f).scaleX(1.02f).scaleY(1.02f).setDuration(Ui.D_FAST).setInterpolator(Ui.EXIT).withEndAction(() -> {
                root.removeView(ov); old.onDestroy();
            }).start();
        }
    }

    /** Snackbars stay above pages: new pages are inserted below them. */
    private int snacks() { int n = 0; for (int i = 0; i < root.getChildCount(); i++) if (root.getChildAt(i).getTag() != null) n++; return n; }

    private void done(Screen s) { s.onShown(); finish(); }

    private void finish() {
        busy = false;
        Runnable r = pending; pending = null;
        if (r != null) r.run();
    }

    public void destroyAll() {
        for (Screen s : stack) s.onDestroy();
        stack.clear(); root.removeAllViews();
    }
}
