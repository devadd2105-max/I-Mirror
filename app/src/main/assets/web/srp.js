// SRP-6a client (BigInt + WebCrypto). Twin of Srp.java; the code never leaves this page.
const N = BigInt("0xffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aacaa68ffffffffffffffff");
const G = 2n, SIZE = 256;
const enc = new TextEncoder();

const cat = (...a) => { const o = new Uint8Array(a.reduce((s, x) => s + x.length, 0)); let p = 0; for (const x of a) { o.set(x, p); p += x.length; } return o; };
export const sha = async (...a) => new Uint8Array(await crypto.subtle.digest("SHA-256", cat(...a)));
export const hex = (u) => Array.from(u, (b) => b.toString(16).padStart(2, "0")).join("");
export const unhex = (h) => Uint8Array.from(h.match(/../g) || [], (x) => parseInt(x, 16));
const toBig = (u) => BigInt("0x" + (hex(u) || "0"));
const pad = (n) => unhex(n.toString(16).padStart(SIZE * 2, "0"));

function modpow(b, e, m) {
  let r = 1n; b %= m;
  while (e > 0n) { if (e & 1n) r = (r * b) % m; e >>= 1n; b = (b * b) % m; }
  return r;
}

export class SRPClient {
  constructor(pin) { this.pin = pin; }

  async start() {
    const r = crypto.getRandomValues(new Uint8Array(32)); r[0] |= 0x80;
    this.a = toBig(r); this.A = modpow(G, this.a, N);
    return hex(pad(this.A));
  }

  async process(saltHex, Bhex) {
    const salt = unhex(saltHex), B = toBig(unhex(Bhex));
    if (B <= 0n || B >= N) throw new Error("bad B");
    const k = toBig(await sha(pad(N), pad(G)));
    const u = toBig(await sha(pad(this.A), pad(B)));
    if (u === 0n) throw new Error("bad u");
    const x = toBig(await sha(salt, await sha(enc.encode("imirror:" + this.pin))));
    const base = (((B - k * modpow(G, x, N)) % N) + N) % N;
    const S = modpow(base, this.a + u * x, N);
    this.K = await sha(pad(S));
    this.Ab = pad(this.A); this.Bb = pad(B);
    this.M1 = await sha(enc.encode("M1"), this.Ab, this.Bb, this.K);
    return hex(this.M1);
  }

  async verify(m2hex) {
    const want = await sha(enc.encode("M2"), this.Ab, this.M1, this.K), got = unhex(m2hex);
    let d = got.length ^ want.length;
    for (let i = 0; i < want.length; i++) d |= (got[i] ^ want[i]);
    return d === 0;
  }
}
