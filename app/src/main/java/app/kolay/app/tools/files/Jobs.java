package app.kolay.app.tools.files;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Sheet;
import app.kolay.app.ui.Ui;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * Runs long operations one at a time on their own thread (later ones wait in a queue), with a progress sheet (cancel and
 * "Arka planda sürdür" buttons) and the conflict question. The sheet can be hidden while the job keeps running; {@link #chip}
 * shows the state on a screen and reopens the sheet. All state and all listeners live on the main thread.
 */
public final class Jobs {
    private static final ExecutorService RUN = Executors.newSingleThreadExecutor();
    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static Job cur;
    private static boolean resumed;   // the activity is in the foreground (set by Entry); conflicts are not asked otherwise

    private Jobs() {}

    /** Called by the activity's onResume / onPause. */
    public static void resumed(boolean r) { resumed = r; }

    /** A job is running (or waiting). */
    static boolean busy() { return cur != null; }

    /** e.g. "Kopyalanıyor · %42 · Sırada: 2"; "" when idle. */
    static String status() {
        Job j = cur;
        if (j == null) return "";
        String s = j.title;
        if (j.total > 0) s += " · %" + (int) Math.min(100, j.done * 100 / j.total);
        if (!QUEUE.isEmpty()) s += " · Sırada: " + QUEUE.size();
        return s;
    }

    /** {@code r} runs on the main thread whenever the state or progress changes. Always {@link #unlisten} (screen onDestroy). */
    static void listen(Runnable r) { if (!LISTENERS.contains(r)) LISTENERS.add(r); }

    static void unlisten(Runnable r) { LISTENERS.remove(r); }

    /** A listener that runs {@code r} each time the queue becomes idle (a job ended). */
    static Runnable onIdle(Runnable r) {
        boolean[] was = {busy()};
        return () -> { boolean b = busy(); if (was[0] && !b) r.run(); was[0] = b; };
    }

    private static void changed() { for (Runnable r : new ArrayList<>(LISTENERS)) r.run(); }

    /** A small status surface (hidden while idle); tapping it reopens the progress sheet. Listens only while attached. */
    static View chip(Ctx c) {
        TextView t = Ui.text(c, "", 14, c.p.onPrimarySoft, 600);
        t.setGravity(Gravity.CENTER); t.setSingleLine(true); t.setClickable(true);
        t.setPadding(c.dp(16), 0, c.dp(16), 0);
        Ui.surface(c, t, c.p.primarySoft, 16, false);
        t.setVisibility(View.GONE);
        Runnable refresh = () -> {
            boolean b = busy();
            if (b) t.setText(status());
            t.setVisibility(b ? View.VISIBLE : View.GONE);
        };
        t.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { listen(refresh); refresh.run(); }
            @Override public void onViewDetachedFromWindow(View v) { unlisten(refresh); }
        });
        t.setOnClickListener(v -> { if (cur != null) cur.showSheet(c); });
        t.setLayoutParams(Ui.lp(-1, c.dp(44), c.dp(Ui.GUTTER), 0, c.dp(Ui.GUTTER), c.dp(8)));
        return t;
    }

    /**
     * @param work   the operation (worker thread)
     * @param verb   past participle for the summary, e.g. "kopyalandı"
     * @param after  UI thread, when finished (also after cancel/errors) and only if the activity is alive, e.g. reload the folder
     */
    static void run(Ctx c, String title, String verb, BiFunction<Ops.Progress, Ops.Resolver, Ops.Result> work, Runnable after) {
        run(c, title, verb, work, after, false);
    }

    /** @param quiet no summary toast on success (the caller shows the result itself, e.g. opens the file) */
    static void run(Ctx c, String title, String verb, BiFunction<Ops.Progress, Ops.Resolver, Ops.Result> work, Runnable after, boolean quiet) {
        Job j = new Job(c, title, verb, work, after, quiet);
        if (cur == null) j.start();
        else { QUEUE.add(j); Files.toast(c, "Sıraya alındı"); changed(); }
    }

    private static final class Job {
        final Ctx c;
        final String title, verb;
        final BiFunction<Ops.Progress, Ops.Resolver, Ops.Result> work;
        final Runnable after;
        final boolean quiet;
        final AtomicBoolean cancel = new AtomicBoolean();   // set on the UI thread, read by the worker
        final Object[] last = new Object[3];                // name, done, total (latest; read on the UI thread)
        boolean posted, finished;
        long done, total;
        // the sheet, while shown (null when hidden)
        Sheet s; Ctx sc; TextView name, pct, stop; View fill;

        Job(Ctx c, String title, String verb, BiFunction<Ops.Progress, Ops.Resolver, Ops.Result> work, Runnable after, boolean quiet) {
            this.c = c; this.title = title; this.verb = verb; this.work = work; this.after = after; this.quiet = quiet;
        }

        void cancelIt() {
            cancel.set(true);
            if (stop != null) { stop.setText("İptal ediliyor…"); stop.setEnabled(false); }
        }

        void hideSheet() {
            Sheet x = s;
            s = null; sc = null; name = null; pct = null; stop = null; fill = null;   // no view of a gone activity is kept
            if (x != null) x.dismiss();
        }

        void showSheet(Ctx cc) {
            if (s != null || finished || cc.a.isDestroyed()) return;
            Sheet sh = new Sheet(cc, title);
            name = sh.text("Hazırlanıyor…");
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            FrameLayout track = new FrameLayout(cc.a);
            track.setBackground(Ui.rounded(cc, cc.p.surface2, 4));
            fill = new View(cc.a);
            fill.setBackground(Ui.rounded(cc, cc.p.primary, 4));
            fill.setPivotX(0);
            track.addView(fill, new FrameLayout.LayoutParams(-1, -1));
            sh.body.addView(track, Ui.lp(-1, cc.dp(8), 0, 0, 0, cc.dp(8)));
            pct = Ui.text(cc, "", 13, cc.p.muted, 400);
            sh.body.addView(pct, Ui.lp(-1, -2, 0, 0, 0, cc.dp(10)));
            TextView bg = Ui.button(cc, "Arka planda sürdür", Ui.Style.GHOST, null);
            bg.setOnClickListener(v -> hideSheet());
            sh.body.addView(bg, Ui.lp(-1, cc.dp(Ui.H_GHOST), 0, 0, 0, cc.dp(8)));
            stop = Ui.button(cc, "İptal", Ui.Style.TONAL, null);
            stop.setOnClickListener(v -> cancelIt());
            sh.body.addView(stop, Ui.lp(-1, cc.dp(Ui.H_GHOST)));
            sh.d.setCancelable(false);   // back = cancel; the sheet stays until the worker has stopped
            sh.d.setOnKeyListener((d, code, e) -> {
                if (code != android.view.KeyEvent.KEYCODE_BACK) return false;
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) cancelIt();
                return true;
            });
            s = sh; sc = cc;
            sh.show();
            paint();
        }

        void paint() {
            synchronized (last) { posted = false; }
            if (sc != null && sc.a.isDestroyed()) { s = null; sc = null; name = null; pct = null; stop = null; fill = null; }
            synchronized (last) {
                if (last[0] != null && name != null) name.setText((String) last[0]);
                done = last[1] == null ? 0 : (Long) last[1]; total = last[2] == null ? 0 : (Long) last[2];
                if (fill != null) fill.setScaleX(total > 0 ? Math.min(1f, done / (float) total) : 0);
                if (pct != null) pct.setText(total > 0 ? Files.size(done) + " / " + Files.size(total) : "");
                TransferService.progress((String) last[0], done, total);
            }
            changed();
        }

        void start() {
            cur = this;
            showSheet(c);
            // Longer jobs continue with the screen off / app in the background (foreground service with its own İptal).
            c.ui.postDelayed(() -> { if (!finished) TransferService.begin(c.a, title, () -> c.ui.post(() -> { if (!finished) cancelIt(); })); }, 1000);
            Ops.Progress p = new Ops.Progress() {
                long lastPost;
                @Override public void update(String n, long d, long t) {
                    synchronized (last) {
                        last[0] = n; last[1] = d; last[2] = t;
                        long now = System.currentTimeMillis();
                        if (posted || now - lastPost < 80) return;
                        posted = true; lastPost = now;
                    }
                    c.ui.post(Job.this::paint);
                }
                @Override public boolean cancelled() { return cancel.get(); }
            };
            Ops.Resolver r = new Ops.Resolver() {
                Ops.Conflict all;
                @Override public Ops.Conflict resolve(FileItem ex, FileItem in) {
                    if (all != null) return all;
                    Ops.Conflict[] pick = {null};
                    boolean[] remember = {false};
                    CountDownLatch l = new CountDownLatch(1);
                    c.ui.post(() -> {
                        if (!resumed || c.a.isDestroyed()) { pick[0] = Ops.Conflict.KEEP_BOTH; l.countDown(); return; }   // nobody to ask: keep both, lose nothing
                        ask(c, ex, in, (k, rem) -> { pick[0] = k; remember[0] = rem; l.countDown(); });
                    });
                    try {
                        // The sheet can vanish without an answer (activity re-created, notification "İptal"): never wait forever,
                        // or the single worker and the foreground service would stay stuck.
                        while (!l.await(500, TimeUnit.MILLISECONDS)) {
                            if (cancel.get()) break;
                            if (c.a.isDestroyed()) { pick[0] = Ops.Conflict.KEEP_BOTH; break; }   // nobody to ask: keep both, lose nothing
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); cancel.set(true); }
                    Ops.Conflict k = pick[0];
                    if (k == null) { cancel.set(true); return Ops.Conflict.SKIP; }
                    if (remember[0]) all = k;
                    return k;
                }
            };
            RUN.execute(() -> {
                Ops.Result res;
                try { res = work.apply(p, r); }
                catch (RuntimeException e) { res = new Ops.Result(); res.errors.add(String.valueOf(e.getMessage())); }
                Ops.Result fr = res;
                c.ui.post(() -> finish(fr));
            });
            changed();
        }

        void finish(Ops.Result fr) {
            finished = true;
            Job next = QUEUE.poll();
            cur = null;
            if (next == null) TransferService.end();   // with a job waiting the service just carries on
            hideSheet();
            if (!c.a.isDestroyed()) {   // activity re-created meanwhile (e.g. dark mode switch): its pool and views are gone
                if (!fr.errors.isEmpty()) {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < fr.errors.size() && i < 15; i++) b.append("• ").append(fr.errors.get(i)).append('\n');
                    if (fr.errors.size() > 15) b.append("… ve ").append(fr.errors.size() - 15).append(" hata daha");
                    Sheet.info(c, fr.done > 0 ? fr.done + " öğe " + verb + ", bazıları yapılamadı" : "İşlem yapılamadı", b.toString().trim());
                } else if (fr.cancelled) Files.toast(c, "İptal edildi" + (fr.done > 0 ? " (" + fr.done + " öğe " + verb + ")" : ""));
                else if (!quiet) Files.toast(c, fr.done + " öğe " + verb + (fr.skipped > 0 ? ", " + fr.skipped + " atlandı" : ""));
                after.run();
            }
            if (next != null) { if (cur == null) next.start(); else QUEUE.addFirst(next); }   // after() may itself have started a job
            changed();
        }
    }

    interface Answer { void pick(Ops.Conflict k, boolean all); }

    private static void ask(Ctx c, FileItem ex, FileItem in, Answer a) {
        Sheet s = new Sheet(c, "“" + ex.name + "” zaten var");
        boolean[] answered = {false}, all = {false};
        s.text(ex.dir ? "Hedefte aynı adlı bir klasör var. Birleştirirsen içindeki aynı adlı dosyalar için ayrıca sorulur."
            : "Hedefteki: " + Files.size(ex.size) + ", " + Files.date(ex.modified) + "\nGelen: " + Files.size(in.size) + ", " + Files.date(in.modified));
        s.toggle("Hepsine uygula", false, v -> all[0] = v);
        Ops.Conflict[] ks = {Ops.Conflict.REPLACE, Ops.Conflict.SKIP, Ops.Conflict.KEEP_BOTH};
        String[] ls = {ex.dir ? "Birleştir" : "Değiştir", "Atla", "İkisini de tut"};
        Icon.Kind[] is = {Icon.Kind.REFRESH, Icon.Kind.CHEVRON, Icon.Kind.COPY};
        for (int i = 0; i < ks.length; i++) {
            Ops.Conflict k = ks[i];
            s.row(ls[i], is[i], false, () -> { answered[0] = true; a.pick(k, all[0]); });
        }
        s.d.setCanceledOnTouchOutside(false);
        s.d.setOnDismissListener(d -> { if (!answered[0]) { answered[0] = true; a.pick(null, false); } });   // back = cancel the job
        s.show();
        if (!s.d.isShowing() && !answered[0]) { answered[0] = true; a.pick(null, false); }   // activity gone: cancel, never leave the worker waiting
    }
}
