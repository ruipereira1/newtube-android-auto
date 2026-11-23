package com.newtube.mobile.car.setup;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A minimal self-signed X.509 v3 certificate (SHA256withRSA) for this app's ADB key.
 *
 * <p>Wireless debugging pairs and connects over TLS with a client certificate; adbd only cares
 * about the public key inside it. Building the few DER structures by hand avoids pulling in
 * BouncyCastle or sun.security (megabytes) for this one certificate.</p>
 */
final class SelfSignedCertificate {
    /** sha256WithRSAEncryption, NULL parameters. */
    private static final byte[] SHA256_WITH_RSA = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d,
            0x01, 0x01, 0x0b, 0x05, 0x00};
    /** id-at-commonName. */
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    private SelfSignedCertificate() {
    }

    @NonNull
    static X509Certificate create(@NonNull KeyPair keys, @NonNull String commonName, long validMs)
            throws GeneralSecurityException {
        byte[] name = seq(tlv(0x31, seq(concat(OID_CN,
                tlv(0x0c, commonName.getBytes(StandardCharsets.UTF_8))))));
        Date from = new Date(System.currentTimeMillis() - 24L * 3600_000L);
        Date to = new Date(System.currentTimeMillis() + validMs);
        byte[] serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbs = seq(concat(
                tlv(0xa0, tlv(0x02, new byte[]{2})),      // [0] version v3
                tlv(0x02, serial),
                SHA256_WITH_RSA,
                name,                                      // issuer
                seq(concat(time(from), time(to))),
                name,                                      // subject
                keys.getPublic().getEncoded()));           // SubjectPublicKeyInfo (DER)
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] bitString = new byte[signature.length + 1]; // 0 unused bits
        System.arraycopy(signature, 0, bitString, 1, signature.length);
        byte[] der = seq(concat(tbs, SHA256_WITH_RSA, tlv(0x03, bitString)));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    /** UTCTime until 2049, GeneralizedTime after (RFC 5280). */
    private static byte[] time(Date date) {
        boolean utc = date.getTime() < 2524608000000L; // 2050-01-01
        SimpleDateFormat format = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'",
                Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] seq(byte[] content) {
        return tlv(0x30, content);
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xff);
        } else {
            out.write(0x83);
            out.write(length >> 16);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
