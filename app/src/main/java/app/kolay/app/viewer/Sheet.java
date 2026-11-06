package app.kolay.app.viewer;

import java.util.ArrayList;

/**
 * One grid of display strings (spreadsheet sheet or CSV); rows/cells may be null. Pure Java. Synchronized because the
 * grid already draws the first rows while the parser is still filling the rest.
 */
public final class Sheet {
    public static final int MAX_ROWS = 200_000, MAX_COLS = 1024, MAX_CELLS = 2_000_000;

    public final String name;
    final ArrayList<String[]> rows = new ArrayList<>();
    final ArrayList<byte[]> nums = new ArrayList<>();   // 1 = numeric (right-aligned)
    public volatile int cols;
    public volatile boolean truncated;
    /** Column widths from the file in characters (0 = measure); may be shorter than {@link #cols}. */
    public volatile float[] widths = new float[0];
    private int cells;

    public Sheet(String name) { this.name = name; }

    public synchronized int rowCount() { return rows.size(); }

    public synchronized String get(int r, int c) {
        if (r < 0 || r >= rows.size()) return null;
        String[] row = rows.get(r);
        return row == null || c < 0 || c >= row.length ? null : row[c];
    }

    public synchronized boolean numeric(int r, int c) {
        byte[] n = r < 0 || r >= nums.size() ? null : nums.get(r);
        return n != null && c >= 0 && c < n.length && n[c] == 1;
    }

    /** @return false once the size caps are reached (the rest is dropped and {@link #truncated} set). */
    synchronized boolean put(int r, int c, String v, boolean num) {
        if (v == null || v.isEmpty()) return true;
        if (r >= MAX_ROWS || c >= MAX_COLS || cells >= MAX_CELLS) { truncated = true; return false; }
        while (rows.size() <= r) { rows.add(null); nums.add(null); }
        String[] row = rows.get(r);
        byte[] n = nums.get(r);
        if (row == null || row.length <= c) {
            int len = Math.max(c + 1, row == null ? 4 : Math.min(MAX_COLS, row.length * 2));
            String[] nr = new String[len]; byte[] nn = new byte[len];
            if (row != null) { System.arraycopy(row, 0, nr, 0, row.length); System.arraycopy(n, 0, nn, 0, n.length); }
            rows.set(r, row = nr); nums.set(r, n = nn);
        }
        if (row[c] == null) cells++;
        row[c] = v; n[c] = (byte) (num ? 1 : 0);
        if (c + 1 > cols) cols = c + 1;
        return true;
    }

    /** Column name: 0 → A, 26 → AA. */
    public static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        for (c++; c > 0; c = (c - 1) / 26) sb.insert(0, (char) ('A' + (c - 1) % 26));
        return sb.toString();
    }
}
