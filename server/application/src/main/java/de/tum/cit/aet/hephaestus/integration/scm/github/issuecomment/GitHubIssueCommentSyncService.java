package de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlErrorUtils.isNotFoundError;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.DEFAULT_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser.RepositoryOwnerAndName;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.FieldAccessException;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub issue comments via GraphQL API.
 * <p>
 * Uses typed GraphQL models for type-safe deserialization and delegates
 * persistence to GitHubIssueCommentProcessor.
 * <p>
 * <b>Note:</b> Most comments are fetched inline with issues via GetRepositoryIssues query,
 * and inline with pull requests via GetRepositoryPullRequests. This service is primarily used to:
 * <ul>
 *   <li>Fetch remaining comments for issues that have more than 10 (embedded limit)</li>
 *   <li>Handle standalone comment sync when issues aren't involved</li>
 * </ul>
 * <p>
 * <b>Pull requests:</b> a PR's conversation comments are the same {@code IssueComment} entity as an
 * issue's, but GitHub exposes them under a different root ({@code repository.pullRequest} rather than
 * {@code repository.issue}, since {@code repository.issues} excludes PRs). {@link #syncRemainingComments}
 * therefore selects the query document and response path from the parent's concrete type — everything
 * downstream (pagination, rate limiting, persistence) is shared.
 *
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueSyncService
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService
 */
@Service
public class GitHubIssueCommentSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueCommentSyncService.class);
    private static final String QUERY_DOCUMENT = "GetIssueComments";
    private static final String PULL_REQUEST_QUERY_DOCUMENT = "GetPullRequestComments";
    private static final String ISSUE_PARENT_PATH = "repository.issue";
    private static final String PULL_REQUEST_PARENT_PATH = "repository.pullRequest";

    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubIssueCommentProcessor commentProcessor;
    private final RepositoryRepository repositoryRepository;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private final TransactionTemplate transactionTemplate;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int PARENT_REPAIR_CHUNK = 1000;

    public GitHubIssueCommentSyncService(
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubIssueCommentProcessor commentProcessor,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper,
            RepositoryRepository repositoryRepository,
            TransactionTemplate transactionTemplate) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.commentProcessor = commentProcessor;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
        this.repositoryRepository = repositoryRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Moves this repository's comments from issue rows a stored GitHub pull request contradicts to that pull
     * request, from what is already stored and with no provider call. The ceiling is fixed when the pass starts,
     * each chunk commits on its own and starts after the last id the previous one covered; comments stored after
     * the start wait for the next pass.
     *
     * @return the number of comments moved
     */
    public int repairCanonicalParents(long repositoryId) {
        long ceiling = commentProcessor.canonicalParentCeiling(repositoryId);
        long after = 0;
        int moved = 0;
        while (after < ceiling) {
            var chunk = commentProcessor.moveToCanonicalParents(repositoryId, after, ceiling, PARENT_REPAIR_CHUNK);
            moved += chunk.moved();
            if (chunk.lastId() <= after) break;
            after = chunk.lastId();
        }
        if (moved > 0) {
            log.info("Moved comments to their pull request: repositoryId={}, comments={}", repositoryId, moved);
        }
        return moved;
    }

    /**
     * Synchronizes remaining comments for an issue or pull request, starting from the given cursor.
     * <p>
     * This method is called by GitHubIssueSyncService when an issue has more than 10 comments, and by
     * GitHubPullRequestSyncService when a PR has more than 10 conversation comments (the embedded limit
     * in the GetRepositoryIssues / GetRepositoryPullRequests queries). It continues pagination from where
     * the embedded comments left off, avoiding re-fetching already synced comments.
     * <p>
     * The parent's concrete type selects the GraphQL root: a {@link PullRequest} is fetched via
     * {@code repository.pullRequest} (GetPullRequestComments) because {@code repository.issue} does not
     * resolve pull requests. Both roots yield the same IssueComment nodes and share the persistence path.
     * <p>
     * GitHub is asked with no transaction open. The comments it returned before any stop are then stored in one short
     * transaction, whose database errors reach the caller's retry.
     *
     * @param scopeId the scope ID for authentication
     * @param issue the issue or pull request to fetch remaining comments for
     * @param startCursor the pagination cursor to start from (from embedded comments)
     * @return number of additional comments synced
     */
    public SyncResult syncRemainingComments(Long scopeId, Issue issue, String startCursor) {
        if (issue == null || issue.getRepository() == null) {
            log.warn(
                    "Skipped remaining comment sync: reason=issueOrRepositoryNull, issueId={}",
                    issue != null ? issue.getId() : "null");
            return SyncResult.completed(0);
        }

        // A caller can carry the issue across page transactions. Its provider proxy belongs to the old session, so
        // the name comes from the stored repository, and the write builds its context from it again.
        Long repositoryId = Objects.requireNonNull(issue.getRepository().getId());
        String nameWithOwner = repositoryRepository
                .findByIdWithOrganization(repositoryId)
                .orElseThrow(() -> new IllegalStateException("Repository no longer exists during comment sync"))
                .getNameWithOwner();
        String safeNameWithOwner = sanitizeForLog(nameWithOwner);
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(nameWithOwner);
        if (parsedName.isEmpty()) {
            log.warn("Skipped remaining comment sync: reason=invalidRepoNameFormat, repoName={}", safeNameWithOwner);
            return SyncResult.completed(0);
        }
        RepositoryOwnerAndName ownerAndName = parsedName.get();

        // A PR's conversation comments hang off repository.pullRequest, not repository.issue.
        boolean isPullRequest = issue instanceof PullRequest;
        String queryDocument = isPullRequest ? PULL_REQUEST_QUERY_DOCUMENT : QUERY_DOCUMENT;
        String parentPath = isPullRequest ? PULL_REQUEST_PARENT_PATH : ISSUE_PARENT_PATH;
        String commentsPath = parentPath + ".comments";

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

        List<GHIssueComment> fetched = new ArrayList<>();
        boolean deletedFromGitHub = false;
        int commentsReceived = 0;
        String cursor = startCursor;
        boolean hasMore = true;
        int pageCount = 0;
        int retryAttempt = 0;
        int reportedTotalCount = -1;

        log.debug(
                "Starting remaining comment sync: repoName={}, issueNumber={}, isPullRequest={}, startCursor={}",
                safeNameWithOwner,
                issue.getNumber(),
                isPullRequest,
                startCursor != null ? startCursor.substring(0, Math.min(20, startCursor.length())) + "..." : "null");

        boolean rateLimited = false;
        while (hasMore) {
            pageCount++;
            if (pageCount >= MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for remaining comment sync: repoName={}, issueNumber={}, limit={}",
                        safeNameWithOwner,
                        issue.getNumber(),
                        MAX_PAGINATION_PAGES);
                break;
            }

            try {
                final String currentCursor = cursor;
                final int currentPage = pageCount;
                ClientGraphQlResponse response = Mono.defer(() -> client.documentName(queryDocument)
                                .variable("owner", ownerAndName.owner())
                                .variable("name", ownerAndName.name())
                                .variable("number", issue.getNumber())
                                .variable(
                                        "first",
                                        adaptPageSize(
                                                DEFAULT_PAGE_SIZE,
                                                graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                .variable("after", currentCursor)
                                .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying remaining comment sync after transport error: repoName={}, issueNumber={}, page={}, attempt={}, error={}",
                                        safeNameWithOwner,
                                        issue.getNumber(),
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getClass().getSimpleName())))
                        .block(syncProperties.graphqlTimeout());

                if (response == null || !response.isValid()) {
                    if (isNotFoundError(response, parentPath)) {
                        log.debug(
                                "Skipped remaining comment sync: reason=issueDeletedFromGitHub, repoName={}, issueNumber={}",
                                safeNameWithOwner,
                                issue.getNumber());
                        deletedFromGitHub = true;
                        break;
                    }
                    ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(response);
                    if (classification != null) {
                        if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "remaining comment sync",
                                "issueNumber",
                                issue.getNumber(),
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        rateLimited = classification.category() == Category.RATE_LIMITED;
                        break;
                    }
                    log.warn(
                            "Invalid GraphQL response for remaining comment sync: repoName={}, issueNumber={}",
                            safeNameWithOwner,
                            issue.getNumber());
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, response);

                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                            scopeId, "remaining comment sync", "issueNumber", issue.getNumber(), log)) {
                        rateLimited = true;
                        break;
                    }
                }

                GHIssueCommentConnection connection =
                        response.field(commentsPath).toEntity(GHIssueCommentConnection.class);

                if (connection == null || connection.getNodes() == null) {
                    break;
                }
                if (connection.getNodes().isEmpty()) {
                    hasMore = false;
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = connection.getTotalCount();
                }
                commentsReceived += connection.getNodes().size();

                fetched.addAll(connection.getNodes());

                GHPageInfo pageInfo = connection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                retryAttempt = 0;
            } catch (InstallationNotFoundException e) {
                throw e;
            } catch (FieldAccessException e) {
                if (isNotFoundError(e.getResponse(), parentPath)) {
                    log.debug(
                            "Skipped remaining comment sync: reason=issueDeletedFromGitHub, repoName={}, issueNumber={}",
                            safeNameWithOwner,
                            issue.getNumber());
                    deletedFromGitHub = true;
                    break;
                }
                log.error(
                        "Failed to sync remaining comments: repoName={}, issueNumber={}",
                        safeNameWithOwner,
                        issue.getNumber(),
                        e);
                break;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                if (!graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                        classification,
                        retryAttempt,
                        MAX_RETRY_ATTEMPTS,
                        "remaining comment sync",
                        "issueNumber",
                        issue.getNumber(),
                        log))) {
                    rateLimited = classification.category() == Category.RATE_LIMITED;
                    break;
                }
                retryAttempt++;
            }
        }

        // Raw nodes received vs comments.totalCount (totalSynced is post-filter).
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "issueComments", commentsReceived, reportedTotalCount, hasMore, "issueNumber=" + issue.getNumber());
        }

        int totalSynced = fetched.isEmpty()
                ? 0
                : Objects.requireNonNull(transactionTemplate.execute(
                        status -> storeComments(fetched, repositoryId, issue.getNumber(), scopeId)));
        log.debug(
                "Completed remaining comment sync: repoName={}, issueNumber={}, additionalComments={}",
                safeNameWithOwner,
                issue.getNumber(),
                totalSynced);
        if (deletedFromGitHub) {
            return SyncResult.completed(totalSynced);
        }
        return SyncResult.ofListRead(hasMore, rateLimited, totalSynced);
    }

    /** Stores the comments GitHub returned for one issue or pull request; the caller runs it in one transaction. */
    private int storeComments(List<GHIssueComment> comments, Long repositoryId, int issueNumber, Long scopeId) {
        Repository repository = repositoryRepository
                .findByIdWithOrganization(repositoryId)
                .orElseThrow(() -> new IllegalStateException("Repository no longer exists during comment sync"));
        ProcessingContext context = ProcessingContext.forSync(scopeId, repository);
        int stored = 0;
        for (var graphQlComment : comments) {
            GitHubCommentDTO dto = GitHubCommentDTO.fromIssueComment(graphQlComment);
            if (dto != null && commentProcessor.process(dto, issueNumber, context) != null) {
                stored++;
            }
        }
        return stored;
    }
}
