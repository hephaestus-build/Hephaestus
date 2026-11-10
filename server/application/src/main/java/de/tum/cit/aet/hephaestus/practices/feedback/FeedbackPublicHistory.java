package de.tum.cit.aet.hephaestus.practices.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackPublicHistory {
    private final ResultViewQuery views;
    private final ResultPresentation presentation;
    private final ReviewRepository reviews;
    private final ObjectMapper mapper;
    private final ResultSourceAuthorization sourceAuthorization;
    private final PracticeRepository practices;

    public record AuthorizedSupport(UUID resultId, String practiceSlug, HistoryCustody custody) {}

    public Optional<AuthorizedSupport> authorize(Result result, SourceUsePurpose purpose) {
        var facts = sourceAuthorization.custody(result);
        if (!sourceAuthorization.authorized(result, facts, purpose)) return Optional.empty();
        return practices
                .findByIdAndWorkspaceId(result.getPracticeId(), result.getWorkspaceId())
                .map(practice -> new AuthorizedSupport(result.getId(), practice.getSlug(), facts));
    }

    public JsonNode render(
            Feedback row,
            List<Result> support,
            List<AuthorizedSupport> authorized,
            List<FeedbackCopy> confirmed,
            boolean withdrawn,
            boolean textAllowed) {
        Currentness freshness = currentness(row.getWorkspaceId(), support);
        var byId = authorized.stream().collect(Collectors.toMap(AuthorizedSupport::resultId, entry -> entry));
        @Nullable
        String summary =
                !textAllowed || confirmed.stream().noneMatch(copy -> copy.getKind() == FeedbackCopyKind.SUMMARY)
                        ? null
                        : row.getBody();
        var projection = new PublicEntry(
                row.getId(),
                row.getDestination(),
                row.getState(),
                Objects.requireNonNull(row.getDeliveredAt()),
                reviews.findByIdAndWorkspaceId(row.getReviewId(), row.getWorkspaceId())
                        .orElseThrow()
                        .getItemVersion(),
                summary,
                confirmed.stream().anyMatch(copy -> copy.getKind() == FeedbackCopyKind.SUMMARY)
                        ? row.getBody() == null
                        : row.getBody() != null,
                freshness,
                withdrawn,
                support.stream()
                        .map(result -> new PublicBasis(
                                result.getId(),
                                Objects.requireNonNull(byId.get(result.getId())).practiceSlug(),
                                result.getPracticeVersionId() == null
                                        ? null
                                        : result.getPracticeVersionId().toString(),
                                result.getOutcome()))
                        .toList(),
                confirmed.stream()
                        .map(copy -> new PublicCopy(
                                copy.getId(),
                                copy.getKind(),
                                !textAllowed
                                        ? null
                                        : copy.getKind() == FeedbackCopyKind.SUMMARY ? row.getBody() : copy.getBody(),
                                copy.getPath(),
                                copy.getStartLine(),
                                copy.getEndLine(),
                                copy.getSide(),
                                copy.getExternalRef(),
                                copy.getExternalUrl(),
                                Objects.requireNonNull(copy.getPostedAt()),
                                copy.getState()))
                        .toList());
        return mapper.valueToTree(projection);
    }

    /** Whether the claim a piece of feedback rests on still holds: its least current supporting result decides. */
    public Currentness currentness(long workspaceId, List<Result> support) {
        if (support.isEmpty()) return Currentness.UNVERIFIABLE;
        var currentness = views.views(workspaceId, support).stream()
                .map(presentation::currentness)
                .toList();
        return currentness.contains(Currentness.UNVERIFIABLE)
                ? Currentness.UNVERIFIABLE
                : currentness.contains(Currentness.STALE) ? Currentness.STALE : Currentness.CURRENT;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record PublicEntry(
            UUID id,
            FeedbackDestination destination,
            FeedbackState state,
            Instant deliveredAt,
            @Nullable String itemVersion,
            @Nullable String body,
            boolean bodyUnavailable,
            Currentness currentness,
            boolean withdrawn,
            List<PublicBasis> basedOn,
            List<PublicCopy> copies) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record PublicBasis(
            UUID resultId, String practiceSlug, @Nullable String practiceVersionId, ResultOutcome outcome) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record PublicCopy(
            UUID id,
            FeedbackCopyKind kind,
            @Nullable String body,
            @Nullable String path,
            @Nullable Integer startLine,
            @Nullable Integer endLine,
            @Nullable CitationSide side,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            Instant postedAt,
            FeedbackCopyState state) {}
}
