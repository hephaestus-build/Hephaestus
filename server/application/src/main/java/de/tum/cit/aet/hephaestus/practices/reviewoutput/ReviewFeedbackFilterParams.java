package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackQueryFilter;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.web.QueryFilterSupport;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

public record ReviewFeedbackFilterParams(
        @RequestParam(required = false) @Nullable List<FeedbackDeliveryState> deliveryState,
        @RequestParam(required = false) @Nullable List<FeedbackSuppressionReason> suppressionReason,
        @RequestParam(required = false) @Nullable List<FeedbackChannel> channel,
        @RequestParam(required = false) @Nullable UUID agentJobId,
        /**
         * A bare string, not an {@link ArtifactKind} — {@link QueryFilterSupport#artifactKind} has the reason,
         * and parses it in {@link #toFilter()}, where a malformed value becomes a 400.
         */
        @Parameter(description = "Kind of reviewed work, e.g. scm.pull_request")
        @RequestParam(required = false)
        @Nullable
        String artifactKind,

        @Parameter(description = "Artifact ID; requires artifactKind")
        @RequestParam(required = false)
        @Positive
        @Nullable
        Long artifactId,

        @RequestParam(required = false) @Positive @Nullable Long recipientUserId,

        @Parameter(
                description = "Practices whose feedback to list (repeatable): feedback bound to an observation of any"
                        + " of them, in any role")
        @RequestParam(required = false)
        @Nullable
        List<String> practiceSlug,

        @Parameter(description = "Inclusive lower bound")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @Nullable
        Instant from,

        @Parameter(description = "Exclusive upper bound")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @Nullable
        Instant to) {
    public FeedbackQueryFilter toFilter() {
        if (artifactId != null && artifactKind == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "artifactId requires artifactKind");
        }
        ArtifactKind kind = QueryFilterSupport.artifactKind(artifactKind);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        return new FeedbackQueryFilter(
                deliveryState,
                suppressionReason,
                channel,
                agentJobId,
                kind,
                artifactId,
                recipientUserId,
                practiceSlug,
                from,
                to);
    }
}
