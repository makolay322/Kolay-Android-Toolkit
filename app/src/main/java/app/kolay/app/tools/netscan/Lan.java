package app.kolay.app.tools.netscan;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.function.Predicate;

/**
 * The only addresses the scanner client talks to: the local network. IPv4 private ranges (10/8, 172.16/12, 192.168/16)
 * and link-local 169.254/16 (scanners without DHCP fall back to it; never routed), IPv6 unique-local fc00::/7 and
 * link-local fe80::/10 (equally non-global). IPv4-mapped IPv6 is judged by its IPv4 part. Loopback, CGNAT 100.64/10,
 * multicast and every public address are refused.
 */
public final class Lan {
    private Lan() {}

    public static boolean isLan(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 16 && mapped(b)) b = new byte[]{b[12], b[13], b[14], b[15]};
        int x = b[0] & 0xff, y = b[1] & 0xff;
        if (b.length == 4) return x == 10 || (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 168) || (x == 169 && y == 254);
        return (x & 0xfe) == 0xfc || (x == 0xfe && (y & 0xc0) == 0x80);
    }

    private static boolean mapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return b[10] == (byte) 0xff && b[11] == (byte) 0xff;
    }

    public static final class NotLan extends IOException {
        private static final long serialVersionUID = 1L;
        NotLan() { super("Bu adres yerel ağda değil"); }
    }

    /**
     * Resolves {@code host} once and returns the address to connect to (IPv4 preferred). The caller connects to exactly
     * this address, so the check applies to the real peer, not to the name.
     */
    public static InetAddress resolve(String host, Predicate<InetAddress> allowed) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        InetAddress v6 = null;
        for (InetAddress a : all) {
            if (!allowed.test(a)) continue;
            if (a instanceof Inet4Address) return a;
            if (v6 == null) v6 = a;
        }
        if (v6 != null) return v6;
        throw new NotLan();
    }
}
