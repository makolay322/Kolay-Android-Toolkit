package app.kolay.app.tools.netscan;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Device-local memory of the network scanner: manual addresses, pinned certificate fingerprints, last settings per scanner. */
final class Store implements EsclClient.Pins {
    private static final int MAX_HOSTS = 8;
    private final SharedPreferences p;

    Store(Context c) { p = c.getSharedPreferences("netscan", Context.MODE_PRIVATE); }

    @Override public String get(String key) { return p.getString("pin:" + key, null); }
    @Override public void put(String key, String fingerprint) { p.edit().putString("pin:" + key, fingerprint).apply(); }
    void forgetPin(String key) { p.edit().remove("pin:" + key).apply(); }

    /** Manual entries, most recent first. */
    List<String> hosts() {
        String v = p.getString("hosts", "");
        List<String> out = new ArrayList<>();
        if (!v.isEmpty()) out.addAll(Arrays.asList(v.split("\n")));
        return out;
    }

    void addHost(String spec) {
        List<String> h = hosts();
        h.remove(spec);
        h.add(0, spec);
        while (h.size() > MAX_HOSTS) h.remove(h.size() - 1);
        p.edit().putString("hosts", String.join("\n", h)).apply();
    }

    void removeHost(String spec) {
        List<String> h = hosts();
        h.remove(spec);
        p.edit().putString("hosts", String.join("\n", h)).apply();
    }

    /** "source,dpi,mode,letter" as saved by {@link #saveSettings}; null when none. */
    String settings(String key) { return p.getString("set:" + key, null); }
    void saveSettings(String key, String v) { p.edit().putString("set:" + key, v).apply(); }
}
