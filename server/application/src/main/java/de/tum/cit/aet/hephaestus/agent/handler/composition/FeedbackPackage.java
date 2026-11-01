package de.tum.cit.aet.hephaestus.agent.handler.composition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** A draft from admitted results; the caller's fenced attempt supplies its owner and review. */
public record FeedbackPackage(
        List<UUID> basisResultIds,
        List<ComposedFeedback> composition,
        boolean publicComposition,
        @Nullable String publicationContext) {
    private static final JsonMapper MAPPER = new JsonMapper();

    public FeedbackPackage {
        basisResultIds = List.copyOf(basisResultIds);
        composition = List.copyOf(composition);
    }

    /** Identifies these exact checked words and support in this sealed review, across attempt restarts. */
    public String draftId(UUID reviewId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(reviewId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(MAPPER.writeValueAsBytes(this)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
