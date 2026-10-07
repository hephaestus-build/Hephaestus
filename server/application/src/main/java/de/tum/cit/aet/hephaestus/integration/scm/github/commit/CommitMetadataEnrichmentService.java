package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;

import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlConnectionOverflowDetector;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlFragments;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Enriches commits with multi-author contributor data and associated pull request links.
 * <p>
 * <b>Contributors (R1):</b> Fetches all authors from GitHub's {@code Commit.authors(first:10)}
 * GraphQL field, which automatically parses Co-authored-by trailers from squash merge commit
 * messages. Stores each author as a {@link CommitContributor} row with role AUTHOR (ordinal 0
 * for primary) or CO_AUTHOR (ordinal 1+ for co-authors).
 * <p>
 * <b>PR Links (R2):</b> Fetches associated pull requests from GitHub's
 * {@code Commit.associatedPullRequests(first:10)} GraphQL field and stores them in the
 * {@code commit_pull_request} join table.
 * <p>
 * <b>Overflow Handling:</b> Both connections include {@code pageInfo} in the query.
 * When a commit has more authors or associated PRs than the initial page fetch,
 * overflow records are collected during batch processing and individual follow-up
 * queries are issued per overflowed commit after all batches complete. This follows
 * the same two-phase pattern used by {@code GitHubPullRequestSyncService} for
 * reviews, threads, and project items.
 * <p>
 * This service runs separately from {@link CommitAuthorEnrichmentService} because it targets
 * all commits lacking contributor rows (not just those with unresolved emails).
 * <p>
 * <b>Why buildBatchQuery assembles a string:</b> the aliases
 * ({@code commit0: object(oid:"..."), commit1: object(oid:"...")}) are syntax, not values — no GraphQL
 * variable can stand in for an alias name, and the count varies per batch — so the envelope cannot be a
 * static document. Only the envelope is assembled: the per-commit selection set lives in
 * {@code graphql/github/fragments/CommitEnrichmentFields.graphql} and is appended verbatim, which is
 * what keeps every field name under the schema validation a checked-in document gets. SHA values are
 * validated against {@link #SHA_PATTERN} before interpolation to prevent injection.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommitMetadataEnrichmentService {

    /** Timeout for batch GraphQL queries (multiple commits per request). */
    private static final Duration GRAPHQL_BATCH_TIMEOUT = Duration.ofSeconds(60);

    /** Timeout for single-commit follow-up GraphQL queries. */
    private static final Duration GRAPHQL_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Maximum number of commit SHAs to batch in a single GraphQL query.
     */
    private static final int BATCH_SIZE = 50;

    /** Maximum retry attempts for GraphQL error classification. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Initial page size for the authors connection. Most commits have 1-3 authors.
     *
     * <p>Must equal the {@code authors(first:)} argument in {@code CommitEnrichmentFields.graphql}: the
     * overflow follow-up starts counting from this number, so a disagreement silently re-fetches or skips
     * authors. Pinned by {@code CommitMetadataEnrichmentServiceQueryTest}.
     */
    static final int AUTHORS_PAGE_SIZE = 10;

    /**
     * Initial page size for the associated pull requests connection. Most commits link to 1-5 PRs.
     *
     * <p>Must equal the {@code associatedPullRequests(first:)} argument in the fragment, for the same
     * reason as {@link #AUTHORS_PAGE_SIZE}.
     */
    static final int ASSOCIATED_PRS_PAGE_SIZE = 10;

    private static final String COMMIT_ENRICHMENT_FRAGMENT = loadCommitEnrichmentFragment();

    /** Maximum pages to fetch in follow-up pagination to prevent runaway loops. */
    private static final int MAX_FOLLOW_UP_PAGES = 10;

    /** Pattern to validate SHA-1 hex strings. */
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{40}$");

    /** Type reference for deserializing GraphQL fields as {@code Map<String, Object>} without unchecked casts. */
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE_REF =
            new ParameterizedTypeReference<>() {};

    private final CommitRepository commitRepository;
    private final CommitContributorRepository contributorRepository;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubGraphQlSyncCoordinator graphQlSyncCoordinator;
    private final GitHubExceptionClassifier exceptionClassifier;

    /**
     * Tracks a commit whose {@code authors} connection overflowed the initial page.
     * Stores the commit database ID, SHA (for the follow-up query), and the end cursor
     * to continue pagination from.
     */
    private record CommitWithAuthorCursor(Long commitId, String sha, String endCursor) {}

    /**
     * Tracks a commit whose {@code associatedPullRequests} connection overflowed the initial page.
     * Stores the commit database ID, SHA (for the follow-up query), repository ID (for PR linking),
     * and the end cursor to continue pagination from.
     */
    private record CommitWithPrCursor(Long commitId, String sha, Long repositoryId, String endCursor) {}

    /**
     * Result of processing a single batch, carrying both the count of processed commits
     * and any overflow records that need follow-up pagination.
     */
    private record BatchProcessingResult(
            int processedCount, List<CommitWithAuthorCursor> authorOverflows, List<CommitWithPrCursor> prOverflows) {}

    /**
     * Enriches commits with contributor data and PR links for a repository.
     * <p>
     * Finds commits that have no contributor rows yet (indicating they haven't been
     * enriched for multi-author data), fetches authors and associated PRs via GraphQL,
     * and upserts the results. After all batches complete, issues individual follow-up
     * queries for any commits whose connections overflowed the initial page.
     *
     * @param repositoryId  the repository database ID
     * @param nameWithOwner the repository name with owner (e.g. "owner/repo")
     * @param scopeId       the scope ID for GraphQL client authentication
     * @return the number of commits enriched
     */
    public int enrichCommitMetadata(Long repositoryId, String nameWithOwner, @Nullable Long scopeId) {
        if (scopeId == null) {
            log.debug("Skipping commit metadata enrichment: reason=noScopeId, repoId={}", repositoryId);
            return 0;
        }

        // Find SHAs of commits without any contributor rows
        List<String> unenrichedShas = commitRepository.findShasWithoutContributorsByRepositoryId(repositoryId);

        if (unenrichedShas.isEmpty()) {
            log.debug("No commits need metadata enrichment: repoId={}", repositoryId);
            return 0;
        }

        // Validate SHAs
        List<String> validShas = unenrichedShas.stream()
                .filter(sha -> SHA_PATTERN.matcher(sha).matches())
                .toList();

        if (validShas.isEmpty()) {
            return 0;
        }

        log.debug(
                "Enriching {} commits with metadata: repoId={}, repo={}",
                validShas.size(),
                repositoryId,
                nameWithOwner);

        String[] parts = nameWithOwner.split("/", 2);
        if (parts.length != 2) {
            log.warn("Invalid nameWithOwner format: {}", nameWithOwner);
            return 0;
        }
        String owner = parts[0];
        String repoName = parts[1];

        int enriched = 0;
        List<CommitWithAuthorCursor> allAuthorOverflows = new ArrayList<>();
        List<CommitWithPrCursor> allPrOverflows = new ArrayList<>();

        // Phase 1: Process all batches, collecting overflow records
        for (int batchStart = 0; batchStart < validShas.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, validShas.size());
            List<String> batch = validShas.subList(batchStart, batchEnd);

            // Check rate limit before each batch
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncCoordinator.waitForRateLimitIfNeeded(
                        scopeId, "commit metadata enrichment", "repo", nameWithOwner, log)) {
                    log.warn(
                            "Aborting commit metadata enrichment due to rate limit: repo={}, processed={}/{}",
                            nameWithOwner,
                            batchStart,
                            validShas.size());
                    break;
                }
            }

            BatchProcessingResult result =
                    fetchAndProcessBatch(owner, repoName, scopeId, batch, repositoryId, nameWithOwner);
            enriched += result.processedCount();
            allAuthorOverflows.addAll(result.authorOverflows());
            allPrOverflows.addAll(result.prOverflows());
        }

        // Phase 2: Follow-up pagination for overflowed author connections
        if (!allAuthorOverflows.isEmpty()) {
            log.debug(
                    "Starting follow-up author pagination: repo={}, commitCount={}",
                    nameWithOwner,
                    allAuthorOverflows.size());
            for (CommitWithAuthorCursor overflow : allAuthorOverflows) {
                fetchRemainingAuthors(owner, repoName, scopeId, overflow, nameWithOwner);
            }
        }

        // Phase 3: Follow-up pagination for overflowed associatedPullRequests connections
        if (!allPrOverflows.isEmpty()) {
            log.debug(
                    "Starting follow-up associatedPullRequests pagination: repo={}, commitCount={}",
                    nameWithOwner,
                    allPrOverflows.size());
            for (CommitWithPrCursor overflow : allPrOverflows) {
                fetchRemainingPullRequests(owner, repoName, scopeId, overflow, nameWithOwner);
            }
        }

        log.info(
                "Completed commit metadata enrichment: repoId={}, enriched={}/{}, authorOverflows={}, prOverflows={}",
                repositoryId,
                enriched,
                validShas.size(),
                allAuthorOverflows.size(),
                allPrOverflows.size());
        return enriched;
    }

    /**
     * Fetches a single batch of commit metadata via GraphQL and processes the results.
     *
     * @return a {@link BatchProcessingResult} with the count and any overflow records
     */
    private BatchProcessingResult fetchAndProcessBatch(
            String owner, String repoName, Long scopeId, List<String> batch, Long repositoryId, String nameWithOwner) {
        String queryString = buildBatchQuery(owner, repoName, batch);
        BatchProcessingResult emptyResult = new BatchProcessingResult(0, List.of(), List.of());

        int retryAttempt = 0;
        while (retryAttempt <= MAX_RETRY_ATTEMPTS) {
            boolean pendingPermit = false;
            try {
                graphQlClientProvider.acquirePermission();
                pendingPermit = true;

                var client = graphQlClientProvider.forScope(scopeId);
                ClientGraphQlResponse response = Mono.defer(
                                () -> client.document(queryString).execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying commit metadata enrichment after transport error: repo={}, attempt={}, error={}",
                                        nameWithOwner,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(GRAPHQL_BATCH_TIMEOUT);

                if (response == null || !response.isValid()) {
                    pendingPermit = false;
                    graphQlClientProvider.recordFailure(new IllegalStateException("Invalid GraphQL response"));
                    ClassificationResult classification = graphQlSyncCoordinator.classifyGraphQlErrors(response);
                    if (classification != null) {
                        if (graphQlSyncCoordinator.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "commit metadata enrichment",
                                "repo",
                                nameWithOwner,
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        break;
                    }
                    log.warn(
                            "Invalid GraphQL response for commit metadata enrichment: repo={}, errors={}",
                            nameWithOwner,
                            response != null ? response.getErrors() : "null");
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, response);
                pendingPermit = false;
                graphQlClientProvider.recordSuccess();

                return processResponse(response, batch, repositoryId, nameWithOwner);
            } catch (Exception e) {
                if (pendingPermit) {
                    graphQlClientProvider.recordFailure(e);
                }

                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                if (graphQlSyncCoordinator.handleGraphQlClassification(new GraphQlClassificationContext(
                        classification,
                        retryAttempt,
                        MAX_RETRY_ATTEMPTS,
                        "commit metadata enrichment",
                        "repo",
                        nameWithOwner,
                        log))) {
                    retryAttempt++;
                    continue;
                }
                break;
            }
        }

        return emptyResult;
    }

    /**
     * Builds the batched GraphQL query: one {@code commitN} alias per SHA, each spreading the checked-in
     * {@code CommitEnrichmentFields} fragment, with the fragment text appended once at the end.
     *
     * <p>Static and package-private so the assembled string can be parsed and validated against the
     * vendor schema in a unit test — the one part of this request that no {@code .graphql} document can
     * cover, because the alias count is a runtime value.
     */
    static String buildBatchQuery(String owner, String repoName, List<String> shas) {
        StringBuilder sb = new StringBuilder();
        sb.append("query {\n");
        sb.append("  rateLimit { cost limit remaining resetAt }\n");
        sb.append("  repository(owner: \"")
                .append(owner)
                .append("\", name: \"")
                .append(repoName)
                .append("\") {\n");

        for (int i = 0; i < shas.size(); i++) {
            sb.append("    commit")
                    .append(i)
                    .append(": object(oid: \"")
                    .append(shas.get(i))
                    .append("\") { ...")
                    .append(GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_NAME)
                    .append(" }\n");
        }

        sb.append("  }\n");
        sb.append("}\n");
        sb.append(commitEnrichmentFragment());
        return sb.toString();
    }

    /**
     * The fragment document, read once. A failure here means the resource is missing from the packaged
     * jar, which would otherwise surface as a GraphQL validation error from GitHub on every batch.
     */
    private static String loadCommitEnrichmentFragment() {
        ClassPathResource resource = new ClassPathResource(GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Missing GraphQL fragment " + GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE, e);
        }
    }

    static String commitEnrichmentFragment() {
        return COMMIT_ENRICHMENT_FRAGMENT;
    }

    /**
     * Processes a GraphQL response, upserting contributors, PR links, and enrichment metadata.
     * Collects overflow records for connections that need follow-up pagination.
     *
     * @return a {@link BatchProcessingResult} with the count and overflow records
     */
    private BatchProcessingResult processResponse(
            ClientGraphQlResponse response, List<String> batch, Long repositoryId, String nameWithOwner) {
        int processed = 0;
        List<CommitWithAuthorCursor> authorOverflows = new ArrayList<>();
        List<CommitWithPrCursor> prOverflows = new ArrayList<>();

        for (int i = 0; i < batch.size(); i++) {
            String sha = batch.get(i);
            String fieldPath = "repository.commit" + i;

            try {
                ClientResponseField field = response.field(fieldPath);
                if (field.getValue() == null) {
                    log.debug("Commit not found on GitHub for metadata enrichment: sha={}", sha);
                    continue;
                }

                Map<String, Object> commitData = field.toEntity(MAP_TYPE_REF);
                if (commitData == null) {
                    continue;
                }

                // Look up commit ID from database
                var commitOpt = commitRepository.findByShaAndRepositoryId(sha, repositoryId);
                if (commitOpt.isEmpty()) {
                    log.debug("Commit not found in database: sha={}, repoId={}", sha, repositoryId);
                    continue;
                }
                Long commitId = commitOpt.get().getId();

                String context = "commit " + sha.substring(0, 7) + " in " + nameWithOwner;

                // Process authors and detect overflow
                CommitWithAuthorCursor authorOverflow = processAuthors(commitData, commitId, sha, context);
                if (authorOverflow != null) {
                    authorOverflows.add(authorOverflow);
                }

                // Process committer
                processCommitter(commitData, commitId);

                // Process associated PRs and detect overflow
                CommitWithPrCursor prOverflow =
                        processAssociatedPullRequests(commitData, commitId, sha, repositoryId, context);
                if (prOverflow != null) {
                    prOverflows.add(prOverflow);
                }

                // Update enrichment metadata (free data fields)
                updateEnrichmentMetadata(commitData, commitId);

                processed++;
            } catch (Exception e) {
                log.debug("Failed to process metadata for commit: sha={}, error={}", sha, e.getMessage());
            }
        }

        return new BatchProcessingResult(processed, authorOverflows, prOverflows);
    }

    /**
     * Extracts authors from the GraphQL response and upserts them as CommitContributor rows.
     *
     * @return a {@link CommitWithAuthorCursor} if the connection overflowed, null otherwise
     */
    @Nullable
    private CommitWithAuthorCursor processAuthors(
            Map<String, Object> commitData, Long commitId, String sha, String context) {
        Object authorsObj = commitData.get("authors");
        if (!(authorsObj instanceof Map<?, ?> authorsMap)) {
            return null;
        }

        Integer totalCount = extractInteger(authorsMap.get("totalCount"));
        Object nodesObj = authorsMap.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) {
            return null;
        }

        // Detect overflow using centralized detector
        boolean overflowed = false;
        if (totalCount != null) {
            overflowed = GraphQlConnectionOverflowDetector.check("authors", nodes.size(), totalCount, context);
        }

        // Upsert all authors from this page
        for (int ordinal = 0; ordinal < nodes.size(); ordinal++) {
            Object nodeObj = nodes.get(ordinal);
            if (!(nodeObj instanceof Map<?, ?> authorNode)) {
                continue;
            }

            String email = normalizeString(authorNode.get("email"));
            String name = normalizeString(authorNode.get("name"));
            if (email == null) {
                continue;
            }

            // Resolve user ID if available
            Long userId = extractUserId(authorNode);

            String role = ordinal == 0 ? CommitContributor.Role.AUTHOR.name() : CommitContributor.Role.CO_AUTHOR.name();

            contributorRepository.upsertContributor(commitId, userId, role, name, email, ordinal);
        }

        // Return overflow record if pagination is needed
        if (overflowed) {
            String endCursor = extractEndCursor(authorsMap);
            if (endCursor != null) {
                return new CommitWithAuthorCursor(commitId, sha, endCursor);
            }
        }

        return null;
    }

    /**
     * Extracts the committer from the GraphQL response and upserts as a COMMITTER contributor.
     */
    private void processCommitter(Map<String, Object> commitData, Long commitId) {
        Object committerObj = commitData.get("committer");
        if (!(committerObj instanceof Map<?, ?> committerMap)) {
            return;
        }

        String email = normalizeString(committerMap.get("email"));
        String name = normalizeString(committerMap.get("name"));
        if (email == null) {
            return;
        }

        Long userId = extractUserId(committerMap);

        contributorRepository.upsertContributor(
                commitId, userId, CommitContributor.Role.COMMITTER.name(), name, email, 0);
    }

    /**
     * Extracts associated PR numbers and links them to the commit.
     *
     * @return a {@link CommitWithPrCursor} if the connection overflowed, null otherwise
     */
    @Nullable
    private CommitWithPrCursor processAssociatedPullRequests(
            Map<String, Object> commitData, Long commitId, String sha, Long repositoryId, String context) {
        Object prObj = commitData.get("associatedPullRequests");
        if (!(prObj instanceof Map<?, ?> prMap)) {
            return null;
        }

        Integer totalCount = extractInteger(prMap.get("totalCount"));
        Object nodesObj = prMap.get("nodes");
        if (!(nodesObj instanceof List<?> nodes)) {
            return null;
        }

        // Detect overflow using centralized detector
        boolean overflowed = false;
        if (totalCount != null) {
            overflowed = GraphQlConnectionOverflowDetector.check(
                    "associatedPullRequests", nodes.size(), totalCount, context);
        }

        // Link all PRs from this page
        List<Integer> prNumbers = new ArrayList<>();
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map<?, ?> prNode)) {
                continue;
            }
            Object numberObj = prNode.get("number");
            if (numberObj instanceof Number num) {
                prNumbers.add(num.intValue());
            }
        }

        if (!prNumbers.isEmpty()) {
            commitRepository.linkCommitToPullRequests(commitId, repositoryId, prNumbers);
        }

        // Return overflow record if pagination is needed
        if (overflowed) {
            String endCursor = extractEndCursor(prMap);
            if (endCursor != null) {
                return new CommitWithPrCursor(commitId, sha, repositoryId, endCursor);
            }
        }

        return null;
    }

    // Follow-up pagination methods

    /**
     * Fetches remaining authors for a single commit whose authors connection overflowed.
     * Issues cursor-based paginated queries until all authors are fetched.
     */
    private void fetchRemainingAuthors(
            String owner, String repoName, Long scopeId, CommitWithAuthorCursor overflow, String nameWithOwner) {
        String cursor = overflow.endCursor();
        int totalFetched = AUTHORS_PAGE_SIZE; // Already fetched this many in the batch query
        int page = 0;

        while (cursor != null && page < MAX_FOLLOW_UP_PAGES) {
            page++;

            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncCoordinator.waitForRateLimitIfNeeded(
                        scopeId, "commit author follow-up", "commit", overflow.sha(), log)) {
                    log.warn(
                            "Aborting author follow-up pagination due to rate limit: sha={}, fetched={}",
                            overflow.sha(),
                            totalFetched);
                    return;
                }
            }

            String query = buildSingleCommitAuthorsQuery(owner, repoName, overflow.sha(), cursor);
            Map<String, Object> authorsConnection = executeSingleCommitFollowUp(
                    query,
                    "object.authors",
                    scopeId,
                    nameWithOwner,
                    "author follow-up for " + overflow.sha().substring(0, 7));

            if (authorsConnection == null) {
                break;
            }

            Object nodesObj = authorsConnection.get("nodes");
            if (!(nodesObj instanceof List<?> nodes) || nodes.isEmpty()) {
                break;
            }

            // Upsert remaining authors with incrementing ordinals
            for (int i = 0; i < nodes.size(); i++) {
                Object nodeObj = nodes.get(i);
                if (!(nodeObj instanceof Map<?, ?> authorNode)) {
                    continue;
                }

                String email = normalizeString(authorNode.get("email"));
                String name = normalizeString(authorNode.get("name"));
                if (email == null) {
                    continue;
                }

                Long userId = extractUserId(authorNode);
                int ordinal = totalFetched + i;
                String role = CommitContributor.Role.CO_AUTHOR.name(); // All follow-up authors are co-authors

                contributorRepository.upsertContributor(overflow.commitId(), userId, role, name, email, ordinal);
            }

            totalFetched += nodes.size();

            // Check if there are more pages
            if (hasNextPage(authorsConnection)) {
                cursor = extractEndCursor(authorsConnection);
            } else {
                cursor = null;
            }
        }

        if (page >= MAX_FOLLOW_UP_PAGES && cursor != null) {
            log.warn(
                    "Hit max follow-up pages for commit authors: sha={}, pages={}, totalFetched={}",
                    overflow.sha(),
                    page,
                    totalFetched);
        }
    }

    /**
     * Fetches remaining associated pull requests for a single commit whose connection overflowed.
     * Issues cursor-based paginated queries until all PRs are fetched.
     */
    private void fetchRemainingPullRequests(
            String owner, String repoName, Long scopeId, CommitWithPrCursor overflow, String nameWithOwner) {
        String cursor = overflow.endCursor();
        int totalFetched = ASSOCIATED_PRS_PAGE_SIZE; // Already fetched this many in the batch query
        int page = 0;

        while (cursor != null && page < MAX_FOLLOW_UP_PAGES) {
            page++;

            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncCoordinator.waitForRateLimitIfNeeded(
                        scopeId, "commit PR follow-up", "commit", overflow.sha(), log)) {
                    log.warn(
                            "Aborting PR follow-up pagination due to rate limit: sha={}, fetched={}",
                            overflow.sha(),
                            totalFetched);
                    return;
                }
            }

            String query = buildSingleCommitPrsQuery(owner, repoName, overflow.sha(), cursor);
            Map<String, Object> prsConnection = executeSingleCommitFollowUp(
                    query,
                    "object.associatedPullRequests",
                    scopeId,
                    nameWithOwner,
                    "PR follow-up for " + overflow.sha().substring(0, 7));

            if (prsConnection == null) {
                break;
            }

            Object nodesObj = prsConnection.get("nodes");
            if (!(nodesObj instanceof List<?> nodes) || nodes.isEmpty()) {
                break;
            }

            // Link remaining PRs
            List<Integer> prNumbers = new ArrayList<>();
            for (Object nodeObj : nodes) {
                if (!(nodeObj instanceof Map<?, ?> prNode)) {
                    continue;
                }
                Object numberObj = prNode.get("number");
                if (numberObj instanceof Number num) {
                    prNumbers.add(num.intValue());
                }
            }

            if (!prNumbers.isEmpty()) {
                commitRepository.linkCommitToPullRequests(overflow.commitId(), overflow.repositoryId(), prNumbers);
            }

            totalFetched += nodes.size();

            // Check if there are more pages
            if (hasNextPage(prsConnection)) {
                cursor = extractEndCursor(prsConnection);
            } else {
                cursor = null;
            }
        }

        if (page >= MAX_FOLLOW_UP_PAGES && cursor != null) {
            log.warn(
                    "Hit max follow-up pages for commit PRs: sha={}, pages={}, totalFetched={}",
                    overflow.sha(),
                    page,
                    totalFetched);
        }
    }

    /**
     * Builds a GraphQL query to fetch a single commit's remaining authors after a cursor.
     */
    private static String buildSingleCommitAuthorsQuery(String owner, String repoName, String sha, String cursor) {
        return ("query {\n" + "  rateLimit { cost limit remaining resetAt }\n"
                + "  repository(owner: \""
                + owner
                + "\", name: \""
                + repoName
                + "\") {\n"
                + "    object(oid: \""
                + sha
                + "\") {\n"
                + "      ... on Commit {\n"
                + "        authors(first: "
                + AUTHORS_PAGE_SIZE
                + ", after: \""
                + cursor
                + "\") {\n"
                + "          totalCount\n"
                + "          pageInfo { hasNextPage endCursor }\n"
                + "          nodes {\n"
                + "            name\n"
                + "            email\n"
                + "            user { login databaseId }\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n");
    }

    /**
     * Builds a GraphQL query to fetch a single commit's remaining associated pull requests after a cursor.
     */
    private static String buildSingleCommitPrsQuery(String owner, String repoName, String sha, String cursor) {
        return ("query {\n" + "  rateLimit { cost limit remaining resetAt }\n"
                + "  repository(owner: \""
                + owner
                + "\", name: \""
                + repoName
                + "\") {\n"
                + "    object(oid: \""
                + sha
                + "\") {\n"
                + "      ... on Commit {\n"
                + "        associatedPullRequests(first: "
                + ASSOCIATED_PRS_PAGE_SIZE
                + ", after: \""
                + cursor
                + "\") {\n"
                + "          totalCount\n"
                + "          pageInfo { hasNextPage endCursor }\n"
                + "          nodes {\n"
                + "            number\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n");
    }

    /**
     * Executes a single-commit follow-up GraphQL query and extracts the connection field.
     *
     * @param queryString   the GraphQL query document
     * @param connectionPath the dot-separated path to the connection within {@code repository} (e.g. "object.authors")
     * @param scopeId       the scope ID for authentication
     * @param nameWithOwner the repo name for logging
     * @param description   human-readable description for logging
     * @return the connection as a Map, or null if the query failed
     */
    @Nullable
    private Map<String, Object> executeSingleCommitFollowUp(
            String queryString, String connectionPath, Long scopeId, String nameWithOwner, String description) {
        int retryAttempt = 0;
        while (retryAttempt <= MAX_RETRY_ATTEMPTS) {
            boolean pendingPermit = false;
            try {
                graphQlClientProvider.acquirePermission();
                pendingPermit = true;

                var client = graphQlClientProvider.forScope(scopeId);
                ClientGraphQlResponse response = Mono.defer(
                                () -> client.document(queryString).execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying {}: repo={}, attempt={}, error={}",
                                        description,
                                        nameWithOwner,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(GRAPHQL_TIMEOUT);

                if (response == null || !response.isValid()) {
                    pendingPermit = false;
                    graphQlClientProvider.recordFailure(new IllegalStateException("Invalid GraphQL response"));
                    ClassificationResult classification = graphQlSyncCoordinator.classifyGraphQlErrors(response);
                    if (classification != null) {
                        if (graphQlSyncCoordinator.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                description,
                                "repo",
                                nameWithOwner,
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        break;
                    }
                    log.warn(
                            "Invalid GraphQL response for {}: repo={}, errors={}",
                            description,
                            nameWithOwner,
                            response != null ? response.getErrors() : "null");
                    break;
                }

                graphQlClientProvider.trackRateLimit(scopeId, response);
                pendingPermit = false;
                graphQlClientProvider.recordSuccess();

                String fullPath = "repository." + connectionPath;
                ClientResponseField field = response.field(fullPath);
                if (field.getValue() == null) {
                    return null;
                }

                return field.toEntity(MAP_TYPE_REF);
            } catch (Exception e) {
                if (pendingPermit) {
                    graphQlClientProvider.recordFailure(e);
                }

                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                if (graphQlSyncCoordinator.handleGraphQlClassification(new GraphQlClassificationContext(
                        classification, retryAttempt, MAX_RETRY_ATTEMPTS, description, "repo", nameWithOwner, log))) {
                    retryAttempt++;
                    continue;
                }
                break;
            }
        }

        return null;
    }

    // Enrichment metadata

    /**
     * Extracts enrichment metadata from the GraphQL response and updates the commit.
     * <p>
     * Uses COALESCE in the repository query so null values don't overwrite existing data.
     * This is important for backfilling webhook-delivered commits where some fields
     * (e.g. additions, deletions) may have been 0 from the webhook but have real values
     * from GraphQL.
     */
    private void updateEnrichmentMetadata(Map<String, Object> commitData, Long commitId) {
        // Additions, deletions, changedFiles
        Integer additions = extractInteger(commitData.get("additions"));
        Integer deletions = extractInteger(commitData.get("deletions"));
        Integer changedFiles = extractInteger(commitData.get("changedFilesIfAvailable"));

        // Timestamps
        Instant authoredAt = parseInstant(commitData.get("authoredDate"));
        Instant committedAt = parseInstant(commitData.get("committedDate"));

        // Message fields
        String messageHeadline = normalizeString(commitData.get("messageHeadline"));
        // R1: Use dedicated messageBody field from GraphQL instead of extracting from full message
        String messageBody = normalizeString(commitData.get("messageBody"));

        // URL
        String url = normalizeString(commitData.get("url"));

        // R2: Expanded signature verification
        Boolean signatureValid = null;
        String signatureState = null;
        Boolean signatureWasSignedByGitHub = null;
        String signatureSignerLogin = null;
        Object signatureObj = commitData.get("signature");
        if (signatureObj instanceof Map<?, ?> signatureMap) {
            Object isValidObj = signatureMap.get("isValid");
            if (isValidObj instanceof Boolean b) {
                signatureValid = b;
            }
            signatureState = normalizeString(signatureMap.get("state"));
            signatureWasSignedByGitHub = extractBoolean(signatureMap.get("wasSignedByGitHub"));
            Object signerObj = signatureMap.get("signer");
            if (signerObj instanceof Map<?, ?> signerMap) {
                signatureSignerLogin = normalizeString(signerMap.get("login"));
            }
        }

        // Boolean flags
        Boolean authoredByCommitter = extractBoolean(commitData.get("authoredByCommitter"));
        Boolean committedViaWeb = extractBoolean(commitData.get("committedViaWeb"));

        // R3: Parent count and parent SHAs
        Integer parentCount = null;
        String parentShas = null;
        Object parentsObj = commitData.get("parents");
        if (parentsObj instanceof Map<?, ?> parentsMap) {
            parentCount = extractInteger(parentsMap.get("totalCount"));
            Object nodesObj = parentsMap.get("nodes");
            if (nodesObj instanceof List<?> nodes && !nodes.isEmpty()) {
                List<String> oids = new ArrayList<>();
                for (Object nodeObj : nodes) {
                    if (nodeObj instanceof Map<?, ?> nodeMap) {
                        String oid = normalizeString(nodeMap.get("oid"));
                        if (oid != null) {
                            oids.add(oid);
                        }
                    }
                }
                if (!oids.isEmpty()) {
                    parentShas = String.join(",", oids);
                }
            }
        }

        // R4: CI status check rollup
        String statusCheckRollupState = null;
        Object rollupObj = commitData.get("statusCheckRollup");
        if (rollupObj instanceof Map<?, ?> rollupMap) {
            statusCheckRollupState = normalizeString(rollupMap.get("state"));
        }

        // R6: Organizational commit attribution (direct Organization field, not a connection)
        String onBehalfOfLogin = null;
        Object onBehalfOfObj = commitData.get("onBehalfOf");
        if (onBehalfOfObj instanceof Map<?, ?> onBehalfOfMap) {
            onBehalfOfLogin = normalizeString(onBehalfOfMap.get("login"));
        }

        commitRepository.updateEnrichmentMetadata(
                commitId,
                additions,
                deletions,
                changedFiles,
                authoredAt,
                committedAt,
                messageHeadline,
                messageBody,
                url,
                signatureValid,
                authoredByCommitter,
                committedViaWeb,
                parentCount,
                signatureState,
                signatureWasSignedByGitHub,
                signatureSignerLogin,
                parentShas,
                statusCheckRollupState,
                onBehalfOfLogin);
    }

    // Utility methods

    /**
     * Extracts the user's database ID from a GitActor node.
     */
    @Nullable
    private Long extractUserId(Map<?, ?> actorMap) {
        Object userObj = actorMap.get("user");
        if (!(userObj instanceof Map<?, ?> userMap)) {
            return null;
        }
        Object databaseIdObj = userMap.get("databaseId");
        if (databaseIdObj instanceof Number num) {
            return num.longValue();
        }
        return null;
    }

    /**
     * Extracts the {@code pageInfo.endCursor} from a connection map.
     */
    @Nullable
    private static String extractEndCursor(Map<?, ?> connectionMap) {
        Object pageInfoObj = connectionMap.get("pageInfo");
        if (!(pageInfoObj instanceof Map<?, ?> pageInfo)) {
            return null;
        }
        Object endCursorObj = pageInfo.get("endCursor");
        if (endCursorObj instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    /**
     * Checks the {@code pageInfo.hasNextPage} from a connection map.
     */
    private static boolean hasNextPage(Map<?, ?> connectionMap) {
        Object pageInfoObj = connectionMap.get("pageInfo");
        if (!(pageInfoObj instanceof Map<?, ?> pageInfo)) {
            return false;
        }
        Object hasNext = pageInfo.get("hasNextPage");
        return Boolean.TRUE.equals(hasNext);
    }

    private static @Nullable String normalizeString(@Nullable Object value) {
        return CommitUtils.normalizeString(value);
    }

    @Nullable
    private static Integer extractInteger(@Nullable Object value) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        return null;
    }

    @Nullable
    private static Boolean extractBoolean(@Nullable Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return null;
    }

    @Nullable
    private static Instant parseInstant(@Nullable Object value) {
        if (!(value instanceof String s) || s.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
