package app.kolay.app.tools.netscan;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Trust on first use, for one scanner only (never installed globally). Scanners carry self-signed certificates, so the
 * system trust store cannot judge them: the first certificate seen is remembered by the caller (SHA-256 of the leaf), and
 * from then on only that exact certificate is accepted.
 */
@SuppressWarnings("CustomX509TrustManager")   // deliberate: pinning, not "trust all" (see SECURITY.md)
final class Tofu implements X509TrustManager {
    /** Hex SHA-256 of the accepted leaf certificate; null while nothing is pinned. */
    volatile String pinned;
    /** Fingerprint of the certificate presented in the last handshake. */
    volatile String seen;
    /** The last handshake failed because the certificate differs from the pinned one. */
    volatile boolean mismatch;
    private SSLSocketFactory factory;

    Tofu(String pinned) { this.pinned = pinned; }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
        String fp = fingerprint(chain[0]);
        seen = fp;
        String pin = pinned;
        if (pin != null && !pin.equals(fp)) { mismatch = true; throw new CertificateException("certificate does not match the pinned one"); }
    }

    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("not a server");
    }

    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

    synchronized SSLSocketFactory factory() throws GeneralSecurityException {
        if (factory == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{this}, null);
            factory = ctx.getSocketFactory();
        }
        return factory;
    }

    static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new CertificateException(e); }
    }

    /** "AB:CD:EF:…" — the first {@code bytes} bytes, for showing to the user. */
    static String pretty(String hex, int bytes) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(bytes * 2, hex.length()); i += 2) {
            if (b.length() > 0) b.append(':');
            b.append(hex, i, i + 2);
        }
        return b.toString().toUpperCase(java.util.Locale.ROOT);
    }
}
