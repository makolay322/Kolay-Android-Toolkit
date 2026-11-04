package app.kolay.shell;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings tile: one tap in the pull-down panel collapses it and opens the QR reader. Holds no logic of its own. */
public class QrTileService extends TileService {
    /** Read by the app (Activity.getIntent) to open its QR screen right after the home screen. */
    static final String ACTION_QR = "app.kolay.action.QR_SCAN";

    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override public void onClick() {
        Intent i = new Intent(this, MainActivity.class).setAction(ACTION_QR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
