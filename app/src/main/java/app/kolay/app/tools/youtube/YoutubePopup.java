package app.kolay.app.tools.youtube;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.kolay.app.ui.*;

/** The summary as a bottom sheet over the app that shared the link ("YouTube Özet" in the share sheet). Nothing is stored; closing the sheet finishes the activity. */
final class YoutubePopup {
    private YoutubePopup() {}

    static void show(Ctx c, String shared) {
        Sheet s = new Sheet(c, "YouTube Özeti");
        s.d.setCanceledOnTouchOutside(true);
        boolean[] gone = {false};
        s.d.setOnDismissListener(d -> { gone[0] = true; c.a.finish(); });
        String id = Transcript.videoId(shared == null ? "" : shared);

        LinearLayout statusRow = new LinearLayout(c.a);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        Arc spinner = new Arc(c.a, c.p.primarySoft, c.p.primary, c.dp(2.5f));
        statusRow.addView(spinner, Ui.lp(c.dp(18), c.dp(18), 0, 0, c.dp(10), 0));
        TextView status = Ui.text(c, id == null ? "Bu metinde YouTube bağlantısı yok." : "Transkript alınıyor…", 14, id == null ? c.p.danger : c.p.muted, 500);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        if (id == null) spinner.setVisibility(View.GONE);
        s.body.addView(statusRow, Ui.lp(-1, -2, c.dp(4), 0, c.dp(4), c.dp(12)));

        TextView result = Ui.text(c, "", 15, c.p.ink, 400);
        result.setLineSpacing(0, 1.3f);
        result.setVisibility(View.GONE);
        s.body.addView(result, Ui.lp(-1, -2, c.dp(4), 0, c.dp(4), c.dp(14)));

        TextView copy = Ui.button(c, "Kopyala", Ui.Style.TONAL, Icon.Kind.COPY);
        TextView share = Ui.button(c, "Paylaş", Ui.Style.TONAL, Icon.Kind.SHARE);
        LinearLayout acts = Ui.pair(c, copy, share, Ui.H_BTN);
        acts.setVisibility(View.GONE);
        s.body.addView(acts, Ui.lp(-1, -2));
        TextView close = Ui.button(c, "Kapat", Ui.Style.GHOST, null);
        close.setOnClickListener(v -> s.dismiss());
        s.body.addView(close, Ui.lp(-1, c.dp(Ui.H_GHOST), 0, c.dp(6), 0, 0));
        s.show();
        if (id == null) return;

        c.io.execute(() -> {
            String out = null, err = null;
            try {
                Transcript.Result t = Transcript.fetch(id);
                c.ui.post(() -> { if (!gone[0]) Ui.swapText(status, "Özetleniyor… (" + t.lang + " transkript)"); });
                out = Summary.generate(c.host, t);
                if (out.isEmpty()) err = "Özet üretilemedi";
            } catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final String o = out, e = err;
            c.ui.post(() -> {
                if (gone[0]) return;
                if (e != null) { spinner.setVisibility(View.GONE); status.setTextColor(c.p.danger); Ui.swapText(status, e); return; }
                String plain = Summary.plain(o);
                statusRow.setVisibility(View.GONE);
                result.setText(Summary.render(c, o));
                result.setVisibility(View.VISIBLE); acts.setVisibility(View.VISIBLE);
                Ui.appear(result, 0); Ui.appear(acts, 80);
                copy.setOnClickListener(v -> {
                    ((ClipboardManager) c.a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Özet", plain));
                    Ui.flashCopied(c, copy, Ui.Style.TONAL);
                });
                share.setOnClickListener(v -> c.a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plain), null)));
            });
        });
    }
}
