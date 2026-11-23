package org.example.stockwatch247.service.channel;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * Prints a new VAPID key pair for the secret manager. Run it once per deployment and keep the pair: every browser's
 * push subscription is bound to the public key, so a new pair ends them all.
 *
 * <pre>java -Dloader.main=org.example.stockwatch247.service.channel.VapidKeyGenerator -cp app.jar \
 *   org.springframework.boot.loader.launch.PropertiesLauncher</pre>
 */
public final class VapidKeyGenerator {
    private VapidKeyGenerator() { }

    public static void main(String[] args) {
        KeyPair pair = WebPushCrypto.newKeyPair();
        System.out.println("VAPID_PUBLIC_KEY=" + WebPushCrypto.base64Url(WebPushCrypto.uncompressed((ECPublicKey) pair.getPublic())));
        System.out.println("VAPID_PRIVATE_KEY=" + WebPushCrypto.base64Url(WebPushCrypto.scalar((ECPrivateKey) pair.getPrivate())));
        System.out.println("VAPID_SUBJECT=mailto:<a mailbox that reads push service notices>");
    }
}
