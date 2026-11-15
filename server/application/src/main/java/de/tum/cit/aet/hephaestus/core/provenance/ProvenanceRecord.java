package de.tum.cit.aet.hephaestus.core.provenance;

import com.nimbusds.jose.util.Base64URL;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;

/**
 * What a provenance record says about one generated text. It names no person and no workspace: the record travels
 * with the text, also to places where anyone can read it.
 *
 * @param id the record's own identity, which this instance can look up
 * @param issuer the web address of the instance that signed it
 * @param version the Hephaestus release that generated the text
 * @param model the model that generated it, as its provider names it; {@code null} when no model was involved
 * @param generatedAt when the text was generated
 * @param contentSha256 the base64url SHA-256 of {@link ProvenanceText#canonical} of the text
 */
@NamedInterface("provenance")
public record ProvenanceRecord(
        UUID id,
        String issuer,
        String version,
        @Nullable String model,
        Instant generatedAt,
        Kind kind,
        String contentSha256) {

    /** The name in each record's {@code gen} claim. */
    public static final String GENERATOR = "Hephaestus";

    /** What kind of text a record marks. */
    @NamedInterface("provenance")
    public enum Kind {
        /** Practice feedback. Today only the copies on GitHub and GitLab carry the record (ADR 0105). */
        FEEDBACK,
        /** A product-feedback issue that a model drafted. */
        ISSUE
    }

    /** Whether {@code text} is the text this record marks, up to {@link ProvenanceText#canonical}. */
    public boolean marks(String text) {
        return MessageDigest.isEqual(ProvenanceText.sha256(text), new Base64URL(contentSha256).decode());
    }
}
