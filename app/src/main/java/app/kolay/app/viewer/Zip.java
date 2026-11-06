package app.kolay.app.viewer;

import java.io.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only access to an OOXML package with zip-bomb guards: a cap on the entry count and on the bytes actually
 * inflated per entry (declared sizes can lie, so the stream itself is counted). Pure Java; thread-safe like ZipFile.
 */
public final class Zip implements Closeable {
    static final int MAX_ENTRIES = 20000;
    /** Per-entry inflate caps: XML parts and pictures. */
    public static final long MAX_XML = 160L << 20, MAX_MEDIA = 24L << 20;

    private final ZipFile zf;
    private final Closeable extra;
    private final HashMap<String, ZipEntry> entries = new HashMap<>();

    public Zip(File f, Closeable extra) throws IOException { this(new ZipFile(f), extra); }

    Zip(ZipFile zf, Closeable extra) throws IOException {
        this.zf = zf; this.extra = extra;
        int n = 0;
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry z = e.nextElement();
            if (++n > MAX_ENTRIES) { close(); throw new IOException("too many entries"); }
            String name = z.getName();
            if (name.startsWith("/")) name = name.substring(1);
            entries.put(name.toLowerCase(java.util.Locale.ROOT), z);
        }
    }

    public boolean has(String name) { return entry(name) != null; }

    private ZipEntry entry(String name) {
        if (name == null) return null;
        if (name.startsWith("/")) name = name.substring(1);
        return entries.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Opens an entry; reading more than {@code cap} inflated bytes throws. Null when the entry does not exist. */
    public InputStream open(String name, long cap) throws IOException {
        ZipEntry z = entry(name);
        if (z == null) return null;
        if (z.getSize() > cap) throw new IOException("entry too large");
        return new Capped(new BufferedInputStream(zf.getInputStream(z), 1 << 16), cap);
    }

    public byte[] read(String name, long cap) throws IOException {
        try (InputStream in = open(name, cap)) {
            if (in == null) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream(16384);
            byte[] b = new byte[16384];
            for (int r; (r = in.read(b)) > 0; ) o.write(b, 0, r);
            return o.toByteArray();
        }
    }

    @Override public void close() {
        try { zf.close(); } catch (IOException ignored) {}
        if (extra != null) try { extra.close(); } catch (IOException ignored) {}
    }

    /** Counts inflated bytes and fails past the cap. */
    static final class Capped extends FilterInputStream {
        private long left;
        Capped(InputStream in, long cap) { super(in); left = cap; }
        private void take(long n) throws IOException { left -= n; if (left < 0) throw new IOException("entry too large"); }
        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) take(1); return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int r = super.read(b, off, len); if (r > 0) take(r); return r; }
        @Override public long skip(long n) throws IOException { long r = super.skip(n); take(r); return r; }
    }
}
