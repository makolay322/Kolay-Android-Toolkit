package app.kolay.app.tools.files;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Ui;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/** Runs one long operation at a time on its own thread, with a progress sheet (cancel button) and the conflict question. */
final class Jobs {
    private static final ExecutorService RUN = Executors.newSingleThreadExecutor();

    private Jobs() {}

    /**
     * @param work   the operation (worker thread)
     * @param verb   past participle for the summary, e.g. "kopyalandı"
     * @param after  UI thread, when finished (also after cancel/errors), e.g. reload the folder
     */
    static void run(Ctx c, String title, String verb, BiFunction<Ops.Progress, Ops.Resolver, Ops.Result> work, Runnable after) {
        Sheet s = new Sheet(c, title);
        TextView name = s.text("Hazırlanıyor…");
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        FrameLayout track = new FrameLayout(c.a);
        track.setBackground(Ui.rounded(c, c.p.surface2, 4));
        View fill = new View(c.a);
        fill.setBackground(Ui.rounded(c, c.p.primary, 4));
        fill.setPivotX(0); fill.setScaleX(0);
        track.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        s.body.addView(track, Ui.lp(-1, c.dp(8), 0, 0, 0, c.dp(8)));
        TextView pct = Ui.text(c, "", 13, c.p.muted, 400);
        s.body.addView(pct, Ui.lp(-1, -2, 0, 0, 0, c.dp(10)));
        TextView stop = Ui.button(c, "İptal", Ui.Style.TONAL, null);
        s.body.addView(stop, Ui.lp(-1, c.dp(Ui.H_GHOST)));

        final boolean[] cancel = {false};
        final Object[] last = new Object[3];   // name, done, total (latest; read on the UI thread)
        final boolean[] posted = {false};
        Runnable cancelIt = () -> { cancel[0] = true; stop.setText("İptal ediliyor…"); stop.setEnabled(false); };
        stop.setOnClickListener(v -> cancelIt.run());
        s.d.setCancelable(false);   // back = cancel; the sheet stays until the worker has stopped
        s.d.setOnKeyListener((d, code, e) -> {
            if (code != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) cancelIt.run();
            return true;
        });
        s.show();

        Runnable paint = () -> {
            synchronized (last) {
                posted[0] = false;
                if (last[0] != null) name.setText((String) last[0]);
                long d = last[1] == null ? 0 : (Long) last[1], t = last[2] == null ? 0 : (Long) last[2];
                fill.setScaleX(t > 0 ? Math.min(1f, d / (float) t) : 0);
                pct.setText(t > 0 ? Files.size(d) + " / " + Files.size(t) : "");
            }
        };
        Ops.Progress p = new Ops.Progress() {
            long lastPost;
            @Override public void update(String n, long d, long t) {
                synchronized (last) {
                    last[0] = n; last[1] = d; last[2] = t;
                    long now = System.currentTimeMillis();
                    if (posted[0] || now - lastPost < 80) return;
                    posted[0] = true; lastPost = now;
                }
                c.ui.post(paint);
            }
            @Override public boolean cancelled() { return cancel[0]; }
        };
        Ops.Resolver r = new Ops.Resolver() {
            Ops.Conflict all;
            @Override public Ops.Conflict resolve(FileItem ex, FileItem in) {
                if (all != null) return all;
                Ops.Conflict[] pick = {null};
                boolean[] remember = {false};
                CountDownLatch l = new CountDownLatch(1);
                c.ui.post(() -> ask(c, ex, in, (k, rem) -> { pick[0] = k; remember[0] = rem; l.countDown(); }));
                try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); cancel[0] = true; }
                if (pick[0] == null) { cancel[0] = true; return Ops.Conflict.SKIP; }
                if (remember[0]) all = pick[0];
                return pick[0];
            }
        };
        RUN.execute(() -> {
            Ops.Result res;
            try { res = work.apply(p, r); }
            catch (RuntimeException e) { res = new Ops.Result(); res.errors.add(String.valueOf(e.getMessage())); }
            Ops.Result fr = res;
            c.ui.post(() -> {
                s.dismiss();
                if (!fr.errors.isEmpty()) {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < fr.errors.size() && i < 15; i++) b.append("• ").append(fr.errors.get(i)).append('\n');
                    if (fr.errors.size() > 15) b.append("… ve ").append(fr.errors.size() - 15).append(" hata daha");
                    Sheet.info(c, fr.done > 0 ? fr.done + " öğe " + verb + ", bazıları yapılamadı" : "İşlem yapılamadı", b.toString().trim());
                } else if (fr.cancelled) Files.toast(c, "İptal edildi" + (fr.done > 0 ? " (" + fr.done + " öğe " + verb + ")" : ""));
                else Files.toast(c, fr.done + " öğe " + verb + (fr.skipped > 0 ? ", " + fr.skipped + " atlandı" : ""));
                after.run();
            });
        });
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
    }
}
