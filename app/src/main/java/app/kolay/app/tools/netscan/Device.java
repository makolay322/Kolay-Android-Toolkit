package app.kolay.app.tools.netscan;

import java.net.URI;
import java.util.Locale;

/** A scanner as the lists know it: found on the network (mDNS) or entered by hand. */
final class Device {
    /** Stable identity for the pinned certificate and the remembered settings: "uuid:…" when announced, else "host:…". */
    final String key;
    String name;
    /** Address literal or host name; resolved and checked ({@link Lan}) on every connect. */
    String host;
    /** 0 = not announced (then 443 / 80). */
    int httpPort, httpsPort;
    /** eSCL resource path without slashes, e.g. "eSCL"; "" = root. */
    String rs = "eSCL";
    final boolean manual;

    Device(String key, boolean manual) { this.key = key; this.manual = manual; }

    /** "/eSCL" or "" — what the request paths start with. */
    String root() { return rs.isEmpty() ? "" : "/" + rs; }

    /** The TXT "rs" value made safe for a request line: only path characters, no leading/trailing slashes. */
    static String cleanRs(String rs) {
        if (rs == null) return "eSCL";
        String s = rs.trim().replaceAll("^/+|/+$", "");
        return s.matches("[A-Za-z0-9._~/-]{0,64}") ? s : "eSCL";
    }

    /**
     * Parses what the user typed: "192.168.1.20", "tarayici.local", "192.168.1.20:8080", "[fd00::5]:80",
     * optionally with "http://" / "https://" and a resource path ("…/eSCL"). Null when it is not a host.
     * The scheme is ignored: HTTPS is always tried first.
     */
    static Device manual(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty() || s.length() > 200) return null;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://")) s = s.substring(7);
        else if (low.startsWith("https://")) s = s.substring(8);
        URI u;
        try { u = new URI("http://" + s); } catch (Exception e) { return null; }
        String h = u.getHost();
        if (h == null || h.isEmpty() || u.getRawQuery() != null || u.getRawFragment() != null || u.getRawUserInfo() != null) return null;
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (!h.matches("[A-Za-z0-9.:%_-]+")) return null;
        int port = u.getPort();
        if (port == 0 || port > 65535) return null;
        String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("^/+|/+$", "");
        String rs = path.isEmpty() ? "eSCL" : cleanRs(path);
        String spec = spec(h, port, rs);
        Device d = new Device("host:" + spec.toLowerCase(Locale.ROOT), true);
        d.host = h;
        d.name = h;
        if (port > 0) { d.httpPort = port; d.httpsPort = port; }
        d.rs = rs;
        return d;
    }

    /** The text the user typed, normalised (what is stored in the list of manual entries). */
    String spec() { return spec(host, httpPort, rs); }

    private static String spec(String host, int port, String rs) {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + (port > 0 ? ":" + port : "") + (rs.equals("eSCL") ? "" : "/" + rs);
    }
}
