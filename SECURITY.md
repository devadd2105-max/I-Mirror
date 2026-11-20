# Security

- **Pairing code never travels.** The iPad and phone prove they know the 6-digit code with SRP-6a; the code itself is never sent, so someone watching the Wi-Fi cannot learn it.
- **Encrypted.** The connection is TLS with a certificate made fresh on each start and kept only in memory. Inside it, every picture is sealed again with AES-256-GCM using keys derived from the pairing.
- **Code limits.** Each code works once, expires after 10 minutes, and allows 3 wrong tries before it is replaced and a 30 second cooldown starts.
- **One viewer.** After one iPad pairs, others are refused.
- **Hardened server.** Strict browser headers, Origin check on the WebSocket, size caps, and a limit on open connections.
- **Phone side.** The app has no storage, no analytics, no account and no internet use; the screen shows a notification with a Stop button the whole time, and the app window hides itself from screenshots.

## Touch control

Off every time mirroring starts. It needs the owner to switch it on in the app and to enable the accessibility service in Android settings. Touches travel inside the same encrypted channel, so only the paired iPad can send them. The service listens for no events and cannot read the screen; it can only draw gestures and press Back, Home and Recents. Stopping mirroring turns it off.

## Limits

The certificate is self-signed, so Safari shows a warning. The pairing code is what proves it is your phone: only continue if the code on the phone matches what you typed and you started the mirroring yourself.

Anyone on the same Wi-Fi can open the page, but cannot see anything without the code.
