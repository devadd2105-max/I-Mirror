"""Plays the real server (fake phone frames) through a real browser: pairing, encryption, frames, safety checks."""
import queue
import re
import shutil
import socket
import ssl
import subprocess
import threading
import time
from pathlib import Path

import pytest

pw = pytest.importorskip("playwright.sync_api")
ROOT = Path(__file__).resolve().parent.parent
CHROME = shutil.which("google-chrome") or shutil.which("chromium")
pytestmark = pytest.mark.skipif(CHROME is None or shutil.which("javac") is None, reason="needs Chrome and a JDK")


class Harness:
    def __init__(self, tmp):
        classes = tmp / "classes"
        classes.mkdir()
        srcs = [str(p) for p in (ROOT / "app/src/main/java/com/devadd/imirror/core").glob("*.java")] + [str(ROOT / "tests/harness/TestHarness.java")]
        subprocess.run(["javac", "-d", str(classes), *srcs], check=True, capture_output=True)
        ks = tmp / "t.p12"
        subprocess.run(["keytool", "-genkeypair", "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                        "-dname", "CN=I-Mirror test", "-validity", "30", "-storetype", "PKCS12", "-keystore", str(ks),
                        "-storepass", "testpw", "-keypass", "testpw"], check=True, capture_output=True)
        self.p = subprocess.Popen(["java", "-cp", str(classes), "TestHarness", str(ks), str(ROOT / "app/src/main/assets/web"), "18443"],
                                  stdout=subprocess.PIPE, text=True, env={"PATH": "/usr/bin:/bin:/usr/local/bin"})
        self.lines = queue.Queue()
        self.pin = None
        self.log = []
        threading.Thread(target=self._read, daemon=True).start()
        self.port = int(self.wait(r"PORT (\d+)")[0])

    def _read(self):
        for line in self.p.stdout:
            line = line.strip()
            m = re.match(r"PIN (\d{6})", line)
            if m:
                self.pin = m.group(1)
            self.log.append(line)
            self.lines.put(line)

    def wait(self, pattern, timeout=20):
        end = time.time() + timeout
        while time.time() < end:
            try:
                m = re.match(pattern, self.lines.get(timeout=0.3))
                if m:
                    return m.groups()
            except queue.Empty:
                pass
        raise AssertionError(f"timed out waiting for {pattern}; log: {self.log[-8:]}")

    def new_pin(self, old, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            if self.pin and self.pin != old:
                return self.pin
            time.sleep(0.1)
        raise AssertionError("no new pin")

    @property
    def base(self):
        return f"https://127.0.0.1:{self.port}"

    def close(self):
        self.p.kill()


@pytest.fixture(scope="module")
def srv(tmp_path_factory):
    h = Harness(tmp_path_factory.mktemp("im"))
    time.sleep(0.5)
    yield h
    h.close()


@pytest.fixture()
def page(srv):
    with pw.sync_playwright() as p:
        b = p.chromium.launch(executable_path=CHROME, args=["--no-sandbox"])
        ctx = b.new_context(ignore_https_errors=True, viewport={"width": 1180, "height": 820})
        pg = ctx.new_page()
        pg.errors = []
        pg.on("pageerror", lambda e: pg.errors.append(str(e)))
        yield pg, ctx
        b.close()


def wait_js(pg, expr, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        if pg.evaluate(f"(() => !!({expr}))()"):
            return
        time.sleep(0.1)
    raise AssertionError("timed out: " + expr)


def msg(pg):
    return pg.inner_text("#pairmsg")


def disconnect(pg, srv):
    if pg.evaluate("window.__imirror.connected"):
        pg.click("#off")
        srv.wait(r"CLIENT down")
    time.sleep(0.2)


def test_pair_and_receive_live_frames(srv, page):
    pg, _ = page
    pg.goto(srv.base)
    pg.fill("#pin", srv.pin)
    wait_js(pg, "window.__imirror.connected")
    assert srv.wait(r"CLIENT (up)") == ("up",)
    colours = set()
    for _ in range(20):
        colours.add(tuple(pg.evaluate("(() => { const c = document.querySelector('#view'); return Array.from(c.getContext('2d').getImageData(150, 300, 1, 1).data) })()")[:3]))
        time.sleep(0.15)
    assert len(colours) >= 3, colours                     # the picture is really changing
    assert pg.evaluate("document.querySelector('#view').width") == 180
    assert pg.evaluate("document.querySelector('#view').height") == 390
    assert "fps" in pg.inner_text("#stat")
    assert pg.errors == []
    disconnect(pg, srv)


def test_quality_control_reaches_the_phone(srv, page):
    pg, _ = page
    pg.goto(srv.base)
    pg.fill("#pin", srv.pin)
    wait_js(pg, "window.__imirror.connected")
    srv.wait(r"QUALITY 1")                                # the page announces its starting level
    pg.select_option("#quality", "2")
    assert srv.wait(r"QUALITY (\d)") == ("2",)
    pg.select_option("#quality", "0")
    assert srv.wait(r"QUALITY (\d)") == ("0",)
    disconnect(pg, srv)


def test_pin_is_single_use_and_a_second_viewer_is_refused(srv, page):
    pg, ctx = page
    used = srv.pin
    pg.goto(srv.base)
    pg.fill("#pin", used)
    wait_js(pg, "window.__imirror.connected")
    other = ctx.new_page()
    other.goto(srv.base)
    other.fill("#pin", srv.new_pin(used))
    wait_js(other, "document.querySelector('#pairmsg').textContent.includes('already connected')")
    assert other.evaluate("window.__imirror.connected") is False
    disconnect(pg, srv)
    # the code that worked once is now dead
    old = srv.pin
    other.reload()
    other.fill("#pin", used)
    wait_js(other, "document.querySelector('#pairmsg').textContent.includes('wrong')")
    assert "2 tries left" in msg(other) or "tries left" in msg(other)


def raw(srv, data, read=2048):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    s = ctx.wrap_socket(socket.create_connection(("127.0.0.1", srv.port), timeout=5))
    s.sendall(data)
    out = b""
    try:
        while True:
            chunk = s.recv(read)
            if not chunk:
                break
            out += chunk
    except (socket.timeout, ssl.SSLError, ConnectionResetError):
        pass
    s.close()
    return out


def test_http_hardening(srv):
    ok = raw(srv, b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
    assert ok.startswith(b"HTTP/1.1 200")
    for h in (b"Content-Security-Policy: default-src 'none'", b"X-Content-Type-Options: nosniff", b"Cache-Control: no-store", b"Referrer-Policy: no-referrer"):
        assert h in ok
    assert raw(srv, b"GET /../../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n").startswith(b"HTTP/1.1 404")
    assert raw(srv, b"GET /app.js/../../x HTTP/1.1\r\nHost: x\r\n\r\n").startswith(b"HTTP/1.1 404")
    assert raw(srv, b"POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n").startswith(b"HTTP/1.1 405")
    assert raw(srv, b"GET / HTTP/1.1\r\nHost: x\r\nX: " + b"a" * 20000 + b"\r\n\r\n").startswith(b"HTTP/1.1 431")
    assert raw(srv, b"nonsense\r\n\r\n").startswith(b"HTTP/1.1 400")


def test_websocket_origin_and_handshake_checks(srv):
    key = b"dGhlIHNhbXBsZSBub25jZQ=="
    base = b"GET /ws HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " % srv.port + key + b"\r\n"
    assert raw(srv, base + b"\r\n").startswith(b"HTTP/1.1 403")                                           # no Origin
    assert raw(srv, base + b"Origin: https://evil.example\r\n\r\n").startswith(b"HTTP/1.1 403")          # other site
    good = raw(srv, base + b"Origin: https://127.0.0.1:%d\r\n\r\n" % srv.port, 300)
    assert good.startswith(b"HTTP/1.1 101") and b"s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" in good                  # RFC 6455 sample accept value


def test_garbage_after_the_handshake_is_dropped_without_a_crash(srv, page):
    key = b"dGhlIHNhbXBsZSBub25jZQ=="
    req = b"GET /ws HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " % srv.port + key + b"\r\nOrigin: https://127.0.0.1:%d\r\n\r\n" % srv.port
    raw(srv, req + b"\x81\x05hello")                                    # unmasked frame
    raw(srv, req + b"\x81\xfe\xff\xff" + b"A" * 50)                      # oversized length, short body
    raw(srv, req + b"\x01\x80\x00\x00\x00\x00")                          # fragment with no end
    pg, _ = page                                                        # the server still works afterwards
    pg.goto(srv.base)
    pg.fill("#pin", srv.pin)
    wait_js(pg, "window.__imirror.connected")
    disconnect(pg, srv)


def test_wrong_codes_lock_out_and_the_right_code_waits(srv, page):
    pg, _ = page
    good = srv.pin
    wrong = "000000" if good != "000000" else "111111"
    pg.goto(srv.base)
    seen = []
    for _ in range(3):                                    # earlier tests may have used a try already
        pg.fill("#pin", wrong)
        wait_js(pg, "document.querySelector('#pairmsg').textContent.match(/tr(y|ies) left|Too many/)")
        seen.append(msg(pg))
        if "Too many" in seen[-1]:
            break
        pg.reload()
    assert "Too many wrong codes" in seen[-1] and all("left" in m for m in seen[:-1]), seen
    fresh = srv.new_pin(good)
    assert fresh != good
    pg.reload()
    pg.fill("#pin", fresh)                                # even the correct new code waits out the cooldown
    wait_js(pg, "document.querySelector('#pairmsg').textContent.includes('Wait')")
    assert pg.evaluate("window.__imirror.connected") is False
