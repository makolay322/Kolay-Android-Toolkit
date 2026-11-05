package app.kolay.app.ui;

import android.view.View;

/** One navigable page. Created by {@link Nav}; build the view in {@link #create()} using {@link #c}. */
public abstract class Screen {
    protected Ctx c;
    View view;

    final void attach(Ctx c) { this.c = c; }
    public abstract View create();
    /** After the enter transition finished (first time only). Start entrance animations here. */
    public void onShown() {}
    /** Activity resumed / paused while this screen is on top (camera and other exclusive resources belong here). */
    public void onResume() {}
    public void onPause() {}
    /** This screen is on top again because the one above it was popped. */
    public void onReturn() {}
    public void onPermission(int req, String[] perms, int[] results) {}
    /** Result of an activity this screen started with {@code c.a.startActivityForResult} (e.g. the system image picker). */
    public void onActivityResult(int req, int result, android.content.Intent data) {}
    /** @return true if consumed (e.g. cancelled something) instead of leaving the screen. */
    public boolean onBack() { return false; }
    public void onDestroy() {}
}
