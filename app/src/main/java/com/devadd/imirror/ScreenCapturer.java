package com.devadd.imirror;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Captures the screen with MediaProjection and turns each new picture into a JPEG.
 * Only the newest picture is kept, so the viewer never lags behind; a screen that stops
 * changing still ends on its final picture.
 */
final class ScreenCapturer {
    interface Sink {
        boolean wantsFrames();
        void onFrame(byte[] jpeg, int w, int h);
    }

    // Fast, Balanced, Sharp: size of the shorter side, JPEG quality, minimum ms between pictures.
    private static final int[] SHORT_SIDE = {480, 720, 1080};
    private static final int[] JPEG_Q = {55, 68, 82};
    private static final int[] MIN_MS = {50, 33, 40};
    private static final int IDLE_MS = 1000;       // with nobody watching, keep one fresh picture ready

    private final MediaProjection projection;
    private final Sink sink;
    private final Runnable onStopped;
    private final DisplayManager dm;
    private final ByteArrayOutputStream jpeg = new ByteArrayOutputStream(256 * 1024);
    private HandlerThread thread;
    private Handler handler;
    private ImageReader reader;
    private VirtualDisplay display;
    private Image held;
    private Bitmap bmp;
    private volatile int level = 1;
    private int w, h, dpi;
    private long lastMs;
    private boolean retryQueued, stopped;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) handler.post(ScreenCapturer.this::reconfigure);
        }
    };

    ScreenCapturer(Context ctx, MediaProjection projection, Sink sink, Runnable onStopped) {
        this.projection = projection;
        this.sink = sink;
        this.onStopped = onStopped;
        this.dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
    }

    void start() {
        thread = new HandlerThread("imirror-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        // Android 14 requires a callback to be registered before the virtual display is created.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                handler.post(() -> { stop(); onStopped.run(); });
            }
        }, handler);
        handler.post(() -> {
            measure();
            reader = newReader();
            display = projection.createVirtualDisplay("I-Mirror", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            dm.registerDisplayListener(displayListener, handler);
        });
    }

    void setLevel(int newLevel) {
        if (newLevel < 0 || newLevel > 2) return;
        level = newLevel;
        if (handler != null) handler.post(this::reconfigure);
    }

    void stop() {
        if (handler == null) return;
        handler.post(() -> {
            if (stopped) return;
            stopped = true;
            dm.unregisterDisplayListener(displayListener);
            if (held != null) { held.close(); held = null; }
            if (display != null) display.release();
            if (reader != null) reader.close();
            if (bmp != null) bmp.recycle();
            thread.quitSafely();
        });
    }

    @SuppressWarnings("deprecation")
    private void measure() {
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int rw = m.widthPixels, rh = m.heightPixels;
        dpi = m.densityDpi;
        double scale = Math.min(1.0, SHORT_SIDE[level] / (double) Math.min(rw, rh));
        w = Math.max(2, ((int) Math.round(rw * scale)) & ~1);
        h = Math.max(2, ((int) Math.round(rh * scale)) & ~1);
    }

    private ImageReader newReader() {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(rd -> drain(), handler);
        return r;
    }

    /** New size (rotation or quality change): point the virtual display at a matching reader. */
    private void reconfigure() {
        if (stopped || display == null) return;
        int oldW = w, oldH = h;
        measure();
        if (w == oldW && h == oldH) return;
        ImageReader old = reader;
        if (held != null) { held.close(); held = null; }
        reader = newReader();
        display.resize(w, h, dpi);
        display.setSurface(reader.getSurface());
        old.close();
        if (bmp != null) { bmp.recycle(); bmp = null; }
    }

    private void drain() {
        if (stopped || reader == null) return;
        Image n = reader.acquireLatestImage();
        if (n != null) {
            if (held != null) held.close();
            held = n;
        }
        if (held == null) return;
        long now = SystemClock.uptimeMillis();
        long wait = lastMs + (sink.wantsFrames() ? MIN_MS[level] : IDLE_MS) - now;
        if (wait > 0) {                                  // too soon: keep this picture and send it a moment later
            if (!retryQueued) {
                retryQueued = true;
                handler.postDelayed(() -> { retryQueued = false; drain(); }, wait);
            }
            return;
        }
        lastMs = now;
        try {
            encode(held);
        } catch (RuntimeException ignored) {
            // a bad picture is skipped
        } finally {
            held.close();
            held = null;
        }
    }

    private void encode(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int iw = img.getWidth(), ih = img.getHeight();
        int bw = p.getRowStride() / p.getPixelStride();
        if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != ih) {
            if (bmp != null) bmp.recycle();
            bmp = Bitmap.createBitmap(bw, ih, Bitmap.Config.ARGB_8888);
        }
        bmp.copyPixelsFromBuffer(buf);
        Bitmap src = bw == iw ? bmp : Bitmap.createBitmap(bmp, 0, 0, iw, ih);
        jpeg.reset();
        src.compress(Bitmap.CompressFormat.JPEG, JPEG_Q[level], jpeg);
        if (src != bmp) src.recycle();
        sink.onFrame(jpeg.toByteArray(), iw, ih);
    }
}
