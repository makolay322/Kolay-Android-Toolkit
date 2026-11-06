package app.kolay.app.viewer;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The single hook for old binary Office files (.doc / .xls / .ppt). Nothing is built in yet: until a {@link Provider}
 * is installed (planned: a library downloaded at run time), {@link #extract} returns a not-available result and the
 * viewer shows the "Eski Office biçimi" state. A provider returns the same models the OOXML readers produce, so the
 * viewer shows its output with the existing panes.
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

    public static Result extract(Context ctx, Uri uri, String ext, BooleanSupplier cancelled) throws IOException {
        Provider p = provider;
        if (p == null) return Result.unavailable("Bu eski Office biçimi (." + ext + ") henüz desteklenmiyor. "
            + "Dosyayı Word, Excel veya PowerPoint'te yeni biçimde (." + ext + "x) kaydedip yeniden açabilirsin.");
        return p.extract(ctx, uri, ext, cancelled);
    }
}
