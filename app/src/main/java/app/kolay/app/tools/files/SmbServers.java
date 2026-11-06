package app.kolay.app.tools.files;

import android.util.Base64;
import app.kolay.app.tools.netscan.Lan;
import app.kolay.app.ui.Ctx;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Saved Samba servers. Name, address, user, domain and hand-added shares live in the app's private preferences; the password
 * only in the encrypted secret store ({@code Host.secret}, Android Keystore), under "smb_" + id. One {@link SmbSource} per server
 * is kept for the whole app run, so every screen shares its connection.
 */
final class SmbServers {
    private SmbServers() {}

    private static final Map<String, SmbSource> OPEN = new HashMap<>();

    static List<SmbSource.Server> all(Ctx c) {
        List<SmbSource.Server> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Files.prefs(c.a).getString("smb", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                List<String> shares = new ArrayList<>();
                JSONArray sh = o.optJSONArray("shares");
                if (sh != null) for (int k = 0; k < sh.length(); k++) shares.add(sh.getString(k));
                String id = o.getString("id");
                boolean guest = o.optBoolean("guest");
                out.add(new SmbSource.Server(id, o.optString("name"), o.getString("host"), o.optInt("port", 445), o.optString("user"),
                    guest ? null : password(c, id), o.optString("domain"), guest, shares));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /** Adds or replaces (same id) a server; {@code password} null keeps the stored one. Returns the saved entry. */
    static SmbSource.Server save(Ctx c, String id, String name, String host, int port, String user, String password, String domain, boolean guest, List<String> shares) {
        if (id == null) id = UUID.randomUUID().toString();
        if (guest) c.host.setSecret("smb_" + id, "");
        else if (password != null) c.host.setSecret("smb_" + id, Base64.encodeToString(password.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));   // encoded: the store trims
        List<SmbSource.Server> all = all(c);
        JSONArray a = new JSONArray();
        boolean replaced = false;
        try {
            for (SmbSource.Server s : all) {
                if (s.id.equals(id)) { a.put(json(id, name, host, port, user, domain, guest, shares)); replaced = true; }
                else a.put(json(s.id, s.name, s.host, s.port, s.user, s.domain, s.guest, s.shares));
            }
            if (!replaced) a.put(json(id, name, host, port, user, domain, guest, shares));
        } catch (JSONException e) { throw new IllegalStateException(e); }
        Files.prefs(c.a).edit().putString("smb", a.toString()).apply();
        drop(id);
        for (SmbSource.Server s : all(c)) if (s.id.equals(id)) return s;
        throw new IllegalStateException("not saved");
    }

    static void delete(Ctx c, String id) {
        JSONArray a = new JSONArray();
        try {
            for (SmbSource.Server s : all(c)) if (!s.id.equals(id)) a.put(json(s.id, s.name, s.host, s.port, s.user, s.domain, s.guest, s.shares));
        } catch (JSONException e) { throw new IllegalStateException(e); }
        Files.prefs(c.a).edit().putString("smb", a.toString()).apply();
        c.host.setSecret("smb_" + id, "");
        drop(id);
    }

    /** The shared source for {@code s} (created on first use). */
    static synchronized SmbSource source(SmbSource.Server s) {
        SmbSource src = OPEN.get(s.id);
        if (src == null) { src = new SmbSource(s, Lan::isLan); OPEN.put(s.id, src); }
        return src;
    }

    private static synchronized void drop(String id) {
        SmbSource old = OPEN.remove(id);
        if (old != null) { SmbSource o = old; new Thread(o::close).start(); }   // closing talks to the server: not on the UI thread
    }

    private static String password(Ctx c, String id) {
        String v = c.host.secret("smb_" + id);
        if (v.isEmpty()) return "";
        try { return new String(Base64.decode(v, Base64.NO_WRAP), StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return ""; }
    }

    private static JSONObject json(String id, String name, String host, int port, String user, String domain, boolean guest, List<String> shares) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("name", name).put("host", host).put("port", port).put("user", user).put("domain", domain).put("guest", guest);
        JSONArray sh = new JSONArray();
        for (String s : shares) if (!s.trim().isEmpty()) sh.put(s.trim());
        o.put("shares", sh);
        return o;
    }
}
