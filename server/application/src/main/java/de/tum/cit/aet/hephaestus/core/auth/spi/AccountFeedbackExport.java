package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Feedback delivered to the account and the account's responses to it. Undelivered feedback and the text of
 * withdrawn feedback stay with the privacy contact, as in the app.
 */
public interface AccountFeedbackExport {
    List<Received> received(long accountId);

    List<Response> responses(long accountId);

    /**
     * One delivered feedback. A withdrawn feedback keeps its dates and drops its text.
     *
     * @param pieces what was posted on the work or in a conversation, in order
     */
    record Received(
            String workspace,
            UUID feedbackId,
            String destination,
            @Nullable String body,
            List<Piece> pieces,
            Instant createdAt,
            Instant deliveredAt,
            @Nullable Instant withdrawnAt) {}

    record Piece(
            String kind,
            @Nullable String path,
            @Nullable Integer startLine,
            @Nullable Integer endLine,
            @Nullable String body,
            Instant postedAt) {}

    /** Every response the account gave, oldest first; the latest one per feedback is current. */
    record Response(
            String workspace,
            UUID feedbackId,
            @Nullable String resolution,
            @Nullable String usefulness,
            @Nullable String comment,
            Instant createdAt) {}
}
