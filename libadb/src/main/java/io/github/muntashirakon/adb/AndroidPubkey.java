// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.bouncycastle.util.encoders.Base64;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Locale;

final class AndroidPubkey {
    /**
     * Size of an RSA modulus such as an encrypted block or a signature.
     */
    public static final int ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8;

    /**
     * Size of an encoded RSA key.
     */
    public static final int ANDROID_PUBKEY_ENCODED_SIZE = 3 * 4 + 2 * ANDROID_PUBKEY_MODULUS_SIZE;

    /**
     * Size of the RSA modulus in words.
     */
    public static final int ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4;

    /** DER DigestInfo prefix for SHA-1 (RFC 8017 sec. 9.2 note 1). */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    /**
     * Legacy ADB AUTH: adbd verifies the 20-byte token as if it were a SHA-1
     * digest (RSA_verify(NID_sha1, token, ...)). Produce the matching PKCS#1
     * v1.5 signature with NONEwithRSA over DigestInfo(SHA-1) || token.
     */
    @NonNull
    public static byte[] adbAuthSign(@NonNull PrivateKey privateKey, @NonNull byte[] token)
            throws GeneralSecurityException {
        if (token.length != 20) {
            throw new GeneralSecurityException("ADB AUTH token must be 20 bytes, got " + token.length);
        }
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO, 0, digestInfo, 0, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO.length, token.length);
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(privateKey);
        sig.update(digestInfo);
        return sig.sign();
    }

    /**
     * Converts a standard RSAPublicKey object to the special ADB format. Available since 4.2.2.
     *
     * @param publicKey RSAPublicKey object to convert
     * @param name      Name without null terminator
     * @return Byte array containing the converted RSAPublicKey object
     */
    @NonNull
    public static byte[] encodeWithName(@NonNull RSAPublicKey publicKey, @NonNull String name)
            throws InvalidKeyException {
        int pkeySize = 4 * (int) Math.ceil(ANDROID_PUBKEY_ENCODED_SIZE / 3.0);
        ByteArrayOutputStream out = new ByteArrayOutputStream(pkeySize + name.length() + 2);
        byte[] encoded = Base64.encode(encode(publicKey));
        out.write(encoded, 0, encoded.length);
        byte[] userInfo = getUserInfo(name);
        out.write(userInfo, 0, userInfo.length);
        return out.toByteArray();
    }

    // Taken from get_user_info except that a custom name is used instead of host@user
    @VisibleForTesting
    @NonNull
    static byte[] getUserInfo(@NonNull String name) {
        return String.format(Locale.ROOT, " %s\u0000", name)
                .getBytes(StandardCharsets.UTF_8);
    }

    // AOSP platform/system/core commit e797a5c7,
    // libcrypto_utils/android_pubkey.cpp:
    // typedef struct RSAPublicKey {
    //     uint32_t modulus_size_words;                     // Modulus length. This must be ANDROID_PUBKEY_MODULUS_SIZE.
    //     uint32_t n0inv;                                  // Precomputed montgomery parameter: -1 / n[0] mod 2^32
    //     uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE];    // RSA modulus as a little-endian array.
    //     uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE];         // Montgomery parameter R^2 as a little-endian array.
    //     uint32_t exponent;                               // RSA modulus: 3 or 65537
    // } RSAPublicKey;

    /**
     * Encodes the given key in the Android RSA public key binary format.
     *
     * @return Public RSA key in Android's custom binary format. The size of the key should be at least
     * {@link #ANDROID_PUBKEY_ENCODED_SIZE}
     */
    @NonNull
    public static byte[] encode(@NonNull RSAPublicKey publicKey) throws InvalidKeyException {
        BigInteger r32;
        BigInteger n0inv;
        BigInteger rr;

        if (publicKey.getModulus().bitLength() != ANDROID_PUBKEY_MODULUS_SIZE * 8) {
            throw new InvalidKeyException("ADB requires an RSA-2048 key");
        }
        BigInteger exponent = publicKey.getPublicExponent();
        if (!BigInteger.valueOf(3).equals(exponent)
                && !BigInteger.valueOf(65537).equals(exponent)) {
            throw new InvalidKeyException("ADB RSA exponent must be 3 or 65537");
        }

        ByteBuffer keyStruct = ByteBuffer.allocate(ANDROID_PUBKEY_ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        // Store the modulus size.
        keyStruct.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS); // modulus_size_words

        // Compute and store n0inv = -1 / N[0] mod 2^32.
        r32 = BigInteger.ZERO.setBit(32); // r32 = 2^32
        n0inv = publicKey.getModulus().mod(r32); // n0inv = N[0] mod 2^32
        n0inv = n0inv.modInverse(r32); // n0inv = 1/n0inv mod 2^32
        n0inv = r32.subtract(n0inv);  // n0inv = 2^32 - n0inv
        keyStruct.putInt(n0inv.intValue()); // n0inv

        // Store the modulus.
        keyStruct.put(bigEndianToLittleEndianPadded(
                ANDROID_PUBKEY_MODULUS_SIZE, publicKey.getModulus()));

        // Compute and store rr = (2^(rsa_size)) ^ 2 mod N.
        rr = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8); // rr = 2^(rsa_size)
        rr = rr.modPow(BigInteger.valueOf(2), publicKey.getModulus()); // rr = rr^2 mod N
        keyStruct.put(bigEndianToLittleEndianPadded(ANDROID_PUBKEY_MODULUS_SIZE, rr));

        // Store the exponent.
        keyStruct.putInt(publicKey.getPublicExponent().intValue()); // exponent

        return keyStruct.array();
    }

    private static byte[] bigEndianToLittleEndianPadded(int len, @NonNull BigInteger in)
            throws InvalidKeyException {
        byte[] out = new byte[len];
        byte[] bytes = swapEndianness(in.toByteArray());  // Convert big endian -> little endian
        int numBytes = bytes.length;
        if (len < numBytes) {
            if (!fitsInBytes(bytes, numBytes, len)) {
                throw new InvalidKeyException("RSA value does not fit ADB key structure");
            }
            numBytes = len;
        }
        System.arraycopy(bytes, 0, out, 0, numBytes);
        return out;
    }

    static boolean fitsInBytes(@NonNull byte[] bytes, int numBytes, int len) {
        byte mask = 0;
        for (int i = len; i < numBytes; i++) {
            mask |= bytes[i];
        }
        return mask == 0;
    }

    @NonNull
    private static byte[] swapEndianness(@NonNull byte[] bytes) {
        int len = bytes.length;
        byte[] out = new byte[len];
        for (int i = 0; i < len; ++i) {
            out[i] = bytes[len - i - 1];
        }
        return out;
    }
}
