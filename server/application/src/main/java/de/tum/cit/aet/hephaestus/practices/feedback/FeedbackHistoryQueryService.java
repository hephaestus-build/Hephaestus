package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponseRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackHistory;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackHistoryQueryService implements FeedbackHistoryQuery {
    private final FeedbackRepository feedback;
    private final FeedbackBasisRepository basis;
    private final FeedbackCopyRepository copies;
    private final FeedbackResponseRepository responses;
    private final FeedbackVisibilityService visibility;
    private final ResultRepository results;
    private final ResultHistoryQuery history;
    private final FeedbackQueryRepository queries;
    private final FeedbackPublicHistory publicHistoryReader;
    private final AccountMembershipQuery memberships;
    private final FeedbackPublicationContext publication;
    private final FeedbackSupersession replacements;

    @Override
    public Optional<FeedbackHistoryEntry> find(long workspaceId, UUID feedbackId, long developerAccountId) {
        if (!memberships.isActiveVisibleMember(workspaceId, developerAccountId)) return Optional.empty();
        Specification<Feedback> selected = (row, query, cb) -> cb.and(
                cb.equal(row.get("workspaceId"), workspaceId),
                cb.equal(row.get("id"), feedbackId),
                cb.equal(row.get("developerId"), developerAccountId));
        return readOwn(workspaceId, selected, developerAccountId, 1).stream().findFirst();
    }

    @Override
    public List<FeedbackHistoryEntry> forDeveloper(
            long workspaceId, long developerAccountId, Instant since, int limit) {
        if (!memberships.isActiveVisibleMember(workspaceId, developerAccountId)) return List.of();
        Specification<Feedback> selected = (row, query, cb) -> cb.and(
                cb.equal(row.get("workspaceId"), workspaceId),
                cb.equal(row.get("developerId"), developerAccountId),
                cb.greaterThanOrEqualTo(row.get("createdAt"), since));
        return readOwn(workspaceId, selected, developerAccountId, limit);
    }

    @Override
    public List<FeedbackHistoryEntry> forItem(long workspaceId, long itemId, long viewerAccountId, int limit) {
        if (!memberships.isActiveVisibleMember(workspaceId, viewerAccountId)) return List.of();
        Specification<Feedback> selected = (row, query, cb) -> cb.and(
                cb.equal(row.get("workspaceId"), workspaceId),
                cb.equal(row.get("itemId"), itemId),
                cb.or(
                        cb.equal(row.get("developerId"), viewerAccountId),
                        cb.and(
                                cb.equal(row.get("destination"), FeedbackDestination.ON_WORK),
                                cb.isNotNull(row.get("deliveredAt")))));
        return read(selected, viewerAccountId, limit, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
    }

    @Override
    public Optional<PrivateFeedbackHistory> forPrivateComposition(
            long workspaceId, long developerAccountId, Collection<PrivateFeedbackHistory.Route> routes, int limit) {
        if (limit < 1) throw new IllegalArgumentException("A history read needs a positive limit.");
        if (routes.isEmpty() || !memberships.isActiveVisibleMember(workspaceId, developerAccountId))
            return Optional.empty();
        // Each destination with the practices routed to it, never the product of all of either.
        var routed = routes.stream()
                .collect(Collectors.groupingBy(
                        PrivateFeedbackHistory.Route::destination,
                        Collectors.mapping(PrivateFeedbackHistory.Route::practiceId, Collectors.toSet())));
        var conversation = routed.getOrDefault(FeedbackDestination.CONVERSATION, Set.of());
        Specification<Feedback> selected = (row, query, cb) -> {
            var onRoute = routed.entrySet().stream()
                    .map(route -> {
                        var about = Objects.requireNonNull(query).subquery(UUID.class);
                        var edge = about.from(FeedbackBasis.class);
                        var result = about.from(Result.class);
                        about.select(edge.<UUID>get("feedbackId"))
                                .where(
                                        cb.equal(edge.get("workspaceId"), workspaceId),
                                        cb.equal(result.get("workspaceId"), workspaceId),
                                        cb.equal(result.get("id"), edge.get("resultId")),
                                        result.get("practiceId").in(route.getValue()));
                        return cb.and(
                                cb.equal(row.get("destination"), route.getKey()),
                                row.get("id").in(about));
                    })
                    .toArray(Predicate[]::new);
            return cb.and(
                    cb.equal(row.get("workspaceId"), workspaceId),
                    cb.equal(row.get("developerId"), developerAccountId),
                    cb.or(onRoute),
                    cb.or(
                            cb.isNotNull(row.get("deliveredAt")),
                            cb.and(
                                    cb.equal(row.get("destination"), FeedbackDestination.CONVERSATION),
                                    cb.equal(row.get("state"), FeedbackState.READY))));
        };
        Instant readAt = Instant.now();
        var rows = feedback.findAll(
                        selected, PageRequest.of(0, limit + 1, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .getContent();
        var entries = new ArrayList<PrivateFeedbackHistory.Entry>();
        int withoutCopyAuthority = 0;
        for (var row : rows.stream().limit(limit).toList()) {
            var facts = facts(row, developerAccountId, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
            // A row whose support may not be copied for this purpose is left out whole, and counted.
            if (!facts.entry().copyAuthorityComplete()) {
                withoutCopyAuthority++;
                continue;
            }
            var slugs = facts.authorized().stream()
                    .collect(Collectors.toMap(
                            FeedbackPublicHistory.AuthorizedSupport::resultId,
                            FeedbackPublicHistory.AuthorizedSupport::practiceSlug,
                            (first, second) -> first));
            @Nullable String target = null;
            Long practice = replacements.replaceablePractice(row, developerAccountId);
            if (practice != null && conversation.contains(practice)) {
                long replaceable = practice;
                target = facts.support().stream()
                        .filter(result -> result.getPracticeId() == replaceable)
                        .map(result -> slugs.get(result.getId()))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
            }
            // Queued feedback matters here only as a replacement target on an open conversation route.
            if (row.getDeliveredAt() == null && target == null) continue;
            entries.add(new PrivateFeedbackHistory.Entry(
                    facts.entry(),
                    facts.support().stream()
                            .map(result -> new PrivateFeedbackHistory.Basis(
                                    result.getId(),
                                    Objects.requireNonNull(slugs.get(result.getId())),
                                    result.getPracticeVersionId(),
                                    result.getOutcome()))
                            .toList(),
                    publicHistoryReader.currentness(workspaceId, facts.support()),
                    facts.withdrawn(),
                    target));
        }
        return Optional.of(
                new PrivateFeedbackHistory(readAt, limit, rows.size() > limit, withoutCopyAuthority, entries));
    }

    @Override
    public String publicationContext(long workspaceId, long itemId, UUID reviewId, Collection<Long> developerIds) {
        return publication.issue(workspaceId, itemId, reviewId, developerIds);
    }

    @Override
    public Optional<FeedbackStopReason> publicationRefusal(
            long workspaceId, long itemId, UUID reviewId, long developerId, @Nullable String publicationContext) {
        return publication.refusal(workspaceId, itemId, reviewId, developerId, publicationContext);
    }

    private List<FeedbackHistoryEntry> read(
            Specification<Feedback> selected, long viewerAccountId, int limit, SourceUsePurpose purpose) {
        if (limit < 1) throw new IllegalArgumentException("A history read needs a positive limit.");
        return feedback
                .findAll(selected, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .getContent()
                .stream()
                .map(row -> facts(row, viewerAccountId, purpose).entry())
                .toList();
    }

    /**
     * The developer's own pieces as they may learn of them: in a state they may see, and not withdrawn, as their
     * Practice profile reads them ({@code ResultPresentation.ownFeedback}).
     */
    private List<FeedbackHistoryEntry> readOwn(
            long workspaceId, Specification<Feedback> selected, long developerAccountId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("A history read needs a positive limit.");
        Specification<Feedback> visible =
                selected.and((row, query, cb) -> row.get("state").in(FeedbackState.VISIBLE_TO_DEVELOPER));
        // Withdrawal is a decision on the piece, not a column: page on until the limit holds pieces that are kept, so
        // a caller that asks for one more row than it lists still learns whether more exist. One query per page skips
        // the withdrawn rows before their full read.
        List<FeedbackHistoryEntry> kept = new ArrayList<>();
        for (int page = 0; kept.size() < limit; page++) {
            var rows = feedback.findAll(
                            visible, PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                    .getContent();
            var withdrawn = visibility.withdrawnAt(
                    workspaceId, rows.stream().map(Feedback::getId).toList());
            for (var row : rows) {
                if (withdrawn.containsKey(row.getId()) || kept.size() >= limit) continue;
                var facts = facts(row, developerAccountId, SourceUsePurpose.CONVERSATIONAL_MENTORING);
                if (!facts.withdrawn()) kept.add(facts.entry());
            }
            if (rows.size() < limit) break;
        }
        return List.copyOf(kept);
    }

    /** One history row as {@code purpose} may see it, with the support it was judged on. */
    private record Facts(
            FeedbackHistoryEntry entry,
            List<Result> support,
            List<FeedbackPublicHistory.AuthorizedSupport> authorized,
            boolean withdrawn) {}

    private Facts facts(Feedback row, long viewerAccountId, SourceUsePurpose purpose) {
        long workspaceId = row.getWorkspaceId();
        var edges = basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(workspaceId, row.getId());
        var ids = edges.stream().map(FeedbackBasis::getResultId).distinct().toList();
        var support = results.findByWorkspaceIdAndIdIn(workspaceId, ids);
        var authorized = row.getDestination() == FeedbackDestination.ON_WORK
                ? support.stream()
                        .flatMap(result -> publicHistoryReader.authorize(result, purpose).stream())
                        .toList()
                : ids.stream()
                        .flatMap(id -> history.find(workspaceId, id, viewerAccountId, purpose).stream())
                        .map(entry -> new FeedbackPublicHistory.AuthorizedSupport(
                                entry.resultId(),
                                entry.practiceSlug(),
                                new HistoryCustody(
                                        entry.copyAuthorityComplete(),
                                        false,
                                        entry.copiedIdentities(),
                                        entry.repositoryIds(),
                                        entry.sourceJobIds())))
                        .toList();
        boolean complete = !ids.isEmpty()
                && support.size() == ids.size()
                && authorized.size() == ids.size()
                && authorized.stream()
                        .map(FeedbackPublicHistory.AuthorizedSupport::resultId)
                        .collect(Collectors.toSet())
                        .containsAll(ids)
                && authorized.stream().allMatch(entry -> entry.custody().complete());
        var confirmed = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, row.getId()).stream()
                .filter(copy -> copy.getPostedAt() != null
                        && (copy.getState() == FeedbackCopyState.POSTED
                                || copy.getState() == FeedbackCopyState.RETRACTED))
                .toList();
        boolean withdrawn = visibility.withdrawn(workspaceId, row.getId());
        boolean textAllowed = complete && !withdrawn && !basis.hasIncorrectBasis(workspaceId, row.getId());
        @Nullable String body = null;
        if (textAllowed) {
            if (row.getDestination() == FeedbackDestination.ON_WORK) {
                body = confirmed.stream()
                        .map(copy -> copy.getKind() == FeedbackCopyKind.SUMMARY ? row.getBody() : copy.getBody())
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n\n"));
                if (body.isEmpty()) body = null;
            } else if (row.getDestination() == FeedbackDestination.CONVERSATION && row.getDeliveredAt() != null) {
                body = confirmed.stream()
                        .filter(copy -> copy.getKind() == FeedbackCopyKind.CHAT_TURN)
                        .map(FeedbackCopy::getBody)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
            } else if (row.getState() == FeedbackState.READY
                    || (row.getDestination() == FeedbackDestination.PRACTICE_PAGE && row.getDeliveredAt() != null)) {
                body = row.getBody();
            }
        }
        @Nullable
        FeedbackResponse response = row.getDeveloperId() == viewerAccountId
                ? responses.findCurrent(workspaceId, row.getId()).orElse(null)
                : null;
        @Nullable
        JsonNode publicHistory = row.getDestination() == FeedbackDestination.ON_WORK
                        && complete
                        && row.getDeliveredAt() != null
                        && !confirmed.isEmpty()
                ? publicHistoryReader.render(row, support, authorized, confirmed, withdrawn, textAllowed)
                : null;
        var visible = new FeedbackHistoryEntry(
                row.getId(),
                row.getReviewId(),
                row.getItemId(),
                row.getDeveloperId(),
                row.getDestination(),
                row.getState(),
                body,
                row.getCreatedAt(),
                row.getDeliveredAt(),
                ids,
                edges.stream()
                        .map(FeedbackBasis::getProblemId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList(),
                complete
                        ? authorized.stream()
                                .map(FeedbackPublicHistory.AuthorizedSupport::practiceSlug)
                                .distinct()
                                .sorted()
                                .toList()
                        : List.of(),
                response == null ? null : response.getResolution(),
                response == null ? null : response.getUsefulness(),
                complete
                        ? authorized.stream()
                                .flatMap(entry -> entry.custody().sourceJobIds().stream())
                                .distinct()
                                .sorted(Comparator.comparing(UUID::toString))
                                .toList()
                        : List.of(),
                complete
                        ? authorized.stream()
                                .flatMap(entry -> entry.custody().copiedIdentities().stream())
                                .distinct()
                                .toList()
                        : List.of(),
                complete
                        ? authorized.stream()
                                .flatMap(entry -> entry.custody().repositoryIds().stream())
                                .distinct()
                                .sorted()
                                .toList()
                        : List.of(),
                queries.threadId(workspaceId, row.getId()),
                complete,
                publicHistory);
        return new Facts(visible, support, authorized, withdrawn);
    }
}
