package app.kolay.app.tools.scan;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import app.kolay.app.doc.QuadDetector;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Back camera for the document scanner: preview on a TextureView (whole frame visible, letterboxed), a small YUV stream for
 * live page detection ({@link QuadDetector}, ~10 per second on its own thread) and full-resolution JPEG stills.
 * Same structure as {@code QrCamera}: each start() gets its own {@link Run}, so a quick stop/start never closes the newer
 * session. If the device refuses three streams the analysis stream is dropped (manual capture still works).
 * Frames are never stored or logged; stills go to the listener only.
 */
final class DocCamera {
    interface Listener {
        /** Main thread. Detected page outline in upright, normalised preview coordinates (TL, TR, BR, BL), or null. */
        void onQuad(float[] quad);
        /** Main thread. A still requested with {@link #capture}; upright via EXIF/pixels. */
        void onJpeg(byte[] jpeg);
        /** Main thread. A capture did not produce an image. */
        void onCaptureFailed();
        /** Main thread. The camera has been released. */
        void onError(String message);
    }

    private static final long MAX_JPEG_PX = 13_000_000L;

    private final TextureView tv;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CameraManager cm;
    private final String id;
    private final CameraCharacteristics ch;
    private final Size jpegSize, previewSize, analysisSize;
    private final int sensor;
    private final boolean torchAble;

    private Run cur;            // main thread only
    private boolean torch;      // main thread only
    private volatile boolean detect = true;

    DocCamera(Activity a, TextureView tv, Listener l) {
        this.tv = tv; this.l = l;
        CameraManager m = null; String back = null; CameraCharacteristics c = null;
        Size js = null, ps = null, as = null; boolean flash = false; int so = 90;
        try {
            m = (CameraManager) a.getSystemService(Context.CAMERA_SERVICE);
            for (String cid : m.getCameraIdList()) {
                CameraCharacteristics cc = m.getCameraCharacteristics(cid);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { back = cid; c = cc; break; }
            }
            if (c != null) {
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    js = pickJpeg(map.getOutputSizes(ImageFormat.JPEG));
                    if (js != null) {
                        float ar = js.getWidth() / (float) js.getHeight();
                        ps = pick(map.getOutputSizes(SurfaceTexture.class), ar, 1920, 1440, 1920);
                        if (ps == null) ps = pick(map.getOutputSizes(SurfaceTexture.class), -1, 1920, 1440, 1920);
                        if (ps != null) as = pick(map.getOutputSizes(ImageFormat.YUV_420_888), ps.getWidth() / (float) ps.getHeight(), 800, 600, 640);
                    }
                }
                Boolean f = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                flash = f != null && f;
                Integer o = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (o != null) so = o;
            }
        } catch (Exception ignored) { back = null; }
        cm = m; id = back; ch = c; jpegSize = js; previewSize = ps; analysisSize = as; torchAble = flash; sensor = so;

        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hgt) { fit(w, hgt); if (cur != null) cur.open(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hgt) { fit(w, hgt); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { stop(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
    }

    /** Largest 4:3 JPEG size up to ~13 MP, else the largest up to that, else the smallest. */
    private static Size pickJpeg(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best43 = null, best = null, smallest = null;
        for (Size s : sizes) {
            long a = (long) s.getWidth() * s.getHeight();
            if (smallest == null || a < area(smallest)) smallest = s;
            if (a > MAX_JPEG_PX) continue;
            if (best == null || a > area(best)) best = s;
            if (Math.abs(s.getWidth() / (float) s.getHeight() - 4f / 3f) < 0.02f && (best43 == null || a > area(best43))) best43 = s;
        }
        return best43 != null ? best43 : best != null ? best : smallest;
    }

    /** Size with aspect {@code ar} (any when < 0) inside maxW × maxH, closest to {@code idealW} from below, else the largest. */
    private static Size pick(Size[] sizes, float ar, int maxW, int maxH, int idealW) {
        if (sizes == null) return null;
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > maxW || s.getHeight() > maxH || s.getWidth() < 320) continue;
            if (ar > 0 && Math.abs(s.getWidth() / (float) s.getHeight() - ar) > 0.02f) continue;
            if (best == null) { best = s; continue; }
            int d = Math.abs(s.getWidth() - idealW), bd = Math.abs(best.getWidth() - idealW);
            if (d < bd || (d == bd && s.getWidth() > best.getWidth())) best = s;
        }
        return best;
    }

    private static long area(Size s) { return (long) s.getWidth() * s.getHeight(); }

    boolean available() { return id != null && jpegSize != null && previewSize != null; }
    boolean hasTorch() { return torchAble; }
    boolean torchOn() { return torch; }
    void setDetect(boolean on) { detect = on; }

    /** Upright preview size (width, height): the overlay maps normalised coordinates with it. */
    float[] content() {
        if (previewSize == null) return new float[]{3, 4};
        boolean swap = sensor % 180 == 90;
        return new float[]{swap ? previewSize.getHeight() : previewSize.getWidth(), swap ? previewSize.getWidth() : previewSize.getHeight()};
    }

    /** Whole frame visible (letterbox): the SurfaceTexture is stretched to the view; undo that and scale to fit inside. */
    private void fit(int vw, int vh) {
        if (previewSize == null || vw == 0 || vh == 0) return;
        float[] cs = content();
        float s = Math.min(vw / cs[0], vh / cs[1]);
        Matrix m = new Matrix();
        m.setScale(cs[0] * s / vw, cs[1] * s / vh, vw / 2f, vh / 2f);
        tv.setTransform(m);
    }

    // ---- lifecycle (main thread) ---------------------------------------------------------------------------

    /** Needs the CAMERA permission. Idempotent. */
    void start() {
        if (cur != null) return;
        if (!available()) { l.onError("Bu cihazda uygun bir arka kamera bulunamadı."); return; }
        cur = new Run();
        if (tv.isAvailable()) { fit(tv.getWidth(), tv.getHeight()); cur.open(); }
    }

    /** Releases the camera. Idempotent. */
    void stop() {
        Run r = cur;
        cur = null; torch = false;
        if (r != null) r.close();
    }

    void setTorch(boolean on) {
        if (!torchAble || cur == null) return;
        torch = on;
        cur.applyTorch(on);
    }

    /**
     * Takes one full-resolution still. {@code deviceRotation}: how far the phone is turned clockwise from portrait
     * (0/90/180/270), so a page shot in landscape comes out upright. @return false when no session is ready or one is running.
     */
    boolean capture(int deviceRotation) {
        Run r = cur;
        return r != null && r.capture((sensor + deviceRotation) % 360);
    }

    private void fail(Run r, String msg) {
        main.post(() -> {
            if (cur != r) return;
            stop();
            l.onError(msg);
        });
    }

    private final class Run {
        private final HandlerThread th = new HandlerThread("doc-cam"), dth = new HandlerThread("doc-detect");
        private final Handler h, dh;
        private final QuadDetector detector = new QuadDetector();
        private volatile boolean alive = true, opening, inFlight, capturing;
        private CameraDevice dev;
        private CameraCaptureSession sess;
        private ImageReader jpeg, yuv;
        private Surface previewSurface;
        private CaptureRequest.Builder req;
        private boolean wantTorch, withAnalysis = analysisSize != null;
        private long lastDetect;
        private byte[] frame;   // luma copy of the analysed frame, reused (detection thread only)
        private volatile int shots;

        Run() { th.start(); dth.start(); h = new Handler(th.getLooper()); dh = new Handler(dth.getLooper()); }

        void open() {
            if (opening || !alive) return;
            opening = true;
            h.post(this::openOnThread);
        }

        @SuppressWarnings("MissingPermission")
        private void openOnThread() {
            inFlight = true;
            try {
                cm.openCamera(id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice cd) {
                        inFlight = false;
                        if (!alive) { cd.close(); return; }
                        dev = cd;
                        createSession();
                    }
                    @Override public void onDisconnected(CameraDevice cd) { inFlight = false; cd.close(); fail(Run.this, "Kamera başka bir uygulama tarafından alındı."); }
                    @Override public void onError(CameraDevice cd, int err) { inFlight = false; cd.close(); fail(Run.this, "Kamera açılamadı."); }
                    @Override public void onClosed(CameraDevice cd) { th.quitSafely(); }
                }, h);
            } catch (Exception e) { inFlight = false; fail(this, "Kamera açılamadı."); }
        }

        private void createSession() {
            try {
                SurfaceTexture st = tv.getSurfaceTexture();
                if (st == null) { fail(this, "Kamera önizlemesi hazırlanamadı."); return; }
                st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                if (previewSurface == null) previewSurface = new Surface(st);
                if (jpeg == null) {
                    jpeg = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
                    jpeg.setOnImageAvailableListener(this::onJpeg, h);
                }
                if (withAnalysis && yuv == null) {
                    yuv = ImageReader.newInstance(analysisSize.getWidth(), analysisSize.getHeight(), ImageFormat.YUV_420_888, 2);
                    yuv.setOnImageAvailableListener(this::onFrame, dh);
                }
                req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                req.addTarget(previewSurface);
                if (withAnalysis) req.addTarget(yuv.getSurface());
                setAf(req);

                List<OutputConfiguration> outs = new ArrayList<>();
                outs.add(new OutputConfiguration(previewSurface));
                if (withAnalysis) outs.add(new OutputConfiguration(yuv.getSurface()));
                outs.add(new OutputConfiguration(jpeg.getSurface()));
                SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, h::post,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (!alive) { s.close(); return; }
                            sess = s;
                            repeat();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            if (withAnalysis && alive) {   // retry without the live-detection stream
                                withAnalysis = false;
                                if (yuv != null) { yuv.close(); yuv = null; }
                                main.post(() -> { if (cur == Run.this) l.onQuad(null); });
                                createSession();
                            } else fail(Run.this, "Kamera başlatılamadı.");
                        }
                    });
                dev.createCaptureSession(sc);
            } catch (Exception e) { fail(this, "Kamera başlatılamadı."); }
        }

        private void setAf(CaptureRequest.Builder b) {
            int[] af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (af != null && Arrays.stream(af).anyMatch(m -> m == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        }

        private void repeat() {
            if (sess == null || req == null) return;
            try {
                req.set(CaptureRequest.FLASH_MODE, wantTorch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
                sess.setRepeatingRequest(req.build(), null, h);
            } catch (Exception ignored) { }
        }

        void applyTorch(boolean on) { h.post(() -> { wantTorch = on; repeat(); }); }

        boolean capture(int orientation) {
            if (capturing || !alive) return false;
            capturing = true;
            final int token = ++shots;
            h.postDelayed(() -> {   // a still that never arrives must not block the shutter for good
                if (capturing && shots == token) { capturing = false; main.post(() -> { if (cur == this) l.onCaptureFailed(); }); }
            }, 6000);
            h.post(() -> {
                if (sess == null || dev == null || jpeg == null) { capturing = false; main.post(() -> { if (cur == this) l.onCaptureFailed(); }); return; }
                try {
                    CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    b.addTarget(jpeg.getSurface());
                    b.addTarget(previewSurface);
                    setAf(b);
                    b.set(CaptureRequest.FLASH_MODE, wantTorch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
                    b.set(CaptureRequest.JPEG_ORIENTATION, orientation);
                    b.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
                    sess.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                            capturing = false;
                            main.post(() -> { if (cur == Run.this) l.onCaptureFailed(); });
                        }
                    }, h);
                } catch (Exception e) {
                    capturing = false;
                    main.post(() -> { if (cur == this) l.onCaptureFailed(); });
                }
            });
            return true;
        }

        private void onJpeg(ImageReader r) {
            Image img = null;
            try {
                img = r.acquireNextImage();
                if (img == null) return;
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                capturing = false;
                if (!alive) return;
                main.post(() -> { if (cur == this) l.onJpeg(data); });
            } catch (Exception | OutOfMemoryError e) {
                capturing = false;
                main.post(() -> { if (cur == this) l.onCaptureFailed(); });
            } finally {
                if (img != null) img.close();
            }
        }

        private void onFrame(ImageReader r) {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null || !alive || !detect) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastDetect < 90) return;
                lastDetect = now;
                Image.Plane y = img.getPlanes()[0];
                int w = img.getWidth(), hgt = img.getHeight(), stride = y.getRowStride();
                ByteBuffer buf = y.getBuffer();
                int len = Math.min(buf.remaining(), stride * hgt);
                if (frame == null || frame.length < len) frame = new byte[len];
                buf.get(frame, 0, len);
                img.close(); img = null;   // free the buffer before the (longer) analysis
                if (len < stride * (hgt - 1) + w) return;
                float[] q = QuadDetector.rotate(detector.detect(frame, stride, w, hgt), sensor);
                main.post(() -> { if (cur == this && detect) l.onQuad(q); });
            } catch (Exception ignored) {
                // a bad frame must not end scanning
            } finally {
                if (img != null) img.close();
            }
        }

        void close() {
            alive = false;
            h.post(() -> {
                try { if (sess != null) sess.close(); } catch (Exception ignored) { }
                try { if (dev != null) dev.close(); } catch (Exception ignored) { }
                try { if (jpeg != null) jpeg.close(); } catch (Exception ignored) { }
                try { if (yuv != null) yuv.close(); } catch (Exception ignored) { }
                if (previewSurface != null) previewSurface.release();
                boolean hadDevice = dev != null;
                sess = null; dev = null; jpeg = null; yuv = null; previewSurface = null; req = null;
                dth.quitSafely();
                if (!hadDevice && !inFlight) th.quitSafely();
            });
        }
    }
}
