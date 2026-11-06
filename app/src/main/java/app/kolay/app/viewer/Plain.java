package app.kolay.app.viewer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

/** Plain-text decoding: BOM (UTF-8/16), else UTF-8 if the start decodes cleanly, else Windows-1254 (Turkish). Pure Java. */
public final class Plain {
    private Plain() {}

    public static Reader reader(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
        in.mark(1 << 16);
        byte[] head = new byte[1 << 16];
        int n = 0;
        for (int r; n < head.length && (r = in.read(head, n, head.length - n)) > 0; ) n += r;
        in.reset();
        Charset cs;
        int skip = 0;
        if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) { cs = StandardCharsets.UTF_8; skip = 3; }
        else if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) { cs = StandardCharsets.UTF_16LE; skip = 2; }
        else if (n >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) { cs = StandardCharsets.UTF_16BE; skip = 2; }
        else cs = utf8(head, n) ? StandardCharsets.UTF_8 : win1254();
        for (int i = 0; i < skip; i++) in.read();
        return new BufferedReader(new InputStreamReader(in, cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)), 1 << 16);
    }

    private static Charset win1254() {
        try { return Charset.forName("windows-1254"); } catch (RuntimeException e) { return StandardCharsets.ISO_8859_1; }
    }

    /** A cut-off multi-byte sequence at the end of the sample is fine (not end of input). */
    private static boolean utf8(byte[] b, int n) {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CoderResult r = d.decode(ByteBuffer.wrap(b, 0, n), CharBuffer.allocate(n + 1), false);
        return !r.isError();
    }
}
