package app.kolay.app.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import app.kolay.shell.Host;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Everything a screen needs: host services, theme, navigation, a UI handler and a background pool. */
public final class Ctx {
    public final Host host;
    public final Activity a;
    public final Palette p;
    public final Handler ui = new Handler(Looper.getMainLooper());
    public final ExecutorService io = Executors.newFixedThreadPool(3);
    public Nav nav;
    /** The activity's content root (screens, snackbars). Set by {@link Nav}. */
    public android.widget.FrameLayout root;
    /** Current system bar + keyboard insets in px (top: status bar, bottom: navigation bar or keyboard). */
    public final android.graphics.Rect insets = new android.graphics.Rect();

    public Ctx(Host host, Palette p) { this.host = host; this.a = host.activity(); this.p = p; }

    /** Status bar icons dark (for light backgrounds) or light (over the home hero / dark theme). */
    public void lightBars(boolean darkIcons) {
        android.view.View d = a.getWindow().getDecorView();
        int f = d.getSystemUiVisibility() & ~(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (darkIcons) f |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!p.dark) f |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(f);
    }

    public int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics())); }
}
