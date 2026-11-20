package com.devadd.imirror;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One screen: start or stop mirroring, and show the pairing code and address for the iPad. */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1, REQ_NOTIFICATIONS = 2;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, pin, urls, help;
    private Button button;
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 700); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Keeps the pairing code out of screenshots and out of the mirrored picture itself.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(build());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private ScrollView build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(48), dp(24), dp(32));
        col.addView(text("I-Mirror", 30, Color.WHITE, true));
        col.addView(text("Made by Deva, for Deva", 14, 0xFF8FB0FF, false));
        status = text("", 18, 0xFFE8EEFC, true);
        col.addView(status);
        pin = text("", 44, 0xFFFDC500, true);
        pin.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        col.addView(pin);
        urls = text("", 16, 0xFFE8EEFC, false);
        col.addView(urls);
        help = text("", 14, 0xFF8D9BB8, false);
        col.addView(help);
        button = new Button(this);
        button.setAllCaps(false);
        button.setTextSize(18);
        button.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(24);
        col.addView(button, lp);
        col.addView(text("Your screen goes only to an iPad that types the code, over your own Wi-Fi, end-to-end encrypted. Nothing goes to the internet.",
                13, 0xFF8D9BB8, false));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0A0F1C);
        sv.setFillViewport(true);
        sv.addView(col);
        return sv;
    }

    private void toggle() {
        if (MirrorService.STATE.running) {
            startService(new Intent(this, MirrorService.class).setAction(MirrorService.ACTION_STOP));
        } else {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            MirrorService.STATE.error = "Screen sharing was not allowed.";
            return;
        }
        Intent svc = new Intent(this, MirrorService.class).setAction(MirrorService.ACTION_START)
                .putExtra(MirrorService.EXTRA_CODE, result).putExtra(MirrorService.EXTRA_DATA, data);
        startForegroundService(svc);
    }

    private void refresh() {
        MirrorService.State s = MirrorService.STATE;
        if (s.running) {
            button.setText("Stop mirroring");
            status.setText(s.client ? "iPad connected" : "Waiting for your iPad");
            String p = s.pin;
            pin.setText(p.length() == 6 ? p.substring(0, 3) + " " + p.substring(3) : p);
            pin.setVisibility(s.client ? android.view.View.GONE : android.view.View.VISIBLE);
            StringBuilder sb = new StringBuilder("On the iPad, open Safari and go to:\n");
            if (s.urls.isEmpty()) sb.append("(join the same Wi-Fi as the iPad first)");
            for (String u : s.urls) sb.append(u).append('\n');
            urls.setText(sb.toString().trim());
            help.setText("Safari warns about the certificate because your phone made it itself. Tap Show Details, then visit this website. Then type the code above.");
        } else {
            button.setText("Start mirroring");
            status.setText(s.error.isEmpty() ? "Not mirroring" : s.error);
            pin.setText("");
            urls.setText("");
            help.setText("Both devices must be on the same Wi-Fi.");
        }
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { ui.removeCallbacks(tick); super.onPause(); }
}
