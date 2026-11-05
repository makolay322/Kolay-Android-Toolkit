package app.kolay.app.ui;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Stroke icons on a 24-unit grid, drawn with paths so they stay crisp at any size and take any color. */
public final class Icon extends Drawable {
    public enum Kind { BACK, CHEVRON, CHECK, COPY, SHARE, SETTINGS, SCAN, KEY, REFRESH, LINK, QR, FLASH, BARCODE, CAMERA, CRESCENT, CLOCK,
        IMAGE, PIN, SPARKLE, PASTE, DOWNLOAD, LAYERS, PLAY, DOC, APPS, COMPASS, CHAT, SEND, DOCSCAN, TRASH, CROP, PLUS, NETSCAN }

    private final Kind kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public Icon(Kind kind, int color) {
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(color);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float s = b.width() / 24f;
        c.save(); c.translate(b.left, b.top); c.scale(s, s);
        p.setStrokeWidth(2f);
        path.reset();
        switch (kind) {
            case BACK: path.moveTo(15, 5); path.lineTo(8, 12); path.lineTo(15, 19); break;
            case CHEVRON: path.moveTo(9, 5); path.lineTo(16, 12); path.lineTo(9, 19); break;
            case CHECK: path.moveTo(5, 12.5f); path.lineTo(10, 17.5f); path.lineTo(19, 7); break;
            case COPY:
                path.addRoundRect(9, 9, 20, 20, 2.5f, 2.5f, Path.Direction.CW);
                path.moveTo(15, 9); path.lineTo(15, 6.5f); path.quadTo(15, 4, 12.5f, 4); path.lineTo(6.5f, 4);
                path.quadTo(4, 4, 4, 6.5f); path.lineTo(4, 12.5f); path.quadTo(4, 15, 6.5f, 15); path.lineTo(9, 15);
                break;
            case SHARE:
                path.addCircle(18, 5.5f, 2.6f, Path.Direction.CW); path.addCircle(6, 12, 2.6f, Path.Direction.CW); path.addCircle(18, 18.5f, 2.6f, Path.Direction.CW);
                path.moveTo(8.2f, 10.8f); path.lineTo(15.8f, 6.7f); path.moveTo(8.2f, 13.2f); path.lineTo(15.8f, 17.3f);
                break;
            case SETTINGS:
                slider(7, 15); slider(12, 9); slider(17, 16);
                break;
            case SCAN:
                path.moveTo(4, 8.5f); path.lineTo(4, 6.5f); path.quadTo(4, 4, 6.5f, 4); path.lineTo(8.5f, 4);
                path.moveTo(15.5f, 4); path.lineTo(17.5f, 4); path.quadTo(20, 4, 20, 6.5f); path.lineTo(20, 8.5f);
                path.moveTo(20, 15.5f); path.lineTo(20, 17.5f); path.quadTo(20, 20, 17.5f, 20); path.lineTo(15.5f, 20);
                path.moveTo(8.5f, 20); path.lineTo(6.5f, 20); path.quadTo(4, 20, 4, 17.5f); path.lineTo(4, 15.5f);
                path.moveTo(8, 12); path.lineTo(16, 12);
                break;
            case KEY:
                path.addCircle(8, 15.5f, 3.6f, Path.Direction.CW);
                path.moveTo(10.6f, 12.9f); path.lineTo(19, 4.5f); path.moveTo(16, 7.5f); path.lineTo(18.5f, 10);
                break;
            case REFRESH:
                path.addArc(new RectF(5, 5, 19, 19), -50, 280);
                path.moveTo(16.5f, 3.5f); path.lineTo(17.2f, 6.9f); path.lineTo(13.8f, 7.6f);
                break;
            case QR:
                path.addRoundRect(4, 4, 10, 10, 1.5f, 1.5f, Path.Direction.CW);
                path.addRoundRect(14, 4, 20, 10, 1.5f, 1.5f, Path.Direction.CW);
                path.addRoundRect(4, 14, 10, 20, 1.5f, 1.5f, Path.Direction.CW);
                path.moveTo(14, 14); path.lineTo(14, 20); path.moveTo(17, 14); path.lineTo(20, 14);
                path.moveTo(17, 17.5f); path.lineTo(20, 17.5f); path.moveTo(17.5f, 20); path.lineTo(20, 20);
                break;
            case FLASH:
                path.moveTo(13, 3); path.lineTo(6, 13.5f); path.lineTo(12, 13.5f); path.lineTo(11, 21); path.lineTo(18, 10.5f); path.lineTo(12, 10.5f); path.close();
                break;
            case BARCODE:
                for (int x = 4; x <= 20; x += 4) { path.moveTo(x, 5); path.lineTo(x, 19); }
                break;
            case CAMERA:
                path.addRoundRect(3.5f, 7, 20.5f, 19.5f, 3, 3, Path.Direction.CW);
                path.addCircle(12, 13.2f, 3.3f, Path.Direction.CW);
                path.moveTo(8.5f, 7); path.lineTo(9.8f, 4.5f); path.lineTo(14.2f, 4.5f); path.lineTo(15.5f, 7);
                break;
            case CRESCENT: {
                Path o = new Path(), i = new Path();
                o.addCircle(12, 12, 8, Path.Direction.CW); i.addCircle(16, 9.5f, 6.6f, Path.Direction.CW);
                o.op(i, Path.Op.DIFFERENCE); path.set(o);
                break;
            }
            case CLOCK:
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                path.moveTo(12, 7); path.lineTo(12, 12); path.lineTo(15.5f, 14);
                break;
            case IMAGE:
                path.addRoundRect(3.5f, 5, 20.5f, 19, 2.5f, 2.5f, Path.Direction.CW);
                path.addCircle(9, 9.8f, 1.7f, Path.Direction.CW);
                path.moveTo(4, 17); path.lineTo(9.5f, 12.5f); path.lineTo(13, 15.5f); path.lineTo(15.5f, 13.5f); path.lineTo(20, 17);
                break;
            case PIN:
                path.moveTo(12, 21); path.cubicTo(8, 16.8f, 5.5f, 13.6f, 5.5f, 10); path.cubicTo(5.5f, 6.2f, 8.4f, 3.5f, 12, 3.5f);
                path.cubicTo(15.6f, 3.5f, 18.5f, 6.2f, 18.5f, 10); path.cubicTo(18.5f, 13.6f, 16, 16.8f, 12, 21); path.close();
                path.addCircle(12, 10, 2.5f, Path.Direction.CW);
                break;
            case SPARKLE:
                path.moveTo(10.5f, 6); path.quadTo(11.4f, 12.6f, 18, 13.5f); path.quadTo(11.4f, 14.4f, 10.5f, 21);
                path.quadTo(9.6f, 14.4f, 3, 13.5f); path.quadTo(9.6f, 12.6f, 10.5f, 6); path.close();
                path.moveTo(18, 3); path.lineTo(18, 7); path.moveTo(16, 5); path.lineTo(20, 5);
                break;
            case PASTE:
                path.moveTo(9, 5); path.lineTo(7, 5); path.quadTo(5, 5, 5, 7); path.lineTo(5, 19); path.quadTo(5, 21, 7, 21);
                path.lineTo(17, 21); path.quadTo(19, 21, 19, 19); path.lineTo(19, 7); path.quadTo(19, 5, 17, 5); path.lineTo(15, 5);
                path.addRoundRect(9, 3, 15, 7, 1.5f, 1.5f, Path.Direction.CW);
                path.moveTo(8.5f, 12); path.lineTo(15.5f, 12); path.moveTo(8.5f, 16); path.lineTo(13, 16);
                break;
            case DOWNLOAD:
                path.moveTo(12, 4); path.lineTo(12, 15); path.moveTo(7.5f, 10.5f); path.lineTo(12, 15); path.lineTo(16.5f, 10.5f);
                path.moveTo(5, 19.5f); path.lineTo(19, 19.5f);
                break;
            case LAYERS:
                path.moveTo(12, 4); path.lineTo(20, 8); path.lineTo(12, 12); path.lineTo(4, 8); path.close();
                path.moveTo(4, 12); path.lineTo(12, 16); path.lineTo(20, 12);
                path.moveTo(4, 16); path.lineTo(12, 20); path.lineTo(20, 16);
                break;
            case PLAY:
                path.addRoundRect(3, 5.5f, 21, 18.5f, 4, 4, Path.Direction.CW);
                path.moveTo(10.2f, 9); path.lineTo(15, 12); path.lineTo(10.2f, 15); path.close();
                break;
            case APPS:   // three app squares and an "update" arrow in the fourth corner
                path.addRoundRect(4, 4, 10.5f, 10.5f, 2, 2, Path.Direction.CW);
                path.addRoundRect(13.5f, 4, 20, 10.5f, 2, 2, Path.Direction.CW);
                path.addRoundRect(4, 13.5f, 10.5f, 20, 2, 2, Path.Direction.CW);
                path.moveTo(16.75f, 20.5f); path.lineTo(16.75f, 13.5f); path.moveTo(13.75f, 16.5f); path.lineTo(16.75f, 13.5f); path.lineTo(19.75f, 16.5f);
                break;
            case DOC:
                path.moveTo(14, 3.5f); path.lineTo(7.5f, 3.5f); path.quadTo(5.5f, 3.5f, 5.5f, 5.5f); path.lineTo(5.5f, 18.5f);
                path.quadTo(5.5f, 20.5f, 7.5f, 20.5f); path.lineTo(16.5f, 20.5f); path.quadTo(18.5f, 20.5f, 18.5f, 18.5f); path.lineTo(18.5f, 8); path.close();
                path.moveTo(14, 3.5f); path.lineTo(14, 8); path.lineTo(18.5f, 8);
                path.moveTo(9, 12.5f); path.lineTo(15, 12.5f); path.moveTo(9, 16); path.lineTo(13.5f, 16);
                break;
            case COMPASS:   // ring with a diamond needle pointing up-right
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                path.moveTo(15.5f, 8.5f); path.lineTo(13.2f, 13.2f); path.lineTo(8.5f, 15.5f); path.lineTo(10.8f, 10.8f); path.close();
                break;
            case CHAT:      // speech bubble with a tail at the lower left and two text lines
                path.moveTo(7.5f, 4.5f); path.lineTo(16.5f, 4.5f); path.quadTo(20, 4.5f, 20, 8); path.lineTo(20, 13);
                path.quadTo(20, 16.5f, 16.5f, 16.5f); path.lineTo(10.5f, 16.5f); path.lineTo(6, 20); path.lineTo(6.5f, 16.3f);
                path.quadTo(4, 15.7f, 4, 13); path.lineTo(4, 8); path.quadTo(4, 4.5f, 7.5f, 4.5f); path.close();
                path.moveTo(8.5f, 9); path.lineTo(15.5f, 9); path.moveTo(8.5f, 12.5f); path.lineTo(13, 12.5f);
                break;
            case SEND:      // arrow pointing up
                path.moveTo(12, 19); path.lineTo(12, 5.5f); path.moveTo(6.5f, 11); path.lineTo(12, 5.5f); path.lineTo(17.5f, 11);
                break;
            case DOCSCAN:   // a page with text lines inside scanner corner brackets
                path.moveTo(3.5f, 7.5f); path.lineTo(3.5f, 5.5f); path.quadTo(3.5f, 3.5f, 5.5f, 3.5f); path.lineTo(7.5f, 3.5f);
                path.moveTo(16.5f, 3.5f); path.lineTo(18.5f, 3.5f); path.quadTo(20.5f, 3.5f, 20.5f, 5.5f); path.lineTo(20.5f, 7.5f);
                path.moveTo(20.5f, 16.5f); path.lineTo(20.5f, 18.5f); path.quadTo(20.5f, 20.5f, 18.5f, 20.5f); path.lineTo(16.5f, 20.5f);
                path.moveTo(7.5f, 20.5f); path.lineTo(5.5f, 20.5f); path.quadTo(3.5f, 20.5f, 3.5f, 18.5f); path.lineTo(3.5f, 16.5f);
                path.addRoundRect(7.5f, 6.5f, 16.5f, 17.5f, 1.5f, 1.5f, Path.Direction.CW);
                path.moveTo(10, 10); path.lineTo(14, 10); path.moveTo(10, 13); path.lineTo(14, 13);
                break;
            case NETSCAN:   // flatbed scanner with a scan line, Wi-Fi arcs above
                path.addRoundRect(3.5f, 13.5f, 20.5f, 20, 2.5f, 2.5f, Path.Direction.CW);
                path.moveTo(7, 17); path.lineTo(13.5f, 17);
                path.addArc(new RectF(9, 8, 15, 14), 225, 90);
                path.addArc(new RectF(6, 5, 18, 17), 225, 90);
                path.addCircle(12, 11, 0.6f, Path.Direction.CW);
                break;
            case TRASH:
                path.moveTo(4.5f, 6.5f); path.lineTo(19.5f, 6.5f);
                path.moveTo(9.5f, 6.5f); path.lineTo(9.5f, 4.5f); path.lineTo(14.5f, 4.5f); path.lineTo(14.5f, 6.5f);
                path.moveTo(6.5f, 6.5f); path.lineTo(7.5f, 19); path.quadTo(7.6f, 20, 8.6f, 20); path.lineTo(15.4f, 20);
                path.quadTo(16.4f, 20, 16.5f, 19); path.lineTo(17.5f, 6.5f);
                path.moveTo(10.5f, 10); path.lineTo(10.5f, 16.5f); path.moveTo(13.5f, 10); path.lineTo(13.5f, 16.5f);
                break;
            case CROP:
                path.moveTo(7, 3.5f); path.lineTo(7, 17); path.lineTo(20.5f, 17);
                path.moveTo(3.5f, 7); path.lineTo(17, 7); path.lineTo(17, 20.5f);
                break;
            case PLUS:
                path.moveTo(12, 5); path.lineTo(12, 19); path.moveTo(5, 12); path.lineTo(19, 12);
                break;
            case LINK:
                path.moveTo(10, 14); path.lineTo(14, 10);
                path.moveTo(8.5f, 11.5f); path.lineTo(6.8f, 13.2f); path.quadTo(5, 15.5f, 7, 17.3f); path.quadTo(9, 19, 10.8f, 17.2f); path.lineTo(12, 16);
                path.moveTo(15.5f, 12.5f); path.lineTo(17.2f, 10.8f); path.quadTo(19, 8.5f, 17, 6.7f); path.quadTo(15, 5, 13.2f, 6.8f); path.lineTo(12, 8);
                break;
        }
        c.drawPath(path, p);
        c.restore();
    }

    /** Horizontal line at y with a ring at x, leaving a gap around the ring. */
    private void slider(float y, float x) {
        path.moveTo(4, y); path.lineTo(x - 3.2f, y);
        path.moveTo(x + 3.2f, y); path.lineTo(20, y);
        path.addCircle(x, y, 2.2f, Path.Direction.CW);
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 24; }
    @Override public int getIntrinsicHeight() { return 24; }
}
