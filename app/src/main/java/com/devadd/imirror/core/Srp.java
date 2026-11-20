package com.devadd.imirror.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * SRP-6a password-authenticated key exchange for the pairing code (RFC 3526 group 14, SHA-256).
 * The code never crosses the network. The browser has the same maths in web/srp.js.
 */
public final class Srp {
    private Srp() {}

    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    static final BigInteger G = BigInteger.valueOf(2);
    static final int SIZE = 256;
    private static final SecureRandom RNG = new SecureRandom();
    private static final BigInteger K_MULT = new BigInteger(1, sha(pad(N), pad(G)));

    static byte[] sha(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Big-endian, left-padded to 256 bytes. */
    static byte[] pad(BigInteger n) {
        byte[] raw = n.toByteArray();
        int off = Math.max(0, raw.length - SIZE), len = raw.length - off;
        byte[] out = new byte[SIZE];
        System.arraycopy(raw, off, out, SIZE - len, len);
        return out;
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static BigInteger verifier(byte[] salt, String pin) {
        BigInteger x = new BigInteger(1, sha(salt, sha(ascii("imirror:" + pin))));
        return G.modPow(x, N);
    }

    public static final class Server {
        private final BigInteger v, b, B;
        private BigInteger A;
        private byte[] key;

        public Server(BigInteger verifier) {
            this.v = verifier;
            this.b = new BigInteger(256, RNG).setBit(255);
            this.B = K_MULT.multiply(v).add(G.modPow(b, N)).mod(N);
        }

        public byte[] publicB() { return pad(B); }
        public byte[] sessionKey() { return key; }

        /** False if the client's value is unusable. */
        public boolean setA(BigInteger a) {
            if (a.signum() <= 0 || a.compareTo(N) >= 0) return false;
            BigInteger u = new BigInteger(1, sha(pad(a), pad(B)));
            if (u.signum() == 0) return false;
            BigInteger s = a.multiply(v.modPow(u, N)).mod(N).modPow(b, N);
            this.A = a;
            this.key = sha(pad(s));
            return true;
        }

        /** The server proof if the client proof is right, otherwise null. Constant-time compare. */
        public byte[] verify(byte[] m1) {
            if (key == null || A == null) return null;
            byte[] want = sha(ascii("M1"), pad(A), pad(B), key);
            if (!MessageDigest.isEqual(want, m1)) return null;
            return sha(ascii("M2"), pad(A), m1, key);
        }
    }
}
