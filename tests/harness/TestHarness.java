import com.devadd.imirror.core.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;

/** Runs the real server with a fake phone screen, for the browser tests. Prints PORT/PIN/CLIENT/QUALITY lines. */
public class TestHarness {
    public static void main(String[] a) throws Exception {
        String keystore = a[0], assetDir = a[1];
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keystore)) { ks.load(in, "testpw".toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey("t", "testpw".toCharArray());
        Certificate[] c = ks.getCertificateChain("t");
        X509Certificate[] chain = new X509Certificate[c.length];
        for (int i = 0; i < c.length; i++) chain[i] = (X509Certificate) c[i];
        SSLContext ssl = TlsContexts.server(key, chain);
        AssetSource assets = n -> Files.readAllBytes(Paths.get(assetDir, n));
        Pairing pairing = new Pairing(pin -> { System.out.println("PIN " + pin); System.out.flush(); });
        MirrorServer[] holder = new MirrorServer[1];
        MirrorServer server = new MirrorServer(ssl, assets, pairing,
                (connected, addr) -> { System.out.println("CLIENT " + (connected ? "up" : "down")); System.out.flush(); },
                level -> { System.out.println("QUALITY " + level); System.out.flush(); });
        holder[0] = server;
        int port = server.start(Integer.parseInt(a[2]));
        System.out.println("PORT " + port);
        System.out.flush();
        Thread t = new Thread(() -> {
            int n = 0;
            try {
                while (true) {
                    BufferedImage img = new BufferedImage(180, 390, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    g.setColor(new Color(40 + (n * 25) % 200, 80, 160));      // the colour changes every frame
                    g.fillRect(0, 0, 180, 390);
                    g.setColor(Color.WHITE);
                    g.fillRect(20, 20, 40, 40);
                    g.dispose();
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    ImageIO.write(img, "jpg", o);
                    server.offerFrame(o.toByteArray(), 180, 390);
                    n++;
                    Thread.sleep(70);
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
        t.setDaemon(true);
        t.start();
        Thread.currentThread().join();
    }
}
