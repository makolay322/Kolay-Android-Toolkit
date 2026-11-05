package app.kolay.app.tools.netscan;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import app.kolay.app.ui.Ctx;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * mDNS discovery of eSCL scanners ({@code _uscan._tcp}, {@code _uscans._tcp}) with NsdManager. No permission and no
 * multicast lock needed (the system's mDNS daemon does the multicast). Services are resolved one at a time (before
 * Android 14 a second concurrent resolve fails). Callbacks are moved to the main thread; a stop invalidates the ones
 * still in flight. Only local-network addresses ({@link Lan}) are listed.
 */
final class Discovery {
    private static final String[] TYPES = {"_uscan._tcp", "_uscans._tcp"};

    private final Ctx c;
    private final NsdManager nsd;
    private final Runnable onChange;
    /** Main thread. */
    final List<Device> found = new ArrayList<>();
    private final Map<String, String> services = new HashMap<>();   // "s/name" → device key
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private boolean running, resolving;
    private int gen;

    Discovery(Ctx c, Runnable onChange) {
        this.c = c;
        this.onChange = onChange;
        nsd = (NsdManager) c.a.getSystemService(Context.NSD_SERVICE);
    }

    boolean running() { return running; }

    void start() {
        if (running || nsd == null) return;
        running = true;
        final int g = ++gen;
        for (String t : TYPES) {
            NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String type, int err) { }
                @Override public void onStopDiscoveryFailed(String type, int err) { }
                @Override public void onDiscoveryStarted(String type) { }
                @Override public void onDiscoveryStopped(String type) { }
                @Override public void onServiceFound(NsdServiceInfo si) { c.ui.post(() -> { if (g == gen) { queue.add(si); pump(); } }); }
                @Override public void onServiceLost(NsdServiceInfo si) { c.ui.post(() -> { if (g == gen) lost(si); }); }
            };
            try { nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l); listeners.add(l); }
            catch (RuntimeException ignored) { }
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        gen++;
        for (NsdManager.DiscoveryListener l : listeners) {
            try { nsd.stopServiceDiscovery(l); } catch (RuntimeException ignored) { }   // e.g. start had failed
        }
        listeners.clear();
        queue.clear();
        resolving = false;
    }

    @SuppressWarnings("deprecation")   // resolveService: deprecated in API 34, the replacement is 34+ only
    private void pump() {
        if (resolving || !running || queue.isEmpty()) return;
        resolving = true;
        NsdServiceInfo si = queue.poll();
        final int g = gen;
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int err) {
                    c.ui.post(() -> {
                        if (g != gen) return;
                        resolving = false;
                        if (err == NsdManager.FAILURE_ALREADY_ACTIVE) c.ui.postDelayed(() -> { if (g == gen) { queue.add(si); pump(); } }, 400);
                        else pump();
                    });
                }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    c.ui.post(() -> {
                        if (g != gen) return;
                        resolving = false;
                        merge(info);
                        pump();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }

    private static String id(NsdServiceInfo si) {
        String t = si.getServiceType() == null ? "" : si.getServiceType();
        return (t.contains("_uscans") ? "s/" : "/") + si.getServiceName();
    }

    private void merge(NsdServiceInfo si) {
        InetAddress addr = address(si);
        if (addr == null || !Lan.isLan(addr) || si.getPort() <= 0) return;
        Map<String, byte[]> txt = si.getAttributes();
        String uuid = txt(txt, "UUID"), ty = txt(txt, "ty"), rs = txt(txt, "rs");
        String key = uuid != null && !uuid.isEmpty() ? "uuid:" + uuid.toLowerCase(Locale.ROOT).replace("urn:uuid:", "") : "nsd:" + si.getServiceName();
        Device d = null;
        for (Device x : found) if (x.key.equals(key)) d = x;
        if (d == null) { d = new Device(key, false); found.add(d); }
        d.name = ty != null && !ty.isEmpty() ? ty : si.getServiceName();
        d.host = addr.getHostAddress();
        if (id(si).startsWith("s/")) d.httpsPort = si.getPort(); else d.httpPort = si.getPort();
        d.rs = Device.cleanRs(rs);   // absent → "eSCL"
        services.put(id(si), key);
        onChange.run();
    }

    private void lost(NsdServiceInfo si) {
        String key = services.remove(id(si));
        if (key == null || services.containsValue(key)) return;
        for (int i = found.size() - 1; i >= 0; i--) if (found.get(i).key.equals(key)) found.remove(i);
        onChange.run();
    }

    @SuppressWarnings("deprecation")   // getHost: deprecated in API 34 in favour of getHostAddresses
    private static InetAddress address(NsdServiceInfo si) {
        if (Build.VERSION.SDK_INT >= 34) {
            InetAddress v6 = null;
            for (InetAddress a : si.getHostAddresses()) {
                if (!Lan.isLan(a)) continue;
                if (a instanceof Inet4Address) return a;
                if (v6 == null) v6 = a;
            }
            return v6;
        }
        return si.getHost();
    }

    /** A TXT record value (key case-insensitive); null when absent. */
    private static String txt(Map<String, byte[]> m, String name) {
        if (m == null) return null;
        for (Map.Entry<String, byte[]> e : m.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue() == null ? "" : new String(e.getValue(), StandardCharsets.UTF_8).trim();
        }
        return null;
    }
}
