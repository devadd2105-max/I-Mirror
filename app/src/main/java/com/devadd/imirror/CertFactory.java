package com.devadd.imirror;

import com.devadd.imirror.core.TlsContexts;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** A fresh self-signed certificate every time mirroring starts, held only in memory. */
final class CertFactory {
    private CertFactory() {}

    static SSLContext create() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = gen.generateKeyPair();
        X500Name name = new X500Name("CN=I-Mirror");
        long now = System.currentTimeMillis();
        BigInteger serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE);
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                name, serial, new Date(now - 60_000L), new Date(now + 30L * 24 * 3600 * 1000), name, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(signer));
        return TlsContexts.server(kp.getPrivate(), new X509Certificate[] {cert});
    }
}
