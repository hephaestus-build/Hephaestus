package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.web.webauthn.api.Bytes;
import tools.jackson.dataformat.cbor.CBORMapper;

/** Test-only authenticator. Real ES256 signatures exercise Spring's production verification path. */
final class PasskeyTestAuthenticator {
    private final KeyPair key;
    private final Bytes id = Bytes.random();
    private final PasskeyJson json = new PasskeyJson();
    private final CBORMapper cbor = CBORMapper.builder().build();

    PasskeyTestAuthenticator() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        key = generator.generateKeyPair();
    }

    private static String encoded(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] clientData(String type, String challenge, String origin) {
        return json.write(Map.of("type", type, "challenge", challenge, "origin", origin, "crossOrigin", false))
                .getBytes(StandardCharsets.UTF_8);
    }

    private String credential(Map<String, Object> response) {
        return json.write(Map.of(
                "id",
                id.toBase64UrlString(),
                "rawId",
                id.toBase64UrlString(),
                "type",
                "public-key",
                "response",
                response,
                "clientExtensionResults",
                Map.of()));
    }

    String register(String options) throws GeneralSecurityException {
        var creation = json.readCreation(options);
        var publicKey = (ECPublicKey) key.getPublic();
        byte[] cose = cbor.writeValueAsBytes(Map.of(
                1,
                2,
                3,
                -7,
                -1,
                1,
                -2,
                scalar(publicKey.getW().getAffineX()),
                -3,
                scalar(publicKey.getW().getAffineY())));
        var authData = ByteBuffer.allocate(37 + 16 + 2 + id.getBytes().length + cose.length)
                .put(MessageDigest.getInstance("SHA-256")
                        .digest(creation.getRp().getId().getBytes(StandardCharsets.UTF_8)))
                .put((byte) 0x45)
                .putInt(0)
                .put(new byte[16])
                .putShort((short) id.getBytes().length)
                .put(id.getBytes())
                .put(cose)
                .array();
        byte[] attestation = cbor.writeValueAsBytes(Map.of("fmt", "none", "authData", authData, "attStmt", Map.of()));
        return credential(Map.of(
                "attestationObject",
                encoded(attestation),
                "clientDataJSON",
                encoded(clientData(
                        "webauthn.create", creation.getChallenge().toBase64UrlString(), "http://localhost:4200")),
                "transports",
                new String[] {"internal"}));
    }

    String assertCredential(String options, String userHandle, boolean verified, String origin, int counter)
            throws GeneralSecurityException {
        return assertCredential(options, userHandle, verified, origin, counter, true);
    }

    String assertCredential(
            String options, String userHandle, boolean verified, String origin, int counter, boolean validSignature)
            throws GeneralSecurityException {
        var request = json.readRequest(options);
        byte[] clientData = clientData("webauthn.get", request.getChallenge().toBase64UrlString(), origin);
        byte[] authData = ByteBuffer.allocate(37)
                .put(MessageDigest.getInstance("SHA-256")
                        .digest(Objects.requireNonNull(request.getRpId()).getBytes(StandardCharsets.UTF_8)))
                .put(verified ? (byte) 0x05 : (byte) 0x01)
                .putInt(counter)
                .array();
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(validSignature ? key.getPrivate() : new PasskeyTestAuthenticator().key.getPrivate());
        signature.update(authData);
        signature.update(MessageDigest.getInstance("SHA-256").digest(clientData));
        return credential(Map.of(
                "authenticatorData",
                encoded(authData),
                "clientDataJSON",
                encoded(clientData),
                "signature",
                encoded(signature.sign()),
                "userHandle",
                userHandle));
    }

    private static byte[] scalar(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] padded = new byte[32];
        int size = Math.min(raw.length, padded.length);
        System.arraycopy(raw, raw.length - size, padded, padded.length - size, size);
        return padded;
    }
}
