# I-Mirror

Mirror an Android phone's screen to an iPad, directly over Wi-Fi. No laptop in the middle, no cloud, no account.

The phone runs a small secure web server. The iPad opens it in Safari, types a 6-digit code, and watches the phone live.

## Use it

1. Install the APK on the phone (see below) and open **I-Mirror**.
2. Tap **Start mirroring** and allow screen sharing.
3. On the iPad (same Wi-Fi), open the `https://…` address shown on the phone.
4. Safari warns about the certificate because the phone created it itself. Tap **Show Details**, then **visit this website**.
5. Type the 3+3 digit code shown on the phone. The screen appears.

The **Quality** menu on the iPad switches between Fast, Balanced and Sharp.

## Get the APK

Every push to `main` builds the app on GitHub: open **Actions**, pick the latest run, download the **I-Mirror-apk** artifact, unzip it, and copy `app-debug.apk` to the phone. Allow installs from your browser or Files app when asked. Play Protect may warn about an unknown app, since it is a debug build you made yourself.

## How it works

- `app/src/main/java/.../core/`: plain Java server, SRP pairing, encrypted channel. Tested without Android.
- `app/src/main/assets/web/`: the iPad page (pairing, decrypt, draw).
- `ScreenCapturer` takes the screen with MediaProjection, scales it, and sends JPEGs. Only the newest picture is kept, so it never lags behind.
- `MirrorService` is a foreground service with a Stop button in its notification.

## Test the core

Needs JDK 17+, Python 3, `pytest`, `playwright` with Chromium:

    python3 -m pytest tests -q

See [SECURITY.md](SECURITY.md) for the protections and the limits.

## Limits

- View only. Touching the iPad does not control the phone.
- Apps that block screen capture (some banking, video and DRM apps) show black.
- Both devices must be on the same Wi-Fi, and the network must allow devices to reach each other (guest and school Wi-Fi often do not).
