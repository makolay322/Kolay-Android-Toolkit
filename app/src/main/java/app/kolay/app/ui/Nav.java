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

    public Nav(Ctx c, FrameLayout root) { this.c = c; this.root = root; c.nav = this; }

    public Screen top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    private Screen build(Screen s) {
        s.attach(c);
        View v = s.create();
        v.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        s.view = v;
        return s;
    }

    public void push(Screen next) {
        if (busy) { pending = () -> push(next); return; }
        busy = true;
        Screen prev = top();
        build(next);
        stack.add(next);
        View v = next.view;
        root.addView(v);
        float shift = c.dp(64);
        if (prev == null) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(240).withEndAction(() -> done(next)).start();
            return;
        }
        v.setAlpha(0f); v.setTranslationX(shift);
        v.animate().alpha(1f).translationX(0).setDuration(360).setInterpolator(Ui.EMPH).withEndAction(() -> done(next)).start();
        View pv = prev.view;
        pv.animate().alpha(0f).translationX(-shift * 0.5f).setDuration(260).setInterpolator(Ui.STD)
            .withEndAction(() -> pv.setVisibility(View.GONE)).start();
    }

    public boolean pop() {
        if (busy) { pending = this::pop; return true; }
        if (stack.size() < 2) return false;
        busy = true;
        Screen gone = stack.remove(stack.size() - 1);
        Screen back = top();
        View gv = gone.view, bv = back.view;
        float shift = c.dp(64);
        bv.setVisibility(View.VISIBLE); bv.setAlpha(0f); bv.setTranslationX(-shift * 0.5f);
        bv.animate().alpha(1f).translationX(0).setDuration(320).setInterpolator(Ui.EMPH).start();
        gv.animate().alpha(0f).translationX(shift).setDuration(240).setInterpolator(Ui.EXIT).withEndAction(() -> {
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
        root.addView(v);
        v.setAlpha(0f); v.setScaleX(0.96f); v.setScaleY(0.96f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(340).setInterpolator(Ui.EMPH).withEndAction(() -> done(next)).start();
        if (old != null) {
            View ov = old.view;
            ov.animate().alpha(0f).scaleX(1.02f).scaleY(1.02f).setDuration(200).setInterpolator(Ui.EXIT).withEndAction(() -> {
                root.removeView(ov); old.onDestroy();
            }).start();
        }
    }

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
