package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser.RepositoryOwnerAndName;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Deletion reconciliation for issues and pull requests — the half of "sync" that upserts cannot do.
 *
 * <p>Every other GitHub sync path is upsert-only, so an issue or pull request removed upstream
 * survives locally forever: it is caught only by a webhook, and this deployment's webhooks are not
 * redeliverable (ADR-0008), so a single missed delivery leaves a phantom row that permanently
 * inflates the per-repository counts the admin UI reports. For pull requests it is worse than
 * unreliable — GitHub has no {@code pull_request.deleted} action at all, so a pull request that
 * disappears (repository transfer, staff removal) has no event to miss and no code path to catch it.
 * This sweep is the only mechanism that closes either gap.
 *
 * <p>The method is a set difference. For each repository it enumerates the complete upstream number
 * set with a number-only GraphQL query ({@code GetRepositoryIssueNumbers} /
 * {@code GetRepositoryPullRequestNumbers}, ~1 rate-limit point per 100 items, versus ~59 for one
 * page of the content query), diffs it against the live local numbers, and tombstones the remainder.
 * It follows the {@code removeStale*} precedent already established for labels, milestones and
 * collaborators rather than inventing a second shape for the same idea.
 *
 * <h2>Fail-closed</h2>
 *
 * A sweep that deletes on bad data is far worse than the phantom row it exists to remove, because
 * the phantom is visible and a wrong deletion is not. So deletion is authorized by
 * <em>provable completeness</em> and nothing else: {@link UpstreamListing#complete()} is true only
 * when pagination ran to {@code hasNextPage == false} with no rate-limit abort, no GraphQL error, no
 * thrown exception, no page-cap truncation, no cancellation, and a node count that agrees exactly
 * with the server's own {@code totalCount}. Any doubt — including any doubt this code did not
 * anticipate, since the flag starts {@code false} and is set only on the one clean exit — skips the
 * repository entirely and deletes nothing. A partial listing is never merged with a previous one.
 *
 * <h3>An empty listing is not evidence of an empty repository</h3>
 *
 * The completeness proof above is a proof about <em>pagination</em>, and pagination is trivially
 * "complete" for a listing that returned nothing: {@code 0 == 0} satisfies the {@code totalCount}
 * cross-check exactly as well as {@code 4000 == 4000} does. That is the one shape where the strongest
 * check in this class has no discriminating power at all, and it authorizes tombstoning the whole
 * repository. Two further guards close it, both fail-closed:
 *
 * <ol>
 *   <li><b>{@code featureDisabled}</b> — a repository whose Issues feature is switched off still answers
 *       the {@code issues} connection, with zero nodes and {@code totalCount: 0}. That is indexing
 *       state, not deletion: turning Issues back on returns every issue. The sweep selects
 *       {@code repository.hasIssuesEnabled} alongside the connection and refuses the listing when it is
 *       explicitly {@code false}. Pull requests have no equivalent toggle in GitHub's schema, so only
 *       the issue half carries a flag.
 *   <li><b>{@code emptyUpstreamWithLiveMirror}</b> — independently of any flag, a provably-complete but
 *       <em>empty</em> upstream listing is refused whenever the mirror still holds live rows of that
 *       class. The two readings of that combination are "every issue in the repository was deleted since
 *       the last sync" and "something about this listing is wrong (a narrowed token, an unannounced
 *       feature toggle, a vendor bug)". The first is rare and self-heals on the next sync; the second is
 *       not, and being wrong costs the whole repository. So the sweep declines and reports degraded
 *       rather than deleting on the least trustworthy evidence it can receive.
 * </ol>
 *
 * <p>The cost of both guards is bounded and known: a repository whose issues really were all deleted
 * keeps its phantom rows until a human notices. That is the deliberately cheaper failure.
 *
 * <p>Tombstone rather than row deletion: see {@code Issue#deletedAt}. Because
 * {@code IssueRepository.upsertCore} clears the tombstone, a repository wrongly swept heals itself on
 * the next ordinary sync — which is the property that makes acting on inference tolerable at all.
 */
@Service
public class GitHubDeletionSweepService {

    private static final Logger log = LoggerFactory.getLogger(GitHubDeletionSweepService.class);

    private static final String ISSUE_QUERY_DOCUMENT = "GetRepositoryIssueNumbers";
    private static final String PULL_REQUEST_QUERY_DOCUMENT = "GetRepositoryPullRequestNumbers";

    /**
     * The connection maximum. Unlike the content queries — which pay ~59 points per page and so run
     * at 25 — a number-only page costs ~1 point regardless of size, so the largest legal page is
     * strictly cheapest: it minimises both round trips and total points for a given repository.
     */
    private static final int SWEEP_PAGE_SIZE = 100;

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final IssueRepository issueRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private final GitHubSyncProperties syncProperties;

    public GitHubDeletionSweepService(
            IssueRepository issueRepository,
            RepositoryRepository repositoryRepository,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            GitHubSyncProperties syncProperties) {
        this.issueRepository = issueRepository;
        this.repositoryRepository = repositoryRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.graphQlSyncHelper = graphQlSyncHelper;
        this.syncProperties = syncProperties;
    }

    /** Which of the two entity classes a listing/diff pass is operating on. */
    private enum SweptEntity {
        ISSUE("issues", ISSUE_QUERY_DOCUMENT, "repository.issues", "repository.hasIssuesEnabled"),
        // GitHub has no repository-level toggle for pull requests — they cannot be switched off — so
        // there is no flag to interrogate here. The emptiness guard still covers this class.
        PULL_REQUEST("pull requests", PULL_REQUEST_QUERY_DOCUMENT, "repository.pullRequests", null);

        private final String plural;
        private final String document;
        private final String field;

        /**
         * Path of the repository-level flag that says whether this entity class is switched on upstream,
         * or {@code null} when the vendor has no such toggle. A disabled feature answers the connection
         * with zero nodes rather than an error, which would otherwise read as a complete empty listing.
         */
        @Nullable
        private final String featureFlagPath;

        SweptEntity(String plural, String document, String field, @Nullable String featureFlagPath) {
            this.plural = plural;
            this.document = document;
            this.field = field;
            this.featureFlagPath = featureFlagPath;
        }
    }

    /**
     * The upstream half of the set difference.
     *
     * <p>The two members are inseparable on purpose. Every abort path in this class still has a
     * populated accumulator, so a bare {@code Set} cannot distinguish "this repository has 12 issues"
     * from "we read 12 of 4000 and then hit the rate limit" — and acting on the second reading
     * deletes 3988 live issues. Pairing the set with {@code complete} makes that mistake impossible
     * to make silently: callers must read the flag to reach the numbers.
     *
     * @param numbers  the upstream numbers observed; meaningful for deletion ONLY when {@code complete}
     * @param complete whether the listing is provably the entire upstream set
     * @param reason   why the listing is incomplete; {@code null} when {@code complete}
     */
    record UpstreamListing(
            Set<Integer> numbers,
            boolean complete,
            @Nullable String reason) {
        static UpstreamListing complete(Set<Integer> numbers) {
            return new UpstreamListing(numbers, true, null);
        }

        static UpstreamListing incomplete(String reason) {
            return new UpstreamListing(Set.of(), false, reason);
        }
    }

    /**
     * Outcome of sweeping one repository.
     *
     * @param issuesTombstoned       issues newly tombstoned
     * @param pullRequestsTombstoned pull requests newly tombstoned
     * @param skipped                true when at least one entity class could not be swept because its
     *                               upstream listing was incomplete — the job should report warnings
     */
    public record SweepOutcome(int issuesTombstoned, int pullRequestsTombstoned, boolean skipped) {
        static final SweepOutcome NOTHING = new SweepOutcome(0, 0, false);

        public int total() {
            return issuesTombstoned + pullRequestsTombstoned;
        }
    }

    /**
     * Sweeps every monitored repository of a scope, reporting {@link SyncPhase#SWEEP} progress and
     * honoring cancellation between repositories.
     *
     * <p>One repository's failure never stops the pass: an unsweepable repository is skipped and the
     * rest still get reconciled, because the alternative — one rate-limited repository suppressing
     * the sweep for the whole connection — is how drift becomes permanent.
     *
     * @param scopeId the workspace/scope to sweep
     * @param handle  live job handle for progress + cancellation; {@code null} outside a recorded job
     */
    public SweepOutcome sweepScope(Long scopeId, @Nullable SyncExecutionHandle handle) {
        List<Repository> repositories = repositoryRepository.findAllByWorkspaceMonitors(scopeId);
        if (repositories.isEmpty()) {
            return SweepOutcome.NOTHING;
        }

        int issues = 0;
        int pullRequests = 0;
        boolean skipped = false;
        int done = 0;
        int total = repositories.size();

        for (Repository repository : repositories) {
            if (isCancelled(handle)) {
                log.info(
                        "Deletion sweep cancelled between repositories: scopeId={}, reposSwept={}, reposRemaining={}",
                        scopeId,
                        done,
                        total - done);
                break;
            }
            report(
                    handle,
                    done,
                    total,
                    "Checking " + repository.getNameWithOwner() + " for deleted items",
                    repository.getNameWithOwner());

            SweepOutcome outcome;
            try {
                outcome = sweepRepository(scopeId, repository, handle);
            } catch (RuntimeException e) {
                // sweepRepository is not expected to throw, but a sweep failure must never abort the
                // reconciliation job — this is drift repair, not the sync itself.
                log.warn(
                        "Deletion sweep failed for repository, continuing: repoName={}, scopeId={}, message={}",
                        sanitizeForLog(repository.getNameWithOwner()),
                        scopeId,
                        e.toString());
                skipped = true;
                done++;
                continue;
            }

            issues += outcome.issuesTombstoned();
            pullRequests += outcome.pullRequestsTombstoned();
            skipped = skipped || outcome.skipped();
            done++;
        }

        SweepOutcome scopeOutcome = new SweepOutcome(issues, pullRequests, skipped);
        report(handle, done, total, sweepSummary(scopeOutcome), null);
        log.info(
                "Deletion sweep finished: scopeId={}, reposSwept={}, issuesTombstoned={}, pullRequestsTombstoned={}, degraded={}",
                scopeId,
                done,
                issues,
                pullRequests,
                skipped);
        return scopeOutcome;
    }

    /** The one sentence the admin UI renders for the sweep's terminal report. */
    static String sweepSummary(SweepOutcome outcome) {
        if (outcome.total() == 0) {
            return outcome.skipped()
                    ? "Checked for deleted items: some repositories could not be verified"
                    : "Checked for deleted items: none found";
        }
        return "Retired " + outcome.total() + " item" + (outcome.total() == 1 ? "" : "s") + " deleted upstream";
    }

    private static void report(
            @Nullable SyncExecutionHandle handle, int done, int total, String step, @Nullable String repositoryName) {
        if (handle != null) {
            handle.progress(done, total, SyncProgress.ofResource(SyncPhase.SWEEP, step, repositoryName, done, total));
        }
    }

    /**
     * Sweeps one repository: tombstones every local issue and pull request that upstream no longer
     * has. Deletes nothing unless the corresponding upstream listing is provably complete.
     *
     * <p>The two entity classes are swept independently — an incomplete issue listing does not
     * suppress a provably complete pull-request sweep, and vice versa, because they are separate
     * connections with separate failure modes.
     *
     * @param scopeId    the workspace/scope owning the GitHub credentials
     * @param repository the local repository to sweep
     * @param handle     the live job handle for progress and cooperative cancellation; may be
     *                   {@code null} when the sweep runs outside a recorded job
     */
    public SweepOutcome sweepRepository(Long scopeId, Repository repository, @Nullable SyncExecutionHandle handle) {
        String nameWithOwner = repository.getNameWithOwner();
        String safeNameWithOwner = Objects.requireNonNullElse(sanitizeForLog(nameWithOwner), "null");

        Optional<RepositoryOwnerAndName> parsed = GitHubRepositoryNameParser.parse(nameWithOwner);
        if (parsed.isEmpty()) {
            log.warn("Skipped deletion sweep: reason=unparseableRepositoryName, repoName={}", safeNameWithOwner);
            return SweepOutcome.NOTHING;
        }

        if (isCancelled(handle)) {
            return SweepOutcome.NOTHING;
        }

        int issuesTombstoned = 0;
        int pullRequestsTombstoned = 0;
        boolean skipped = false;

        for (SweptEntity entity : SweptEntity.values()) {
            if (isCancelled(handle)) {
                // Do not mark this a "skip" warning: an operator-requested cancel is not a degraded
                // sweep, and the job already finalizes CANCELLED.
                break;
            }
            // Read the local side BEFORE the listing starts, and diff only this snapshot. The
            // listing takes many round trips; anything a webhook inserts while it runs is local but
            // legitimately absent from the pages already fetched, and diffing a post-listing read
            // would tombstone it. Snapshotting first makes the candidate set exactly "rows that
            // existed before we started looking", which is the set the completeness guarantee
            // actually covers. Items that arrive during the window are simply left for the next
            // sweep, by which point they are in the listing.
            List<Integer> localBeforeListing = localLiveNumbers(repository.getId(), entity);

            UpstreamListing listing = listUpstreamNumbers(scopeId, parsed.get(), entity, safeNameWithOwner, handle);
            // The totalCount cross-check inside the listing cannot see this one: an empty listing
            // reconciles with a totalCount of zero perfectly, so "complete" is earned without proving
            // anything. Wiping a whole repository on that evidence is not a trade worth making, so demote
            // it to the same incomplete/degraded path every other doubt takes. `localBeforeListing` is
            // read through a TYPE()-discriminated query, so an empty issue mirror is not masked by live
            // pull requests sharing the single `issue` table (and vice versa).
            if (listing.complete() && listing.numbers().isEmpty() && !localBeforeListing.isEmpty()) {
                listing = UpstreamListing.incomplete("emptyUpstreamWithLiveMirror");
            }
            if (!listing.complete()) {
                skipped = true;
                log.warn(
                        "Skipped deletion sweep, deleted nothing: reason={}, entity={}, repoName={}, scopeId={}",
                        listing.reason(),
                        entity.plural,
                        safeNameWithOwner,
                        scopeId);
                continue;
            }
            int tombstoned = tombstoneMissing(
                    repository.getId(), entity, localBeforeListing, listing.numbers(), safeNameWithOwner);
            if (entity == SweptEntity.ISSUE) {
                issuesTombstoned = tombstoned;
            } else {
                pullRequestsTombstoned = tombstoned;
            }
        }

        return new SweepOutcome(issuesTombstoned, pullRequestsTombstoned, skipped);
    }

    /** The live local numbers for one entity class of one repository. */
    private List<Integer> localLiveNumbers(Long repositoryId, SweptEntity entity) {
        return entity == SweptEntity.ISSUE
                ? issueRepository.findLiveIssueNumbersByRepositoryId(repositoryId)
                : issueRepository.findLivePullRequestNumbersByRepositoryId(repositoryId);
    }

    /**
     * Diffs a <em>pre-listing</em> snapshot of the local live numbers against a <em>provably
     * complete</em> upstream set and tombstones the difference. Never call this with a listing that
     * is not complete, and never with a {@code local} snapshot read after the listing began — see
     * the call site for why both halves matter.
     *
     * @param local the local live numbers as of before the upstream listing started
     */
    private int tombstoneMissing(
            Long repositoryId,
            SweptEntity entity,
            List<Integer> local,
            Set<Integer> upstream,
            String safeNameWithOwner) {
        List<Integer> missing =
                local.stream().filter(number -> !upstream.contains(number)).toList();
        if (missing.isEmpty()) {
            log.debug(
                    "Deletion sweep found no drift: entity={}, repoName={}, localCount={}, upstreamCount={}",
                    entity.plural,
                    safeNameWithOwner,
                    local.size(),
                    upstream.size());
            return 0;
        }

        // Type-discriminated write: issues and pull requests share the single-table `issue` table, so a
        // type-blind UPDATE keyed on (repository_id, number) would tombstone the wrong discriminator when
        // the two share a number. GitHub's numbers are shared across the namespace, but the write must
        // still target the class it just proved complete — never the other one. See IssueRepository.
        int tombstoned = entity == SweptEntity.ISSUE
                ? issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(repositoryId, missing, Instant.now())
                : issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(repositoryId, missing, Instant.now());
        log.info(
                "Deletion sweep tombstoned upstream-deleted items: entity={}, repoName={}, repoId={}, tombstoned={}, localCount={}, upstreamCount={}",
                entity.plural,
                safeNameWithOwner,
                repositoryId,
                tombstoned,
                local.size(),
                upstream.size());
        return tombstoned;
    }

    /**
     * Enumerates the complete upstream number set for one entity class of one repository.
     *
     * <p>Structured so that {@code complete} can only be reported at the single clean exit: the loop
     * returns {@link UpstreamListing#incomplete} from every other path, so a future edit that adds a
     * new failure mode and forgets about it fails safe by default rather than authorizing a deletion.
     */
    private UpstreamListing listUpstreamNumbers(
            Long scopeId,
            RepositoryOwnerAndName ownerAndName,
            SweptEntity entity,
            String safeNameWithOwner,
            @Nullable SyncExecutionHandle handle) {
        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        Duration timeout = syncProperties.graphqlTimeout();

        Set<Integer> numbers = new LinkedHashSet<>();
        String cursor = null;
        int retryAttempt = 0;
        int pageCount = 0;
        int reportedTotalCount = -1;

        while (true) {
            if (isCancelled(handle)) {
                return UpstreamListing.incomplete("cancelled");
            }
            if (pageCount >= MAX_PAGINATION_PAGES) {
                // The cap is a runaway guard, not a stopping condition: the set beyond it is unread,
                // so everything past it would look deleted.
                return UpstreamListing.incomplete("paginationCapReached");
            }
            pageCount++;

            ClientGraphQlResponse response;
            String currentCursor = cursor;
            try {
                response = Mono.defer(() -> client.documentName(entity.document)
                                .variable("owner", ownerAndName.owner())
                                .variable("name", ownerAndName.name())
                                .variable("first", SWEEP_PAGE_SIZE)
                                .variable("after", currentCursor)
                                .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError))
                        .block(timeout);
            } catch (RuntimeException e) {
                // Includes the transport retries being exhausted and the block() timeout. Whatever the
                // cause, the set is short — refuse it.
                log.warn(
                        "Deletion sweep listing failed: entity={}, repoName={}, page={}, message={}",
                        entity.plural,
                        safeNameWithOwner,
                        pageCount,
                        e.toString());
                return UpstreamListing.incomplete("listingThrew");
            }

            if (response == null || !response.isValid()) {
                var classification = graphQlSyncHelper.classifyGraphQlErrors(response);
                if (classification != null
                        && graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "deletion sweep",
                                "repoName",
                                safeNameWithOwner,
                                log))) {
                    // The coordinator already slept; retry the SAME cursor so no page is skipped.
                    retryAttempt++;
                    pageCount--;
                    continue;
                }
                return UpstreamListing.incomplete("graphQlError");
            }

            graphQlClientProvider.trackRateLimit(scopeId, response);

            // Interrogate the feature flag before believing a single node. A repository with Issues
            // switched off still answers the connection — with nothing in it — which is indistinguishable
            // from "this repository has no issues" and would tombstone the entire mirrored set. Only an
            // explicit `false` aborts: a null (field unselected, restricted, or undecodable) leaves the
            // decision to the emptiness guard rather than blocking every sweep.
            if (entity.featureFlagPath != null
                    && Boolean.FALSE.equals(readBooleanField(response, entity.featureFlagPath))) {
                log.warn(
                        "Deletion sweep listing refused, feature disabled upstream: entity={}, repoName={}, flag={}",
                        entity.plural,
                        safeNameWithOwner,
                        entity.featureFlagPath);
                return UpstreamListing.incomplete("featureDisabled");
            }

            GHPageInfo pageInfo;
            List<Integer> pageNumbers;
            int pageTotalCount;
            try {
                if (entity == SweptEntity.ISSUE) {
                    GHIssueConnection connection = response.field(entity.field).toEntity(GHIssueConnection.class);
                    if (connection == null) {
                        return UpstreamListing.incomplete("nullConnection");
                    }
                    pageInfo = connection.getPageInfo();
                    pageTotalCount = connection.getTotalCount();
                    pageNumbers = connection.getNodes() == null
                            ? List.of()
                            : connection.getNodes().stream()
                                    .filter(node -> node != null)
                                    .map(node -> node.getNumber())
                                    .toList();
                } else {
                    GHPullRequestConnection connection =
                            response.field(entity.field).toEntity(GHPullRequestConnection.class);
                    if (connection == null) {
                        return UpstreamListing.incomplete("nullConnection");
                    }
                    pageInfo = connection.getPageInfo();
                    pageTotalCount = connection.getTotalCount();
                    pageNumbers = connection.getNodes() == null
                            ? List.of()
                            : connection.getNodes().stream()
                                    .filter(node -> node != null)
                                    .map(node -> node.getNumber())
                                    .toList();
                }
            } catch (RuntimeException e) {
                // Log the root cause, not just e.toString(): Spring wraps every decode failure in a
                // GraphQlClientException whose message is a bare "Cannot read field '<path>'" and
                // whose cause carries the only text that identifies the actual fault. Reporting the
                // wrapper alone would make a client-side Jackson error indistinguishable from an
                // upstream GraphQL error behind the fail-closed skip.
                log.warn(
                        "Deletion sweep decode failed: entity={}, repoName={}, page={}, message={}, cause={}",
                        entity.plural,
                        safeNameWithOwner,
                        pageCount,
                        e.toString(),
                        NestedExceptionUtils.getMostSpecificCause(e).toString());
                return UpstreamListing.incomplete("decodeFailed");
            }

            numbers.addAll(pageNumbers);
            reportedTotalCount = pageTotalCount;
            retryAttempt = 0;

            boolean hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
            if (!hasNextPage) {
                break;
            }
            String endCursor = pageInfo.getEndCursor();
            if (endCursor == null) {
                // hasNextPage without a cursor: there is more and we cannot ask for it.
                return UpstreamListing.incomplete("missingCursor");
            }
            cursor = endCursor;
        }

        // The listing claims to be whole; make it prove it against the server's own count. This is the
        // check that catches a silent truncation, which is the one failure that looks exactly like a
        // small repository and would otherwise authorize deleting everything past the cut.
        if (reportedTotalCount >= 0 && numbers.size() != reportedTotalCount) {
            log.warn(
                    "Deletion sweep listing did not reconcile with upstream totalCount, deleting nothing: entity={}, repoName={}, received={}, totalCount={}",
                    entity.plural,
                    safeNameWithOwner,
                    numbers.size(),
                    reportedTotalCount);
            return UpstreamListing.incomplete("totalCountMismatch");
        }

        return UpstreamListing.complete(numbers);
    }

    /**
     * Reads a boolean scalar out of a response without letting a missing or undecodable field become an
     * exception. Returns {@code null} for "not answered" so callers can distinguish it from {@code false}
     * — the distinction matters, because only an explicit {@code false} is evidence of anything.
     */
    @Nullable
    private static Boolean readBooleanField(ClientGraphQlResponse response, String path) {
        try {
            ClientResponseField field = response.field(path);
            Object value = field == null ? null : field.getValue();
            return value instanceof Boolean bool ? bool : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isCancelled(@Nullable SyncExecutionHandle handle) {
        return handle != null && handle.isCancellationRequested();
    }
}
