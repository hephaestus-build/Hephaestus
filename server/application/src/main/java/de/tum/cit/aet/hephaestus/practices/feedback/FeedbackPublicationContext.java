package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one owner of a public piece's publication context: what other reviews have communicated on the same work to the
 * same people, and whether one of their writes started without a known outcome. The history read issues it; the
 * preparation, approval and write fences compare it with the current rows.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackPublicationContext {
    private static final Pattern TOKEN = Pattern.compile("v1:([1-9][0-9]*(?:,[1-9][0-9]*)*):([0-9a-f]{64})");
    private final FeedbackPublicationRepository rows;
    private final ReviewRepository reviews;

    /** The context a history read hands to the composer, bound to the work, the review and the people it covers. */
    public String issue(long workspaceId, long itemId, UUID reviewId, Collection<Long> developerIds) {
        var developers = developerIds.stream().distinct().sorted().toList();
        if (developers.isEmpty()) throw new IllegalArgumentException("A publication context names its developers.");
        var facts = rows.facts(
                workspaceId, itemId, reviewId, developers, binding(workspaceId, itemId, reviewId, developers));
        return token(developers, facts.getContext());
    }

    /**
     * Why a new public copy for this developer cannot be created now. A missing context was issued before contexts
     * existed: it stands for an empty history, so it holds only while no other review has communicated on the work.
     */
    public Optional<FeedbackStopReason> refusal(
            long workspaceId, long itemId, UUID reviewId, long developerId, @Nullable String context) {
        List<Long> developers = context == null ? List.of(developerId) : developers(context);
        if (developers == null || !developers.contains(developerId))
            return Optional.of(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
        var facts = rows.facts(
                workspaceId, itemId, reviewId, developers, binding(workspaceId, itemId, reviewId, developers));
        if (facts.getUnresolved()) return Optional.of(FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
        String composed = context == null ? token(developers, facts.getEmptyContext()) : context;
        return composed.equals(token(developers, facts.getContext()))
                ? Optional.empty()
                : Optional.of(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
    }

    /** The same rule for a prepared on-work piece, against the context its immutable composition record retains. */
    public Optional<FeedbackStopReason> refusal(Feedback row) {
        Long itemId = row.getItemId();
        if (row.getDestination() != FeedbackDestination.ON_WORK || itemId == null) return Optional.empty();
        var retained = reviews.findByIdAndWorkspaceId(row.getReviewId(), row.getWorkspaceId())
                .orElseThrow()
                .getChecks()
                .path("feedbackComposition")
                .path(row.getId().toString())
                .path("publicationContext");
        return refusal(
                row.getWorkspaceId(),
                itemId,
                row.getReviewId(),
                row.getDeveloperId(),
                retained.isString() ? retained.asString() : null);
    }

    public Optional<Long> itemOf(long workspaceId, UUID feedbackId) {
        return rows.itemOf(workspaceId, feedbackId);
    }

    private static @Nullable List<Long> developers(String context) {
        var matcher = TOKEN.matcher(context);
        if (!matcher.matches()) return null;
        List<Long> developers;
        try {
            developers = Arrays.stream(matcher.group(1).split(","))
                    .map(Long::valueOf)
                    .toList();
        } catch (NumberFormatException outOfRange) {
            // An id beyond the account id range was never issued, so the composer did not read this history.
            return null;
        }
        return developers.equals(developers.stream().distinct().sorted().toList()) ? developers : null;
    }

    private static String token(List<Long> developers, String digest) {
        return "v1:" + join(developers) + ":" + digest;
    }

    private static String binding(long workspaceId, long itemId, UUID reviewId, List<Long> developers) {
        return "hephaestus.publication-context|v1|" + workspaceId + "|" + itemId + "|" + reviewId + "|"
                + join(developers) + "|";
    }

    private static String join(List<Long> developers) {
        return developers.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
