package app.kolay.app.viewer;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The single hook for old binary Office files (.doc / .xls / .ppt). Nothing is built into the APK: the reader is a plugin
 * (Apache POI + adapter, tools/office-plugin/) downloaded once on request, size + SHA-256 pinned in the shell, and
 * {@link #load loaded} from those verified bytes. Until then {@link #extract} returns a not-available result. A provider
 * returns the same models the OOXML readers produce, so the viewer shows its output with the existing panes.
 *
 * The plugin is compiled against this app's classes and runs in a child class loader, so these members form its ABI and
 * must not change without a new plugin build: {@link Provider}, {@link Result} factories, {@code Docx.Para/Run/Table}
 * fields, {@code Docx.MAX_TABLE_ROWS}, {@code Sheet(String)}, {@code Sheet.put}, {@code Sheet.widths}, {@code Sheet.MAX_COLS}.
 */
public final class LegacyOffice {
    private LegacyOffice() {}

    /** Either content (blocks for .doc/.ppt, sheets for .xls) or a reason it is not available. */
    public static final class Result {
        public final List<Docx.Block> blocks;
        public final List<Sheet> sheets;
        public final String reason;

        private Result(List<Docx.Block> blocks, List<Sheet> sheets, String reason) { this.blocks = blocks; this.sheets = sheets; this.reason = reason; }
        public static Result document(List<Docx.Block> blocks) { return new Result(blocks, null, null); }
        public static Result workbook(List<Sheet> sheets) { return new Result(null, sheets, null); }
        public static Result unavailable(String reason) { return new Result(null, null, reason); }
        public boolean available() { return blocks != null || sheets != null; }
    }

    public interface Provider {
        /** {@code ext} is "doc", "xls" or "ppt". Runs on a background thread; must not use the network for the file. */
        Result extract(Context ctx, Uri uri, String ext, BooleanSupplier cancelled) throws IOException;
    }

    private static volatile Provider provider;

    public static void install(Provider p) { provider = p; }

    public static boolean available() { return provider != null; }

    /** Class the plugin dex must contain (a public no-arg {@link Provider}). */
    private static final String PLUGIN_CLASS = "app.kolay.office.PoiProvider";

    /**
     * Loads the plugin from bytes the caller has already checked against the pinned SHA-256 (in memory, so nothing
     * unverified is ever loaded) and installs it. Its class loader's parent is the app's, so this interface is shared.
     */
    public static synchronized void load(java.nio.ByteBuffer dex) throws ReflectiveOperationException {
        if (provider != null) return;
        ClassLoader cl = new dalvik.system.InMemoryDexClassLoader(dex, LegacyOffice.class.getClassLoader());
        install((Provider) cl.loadClass(PLUGIN_CLASS).getDeclaredConstructor().newInstance());
    }

    public static Result extract(Context ctx, Uri uri, String ext, BooleanSupplier cancelled) throws IOException {
        Provider p = provider;
        if (p == null) return Result.unavailable("Bu eski Office biçimi (." + ext + ") henüz desteklenmiyor. "
            + "Dosyayı Word, Excel veya PowerPoint'te yeni biçimde (." + ext + "x) kaydedip yeniden açabilirsin.");
        return p.extract(ctx, uri, ext, cancelled);
    }
}
