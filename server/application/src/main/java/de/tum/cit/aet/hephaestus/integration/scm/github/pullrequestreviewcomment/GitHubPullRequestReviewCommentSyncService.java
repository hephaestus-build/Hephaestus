package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlErrorUtils.isNotFoundError;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.DEFAULT_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.LARGE_PAGE_SIZE;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.adaptPageSize;

import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser.RepositoryOwnerAndName;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCommentAuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHDiffSide;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewThreadConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubReviewThreadDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.dto.GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.FieldAccessException;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service for synchronizing GitHub pull request review comments via GraphQL API.
 * <p>
 * This service fetches pull request review comments via GraphQL (through review threads)
 * and uses GitHubPullRequestReviewCommentProcessor for persistence. It supports syncing
 * comments for a single PR or all PRs in a repository.
 * <p>
 * <b>Note:</b> Most review threads and comments are fetched inline with PRs via
 * GetRepositoryPullRequests query. This service is primarily used to:
 * <ul>
 *   <li>Process thread data from the embedded response</li>
 *   <li>Fetch remaining threads for PRs that have more than 10 (embedded limit)</li>
 *   <li>Handle standalone comment sync when PRs aren't involved</li>
 * </ul>
 * <p>
 * <b>Threading Model:</b> GraphQL provides explicit thread structure via reviewThreads,
 * which is more accurate than the synthetic threading from webhooks. This service
 * creates/updates threads based on GraphQL data and associates comments with them.
 *
 * @see de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService
 */
@SuppressWarnings("deprecation")
@Service
public class GitHubPullRequestReviewCommentSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestReviewCommentSyncService.class);
    private static final String GET_PR_REVIEW_COMMENTS_DOCUMENT = "GetPullRequestReviewComments";
    private static final String GET_THREAD_COMMENTS_DOCUMENT = "GetThreadComments";

    private final PullRequestReviewThreadRepository threadRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubPullRequestReviewCommentProcessor commentProcessor;
    private final GitHubUserProcessor userProcessor;
    private final GitHubSyncProperties syncProperties;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubGraphQlSyncCoordinator graphQlSyncHelper;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public GitHubPullRequestReviewCommentSyncService(
            PullRequestReviewThreadRepository threadRepository,
            GitHubGraphQlClientProvider graphQlClientProvider,
            GitHubPullRequestReviewCommentProcessor commentProcessor,
            GitHubUserProcessor userProcessor,
            GitHubSyncProperties syncProperties,
            GitHubExceptionClassifier exceptionClassifier,
            GitHubGraphQlSyncCoordinator graphQlSyncHelper) {
        this.threadRepository = threadRepository;
        this.graphQlClientProvider = graphQlClientProvider;
        this.commentProcessor = commentProcessor;
        this.userProcessor = userProcessor;
        this.syncProperties = syncProperties;
        this.exceptionClassifier = exceptionClassifier;
        this.graphQlSyncHelper = graphQlSyncHelper;
    }

    /**
     * Synchronizes all review comments for a single pull request using GraphQL.
     *
     * @param scopeId the scope ID for authentication
     * @param pullRequest the pull request to sync comments for
     * @return number of comments synced
     */
    @Transactional
    public int syncCommentsForPullRequest(Long scopeId, PullRequest pullRequest) {
        if (pullRequest == null) {
            log.warn("Skipped review comment sync: reason=prIsNull");
            return 0;
        }

        Repository repository = pullRequest.requireRepository();
        if (repository == null) {
            log.warn("Skipped review comment sync: reason=prHasNoRepository, prId={}", pullRequest.getId());
            return 0;
        }

        String safeNameWithOwner = sanitizeForLog(repository.getNameWithOwner());
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(repository.getNameWithOwner());
        if (parsedName.isEmpty()) {
            log.warn("Skipped review comment sync: reason=invalidRepoNameFormat, repoName={}", safeNameWithOwner);
            return 0;
        }
        String owner = parsedName.get().owner();
        String name = parsedName.get().name();

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

        int retryAttempt = 0;
        try {
            int totalSynced = 0;
            // Threads received across all pages — the apples-to-apples count for
            // reviewThreads.totalCount. (totalSynced counts comments, a different unit.)
            int threadsReceived = 0;
            String cursor = null;
            boolean hasNextPage = true;
            int pageCount = 0;
            int reportedTotalCount = -1;

            while (hasNextPage) {
                // Check for interrupt (e.g., during application shutdown)
                if (Thread.interrupted()) {
                    log.info(
                            "Review comments sync interrupted (shutdown requested): repoName={}, prNumber={}, pageCount={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            pageCount);
                    Thread.currentThread().interrupt();
                    break;
                }

                pageCount++;
                if (pageCount >= MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached maximum pagination limit for review comments: repoName={}, prNumber={}, limit={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            MAX_PAGINATION_PAGES);
                    break;
                }

                final String currentCursor = cursor;
                final int currentPage = pageCount;

                ClientGraphQlResponse graphQlResponse = Mono.defer(
                                () -> client.documentName(GET_PR_REVIEW_COMMENTS_DOCUMENT)
                                        .variable("owner", owner)
                                        .variable("name", name)
                                        .variable("number", pullRequest.getNumber())
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
                                        "Retrying review comment sync after transport error: prNumber={}, page={}, attempt={}, error={}",
                                        pullRequest.getNumber(),
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(syncProperties.extendedGraphqlTimeout());

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(graphQlResponse);
                    if (classification != null) {
                        if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "review comment sync",
                                "prNumber",
                                pullRequest.getNumber(),
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        break;
                    }
                    log.warn(
                            "Received invalid GraphQL response: repoName={}, prNumber={}, errors={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    break;
                }

                // Track rate limit from response
                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                // Check if we should pause due to rate limiting
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                            scopeId, "review comment sync", "prNumber", pullRequest.getNumber(), log)) {
                        break;
                    }
                }

                GHPullRequestReviewThreadConnection response = graphQlResponse
                        .field("repository.pullRequest.reviewThreads")
                        .toEntity(GHPullRequestReviewThreadConnection.class);

                if (response == null || response.getNodes() == null) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = response.getTotalCount();
                }

                threadsReceived += response.getNodes().size();
                for (var graphQlThread : response.getNodes()) {
                    int synced = processThreadInternal(graphQlThread, pullRequest, client, scopeId);
                    totalSynced += synced;
                }

                var pageInfo = response.getPageInfo();
                hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                retryAttempt = 0;
            }

            // Threads received (not comments synced — a different unit) vs reviewThreads.totalCount.
            if (reportedTotalCount >= 0) {
                GraphQlConnectionOverflowDetector.checkPaginated(
                        "reviewThreads",
                        threadsReceived,
                        reportedTotalCount,
                        hasNextPage,
                        "prNumber=" + pullRequest.getNumber());
            }

            log.debug(
                    "Synced review comments for pull request: repoName={}, prNumber={}, commentCount={}",
                    safeNameWithOwner,
                    pullRequest.getNumber(),
                    totalSynced);
            return totalSynced;
        } catch (InstallationNotFoundException e) {
            // Re-throw to abort the entire sync operation
            throw e;
        } catch (FieldAccessException e) {
            // Check if this is a NOT_FOUND error (PR deleted from GitHub)
            if (isNotFoundError(e.getResponse(), "repository.pullRequest")) {
                log.debug(
                        "Skipped review comment sync: reason=prDeletedFromGitHub, repoName={}, prNumber={}",
                        safeNameWithOwner,
                        pullRequest.getNumber());
                return 0;
            }
            log.error(
                    "Failed to sync review comments: repoName={}, prNumber={}",
                    safeNameWithOwner,
                    pullRequest.getNumber(),
                    e);
            return 0;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            if (!graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                    classification,
                    retryAttempt,
                    MAX_RETRY_ATTEMPTS,
                    "review comment sync",
                    "prNumber",
                    pullRequest.getNumber(),
                    log))) {
                return 0;
            }
            return 0;
        }
    }

    /**
     * Processes a review thread DTO and its comments.
     * <p>
     * Called from GitHubPullRequestSyncService when processing threads embedded in the PR response.
     * The DTO wraps the thread data and its comments connection for processing.
     *
     * @param threadDto   the review thread DTO
     * @param pullRequest the pull request the thread belongs to
     * @param scopeId     the scope ID for authentication (used for nested comment pagination)
     * @return number of comments synced from this thread
     */
    public int processThread(GitHubReviewThreadDTO threadDto, PullRequest pullRequest, Long scopeId) {
        if (threadDto == null) {
            return 0;
        }
        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);
        return processThreadFromDto(threadDto, pullRequest, client, scopeId);
    }

    /**
     * Internal method to process a review thread DTO with an existing client.
     *
     * @param threadDto   the review thread DTO
     * @param pullRequest the pull request the thread belongs to
     * @param client      the GraphQL client to use for nested pagination
     * @return number of comments synced from this thread
     */
    private int processThreadFromDto(
            GitHubReviewThreadDTO threadDto, PullRequest pullRequest, HttpGraphQlClient client, Long scopeId) {
        if (threadDto == null) {
            return 0;
        }

        GHPullRequestReviewCommentConnection commentsConnection = threadDto.commentsConnection();
        if (commentsConnection == null || commentsConnection.getNodes() == null) {
            return 0;
        }

        // Handle nested pagination for thread comments
        var commentsPageInfo = commentsConnection.getPageInfo();
        if (commentsPageInfo != null && Boolean.TRUE.equals(commentsPageInfo.getHasNextPage())) {
            // Fetch all remaining comments using pagination
            fetchAllRemainingThreadComments(
                    threadDto.nodeId(), commentsConnection, commentsPageInfo.getEndCursor(), client, scopeId);
        }

        var graphQlComments = commentsConnection.getNodes();
        if (graphQlComments.isEmpty()) {
            return 0;
        }

        // Get the first comment to determine the thread ID (use its databaseId as thread ID)
        var firstComment = graphQlComments.get(0);
        Long threadId = extractDatabaseId(firstComment);
        if (threadId == null) {
            log.warn("Skipped thread: reason=missingDatabaseIdOnFirstComment, threadId={}", threadDto.nodeId());
            return 0;
        }

        // Create or update the thread, passing the first comment to set timestamps
        PullRequestReviewThread thread = getOrCreateThreadFromDto(threadId, threadDto, pullRequest, firstComment);

        // Create processing context for sync operations to enable activity event creation
        ProcessingContext context = ProcessingContext.forSync(scopeId, pullRequest.requireRepository());

        int synced = 0;
        PullRequestReviewComment rootComment = null;
        for (var graphQlComment : graphQlComments) {
            GitHubReviewCommentDTO dto = convertToDTO(graphQlComment, thread);
            if (dto != null) {
                PullRequestReviewComment comment = commentProcessor.processCreated(
                        dto, pullRequest.requireRepository().getId(), pullRequest.getNumber(), context);
                if (comment != null) {
                    synced++;
                    // Track the first (root) comment
                    if (rootComment == null) {
                        rootComment = comment;
                    }
                }
            }
        }

        // Set the root comment on the thread if not already set
        if (rootComment != null && thread.getRootComment() == null) {
            thread.setRootComment(rootComment);
            threadRepository.save(thread);
        }

        return synced;
    }

    /**
     * Fetches all remaining comments for a thread when the initial query hit the pagination limit.
     * This method modifies the commentsConnection's nodes list in place by adding all fetched comments.
     *
     * @param threadNodeId       the node ID of the thread (for pagination queries)
     * @param commentsConnection the existing comments connection to update
     * @param startCursor        the cursor to start fetching from
     * @param client             the GraphQL client to use for fetching
     */
    private void fetchAllRemainingThreadComments(
            String threadNodeId,
            GHPullRequestReviewCommentConnection commentsConnection,
            String startCursor,
            HttpGraphQlClient client,
            Long scopeId) {
        if (commentsConnection == null || commentsConnection.getNodes() == null) {
            return;
        }

        // Create a mutable list to collect all comments
        List<GHPullRequestReviewComment> allComments = new ArrayList<>(commentsConnection.getNodes());

        String cursor = startCursor;
        boolean hasMore = true;
        int fetchedPages = 0;
        int retryAttempt = 0;
        int reportedTotalCount = -1;

        while (hasMore) {
            // Check for interrupt (e.g., during application shutdown)
            if (Thread.interrupted()) {
                log.info(
                        "Thread comments fetch interrupted (shutdown requested): threadId={}, fetchedPages={}",
                        threadNodeId,
                        fetchedPages);
                Thread.currentThread().interrupt();
                break;
            }

            fetchedPages++;
            if (fetchedPages > MAX_PAGINATION_PAGES) {
                log.warn(
                        "Reached maximum pagination limit for thread comments: threadId={}, limit={}",
                        threadNodeId,
                        MAX_PAGINATION_PAGES);
                break;
            }

            try {
                final String currentCursor = cursor;
                final int currentPage = fetchedPages;

                ClientGraphQlResponse response = Mono.defer(() -> client.documentName(GET_THREAD_COMMENTS_DOCUMENT)
                                .variable("threadId", threadNodeId)
                                .variable(
                                        "first",
                                        adaptPageSize(
                                                LARGE_PAGE_SIZE, graphQlClientProvider.getRateLimitRemaining(scopeId)))
                                .variable("after", currentCursor)
                                .execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying thread comments fetch after transport error: threadId={}, page={}, attempt={}, error={}",
                                        threadNodeId,
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(syncProperties.graphqlTimeout());

                if (response == null || !response.isValid()) {
                    ClassificationResult classification = graphQlSyncHelper.classifyGraphQlErrors(response);
                    if (classification != null) {
                        if (graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "thread comments fetch",
                                "threadId",
                                threadNodeId,
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        break;
                    }
                    log.warn(
                            "Invalid GraphQL response for thread comments: threadId={}, errors={}",
                            threadNodeId,
                            response != null ? response.getErrors() : "null");
                    break;
                }

                // Track rate limit from response
                graphQlClientProvider.trackRateLimit(scopeId, response);

                // Check if we should pause due to rate limiting
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    if (!graphQlSyncHelper.waitForRateLimitIfNeeded(
                            scopeId, "thread comments fetch", "threadId", threadNodeId, log)) {
                        break;
                    }
                }

                GHPullRequestReviewCommentConnection fetchedConnection =
                        response.field("node.comments").toEntity(GHPullRequestReviewCommentConnection.class);

                if (fetchedConnection == null || fetchedConnection.getNodes() == null) {
                    break;
                }

                if (reportedTotalCount < 0) {
                    reportedTotalCount = fetchedConnection.getTotalCount();
                }

                allComments.addAll(fetchedConnection.getNodes());

                GHPageInfo pageInfo = fetchedConnection.getPageInfo();
                hasMore = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
                retryAttempt = 0;
            } catch (Exception e) {
                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                if (!graphQlSyncHelper.handleGraphQlClassification(new GraphQlClassificationContext(
                        classification,
                        retryAttempt,
                        MAX_RETRY_ATTEMPTS,
                        "thread comments fetch",
                        "threadId",
                        threadNodeId,
                        log))) {
                    break;
                }
                retryAttempt++;
            }
        }

        // allComments holds the embedded first page + every paginated page (raw), apples-to-apples
        // with the thread's comments.totalCount.
        if (reportedTotalCount >= 0) {
            GraphQlConnectionOverflowDetector.checkPaginated(
                    "threadComments", allComments.size(), reportedTotalCount, hasMore, "threadId=" + threadNodeId);
        }

        // Update the connection's comments with the complete list
        commentsConnection.setNodes(allComments);

        if (fetchedPages > 0) {
            log.debug(
                    "Fetched additional thread comments: threadId={}, pageCount={}, totalComments={}",
                    threadNodeId,
                    fetchedPages,
                    allComments.size());
        }
    }

    /**
     * Gets or creates a thread based on DTO data.
     * <p>
     * Since PullRequestReviewThread in GitHub's GraphQL API doesn't have timestamp fields,
     * we derive the thread's createdAt and updatedAt from the first (root) comment's timestamps.
     *
     * @param threadId     the thread ID (first comment's databaseId)
     * @param threadDto    the thread DTO data
     * @param pullRequest  the pull request the thread belongs to
     * @param firstComment the first comment in the thread (used for timestamps)
     * @return the created or existing thread
     */
    private PullRequestReviewThread getOrCreateThreadFromDto(
            Long threadId,
            GitHubReviewThreadDTO threadDto,
            PullRequest pullRequest,
            GHPullRequestReviewComment firstComment) {
        Long providerId = Objects.requireNonNull(
                pullRequest.requireRepository().getProvider().getId());
        return threadRepository
                .findByNativeIdAndProviderId(threadId, providerId)
                .orElseGet(() -> {
                    PullRequestReviewThread thread = new PullRequestReviewThread();
                    thread.setNativeId(threadId);
                    thread.setProvider(pullRequest.requireRepository().getProvider());
                    thread.setNodeId(threadDto.nodeId());
                    thread.setPullRequest(pullRequest);
                    thread.setPath(threadDto.path());
                    thread.setLine(threadDto.line());
                    thread.setStartLine(threadDto.startLine());
                    thread.setSide(mapDiffSideString(threadDto.diffSide()));
                    thread.setStartSide(mapDiffSideString(threadDto.startDiffSide()));
                    thread.setOutdated(threadDto.isOutdated());
                    thread.setCollapsed(threadDto.isCollapsed());

                    // Set timestamps from the first (root) comment
                    // PullRequestReviewThread doesn't have its own timestamps in GitHub API,
                    // so we use the first comment's timestamps as a proxy
                    if (firstComment != null) {
                        if (firstComment.getCreatedAt() != null) {
                            thread.setCreatedAt(firstComment.getCreatedAt().toInstant());
                        }
                        if (firstComment.getUpdatedAt() != null) {
                            thread.setUpdatedAt(firstComment.getUpdatedAt().toInstant());
                        }
                    }

                    // Map resolved state and resolvedBy user
                    if (threadDto.isResolved()) {
                        thread.setState(PullRequestReviewThread.State.RESOLVED);
                        // Set resolvedBy user if available (already converted to DTO)
                        GitHubUserDTO resolvedByDto = threadDto.resolvedBy();
                        if (resolvedByDto != null) {
                            User resolvedBy = userProcessor.ensureExists(
                                    resolvedByDto,
                                    Objects.requireNonNull(pullRequest
                                            .requireRepository()
                                            .getProvider()
                                            .getId()));
                            thread.setResolvedBy(resolvedBy);
                        }
                    } else {
                        thread.setState(PullRequestReviewThread.State.UNRESOLVED);
                    }

                    return threadRepository.save(thread);
                });
    }

    /**
     * Maps a diff side string to the entity Side enum.
     */
    private PullRequestReviewComment.@Nullable Side mapDiffSideString(@Nullable String diffSide) {
        if (diffSide == null) {
            return null;
        }
        return switch (diffSide) {
            case "LEFT" -> PullRequestReviewComment.Side.LEFT;
            case "RIGHT" -> PullRequestReviewComment.Side.RIGHT;
            default -> null;
        };
    }

    /**
     * Internal method to process a raw GraphQL review thread with an existing client.
     * <p>
     * This method is used internally by methods that fetch threads directly from GraphQL API
     * (syncCommentsForPullRequest, syncRemainingThreads) and need to process raw GraphQL types.
     *
     * @param graphQlThread the GraphQL review thread
     * @param pullRequest   the pull request the thread belongs to
     * @param client        the GraphQL client to use for nested pagination
     * @return number of comments synced from this thread
     */
    private int processThreadInternal(
            GHPullRequestReviewThread graphQlThread, PullRequest pullRequest, HttpGraphQlClient client, Long scopeId) {
        if (graphQlThread == null) {
            return 0;
        }

        GHPullRequestReviewCommentConnection commentsConnection = graphQlThread.getComments();
        if (commentsConnection == null || commentsConnection.getNodes() == null) {
            return 0;
        }

        // Handle nested pagination for thread comments
        var commentsPageInfo = commentsConnection.getPageInfo();
        if (commentsPageInfo != null && Boolean.TRUE.equals(commentsPageInfo.getHasNextPage())) {
            // Fetch all remaining comments using pagination
            fetchAllRemainingThreadComments(
                    graphQlThread.getId(), commentsConnection, commentsPageInfo.getEndCursor(), client, scopeId);
        }

        var graphQlComments = commentsConnection.getNodes();
        if (graphQlComments.isEmpty()) {
            return 0;
        }

        // Get the first comment to determine the thread ID (use its databaseId as thread ID)
        var firstComment = graphQlComments.get(0);
        Long threadId = extractDatabaseId(firstComment);
        if (threadId == null) {
            log.warn("Skipped thread: reason=missingDatabaseIdOnFirstComment, threadId={}", graphQlThread.getId());
            return 0;
        }

        // Create or update the thread, passing the first comment to set timestamps
        PullRequestReviewThread thread =
                getOrCreateThreadFromGraphQl(threadId, graphQlThread, pullRequest, firstComment);

        // Create processing context for sync operations to enable activity event creation
        ProcessingContext context = ProcessingContext.forSync(scopeId, pullRequest.requireRepository());

        int synced = 0;
        PullRequestReviewComment rootComment = null;
        for (var graphQlComment : graphQlComments) {
            GitHubReviewCommentDTO dto = convertToDTO(graphQlComment, thread);
            if (dto != null) {
                PullRequestReviewComment comment = commentProcessor.processCreated(
                        dto, pullRequest.requireRepository().getId(), pullRequest.getNumber(), context);
                if (comment != null) {
                    synced++;
                    // Track the first (root) comment
                    if (rootComment == null) {
                        rootComment = comment;
                    }
                }
            }
        }

        // Set the root comment on the thread if not already set
        if (rootComment != null && thread.getRootComment() == null) {
            thread.setRootComment(rootComment);
            threadRepository.save(thread);
        }

        return synced;
    }

    /**
     * Gets or creates a thread based on raw GraphQL data.
     * <p>
     * Used internally when processing threads fetched directly from GraphQL API.
     *
     * @param threadId      the thread ID (first comment's databaseId)
     * @param graphQlThread the GraphQL thread data
     * @param pullRequest   the pull request the thread belongs to
     * @param firstComment  the first comment in the thread (used for timestamps)
     * @return the created or existing thread
     */
    private PullRequestReviewThread getOrCreateThreadFromGraphQl(
            Long threadId,
            GHPullRequestReviewThread graphQlThread,
            PullRequest pullRequest,
            GHPullRequestReviewComment firstComment) {
        Long providerId = Objects.requireNonNull(
                pullRequest.requireRepository().getProvider().getId());
        return threadRepository
                .findByNativeIdAndProviderId(threadId, providerId)
                .orElseGet(() -> {
                    PullRequestReviewThread thread = new PullRequestReviewThread();
                    thread.setNativeId(threadId);
                    thread.setProvider(pullRequest.requireRepository().getProvider());
                    thread.setNodeId(graphQlThread.getId());
                    thread.setPullRequest(pullRequest);
                    thread.setPath(graphQlThread.getPath());
                    thread.setLine(graphQlThread.getLine());
                    thread.setStartLine(graphQlThread.getStartLine());
                    thread.setSide(mapDiffSide(graphQlThread.getDiffSide()));
                    thread.setStartSide(mapDiffSide(graphQlThread.getStartDiffSide()));
                    thread.setOutdated(graphQlThread.getIsOutdated());
                    thread.setCollapsed(graphQlThread.getIsCollapsed());

                    // Set timestamps from the first (root) comment
                    if (firstComment != null) {
                        if (firstComment.getCreatedAt() != null) {
                            thread.setCreatedAt(firstComment.getCreatedAt().toInstant());
                        }
                        if (firstComment.getUpdatedAt() != null) {
                            thread.setUpdatedAt(firstComment.getUpdatedAt().toInstant());
                        }
                    }

                    // Map resolved state and resolvedBy user
                    if (graphQlThread.getIsResolved()) {
                        thread.setState(PullRequestReviewThread.State.RESOLVED);
                        GHUser graphQlResolvedBy = graphQlThread.getResolvedBy();
                        if (graphQlResolvedBy != null) {
                            GitHubUserDTO resolvedByDto = GitHubUserDTO.fromUser(graphQlResolvedBy);
                            if (resolvedByDto != null) {
                                User resolvedBy = userProcessor.ensureExists(
                                        resolvedByDto,
                                        Objects.requireNonNull(pullRequest
                                                .requireRepository()
                                                .getProvider()
                                                .getId()));
                                thread.setResolvedBy(resolvedBy);
                            }
                        }
                    } else {
                        thread.setState(PullRequestReviewThread.State.UNRESOLVED);
                    }

                    return threadRepository.save(thread);
                });
    }

    /**
     * Extracts the database ID from a GraphQL comment.
     * Uses fullDatabaseId if available, otherwise falls back to deprecated databaseId.
     */
    private @Nullable Long extractDatabaseId(@Nullable GHPullRequestReviewComment graphQlComment) {
        if (graphQlComment == null) {
            return null;
        }

        // Prefer fullDatabaseId (BigInteger) over deprecated databaseId (Integer)
        BigInteger fullDbId = graphQlComment.getFullDatabaseId();
        if (fullDbId != null) {
            return fullDbId.longValue();
        }

        Integer dbId = graphQlComment.getDatabaseId();
        if (dbId != null) {
            return dbId.longValue();
        }

        return null;
    }

    /**
     * Converts a GraphQL GHPullRequestReviewComment to a GitHubReviewCommentDTO.
     *
     * @param graphQlComment the GraphQL review comment
     * @param thread         the thread this comment belongs to
     * @return the DTO for processing, or null if databaseId is missing
     */
    private @Nullable GitHubReviewCommentDTO convertToDTO(
            GHPullRequestReviewComment graphQlComment, PullRequestReviewThread thread) {
        if (graphQlComment == null) {
            return null;
        }

        Long databaseId = extractDatabaseId(graphQlComment);
        if (databaseId == null) {
            log.warn("Skipped comment with missing databaseId: nodeId={}", graphQlComment.getId());
            return null;
        }

        // Convert author - use fromActor to handle all GHActor types (User, Bot, Mannequin, Organization)
        GitHubUserDTO author = GitHubUserDTO.fromActor(graphQlComment.getAuthor());

        // Convert author association
        String authorAssociation = convertAuthorAssociation(graphQlComment.getAuthorAssociation());

        // Get review ID if present - prefer fullDatabaseId over deprecated databaseId
        Long reviewId = null;
        if (graphQlComment.getPullRequestReview() != null) {
            BigInteger reviewFullDbId = graphQlComment.getPullRequestReview().getFullDatabaseId();
            if (reviewFullDbId != null) {
                reviewId = reviewFullDbId.longValue();
            } else {
                Integer reviewDbId = graphQlComment.getPullRequestReview().getDatabaseId();
                if (reviewDbId != null) {
                    reviewId = reviewDbId.longValue();
                }
            }
        }

        // Get reply-to ID if present - prefer fullDatabaseId over deprecated databaseId
        Long inReplyToId = null;
        if (graphQlComment.getReplyTo() != null) {
            BigInteger replyToFullDbId = graphQlComment.getReplyTo().getFullDatabaseId();
            if (replyToFullDbId != null) {
                inReplyToId = replyToFullDbId.longValue();
            } else {
                Integer replyToDbId = graphQlComment.getReplyTo().getDatabaseId();
                if (replyToDbId != null) {
                    inReplyToId = replyToDbId.longValue();
                }
            }
        }

        // GraphQL comment doesn't have direct side field, use thread's side
        String side =
                (thread != null && thread.getSide() != null) ? thread.getSide().name() : null;

        // Extract htmlUrl from GraphQL url field
        String htmlUrl =
                graphQlComment.getUrl() != null ? graphQlComment.getUrl().toString() : null;

        return new GitHubReviewCommentDTO(
                databaseId, // id
                graphQlComment.getId(), // nodeId
                graphQlComment.getDiffHunk(), // diffHunk
                graphQlComment.getPath(), // path
                graphQlComment.getBody(), // body
                htmlUrl, // htmlUrl - from GraphQL url field
                author, // author
                graphQlComment.getCreatedAt() != null
                        ? graphQlComment.getCreatedAt().toInstant()
                        : null, // createdAt
                graphQlComment.getUpdatedAt() != null
                        ? graphQlComment.getUpdatedAt().toInstant()
                        : null, // updatedAt
                reviewId, // reviewId
                graphQlComment.getCommit() != null ? graphQlComment.getCommit().getOid() : null, // commitId
                graphQlComment.getOriginalCommit() != null
                        ? graphQlComment.getOriginalCommit().getOid()
                        : null, // originalCommitId
                authorAssociation, // authorAssociation
                graphQlComment.getLine(), // line
                graphQlComment.getOriginalLine(), // originalLine
                graphQlComment.getStartLine(), // startLine
                graphQlComment.getOriginalStartLine(), // originalStartLine
                side, // side
                null, // startSide
                inReplyToId, // inReplyToId
                graphQlComment.getOutdated() // outdated
                );
    }

    /**
     * Converts a GraphQL GHCommentAuthorAssociation to its string representation.
     *
     * @param association the GraphQL author association
     * @return the string representation
     */
    private String convertAuthorAssociation(GHCommentAuthorAssociation association) {
        if (association == null) {
            return "NONE";
        }
        return association.name();
    }

    /**
     * Maps a GraphQL GHDiffSide to the entity Side enum.
     */
    private PullRequestReviewComment.@Nullable Side mapDiffSide(@Nullable GHDiffSide diffSide) {
        if (diffSide == null) {
            return null;
        }
        return switch (diffSide) {
            case LEFT -> PullRequestReviewComment.Side.LEFT;
            case RIGHT -> PullRequestReviewComment.Side.RIGHT;
        };
    }

    /**
     * Synchronizes remaining review threads for a pull request, starting from the given cursor.
     * <p>
     * This method is called by GitHubPullRequestSyncService when a PR has more than 10 review threads
     * (the embedded limit in GetRepositoryPullRequests query). It continues pagination from where
     * the embedded threads left off, avoiding re-fetching already synced threads.
     *
     * @param scopeId     the scope ID for authentication
     * @param pullRequest the pull request to fetch remaining threads for
     * @param startCursor the pagination cursor to start from (from embedded threads)
     * @return number of additional comments synced
     */
    @Transactional
    public int syncRemainingThreads(Long scopeId, PullRequest pullRequest, String startCursor) {
        if (pullRequest == null) {
            log.warn("Skipped remaining thread sync: reason=prIsNull");
            return 0;
        }

        Repository repository = pullRequest.requireRepository();
        if (repository == null) {
            log.warn("Skipped remaining thread sync: reason=prHasNoRepository, prId={}", pullRequest.getId());
            return 0;
        }

        String safeNameWithOwner = sanitizeForLog(repository.getNameWithOwner());
        Optional<RepositoryOwnerAndName> parsedName = GitHubRepositoryNameParser.parse(repository.getNameWithOwner());
        if (parsedName.isEmpty()) {
            log.warn("Skipped remaining thread sync: reason=invalidRepoNameFormat, repoName={}", safeNameWithOwner);
            return 0;
        }
        String owner = parsedName.get().owner();
        String name = parsedName.get().name();

        HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

        log.debug(
                "Starting remaining thread sync: repoName={}, prNumber={}, startCursor={}",
                safeNameWithOwner,
                pullRequest.getNumber(),
                startCursor != null ? startCursor.substring(0, Math.min(20, startCursor.length())) + "..." : "null");

        try {
            int totalSynced = 0;
            String cursor = startCursor;
            boolean hasNextPage = true;
            int pageCount = 0;

            while (hasNextPage) {
                // Check for interrupt (e.g., during application shutdown)
                if (Thread.interrupted()) {
                    log.info(
                            "Remaining thread sync interrupted (shutdown requested): repoName={}, prNumber={}, pageCount={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            pageCount);
                    Thread.currentThread().interrupt();
                    break;
                }

                pageCount++;
                if (pageCount >= MAX_PAGINATION_PAGES) {
                    log.warn(
                            "Reached maximum pagination limit for remaining thread sync: repoName={}, prNumber={}, limit={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            MAX_PAGINATION_PAGES);
                    break;
                }

                final String currentCursor = cursor;
                final int currentPage = pageCount;

                ClientGraphQlResponse graphQlResponse = Mono.defer(
                                () -> client.documentName(GET_PR_REVIEW_COMMENTS_DOCUMENT)
                                        .variable("owner", owner)
                                        .variable("name", name)
                                        .variable("number", pullRequest.getNumber())
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
                                        "Retrying remaining thread sync after transport error: prNumber={}, page={}, attempt={}, error={}",
                                        pullRequest.getNumber(),
                                        currentPage,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(syncProperties.extendedGraphqlTimeout());

                if (graphQlResponse == null || !graphQlResponse.isValid()) {
                    log.warn(
                            "Received invalid GraphQL response: repoName={}, prNumber={}, errors={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            graphQlResponse != null ? graphQlResponse.getErrors() : "null");
                    break;
                }

                // Track rate limit from response
                graphQlClientProvider.trackRateLimit(scopeId, graphQlResponse);

                // Check if we should pause due to rate limiting
                if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                    log.warn(
                            "Aborting remaining thread sync due to critical rate limit: repoName={}, prNumber={}",
                            safeNameWithOwner,
                            pullRequest.getNumber());
                    break;
                }

                GHPullRequestReviewThreadConnection response = graphQlResponse
                        .field("repository.pullRequest.reviewThreads")
                        .toEntity(GHPullRequestReviewThreadConnection.class);

                if (response == null || response.getNodes() == null) {
                    log.warn(
                            "Remaining thread sync stopped on empty response: repoName={}, prNumber={}",
                            safeNameWithOwner,
                            pullRequest.getNumber());
                    break;
                }

                for (var graphQlThread : response.getNodes()) {
                    int synced = processThreadInternal(graphQlThread, pullRequest, client, scopeId);
                    totalSynced += synced;
                }

                var pageInfo = response.getPageInfo();
                hasNextPage = pageInfo != null && Boolean.TRUE.equals(pageInfo.getHasNextPage());
                cursor = pageInfo != null ? pageInfo.getEndCursor() : null;
            }

            // No overflow check here: this continuation only covers the pages after startCursor, so
            // comparing its partial count against the full reviewThreads.totalCount is meaningless.
            // Early stops are already logged at their break sites above.

            log.debug(
                    "Completed remaining thread sync: repoName={}, prNumber={}, additionalComments={}",
                    safeNameWithOwner,
                    pullRequest.getNumber(),
                    totalSynced);
            return totalSynced;
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (FieldAccessException e) {
            if (isNotFoundError(e.getResponse(), "repository.pullRequest")) {
                log.debug(
                        "Skipped remaining thread sync: reason=prDeletedFromGitHub, repoName={}, prNumber={}",
                        safeNameWithOwner,
                        pullRequest.getNumber());
                return 0;
            }
            log.error(
                    "Failed to sync remaining threads: repoName={}, prNumber={}",
                    safeNameWithOwner,
                    pullRequest.getNumber(),
                    e);
            return 0;
        } catch (Exception e) {
            ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
            switch (classification.category()) {
                case RATE_LIMITED ->
                    log.warn(
                            "Rate limited during remaining thread sync: repoName={}, prNumber={}, message={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            classification.message());
                case NOT_FOUND ->
                    log.warn(
                            "Resource not found during remaining thread sync: repoName={}, prNumber={}, message={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            classification.message());
                case AUTH_ERROR -> {
                    log.error(
                            "Authentication error during remaining thread sync: repoName={}, prNumber={}, message={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            classification.message());
                    throw e;
                }
                case RETRYABLE ->
                    log.warn(
                            "Retryable error during remaining thread sync: repoName={}, prNumber={}, message={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            classification.message());
                default ->
                    log.error(
                            "Unexpected error during remaining thread sync: repoName={}, prNumber={}, message={}",
                            safeNameWithOwner,
                            pullRequest.getNumber(),
                            classification.message(),
                            e);
            }
            return 0;
        }
    }
}
