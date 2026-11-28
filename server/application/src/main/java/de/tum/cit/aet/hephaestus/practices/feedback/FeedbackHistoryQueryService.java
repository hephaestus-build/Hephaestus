package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
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
import de.tum.cit.aet.hephaestus.practices.spi.NoteThreadState;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackHistory;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
@ConditionalOnServerRole
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
    private final FeedbackConversationHistory conversationHistory;
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
        return read(workspaceId, selected, viewerAccountId, limit, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
    }

    @Override
    public Optional<PrivateFeedbackHistory> forPrivateComposition(
            long workspaceId, long developerAccountId, Collection<PrivateFeedbackHistory.Route> routes, int limit) {
        if (limit < 1) throw new InvalidInputException("A history read needs a positive limit.");
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
        var listed = rows.stream().limit(limit).toList();
        var page = new Page(workspaceId, listed, developerAccountId, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, true);
        for (var row : listed) {
            var facts = page.facts(row);
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
            long workspaceId,
            Specification<Feedback> selected,
            long viewerAccountId,
            int limit,
            SourceUsePurpose purpose) {
        if (limit < 1) throw new InvalidInputException("A history read needs a positive limit.");
        var rows = feedback.findAll(selected, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .getContent();
        // A read across viewers, which one transaction may make for several: it reads no conversation, as each
        // conversation has one owner.
        var page = new Page(workspaceId, rows, viewerAccountId, purpose, false);
        return rows.stream().map(row -> page.facts(row).entry()).toList();
    }

    /**
     * The developer's own pieces as they may learn of them: in a state they may see, and not withdrawn, as their
     * Practice profile reads them ({@code ResultPresentation.ownFeedback}).
     */
    private List<FeedbackHistoryEntry> readOwn(
            long workspaceId, Specification<Feedback> selected, long developerAccountId, int limit) {
        if (limit < 1) throw new InvalidInputException("A history read needs a positive limit.");
        Specification<Feedback> visible =
                selected.and((row, query, cb) -> row.get("state").in(FeedbackState.VISIBLE_TO_DEVELOPER));
        // Withdrawal is a decision on the piece, not a column: page on until the limit holds pieces that are kept, so
        // a caller that asks for one more row than it lists still learns whether more exist.
        List<FeedbackHistoryEntry> kept = new ArrayList<>();
        for (int page = 0; kept.size() < limit; page++) {
            var rows = feedback.findAll(
                            visible, PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                    .getContent();
            var read = new Page(workspaceId, rows, developerAccountId, SourceUsePurpose.CONVERSATIONAL_MENTORING, true);
            for (var row : rows) {
                if (kept.size() >= limit) break;
                var facts = read.facts(row);
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

    /**
     * What the facts of a page of rows read, read once for the page: their basis, support, the support's decisions,
     * copies with the mirrored state of their provider threads, withdrawals, incorrectness, responses and conversation
     * threads. Each row's facts are then decided from it, as one row's facts were decided alone.
     */
    private final class Page {
        private final long viewerAccountId;
        private final Map<UUID, List<FeedbackBasisRepository.ReadRow>> edges = new HashMap<>();
        private final Map<UUID, Result> supportById = new HashMap<>();
        private final Map<UUID, FeedbackPublicHistory.AuthorizedSupport> publicSupport = new HashMap<>();
        private final Map<UUID, ResultHistoryEntry> privateSupport = new HashMap<>();
        private final Map<UUID, List<FeedbackCopy>> copiesOf = new HashMap<>();
        private final Set<UUID> withdrawn = new HashSet<>();
        private final Set<UUID> incorrect = new HashSet<>();
        private final Set<UUID> incorrectCopies = new HashSet<>();
        private final Map<UUID, FeedbackResponse> responsesOf = new HashMap<>();
        private final Map<UUID, UUID> threads = new HashMap<>();
        private final Map<UUID, String> shownWords = new HashMap<>();
        private final Map<UUID, String> identities = new HashMap<>();
        private final Map<String, NoteThreadState> threadStates = new HashMap<>();

        Page(
                long workspaceId,
                List<Feedback> rows,
                long viewerAccountId,
                SourceUsePurpose purpose,
                boolean conversationThreads) {
            this.viewerAccountId = viewerAccountId;
            for (var chunk : chunks(rows)) read(workspaceId, chunk, purpose, conversationThreads);
        }

        private void read(
                long workspaceId, List<Feedback> rows, SourceUsePurpose purpose, boolean conversationThreads) {
            var ids = rows.stream().map(Feedback::getId).toList();
            basis.findReadRows(workspaceId, ids)
                    .forEach(edge -> edges.computeIfAbsent(edge.feedbackId(), ignored -> new ArrayList<>())
                            .add(edge));
            var supportIds = rows.stream()
                    .flatMap(row -> resultIds(row.getId()).stream())
                    .distinct()
                    .toList();
            for (var chunk : chunks(supportIds))
                results.findByWorkspaceIdAndIdIn(workspaceId, chunk)
                        .forEach(result -> supportById.put(result.getId(), result));
            // The basis of a piece on the work is the support of its copies, so it names every problem a note cites.
            identities.putAll(publicHistoryReader.identities(
                    workspaceId,
                    rows.stream()
                            .filter(row -> row.getDestination() == FeedbackDestination.ON_WORK)
                            .flatMap(row -> this.edges.getOrDefault(row.getId(), List.of()).stream())
                            .map(FeedbackBasisRepository.ReadRow::problemId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList()));
            // A piece on the work is read by its public support; any other by its developer's own history.
            publicSupport.putAll(publicHistoryReader.authorizeAll(
                    workspaceId,
                    rows.stream()
                            .filter(row -> row.getDestination() == FeedbackDestination.ON_WORK)
                            .flatMap(row -> support(row.getId()).stream())
                            .distinct()
                            .toList(),
                    purpose));
            var privateIds = rows.stream()
                    .filter(row -> row.getDestination() != FeedbackDestination.ON_WORK)
                    .flatMap(row -> resultIds(row.getId()).stream())
                    .distinct()
                    .toList();
            if (!privateIds.isEmpty())
                privateSupport.putAll(history.findAll(workspaceId, privateIds, viewerAccountId, purpose));
            copies.findByWorkspaceIdAndFeedbackIdInOrderByPositionAsc(workspaceId, ids)
                    .forEach(copy -> copiesOf.computeIfAbsent(copy.getFeedbackId(), ignored -> new ArrayList<>())
                            .add(copy));
            var onWork = rows.stream()
                    .filter(row -> row.getDestination() == FeedbackDestination.ON_WORK && row.getItemId() != null)
                    .toList();
            threadStates.putAll(publicHistoryReader.threadStates(
                    workspaceId,
                    onWork.stream()
                            .map(Feedback::getItemId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList(),
                    onWork.stream()
                            .flatMap(row -> copiesOf.getOrDefault(row.getId(), List.of()).stream())
                            .filter(NoteCitations::note)
                            .map(FeedbackCopy::getThreadRef)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList()));
            withdrawn.addAll(visibility.withdrawnAt(workspaceId, ids).keySet());
            incorrect.addAll(basis.incorrectFeedbackIds(workspaceId, ids));
            incorrectCopies.addAll(copies.incorrectCopyIdsOf(workspaceId, ids));
            var own = rows.stream()
                    .filter(row -> row.getDeveloperId() == viewerAccountId)
                    .map(Feedback::getId)
                    .toList();
            if (!own.isEmpty())
                responses
                        .findCurrentForFeedbackIds(workspaceId, own)
                        .forEach(response -> responsesOf.put(response.getFeedbackId(), response));
            var ownConversation = rows.stream()
                    .filter(row -> conversationThreads
                            && row.getDeveloperId() == viewerAccountId
                            && row.getDestination() == FeedbackDestination.CONVERSATION)
                    .toList();
            if (!ownConversation.isEmpty()) {
                conversationHistory.threads(workspaceId, ownConversation).forEach(thread -> {
                    threads.put(thread.getFeedbackId(), thread.getConversationId());
                    if (thread.getWords() != null) shownWords.put(thread.getFeedbackId(), thread.getWords());
                });
                // The 1.0 upgrade brought a 0.x receipt over with its words and without its answer or write fence:
                // its copy is the only home of those words. A receipt of this release always has a write fence.
                for (var row : ownConversation)
                    copiesOf.getOrDefault(row.getId(), List.of()).stream()
                            .filter(copy -> copy.getKind() == FeedbackCopyKind.CHAT_TURN
                                    && copy.getPostedAt() != null
                                    && copy.getConversationEntryId() == null
                                    && copy.getWriteStartedAt() == null
                                    && copy.getBody() != null)
                            .findFirst()
                            .ifPresent(copy -> shownWords.putIfAbsent(row.getId(), copy.getBody()));
            }
        }

        private List<UUID> resultIds(UUID feedbackId) {
            return edges.getOrDefault(feedbackId, List.of()).stream()
                    .map(FeedbackBasisRepository.ReadRow::resultId)
                    .distinct()
                    .toList();
        }

        private List<Result> support(UUID feedbackId) {
            return resultIds(feedbackId).stream()
                    .map(supportById::get)
                    .filter(Objects::nonNull)
                    .toList();
        }

        /** One history row as the page's purpose may see it, with the support it was judged on. */
        Facts facts(Feedback row) {
            var edges = this.edges.getOrDefault(row.getId(), List.of());
            var ids = resultIds(row.getId());
            var support = support(row.getId());
            var authorized = row.getDestination() == FeedbackDestination.ON_WORK
                    ? support.stream()
                            .map(result -> publicSupport.get(result.getId()))
                            .filter(Objects::nonNull)
                            .toList()
                    : ids.stream()
                            .map(privateSupport::get)
                            .filter(Objects::nonNull)
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
            var confirmed = copiesOf.getOrDefault(row.getId(), List.of()).stream()
                    .filter(copy -> copy.getPostedAt() != null
                            && (copy.getState() == FeedbackCopyState.POSTED
                                    || copy.getState() == FeedbackCopyState.RETRACTED))
                    .toList();
            boolean withdrawn = this.withdrawn.contains(row.getId());
            boolean textAllowed = complete && !withdrawn && !incorrect.contains(row.getId());
            @Nullable String body = null;
            if (textAllowed) {
                if (row.getDestination() == FeedbackDestination.ON_WORK) {
                    body = confirmed.stream()
                            .filter(copy -> !incorrectCopies.contains(copy.getId()))
                            .map(copy -> copy.getKind() == FeedbackCopyKind.SUMMARY ? row.getBody() : copy.getBody())
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining("\n\n"));
                    if (body.isEmpty()) body = null;
                } else if (row.getDestination() == FeedbackDestination.CONVERSATION && row.getDeliveredAt() != null) {
                    // A read across viewers reads no conversation, so it shows no words of one.
                    body = shownWords.get(row.getId());
                } else if (row.getState() == FeedbackState.READY
                        || (row.getDestination() == FeedbackDestination.PRACTICE_PAGE
                                && row.getDeliveredAt() != null)) {
                    body = row.getBody();
                }
            }
            @Nullable
            FeedbackResponse response = row.getDeveloperId() == viewerAccountId ? responsesOf.get(row.getId()) : null;
            @Nullable
            JsonNode publicHistory = row.getDestination() == FeedbackDestination.ON_WORK
                            && complete
                            && row.getDeliveredAt() != null
                            && !confirmed.isEmpty()
                    ? publicHistoryReader.render(
                            row,
                            new FeedbackPublicHistory.PublicSupport(support, authorized, identities, threadStates),
                            confirmed,
                            withdrawn,
                            textAllowed)
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
                            .map(FeedbackBasisRepository.ReadRow::problemId)
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
                    threads.get(row.getId()),
                    complete,
                    publicHistory);
            return new Facts(visible, support, authorized, withdrawn);
        }
    }

    /** A bounded number of ids per collection read, well below the driver's bind-parameter limit. */
    private static final int READ_CHUNK = 1000;

    private static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < values.size(); from += READ_CHUNK)
            chunks.add(values.subList(from, Math.min(values.size(), from + READ_CHUNK)));
        return chunks;
    }
}
