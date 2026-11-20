// AES-256-GCM record layer. Keys come from HKDF over the SRP session key; the nonce is a
// strictly increasing counter, so any replay, reordering or tampering fails authentication.
const enc = new TextEncoder();

async function derive(K, label) {
  const base = await crypto.subtle.importKey("raw", K, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: enc.encode(label) },
    base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}

export class Channel {
  static async create(K) {
    const c = new Channel();
    c.tx = await derive(K, "imirror v1 c2s");
    c.rx = await derive(K, "imirror v1 s2c");
    c.sn = 0; c.rn = 0;
    c.txAad = enc.encode("imirror1C"); c.rxAad = enc.encode("imirror1S");
    return c;
  }
  nonce(n) { const b = new Uint8Array(12); new DataView(b.buffer).setBigUint64(4, BigInt(n)); return b; }
  async seal(data) {
    const iv = this.nonce(this.sn++);
    return new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: this.txAad, tagLength: 128 }, this.tx, data));
  }
  async open(data) {
    const iv = this.nonce(this.rn);
    const pt = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv, additionalData: this.rxAad, tagLength: 128 }, this.rx, data));
    this.rn++;
    return pt;
  }
}
