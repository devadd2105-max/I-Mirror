package com.devadd.imirror;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * Plays the iPad's touches on the phone. It listens for no events and reads nothing from the screen;
 * the only thing it can do is draw gestures and press Back, Home and Recents.
 */
public class TouchService extends AccessibilityService {
    private static volatile TouchService instance;
    private static final int DOWN = 0, MOVE = 1, UP = 2, CANCEL = 3;
    private static final float SLOP_PX = 12f;
    private static final long HOLD_MS = 180, MIN_DUR = 12, MAX_DUR = 120;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int sw, sh;
    // current touch
    private boolean active, started, busy, ended, finalSent;
    private float downX, downY, lastX, lastY, tx, ty;
    private long downAt, lastAt;
    private GestureDescription.StrokeDescription stroke;
    private int token;                        // bumped per touch so stale callbacks are ignored

    static boolean isRunning() { return instance != null; }

    static void touch(int kind, float nx, float ny) {
        TouchService s = instance;
        if (s != null) s.main.post(() -> s.handle(kind, nx, ny));
    }

    static void button(int code) {
        TouchService s = instance;
        if (s == null) return;
        int action = code == 0 ? GLOBAL_ACTION_BACK : code == 1 ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_RECENTS;
        s.main.post(() -> s.performGlobalAction(action));
    }

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { reset(); }
    @Override public boolean onUnbind(android.content.Intent i) { instance = null; reset(); return super.onUnbind(i); }

    @SuppressWarnings("deprecation")
    private void measure() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m);
        sw = m.widthPixels;
        sh = m.heightPixels;
    }

    private void reset() {
        token++;
        active = started = busy = ended = finalSent = false;
        stroke = null;
    }

    private void handle(int kind, float nx, float ny) {
        float x = Math.max(0f, Math.min(1f, nx)), y = Math.max(0f, Math.min(1f, ny));
        if (kind == DOWN) {
            measure();
            reset();
            active = true;
            downX = lastX = tx = x * sw;
            downY = lastY = ty = y * sh;
            downAt = lastAt = SystemClock.uptimeMillis();
            final int t = token;
            main.postDelayed(() -> { if (t == token && active && !started) begin(); }, HOLD_MS);
            return;
        }
        if (!active) return;
        if (kind == CANCEL) { reset(); return; }
        tx = x * sw;
        ty = y * sh;
        if (kind == UP) ended = true;
        if (!started) {
            boolean moved = Math.hypot(tx - downX, ty - downY) > SLOP_PX;
            if (ended && !moved) { tap(); return; }
            begin();
            return;
        }
        if (!busy) next();
    }

    /** A quick touch with no movement: one short stroke. */
    private void tap() {
        Path p = new Path();
        p.moveTo(downX, downY);
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 40)).build(), null, null);
        reset();
    }

    /** The finger moved or stayed down: start a stroke that continues until the iPad lifts. */
    private void begin() {
        started = true;
        Path p = new Path();
        p.moveTo(downX, downY);
        p.lineTo(downX + 0.5f, downY);
        send(new GestureDescription.StrokeDescription(p, 0, MIN_DUR, true), downX + 0.5f, downY);
    }

    private void next() {
        if (stroke == null || (tx == lastX && ty == lastY && !ended)) return;
        long now = SystemClock.uptimeMillis();
        long dur = Math.max(MIN_DUR, Math.min(MAX_DUR, now - lastAt));
        Path p = new Path();
        p.moveTo(lastX, lastY);
        p.lineTo(tx == lastX && ty == lastY ? tx + 0.5f : tx, ty);
        if (ended) finalSent = true;
        send(stroke.continueStroke(p, 0, dur, !ended), tx, ty);
    }

    private void send(GestureDescription.StrokeDescription s, float endX, float endY) {
        stroke = s;
        lastX = endX;
        lastY = endY;
        lastAt = SystemClock.uptimeMillis();
        busy = true;
        final int t = token;
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                main.post(() -> {
                    if (t != token) return;
                    busy = false;
                    if (finalSent) { reset(); return; }
                    if (active) next();
                });
            }
            @Override public void onCancelled(GestureDescription g) {
                main.post(() -> { if (t == token) reset(); });
            }
        }, null);
        if (!ok) reset();
    }
}
