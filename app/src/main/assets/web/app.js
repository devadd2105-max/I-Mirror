import { SRPClient } from "/srp.js";
import { Channel } from "/channel.js";

const $ = (s) => document.querySelector(s);
const view = $("#view"), vctx = view.getContext("2d", { alpha: false });

let ws = null, secureP = null, sendQ = Promise.resolve(), recvQ = Promise.resolve();
let connected = false, keepAlive = null;
const bin = [];

const ERRORS = {
  busy: () => "Another iPad is already connected to this phone.",
  bad_hello: () => "The phone refused that request. Try again.",
  expired: () => "That code expired. Type the new one shown on your phone.",
  bad_pin: (n) => `That code is wrong. ${n} ${n === "1" ? "try" : "tries"} left.`,
  locked: (n) => `Too many wrong codes. The phone made a new one. Wait ${n} seconds, then type it.`,
  cooldown: (n) => `Too many wrong codes. Wait ${n} seconds, then type the new code.`,
};

function pairMsg(text, err = false) { const m = $("#pairmsg"); m.textContent = text; m.className = err ? "err" : ""; }
const hexOk = (s) => /^[0-9a-f]{1,512}$/.test(s);

function refusal(text) {
  const [tag, code, arg] = text.split(" ");
  if (tag !== "ERR") return null;
  return (ERRORS[code] || (() => "Refused: " + code))(arg);
}

async function connect(pin) {
  $("#go").disabled = true;
  pairMsg("Connecting securely…");
  const inbox = []; let waiter = null, closed = false, failure = null;
  const next = () => new Promise((res, rej) => {
    if (inbox.length) return res(inbox.shift());
    if (closed) return rej(new Error(failure || "Connection closed"));
    waiter = { res, rej };
  });
  try {
    ws = new WebSocket(`wss://${location.host}/ws`);
    ws.binaryType = "arraybuffer";
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") { inbox.push(ev.data); if (waiter) { const w = waiter; waiter = null; w.res(inbox.shift()); } }
      else { bin.push(ev.data); pump(); }
    };
    ws.onclose = () => {
      closed = true;
      if (waiter) { const w = waiter; waiter = null; w.rej(new Error(failure || "Connection closed")); }
      if (connected) lost("The connection was closed.");
    };
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error("Could not reach the phone. Is it on the same Wi-Fi, with I-Mirror started?")); });

    const cli = new SRPClient(pin);
    ws.send("HELLO " + await cli.start());
    let r = await next();
    let bad = refusal(r); if (bad) { failure = bad; throw new Error(bad); }
    const [tag, salt, B] = r.split(" ");
    if (tag !== "CHAL" || !hexOk(salt) || !hexOk(B)) throw new Error("Unexpected reply from the phone.");
    pairMsg("Checking the code…");
    ws.send("PROOF " + await cli.process(salt, B));
    r = await next();
    bad = refusal(r); if (bad) { failure = bad; throw new Error(bad); }
    const [t2, m2] = r.split(" ");
    if (t2 !== "OK" || !hexOk(m2) || !(await cli.verify(m2))) throw new Error("The phone could not prove it knows the code. Disconnected for your safety.");
    secureP = Channel.create(cli.K);
    pump();
    pairMsg("Code accepted…");
  } catch (e) {
    pairMsg(e.message || "Could not connect.", true);
    $("#go").disabled = false;
    try { ws.close(); } catch {}
    ws = null; secureP = null;
  }
}

// ---------------------------------------------------------------- encrypted channel
function pump() {
  if (!secureP) return;
  recvQ = recvQ.then(async () => {
    const c = await secureP;
    while (bin.length) {
      const d = bin.shift();
      try { onSecure(await c.open(new Uint8Array(d))); }
      catch (e) { lost("Security check failed, so the connection was closed."); return; }
    }
  });
}

function sendSecure(bytes) {
  if (!secureP || !ws || ws.readyState !== 1) return;
  sendQ = sendQ.then(async () => {
    const c = await secureP;
    ws.send(await c.seal(bytes));
  }).catch(() => {});
}

function onSecure(p) {
  if (!p.length) return;
  if (p[0] === 0x03) { ready(); return; }
  if (p[0] === 0x04 && p.length === 2) { setControl(p[1] === 1); return; }
  if (p[0] === 0x01 && p.length > 9) {
    const dv = new DataView(p.buffer, p.byteOffset, p.byteLength);
    showFrame(new Blob([p.subarray(9)], { type: "image/jpeg" }), dv.getUint32(1), dv.getUint32(5));
  }
}

// ---------------------------------------------------------------- picture
let pending = null, decoding = false, frames = 0, lastTick = performance.now();
function showFrame(blob, w, h) {
  pending = { blob, w, h };
  if (!decoding) decodeNext();
}
async function decodeNext() {
  decoding = true;
  while (pending) {
    const f = pending; pending = null;
    try {
      const bmp = await createImageBitmap(f.blob);
      if (view.width !== bmp.width || view.height !== bmp.height) { view.width = bmp.width; view.height = bmp.height; }
      vctx.drawImage(bmp, 0, 0);
      bmp.close();
      frames++;
    } catch { /* a damaged frame is skipped */ }
  }
  decoding = false;
}
setInterval(() => {
  if (!connected) return;
  const now = performance.now(), fps = Math.round(frames * 1000 / (now - lastTick));
  frames = 0; lastTick = now;
  $("#stat").textContent = `Encrypted · ${fps} fps`;
}, 1000);

// ---------------------------------------------------------------- touch control
// The phone's owner switches control on; until then this page can only watch.
let controlOn = false, touchOn = true, activePointer = null;
function setControl(on) {
  controlOn = on;
  $("#nav").hidden = !on;
  if (!on) activePointer = null;
}
function sendTouch(kind, e) {
  const r = view.getBoundingClientRect();
  const x = Math.round(Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * 65535);
  const y = Math.round(Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)) * 65535);
  sendSecure(Uint8Array.of(0x12, kind, x >> 8, x & 255, y >> 8, y & 255));
}
const live = () => controlOn && touchOn && connected;
view.addEventListener("pointerdown", (e) => {
  if (!live() || activePointer !== null || (e.pointerType === "mouse" && e.button !== 0)) return;
  e.preventDefault();
  activePointer = e.pointerId;
  try { view.setPointerCapture(e.pointerId); } catch {}
  sendTouch(0, e);
});
view.addEventListener("pointermove", (e) => {
  if (e.pointerId !== activePointer) return;
  e.preventDefault();
  sendTouch(1, e);
});
const lift = (kind) => (e) => {
  if (e.pointerId !== activePointer) return;
  e.preventDefault();
  activePointer = null;
  sendTouch(kind, e);
};
view.addEventListener("pointerup", lift(2));
view.addEventListener("pointercancel", lift(3));
view.addEventListener("contextmenu", (e) => e.preventDefault());
$("#touch").addEventListener("click", () => {
  touchOn = !touchOn;
  $("#touch").textContent = touchOn ? "Touch: on" : "Touch: off";
  $("#touch").setAttribute("aria-pressed", String(touchOn));
});
document.querySelectorAll("[data-nav]").forEach((b) => b.addEventListener("click", () => {
  if (controlOn && connected) sendSecure(Uint8Array.of(0x13, Number(b.dataset.nav)));
}));

// ---------------------------------------------------------------- session
function ready() {
  if (connected) return;
  connected = true;
  $("#pair").hidden = true;
  $("#viewer").hidden = false;
  sendSecure(Uint8Array.of(0x11, Number($("#quality").value)));
  keepAlive = setInterval(() => sendSecure(Uint8Array.of(0x10)), 10000);
}

function lost(msg) {
  const was = connected;
  connected = false;
  clearInterval(keepAlive);
  try { ws && ws.close(); } catch {}
  ws = null; secureP = null; bin.length = 0; pending = null;
  setControl(false);
  $("#viewer").hidden = true;
  $("#pair").hidden = false;
  $("#go").disabled = false;
  $("#pin").value = "";
  pairMsg(was ? msg + " Type the new code shown on your phone." : msg, true);
}

$("#pin").addEventListener("input", () => {
  const el = $("#pin");
  el.value = el.value.replace(/\D/g, "").slice(0, 6);
  if (el.value.length === 6 && !$("#go").disabled) connect(el.value);
});
$("#go").addEventListener("click", () => { const v = $("#pin").value; if (/^\d{6}$/.test(v)) connect(v); else pairMsg("Type all 6 digits.", true); });
$("#quality").addEventListener("change", () => sendSecure(Uint8Array.of(0x11, Number($("#quality").value))));
$("#off").addEventListener("click", () => { lost("Disconnected."); });
$("#full").addEventListener("click", () => {
  const el = $("#viewer");
  (el.requestFullscreen || el.webkitRequestFullscreen || (() => {})).call(el);
});
window.__imirror = { get connected() { return connected; } };
