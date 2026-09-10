package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** GitHub issues PKCS#1 PEM keys; Java consumes PKCS#8. Both Apps use this one parser. */
public final class RsaPrivateKeys {
    private RsaPrivateKeys() {}

    public static PrivateKey parse(String pem) {
        try {
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                byte[] pkcs1 = decodePemBlock(pem, "RSA PRIVATE KEY");
                byte[] pkcs8 = convertPkcs1ToPkcs8(pkcs1);
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            }
            byte[] der = decodePemBlock(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid RSA private key", e);
        }
    }

    private static byte[] decodePemBlock(String pem, String type) {
        String normalized = pem.replace("\r", "");
        String beginMarker = "-----BEGIN " + type + "-----";
        String endMarker = "-----END " + type + "-----";
        int begin = normalized.indexOf(beginMarker);
        int end = normalized.indexOf(endMarker);
        if (begin < 0 || end < 0) {
            throw new IllegalArgumentException("PEM block for type '" + type + "' not found");
        }
        String base64 = normalized.substring(begin + beginMarker.length(), end).replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static byte[] convertPkcs1ToPkcs8(byte[] pkcs1) {
        try {
            byte[] version = new byte[] {0x02, 0x01, 0x00};
            byte[] algorithmIdentifier = new byte[] {
                0x30,
                0x0d,
                0x06,
                0x09,
                0x2a,
                (byte) 0x86,
                0x48,
                (byte) 0x86,
                (byte) 0xf7,
                0x0d,
                0x01,
                0x01,
                0x01,
                0x05,
                0x00,
            };
            byte[] privateKeyOctetString = encodeDerOctetString(pkcs1);
            byte[] innerSequence = concat(version, algorithmIdentifier, privateKeyOctetString);
            return encodeDerSequence(innerSequence);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to convert PKCS#1 key to PKCS#8", e);
        }
    }

    private static byte[] encodeDerSequence(byte[] content) throws IOException {
        return encodeDerStructure((byte) 0x30, content);
    }

    private static byte[] encodeDerOctetString(byte[] content) throws IOException {
        return encodeDerStructure((byte) 0x04, content);
    }

    private static byte[] encodeDerStructure(byte tag, byte[] content) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            baos.write(tag);
            baos.write(encodeDerLength(content.length));
            baos.write(content);
            return baos.toByteArray();
        }
    }

    private static byte[] encodeDerLength(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        int numBytes = 0;
        int temp = length;
        byte[] buffer = new byte[4];
        while (temp > 0) {
            buffer[buffer.length - 1 - numBytes] = (byte) (temp & 0xFF);
            temp >>= 8;
            numBytes++;
        }
        byte[] result = new byte[1 + numBytes];
        result[0] = (byte) (0x80 | numBytes);
        System.arraycopy(buffer, buffer.length - numBytes, result, 1, numBytes);
        return result;
    }

    private static byte[] concat(byte[]... arrays) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            for (byte[] array : arrays) {
                baos.write(array);
            }
            return baos.toByteArray();
        }
    }
}
