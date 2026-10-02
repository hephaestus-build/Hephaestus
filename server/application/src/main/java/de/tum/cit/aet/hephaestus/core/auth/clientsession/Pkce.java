package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** RFC 7636 S256 checks and the opaque secrets an installed-client session hands out. */
public final class Pkce {

    /** RFC 7636 §4.1: 43–128 unreserved characters. */
    public static final String VERIFIER_PATTERN = "^[A-Za-z0-9._~-]{43,128}$";

    /** A handoff code or refresh secret: 32 random bytes, base64url without padding. */
    public static final String SECRET_PATTERN = "^[A-Za-z0-9_-]{43}$";

    private static final Pattern VERIFIER = Pattern.compile(VERIFIER_PATTERN);

    /** The S256 challenge of any verifier is a 32-byte digest, which base64url-encodes to exactly 43. */
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private static final SecureRandom RNG = new SecureRandom();

    private Pkce() {}

    public static boolean isChallenge(String candidate) {
        return CHALLENGE.matcher(candidate).matches();
    }

    /** Whether {@code verifier} hashes to {@code challenge}, compared in constant time. */
    public static boolean verifies(String verifier, String challenge) {
        if (!VERIFIER.matcher(verifier).matches()) {
            return false;
        }
        byte[] computed =
                Base64.getUrlEncoder().withoutPadding().encode(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        return MessageDigest.isEqual(computed, challenge.getBytes(StandardCharsets.US_ASCII));
    }

    /** 256 random bits, base64url without padding: unguessable, so its hash needs no salt. */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of a secret as 64 hex characters; the only form a secret is stored in. */
    public static String hash(String secret) {
        return HexFormat.of().formatHex(sha256(secret.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
        }
    }
}
