package app.kolay.app.tools.netscan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Talks eSCL to one scanner. Security (see SECURITY.md): only addresses allowed by {@code allowed} (production:
 * {@link Lan#isLan}); HTTPS first with a pinned certificate (trust on first use); plain HTTP only when HTTPS does not
 * work and no certificate is pinned for this scanner yet (a pinned scanner is never downgraded). Blocking: call off the
 * main thread; {@link #cancel} may be called from any thread.
 */
final class EsclClient {
    /** Where pinned fingerprints live (production: SharedPreferences). */
    interface Pins { String get(String key); void put(String key, String fingerprint); }

    /** Receives the scanned documents. Called on the scanning thread. */
    interface Pages {
        /** A fresh file the next document is written to. */
        File file() throws IOException;
        /** {@code f} holds a complete document of the given type (header value, may be null). */
        void got(File f, String contentType) throws IOException;
    }

    /** The certificate differs from the pinned one, or a pinned scanner is reachable only without HTTPS. */
    static final class TrustProblem extends IOException {
        private static final long serialVersionUID = 1L;
        final boolean mismatch;
        TrustProblem(boolean mismatch) { super(mismatch ? "certificate changed" : "https unavailable"); this.mismatch = mismatch; }
    }

    static final class Busy extends IOException {
        private static final long serialVersionUID = 1L;
        Busy() { super("Tarayıcı meşgul"); }
    }

    private static final int READ_MS = 15_000, POST_MS = 30_000, DOC_MS = 60_000, DELETE_MS = 5_000, CLOSE_MS = 5_000;
    private static final long MAX_DOC = 300L << 20;
    private static final int MAX_WAITS = 60, MAX_TIMEOUTS = 2, POST_RETRIES = 5;

    final Device d;
    private final Pins pins;
    private final Predicate<InetAddress> allowed;
    private final Tofu tofu = new Tofu(null);
    private final Http http = new Http(tofu);
    private Http.Endpoint ep;
    /** Fingerprint pinned by this connect (first use), else null. */
    volatile String firstPin;

    EsclClient(Device d, Pins pins, Predicate<InetAddress> allowed) { this.d = d; this.pins = pins; this.allowed = allowed; }

    boolean secure() { return ep != null && ep.tls; }
    String pinned() { return tofu.pinned; }

    void cancel() { http.abort(); }

    /** Finds a working endpoint (HTTPS, then — if allowed — HTTP) and reads the capabilities. */
    Escl.Caps connect() throws IOException {
        http.reset();
        InetAddress a = Lan.resolve(d.host, allowed);
        String pin = pins.get(d.key);
        tofu.pinned = pin;
        List<Http.Endpoint> tries = new ArrayList<>(2);
        tries.add(new Http.Endpoint(a, d.httpsPort > 0 ? d.httpsPort : 443, true, d.host));
        if (pin == null) tries.add(new Http.Endpoint(a, d.httpPort > 0 ? d.httpPort : 80, false, d.host));
        IOException last = null;
        for (Http.Endpoint e : tries) {
            tofu.seen = null; tofu.mismatch = false;
            try {
                Http.Response r = http.exchange(e, "GET", d.root() + "/ScannerCapabilities", null, null, READ_MS, null, Http.SMALL);
                if (r.code != 200) throw new IOException("Tarayıcı yanıt vermedi (HTTP " + r.code + ")");
                Escl.Caps caps = Escl.caps(r.body);
                ep = e;
                if (e.tls && pin == null && tofu.seen != null) {
                    pins.put(d.key, tofu.seen);
                    tofu.pinned = tofu.seen;
                    firstPin = tofu.seen;
                }
                return caps;
            } catch (Http.Aborted x) {
                throw x;
            } catch (IOException x) {
                if (tofu.mismatch) throw new TrustProblem(true);
                last = x;
            }
        }
        if (pin != null) throw new TrustProblem(false);
        throw last;
    }

    private Http.Response call(Http http, String method, String path, byte[] body, int readMs, Http.Sink sink, long max) throws IOException {
        if (ep == null) throw new IOException("not connected");
        try {
            return http.exchange(ep, method, path, "text/xml", body, readMs, sink, max);
        } catch (IOException x) {
            if (tofu.mismatch) throw new TrustProblem(true);
            throw x;
        }
    }

    Escl.Status status() throws IOException {
        Http.Response r = call(http, "GET", d.root() + "/ScannerStatus", null, READ_MS, null, Http.SMALL);
        if (r.code != 200) throw new IOException("Tarayıcı durumu alınamadı (HTTP " + r.code + ")");
        return Escl.status(r.body);
    }

    /** POST ScanJobs; returns the job's path (taken from Location, always used on our own endpoint). */
    String startJob(String xml) throws IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; ; i++) {
            Http.Response r = call(http, "POST", d.root() + "/ScanJobs", body, POST_MS, null, Http.SMALL);
            if (r.code >= 200 && r.code < 300) {
                String loc = r.header("location");
                if (loc == null || loc.isEmpty()) throw new IOException("Tarayıcı iş adresi vermedi");
                return jobPath(d.root() + "/ScanJobs", loc);
            }
            if (r.code == 503 && i < POST_RETRIES) { pause(1000); continue; }
            if (r.code == 409 || r.code == 503) throw new Busy();
            throw new IOException("Tarayıcı ayarları kabul etmedi (HTTP " + r.code + ")");
        }
    }

    /**
     * The path of a Location value (absolute — possibly another scheme, host or port — or relative to the request).
     * Only the path is used: requests stay on the checked endpoint, so a Location cannot send us elsewhere.
     */
    static String jobPath(String requestPath, String location) throws IOException {
        String p;
        try { p = URI.create("http://scanner" + requestPath).resolve(location.trim()).getRawPath(); }
        catch (IllegalArgumentException e) { throw new IOException("Geçersiz iş adresi"); }
        if (p == null) throw new IOException("Geçersiz iş adresi");
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty() || !p.startsWith("/") || !p.matches("[A-Za-z0-9._~%!$&'()*+,;=:@/-]+")) throw new IOException("Geçersiz iş adresi");
        return p;
    }

    /**
     * One job: create it, then fetch NextDocument until the scanner says there is no more (feeder) or one page arrived
     * (platen). Returns the number of documents. Afterwards the job is deleted on the scanner in every case (best effort):
     * on cancel / error that stops it; after a finished job it is harmless and frees scanners that keep jobs open.
     */
    int scan(String xml, boolean feeder, Pages pages) throws IOException {
        http.reset();
        String job = startJob(xml);
        try {
            int n = fetch(job, feeder, pages);
            if (!feeder) closePlaten(job);
            return n;
        } finally {
            try { call(new Http(tofu), "DELETE", job, null, DELETE_MS, null, Http.SMALL); } catch (IOException ignored) { }
        }
    }

    private int fetch(String job, boolean feeder, Pages pages) throws IOException {
        int n = 0, waits = 0, timeouts = 0;
        while (true) {
            if (http.aborted()) throw new Http.Aborted();
            File f = pages.file();
            OutputStream[] open = new OutputStream[1];
            Http.Response r;
            try {
                r = call(http, "GET", job + "/NextDocument", null, DOC_MS,
                    (code, type) -> code == 200 ? (open[0] = new FileOutputStream(f)) : null, MAX_DOC);
            } catch (SocketTimeoutException t) {
                f.delete();
                if (++timeouts > MAX_TIMEOUTS) throw new IOException("Tarayıcı zamanında yanıt vermedi");
                continue;
            } catch (IOException x) {
                f.delete();
                throw x;
            } finally {
                if (open[0] != null) open[0].close();
            }
            if (r.code == 200) {
                pages.got(f, r.header("content-type"));
                n++; waits = 0; timeouts = 0;
                if (!feeder) return n;
                continue;
            }
            f.delete();
            if (r.code == 404 || r.code == 410) return n;
            if (r.code == 503 && ++waits <= MAX_WAITS) { pause(1000); continue; }
            if (r.code == 409 || r.code == 503) throw new Busy();
            throw new IOException("Sayfa alınamadı (HTTP " + r.code + ")");
        }
    }

    /**
     * Platen: the job holds one page, but eSCL ends a job with a 404 on NextDocument and some scanners stay busy until
     * they were asked once more. One short request; whatever comes back (404, an error, a timeout, even another page)
     * means done, and an extra page is discarded (kept in memory at most {@link Http#SMALL}, never written).
     */
    private void closePlaten(String job) {
        if (http.aborted()) return;
        try { call(http, "GET", job + "/NextDocument", null, CLOSE_MS, null, Http.SMALL); } catch (IOException ignored) { }
    }

    private void pause(long ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (http.aborted()) throw new Http.Aborted();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Http.Aborted(); }
        }
    }
}
