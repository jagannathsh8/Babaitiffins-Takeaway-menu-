import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signs an APK (v1 + v2) with apksig 2.3.0; run it on Java 8-11 (its v1 signer uses JDK internals removed later): Sign <in.apk> <out.apk> <keystore.p12> <password> <alias>. */
public class Sign {
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        FileInputStream in = new FileInputStream(a[2]); ks.load(in, a[3].toCharArray()); in.close();
        PrivateKey k = (PrivateKey) ks.getKey(a[4], a[3].toCharArray());
        X509Certificate c = (X509Certificate) ks.getCertificate(a[4]);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("BABAI", k, Collections.singletonList(c)).build();
        new ApkSigner.Builder(Collections.singletonList(sc)).setInputApk(new File(a[0])).setOutputApk(new File(a[1]))
                .setMinSdkVersion(21).setV1SigningEnabled(true).setV2SigningEnabled(true).build().sign();
        System.out.println("signed " + a[1]);
    }
}
