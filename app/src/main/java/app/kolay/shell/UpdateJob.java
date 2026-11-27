package app.kolay.shell;

import android.app.job.*;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import app.kolay.app.tools.files.TransferService;

/**
 * Periodic background check: downloads a new release and, while the app is not on screen, installs it (Android 12+
 * lets an app update itself without a prompt; if Android still wants a confirmation, a notification asks for it).
 */
public class UpdateJob extends JobService {
    private static final int ID = 4711;

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null || js.getPendingJob(ID) != null) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(12L * 3600 * 1000)
                .setPersisted(true).build());   // survives a reboot (RECEIVE_BOOT_COMPLETED is declared)
        } catch (Throwable ignored) { }
    }

    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> {
            try {
                Context c = getApplicationContext();
                Updater.check(c, false);
                if (Build.VERSION.SDK_INT >= 31 && !MainActivity.foreground && !TransferService.running() && Updater.ready(c)
                        && c.getPackageManager().canRequestPackageInstalls()) Updater.install(c, true);
            } catch (Throwable ignored) { }
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return false; }
}
