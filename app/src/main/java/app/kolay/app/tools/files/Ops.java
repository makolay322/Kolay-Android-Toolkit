package app.kolay.app.tools.files;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * File operations over any {@link FileSource} (pure Java; runs on a worker thread). Data-safety rules:
 * a file is written under a hidden temporary name, its size is checked against the source, and only then renamed into
 * place; a replaced file is kept aside until the new one is in place; a move deletes the source only after a verified copy
 * (a move inside one volume is a plain rename). Cancelling removes the partial file and keeps everything finished so far.
 */
public final class Ops {
    public enum Conflict { REPLACE, SKIP, KEEP_BOTH }

    /** Asked (on the worker thread) when the target name exists; may block for the user's answer. */
    public interface Resolver { Conflict resolve(FileItem existing, FileItem incoming); }

    public interface Progress {
        void update(String name, long done, long total);
        boolean cancelled();
    }

    public static final class Result {
        public int done, skipped;
        public boolean cancelled;
        public final List<String> errors = new ArrayList<>();
    }

    static final class Cancelled extends IOException { Cancelled() { super("cancelled"); } }

    private static final int MAX_DEPTH = 64;   // guards against link loops
    static final String PART = ".kolay-part", OLD = ".kolay-old";

    private final Progress pr;
    private final Result res = new Result();
    private long done, total;

    private Ops(Progress pr) { this.pr = pr; }

    // ---------------------------------------------------------------- copy / move

    /** Copies (or moves) {@code paths} of {@code src} into {@code dstDir} of {@code dst}. */
    public static Result transfer(FileSource src, List<String> paths, FileSource dst, String dstDir, boolean move, Resolver r, Progress p) {
        Ops o = new Ops(p);
        try {
            for (String s : paths) o.total += measure(src, s, p::cancelled)[0];
            for (String s : paths) {
                FileItem it = src.stat(s);
                if (it == null) { o.res.errors.add(FileSource.name(s) + ": bulunamadı"); continue; }
                if (src == dst && it.dir && FileSource.within(dstDir, it.path)) { o.res.errors.add(it.name + ": klasör kendi içine kopyalanamaz"); continue; }
                if (o.transferOne(src, it, dst, dstDir, move, r, 0)) o.res.done++;
            }
        } catch (Cancelled e) { o.res.cancelled = true; }
        catch (IOException e) { o.res.errors.add(String.valueOf(e.getMessage())); }
        return o.res;
    }

    /** @return true if the item (and everything inside it) arrived. */
    private boolean transferOne(FileSource src, FileItem it, FileSource dst, String dstDir, boolean move, Resolver r, int depth) throws Cancelled {
        try {
            if (depth > MAX_DEPTH) throw new IOException("klasör çok derin");
            check();
            String target = FileSource.child(dstDir, it.name);
            FileItem ex = dst.stat(target);
            boolean merge = false, replace = false;
            if (ex != null) {
                if (src == dst && target.equals(it.path)) {          // onto itself: a move is a no-op, a copy gets a new name
                    if (move) { skip(src, it); return true; }
                    target = FileSource.child(dstDir, uniqueName(dst, dstDir, it.name));
                } else {
                    Conflict c = r.resolve(ex, it);
                    if (c == Conflict.SKIP) { res.skipped++; skip(src, it); return false; }
                    if (c == Conflict.KEEP_BOTH) target = FileSource.child(dstDir, uniqueName(dst, dstDir, it.name));
                    else if (ex.dir != it.dir) throw new IOException("aynı adda farklı türde bir öğe var");
                    else if (it.dir) merge = true;
                    else replace = true;
                }
            }
            if (move && src == dst && !merge && !replace) {
                long bytes = it.dir ? measure(src, it.path, pr::cancelled)[0] : it.size;
                try { src.rename(it.path, target); done += bytes; pr.update(it.name, done, total); return true; }
                catch (IOException ignored) { }                       // e.g. another volume: copy, verify, then delete
            }
            if (it.dir) {
                if (!merge) dst.mkdir(target);
                boolean ok = true;
                for (FileItem ch : src.list(it.path)) ok &= transferOne(src, ch, dst, target, move, r, depth + 1);
                if (move && ok) src.delete(it.path);
                return ok;
            }
            copyFile(src, it, dst, target, replace);
            if (move) src.delete(it.path);
            return true;
        } catch (Cancelled e) {
            throw e;
        } catch (IOException e) {
            res.errors.add(it.name + ": " + e.getMessage());
            return false;
        }
    }

    private void copyFile(FileSource src, FileItem it, FileSource dst, String target, boolean replace) throws IOException {
        String dir = FileSource.parent(target);
        String tmp = FileSource.child(dir, uniqueName(dst, dir, "." + it.name + PART));
        long n;
        try (InputStream in = src.openRead(it.path); OutputStream out = dst.openWrite(tmp)) {
            n = pump(in, out, it.name);
        } catch (IOException e) {
            quietDelete(dst, tmp);
            throw e;
        }
        FileItem w = dst.stat(tmp), now = src.stat(it.path);
        if (w == null || w.size != n || now == null || now.size != n) {
            quietDelete(dst, tmp);
            throw new IOException("kopya doğrulanamadı (boyut farklı)");
        }
        if (!replace) {
            try { dst.rename(tmp, target); } catch (IOException e) { quietDelete(dst, tmp); throw e; }
            return;
        }
        String old = FileSource.child(dir, uniqueName(dst, dir, "." + it.name + OLD));
        dst.rename(target, old);
        try { dst.rename(tmp, target); }
        catch (IOException e) { dst.rename(old, target); quietDelete(dst, tmp); throw e; }   // put the original back
        quietDelete(dst, old);
    }

    private long pump(InputStream in, OutputStream out, String name) throws IOException {
        byte[] buf = new byte[256 * 1024];
        long n = 0;
        for (int k; (k = in.read(buf)) > 0; ) {
            check();
            out.write(buf, 0, k);
            n += k; done += k;
            pr.update(name, done, total);
        }
        return n;
    }

    /** Counts a skipped item's bytes as processed so the bar still reaches the end. */
    private void skip(FileSource s, FileItem it) throws Cancelled {
        done += it.dir ? measure(s, it.path, pr::cancelled)[0] : it.size;
        pr.update(it.name, done, total);
    }

    // ---------------------------------------------------------------- delete

    public static Result delete(FileSource s, List<String> paths, Progress p) {
        Ops o = new Ops(p);
        try {
            for (String path : paths) { long[] m = measure(s, path, p::cancelled); o.total += m[1] + m[2]; }
            for (String path : paths) {
                FileItem it = s.stat(path);
                if (it == null) continue;
                if (o.deleteTree(s, it, 0)) o.res.done++;
            }
        } catch (Cancelled e) { o.res.cancelled = true; }
        catch (IOException e) { o.res.errors.add(String.valueOf(e.getMessage())); }
        return o.res;
    }

    private boolean deleteTree(FileSource s, FileItem it, int depth) throws Cancelled {
        check();
        try {
            if (depth > MAX_DEPTH) throw new IOException("klasör çok derin");
            boolean ok = true;
            if (it.dir) for (FileItem ch : s.list(it.path)) ok &= deleteTree(s, ch, depth + 1);
            if (ok) s.delete(it.path);
            pr.update(it.name, ++done, total);
            return ok;
        } catch (IOException e) {
            res.errors.add(it.name + ": " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- zip

    /** Packs {@code paths} (names relative to their folder) into the new file {@code zipPath}. */
    public static Result zip(FileSource s, List<String> paths, String zipPath, Progress p) {
        Ops o = new Ops(p);
        String dir = FileSource.parent(zipPath);
        String tmp = null;
        try {
            for (String path : paths) o.total += measure(s, path, p::cancelled)[0];
            tmp = FileSource.child(dir, uniqueName(s, dir, "." + FileSource.name(zipPath) + PART));
            try (ZipOutputStream z = new ZipOutputStream(s.openWrite(tmp))) {
                for (String path : paths) {
                    FileItem it = s.stat(path);
                    if (it != null) { o.zipOne(s, it, "", z, 0); o.res.done++; }
                }
            }
            s.rename(tmp, zipPath);
            tmp = null;
        } catch (Cancelled e) { o.res.cancelled = true; }
        catch (IOException e) { o.res.errors.add(String.valueOf(e.getMessage())); }
        if (tmp != null) quietDelete(s, tmp);
        return o.res;
    }

    private void zipOne(FileSource s, FileItem it, String prefix, ZipOutputStream z, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("klasör çok derin");
        String name = prefix + it.name;
        if (it.dir) {
            ZipEntry e = new ZipEntry(name + "/");
            e.setTime(it.modified);
            z.putNextEntry(e); z.closeEntry();
            for (FileItem ch : s.list(it.path)) zipOne(s, ch, name + "/", z, depth + 1);
            return;
        }
        ZipEntry e = new ZipEntry(name);
        e.setTime(it.modified);
        z.putNextEntry(e);
        try (InputStream in = s.openRead(it.path)) { pump(in, z, it.name); }
        z.closeEntry();
    }

    /** Extracts {@code zipPath} into {@code destDir}, which must not exist yet. Entries that would land outside it are refused. */
    public static Result unzip(FileSource s, String zipPath, String destDir, Progress p) {
        Ops o = new Ops(p);
        try {
            FileItem zf = s.stat(zipPath);
            if (zf == null) throw new IOException("arşiv bulunamadı");
            o.total = zf.size;
            s.mkdir(destDir);
            long[] read = {0};
            try (ZipInputStream z = new ZipInputStream(new FilterInputStream(s.openRead(zipPath)) {
                @Override public int read(byte[] b, int off, int len) throws IOException { int k = super.read(b, off, len); if (k > 0) read[0] += k; return k; }
                @Override public int read() throws IOException { int k = super.read(); if (k >= 0) read[0]++; return k; }
            })) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    o.check();
                    String rel = safeEntry(e.getName());
                    if (rel == null) { o.res.errors.add(e.getName() + ": güvensiz yol, atlandı"); continue; }
                    String target = FileSource.child(destDir, rel);
                    if (!FileSource.within(target, destDir)) { o.res.errors.add(e.getName() + ": güvensiz yol, atlandı"); continue; }
                    if (e.isDirectory()) { mkdirs(s, destDir, rel); continue; }
                    String dir = FileSource.parent(target);
                    mkdirs(s, destDir, dir.length() > destDir.length() ? dir.substring(destDir.length() + 1) : "");
                    if (s.stat(target) != null) target = FileSource.child(dir, uniqueName(s, dir, FileSource.name(target)));   // duplicate entry
                    String tmp = FileSource.child(dir, uniqueName(s, dir, "." + FileSource.name(target) + PART));
                    try (OutputStream out = s.openWrite(tmp)) {
                        byte[] buf = new byte[256 * 1024];
                        for (int k; (k = z.read(buf)) > 0; ) {
                            o.check();
                            out.write(buf, 0, k);
                            o.done = read[0];
                            p.update(FileSource.name(target), o.done, o.total);
                        }
                    } catch (IOException ex) { quietDelete(s, tmp); throw ex; }
                    s.rename(tmp, target);
                    o.res.done++;
                }
            }
        } catch (Cancelled e) { o.res.cancelled = true; }
        catch (IOException e) { o.res.errors.add(String.valueOf(e.getMessage())); }
        return o.res;
    }

    /** Zip-slip guard: the entry name as a clean relative path, or null if it tries to leave the target folder. */
    public static String safeEntry(String name) {
        if (name == null) return null;
        StringBuilder b = new StringBuilder();
        for (String seg : name.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..") || seg.indexOf('\0') >= 0) return null;
            if (b.length() > 0) b.append('/');
            b.append(seg);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static void mkdirs(FileSource s, String base, String rel) throws IOException {
        String cur = base;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) continue;
            cur = FileSource.child(cur, seg);
            FileItem it = s.stat(cur);
            if (it == null) s.mkdir(cur);
            else if (!it.dir) throw new IOException(seg + ": aynı adda bir dosya var");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** {bytes, files, folders} under {@code path} (the path itself included). */
    public static long[] measure(FileSource s, String path, BooleanSupplier cancelled) throws Cancelled {
        long[] m = new long[3];
        try {
            FileItem it = s.stat(path);
            if (it != null) measure(s, it, cancelled, m, 0);
        } catch (Cancelled e) { throw e; } catch (IOException ignored) { }
        return m;
    }

    private static void measure(FileSource s, FileItem it, BooleanSupplier cancelled, long[] m, int depth) throws Cancelled {
        if (cancelled.getAsBoolean()) throw new Cancelled();
        if (!it.dir) { m[0] += it.size; m[1]++; return; }
        m[2]++;
        if (depth > MAX_DEPTH) return;
        try { for (FileItem ch : s.list(it.path)) measure(s, ch, cancelled, m, depth + 1); } catch (Cancelled e) { throw e; } catch (IOException ignored) { }
    }

    /** Names in {@code dir} (subfolders too if {@code recursive}) containing {@code query}, case-insensitive; reports each hit. */
    public static void search(FileSource s, String dir, String query, boolean recursive, boolean hidden, Consumer<FileItem> hit, BooleanSupplier cancelled) {
        search(s, dir, query.toLowerCase(Locale.ROOT), recursive, hidden, hit, cancelled, 0);
    }

    private static void search(FileSource s, String dir, String q, boolean rec, boolean hidden, Consumer<FileItem> hit, BooleanSupplier cancelled, int depth) {
        List<FileItem> kids;
        try { kids = s.list(dir); } catch (IOException e) { return; }
        for (FileItem it : kids) {
            if (cancelled.getAsBoolean()) return;
            if (!hidden && it.hidden()) continue;
            if (it.name.toLowerCase(Locale.ROOT).contains(q)) hit.accept(it);
            if (rec && it.dir && depth < MAX_DEPTH) search(s, it.path, q, true, hidden, hit, cancelled, depth + 1);
        }
    }

    /** {@code name}, or "name (1).ext", "name (2).ext"… — the first that does not exist in {@code dir}. */
    public static String uniqueName(FileSource s, String dir, String name) throws IOException {
        if (s.stat(FileSource.child(dir, name)) == null) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String n = base + " (" + i + ")" + ext;
            if (s.stat(FileSource.child(dir, n)) == null) return n;
        }
    }

    /** Null if {@code name} is usable as a file name, else the reason. */
    public static String badName(String name) {
        if (name == null || name.trim().isEmpty()) return "Ad boş olamaz";
        if (name.equals(".") || name.equals("..")) return "Bu ad kullanılamaz";
        if (name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) return "Ad “/” içeremez";
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) return "Ad çok uzun";
        return null;
    }

    private void check() throws Cancelled { if (pr.cancelled()) throw new Cancelled(); }

    private static void quietDelete(FileSource s, String path) { try { s.delete(path); } catch (IOException ignored) { } }
}
