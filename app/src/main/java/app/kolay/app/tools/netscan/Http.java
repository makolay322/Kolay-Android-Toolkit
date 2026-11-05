package app.kolay.app.tools.netscan;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLSocket;

/**
 * Minimal HTTP/1.1 client for the scanner: one request per connection ({@code Connection: close}), over a plain or a TLS
 * socket to an address the caller already checked ({@link Lan}). Raw sockets on purpose: the app's network security
 * config forbids cleartext for the HTTP stacks, and the LAN-only plain-HTTP fallback must not loosen that for everything
 * else. Understands Content-Length, chunked transfer coding and read-to-close bodies.
 */
final class Http {
    static final int CONNECT_MS = 5000;
    private static final int MAX_LINE = 8192, MAX_HEADERS = 100;
    /** Error / XML bodies kept in memory. */
    static final long SMALL = 1 << 20;

    /** Where to connect: a checked address, never a name that could resolve differently later. */
    static final class Endpoint {
        final InetAddress addr; final int port; final boolean tls;
        /** Host header and TLS server name: the name as given (or the address literal). */
        final String host;
        Endpoint(InetAddress addr, int port, boolean tls, String host) { this.addr = addr; this.port = port; this.tls = tls; this.host = host; }

        String hostHeader() {
            String h = host;
            if (h.indexOf(':') >= 0) { int z = h.indexOf('%'); h = "[" + (z >= 0 ? h.substring(0, z) : h) + "]"; }
            return (tls ? port == 443 : port == 80) ? h : h + ":" + port;
        }
    }

    static final class Response {
        final int code;
        final Map<String, String> headers;   // lower-case names
        final byte[] body;                    // null when the body went to a sink
        Response(int code, Map<String, String> headers, byte[] body) { this.code = code; this.headers = headers; this.body = body; }
        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    }

    /** Picks the destination of a body once the status is known; null = keep it in memory (≤ {@link #SMALL}). */
    interface Sink { OutputStream open(int code, String contentType) throws IOException; }

    private final Tofu tofu;
    private volatile Socket active;
    private volatile boolean aborted;

    /** The request was cut off by {@link #abort}. */
    static final class Aborted extends IOException {
        Aborted() { super("cancelled"); }
    }

    Http(Tofu tofu) { this.tofu = tofu; }

    boolean aborted() { return aborted; }

    /** Allows requests again after {@link #abort}. */
    void reset() { aborted = false; }

    /** Closes the connection in progress (if any) from another thread; the blocked call fails with an IOException. */
    void abort() {
        aborted = true;
        Socket s = active;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    Response exchange(Endpoint e, String method, String path, String contentType, byte[] body, int readMs, Sink sink, long max) throws IOException {
        if (aborted) throw new Aborted();
        Socket raw = new Socket();
        active = raw;
        Socket s = raw;
        try {
            if (aborted) throw new Aborted();
            raw.connect(new InetSocketAddress(e.addr, e.port), CONNECT_MS);
            raw.setSoTimeout(readMs);
            if (e.tls) {
                if (tofu == null) throw new IOException("no trust manager");
                SSLSocket ss;
                try { ss = (SSLSocket) tofu.factory().createSocket(raw, e.addr instanceof Inet6Address ? e.addr.getHostAddress() : e.host, e.port, true); }
                catch (java.security.GeneralSecurityException x) { throw new IOException(x); }
                active = ss; s = ss;
                if (aborted) throw new Aborted();
                ss.startHandshake();
            }
            StringBuilder h = new StringBuilder(256);
            h.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(e.hostHeader()).append("\r\n")
                .append("User-Agent: Kolay\r\nAccept: */*\r\nConnection: close\r\n");
            if (body != null) h.append("Content-Type: ").append(contentType).append("\r\nContent-Length: ").append(body.length).append("\r\n");
            h.append("\r\n");
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 8192);
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) out.write(body);
            out.flush();
            return read(new BufferedInputStream(s.getInputStream(), 65536), method, sink, max);
        } catch (IOException x) {
            if (aborted && !(x instanceof Aborted)) throw new Aborted();
            throw x;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
            if (s != raw) try { raw.close(); } catch (IOException ignored) { }
            active = null;
        }
    }

    /** Parses one response from {@code in}. Package-visible for tests. */
    static Response read(InputStream in, String method, Sink sink, long max) throws IOException {
        int code;
        Map<String, String> hd;
        do {   // skip interim 1xx responses
            String status = line(in);
            if (status == null) throw new EOFException("no response");
            String[] parts = status.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("bad status line");
            try { code = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException x) { throw new IOException("bad status line"); }
            hd = headers(in);
        } while (code >= 100 && code < 200);

        if ("HEAD".equals(method) || code == 204 || code == 304) return new Response(code, hd, new byte[0]);
        OutputStream dst = sink != null ? sink.open(code, hd.get("content-type")) : null;
        ByteArrayOutputStream mem = null;
        long limit = max;
        if (dst == null) { mem = new ByteArrayOutputStream(); dst = mem; limit = Math.min(max, SMALL); }
        String te = hd.get("transfer-encoding"), cl = hd.get("content-length");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) dechunk(in, dst, limit);
        else if (cl != null) {
            long n;
            try { n = Long.parseLong(cl.trim()); } catch (NumberFormatException x) { throw new IOException("bad Content-Length"); }
            if (n < 0 || n > limit) throw new IOException("Yanıt çok büyük");
            copy(in, dst, n, true);
        } else copy(in, dst, limit, false);
        dst.flush();
        return new Response(code, hd, mem != null ? mem.toByteArray() : null);
    }

    private static Map<String, String> headers(InputStream in) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; ; i++) {
            String l = line(in);
            if (l == null) throw new EOFException("headers cut off");
            if (l.isEmpty()) return m;
            if (i >= MAX_HEADERS) throw new IOException("too many headers");
            int c = l.indexOf(':');
            if (c <= 0) continue;
            String k = l.substring(0, c).trim().toLowerCase(Locale.ROOT), v = l.substring(c + 1).trim();
            m.merge(k, v, (a, b) -> a + ", " + b);
        }
    }

    /** One CRLF (or LF) terminated line, ISO-8859-1; null at end of stream before any byte. */
    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (b.length() >= MAX_LINE) throw new IOException("line too long");
            b.append((char) ch);
        }
        if (ch < 0 && b.length() == 0) return null;
        int n = b.length();
        if (n > 0 && b.charAt(n - 1) == '\r') b.setLength(n - 1);
        return b.toString();
    }

    private static void dechunk(InputStream in, OutputStream out, long limit) throws IOException {
        long total = 0;
        while (true) {
            String l = line(in);
            if (l == null) throw new EOFException("chunk cut off");
            int semi = l.indexOf(';');
            String hex = (semi >= 0 ? l.substring(0, semi) : l).trim();
            long n;
            try { n = Long.parseLong(hex, 16); } catch (NumberFormatException x) { throw new IOException("bad chunk size"); }
            if (n < 0) throw new IOException("bad chunk size");
            if (n == 0) {   // trailers until the empty line
                String t;
                while ((t = line(in)) != null && !t.isEmpty()) { }
                return;
            }
            total += n;
            if (total > limit) throw new IOException("Yanıt çok büyük");
            copy(in, out, n, true);
            String end = line(in);
            if (end == null || !end.isEmpty()) throw new IOException("bad chunk end");
        }
    }

    /** Copies {@code n} bytes (exact: EOF before is an error) or up to EOF (then more than {@code n} is an error). */
    private static void copy(InputStream in, OutputStream out, long n, boolean exact) throws IOException {
        byte[] buf = new byte[65536];
        long left = n;
        while (true) {
            int want = exact ? (int) Math.min(buf.length, left) : buf.length;
            if (exact && want == 0) return;
            int r = in.read(buf, 0, want);
            if (r < 0) { if (exact) throw new EOFException("body cut off"); return; }
            if (!exact && r > left) throw new IOException("Yanıt çok büyük");
            out.write(buf, 0, r);
            left -= r;
        }
    }
}
