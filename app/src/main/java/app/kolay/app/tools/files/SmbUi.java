package app.kolay.app.tools.files;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.kolay.app.tools.netscan.Lan;
import app.kolay.app.ui.Ctx;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Sheet;
import app.kolay.app.ui.Ui;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** "Ağ" on the Dosyalar start page: saved Samba servers, the add/edit form and the LAN search. */
final class SmbUi {
    private SmbUi() {}

    /** Adds the section (label, one row per server, "Sunucu ekle", "Ağda ara") to {@code col}; {@code refresh} rebuilds the page. */
    static void section(Ctx c, LinearLayout col, Runnable refresh) {
        col.addView(Ui.label(c, "AĞ"), Ui.lp(-1, -2, c.dp(4), c.dp(14), 0, c.dp(8)));
        for (SmbSource.Server s : SmbServers.all(c)) {
            String sub = "smb://" + s.host + (s.port != 445 ? ":" + s.port : "") + " · " + (s.guest ? "misafir" : s.user.isEmpty() ? "kullanıcısız" : s.user);
            LinearLayout r = Ui.navRow(c, Icon.Kind.NETSCAN, s.name, sub, 2, () -> c.nav.push(new BrowserScreen(SmbServers.source(s), "/", s.name)));
            r.setOnLongClickListener(v -> {
                Sheet.menu(c, s.name, new String[]{"Düzenle", "Sil"}, new Icon.Kind[]{Icon.Kind.SETTINGS, Icon.Kind.TRASH}, k -> {
                    if (k == 0) form(c, s, null, null, refresh);
                    else Sheet.confirm(c, "Sunucu silinsin mi?", "“" + s.name + "” ve kayıtlı parolası telefondan silinir. Sunucudaki dosyalara dokunulmaz.", "Sil", true,
                        () -> { SmbServers.delete(c, s.id); refresh.run(); });
                });
                return true;
            });
            col.addView(r, Ui.lp(-1, c.dp(68), 0, 0, 0, c.dp(Ui.GAP)));
        }
        LinearLayout row = new LinearLayout(c.a);
        TextView add = Ui.button(c, "Sunucu ekle", Ui.Style.TONAL, Icon.Kind.PLUS);
        add.setOnClickListener(v -> form(c, null, null, null, refresh));
        TextView find = Ui.button(c, "Ağda ara", Ui.Style.TONAL, Icon.Kind.SEARCH);
        find.setOnClickListener(v -> discover(c, refresh));
        row.addView(add, new LinearLayout.LayoutParams(0, c.dp(Ui.H_GHOST), 1));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, c.dp(Ui.H_GHOST), 1);
        fl.setMarginStart(c.dp(Ui.GAP));
        row.addView(find, fl);
        col.addView(row, Ui.lp(-1, -2));
    }

    // ------------------------------------------------------------ add / edit

    /** The server form: {@code s} to edit, or null with an optional {@code name}/{@code host} from the search. */
    private static void form(Ctx c, SmbSource.Server s, String name, String host, Runnable refresh) {
        Sheet sh = new Sheet(c, s == null ? "Samba sunucusu ekle" : "Sunucuyu düzenle");
        int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        EditText eName = field(c, sh, "Ad (ör. NAS)", text, s != null ? s.name : name);
        EditText eHost = field(c, sh, "Adres (ör. 192.168.1.10 ya da nas.local)", text | InputType.TYPE_TEXT_VARIATION_URI, s != null ? s.host : host);
        EditText ePort = field(c, sh, "Port (boş = 445)", InputType.TYPE_CLASS_NUMBER, s != null && s.port != 445 ? String.valueOf(s.port) : "");
        EditText eUser = field(c, sh, "Kullanıcı adı", text, s != null ? s.user : "");
        EditText ePass = field(c, sh, s != null && !s.guest ? "Parola (değiştirmek için yaz)" : "Parola", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
        EditText eDom = field(c, sh, "Etki alanı (isteğe bağlı)", text, s != null ? s.domain : "");
        EditText eShares = field(c, sh, "Paylaşımlar (isteğe bağlı, virgülle; liste gelmezse)", text, s != null ? String.join(", ", s.shares) : "");
        boolean[] guest = {s != null && s.guest};
        View[] cred = {eUser, ePass, eDom};
        sh.toggle("Misafir olarak bağlan", guest[0], v -> { guest[0] = v; for (View x : cred) x.setVisibility(v ? View.GONE : View.VISIBLE); });
        for (View x : cred) x.setVisibility(guest[0] ? View.GONE : View.VISIBLE);
        TextView msg = Ui.text(c, "Parola telefonda şifreli saklanır. Yalnızca yerel ağdaki ya da Tailscale'deki sunuculara bağlanılır (Tailscale adresi elle girilir); SMB 2 ve 3 desteklenir (SMB1 güvensiz olduğu için yok).", 13, c.p.muted, 400);
        sh.body.addView(msg, Ui.lp(-1, -2, c.dp(4), c.dp(4), 0, c.dp(4)));
        boolean[] busy = {false}, force = {false};
        sh.actions("Vazgeç", "Kaydet", false, () -> {
            if (busy[0]) return;
            String h = eHost.getText().toString().trim(), extraShare = null;
            if (h.toLowerCase(Locale.ROOT).startsWith("smb://")) h = h.substring(6);
            h = h.replace('\\', '/');
            while (h.startsWith("/")) h = h.substring(1);
            int slash = h.indexOf('/');
            if (slash >= 0) { String rest = h.substring(slash + 1); extraShare = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest; h = h.substring(0, slash); }
            int port = 445;
            String ps = ePort.getText().toString().trim();
            if (h.matches("[^:\\[\\]]+:\\d+")) { ps = h.substring(h.indexOf(':') + 1); h = h.substring(0, h.indexOf(':')); }
            if (h.isEmpty()) { error(c, msg, "Adres gerekli"); return; }
            if (!ps.isEmpty()) {
                try { port = Integer.parseInt(ps); } catch (NumberFormatException e) { port = -1; }
                if (port < 1 || port > 65535) { error(c, msg, "Port 1–65535 arasında olmalı"); return; }
            }
            List<String> shares = new ArrayList<>();
            for (String x : eShares.getText().toString().split(",")) { String t = x.trim().replace("/", ""); if (!t.isEmpty() && !shares.contains(t)) shares.add(t); }
            if (extraShare != null && !extraShare.isEmpty() && !shares.contains(extraShare)) shares.add(extraShare);
            String n = eName.getText().toString().trim();
            if (n.isEmpty()) n = h;
            String pass = ePass.getText().toString();
            String passToSave = s != null && !s.guest && pass.isEmpty() ? null : pass;       // empty while editing: keep the stored one
            String passToTry = passToSave == null ? s.password : pass;
            SmbSource.Server trial = new SmbSource.Server("trial", n, h, port, eUser.getText().toString().trim(), guest[0] ? null : passToTry,
                eDom.getText().toString().trim(), guest[0], shares);
            Runnable save = () -> {
                SmbServers.save(c, s == null ? null : s.id, trial.name, trial.host, trial.port, trial.user, guest[0] ? null : passToSave, trial.domain, guest[0], trial.shares);
                sh.dismiss();
                refresh.run();
            };
            if (force[0]) { save.run(); return; }
            busy[0] = true;
            error(c, msg, null);
            msg.setText("Bağlanılıyor…");
            c.io.execute(() -> {
                String res; boolean policy = false;
                try (SmbSource t = new SmbSource(trial, Lan::isLanOrTailscale)) { res = t.check(); }
                catch (SmbSource.Fatal e) { res = e.getMessage(); policy = true; }
                catch (IOException e) { res = null; String m = e.getMessage(); force[0] = true; c.ui.post(() -> error(c, msg, m + "\nYine de kaydetmek için Kaydet'e tekrar dokun.")); }
                String fr = res; boolean fp = policy;
                c.ui.post(() -> {
                    busy[0] = false;
                    if (fp) { force[0] = false; error(c, msg, fr); return; }   // public address / unknown name: never saved
                    if (fr != null) { Files.toast(c, "Bağlandı: " + fr); save.run(); }
                });
            });
        });
        sh.show();
    }

    private static EditText field(Ctx c, Sheet sh, String hint, int type, String value) {
        EditText e = Ui.input(c, hint, type);
        if (value != null) e.setText(value);
        sh.body.addView(e, Ui.lp(-1, -2, 0, 0, 0, c.dp(8)));
        return e;
    }

    private static void error(Ctx c, TextView msg, String s) {
        if (s == null) { msg.setTextColor(c.p.muted); return; }
        msg.setTextColor(c.p.danger);
        msg.setText(s);
    }

    // ------------------------------------------------------------ LAN search

    private static void discover(Ctx c, Runnable refresh) {
        Sheet sh = new Sheet(c, "Ağdaki Samba sunucuları");
        TextView status = sh.text("Aranıyor… (mDNS ve bu ağdaki 445 numaralı port)");
        Set<String> seen = new HashSet<>();
        SmbDiscovery[] d = {null};
        d[0] = new SmbDiscovery(c, (name, ip) -> {
            if (!seen.add(ip)) return;
            sh.row(name.equals(ip) ? ip : name + " (" + ip + ")", Icon.Kind.NETSCAN, false, () -> form(c, null, name.equals(ip) ? null : name, ip, refresh));
        }, () -> status.setText(seen.isEmpty() ? "Sunucu bulunamadı. Adresi elle ekleyebilirsin." : "Arama bitti. Eklemek için birine dokun."));
        sh.d.setOnDismissListener(x -> d[0].stop());
        sh.show();
        d[0].start();
    }
}
