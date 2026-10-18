// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.BeforeClass;
import org.junit.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

public class AndroidPubkeyTest {
    private static RSAPublicKey publicKey;

    @BeforeClass
    public static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
    }

    @Test
    public void encodeProducesTheAndroidRsaStructure() throws Exception {
        byte[] encoded = AndroidPubkey.encode(publicKey);
        assertEquals(AndroidPubkey.ANDROID_PUBKEY_ENCODED_SIZE, encoded.length);

        ByteBuffer fields = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(AndroidPubkey.ANDROID_PUBKEY_MODULUS_SIZE_WORDS, fields.getInt());
        long n0inv = Integer.toUnsignedLong(fields.getInt());

        byte[] modulus = Arrays.copyOfRange(encoded, 8, 264);
        assertEquals(publicKey.getModulus(), littleEndianInteger(modulus));
        long lowWord = publicKey.getModulus().longValue() & 0xffffffffL;
        assertEquals(0xffffffffL, lowWord * n0inv & 0xffffffffL);

        byte[] rr = Arrays.copyOfRange(encoded, 264, 520);
        BigInteger expectedRr = BigInteger.ONE.shiftLeft(4096).mod(publicKey.getModulus());
        assertEquals(expectedRr, littleEndianInteger(rr));
        assertEquals(65537, fields.getInt(520));
    }

    @Test
    public void encodeWithNameAppendsTheAdbIdentity() throws Exception {
        byte[] key = AndroidPubkey.encode(publicKey);
        byte[] expected = (Base64.getEncoder().encodeToString(key) + " test-device\0")
                .getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, AndroidPubkey.encodeWithName(publicKey, "test-device"));
    }

    @Test
    public void encodeRejectsUnsupportedKeys() throws Exception {
        KeyPairGenerator shortGenerator = KeyPairGenerator.getInstance("RSA");
        shortGenerator.initialize(1024);
        RSAPublicKey shortKey = (RSAPublicKey) shortGenerator.generateKeyPair().getPublic();
        assertThrows(InvalidKeyException.class, () -> AndroidPubkey.encode(shortKey));

        RSAPublicKey wrongExponent = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(
                        publicKey.getModulus(), BigInteger.valueOf(17)));
        assertThrows(InvalidKeyException.class, () -> AndroidPubkey.encode(wrongExponent));
    }

    private static BigInteger littleEndianInteger(byte[] bytes) {
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            reversed[i] = bytes[bytes.length - i - 1];
        }
        return new BigInteger(1, reversed);
    }
}
