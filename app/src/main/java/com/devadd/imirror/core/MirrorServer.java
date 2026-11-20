package com.devadd.imirror.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * HTTPS server for the viewer page plus one encrypted WebSocket session.
 *
 * Wire format after pairing (everything is AES-GCM sealed, first plaintext byte is the type):
 *   server to client: 0x01 frame (w:int32, h:int32, JPEG bytes), 0x03 ready, 0x04 control on/off (one byte)
 *   client to server: 0x10 ping, 0x11 quality (one byte: 0 low, 1 balanced, 2 high),
 *                     0x12 touch (kind: 0 down, 1 move, 2 up, 3 cancel; x, y as uint16 of 65535),
 *                     0x13 button (0 back, 1 home, 2 recents)
 * Pairing uses plain text frames: HELLO, CHAL, PROOF, OK, ERR.
 */
public final class MirrorServer implements Closeable {
    public interface Events {
        void onClient(boolean connected, String address);
    }

    public interface Controls {
        void onQuality(int level);
    }

    /** Touch and button input from the paired viewer. Only called while control is switched on. */
    public interface Input {
        void onTouch(int kind, float x, float y);
        void onButton(int code);
    }

    static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEAD = 8192, MAX_CONNECTIONS = 12;
    private static final Map<String, String[]> PAGES = new HashMap<>();
    static {
        PAGES.put("/", new String[] {"index.html", "text/html; charset=utf-8"});
        PAGES.put("/app.js", new String[] {"app.js", "text/javascript; charset=utf-8"});
        PAGES.put("/srp.js", new String[] {"srp.js", "text/javascript; charset=utf-8"});
        PAGES.put("/channel.js", new String[] {"channel.js", "text/javascript; charset=utf-8"});
        PAGES.put("/style.css", new String[] {"style.css", "text/css; charset=utf-8"});
    }
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; "
            + "connect-src 'self' wss:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final SSLContext ssl;
    private final AssetSource assets;
    private final Pairing pairing;
    private final Events events;
    private final Controls controls;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "imirror-conn");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile SSLServerSocket server;
    private volatile Session session;
    private volatile Input input;
    private volatile boolean controlOn;
    private volatile byte[] lastJpeg;
    private volatile int lastW, lastH;

    public MirrorServer(SSLContext ssl, AssetSource assets, Pairing pairing, Events events, Controls controls) {
        this.ssl = ssl;
        this.assets = assets;
        this.pairing = pairing;
        this.events = events;
        this.controls = controls;
    }

    /** Binds the first free port from `first` upwards and returns it. */
    public int start(int first) throws IOException {
        IOException last = null;
        for (int p = first; p < first + 10; p++) {
            try {
                SSLServerSocket s = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(p, 16);
                s.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                server = s;
                Thread t = new Thread(this::acceptLoop, "imirror-accept");
                t.setDaemon(true);
                t.start();
                return p;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no free port");
    }

    public void setInput(Input in) { input = in; }

    /** Tells the viewer whether it may control the phone (the phone's owner decides). */
    public void setControlAvailable(boolean on) {
        controlOn = on;
        Session s = session;
        if (s != null) s.poke();
    }

    public boolean wantsFrames() { return session != null; }

    /** Hands over the newest frame. The previous unsent frame is dropped, so the viewer never lags behind. */
    public void offerFrame(byte[] jpeg, int w, int h) {
        lastJpeg = jpeg;
        lastW = w;
        lastH = h;
        Session s = session;
        if (s != null) s.offer(jpeg, w, h);
    }

    @Override
    public void close() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        Session s = session;
        if (s != null) s.kill();
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket sock = server.accept();
                if (open.incrementAndGet() > MAX_CONNECTIONS) {
                    open.decrementAndGet();
                    sock.close();
                    continue;
                }
                pool.execute(() -> {
                    try { handle(sock); }
                    catch (Exception ignored) { }
                    finally { open.decrementAndGet(); try { sock.close(); } catch (IOException ignored) { } }
                });
            } catch (IOException e) {
                if (server.isClosed()) return;
            }
        }
    }

    // ------------------------------------------------------------------ HTTP
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) return null;
            if (b.size() >= MAX_HEAD) return "";
            b.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void handle(Socket sock) throws Exception {
        sock.setSoTimeout(10_000);
        sock.setTcpNoDelay(true);
        InputStream in = new BufferedInputStream(sock.getInputStream(), 4096);
        OutputStream out = sock.getOutputStream();
        String head;
        try { head = readHead(in); } catch (SocketTimeoutException e) { return; }
        if (head == null) return;
        if (head.isEmpty()) { respond(out, 431, "text/plain", "headers too large".getBytes(StandardCharsets.UTF_8)); return; }
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length != 3 || !req[2].startsWith("HTTP/1.")) { respond(out, 400, "text/plain", "bad request".getBytes()); return; }
        Map<String, String> h = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        if (!req[0].equals("GET")) { respond(out, 405, "text/plain", "method not allowed".getBytes()); return; }
        String path = req[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        if (path.equals("/ws")) { upgrade(sock, in, out, h); return; }
        String[] page = PAGES.get(path);
        if (page == null) { respond(out, 404, "text/plain", "not found".getBytes()); return; }
        respond(out, 200, page[1], assets.read(page[0]));
    }

    private static void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 403 ? "Forbidden" : code == 404 ? "Not Found"
                : code == 405 ? "Method Not Allowed" : code == 431 ? "Request Header Fields Too Large" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
                + "Cross-Origin-Opener-Policy: same-origin\r\nContent-Security-Policy: " + CSP + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // ------------------------------------------------------------------ WebSocket + pairing
    private void upgrade(Socket sock, InputStream in, OutputStream out, Map<String, String> h) throws Exception {
        String key = h.get("sec-websocket-key");
        String conn = h.getOrDefault("connection", "").toLowerCase(Locale.ROOT);
        if (!"websocket".equalsIgnoreCase(h.get("upgrade")) || !conn.contains("upgrade") || !"13".equals(h.get("sec-websocket-version"))
                || key == null) { respond(out, 400, "text/plain", "bad request".getBytes()); return; }
        try { if (Base64.getDecoder().decode(key).length != 16) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { respond(out, 400, "text/plain", "bad request".getBytes()); return; }
        // Only pages served by this server may open the socket (blocks cross-site WebSocket hijacking).
        String origin = h.get("origin"), host = h.get("host");
        if (origin == null || host == null || !origin.equalsIgnoreCase("https://" + host)) {
            respond(out, 403, "text/plain", "forbidden".getBytes());
            return;
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        WebSocketConn ws = new WebSocketConn(in, out, 2048);
        if (session != null) {                                  // someone is already mirroring
            ws.sendText("ERR busy");
            ws.sendClose();
            return;
        }
        String addr = ((InetSocketAddress) sock.getRemoteSocketAddress()).getAddress().getHostAddress();
        Session s = null;
        try {
            sock.setSoTimeout(15_000);
            SecureChannel chan = pair(ws);
            if (chan == null) return;
            // Only a successfully paired viewer takes the single slot, so an idle connection cannot block pairing.
            if (!busy.compareAndSet(false, true)) { ws.sendText("ERR busy"); ws.sendClose(); return; }
            sock.setSoTimeout(30_000);                         // the page pings every 10 s
            ws.setMaxPayload(1024);
            s = new Session(sock, ws, chan);
            session = s;
            events.onClient(true, addr);
            s.run();
        } finally {
            if (s != null) { s.kill(); session = null; events.onClient(false, addr); busy.set(false); }
        }
    }

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("[0-9a-f]{1,512}");

    private static String[] words(WebSocketConn.Message m, String cmd, int n) {
        if (m == null || m.binary) return null;
        String[] w = new String(m.data, StandardCharsets.UTF_8).split(" ");
        if (w.length != n || !w[0].equals(cmd)) return null;
        for (int i = 1; i < n; i++) if (!HEX.matcher(w[i]).matches()) return null;
        return w;
    }

    private static byte[] unhex(String s) {
        if ((s.length() & 1) == 1) s = "0" + s;
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return o;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** Runs the SRP exchange. Returns the encrypted channel, or null after telling the client why not. */
    private SecureChannel pair(WebSocketConn ws) throws Exception {
        String[] hello = words(ws.read(), "HELLO", 2);
        if (hello == null) return null;
        Pairing.Attempt a = pairing.begin();
        if (a == null) { ws.sendText("ERR cooldown " + pairing.cooldownSeconds()); ws.sendClose(); return null; }
        BigInteger A = new BigInteger(1, unhex(hello[1]));
        if (!a.srp.setA(A)) { ws.sendText("ERR bad_hello"); ws.sendClose(); return null; }
        ws.sendText("CHAL " + hex(a.salt) + " " + hex(a.srp.publicB()));
        String[] proof = words(ws.read(), "PROOF", 2);
        if (proof == null) return null;
        byte[] m2 = a.srp.verify(unhex(proof[1]));
        if (m2 == null) {
            int left = pairing.fail(a);
            ws.sendText(left > 0 ? "ERR bad_pin " + left : "ERR locked " + Math.max(1, pairing.cooldownSeconds()));
            ws.sendClose();
            return null;
        }
        byte[] key = a.srp.sessionKey();
        if (!pairing.succeed(a)) { ws.sendText("ERR expired"); ws.sendClose(); return null; }
        ws.sendText("OK " + hex(m2));
        return new SecureChannel(key);
    }

    /** One paired viewer. */
    private final class Session {
        private final Socket sock;
        private final WebSocketConn ws;
        private final SecureChannel chan;
        private final Object lock = new Object();
        private byte[] frame;
        private int fw, fh;
        private volatile boolean dead;
        private int lastLevel = -1, ctlSent = -1;

        Session(Socket sock, WebSocketConn ws, SecureChannel chan) {
            this.sock = sock;
            this.ws = ws;
            this.chan = chan;
        }

        void offer(byte[] jpeg, int w, int h) {
            synchronized (lock) { frame = jpeg; fw = w; fh = h; lock.notifyAll(); }
        }

        void poke() {
            synchronized (lock) { lock.notifyAll(); }
        }

        void kill() {
            dead = true;
            synchronized (lock) { lock.notifyAll(); }
            try { sock.close(); } catch (IOException ignored) { }
        }

        private void sendSealed(byte[] plain) throws Exception {
            ws.sendBinary(chan.seal(plain));
        }

        void run() throws Exception {
            sendSealed(new byte[] {0x03});
            byte[] first = lastJpeg;
            if (first != null) offer(first, lastW, lastH);   // a still screen still shows up at once
            Thread writer = new Thread(this::writeLoop, "imirror-writer");
            writer.setDaemon(true);
            writer.start();
            try {
                while (!dead) {
                    WebSocketConn.Message m = ws.read();
                    if (m == null || !m.binary) break;
                    byte[] p = chan.open(m.data);
                    if (p.length == 0) break;
                    if (p[0] == 0x10 && p.length == 1) continue;
                    if (p[0] == 0x11 && p.length == 2 && p[1] >= 0 && p[1] <= 2) {
                        if (p[1] != lastLevel) { lastLevel = p[1]; controls.onQuality(p[1]); }
                        continue;
                    }
                    if (p[0] == 0x12 && p.length == 6) {
                        Input in = input;
                        int kind = p[1];
                        if (kind < 0 || kind > 3) break;
                        if (in != null && controlOn) {
                            int x = ((p[2] & 0xff) << 8) | (p[3] & 0xff), y = ((p[4] & 0xff) << 8) | (p[5] & 0xff);
                            in.onTouch(kind, x / 65535f, y / 65535f);
                        }
                        continue;
                    }
                    if (p[0] == 0x13 && p.length == 2 && p[1] >= 0 && p[1] <= 2) {
                        Input in = input;
                        if (in != null && controlOn) in.onButton(p[1]);
                        continue;
                    }
                    break;                                    // anything else is a protocol error
                }
            } catch (IOException | SecureChannel.ChannelException e) {
                // the connection ended or failed its security check
            } finally {
                kill();
            }
        }

        private void writeLoop() {
            try {
                while (!dead) {
                    byte[] f; int w, h, want;
                    synchronized (lock) {
                        while (!dead && frame == null && ctlSent == (controlOn ? 1 : 0)) lock.wait();
                        if (dead) return;
                        f = frame; w = fw; h = fh; frame = null;
                        want = controlOn ? 1 : 0;
                    }
                    if (want != ctlSent) {
                        sendSealed(new byte[] {0x04, (byte) want});
                        ctlSent = want;
                    }
                    if (f == null) continue;
                    ByteBuffer b = ByteBuffer.allocate(9 + f.length);
                    b.put((byte) 0x01).putInt(w).putInt(h).put(f);
                    sendSealed(b.array());
                }
            } catch (Exception e) {
                kill();
            }
        }
    }
}
