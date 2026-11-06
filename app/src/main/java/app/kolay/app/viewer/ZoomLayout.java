package app.kolay.app.viewer;

import android.content.Context;
import android.view.*;
import android.widget.AbsListView;
import android.widget.FrameLayout;

/**
 * Pinch / double-tap zoom around a scrolling list (PDF pages, slides). At 1× the list scrolls normally; zoomed in,
 * drags pan sideways and vertically, and the vertical rest scrolls the list. The child is scaled from its top-left.
 */
final class ZoomLayout extends FrameLayout {
    interface OnZoom { void ended(float scale); }

    static final float MAX = 4f;
    private final ScaleGestureDetector sg;
    private final GestureDetector gd;
    private final int slop;
    private float scale = 1f, tx, ty, lastX, lastY, carry;
    private VelocityTracker vt;
    OnZoom onZoom;

    ZoomLayout(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { zoomTo(scale * d.getScaleFactor(), d.getFocusX(), d.getFocusY()); return true; }
            @Override public void onScaleEnd(ScaleGestureDetector d) { if (onZoom != null) onZoom.ended(scale); }
        });
        sg.setQuickScaleEnabled(false);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomTo(scale > 1.2f ? 1f : 2.5f, e.getX(), e.getY());
                if (onZoom != null) onZoom.ended(scale);
                return true;
            }
        });
    }

    float scale() { return scale; }

    private AbsListView list() { return (AbsListView) getChildAt(0); }

    private void zoomTo(float ns, float fx, float fy) {
        ns = Math.max(1f, Math.min(MAX, ns));
        float f = ns / scale;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        scale = ns;
        clampApply(0);
    }

    /** Clamps the offsets; vertical overflow {@code dy} beyond the edges scrolls the list instead. */
    private void clampApply(float dy) {
        float w = getWidth(), h = getHeight();
        tx = Math.max(w - w * scale, Math.min(0, tx));
        float nty = ty + dy, min = h - h * scale, over = 0;
        if (nty > 0) { over = nty; nty = 0; } else if (nty < min) { over = nty - min; nty = min; }
        ty = nty;
        if (over != 0 && list() != null) {
            carry += -over / scale;
            int step = (int) carry;
            if (step != 0) { list().scrollListBy(step); carry -= step; }
        }
        View ch = getChildAt(0);
        if (ch == null) return;
        ch.setPivotX(0); ch.setPivotY(0);
        ch.setScaleX(scale); ch.setScaleY(scale);
        ch.setTranslationX(tx); ch.setTranslationY(ty);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        sg.onTouchEvent(ev);
        gd.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX = ev.getX(); lastY = ev.getY(); track(ev, true); break;
            case MotionEvent.ACTION_POINTER_DOWN: return true;
            case MotionEvent.ACTION_MOVE:
                track(ev, false);
                if (scale > 1.01f && (Math.abs(ev.getX() - lastX) > slop || Math.abs(ev.getY() - lastY) > slop)) {
                    lastX = ev.getX(); lastY = ev.getY();
                    return true;
                }
                break;
            default: break;
        }
        return sg.isInProgress();
    }

    private void track(MotionEvent ev, boolean reset) {
        if (reset && vt != null) vt.clear();
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        sg.onTouchEvent(ev);
        gd.onTouchEvent(ev);
        track(ev, false);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX = ev.getX(); lastY = ev.getY(); return true;
            case MotionEvent.ACTION_POINTER_UP: {
                int keep = ev.getActionIndex() == 0 ? 1 : 0;
                lastX = ev.getX(keep); lastY = ev.getY(keep);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (!sg.isInProgress() && ev.getPointerCount() == 1) {
                    tx += ev.getX() - lastX;
                    clampApply(ev.getY() - lastY);
                }
                lastX = ev.getX(); lastY = ev.getY();
                break;
            case MotionEvent.ACTION_UP:
                if (vt != null && scale > 1.01f && list() != null) {
                    vt.computeCurrentVelocity(1000);
                    float vy = vt.getYVelocity();
                    if (Math.abs(vy) > ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity()) list().fling((int) (-vy / scale));
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                if (vt != null) { vt.recycle(); vt = null; }
                break;
            default: break;
        }
        return true;
    }
}
