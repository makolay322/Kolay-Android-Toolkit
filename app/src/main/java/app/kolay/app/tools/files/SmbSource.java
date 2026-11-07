package app.kolay.app.tools.files;

import app.kolay.app.tools.netscan.Lan;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.auth.NtlmAuthenticator;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.Share;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.net.SocketFactory;

/**
 * A Samba / Windows file server (SMB 2.0.2 – 3.1.1, smbj). Paths: "/" lists the shares, "/share/dir/file" is inside one.
 * One connection + session per server, reused by every screen; a dropped or idle connection is re-opened once, transparently.
 * Signing and encryption follow what the server requires; SMB1 is not spoken. Only LAN and Tailscale addresses are dialled: every socket
 * goes through {@link LanSockets}, which resolves the name once and refuses public addresses (so DFS or a DNS trick cannot
 * point it elsewhere either). Pure Java (no Android types): testable on a desktop JVM. Credentials and file contents are never logged.
 */
public final class SmbSource implements FileSource, Closeable {
    /** A saved server. {@code password} is null for a guest login. */
    public static final class Server {
        public final String id, name, host, user, domain;
        public final int port;
        public final boolean guest;
        public final List<String> shares;   // added by hand (servers that do not list their shares)
        final String password;

        public Server(String id, String name, String host, int port, String user, String password, String domain, boolean guest, List<String> shares) {
            this.id = id; this.name = name; this.host = host; this.port = port <= 0 ? 445 : port; this.user = user == null ? "" : user;
            this.password = password; this.domain = domain == null ? "" : domain; this.guest = guest;
            this.shares = shares == null ? new ArrayList<>() : shares;
        }
    }

    static final int CONNECT_MS = 6000;
    private static final long IDLE_MS = 3 * 60_000;   // older connections are re-opened before use (a silently dropped one would hang until the timeout)

    public final Server server;
    private final Predicate<InetAddress> allowed;
    private final SMBClient client;
    private Connection conn;
    private Session session;
    private final Map<String, DiskShare> shares = new HashMap<>();
    private volatile long lastUse;
    private final AtomicInteger streams = new AtomicInteger();

    public SmbSource(Server server, Predicate<InetAddress> allowed) {
        this.server = server;
        this.allowed = allowed;
        SmbConfig cfg = SmbConfig.builder()
            .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
            .withSecurityProvider(new BCSecurityProvider())   // Android's JCA has no MD4 / AES-CMAC; smbj's BC adapter uses BouncyCastle directly
            .withAuthenticators(new NtlmAuthenticator.Factory())   // NTLMv2 only (Kerberos/GSS is not on Android)
            .withSigningEnabled(true).withSigningRequired(false)   // signs whenever the server asks for it
            .withEncryptData(true)                                // SMB 3.x: encrypted whenever the server can (needed for shares that require it); 2.x: plain
            .withDfsEnabled(false)
            .withSocketFactory(new LanSockets())
            .withSoTimeout(0)                                     // the reader thread waits; each request has its own timeout
            .withTimeout(30, TimeUnit.SECONDS)
            .withReadTimeout(60, TimeUnit.SECONDS).withWriteTimeout(60, TimeUnit.SECONDS).withTransactTimeout(30, TimeUnit.SECONDS)
            .build();
        client = new SMBClient(cfg);
    }

    // ---------------------------------------------------------------- FileSource

    @Override public List<FileItem> list(String dir) throws IOException {
        String[] p = split(dir);
        if (p[0] == null) {
            List<FileItem> out = new ArrayList<>();
            for (String s : shareNames()) out.add(new FileItem("/" + s, true, 0, 0));
            return out;
        }
        return call(() -> {
            List<FileItem> out = new ArrayList<>();
            for (FileIdBothDirectoryInformation f : share(p[0]).list(p[1])) {
                String n = f.getFileName();
                if (n.equals(".") || n.equals("..")) continue;
                boolean d = (f.getFileAttributes() & 0x10) != 0;
                out.add(new FileItem(FileSource.child(dir, n), d, d ? 0 : f.getEndOfFile(), f.getLastWriteTime().toEpochMillis()));
            }
            return out;
        });
    }

    @Override public FileItem stat(String path) throws IOException {
        String[] p = split(path);
        if (p[0] == null) return new FileItem("/", true, 0, 0);
        try {
            return call(() -> {
                DiskShare s = share(p[0]);
                if (p[1].isEmpty()) return new FileItem(path, true, 0, 0);
                FileAllInformation i = s.getFileInformation(p[1]);
                boolean d = i.getStandardInformation().isDirectory();
                return new FileItem(path, d, d ? 0 : i.getStandardInformation().getEndOfFile(), i.getBasicInformation().getLastWriteTime().toEpochMillis());
            });
        } catch (Status e) {
            if (e.notFound()) return null;
            throw e;
        }
    }

    @Override public InputStream openRead(String path) throws IOException {
        String[] p = file(path);
        File f = call(() -> share(p[0]).openFile(p[1], EnumSet.of(AccessMask.GENERIC_READ), null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)));
        streams.incrementAndGet();
        return new FilterInputStream(f.getInputStream()) {
            private boolean closed;
            @Override public int read() throws IOException { lastUse = System.currentTimeMillis(); try { return super.read(); } catch (RuntimeException e) { throw wrap(e); } }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                lastUse = System.currentTimeMillis();
                try { return super.read(b, off, len); } catch (RuntimeException e) { throw wrap(e); }
            }
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try { super.close(); } catch (RuntimeException ignored) { }
                finally { quietClose(f); streams.decrementAndGet(); lastUse = System.currentTimeMillis(); }
            }
        };
    }

    @Override public OutputStream openWrite(String path) throws IOException {
        String[] p = file(path);
        File f = call(() -> share(p[0]).openFile(p[1], EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ), null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OVERWRITE_IF, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)));
        streams.incrementAndGet();
        return new FilterOutputStream(f.getOutputStream()) {
            private boolean closed;
            @Override public void write(int b) throws IOException { lastUse = System.currentTimeMillis(); try { out.write(b); } catch (RuntimeException e) { throw wrap(e); } }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                lastUse = System.currentTimeMillis();
                try { out.write(b, off, len); } catch (RuntimeException e) { throw wrap(e); }
            }
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try {
                    out.close();                      // sends what is still buffered
                    try { f.flush(); }                // SMB2 FLUSH: on the server's disk before a move deletes the source
                    catch (SMBApiException e) { long s = e.getStatusCode(); if (s != 0xC00000BBL && s != 0xC0000010L) throw e; }   // not supported: nothing to flush
                } catch (RuntimeException e) {
                    throw wrap(e);
                } finally { quietClose(f); streams.decrementAndGet(); lastUse = System.currentTimeMillis(); }
            }
        };
    }

    @Override public void mkdir(String path) throws IOException {
        String[] p = file(path);
        call(() -> { share(p[0]).mkdir(p[1]); return null; });
    }

    @Override public void delete(String path) throws IOException {
        String[] p = file(path);
        FileItem it = stat(path);
        if (it == null) return;
        call(() -> { if (it.dir) share(p[0]).rmdir(p[1], false); else share(p[0]).rm(p[1]); return null; });
    }

    @Override public void rename(String from, String to) throws IOException {
        String[] a = file(from), b = file(to);
        if (!a[0].equalsIgnoreCase(b[0])) throw new IOException("Paylaşımlar arası tek adımda taşınamaz");
        call(() -> {
            try (DiskEntry e = share(a[0]).open(a[1], EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null,
                    EnumSet.allOf(SMB2ShareAccess.class), SMB2CreateDisposition.FILE_OPEN, null)) {
                e.rename(b[1], false);   // fails if the target exists
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- shares

    /** Shares listed by the server (srvsvc) plus the ones added by hand. */
    public List<String> shareNames() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        IOException failed = null;
        try { out.addAll(call(() -> SrvSvc.diskShares(session(), server.host))); }
        catch (Status e) { if (e.code == 0xC000006DL || e.code == 0xC0000022L) throw e; failed = e; }   // wrong password / no access: say so
        catch (IOException e) { failed = e; }
        for (String s : server.shares) if (!s.trim().isEmpty()) out.add(s.trim());
        if (out.isEmpty() && failed != null) {
            if (failed instanceof Status) throw new IOException("Paylaşım listesi alınamadı. Sunucuyu düzenleyip paylaşım adını elle ekle.", failed);
            throw failed;
        }
        return new ArrayList<>(out);
    }

    /** Connects and logs in once (for "Bağlan"); returns a short description of the negotiated protocol. */
    public String check() throws IOException {
        return call(() -> {
            Session s = session();
            SMB2Dialect d = s.getConnection().getNegotiatedProtocol().getDialect();
            String v = d.name().replace("SMB_", "").replace('_', '.');
            return "SMB " + v + (s.isSigningRequired() ? ", imzalı" : "") + (s.shouldEncryptData() ? ", şifreli" : "") + (s.isGuest() ? ", misafir" : "");
        });
    }

    // ---------------------------------------------------------------- connection

    private synchronized Session session() throws IOException {
        long now = System.currentTimeMillis();
        if (session != null && conn != null && conn.isConnected() && (now - lastUse < IDLE_MS || streams.get() > 0)) { lastUse = now; return session; }
        reset();
        InetAddress a = resolve();
        conn = client.connect(a.getHostAddress(), server.port);
        AuthenticationContext auth = server.guest ? AuthenticationContext.guest()
            : new AuthenticationContext(server.user, server.password == null ? new char[0] : server.password.toCharArray(), server.domain);
        session = conn.authenticate(auth);
        lastUse = System.currentTimeMillis();
        return session;
    }

    private synchronized DiskShare share(String name) throws IOException {
        Session s = session();
        DiskShare d = shares.get(name);
        if (d != null && d.isConnected()) return d;
        Share sh = s.connectShare(name);
        if (!(sh instanceof DiskShare)) { quietClose(sh); throw new IOException("“" + name + "” bir dosya paylaşımı değil"); }
        shares.put(name, (DiskShare) sh);
        return (DiskShare) sh;
    }

    /** Drops the connection (next use reconnects). */
    private synchronized void reset() {
        shares.clear();
        Session s = session; Connection c = conn;
        session = null; conn = null;
        if (s != null) quietClose(s);
        if (c != null) { try { c.close(true); } catch (Exception ignored) { } }
    }

    @Override public synchronized void close() { reset(); try { client.close(); } catch (Exception ignored) { } }

    private InetAddress resolve() throws IOException {
        try { return Lan.resolve(server.host, allowed); }
        catch (Lan.NotLan e) { throw new Fatal(notLan(server.host)); }
        catch (UnknownHostException e) { throw new Fatal("Sunucu adı çözülemedi: " + server.host); }
    }

    static String notLan(String host) {
        return "Güvenlik: Samba bağlantısı yalnızca yerel ağdaki (özel adresli) ya da Tailscale'deki sunuculara yapılır; “" + host + "” genel bir adres.";
    }

    private interface Op<T> { T run() throws IOException; }

    /** Runs {@code op}; a broken connection is re-opened and the call repeated once. Every error leaves as a Turkish IOException. */
    private <T> T call(Op<T> op) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                T t = op.run();
                lastUse = System.currentTimeMillis();
                return t;
            } catch (SMBApiException e) {
                Status st = new Status(e);
                if (!st.sessionGone() || attempt > 0) throw st;
                reset();
            } catch (Fatal e) {
                throw e;
            } catch (IOException e) {
                boolean net = e instanceof com.hierynomus.protocol.transport.TransportException || e instanceof java.net.SocketException
                    || e instanceof java.io.InterruptedIOException || e instanceof java.io.EOFException;
                if (!net) throw e;                    // our own messages (e.g. "not a file share", srvsvc) pass through as they are
                reset();
                if (attempt > 0 || fatalCause(e)) throw wrap(e);
            } catch (RuntimeException e) {
                reset();
                if (attempt > 0 || fatalCause(e)) throw wrap(e);
            }
        }
    }

    private static boolean fatalCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof Fatal || t instanceof Lan.NotLan) return true;
        return false;
    }

    /** A connection-level failure as a readable IOException. */
    static IOException wrap(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof Status) return (Status) t;
            if (t instanceof SMBApiException) return new Status((SMBApiException) t);
            if (t instanceof Fatal) return (Fatal) t;
            if (t instanceof Lan.NotLan) return new Fatal("Güvenlik: yalnızca yerel ağdaki ya da Tailscale'deki sunuculara bağlanılır.");
            if (t instanceof SocketTimeoutException || t instanceof java.util.concurrent.TimeoutException) return new IOException("Sunucu yanıt vermiyor (zaman aşımı)", e);
            if (t instanceof ConnectException || t instanceof NoRouteToHostException) return new IOException("Sunucuya bağlanılamadı (kapalı ya da bu ağda değil)", e);
        }
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (m.contains("negotiat") || m.contains("dialect") || m.contains("smbv1") || m.contains("smb1"))
            return new IOException("Sunucu SMB 2/3 konuşmuyor. Yalnızca eski SMB1 destekliyor olabilir; SMB1 güvensiz olduğu için desteklenmez — sunucuda SMB2 veya üstünü aç.", e);
        return new IOException("Bağlantı koptu ya da sunucu yanıt vermedi", e);
    }

    // ---------------------------------------------------------------- paths

    /** {share or null, path inside the share with backslashes}. */
    static String[] split(String path) throws IOException {
        List<String> segs = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.isEmpty()) continue;
            if (s.equals(".") || s.equals("..") || s.indexOf('\\') >= 0) throw new IOException("Geçersiz yol");
            segs.add(s);
        }
        if (segs.isEmpty()) return new String[]{null, ""};
        return new String[]{segs.get(0), String.join("\\", segs.subList(1, segs.size()))};
    }

    /** Like {@link #split} but for something inside a share (not the server or a share itself). */
    private static String[] file(String path) throws IOException {
        String[] p = split(path);
        if (p[0] == null || p[1].isEmpty()) throw new IOException("Paylaşımın kendisi değiştirilemez; bir paylaşımın içini aç");
        return p;
    }

    private static void quietClose(AutoCloseable c) { try { c.close(); } catch (Exception ignored) { } }

    // ---------------------------------------------------------------- errors

    /** An error that a retry cannot fix (policy, name resolution). */
    static final class Fatal extends IOException {
        private static final long serialVersionUID = 1L;
        Fatal(String m) { super(m); }
    }

    /** The server answered with an NT status. */
    static final class Status extends IOException {
        private static final long serialVersionUID = 1L;
        final long code;
        Status(SMBApiException e) { super(message(e.getStatusCode()), e); code = e.getStatusCode(); }
        boolean notFound() { return code == 0xC0000034L || code == 0xC000003AL || code == 0xC000000FL || code == 0xC00000CCL; }
        boolean sessionGone() { return code == 0xC000035CL || code == 0xC0000203L || code == 0xC00000C9L || code == 0xC0000128L; }

        private static String message(long s) {
            switch ((int) s) {
                case 0xC000006D: return "Kullanıcı adı veya parola yanlış";
                case 0xC000006E: case 0xC0000072: case 0xC0000234: return "Bu hesapla giriş yapılamıyor (kısıtlı, kapalı ya da kilitli)";
                case 0xC0000071: return "Hesabın parolasının süresi dolmuş";
                case 0xC0000022: return "Erişim reddedildi";
                case 0xC00000CC: return "Paylaşım bulunamadı";
                case 0xC0000034: case 0xC000003A: case 0xC000000F: return "Bulunamadı";
                case 0xC0000035: return "Hedef zaten var";
                case 0xC0000033: case 0xC000003B: return "Geçersiz ad (sunucu \\ : * ? \" < > | gibi karakterleri kabul etmez)";
                case 0xC0000043: return "Dosya başka bir uygulamada açık";
                case 0xC0000056: return "Silinmek üzere";
                case 0xC000007F: case 0xC0000044: return "Sunucuda yer yok";
                case 0xC0000101: return "Klasör boş değil";
                case 0xC0000103: return "Bir klasör değil";
                case 0xC00000BA: return "Bu bir klasör";
                case 0xC0000121: return "Silinemez (salt okunur)";
                case 0xC00000A2: return "Paylaşım salt okunur";
                case 0xC00000BB: return "Sunucu bu işlemi desteklemiyor";
                default: return String.format("Sunucu hatası 0x%08X", s);
            }
        }
    }

    // ---------------------------------------------------------------- sockets

    /** Every socket smbj opens: resolved once, LAN/Tailscale addresses only, connect timeout, TCP keep-alive. */
    private final class LanSockets extends SocketFactory {
        @Override public Socket createSocket(String host, int port) throws IOException {
            InetAddress a;
            try { a = Lan.resolve(host, allowed); } catch (Lan.NotLan e) { throw new Fatal(notLan(host)); }
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.connect(new InetSocketAddress(a, port), CONNECT_MS);
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignored) { }
                throw e;
            }
            return s;
        }
        @Override public Socket createSocket(String host, int port, InetAddress la, int lp) throws IOException { return createSocket(host, port); }
        @Override public Socket createSocket(InetAddress a, int port) throws IOException { return createSocket(a.getHostAddress(), port); }
        @Override public Socket createSocket(InetAddress a, int port, InetAddress la, int lp) throws IOException { return createSocket(a.getHostAddress(), port); }
    }
}
