package com.devadd.imirror.core;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;

/** Builds a TLS server context from one key and certificate held in memory. */
public final class TlsContexts {
    private TlsContexts() {}

    public static SSLContext server(PrivateKey key, X509Certificate[] chain) throws Exception {
        X509KeyManager km = new X509KeyManager() {
            public String[] getClientAliases(String t, Principal[] i) { return null; }
            public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return null; }
            public String[] getServerAliases(String t, Principal[] i) { return new String[] {"imirror"}; }
            public String chooseServerAlias(String t, Principal[] i, Socket s) { return "imirror"; }
            public X509Certificate[] getCertificateChain(String a) { return chain; }
            public PrivateKey getPrivateKey(String a) { return key; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[] {km}, null, null);
        return ctx;
    }
}
