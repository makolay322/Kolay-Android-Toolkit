package app.kolay.app.tools.files;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import app.kolay.app.tools.netscan.Lan;
import app.kolay.app.ui.Ctx;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Enumeration;
import java.util.function.BiConsumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds Samba servers on the local network, lightly: mDNS {@code _smb._tcp} (NsdManager, like the scanner discovery) plus a
 * quick TCP probe of port 445 on the phone's own /24 (Wi-Fi/Ethernet, private addresses only). No permission needed.
 * Results arrive on the UI thread as (name, address); {@link #stop} cancels everything.
 */
final class SmbDiscovery {
    private static final int PROBE_MS = 350, THREADS = 32;

    private final Ctx c;
    private final BiConsumer<String, String> found;
    private final Runnable done;
    private final NsdManager nsd;
    private NsdManager.DiscoveryListener listener;
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving;
    private volatile boolean stopped;
    private ExecutorService pool;

    SmbDiscovery(Ctx c, BiConsumer<String, String> found, Runnable done) {
        this.c = c; this.found = found; this.done = done;
        nsd = (NsdManager) c.a.getSystemService(Context.NSD_SERVICE);
    }

    void start() {
        if (nsd != null) {
            listener = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int e) { }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceFound(NsdServiceInfo si) { c.ui.post(() -> { if (!stopped) { queue.add(si); pump(); } }); }
                @Override public void onServiceLost(NsdServiceInfo si) { }
            };
            try { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener); } catch (RuntimeException e) { listener = null; }
        }
        pool = Executors.newFixedThreadPool(THREADS);
        byte[] base = ownSubnet();
        if (base != null) {
            for (int i = 1; i < 255; i++) {
                byte[] b = base.clone();
                b[3] = (byte) i;
                pool.execute(() -> probe(b));
            }
        }
        pool.shutdown();
        new Thread(() -> {
            try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            c.ui.postDelayed(() -> { if (!stopped) done.run(); }, 1500);   // a little longer for mDNS answers
        }).start();
    }

    void stop() {
        stopped = true;
        if (pool != null) pool.shutdownNow();
        if (listener != null) { try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { } listener = null; }
        queue.clear();
    }

    private void probe(byte[] ip) {
        if (stopped) return;
        try (Socket s = new Socket()) {
            InetAddress a = InetAddress.getByAddress(ip);
            s.connect(new InetSocketAddress(a, 445), PROBE_MS);
            String h = a.getHostAddress();
            c.ui.post(() -> { if (!stopped) found.accept(h, h); });
        } catch (Exception ignored) { }
    }

    /** The first private IPv4 of an up, non-loopback interface (Wi-Fi/Ethernet), host part zeroed to its /24; null if none. */
    private static byte[] ownSubnet() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return null;
            for (NetworkInterface n : Collections.list(all)) {
                if (!n.isUp() || n.isLoopback() || n.isPointToPoint()) continue;   // point-to-point: VPN / mobile data
                for (InterfaceAddress ia : n.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && Lan.isLan(a) && !a.isLinkLocalAddress() && ia.getNetworkPrefixLength() >= 16) return a.getAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    @SuppressWarnings("deprecation")   // resolveService: deprecated in API 34, the replacement is 34+ only
    private void pump() {
        if (resolving || stopped || queue.isEmpty()) return;
        resolving = true;
        NsdServiceInfo si = queue.poll();
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int err) { c.ui.post(() -> { resolving = false; pump(); }); }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    InetAddress a = info.getHost();
                    c.ui.post(() -> {
                        resolving = false;
                        if (!stopped && a != null && Lan.isLan(a)) found.accept(info.getServiceName(), a.getHostAddress());
                        pump();
                    });
                }
            });
        } catch (RuntimeException e) { resolving = false; }
    }
}
