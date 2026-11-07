package app.kolay.shell;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;

/**
 * Widget appearance page, opened by the launcher (when placing the widget, and on Android 12+ via long-press → reconfigure).
 * It is the normal UI started on the "Widget görünümü" page (Entry checks the configure action). The result is OK from the
 * start, so leaving with Back keeps the widget (on Android 10/11 a cancelled configure would remove it).
 */
public class PrayerWidgetConfig extends MainActivity {
    @Override protected void onCreate(Bundle saved) {
        Intent in = getIntent();
        int id = in == null ? AppWidgetManager.INVALID_APPWIDGET_ID : in.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
        super.onCreate(saved);
    }
}
