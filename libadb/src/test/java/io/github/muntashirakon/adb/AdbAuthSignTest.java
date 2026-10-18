package io.github.muntashirakon.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;

public class AdbAuthSignTest {

    // adbd: RSA_verify(NID_sha1, token, 20, sig, ...) - the token is treated
    // as a SHA-1 digest. Verify by recovering DigestInfo||token from the sig.
    @Test
    public void signatureMatchesAdbdSha1Verify() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        byte[] token = new byte[20];
        new SecureRandom().nextBytes(token);

        byte[] sig = AndroidPubkey.adbAuthSign(kp.getPrivate(), token);
        assertTrue(sig.length == 256);

        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.DECRYPT_MODE, kp.getPublic());
        byte[] rec = c.doFinal(sig);
        assertArrayEquals(token, Arrays.copyOfRange(rec, rec.length - 20, rec.length));
        assertTrue(rec.length == 35 && rec[0] == 0x30 && rec[1] == 0x21);

        // Same check via a standard verifier: SHA1withRSA over m where SHA1(m)==token
        // is impossible to construct, so NONEwithRSA over DigestInfo is the reference.
        Signature v = Signature.getInstance("NONEwithRSA");
        v.initVerify(kp.getPublic());
        v.update(rec);
        assertTrue(v.verify(sig));
    }
}
