package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletion reconciliation for GitLab issues and merge requests — the half of "sync" that upserts
 * cannot do. The GitLab counterpart of {@code GitHubDeletionSweepService}, reusing the same shared,
 * integration-agnostic tombstone infrastructure ({@code Issue#deletedAt},
 * {@code IssueRepository#tombstone{Issues,PullRequests}ByRepositoryIdAndNumbers}).
 *
 * <p>Every GitLab sync path is upsert-only, so an issue or merge request removed upstream survives
 * locally forever. It is worse than for GitHub: GitLab emits <em>no issue- or merge-request-deletion
 * webhook at all</em>, so there is not even a missed event to blame — a deleted issue or MR simply has
 * no code path that would ever notice it, and its phantom row permanently inflates the per-project
 * counts the admin UI reports and keeps feeding practice-review and mentor context as if it were
 * live. This sweep is the only mechanism that closes the gap.
 *
 * <p>The method is a set difference. For each project it enumerates the complete upstream IID set with
 * a number-only GraphQL query ({@code GetProjectIssueNumbers} / {@code GetProjectMergeRequestNumbers},
 * a fraction of the complexity of the content queries), diffs it against the live local numbers
 * (GitLab stores each issue/MR under the repository keyed by its per-project {@code iid}, which is the
 * {@code Issue.number} the mirror persists), and tombstones the remainder. It follows the
 * {@code removeStale*} precedent already established for repositories, labels and milestones rather
 * than inventing a second shape for the same idea.
 *
 * <h2>Fail-closed</h2>
 *
 * A sweep that deletes on bad data is far worse than the phantom row it exists to remove, because the
 * phantom is visible and self-correcting and a wrong deletion is not. So deletion is authorized by
 * <em>provable completeness</em> and nothing else: {@link UpstreamListing#complete()} is true only
 * when pagination ran to {@code hasNextPage == false} with no rate-limit abort, no GraphQL error, no
 * circuit-breaker rejection, no thrown exception, no unparseable IID, no page-cap truncation, no
 * cancellation, a {@code count} that was actually observed, and an IID count that agrees exactly with
 * that {@code count}. Any doubt — including a doubt this code did not anticipate, since the flag
 * starts {@code false} and is set only on the one clean exit — skips the entity class entirely and
 * deletes nothing. A partial listing is never merged with a previous one.
 *
 * <h3>An empty listing is not evidence of an empty project</h3>
 *
 * The completeness proof above is a proof about <em>pagination</em>, and pagination is trivially
 * "complete" for a listing that returned nothing: {@code 0 == 0} satisfies the count cross-check
 * exactly as well as {@code 4000 == 4000} does. That is the one shape where the strongest check in
 * this class has no discriminating power at all, and it authorizes tombstoning the entire project.
 * Two further guards close it, both fail-closed:
 *
 * <ol>
 *   <li><b>{@code featureDisabled}</b> — GitLab's {@code IssuesFinder} silently scopes out projects
 *       whose Issues feature is switched off ({@code .merge(Project.with_issues_enabled)}), and the
 *       merge-request finder behaves the same way. Turning the feature off therefore does not raise an
 *       error; it renders as a perfectly well-formed, perfectly "complete" listing of zero items.
 *       The sweep now selects {@code project.issuesEnabled} / {@code project.mergeRequestsEnabled}
 *       alongside the connection and refuses the listing whenever the relevant flag is explicitly
 *       {@code false}. A toggled-off feature hides upstream data; it does not delete it.
 *   <li><b>{@code emptyUpstreamWithLiveMirror}</b> — independently of any flag, a provably-complete but
 *       <em>empty</em> upstream listing is refused whenever the mirror still holds live rows of that
 *       class. The two readings of that combination are "every issue in the project was deleted since
 *       the last sync" and "something about this listing is wrong (permission scope-out, a feature flag
 *       GitLab has not told us about, a vendor bug)". The first is rare and self-heals on the next
 *       sync; the second is not, and being wrong costs the whole project. So the sweep declines and
 *       reports degraded rather than deleting on the least trustworthy evidence it can receive.
 * </ol>
 *
 * <p>The cost of both guards is bounded and known: a project whose issues really were all deleted keeps
 * its phantom rows until a human notices. That is the cheaper failure.
 *
 * <p>Tombstone rather than row deletion: see {@code Issue#deletedAt}. Because
 * {@code IssueRepository.upsertCore} / {@code PullRequestRepository.upsertCore} clear the tombstone
 * ({@code deleted_at = NULL}), a project wrongly swept heals itself on the next ordinary sync — the
 * property that makes acting on inference tolerable at all.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabDeletionSweepService {

    private static final Logger log = LoggerFactory.getLogger(GitLabDeletionSweepService.class);

    private static final String ISSUE_QUERY_DOCUMENT = "GetProjectIssueNumbers";
    private static final String MERGE_REQUEST_QUERY_DOCUMENT = "GetProjectMergeRequestNumbers";

    /** The connection maximum. A number-only page is cheap, so the largest legal page minimises round trips. */
    private static final int SWEEP_PAGE_SIZE = GitLabSyncConstants.DEFAULT_PAGE_SIZE;

    /**
     * Cap on consecutive rate-limit retries of a single page. A RETRY does not advance the page cursor,
     * so the pagination cap cannot bound it; without this a permanently rate-limited page would loop
     * forever. Exhausting the budget is treated as an incomplete listing — fail-closed.
     */
    private static final int MAX_RETRY_ATTEMPTS = 5;

    private final IssueRepository issueRepository;
    private final RepositoryRepository repositoryRepository;
    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabProperties gitLabProperties;
    private final WorkspaceActorSelector actorSelector;
    private final GitLabWorkspaceLinkService workspaceLinkService;
    private final TransactionTemplate transactionTemplate;

    public GitLabDeletionSweepService(
            IssueRepository issueRepository,
            RepositoryRepository repositoryRepository,
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabProperties gitLabProperties,
            WorkspaceActorSelector actorSelector,
            GitLabWorkspaceLinkService workspaceLinkService,
            TransactionTemplate transactionTemplate) {
        this.issueRepository = issueRepository;
        this.repositoryRepository = repositoryRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.gitLabProperties = gitLabProperties;
        this.actorSelector = actorSelector;
        this.workspaceLinkService = workspaceLinkService;
        this.transactionTemplate = transactionTemplate;
    }

    /** Which of the two entity classes a listing/diff pass is operating on. */
    private enum SweptEntity {
        ISSUE("issues", ISSUE_QUERY_DOCUMENT, "project.issues", "project.issuesEnabled"),
        MERGE_REQUEST(
                "merge requests",
                MERGE_REQUEST_QUERY_DOCUMENT,
                "project.mergeRequests",
                "project.mergeRequestsEnabled");

        private final String plural;
        private final String document;
        private final String field;

        /**
         * Path of the project-level flag that says whether this entity class exists upstream at all.
         * When it is off, GitLab's finder scopes the project out and returns an empty connection rather
         * than an error — the listing would otherwise look complete and authorize deleting everything.
         */
        private final String featureFlagPath;

        SweptEntity(String plural, String document, String field, String featureFlagPath) {
            this.plural = plural;
            this.document = document;
            this.field = field;
            this.featureFlagPath = featureFlagPath;
        }

        String countPath() {
            return field + ".count";
        }

        String nodesPath() {
            return field + ".nodes";
        }

        String pageInfoPath() {
            return field + ".pageInfo";
        }
    }

    /**
     * The upstream half of the set difference.
     *
     * <p>The two members are inseparable on purpose. Every abort path in this class still has a
     * populated accumulator, so a bare {@code Set} cannot distinguish "this project has 12 issues"
     * from "we read 12 of 4000 and then hit the rate limit" — and acting on the second reading deletes
     * 3988 live issues. Pairing the set with {@code complete} makes that mistake impossible to make
     * silently: callers must read the flag to reach the numbers.
     *
     * @param numbers  the upstream IIDs observed; meaningful for deletion ONLY when {@code complete}
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
     * Outcome of sweeping one project.
     *
     * @param issuesTombstoned        issues newly tombstoned
     * @param mergeRequestsTombstoned merge requests newly tombstoned
     * @param skipped                 true when at least one entity class could not be swept because its
     *                                upstream listing was incomplete — the job should report warnings
     */
    public record SweepOutcome(int issuesTombstoned, int mergeRequestsTombstoned, boolean skipped) {
        static final SweepOutcome NOTHING = new SweepOutcome(0, 0, false);

        public int total() {
            return issuesTombstoned + mergeRequestsTombstoned;
        }
    }

    /**
     * Sweeps every monitored project of a scope, reporting {@link SyncPhase#SWEEP} progress and
     * honoring cancellation between projects.
     *
     * <p>One project's failure never stops the pass: an unsweepable project is skipped and the rest
     * still get reconciled, because the alternative — one rate-limited project suppressing the sweep
     * for the whole connection — is how drift becomes permanent.
     *
     * @param scopeId the workspace/scope to sweep
     * @param handle  live job handle for progress + cancellation; {@code null} outside a recorded job
     */
    public SweepOutcome sweepScope(Long scopeId, @Nullable SyncExecutionHandle handle) {
        // Only the connected instance's rows: another instance can host a project at the same path, and this
        // scope's credentials list only its own.
        List<Repository> repositories = actorSelector
                .connectedProviderId(scopeId)
                .map(providerId -> repositoryRepository.findAllByWorkspaceMonitorsOnProvider(scopeId, providerId))
                .orElseGet(List::of);
        if (repositories.isEmpty()) {
            return SweepOutcome.NOTHING;
        }

        int issues = 0;
        int mergeRequests = 0;
        boolean skipped = false;
        int done = 0;
        int total = repositories.size();

        for (Repository repository : repositories) {
            if (isCancelled(handle)) {
                log.info(
                        "GitLab deletion sweep cancelled between projects: scopeId={}, projectsSwept={}, projectsRemaining={}",
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
                // sweepRepository is written not to throw, but a sweep must never be the reason a
                // reconciliation job fails — it is drift repair, not the sync itself.
                log.warn(
                        "GitLab deletion sweep failed for project, continuing: projectPath={}, scopeId={}, message={}",
                        sanitizeForLog(repository.getNameWithOwner()),
                        scopeId,
                        e.toString());
                skipped = true;
                done++;
                continue;
            }

            issues += outcome.issuesTombstoned();
            mergeRequests += outcome.mergeRequestsTombstoned();
            skipped = skipped || outcome.skipped();
            done++;
        }

        SweepOutcome scopeOutcome = new SweepOutcome(issues, mergeRequests, skipped);
        report(handle, done, total, sweepSummary(scopeOutcome), null);
        log.info(
                "GitLab deletion sweep finished: scopeId={}, projectsSwept={}, issuesTombstoned={}, mergeRequestsTombstoned={}, degraded={}",
                scopeId,
                done,
                issues,
                mergeRequests,
                skipped);
        return scopeOutcome;
    }

    /** The one sentence the admin UI renders for the sweep's terminal report. */
    static String sweepSummary(SweepOutcome outcome) {
        if (outcome.total() == 0) {
            return outcome.skipped()
                    ? "Checked for deleted items — some projects could not be verified"
                    : "Checked for deleted items — none found";
        }
        return "Retired " + outcome.total() + " item" + (outcome.total() == 1 ? "" : "s") + " deleted upstream";
    }

    private static void report(
            @Nullable SyncExecutionHandle handle, int done, int total, String step, @Nullable String projectName) {
        if (handle != null) {
            handle.progress(done, total, SyncProgress.ofResource(SyncPhase.SWEEP, step, projectName, done, total));
        }
    }

    /**
     * Sweeps one project: tombstones every local issue and merge request that upstream no longer has.
     * Deletes nothing unless the corresponding upstream listing is provably complete.
     *
     * <p>The two entity classes are swept independently — an incomplete issue listing does not suppress
     * a provably complete merge-request sweep, and vice versa, because they are separate connections
     * with separate failure modes.
     *
     * @param scopeId    the workspace/scope owning the GitLab credentials
     * @param repository the local repository (GitLab project) to sweep
     * @param handle     the live job handle for progress and cooperative cancellation; may be
     *                   {@code null} when the sweep runs outside a recorded job
     */
    public SweepOutcome sweepRepository(Long scopeId, Repository repository, @Nullable SyncExecutionHandle handle) {
        String fullPath = repository.getNameWithOwner();
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(fullPath), "<unknown>");

        if (fullPath == null || fullPath.isBlank()) {
            log.warn("Skipped GitLab deletion sweep: reason=blankProjectPath, scopeId={}", scopeId);
            return SweepOutcome.NOTHING;
        }

        if (isCancelled(handle)) {
            return SweepOutcome.NOTHING;
        }

        int issuesTombstoned = 0;
        int mergeRequestsTombstoned = 0;
        boolean skipped = false;

        for (SweptEntity entity : SweptEntity.values()) {
            if (isCancelled(handle)) {
                // An operator-requested cancel is not a degraded sweep; the job already finalizes CANCELLED.
                break;
            }
            // Read the local side BEFORE the listing starts, and diff only this snapshot. The listing
            // takes many round trips; anything a webhook inserts while it runs is local but legitimately
            // absent from the pages already fetched, and diffing a post-listing read would tombstone it.
            // Snapshotting first makes the candidate set exactly "rows that existed before we started
            // looking" — the set the completeness guarantee actually covers. Items that arrive during the
            // window are left for the next sweep, by which point they are in the listing.
            List<Integer> localBeforeListing = localLiveNumbers(repository.getId(), entity);

            UpstreamListing listing = listUpstreamNumbers(scopeId, fullPath, entity, safeProjectPath, handle);
            // The count cross-check inside the listing cannot see this one: an empty listing reconciles
            // with an upstream count of zero perfectly, so "complete" is earned without proving anything.
            // Wiping a whole project on that evidence is not a trade worth making, so demote it to the
            // same incomplete/degraded path every other doubt takes. `localBeforeListing` is read through
            // a TYPE()-discriminated query, so an empty issue mirror is not masked by live merge requests
            // sharing the single `issue` table (and vice versa).
            if (listing.complete() && listing.numbers().isEmpty() && !localBeforeListing.isEmpty()) {
                listing = UpstreamListing.incomplete("emptyUpstreamWithLiveMirror");
            }
            if (!listing.complete()) {
                skipped = true;
                log.warn(
                        "Skipped GitLab deletion sweep, deleted nothing: reason={}, entity={}, projectPath={}, scopeId={}",
                        listing.reason(),
                        entity.plural,
                        safeProjectPath,
                        scopeId);
                continue;
            }
            // The listing can outlast the monitor, the connection or the group link it was started under, so the
            // authority to write is checked again in the transaction that writes.
            UpstreamListing complete = listing;
            Integer tombstoned =
                    transactionTemplate.execute(status -> workspaceLinkService.mayWriteRepository(scopeId, repository)
                            ? tombstoneMissing(
                                    repository.getId(), entity, localBeforeListing, complete.numbers(), safeProjectPath)
                            : null);
            if (tombstoned == null) {
                skipped = true;
                log.warn(
                        "Skipped GitLab deletion sweep, deleted nothing: reason=repositoryNotThisScopes, entity={}, projectPath={}, scopeId={}",
                        entity.plural,
                        safeProjectPath,
                        scopeId);
                continue;
            }
            if (entity == SweptEntity.ISSUE) {
                issuesTombstoned = tombstoned;
            } else {
                mergeRequestsTombstoned = tombstoned;
            }
        }

        return new SweepOutcome(issuesTombstoned, mergeRequestsTombstoned, skipped);
    }

    /** The live local numbers for one entity class of one repository. */
    private List<Integer> localLiveNumbers(Long repositoryId, SweptEntity entity) {
        return entity == SweptEntity.ISSUE
                ? issueRepository.findLiveIssueNumbersByRepositoryId(repositoryId)
                : issueRepository.findLivePullRequestNumbersByRepositoryId(repositoryId);
    }

    /**
     * Diffs a <em>pre-listing</em> snapshot of the local live numbers against a <em>provably
     * complete</em> upstream set and tombstones the difference. Never call this with a listing that is
     * not complete, and never with a {@code local} snapshot read after the listing began.
     *
     * @param local the local live numbers as of before the upstream listing started
     */
    private int tombstoneMissing(
            Long repositoryId, SweptEntity entity, List<Integer> local, Set<Integer> upstream, String safeProjectPath) {
        List<Integer> missing =
                local.stream().filter(number -> !upstream.contains(number)).toList();
        if (missing.isEmpty()) {
            log.debug(
                    "GitLab deletion sweep found no drift: entity={}, projectPath={}, localCount={}, upstreamCount={}",
                    entity.plural,
                    safeProjectPath,
                    local.size(),
                    upstream.size());
            return 0;
        }

        // Type-discriminated write: on GitLab, issue IIDs and merge-request IIDs are separate per-project
        // namespaces (issue #5 and MR !5 coexist), and both share the single-table `issue` table. A
        // type-blind UPDATE keyed on (repository_id, number) would tombstone a live MR when an issue with
        // the same IID is swept (and vice versa). Target only the class this pass proved complete.
        int tombstoned = entity == SweptEntity.ISSUE
                ? issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(repositoryId, missing, Instant.now())
                : issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(repositoryId, missing, Instant.now());
        log.info(
                "GitLab deletion sweep tombstoned upstream-deleted items: entity={}, projectPath={}, repoId={}, tombstoned={}, localCount={}, upstreamCount={}",
                entity.plural,
                safeProjectPath,
                repositoryId,
                tombstoned,
                local.size(),
                upstream.size());
        return tombstoned;
    }

    /**
     * Enumerates the complete upstream IID set for one entity class of one project.
     *
     * <p>Structured so that {@code complete} can only be reported at the single clean exit: the loop
     * returns {@link UpstreamListing#incomplete} from every other path, so a future edit that adds a
     * new failure mode and forgets about it fails safe by default rather than authorizing a deletion.
     */
    private UpstreamListing listUpstreamNumbers(
            Long scopeId,
            String fullPath,
            SweptEntity entity,
            String safeProjectPath,
            @Nullable SyncExecutionHandle handle) {
        String context = entity.plural + " numbers for " + safeProjectPath;

        Set<Integer> numbers = new LinkedHashSet<>();
        String cursor = null;
        String previousCursor = null;
        int page = 0;
        int reportedCount = -1;
        int retryAttempts = 0;

        while (true) {
            if (isCancelled(handle)) {
                return UpstreamListing.incomplete("cancelled");
            }
            if (page >= GitLabSyncConstants.MAX_PAGINATION_PAGES) {
                // The cap is a runaway guard, not a stopping condition: the set beyond it is unread, so
                // everything past it would look deleted.
                return UpstreamListing.incomplete("paginationCapReached");
            }

            try {
                graphQlClientProvider.acquirePermission();
            } catch (RuntimeException e) {
                // Circuit breaker open — the API is presumed unhealthy; a short set now is untrustworthy.
                log.warn("GitLab deletion sweep listing rejected: reason=circuitOpen, context={}", context);
                return UpstreamListing.incomplete("circuitOpen");
            }
            try {
                graphQlClientProvider.waitIfRateLimitLow(scopeId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return UpstreamListing.incomplete("interrupted");
            }

            int remaining = graphQlClientProvider.getRateLimitRemaining(scopeId);
            int pageSize = GitLabSyncConstants.adaptPageSize(SWEEP_PAGE_SIZE, remaining);

            ClientGraphQlResponse response;
            try {
                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
                response = client.documentName(entity.document)
                        .variable("fullPath", fullPath)
                        .variable("first", pageSize)
                        .variable("after", cursor)
                        .execute()
                        .block(gitLabProperties.extendedGraphqlTimeout());
            } catch (RuntimeException e) {
                // Includes the block() timeout and any transport failure. Whatever the cause, the set is
                // short — refuse it.
                graphQlClientProvider.recordFailure(e);
                log.warn(
                        "GitLab deletion sweep listing failed: context={}, page={}, message={}",
                        context,
                        page,
                        e.toString());
                return UpstreamListing.incomplete("listingThrew");
            }

            var handleResult = responseHandler.handle(response, context, log);
            if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                // The handler already slept (rate-limited); retry the SAME cursor so no page is skipped.
                // Deliberately does NOT advance page, so the pagination cap cannot bound this — guard it
                // with a dedicated retry budget instead, and fail closed once it is exhausted.
                if (++retryAttempts > MAX_RETRY_ATTEMPTS) {
                    return UpstreamListing.incomplete("rateLimitRetriesExhausted");
                }
                continue;
            }
            if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                graphQlClientProvider.recordFailure(
                        new GitLabSyncException("Invalid GraphQL response during deletion sweep"));
                return UpstreamListing.incomplete("graphQlError");
            }
            graphQlClientProvider.recordSuccess();

            // Interrogate the feature flag before believing a single node. A project with Issues (or
            // Merge Requests) switched off is scoped out by GitLab's finder, so the connection comes back
            // empty and well-formed — the exact shape of "this project has nothing", which would tombstone
            // the entire mirrored set. Only an explicit `false` aborts: a null (older GitLab, restricted
            // field) leaves the decision to the emptiness guard rather than blocking every sweep.
            if (Boolean.FALSE.equals(readBooleanField(Objects.requireNonNull(response), entity.featureFlagPath))) {
                log.warn(
                        "GitLab deletion sweep listing refused, feature disabled upstream: context={}, flag={}",
                        context,
                        entity.featureFlagPath);
                return UpstreamListing.incomplete("featureDisabled");
            }

            int pageCount;
            List<Integer> pageNumbers;
            GitLabPageInfo pageInfo;
            try {
                Object countField = Objects.requireNonNull(response)
                        .field(entity.countPath())
                        .getValue();
                pageCount = countField instanceof Number n ? n.intValue() : -1;

                @SuppressWarnings({"unchecked", "rawtypes"})
                List<Map<String, Object>> nodes = (List) Objects.requireNonNull(response)
                        .field(entity.nodesPath())
                        .toEntityList(Map.class);
                pageNumbers = new ArrayList<>(nodes == null ? 0 : nodes.size());
                if (nodes != null) {
                    for (Map<String, Object> node : nodes) {
                        Integer iid = parseIid(node == null ? null : node.get("iid"));
                        if (iid == null) {
                            // A node whose IID we cannot read would be silently absent from the upstream
                            // set and could make a live local row look deleted. Refuse the whole listing.
                            log.warn(
                                    "GitLab deletion sweep saw an unparseable IID: context={}, page={}", context, page);
                            return UpstreamListing.incomplete("unparseableIid");
                        }
                        pageNumbers.add(iid);
                    }
                }

                pageInfo = Objects.requireNonNull(response)
                        .field(entity.pageInfoPath())
                        .toEntity(GitLabPageInfo.class);
            } catch (RuntimeException e) {
                log.warn(
                        "GitLab deletion sweep decode failed: context={}, page={}, message={}, cause={}",
                        context,
                        page,
                        e.toString(),
                        rootCauseOf(e).toString());
                return UpstreamListing.incomplete("decodeFailed");
            }

            if (pageCount < 0) {
                // GitLab always returns count for these connections; its absence removes the only proof
                // of completeness we have, so decline rather than trust an unverifiable listing.
                return UpstreamListing.incomplete("missingCount");
            }

            numbers.addAll(pageNumbers);
            reportedCount = pageCount;
            retryAttempts = 0;

            if (pageInfo == null || !pageInfo.hasNextPage()) {
                break;
            }
            cursor = pageInfo.endCursor();
            if (cursor == null) {
                // hasNextPage without a cursor: there is more and we cannot ask for it.
                return UpstreamListing.incomplete("missingCursor");
            }
            if (responseHandler.isPaginationLoop(cursor, previousCursor, context, log)) {
                return UpstreamListing.incomplete("paginationLoop");
            }
            previousCursor = cursor;
            page++;

            try {
                Thread.sleep(gitLabProperties.paginationThrottle().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return UpstreamListing.incomplete("interrupted");
            }
        }

        // The listing claims to be whole; make it prove it against the server's own count. This is the
        // check that catches a silent truncation, which is the one failure that looks exactly like a
        // small project and would otherwise authorize deleting everything past the cut.
        if (numbers.size() != reportedCount) {
            log.warn(
                    "GitLab deletion sweep listing did not reconcile with upstream count, deleting nothing: context={}, received={}, count={}",
                    context,
                    numbers.size(),
                    reportedCount);
            return UpstreamListing.incomplete("countMismatch");
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
            ClientResponseField field = Objects.requireNonNull(response).field(path);
            Object value = field == null ? null : field.getValue();
            return value instanceof Boolean bool ? bool : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Integer parseIid(@Nullable Object iidObj) {
        if (iidObj == null) {
            return null;
        }
        if (iidObj instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(iidObj).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isCancelled(@Nullable SyncExecutionHandle handle) {
        return handle != null && handle.isCancellationRequested();
    }

    /** Unwraps to the innermost cause, guarding against a self-referential cause chain. */
    private static Throwable rootCauseOf(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
