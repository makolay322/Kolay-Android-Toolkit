package app.kolay.app.tools.files;

import app.kolay.app.ui.Ctx;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Runs the shared clipboard ({@link Files#clip}) into a folder: reads and clears it, then copies or moves as a job. */
final class Paste {
    private Paste() {}

    /** @param after UI thread, when the job ended (also after cancel/errors) */
    static void run(Ctx c, FileSource dst, String dir, Runnable after) {
        List<String> what = new ArrayList<>(Files.clip);
        boolean cut = Files.clipCut;
        FileSource from = Files.clipSrc;
        Files.clip.clear();
        Jobs.run(c, cut ? "Taşınıyor" : "Kopyalanıyor", cut ? "taşındı" : "kopyalandı",
            (p, r) -> Ops.transfer(from, what, dst, dir, cut, r, p),
            () -> {
                List<String> changed = new ArrayList<>(localOnly(from, what));
                changed.addAll(localOnly(dst, Collections.singletonList(dir)));
                Files.rescan(c.a, changed);
                after.run();
            });
    }

    /** Local paths among {@code paths} (for the media index; network paths are not indexed). */
    static List<String> localOnly(FileSource s, List<String> paths) { return s == LocalSource.INSTANCE ? paths : new ArrayList<>(); }
}
