package com.devadd.imirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import com.devadd.imirror.core.MirrorServer;
import com.devadd.imirror.core.Pairing;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.net.ssl.SSLContext;

/** Foreground service: owns the screen capture, the secure server and the pairing code. */
public class MirrorService extends Service {
    static final String ACTION_START = "com.devadd.imirror.START", ACTION_STOP = "com.devadd.imirror.STOP";
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    private static final int NOTE_ID = 7, FIRST_PORT = 8443;
    private static final String CHANNEL = "mirror";

    /** What the screen shows. Read from the main thread, written from here. */
    static final class State {
        volatile boolean running, client, control;     // control: the owner has allowed touch control
        volatile String pin = "", error = "";
        volatile List<String> urls = Collections.emptyList();
    }

    static final State STATE = new State();

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private MirrorServer server;
    private Pairing pairing;
    private ScreenCapturer capturer;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean down;

    private final Runnable refreshPin = new Runnable() {
        @Override public void run() {
            if (pairing != null) pairing.pin();         // renews an expired code
            if (server != null) server.setControlAvailable(controlOn());
            main.postDelayed(this, 1_000);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(intent.getAction())) { shutdown(); stopSelf(); return START_NOT_STICKY; }
        if (STATE.running) return START_NOT_STICKY;
        STATE.error = "";
        down = false;
        createChannel();
        // Android 14 wants the foreground state before the projection token is used.
        startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        try {
            begin(intent);
        } catch (Exception e) {
            STATE.error = "Could not start: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
            shutdown();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void begin(Intent intent) throws Exception {
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("no screen permission");

        SSLContext ssl = CertFactory.create();
        pairing = new Pairing(pin -> STATE.pin = pin);
        server = new MirrorServer(ssl, name -> {
            try (InputStream in = getAssets().open("web/" + name)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        }, pairing, (connected, addr) -> STATE.client = connected, level -> {
            ScreenCapturer c = capturer;
            if (c != null) c.setLevel(level);
        });
        server.setInput(new MirrorServer.Input() {
            @Override public void onTouch(int kind, float x, float y) { if (controlOn()) TouchService.touch(kind, x, y); }
            @Override public void onButton(int code) { if (controlOn()) TouchService.button(code); }
        });
        server.setControlAvailable(controlOn());
        int port = server.start(FIRST_PORT);
        STATE.urls = addresses(port);

        capturer = new ScreenCapturer(this, projection, new ScreenCapturer.Sink() {
            @Override public boolean wantsFrames() { return server != null && server.wantsFrames(); }
            @Override public void onFrame(byte[] jpeg, int w, int h) { MirrorServer s = server; if (s != null) s.offerFrame(jpeg, w, h); }
        }, () -> main.post(() -> { shutdown(); stopSelf(); }));
        capturer.start();

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imirror:mirror");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "imirror:wifi");
            wifi.acquire();
        } catch (RuntimeException ignored) {
            // locks only help with speed
        }
        STATE.running = true;
        main.postDelayed(refreshPin, 1_000);
    }

    /** Touch control needs the owner's switch and the accessibility service. */
    private static boolean controlOn() { return STATE.control && TouchService.isRunning(); }

    private static List<String> addresses(int port) {
        List<String> out = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ia = a.nextElement();
                    if (ia instanceof Inet4Address && ia.isSiteLocalAddress()) out.add("https://" + ia.getHostAddress() + ":" + port);
                }
            }
        } catch (Exception ignored) {
            // no addresses: the screen says to join the same Wi-Fi
        }
        return out;
    }

    private synchronized void shutdown() {
        if (down) return;
        down = true;
        main.removeCallbacks(refreshPin);
        if (capturer != null) capturer.stop();
        if (server != null) server.close();
        if (projection != null) { try { projection.stop(); } catch (RuntimeException ignored) { } }
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        capturer = null; server = null; pairing = null; projection = null; wake = null; wifi = null;
        STATE.running = false;
        STATE.client = false;
        STATE.control = false;                  // touch control is off again next time
        STATE.pin = "";
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() { shutdown(); super.onDestroy(); }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private Notification note() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, MirrorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("I-Mirror is sharing your screen")
                .setContentText("Tap to open. Stop it any time.")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stopPi).build())
                .build();
    }
}
