package app.kolay.app.tools.files;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** The device's own storage through java.io / java.nio (needs the all-files permission outside app folders). */
public final class LocalSource implements FileSource {
    public static final LocalSource INSTANCE = new LocalSource();

    @Override public List<FileItem> list(String dir) throws IOException {
        // One stat per entry via nio (File.isDirectory/length/lastModified would be three); thousands of entries stay fast.
        List<FileItem> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(new File(dir).toPath())) {
            for (Path p : ds) {
                String path = FileSource.child(dir, p.getFileName().toString());
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    out.add(new FileItem(path, a.isDirectory(), a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toMillis()));
                } catch (IOException e) {   // dangling link or unreadable entry: still show it
                    out.add(new FileItem(path, false, 0, 0));
                }
            }
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
        return out;
    }

    @Override public FileItem stat(String path) {
        File f = new File(path);
        if (!f.exists()) return null;
        boolean d = f.isDirectory();
        return new FileItem(path, d, d ? 0 : f.length(), f.lastModified());
    }

    @Override public InputStream openRead(String path) throws IOException { return new FileInputStream(path); }

    @Override public OutputStream openWrite(String path) throws IOException {
        return new FileOutputStream(path) {
            private boolean closed;
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try { flush(); getFD().sync(); } finally { super.close(); }   // durable before a move deletes the source
            }
        };
    }

    @Override public void mkdir(String path) throws IOException {
        if (!new File(path).mkdir()) throw new IOException("Klasör oluşturulamadı: " + FileSource.name(path));
    }

    @Override public void delete(String path) throws IOException {
        File f = new File(path);
        if (!f.delete() && f.exists()) throw new IOException("Silinemedi: " + f.getName());
    }

    @Override public void rename(String from, String to) throws IOException {
        File t = new File(to);
        if (t.exists()) throw new IOException("Hedef zaten var: " + t.getName());   // renameTo would silently overwrite a file
        if (!new File(from).renameTo(t)) throw new IOException("Taşınamadı: " + FileSource.name(from));
    }
}
