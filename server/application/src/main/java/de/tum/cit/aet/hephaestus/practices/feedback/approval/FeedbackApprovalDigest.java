package de.tum.cit.aet.hephaestus.practices.feedback.approval;

import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.ProposedPlacement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

public final class FeedbackApprovalDigest {

    private FeedbackApprovalDigest() {}

    public static String of(Feedback feedback) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, feedback.getChannel().name());
        append(canonical, feedback.getArtifactKind());
        append(canonical, feedback.getArtifactId());
        append(canonical, feedback.getRecipientUserId());
        append(canonical, feedback.getAboutUserId());
        append(canonical, feedback.getBody());
        append(canonical, feedback.getReviewedRevision());
        for (String slug : feedback.getProposedPracticeSlugs()) append(canonical, slug);
        for (ProposedPlacement placement : feedback.getProposedPlacements()) {
            append(canonical, placement.type());
            append(canonical, placement.body());
            append(canonical, placement.path());
            append(canonical, placement.startLine());
            append(canonical, placement.endLine());
            append(canonical, placement.deliveryKey());
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static void append(StringBuilder target, @Nullable Object value) {
        String text = String.valueOf(value);
        target.append(text.length()).append(':').append(text);
    }
}
