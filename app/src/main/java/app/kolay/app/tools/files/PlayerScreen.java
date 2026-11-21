package app.kolay.app.tools.files;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.TimedText;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import app.kolay.app.ui.Arc;
import app.kolay.app.ui.Icon;
import app.kolay.app.ui.Screen;
import app.kolay.app.ui.Sheet;
import app.kolay.app.ui.Ui;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in video player on the platform's {@link MediaPlayer} (the phone's own hardware/software codecs; nothing bundled).
 * Local files are read from their path, Samba files through {@link ShareProvider}'s seekable stream (no download).
 * Fullscreen while open: system bars hidden, orientation follows the video, screen kept on while playing; all restored on exit.
 * Gestures: tap = controls, double tap left/right = ∓10 s, horizontal drag = seek, vertical drag left = brightness (this window only),
 * right = media volume. The position is remembered per file (hashed key, see {@link #remember}); a same-name {@code .srt} next to the
 * video is loaded as a timed-text track. A file the phone cannot play ends in an error page with "Birlikte aç".
 */
final class PlayerScreen extends Screen {
    private static final String[] SPEED_LABELS = {"0,5×", "0,75×", "1× (normal)", "1,25×", "1,5×", "1,75×", "2×"};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final String[] ASPECTS = {"Sığdır", "Doldur", "Uzat"};
    private static final int FIT = 0, FILL = 1, STRETCH = 2;
    private static final int S_IDLE = 0, S_PREPARING = 1, S_READY = 2;   // READY = prepared / started / paused / completed
    private static final long SKIP_MS = 10_000, HIDE_MS = 3000, TICK_MS = 400;
    private static final long SRT_MAX = 1 << 20;
    private static final int RESUME_MAX = 100;

    private final FileSource src;
    private final FileItem it;
    private final boolean remote;
    private final Uri streamUri;   // Samba only
    private final String resumeKey;

    private MediaPlayer mp;
    private int state = S_IDLE;
    private int generation;        // bumped on every open/close: late background results of an older open are dropped
    private boolean playing, visible = true, closed, failed, locked, speedSet, resumeOnGain, hasVideo, oriented;
    private long duration, lastPos, pendingSeek = -1, prevTick = -1;
    private int vw, vh, aspect = FIT;
    private float speed = 1f;
    private File subFile;          // sidecar .srt converted to UTF-8 in the cache
    private boolean subAdded, subOff;

    // window state to restore
    private int origOrientation;
    private float origBrightness;
    private int origBehavior = -1;

    private AudioManager am;
    private AudioFocusRequest focus;
    private BroadcastReceiver noisy;

    private FrameLayout page, controls, errorLayer;
    private LinearLayout top, bottom;
    private TextureView tex;
    private Surface surface;
    private TextView title, subs, badge, cur, total, speedBtn;
    private View spinner, subBtn, audioBtn, lockOnly;
    private ImageView playIcon;
    private SeekBar seek;
    private boolean dragging;

    // gesture state
    private static final int G_NONE = 0, G_SEEK = 1, G_BRIGHT = 2, G_VOLUME = 3, G_IGNORE = 4;
    private int gesture;
    private float downX, downY, startValue;
    private long seekTarget;

    PlayerScreen(FileSource src, FileItem it) {
        this.src = src; this.it = it;
        remote = src instanceof SmbSource;
        streamUri = remote ? ShareProvider.stream((SmbSource) src, it.path, it.name, it.size) : null;
        resumeKey = hash(remote ? "s:" + ((SmbSource) src).server.id + ":" + it.path : "f:" + it.path);
    }

    /** True for files the player takes on a tap (by type; whether the phone can decode it shows only when it is opened). */
    static boolean canPlay(String name) { return Files.mime(name).startsWith("video/"); }

    // ------------------------------------------------------------ views

    @Override public View create() {
        Window w = c.a.getWindow();
        origOrientation = c.a.getRequestedOrientation();
        origBrightness = w.getAttributes().screenBrightness;
        am = c.a.getSystemService(AudioManager.class);

        page = new FrameLayout(c.a);
        page.setBackgroundColor(c.p.video);
        tex = new TextureView(c.a);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                surface = new Surface(st);
                if (mp != null && !failed) mp.setSurface(surface);
                applyAspect();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { applyAspect(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (mp != null && !failed) { try { mp.setSurface(null); } catch (RuntimeException ignored) { } }
                if (surface != null) { surface.release(); surface = null; }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        page.addView(tex, new FrameLayout.LayoutParams(-1, -1));

        subs = Ui.text(c, "", 18, c.p.onVideo, 500);
        subs.setGravity(Gravity.CENTER);
        subs.setShadowLayer(c.dp(3), 0, c.dp(1), c.p.video);
        subs.setLineSpacing(0, 1.15f);
        page.addView(subs, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        View touch = new View(c.a);
        GestureDetector gd = new GestureDetector(c.a, new Gestures());
        gd.setIsLongpressEnabled(false);   // a slow start of a drag must still become a drag
        touch.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endGesture();
            return true;
        });
        page.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        badge = Ui.text(c, "", Ui.T_HEAD, c.p.onVideo, 700);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(c.dp(18), c.dp(12), c.dp(18), c.dp(12));
        badge.setBackground(Ui.rounded(c, c.p.scrim, Ui.R_BTN));
        badge.setVisibility(View.GONE);
        page.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        spinner = new Arc(c.a, alpha(c.p.onVideo, 0x33), c.p.onVideo, c.dp(4));
        page.addView(spinner, new FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.CENTER));

        controls = new FrameLayout(c.a);
        buildControls();
        page.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        lockOnly = roundButton(Icon.Kind.LOCK, "Kilidi aç", 48, () -> { locked = false; lockOnly.setVisibility(View.GONE); showControls(); });
        lockOnly.setBackground(Ui.rounded(c, c.p.primary, 24));
        ((ImageView) ((FrameLayout) lockOnly).getChildAt(0)).setImageDrawable(new Icon(Icon.Kind.LOCK, c.p.onPrimary));
        lockOnly.setVisibility(View.GONE);
        page.addView(lockOnly, new FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.BOTTOM | Gravity.START));

        noisy = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pause(); }
        };
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        c.a.registerReceiver(noisy, f);   // protected system broadcast: no exported flag needed

        immersive(true);
        loadSidecar();
        open(-1);
        return page;
    }

    private void buildControls() {
        top = new LinearLayout(c.a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(Ui.gradient(c, c.p.scrim, Color.TRANSPARENT, 0, GradientDrawable.Orientation.TOP_BOTTOM));
        top.addView(roundButton(Icon.Kind.BACK, "Geri", 44, this::exit));
        title = Ui.text(c, it.name, Ui.T_HEAD, c.p.onVideo, 600);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        subBtn = roundButton(Icon.Kind.SUBTITLE, "Altyazı", 44, () -> chooseTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT));
        audioBtn = roundButton(Icon.Kind.MUSIC, "Ses parçası", 44, () -> chooseTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO));
        subBtn.setVisibility(View.GONE); audioBtn.setVisibility(View.GONE);
        top.addView(subBtn); top.addView(audioBtn);
        speedBtn = Ui.text(c, "1×", Ui.T_BODY, c.p.onVideo, 700);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setMinWidth(c.dp(52));
        speedBtn.setBackground(Ui.ripple(c, 22));
        speedBtn.setContentDescription("Oynatma hızı");
        speedBtn.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); chooseSpeed(); });
        top.addView(speedBtn, new LinearLayout.LayoutParams(-2, c.dp(44)));
        top.addView(roundButton(Icon.Kind.ASPECT, "Görüntü oranı", 44, this::cycleAspect));
        controls.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout mid = new LinearLayout(c.a);
        mid.setGravity(Gravity.CENTER);
        mid.addView(skipButton(Icon.Kind.REWIND, "10 saniye geri", -SKIP_MS));
        FrameLayout play = roundButton(Icon.Kind.START, "Oynat / duraklat", 68, this::togglePlay);
        play.setBackground(Ui.oval(c.p.scrim));
        playIcon = (ImageView) play.getChildAt(0);
        playIcon.getLayoutParams().width = c.dp(30); playIcon.getLayoutParams().height = c.dp(30);
        mid.addView(play, Ui.lp(c.dp(68), c.dp(68), c.dp(36), 0, c.dp(36), 0));
        mid.addView(skipButton(Icon.Kind.FORWARD, "10 saniye ileri", SKIP_MS));
        controls.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        bottom = new LinearLayout(c.a);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(Ui.gradient(c, Color.TRANSPARENT, c.p.scrim, 0, GradientDrawable.Orientation.TOP_BOTTOM));
        bottom.addView(roundButton(Icon.Kind.LOCK, "Kilitle", 44, this::lock));
        cur = Ui.text(c, "0:00", Ui.T_SUB, c.p.onVideo, 600);
        bottom.addView(cur, Ui.lp(-2, -2, c.dp(6), 0, 0, 0));
        seek = new SeekBar(c.a);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(c.p.primary));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(c.p.primary));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(alpha(c.p.onVideo, 0x66)));
        seek.setEnabled(false);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) cur.setText(time(p)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; c.ui.removeCallbacks(hide); }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; seekTo(s.getProgress()); scheduleHide(); }
        });
        bottom.addView(seek, new LinearLayout.LayoutParams(0, c.dp(44), 1f));
        total = Ui.text(c, "0:00", Ui.T_SUB, c.p.onVideo, 600);
        bottom.addView(total, Ui.lp(-2, -2, 0, 0, c.dp(12), 0));
        controls.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        controls.setVisibility(View.GONE);
    }

    private FrameLayout roundButton(Icon.Kind k, String desc, int sizeDp, Runnable r) {
        FrameLayout f = new FrameLayout(c.a);
        f.setBackground(Ui.ripple(c, sizeDp / 2f));
        f.addView(Ui.icon(c, k, c.p.onVideo, 24), new FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.CENTER));
        f.setContentDescription(desc);
        f.setClickable(true);
        f.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); r.run(); scheduleHide(); });
        f.setLayoutParams(new LinearLayout.LayoutParams(c.dp(sizeDp), c.dp(sizeDp)));
        return f;
    }

    private FrameLayout skipButton(Icon.Kind k, String desc, long delta) {
        FrameLayout f = roundButton(k, desc, 56, () -> skip(delta));
        ((FrameLayout.LayoutParams) f.getChildAt(0).getLayoutParams()).width = c.dp(34);
        ((FrameLayout.LayoutParams) f.getChildAt(0).getLayoutParams()).height = c.dp(34);
        TextView n = Ui.text(c, "10", 10, c.p.onVideo, 700);
        f.addView(n, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return f;
    }

    @Override public boolean edgeToEdge() { return true; }
    @Override public boolean darkStatusIcons() { return false; }

    @Override public void onInsets() {
        int g = c.dp(8);
        top.setPadding(c.insets.left + g, c.insets.top + g, c.insets.right + g, c.dp(24));
        bottom.setPadding(c.insets.left + g, c.dp(24), c.insets.right + g, c.insets.bottom + g);
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) lockOnly.getLayoutParams();
        l.setMargins(c.insets.left + c.dp(Ui.GUTTER), 0, 0, c.insets.bottom + c.dp(Ui.GUTTER));
        lockOnly.setLayoutParams(l);
        placeSubs();
        if (errorLayer != null) errorLayer.setPadding(c.insets.left, c.insets.top, c.insets.right, c.insets.bottom);
    }

    /** Subtitles sit above the bottom controls while those are shown. */
    private void placeSubs() {
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) subs.getLayoutParams();
        int side = c.insets.left + c.insets.right + c.dp(Ui.GUTTER);
        l.setMargins(side, 0, side, c.insets.bottom + c.dp(controls.getVisibility() == View.VISIBLE ? 76 : 24));
        subs.setLayoutParams(l);
    }

    // ------------------------------------------------------------ lifecycle

    @Override public void onShown() { immersive(true); }

    @Override public void onResume() { visible = true; if (!closed) immersive(true); }

    @Override public void onPause() {
        visible = false;
        resumeOnGain = false;
        pause();
        savePosition();
    }

    @Override public boolean onBack() {
        if (locked) { showLockOnly(); Ui.toast(c, "Ekran kilitli; önce kilidi aç"); return true; }
        close();
        return false;
    }

    @Override public void onDestroy() { close(); }

    private void exit() { if (!onBack()) c.nav.pop(); }

    /** Releases everything and gives the window back as it was; safe to call more than once. */
    private void close() {
        if (closed) return;
        savePosition();
        closed = true;
        generation++;
        c.ui.removeCallbacks(tick); c.ui.removeCallbacks(hide); c.ui.removeCallbacks(hideBadge);
        c.ui.removeCallbacks(hideLock); c.ui.removeCallbacks(slowSeek);
        releasePlayer();
        if (focus != null) am.abandonAudioFocusRequest(focus);
        try { c.a.unregisterReceiver(noisy); } catch (IllegalArgumentException ignored) { }
        tex.setKeepScreenOn(false);
        Window w = c.a.getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = origBrightness;
        w.setAttributes(lp);
        immersive(false);
        c.a.setRequestedOrientation(origOrientation);
        if (subFile != null) subFile.delete();
    }

    private void releasePlayer() {
        if (mp == null) return;
        try { mp.release(); } catch (RuntimeException ignored) { }
        mp = null;
        state = S_IDLE;
        playing = false;
    }

    /** System bars hidden (swipe shows them for a moment) or back as before. */
    private void immersive(boolean on) {
        Window w = c.a.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = w.getInsetsController();
            if (ic == null) return;
            if (on) {
                if (origBehavior < 0) origBehavior = ic.getSystemBarsBehavior();
                ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                ic.hide(WindowInsets.Type.systemBars());
            } else {
                ic.show(WindowInsets.Type.systemBars());
                if (origBehavior >= 0) ic.setSystemBarsBehavior(origBehavior);
            }
        } else {
            View d = w.getDecorView();
            int f = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
            d.setSystemUiVisibility(on ? d.getSystemUiVisibility() | f : d.getSystemUiVisibility() & ~f);
        }
    }

    // ------------------------------------------------------------ opening

    /** (Re)creates the player and opens the file; {@code at} ≥ 0 starts there (retry), otherwise at the remembered position. */
    private void open(long at) {
        releasePlayer();
        failed = false;
        if (errorLayer != null) { page.removeView(errorLayer); errorLayer = null; }
        pendingSeek = at;
        subAdded = false; hasVideo = false;
        spinner.setVisibility(View.VISIBLE);
        int gen = ++generation;
        MediaPlayer m = new MediaPlayer();
        mp = m;
        m.setAudioAttributes(attrs());
        m.setOnPreparedListener(x -> { if (x == mp) prepared(); });
        m.setOnErrorListener((x, what, extra) -> { if (x == mp) error(what, extra); return true; });
        m.setOnCompletionListener(x -> { if (x == mp) completed(); });
        m.setOnVideoSizeChangedListener((x, width, height) -> { if (x == mp) videoSize(width, height); });
        m.setOnSeekCompleteListener(x -> { if (x == mp) { c.ui.removeCallbacks(slowSeek); spinner.setVisibility(View.GONE); } });
        m.setOnInfoListener((x, what, extra) -> {
            if (x != mp) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) spinner.setVisibility(View.GONE);
            return false;
        });
        m.setOnTimedTextListener((x, t) -> { if (x == mp) showSub(t); });
        if (surface != null) m.setSurface(surface);
        // Opening a network file talks to the server: off the main thread. The player itself is only touched on the main thread.
        c.io.execute(() -> {
            ParcelFileDescriptor fd = null;
            try {
                fd = remote ? c.a.getContentResolver().openFileDescriptor(streamUri, "r")
                    : ParcelFileDescriptor.open(new File(it.path), ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (IOException | RuntimeException ignored) { }
            ParcelFileDescriptor got = fd;
            c.ui.post(() -> {
                if (gen != generation || mp != m) { closeQuietly(got); return; }
                if (got == null) { fail(remote); return; }
                try {
                    m.setDataSource(got.getFileDescriptor());   // the player keeps its own duplicate
                    m.prepareAsync();
                    state = S_PREPARING;
                } catch (IOException | RuntimeException e) {
                    fail(false);
                } finally { closeQuietly(got); }
            });
        });
    }

    private static void closeQuietly(ParcelFileDescriptor fd) { if (fd != null) try { fd.close(); } catch (IOException ignored) { } }

    private AudioAttributes attrs() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
    }

    private void prepared() {
        state = S_READY;
        duration = Math.max(0, mp.getDuration());
        MediaPlayer.TrackInfo[] ti = tracks();
        if (ti.length > 0) {
            for (MediaPlayer.TrackInfo t : ti) if (t.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) hasVideo = true;
            if (!hasVideo) { fail(false); return; }   // audio only, or no video stream the phone understands
        } else hasVideo = true;                       // no track list: let the video size / errors decide
        seek.setMax((int) Math.min(Integer.MAX_VALUE, duration));
        seek.setEnabled(duration > 0);
        total.setText(time(duration));
        attachSub();
        trackButtons();
        long at = pendingSeek;
        pendingSeek = -1;
        if (at < 0) {
            long saved = recall(c.a, resumeKey);
            if (saved > 0 && saved < duration * 95 / 100) { at = saved; Ui.toast(c, "Kaldığın yerden: " + time(saved)); }
        }
        if (at > 0 && duration > 0) { lastPos = Math.min(at, duration); mp.seekTo(lastPos, MediaPlayer.SEEK_CLOSEST); }
        c.ui.removeCallbacks(tick);
        c.ui.post(tick);
        if (visible) play();
        if (!playing) spinner.setVisibility(View.GONE);
        showControls();   // a moment of controls at the start (they hide again while playing)
    }

    private MediaPlayer.TrackInfo[] tracks() {
        try {
            MediaPlayer.TrackInfo[] t = mp.getTrackInfo();
            return t == null ? new MediaPlayer.TrackInfo[0] : t;
        } catch (RuntimeException e) { return new MediaPlayer.TrackInfo[0]; }
    }

    private void videoSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        vw = width; vh = height;
        if (!oriented) {
            oriented = true;
            c.a.setRequestedOrientation(width > height ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        applyAspect();
    }

    private void applyAspect() {
        int W = tex.getWidth(), H = tex.getHeight();
        if (W == 0 || H == 0 || vw == 0 || vh == 0) return;
        float sx = 1, sy = 1;
        if (aspect != STRETCH) {
            float s = aspect == FIT ? Math.min((float) W / vw, (float) H / vh) : Math.max((float) W / vw, (float) H / vh);
            sx = vw * s / W; sy = vh * s / H;
        }
        Matrix m = new Matrix();
        m.setScale(sx, sy, W / 2f, H / 2f);
        tex.setTransform(m);
    }

    private void error(int what, int extra) {
        // A Samba file that was already playing, or an I/O / timeout error, is most likely the network (Wi-Fi gone, server away).
        boolean net = remote && (state == S_READY || extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT);
        fail(net);
    }

    /** Error page: the network case offers "Tekrar dene" (reopens at the last position), every case "Birlikte aç". */
    private void fail(boolean network) {
        if (closed || failed) return;
        if (state == S_READY) { try { lastPos = mp.getCurrentPosition(); } catch (RuntimeException ignored) { } }
        savePosition();
        failed = true;
        releasePlayer();
        c.ui.removeCallbacks(tick); c.ui.removeCallbacks(hide); c.ui.removeCallbacks(slowSeek);
        tex.setKeepScreenOn(false);
        spinner.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        badge.setVisibility(View.GONE);
        subs.setText("");
        locked = false; lockOnly.setVisibility(View.GONE);
        if (focus != null) am.abandonAudioFocusRequest(focus);

        Runnable with = () -> { if (remote) Files.stream(c, (SmbSource) src, it); else Files.openLocal(c, it.path, it.name); };
        Runnable retry = () -> open(lastPos);
        LinearLayout st = network
            ? Ui.state(c, Icon.Kind.REFRESH, "Bağlantı kesildi", "Sunucudan okunamadı (Wi-Fi ya da sunucu). Bağlantı düzelince kaldığın yerden sürdürebilirsin.", "Tekrar dene", retry)
            : Ui.state(c, Icon.Kind.PLAY, "Bu video Kolay'da oynatılamıyor",
                "Telefonun kendi çözücüleri bu biçimi (görüntü ya da ses kodlamasını) desteklemiyor olabilir. Başka bir uygulamayla açmayı dene.", "Birlikte aç", with);
        TextView alt = Ui.button(c, network ? "Birlikte aç" : "Tekrar dene", Ui.Style.GHOST, null);
        alt.setOnClickListener(v -> { Ui.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY); (network ? with : retry).run(); });
        if (network || remote) st.addView(alt, Ui.lp(-2, c.dp(Ui.H_GHOST), 0, c.dp(8), 0, 0));
        errorLayer = new FrameLayout(c.a);
        errorLayer.setBackgroundColor(c.p.bg);
        errorLayer.setClickable(true);
        errorLayer.addView(st, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        View back = Ui.iconButton(c, Icon.Kind.BACK, "Geri", this::exit);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.TOP | Gravity.START);
        bl.setMargins(c.dp(12), c.dp(12), 0, 0);
        errorLayer.addView(back, bl);
        errorLayer.setPadding(c.insets.left, c.insets.top, c.insets.right, c.insets.bottom);
        page.addView(errorLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void completed() {
        playing = false;
        tex.setKeepScreenOn(false);
        playIcon.setImageDrawable(new Icon(Icon.Kind.START, c.p.onVideo));
        forget(c.a, resumeKey);
        lastPos = 0;
        showControls();
    }

    // ------------------------------------------------------------ playback

    private void togglePlay() { if (playing) pause(); else play(); }

    private void play() {
        if (state != S_READY || closed || failed) return;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs())
                .setOnAudioFocusChangeListener(this::focusChanged, c.ui).build();
        }
        if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { Ui.toast(c, "Ses şu an başka bir uygulamada (ör. arama)"); return; }
        try {
            mp.start();
            if (speedSet) mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
        } catch (IllegalArgumentException e) {
            speed = 1f; speedSet = false; speedBtn.setText("1×");
            Ui.toast(c, "Bu hız desteklenmiyor");
        } catch (IllegalStateException e) { return; }
        playing = true;
        tex.setKeepScreenOn(true);
        playIcon.setImageDrawable(new Icon(Icon.Kind.PAUSE, c.p.onVideo));
        scheduleHide();
    }

    private void pause() {
        if (state != S_READY || !playing) return;
        try { mp.pause(); } catch (IllegalStateException ignored) { }
        playing = false;
        tex.setKeepScreenOn(false);
        playIcon.setImageDrawable(new Icon(Icon.Kind.START, c.p.onVideo));
        savePosition();
        if (!locked) showControls();
    }

    private void focusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) { resumeOnGain = false; pause(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { resumeOnGain = playing; pause(); }
        else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnGain) { resumeOnGain = false; if (visible) play(); }
    }

    private long position() {
        if (state != S_READY) return lastPos;
        try { return mp.getCurrentPosition(); } catch (RuntimeException e) { return lastPos; }
    }

    private void seekTo(long ms) {
        if (state != S_READY || duration <= 0) return;
        ms = Math.max(0, Math.min(ms, duration));
        lastPos = ms;
        try { mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST); } catch (IllegalStateException e) { return; }
        cur.setText(time(ms));
        seek.setProgress((int) ms);
        c.ui.removeCallbacks(slowSeek);
        c.ui.postDelayed(slowSeek, 400);
    }

    private void skip(long delta) {
        if (state != S_READY || duration <= 0) return;
        seekTo(position() + delta);
        flash((delta < 0 ? "−" : "+") + (Math.abs(delta) / 1000) + " sn");
    }

    private final Runnable slowSeek = () -> { if (!closed && !failed) spinner.setVisibility(View.VISIBLE); };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed || failed || state != S_READY) return;
            long pos = position();
            if (playing && prevTick >= 0 && pos > prevTick) spinner.setVisibility(View.GONE);   // moving on: not loading (in case no info event came)
            prevTick = pos;
            lastPos = pos;
            if (!dragging && controls.getVisibility() == View.VISIBLE) { cur.setText(time(pos)); seek.setProgress((int) pos); }
            // Some phones play the sound of a video whose picture they cannot decode, without an error: say so instead of a black screen.
            if (playing && hasVideo && vw == 0 && pos > 5000) { fail(false); return; }
            c.ui.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------------ controls

    private final Runnable hide = () -> hideControls();
    private final Runnable hideBadge = () -> badge.setVisibility(View.GONE);
    private final Runnable hideLock = () -> lockOnly.setVisibility(View.GONE);

    private void showControls() {
        if (failed || locked) return;
        if (controls.getVisibility() != View.VISIBLE) {
            controls.setVisibility(View.VISIBLE);
            controls.setAlpha(0f);
            controls.animate().alpha(1f).setDuration(Ui.D_FAST).start();
            long pos = position();
            cur.setText(time(pos)); seek.setProgress((int) pos);
            placeSubs();
        }
        scheduleHide();
    }

    private void hideControls() {
        c.ui.removeCallbacks(hide);
        if (controls.getVisibility() != View.VISIBLE) return;
        controls.animate().alpha(0f).setDuration(Ui.D_FAST).withEndAction(() -> { controls.setVisibility(View.GONE); placeSubs(); }).start();
    }

    private void scheduleHide() {
        c.ui.removeCallbacks(hide);
        if (playing && !dragging) c.ui.postDelayed(hide, HIDE_MS);
    }

    private void lock() {
        locked = true;
        hideControls();
        showLockOnly();
        Ui.toast(c, "Kilitlendi; açmak için kilit simgesine dokun");
    }

    private void showLockOnly() {
        lockOnly.setVisibility(View.VISIBLE);
        c.ui.removeCallbacks(hideLock);
        c.ui.postDelayed(hideLock, HIDE_MS);
    }

    private void cycleAspect() {
        aspect = (aspect + 1) % ASPECTS.length;
        applyAspect();
        flash(ASPECTS[aspect]);
    }

    private void chooseSpeed() {
        Icon.Kind[] marks = new Icon.Kind[SPEEDS.length];
        for (int i = 0; i < SPEEDS.length; i++) marks[i] = SPEEDS[i] == speed ? Icon.Kind.CHECK : null;
        Sheet.menu(c, "Oynatma hızı", SPEED_LABELS, marks, k -> {
            speed = SPEEDS[k];
            speedSet = true;
            speedBtn.setText(k == 2 ? "1×" : SPEED_LABELS[k]);
            if (playing) {
                try { mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed)); }
                catch (IllegalArgumentException | IllegalStateException e) { Ui.toast(c, "Bu hız desteklenmiyor"); }
            }
        });
    }

    /** Subtitle and audio buttons appear only when there is a choice (any subtitle track; more than one audio track). */
    private void trackButtons() {
        int audio = 0, text = 0;
        for (MediaPlayer.TrackInfo t : tracks()) {
            if (t.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) audio++;
            else if (t.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) text++;
        }
        audioBtn.setVisibility(audio > 1 ? View.VISIBLE : View.GONE);
        subBtn.setVisibility(text > 0 ? View.VISIBLE : View.GONE);
    }

    private void chooseTrack(int type) {
        if (state != S_READY) return;
        MediaPlayer.TrackInfo[] ti = tracks();
        boolean text = type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT;
        int selected;
        try { selected = mp.getSelectedTrack(type); } catch (RuntimeException e) { selected = -1; }
        List<Integer> idx = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Icon.Kind> marks = new ArrayList<>();
        if (text) { idx.add(-1); labels.add("Kapalı"); marks.add(selected < 0 ? Icon.Kind.CHECK : null); }
        int n = 0;
        for (int i = 0; i < ti.length; i++) {
            if (ti[i].getTrackType() != type) continue;
            n++;
            String lang = language(ti[i].getLanguage());
            boolean external = text && subFile != null && i == ti.length - 1 && subAdded;
            labels.add(external ? "Yan dosya (.srt)" : (text ? "Altyazı " : "Ses ") + n + (lang == null ? "" : " · " + lang));
            idx.add(i);
            marks.add(i == selected ? Icon.Kind.CHECK : null);
        }
        Sheet.menu(c, text ? "Altyazı" : "Ses parçası", labels.toArray(new String[0]), marks.toArray(new Icon.Kind[0]), k -> {
            if (state != S_READY) return;
            int i = idx.get(k);
            try {
                int now = mp.getSelectedTrack(type);
                if (i < 0) { if (now >= 0) mp.deselectTrack(now); subs.setText(""); subOff = true; }
                else { mp.selectTrack(i); if (text) subOff = false; }
            } catch (RuntimeException e) { Ui.toast(c, "Parça değiştirilemedi"); }
        });
    }

    private static String language(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return null;
        String d = Locale.forLanguageTag(code).getDisplayLanguage(new Locale("tr"));
        if (d.isEmpty() || d.equalsIgnoreCase(code)) return code.toUpperCase(Locale.ROOT);
        return d.substring(0, 1).toUpperCase(new Locale("tr")) + d.substring(1);
    }

    private void flash(String s) {
        badge.setText(s);
        badge.setVisibility(View.VISIBLE);
        c.ui.removeCallbacks(hideBadge);
        c.ui.postDelayed(hideBadge, 700);
    }

    // ------------------------------------------------------------ gestures

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            downX = e.getX(); downY = e.getY();
            gesture = G_NONE;
            return true;
        }

        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            if (failed) return true;
            if (locked) showLockOnly();
            else if (controls.getVisibility() == View.VISIBLE) hideControls();
            else showControls();
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            if (locked || failed) return true;
            skip(e.getX() < page.getWidth() / 2f ? -SKIP_MS : SKIP_MS);
            return true;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (locked || failed) return false;
            float tx = e2.getX() - downX, ty = e2.getY() - downY;
            int W = page.getWidth(), H = page.getHeight();
            if (gesture == G_NONE) {
                // Leave the screen edges to the system (status bar pull-down, navigation and back gestures).
                int edge = c.dp(40);
                if (downY < edge || downY > H - edge || downX < c.dp(24) || downX > W - c.dp(24)) gesture = G_IGNORE;
                else if (Math.abs(tx) > Math.abs(ty)) {
                    gesture = state == S_READY && duration > 0 ? G_SEEK : G_IGNORE;
                    startValue = position();
                } else if (downX < W / 2f) { gesture = G_BRIGHT; startValue = brightness(); }
                else { gesture = G_VOLUME; startValue = volume(); }
            }
            if (gesture == G_SEEK) {
                long range = Math.min(duration, 180_000);   // a full-width drag moves up to 3 minutes
                seekTarget = Math.max(0, Math.min(duration, (long) (startValue + tx / W * range)));
                long d = seekTarget - (long) startValue;
                c.ui.removeCallbacks(hideBadge);
                badge.setText((d < 0 ? "−" : "+") + time(Math.abs(d)) + "\n" + time(seekTarget) + " / " + time(duration));
                badge.setVisibility(View.VISIBLE);
            } else if (gesture == G_BRIGHT) {
                float v = clamp(startValue - ty / (H * 0.7f));
                Window w = c.a.getWindow();
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.screenBrightness = Math.max(0.01f, v);
                w.setAttributes(lp);
                c.ui.removeCallbacks(hideBadge);
                badge.setText("Parlaklık %" + Math.round(v * 100));
                badge.setVisibility(View.VISIBLE);
            } else if (gesture == G_VOLUME) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                float v = clamp(startValue - ty / (H * 0.7f));
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0); }
                catch (SecurityException e) { return true; }   // Do Not Disturb may refuse volume changes
                c.ui.removeCallbacks(hideBadge);
                badge.setText("Ses %" + Math.round(v * 100));
                badge.setVisibility(View.VISIBLE);
            }
            return true;
        }
    }

    private void endGesture() {
        if (gesture == G_SEEK) seekTo(seekTarget);
        if (gesture == G_SEEK || gesture == G_BRIGHT || gesture == G_VOLUME) { c.ui.removeCallbacks(hideBadge); c.ui.postDelayed(hideBadge, 500); }
        gesture = G_NONE;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** This window's brightness override, or the system level when there is none yet. */
    private float brightness() {
        float b = c.a.getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        return Settings.System.getInt(c.a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
    }

    private float volume() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : (float) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max;
    }

    // ------------------------------------------------------------ subtitles

    /**
     * A same-name {@code .srt} next to the video (local, or on the same Samba share; at most 1 MB) is copied to the cache as UTF-8
     * (Turkish files are often Windows-1254) and added as a timed-text track once the player is prepared.
     */
    private void loadSidecar() {
        int dot = it.name.lastIndexOf('.');
        String base = dot > 0 ? it.name.substring(0, dot) : it.name;
        String dir = FileSource.parent(it.path);
        File cache = new File(c.a.getCacheDir(), "player");
        c.io.execute(() -> {
            try {
                File[] old = cache.listFiles();   // leftovers of a player that was killed
                if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 3600_000L) f.delete();
                byte[] data = null;
                for (String ext : remote ? new String[]{".srt"} : new String[]{".srt", ".SRT"}) {
                    FileItem f = src.stat(FileSource.child(dir, base + ext));
                    if (f == null || f.dir || f.size <= 0 || f.size > SRT_MAX) continue;
                    data = readAll(src, f.path);
                    break;
                }
                if (data == null) return;
                String text = decode(data);
                if (!cache.isDirectory() && !cache.mkdirs()) return;
                File out = new File(cache, Long.toString(System.nanoTime(), 36) + ".srt");
                try (FileOutputStream o = new FileOutputStream(out)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
                c.ui.post(() -> {
                    if (closed) { out.delete(); return; }
                    if (subFile != null) subFile.delete();
                    subFile = out;
                    attachSub();
                    trackButtons();
                });
            } catch (IOException | RuntimeException ignored) { }   // no subtitles then
        });
    }

    private static byte[] readAll(FileSource s, String path) throws IOException {
        try (InputStream in = s.openRead(path)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > SRT_MAX) throw new IOException("too big"); }
            return b.toByteArray();
        }
    }

    /** UTF-8 if the bytes are valid UTF-8 (BOM dropped), otherwise Windows-1254 (Turkish). */
    static String decode(byte[] d) {
        int off = d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(d, off, d.length - off)).toString();
        } catch (CharacterCodingException e) {
            Charset cs;
            try { cs = Charset.forName("windows-1254"); } catch (RuntimeException x) { cs = StandardCharsets.ISO_8859_1; }
            return new String(d, cs);
        }
    }

    private void attachSub() {
        if (state != S_READY || subFile == null || subAdded) return;
        int before = tracks().length;
        try {
            mp.addTimedTextSource(subFile.getPath(), MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP);
            subAdded = true;
            MediaPlayer.TrackInfo[] ti = tracks();
            for (int i = ti.length - 1; i >= before; i--) {
                if (ti[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) { if (!subOff) mp.selectTrack(i); break; }
            }
        } catch (IOException | RuntimeException ignored) { }
    }

    private void showSub(TimedText t) {
        String s = t == null ? null : t.getText();
        if (s == null || s.isEmpty()) { subs.setText(""); return; }
        // SRT may carry simple tags (<i>, <b>, <font>); line breaks become <br> for the HTML pass
        subs.setText(s.indexOf('<') >= 0 ? Html.fromHtml(s.trim().replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY) : s.trim());
    }

    // ------------------------------------------------------------ resume positions

    private void savePosition() {
        if (closed || failed) return;
        long pos = position();
        if (state != S_READY || duration <= 0) return;
        if (pos < 5000 || pos > duration * 95 / 100) forget(c.a, resumeKey);
        else remember(c.a, resumeKey, pos);
    }

    private static SharedPreferences resumePrefs(Context c) { return c.getSharedPreferences("player", Context.MODE_PRIVATE); }

    /** Positions are stored as "ms,lastUse" under a hash of the file (never the path itself); the oldest go beyond {@value #RESUME_MAX}. */
    private static void remember(Context c, String key, long pos) {
        SharedPreferences sp = resumePrefs(c);
        SharedPreferences.Editor e = sp.edit();
        e.putString(key, pos + "," + System.currentTimeMillis());
        List<Map.Entry<String, ?>> others = new ArrayList<>();
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) if (!en.getKey().equals(key)) others.add(en);
        if (others.size() >= RESUME_MAX) {
            others.sort((a, b) -> Long.compare(used(a.getValue()), used(b.getValue())));
            for (int i = 0; i <= others.size() - RESUME_MAX; i++) e.remove(others.get(i).getKey());
        }
        e.apply();
    }

    private static long used(Object v) {
        try { return Long.parseLong(String.valueOf(v).split(",")[1]); } catch (RuntimeException e) { return 0; }
    }

    private static long recall(Context c, String key) {
        String v = resumePrefs(c).getString(key, null);
        try { return v == null ? 0 : Long.parseLong(v.split(",")[0]); } catch (RuntimeException e) { return 0; }
    }

    private static void forget(Context c, String key) {
        SharedPreferences sp = resumePrefs(c);
        if (sp.contains(key)) sp.edit().remove(key).apply();
    }

    private static String hash(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format(Locale.ROOT, "%02x", d[i]));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------ helpers

    static String time(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
    }

    private static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }
}
