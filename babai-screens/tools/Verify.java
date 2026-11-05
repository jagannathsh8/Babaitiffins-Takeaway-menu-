import com.android.apksig.ApkVerifier;

import java.io.File;

/** Checks an APK's signatures for Android 5 (API 21) up to the newest: Verify <apk>. */
public class Verify {
    public static void main(String[] a) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(a[0])).setMinCheckedPlatformVersion(21).build().verify();
        for (ApkVerifier.IssueWithParams e : r.getErrors()) System.out.println("ERROR " + e);
        System.out.println("verified=" + r.isVerified() + " v1=" + r.isVerifiedUsingV1Scheme() + " v2=" + r.isVerifiedUsingV2Scheme());
        if (!r.isVerified()) System.exit(1);
    }
}
