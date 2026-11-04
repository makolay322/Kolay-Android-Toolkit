package app.kolay.shell;

/** Everything that identifies this deployment. */
final class Config {
    private Config() {}

    static final String REPO_BASE = "https://github.com/makolay322/Kolay-Android-Toolkit/";
    static final String MANIFEST_URL = REPO_BASE + "releases/latest/download/manifest.json";
    static final String RELEASES_PAGE = REPO_BASE + "releases/latest";

    /**
     * Release-signing public keys (ECDSA P-256, X.509 DER, base64). An update is accepted if ANY of them verifies it,
     * which allows key rotation: add the new key, ship a release, then sign with the new key. Private halves never leave
     * the maintainer's machine / the CI secret store.
     */
    static final String[] UPDATE_PUBLIC_KEYS = {
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEQZPGcGyvCzIR+FRxJbQNId/ga2Zx/EUB63tCpB9m5FsqMLYLPpDwxYjyJBmk2Jl4N6aYtLRHck0x3TV2AHbltQ==",
    };

    /** Hosts a download may be served from (GitHub releases redirect to the second one). */
    static boolean trustedHost(String host) {
        return host != null && (host.equals("github.com") || host.endsWith(".githubusercontent.com"));
    }

    static final long CHECK_INTERVAL_MS = 6L * 3600 * 1000;
    static final long RETRY_AFTER_FAIL_MS = 30L * 60 * 1000;
    static final long MAX_APK_BYTES = 120L << 20;
}
