package app.kolay.app.tools.files;

/** One directory entry, as listed by a {@link FileSource}. Immutable. */
public final class FileItem {
    public final String path, name;
    public final boolean dir;
    public final long size, modified;   // size is 0 for directories; modified in epoch ms

    public FileItem(String path, boolean dir, long size, long modified) {
        this.path = path; this.name = FileSource.name(path); this.dir = dir; this.size = size; this.modified = modified;
    }

    public boolean hidden() { return name.startsWith("."); }
}
