package app.kolay.app.tools.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * A place files live (local storage today; e.g. a Samba share later). Paths are absolute, '/'-separated strings
 * inside the source. The browser and {@link Ops} only talk to this interface, so a new source needs no UI changes.
 * Pure Java (no Android types) so the operations can be tested on a desktop JVM.
 */
public interface FileSource {
    /** Children of {@code dir} (unsorted, hidden ones included). */
    List<FileItem> list(String dir) throws IOException;
    /** The entry at {@code path}, or null if nothing is there. */
    FileItem stat(String path) throws IOException;
    InputStream openRead(String path) throws IOException;
    /** Creates or truncates {@code path}; the data is durable once the stream is closed. */
    OutputStream openWrite(String path) throws IOException;
    /** Creates one directory (the parent must exist); fails if it exists. */
    void mkdir(String path) throws IOException;
    /** Deletes a file or an empty directory. */
    void delete(String path) throws IOException;
    /** Renames/moves inside this source; fails if {@code to} exists or the move is not possible in one step (e.g. across volumes). */
    void rename(String from, String to) throws IOException;

    static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    static String child(String dir, String name) { return dir.endsWith("/") ? dir + name : dir + "/" + name; }

    /** True if {@code path} is {@code dir} itself or lies inside it. */
    static boolean within(String path, String dir) { return path.equals(dir) || path.startsWith(dir.endsWith("/") ? dir : dir + "/"); }
}
