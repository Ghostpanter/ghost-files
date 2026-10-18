// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import androidx.annotation.NonNull;

import org.conscrypt.Conscrypt;

import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

final class SslUtils {
    private SslUtils() {}

    @NonNull
    static SSLContext getSslContext(KeyPair keyPair)
            throws NoSuchAlgorithmException, KeyManagementException {
        Objects.requireNonNull(keyPair);
        if (!Conscrypt.isAvailable()) {
            throw new NoSuchAlgorithmException("bundled Conscrypt is unavailable");
        }
        SSLContext context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider());
        context.init(new KeyManager[]{getKeyManager(keyPair)},
                new X509TrustManager[]{getAdbTrustManager()}, new SecureRandom());
        return context;
    }

    @NonNull
    private static KeyManager getKeyManager(KeyPair keyPair) {
        return new X509ExtendedKeyManager() {
            private static final String ALIAS = "key";

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
                for (String keyType : keyTypes) {
                    if ("RSA".equals(keyType)) return ALIAS;
                }
                return null;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                if (!ALIAS.equals(alias)) return null;
                return new X509Certificate[]{(X509Certificate) keyPair.getCertificate()};
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return ALIAS.equals(alias) ? keyPair.getPrivateKey() : null;
            }
        };
    }

    // ADB does not give the client a stable target certificate to verify.
    // The pairing server generates a new key for each pairing operation and
    // adbd generates a separate process-scoped key. Pairing authenticates the
    // six-digit-code exchange; later TLS connections authenticate this client
    // to adbd, but not adbd to this client. Reject malformed certificates and
    // keep TLS mandatory without pretending that an ephemeral key is an
    // identity pin.
    @NonNull
    private static X509TrustManager getAdbTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("ADB TLS context is client-only");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("ADB peer presented no certificate");
                }
                if (!(chain[0].getPublicKey() instanceof RSAPublicKey key)
                        || key.getModulus().bitLength() != 2048
                        || (!BigInteger.valueOf(3).equals(key.getPublicExponent())
                        && !BigInteger.valueOf(65537).equals(key.getPublicExponent()))) {
                    throw new CertificateException("ADB peer certificate is not RSA-2048");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
