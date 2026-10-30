package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultProofAccess;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ResultSourceAuthorization implements ResultProofAccess {
    private final ProductItemQuery items;
    private final ReviewItemRepository assignments;
    private final HistoryCustodyQuery custody;
    private final ResultRepository results;
    private final JsonMapper json;
    private final ClasspathSourceCatalogRegistry sources;
    private final PersonProcessingSuppression suppression;
    private final PersonIdentityResolver people;
    private final AccountDirectory accounts;
    private final ProblemRepository problems;
    private final ProductItemRepository itemRows;
    private final List<ItemSourceAccess> access;

    public HistoryCustody custody(Result result) {
        return custody(result, lookups(result.getWorkspaceId()));
    }

    private HistoryCustody custody(Result result, Lookups lookups) {
        var producer = lookups.producer(result)
                .filter(m -> produced(result, m)
                        || (result.getEvidenceVersion() == 0
                                && m.getState() == ReviewItemState.WITHHELD
                                && ReviewItem.LEGACY_AMBIGUOUS_RESULTS.equals(m.getReason())));
        var retained = lookups.retained(result).stream()
                .map(m -> m.getDetail())
                .filter(Objects::nonNull)
                .map(detail -> detail.path("custody"))
                .filter(node -> node.isObject())
                .map(node -> json.convertValue(node, HistoryCustody.class))
                .distinct()
                .toList();
        if (retained.size() == 1) return retained.getFirst();
        if (retained.size() > 1) return new HistoryCustody(false, true, List.of(), List.of(), List.of());
        var job = producer.map(m -> m.getJobId()).orElse(null);
        if (job == null) return new HistoryCustody(false, false, List.of(), List.of(), List.of());
        return lookups.receipts.computeIfAbsent(job, ignored -> custody.find(result.getWorkspaceId(), List.of(job)));
    }

    private static boolean produced(Result result, ReviewItem assignment) {
        return result.getId().equals(assignment.getResultId());
    }

    /**
     * The job that produced each of {@code rows}: the job of the assignment that the row's own review made for its
     * practice and item, when that assignment records exactly this result. A legacy assignment that records none, or
     * an assignment without a job, names no job. The custody's {@code sourceJobIds} also list copied dependencies, so
     * they cannot name it. The rows' assignments load in a bounded number of queries.
     */
    Map<UUID, UUID> producingJobs(long workspaceId, Collection<Result> rows) {
        var owned = rows.stream()
                .filter(result -> result.getWorkspaceId() == workspaceId)
                .toList();
        var byAssignment = assignmentsOf(workspaceId, owned);
        Map<UUID, UUID> jobs = new HashMap<>();
        for (var result : owned) {
            var producer = byAssignment.get(
                    new ReviewItem.Id(result.getReviewId(), result.getPracticeId(), result.getItemId()));
            if (producer == null || !produced(result, producer)) continue;
            var job = producer.getJobId();
            if (job != null) jobs.put(result.getId(), job);
        }
        return jobs;
    }

    private Map<ReviewItem.Id, ReviewItem> assignmentsOf(long workspaceId, List<Result> owned) {
        Map<ReviewItem.Id, ReviewItem> byAssignment = new HashMap<>();
        for (var chunk :
                chunks(owned.stream().map(Result::getReviewId).distinct().toList()))
            assignments
                    .findByWorkspaceIdAndReviewIdIn(workspaceId, chunk)
                    .forEach(m ->
                            byAssignment.put(new ReviewItem.Id(m.getReviewId(), m.getPracticeId(), m.getItemId()), m));
        return byAssignment;
    }

    public boolean authorized(Result result, HistoryCustody facts, SourceUsePurpose purpose) {
        return authorized(result, facts, purpose, SourceReadAudience.PROCESSING, lookups(result.getWorkspaceId()));
    }

    private boolean authorized(
            Result result,
            HistoryCustody facts,
            SourceUsePurpose purpose,
            SourceReadAudience audience,
            Lookups lookups) {
        if (!facts.complete()) return false;
        if (!sourceAuthorized(result, purpose, audience, lookups)) return false;
        return custodyAuthorized(result, facts, purpose, audience, lookups);
    }

    /**
     * The results a person's standing may be read off: their own on the Practice profile, and everyone's as counts on
     * Practices across the workspace. The tenant, source, erasure, suppression, account and custody fences are those
     * of {@link #authorized} and {@link #permitsHistory} for {@link SourceUsePurpose#PRACTICE_FEEDBACK_DELIVERY}.
     *
     * <p>Only the AI choice does not apply. <b>No AI</b> refuses new AI processing of a person's work and new feedback
     * about them; a standing read off reviews that already ran is neither (ADR 0051). Nothing this admits becomes
     * quotable, deliverable or model context: it is not part of {@link ResultProofAccess}, which every AI and
     * delivery reader uses, and those readers keep the full check.
     */
    @Transactional(readOnly = true)
    public Set<UUID> permitsHumanStanding(long workspaceId, Collection<Result> rows) {
        return admitted(
                workspaceId,
                rows,
                SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY,
                SourceReadAudience.HUMAN_RECORDED_STANDING,
                true);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> permitsAll(long workspaceId, Collection<Result> rows, SourceUsePurpose purpose) {
        return admitted(workspaceId, rows, purpose, SourceReadAudience.PROCESSING, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> permitsAllHistory(long workspaceId, Collection<Result> rows, SourceUsePurpose purpose) {
        if (!ResultProofAccess.readsHistory(purpose)) return Set.of();
        return admitted(workspaceId, rows, purpose, SourceReadAudience.PROCESSING, true);
    }

    /**
     * Rows that share a work item, a source, a person, an account or a capture job look each up once, and the rows'
     * assignments and problems load in a bounded number of queries rather than one per row.
     *
     * @param history whether a row recorded before the current result model is read as history
     */
    private Set<UUID> admitted(
            long workspaceId,
            Collection<Result> rows,
            SourceUsePurpose purpose,
            SourceReadAudience audience,
            boolean history) {
        var owned = rows.stream()
                .filter(result -> result.getWorkspaceId() == workspaceId)
                .toList();
        if (owned.isEmpty()) return Set.of();
        var lookups = lookups(workspaceId);
        lookups.prefetch(workspaceId, owned);
        Set<UUID> permitted = new HashSet<>();
        for (var result : owned) {
            var facts = custody(result, lookups);
            boolean admitted = (history && result.getEvidenceVersion() == 0)
                    ? historyAuthorized(result, facts, purpose, audience, lookups)
                    : authorized(result, facts, purpose, audience, lookups);
            if (admitted) permitted.add(result.getId());
        }
        return permitted;
    }

    /** The read that {@link #asOneRead} opened on this thread, if any. */
    private static final ThreadLocal<@Nullable OneRead> ACTIVE_READ = new ThreadLocal<>();

    private record OneRead(ResultSourceAuthorization owner, long workspaceId, Lookups lookups) {}

    /**
     * Runs {@code read} as one read of the workspace: every check inside it shares its lookups, so a work item,
     * source, person, account or capture job that several checks reach is looked up once. A read this instance already
     * opened for the workspace on this thread is joined. The lookups are dropped when the outermost read returns, so no
     * later read sees an answer of this one.
     */
    @Override
    public <T> T asOneRead(long workspaceId, Supplier<T> read) {
        var open = ACTIVE_READ.get();
        if (joins(open, workspaceId)) return read.get();
        ACTIVE_READ.set(new OneRead(this, workspaceId, new Lookups()));
        try {
            return read.get();
        } finally {
            if (open == null) ACTIVE_READ.remove();
            else ACTIVE_READ.set(open);
        }
    }

    private Lookups lookups(long workspaceId) {
        var open = ACTIVE_READ.get();
        return open != null && joins(open, workspaceId) ? open.lookups() : new Lookups();
    }

    // The holder is static, so a read joins only the scope this instance opened for the same workspace.
    private boolean joins(@Nullable OneRead open, long workspaceId) {
        return open != null && equals(open.owner()) && open.workspaceId() == workspaceId;
    }

    // Only processing by or for AI honours the AI choice; every source owner reads the same audience.
    private static boolean refusesAi(SourceReadAudience audience, @Nullable AiChoice choice) {
        return audience == SourceReadAudience.PROCESSING && choice == AiChoice.NO_AI;
    }

    /**
     * The lookups of one authorization read. The same row set asks the same questions many times: every result on a
     * work item checks its source, every copy of a person resolves the same account. Each answer is read once per
     * read and dropped with it, so no later read can see a stale answer.
     */
    private final class Lookups {
        private final Map<Long, Optional<ItemRef>> items = new HashMap<>();
        private final Map<SourceKey, Boolean> sources = new HashMap<>();
        // Suppression does not depend on the purpose or the audience, so every source check of an item shares it.
        private final Map<Long, Boolean> suppressedSources = new HashMap<>();
        private final Map<Long, Optional<AccountDirectory.AccountView>> accounts = new HashMap<>();
        private final Map<Long, Boolean> suppressedAccounts = new HashMap<>();
        private final Map<UUID, Boolean> suppressedJobs = new HashMap<>();
        private final Map<PersonKey, Boolean> suppressedPeople = new HashMap<>();
        private final Map<PersonKey, Optional<Optional<Long>>> owners = new HashMap<>();
        private final Map<UUID, HistoryCustody> receipts = new HashMap<>();
        private @Nullable List<PersonIdentityResolver.Provider> providers;
        // Filled by prefetch for a read of many rows; a row it has not seen reads its own, as a single check does.
        private final Set<UUID> prefetchedReviews = new HashSet<>();
        private final Map<ReviewItem.Id, ReviewItem> producers = new HashMap<>();
        private final Set<UUID> prefetchedResults = new HashSet<>();
        private final Map<UUID, List<ReviewItem>> retained = new HashMap<>();
        private final Map<UUID, List<Problem>> children = new HashMap<>();

        /**
         * Loads the assignments and problems of the rows in {@code owned} this read has not seen, in chunked collection
         * queries, so the checks below read them from memory. The rows' producers are among their reviews'
         * assignments; retained custody includes assignments of other reviews that reused a row.
         */
        void prefetch(long workspaceId, List<Result> owned) {
            var reviews = owned.stream()
                    .map(Result::getReviewId)
                    .distinct()
                    .filter(prefetchedReviews::add)
                    .toList();
            for (var chunk : chunks(reviews))
                assignments
                        .findByWorkspaceIdAndReviewIdIn(workspaceId, chunk)
                        .forEach(m ->
                                producers.put(new ReviewItem.Id(m.getReviewId(), m.getPracticeId(), m.getItemId()), m));
            var ids = owned.stream()
                    .map(Result::getId)
                    .distinct()
                    .filter(prefetchedResults::add)
                    .toList();
            for (var chunk : chunks(ids)) {
                for (var m : assignments.findByWorkspaceIdAndResultIdInOrderByReviewIdAscPracticeIdAscItemIdAsc(
                        workspaceId, chunk)) {
                    var resultId = m.getResultId();
                    if (resultId != null)
                        retained.computeIfAbsent(resultId, ignored -> new ArrayList<>())
                                .add(m);
                }
                // Every row may reach its problems: a processing read checks the source kinds they cite at any
                // evidence version.
                problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(workspaceId, chunk)
                        .forEach(
                                problem -> children.computeIfAbsent(problem.getResultId(), ignored -> new ArrayList<>())
                                        .add(problem));
            }
        }

        Optional<ReviewItem> producer(Result result) {
            if (!prefetchedReviews.contains(result.getReviewId()))
                return assignments.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        result.getWorkspaceId(), result.getReviewId(), result.getPracticeId(), result.getItemId());
            return Optional.ofNullable(
                    producers.get(new ReviewItem.Id(result.getReviewId(), result.getPracticeId(), result.getItemId())));
        }

        List<ReviewItem> retained(Result result) {
            if (!prefetchedResults.contains(result.getId()))
                return assignments.findByWorkspaceIdAndResultIdOrderByReviewIdAscPracticeIdAscItemIdAsc(
                        result.getWorkspaceId(), result.getId());
            return retained.getOrDefault(result.getId(), List.of());
        }

        List<Problem> problems(Result result) {
            if (!prefetchedResults.contains(result.getId()))
                return problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(result.getWorkspaceId(), result.getId());
            return children.getOrDefault(result.getId(), List.of());
        }

        Optional<ItemRef> item(long workspaceId, long itemId) {
            return items.computeIfAbsent(itemId, id -> ResultSourceAuthorization.this.items.find(workspaceId, id));
        }

        Optional<AccountDirectory.AccountView> account(long accountId) {
            return accounts.computeIfAbsent(accountId, ResultSourceAuthorization.this.accounts::find);
        }

        boolean sourceSuppressed(ItemRef item) {
            var source = item.sourceId();
            return source != null
                    && suppressedSources.computeIfAbsent(
                            item.itemId(),
                            ignored -> suppression.isSourceSuppressed(
                                    item.workspaceId(), item.kind().value(), source));
        }

        boolean accountSuppressed(long accountId) {
            return suppressedAccounts.computeIfAbsent(accountId, suppression::isAccountSuppressed);
        }

        boolean jobSuppressed(UUID job) {
            return suppressedJobs.computeIfAbsent(job, suppression::isReviewJobSuppressed);
        }

        boolean personSuppressed(PersonKey person) {
            return suppressedPeople.computeIfAbsent(
                    person, key -> suppression.isSuppressed(key.providerId(), key.subject(), key.teamId()));
        }

        /** The person's account, none for an unlinked person, or empty when the resolver cannot attribute them. */
        Optional<Optional<Long>> owner(PersonKey person) {
            return owners.computeIfAbsent(person, key -> {
                try {
                    return Optional.of(
                            people.accountFor(new PersonIdentity(key.providerId(), key.subject(), key.teamId())));
                } catch (ResponseStatusException unattributable) {
                    return Optional.empty();
                }
            });
        }

        List<PersonIdentityResolver.Provider> providers() {
            var known = providers;
            if (known == null) {
                known = people.providers();
                providers = known;
            }
            return known;
        }
    }

    /** A bounded number of ids per collection query, well below the driver's bind-parameter limit. */
    private static final int PREFETCH_CHUNK = 1000;

    private static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < values.size(); from += PREFETCH_CHUNK)
            chunks.add(values.subList(from, Math.min(values.size(), from + PREFETCH_CHUNK)));
        return chunks;
    }

    // The audience is part of the answer, so a human read never reuses a processing answer or the reverse.
    private record SourceKey(long itemId, SourceUsePurpose purpose, SourceReadAudience audience) {}

    private record PersonKey(
            long providerId, String subject, @Nullable String teamId) {}

    private boolean custodyAuthorized(
            Result result,
            HistoryCustody facts,
            SourceUsePurpose purpose,
            SourceReadAudience audience,
            Lookups lookups) {
        if (facts.refused()) return false;
        if (facts.sourceJobIds().stream().anyMatch(lookups::jobSuppressed)) return false;
        for (var copy : facts.copiedIdentities()) {
            var providers = lookups.providers().stream()
                    .filter(p -> p.type().equals(copy.providerType())
                            && p.serverUrl().equals(copy.providerOrigin()))
                    .toList();
            if (providers.size() != 1) return false;
            var person = new PersonKey(providers.getFirst().id(), copy.subject(), copy.teamId());
            if (lookups.personSuppressed(person)) return false;
            // Capture admits a contributor without an account, so only a linked owner adds controls.
            // The exact closure includes disabled links, which the enabled-link query cannot see.
            var owner = lookups.owner(person);
            if (owner.isEmpty()) return false;
            var accountId = owner.orElseThrow();
            if (accountId.isPresent()) {
                var account = lookups.account(accountId.orElseThrow());
                if (account.isEmpty()
                        || (account.orElseThrow().status() != Account.Status.ACTIVE
                                && account.orElseThrow().status() != Account.Status.UNCLAIMED)
                        || lookups.accountSuppressed(account.orElseThrow().id())
                        || refusesAi(audience, account.orElseThrow().aiChoice())) return false;
            }
        }
        for (var repositoryId : facts.repositoryIds()) {
            var repositories = itemRows.activeRepositoryIds(result.getWorkspaceId(), repositoryId);
            if (repositories.size() != 1
                    || !source(
                            items.require(result.getWorkspaceId(), repositories.getFirst()),
                            purpose,
                            audience,
                            lookups)) return false;
        }
        return true;
    }

    /** Migrated words are readable history, never a certificate of complete copy custody. */
    @Transactional(readOnly = true)
    public boolean permitsHistory(long workspaceId, UUID resultId, SourceUsePurpose purpose) {
        if (!ResultProofAccess.readsHistory(purpose)) return false;
        var lookups = lookups(workspaceId);
        return results.findByIdAndWorkspaceId(resultId, workspaceId)
                .map(result -> {
                    var facts = custody(result, lookups);
                    if (result.getEvidenceVersion() != 0)
                        return authorized(result, facts, purpose, SourceReadAudience.PROCESSING, lookups);
                    return historyAuthorized(result, facts, purpose, SourceReadAudience.PROCESSING, lookups);
                })
                .orElse(false);
    }

    private boolean historyAuthorized(
            Result result,
            HistoryCustody facts,
            SourceUsePurpose purpose,
            SourceReadAudience audience,
            Lookups lookups) {
        if (lookups.jobSuppressed(result.getReviewId())) return false;
        var developerId = result.getDeveloperId();
        if (developerId != null) {
            var developer = lookups.account(developerId);
            if (developer.isEmpty()
                    || lookups.accountSuppressed(developerId)
                    || refusesAi(audience, developer.orElseThrow().aiChoice())
                    || (developer.orElseThrow().status() != Account.Status.ACTIVE
                            && developer.orElseThrow().status() != Account.Status.UNCLAIMED)) return false;
        }
        return visibleSource(result, purpose, audience, lookups)
                && custodyAuthorized(result, facts, purpose, audience, lookups);
    }

    private boolean sourceAuthorized(
            Result result, SourceUsePurpose purpose, SourceReadAudience audience, Lookups lookups) {
        if (!visibleSource(result, purpose, audience, lookups)) return false;
        var citations = ResultCitations.all(result, lookups.problems(result));
        try {
            for (var citation : citations)
                if (!citation.path("sourceKind").isString()
                        || !sources.isSourceUsePermitted(
                                new SourceKind(citation.path("sourceKind").asString()), purpose)) return false;
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
        return true;
    }

    private boolean visibleSource(
            Result result, SourceUsePurpose purpose, SourceReadAudience audience, Lookups lookups) {
        var item = lookups.item(result.getWorkspaceId(), result.getItemId());
        return item.isPresent() && source(item.orElseThrow(), purpose, audience, lookups);
    }

    /**
     * Whether {@code purpose} may use the work item's source, by the source fence of {@link #permitsAll}: for work
     * that has no result yet, such as a review in flight, whose custody and citations nothing has recorded.
     */
    @Transactional(readOnly = true)
    public boolean permitsItem(long workspaceId, long itemId, SourceUsePurpose purpose) {
        var lookups = lookups(workspaceId);
        var item = lookups.item(workspaceId, itemId);
        return item.isPresent() && source(item.orElseThrow(), purpose, SourceReadAudience.PROCESSING, lookups);
    }

    @Transactional(readOnly = true)
    public boolean permits(long workspaceId, UUID resultId, SourceUsePurpose purpose) {
        var lookups = lookups(workspaceId);
        return results.findByIdAndWorkspaceId(resultId, workspaceId)
                .map(result ->
                        authorized(result, custody(result, lookups), purpose, SourceReadAudience.PROCESSING, lookups))
                .orElse(false);
    }

    private boolean source(ItemRef item, SourceUsePurpose purpose, SourceReadAudience audience, Lookups lookups) {
        var key = new SourceKey(item.itemId(), purpose, audience);
        var known = lookups.sources.get(key);
        if (known != null) return known;
        boolean permitted = sourcePermits(item, purpose, audience, lookups);
        lookups.sources.put(key, permitted);
        return permitted;
    }

    private boolean sourcePermits(
            ItemRef item, SourceUsePurpose purpose, SourceReadAudience audience, Lookups lookups) {
        if (lookups.sourceSuppressed(item)) return false;
        if (item.parentId() != null) {
            var parent = lookups.item(item.workspaceId(), item.parentId());
            if (parent.isEmpty() || !source(parent.orElseThrow(), purpose, audience, lookups)) return false;
        }
        var owners = access.stream()
                .filter(owner -> owner.kinds().contains(item.kind()))
                .toList();
        if (owners.size() != 1) return false;
        // Processing keeps the one check every AI reader uses; only the human read asks the typed one.
        return audience == SourceReadAudience.PROCESSING
                ? owners.getFirst().permits(item, purpose)
                : owners.getFirst().permits(item, purpose, audience);
    }
}
