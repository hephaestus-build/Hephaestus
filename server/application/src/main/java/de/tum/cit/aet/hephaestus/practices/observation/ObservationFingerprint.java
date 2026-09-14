package de.tum.cit.aet.hephaestus.practices.observation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Hash of a practice, reviewed artifact, developer and exact evidence path for location grouping.
 * A shared location does not establish that two observations describe the same behavior.
 * Feedback delivery and recipient reactions are bound to observation IDs.
 */
public final class ObservationFingerprint {

    /** Separates the typed identity fields; the exact path is the final field. */
    private static final char SEP = '\u001F'; // ASCII unit separator

    private ObservationFingerprint() {}

    /** Compute the 64-character location key, preserving case and whitespace in repository paths. */
    public static String compute(
            @Nullable String practiceSlug,
            @Nullable String artifactKind,
            long artifactId,
            long aboutUserId,
            @Nullable String firstLocationPath) {
        Objects.requireNonNull(practiceSlug, "practiceSlug");
        Objects.requireNonNull(artifactKind, "artifactKind");

        String canonical = new StringBuilder()
                .append(practiceSlug)
                .append(SEP)
                .append(artifactKind)
                .append(SEP)
                .append(artifactId)
                .append(SEP)
                .append(aboutUserId)
                .append(SEP)
                .append(firstLocationPath == null ? "" : firstLocationPath)
                .toString();

        return sha256Hex(canonical);
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JVM; absence is unrecoverable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
