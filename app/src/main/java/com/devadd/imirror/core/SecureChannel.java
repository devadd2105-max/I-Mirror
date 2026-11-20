package com.devadd.imirror.core;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM record layer. Each direction has its own HKDF key and a strictly increasing
 * counter used as the nonce, so replays, reordering and tampering all fail authentication.
 */
public final class SecureChannel {
    public static final class ChannelException extends Exception {
        ChannelException(String m) { super(m); }
    }

    private final SecretKeySpec txKey, rxKey;
    private final byte[] txAad, rxAad;
    private long sn = 0, rn = 0;

    /** Server side: sends with the server-to-client key. */
    public SecureChannel(byte[] sessionKey) throws GeneralSecurityException {
        txKey = new SecretKeySpec(hkdf(sessionKey, "imirror v1 s2c"), "AES");
        rxKey = new SecretKeySpec(hkdf(sessionKey, "imirror v1 c2s"), "AES");
        txAad = Srp.ascii("imirror1S");
        rxAad = Srp.ascii("imirror1C");
    }

    /** HKDF-SHA256 with an empty salt, one 32-byte block. */
    static byte[] hkdf(byte[] ikm, String info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(Srp.ascii(info));
        mac.update((byte) 1);
        return mac.doFinal();
    }

    private static byte[] nonce(long n) {
        return ByteBuffer.allocate(12).putInt(0).putLong(n).array();
    }

    public byte[] seal(byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, txKey, new GCMParameterSpec(128, nonce(sn)));
        c.updateAAD(txAad);
        byte[] out = c.doFinal(plain);
        sn++;
        return out;
    }

    public byte[] open(byte[] sealed) throws ChannelException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, rxKey, new GCMParameterSpec(128, nonce(rn)));
            c.updateAAD(rxAad);
            byte[] out = c.doFinal(sealed);
            rn++;
            return out;
        } catch (GeneralSecurityException e) {
            throw new ChannelException("authentication failed");
        }
    }
}
