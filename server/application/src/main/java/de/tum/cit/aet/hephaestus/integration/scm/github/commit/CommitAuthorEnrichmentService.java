package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.JITTER_FACTOR;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_INITIAL_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_BACKOFF;
import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.TRANSPORT_MAX_RETRIES;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.common.ScmTransportErrors;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.util.CommitUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Enriches commits with unresolved author/committer by querying the database
 * for stored email addresses and, if needed, the GitHub GraphQL API.
 * <p>
 * <b>Algorithm (email-based negative caching):</b>
 * <ol>
 *   <li>Query distinct unresolved author/committer emails from {@code git_commit}
 *       where {@code author_id IS NULL AND author_email IS NOT NULL}</li>
 *   <li>First pass: resolve each email against existing DB users via
 *       {@link CommitAuthorResolver#resolveByEmail} (direct match + noreply parsing)</li>
 *   <li>Bulk update all commits sharing the resolved email in one statement</li>
 *   <li>For still-unresolved emails: pick one representative SHA per email,
 *       batch up to 50 per GraphQL query to get the login from GitHub</li>
 *   <li>Resolve login &rarr; user_id, then bulk update by email</li>
 * </ol>
 * <p>
 * <b>Negative caching:</b> Emails that cannot be resolved remain in the database
 * with {@code author_id IS NULL}. Each enrichment cycle re-attempts resolution
 * cheaply (two indexed DB lookups per email). When new users are synced to the
 * database, their emails will match on the next cycle — no TTL needed.
 * <p>
 * Emails are captured at commit ingestion (webhook or backfill) and stored on the
 * {@code git_commit} table, so enrichment never opens a repository clone.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommitAuthorEnrichmentService {

    /** Timeout for batch GraphQL queries (multiple commits per request). */
    private static final Duration GRAPHQL_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Maximum number of commit SHAs to batch in a single GraphQL query.
     * Each SHA becomes an aliased field; cost is ~1 point regardless of alias count.
     */
    private static final int BATCH_SIZE = 50;

    /** Maximum retry attempts for GraphQL error classification. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /** Pattern to validate SHA-1 hex strings (prevents injection into dynamic query). */
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{40}$");

    /**
     * Emails that should never be resolved to a user. GitHub's web-flow bot uses
     * {@code noreply@github.com} as the committer email for squash merges done via
     * the web UI. This always resolves to {@code user: null} in GraphQL, wasting API
     * calls. Filtering these out avoids unnecessary resolution attempts.
     */
    private static final Set<String> UNRESOLVABLE_EMAILS = Set.of("noreply@github.com");

    /** Type reference for deserializing GraphQL fields as {@code Map<String, Object>} without unchecked casts. */
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE_REF =
            new ParameterizedTypeReference<>() {};

    private final CommitRepository commitRepository;
    private final CommitAuthorResolver authorResolver;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubGraphQlSyncCoordinator graphQlSyncCoordinator;
    private final GitHubExceptionClassifier exceptionClassifier;
    private final GitHubUserProcessor userProcessor;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Enriches unresolved commit authors/committers for a repository.
     * <p>
     * This method is safe to call repeatedly &mdash; it only processes commits where
     * {@code author_id} or {@code committer_id} is NULL and the corresponding email is stored.
     *
     * @param repositoryId     the repository database ID
     * @param nameWithOwner    the repository name with owner (e.g. "owner/repo")
     * @param scopeId          the scope ID for GraphQL client authentication
     * @param providerId       the provider ID for scoping user lookups
     * @param repository       the repository entity used to populate the {@link RepositoryRef}
     *                         on the published {@link ScmDomainEvent.CommitAuthorsReconciled} event;
     *                         may be {@code null} when a reference cannot be materialised.
     * @return the number of commits enriched
     */
    public int enrichCommitAuthors(
            Long repositoryId,
            String nameWithOwner,
            @Nullable Long scopeId,
            Long providerId,
            @Nullable Repository repository) {
        // Phase 1: Find all distinct unresolved emails from the database
        List<String> unresolvedAuthorEmails =
                commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(repositoryId);
        List<String> unresolvedCommitterEmails =
                commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(repositoryId);

        // Filter out known-unresolvable emails (e.g., GitHub web-flow bot)
        unresolvedAuthorEmails = unresolvedAuthorEmails.stream()
                .filter(e -> !UNRESOLVABLE_EMAILS.contains(e))
                .toList();
        unresolvedCommitterEmails = unresolvedCommitterEmails.stream()
                .filter(e -> !UNRESOLVABLE_EMAILS.contains(e))
                .toList();

        if (unresolvedAuthorEmails.isEmpty() && unresolvedCommitterEmails.isEmpty()) {
            log.debug("No unresolved commit authors: repoId={}", repositoryId);
            return 0;
        }

        log.debug(
                "Found unresolved emails: repoId={}, authorEmails={}, committerEmails={}",
                repositoryId,
                unresolvedAuthorEmails.size(),
                unresolvedCommitterEmails.size());

        // Phase 2: First pass — try resolving by email using existing DB users
        int enrichedByEmail =
                enrichByEmail(repositoryId, unresolvedAuthorEmails, unresolvedCommitterEmails, providerId);

        // Phase 3: Re-check which emails are still unresolved after email pass
        List<String> stillUnresolvedAuthorEmails =
                commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(repositoryId);
        List<String> stillUnresolvedCommitterEmails =
                commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(repositoryId);

        Set<String> allStillUnresolved = new HashSet<>(stillUnresolvedAuthorEmails);
        allStillUnresolved.addAll(stillUnresolvedCommitterEmails);

        int enrichedByApi = 0;
        if (allStillUnresolved.isEmpty()) {
            log.info("Enriched all commit authors via email: repoId={}, enriched={}", repositoryId, enrichedByEmail);
        } else if (scopeId == null) {
            log.debug(
                    "Skipping GitHub API enrichment: reason=noScopeId, repoId={}, remaining={}",
                    repositoryId,
                    allStillUnresolved.size());
        } else {
            // Phase 4: Cluster remaining unresolved by email, fetch from GitHub GraphQL API
            enrichedByApi = enrichByGitHubGraphQl(
                    repositoryId,
                    nameWithOwner,
                    scopeId,
                    stillUnresolvedAuthorEmails,
                    stillUnresolvedCommitterEmails,
                    providerId);
        }

        int total = enrichedByEmail + enrichedByApi;

        // Signal downstream consumers (the activity ledger) that commit author identities
        // for this repository have been reconciled. The activity module listens for this
        // event and backfills COMMIT_CREATED rows whose actor_id was NULL at ingest time.
        // Must publish regardless of which phase did the work — a Phase-2-only enrichment
        // would otherwise silently fail to trigger the activity backfill.
        if (total > 0 && scopeId != null && repository != null) {
            eventPublisher.publishEvent(new ScmDomainEvent.CommitAuthorsReconciled(
                    repositoryId,
                    EventContext.forSync(
                            scopeId,
                            Objects.requireNonNull(RepositoryRef.from(repository)),
                            IdentityProviderType.GITHUB)));
        }

        log.info(
                "Completed commit author enrichment: repoId={}, enrichedByEmail={}, enrichedByApi={}, total={}",
                repositoryId,
                enrichedByEmail,
                enrichedByApi,
                total);
        return total;
    }

    /**
     * First pass: resolve authors by email using {@link CommitAuthorResolver#resolveByEmail}.
     * This resolves noreply emails and direct email matches without any API call.
     * Uses bulk update by email — one UPDATE per distinct email.
     */
    private int enrichByEmail(
            Long repositoryId,
            List<String> unresolvedAuthorEmails,
            List<String> unresolvedCommitterEmails,
            Long providerId) {
        int enriched = 0;

        for (String email : unresolvedAuthorEmails) {
            Long userId = authorResolver.resolveByEmail(email, providerId);
            if (userId != null) {
                int updated = commitRepository.bulkUpdateAuthorIdByEmail(email, repositoryId, userId);
                enriched += updated;
                log.debug("Enriched {} commits author by email: email={}, repoId={}", updated, email, repositoryId);
            }
        }

        for (String email : unresolvedCommitterEmails) {
            Long userId = authorResolver.resolveByEmail(email, providerId);
            if (userId != null) {
                int updated = commitRepository.bulkUpdateCommitterIdByEmail(email, repositoryId, userId);
                enriched += updated;
                log.debug("Enriched {} commits committer by email: email={}, repoId={}", updated, email, repositoryId);
            }
        }

        return enriched;
    }

    /**
     * Second pass: for each still-unresolved email, find ONE representative commit SHA,
     * fetch from GitHub GraphQL API (batched up to 50 per query) to get the login,
     * then bulk update all commits sharing that email.
     */
    private int enrichByGitHubGraphQl(
            Long repositoryId,
            String nameWithOwner,
            Long scopeId,
            List<String> unresolvedAuthorEmails,
            List<String> unresolvedCommitterEmails,
            Long providerId) {
        // Collect all unique unresolved emails
        Set<String> allUnresolvedEmails = new HashSet<>(unresolvedAuthorEmails);
        allUnresolvedEmails.addAll(unresolvedCommitterEmails);

        if (allUnresolvedEmails.isEmpty()) {
            return 0;
        }

        // Single query: get one representative SHA per unresolved email
        Map<String, String> emailToRepSha = new HashMap<>();
        for (Object[] row : commitRepository.findRepresentativeShasByUnresolvedEmail(repositoryId)) {
            String email = (String) row[0];
            String sha = (String) row[1];
            // Only keep emails that are still in our unresolved set, deduplicate
            if (allUnresolvedEmails.contains(email)) {
                emailToRepSha.putIfAbsent(email, sha);
            }
        }

        if (emailToRepSha.isEmpty()) {
            return 0;
        }

        // Build reverse map: SHA → email (for GraphQL response extraction)
        Map<String, String> shaToEmail = new HashMap<>();
        for (var entry : emailToRepSha.entrySet()) {
            String sha = entry.getValue();
            if (SHA_PATTERN.matcher(sha).matches()) {
                shaToEmail.putIfAbsent(sha, entry.getKey());
            }
        }

        if (shaToEmail.isEmpty()) {
            return 0;
        }

        List<String> validShas = new ArrayList<>(shaToEmail.keySet());

        log.debug(
                "Fetching {} representative commits via GraphQL: repoId={}, repo={}",
                validShas.size(),
                repositoryId,
                nameWithOwner);

        // Fetch commit authors in batches via GraphQL
        Map<String, String> emailToLogin =
                fetchCommitAuthorsBatched(nameWithOwner, scopeId, validShas, shaToEmail, providerId);

        // Bulk update: for each email → login, resolve login → user_id,
        // then update all commits with that email
        int enriched = 0;

        // Author updates
        for (String email : unresolvedAuthorEmails) {
            String login = emailToLogin.get(email);
            if (login == null) {
                continue;
            }
            Long userId = authorResolver.resolveByLogin(login, providerId);
            if (userId != null) {
                int updated = commitRepository.bulkUpdateAuthorIdByEmail(email, repositoryId, userId);
                enriched += updated;
                log.debug(
                        "Enriched {} commits author via GraphQL: email={}, login={}, repoId={}",
                        updated,
                        email,
                        login,
                        repositoryId);
            }
        }

        // Committer updates
        for (String email : unresolvedCommitterEmails) {
            String login = emailToLogin.get(email);
            if (login == null) {
                continue;
            }
            Long userId = authorResolver.resolveByLogin(login, providerId);
            if (userId != null) {
                int updated = commitRepository.bulkUpdateCommitterIdByEmail(email, repositoryId, userId);
                enriched += updated;
                log.debug(
                        "Enriched {} commits committer via GraphQL: email={}, login={}, repoId={}",
                        updated,
                        email,
                        login,
                        repositoryId);
            }
        }

        return enriched;
    }

    /**
     * Fetches commit author/committer logins via GitHub GraphQL API using
     * alias-based batching. Batches up to {@link #BATCH_SIZE} SHAs per query.
     * <p>
     * Each query uses dynamic aliases to fetch multiple commits in a single
     * GraphQL request:
     * <pre>{@code
     * query {
     *   rateLimit { cost limit remaining resetAt }
     *   repository(owner: "owner", name: "repo") {
     *     commit0: object(oid: "sha1") { ... on Commit { author { user { login } } committer { user { login } } } }
     *     commit1: object(oid: "sha2") { ... }
     *   }
     * }
     * }</pre>
     *
     * @return map from email to GitHub login
     */
    private Map<String, String> fetchCommitAuthorsBatched(
            String nameWithOwner, Long scopeId, List<String> shas, Map<String, String> shaToEmail, Long providerId) {
        Map<String, String> emailToLogin = new HashMap<>();
        Map<Long, GitHubUserDTO> usersToUpsert = new HashMap<>();
        String[] parts = nameWithOwner.split("/", 2);
        if (parts.length != 2) {
            log.warn("Invalid nameWithOwner format: {}", nameWithOwner);
            return emailToLogin;
        }
        String owner = parts[0];
        String repoName = parts[1];

        // Process in batches
        for (int batchStart = 0; batchStart < shas.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, shas.size());
            List<String> batch = shas.subList(batchStart, batchEnd);

            // Check rate limit before each batch
            if (graphQlClientProvider.isRateLimitCritical(scopeId)) {
                if (!graphQlSyncCoordinator.waitForRateLimitIfNeeded(
                        scopeId, "commit author enrichment", "repo", nameWithOwner, log)) {
                    log.warn(
                            "Aborting commit author enrichment due to rate limit: repo={}, processed={}/{}",
                            nameWithOwner,
                            batchStart,
                            shas.size());
                    break;
                }
            }

            Map<String, String> batchResult =
                    fetchBatch(owner, repoName, scopeId, batch, shaToEmail, nameWithOwner, usersToUpsert);
            emailToLogin.putAll(batchResult);
        }

        upsertUsers(usersToUpsert, nameWithOwner, providerId);

        return emailToLogin;
    }

    /**
     * Fetches a single batch of commit SHAs via GraphQL with full error handling.
     * Follows the gold standard pattern: transport retry, GraphQL error classification,
     * rate limit tracking, and exception classification.
     *
     * @return map from email to GitHub login for commits in this batch
     */
    private Map<String, String> fetchBatch(
            String owner,
            String repoName,
            Long scopeId,
            List<String> batch,
            Map<String, String> shaToEmail,
            String nameWithOwner,
            Map<Long, GitHubUserDTO> usersToUpsert) {
        Map<String, String> emailToLogin = new HashMap<>();
        String queryString = buildBatchQuery(owner, repoName, batch);

        int retryAttempt = 0;
        while (retryAttempt <= MAX_RETRY_ATTEMPTS) {
            try {
                // Acquire circuit breaker permission
                graphQlClientProvider.acquirePermission();

                // Execute with Mono.defer() for transport retry coverage
                var client = graphQlClientProvider.forScope(scopeId);
                ClientGraphQlResponse response = Mono.defer(
                                () -> client.document(queryString).execute())
                        .retryWhen(Retry.backoff(TRANSPORT_MAX_RETRIES, TRANSPORT_INITIAL_BACKOFF)
                                .maxBackoff(TRANSPORT_MAX_BACKOFF)
                                .jitter(JITTER_FACTOR)
                                .filter(ScmTransportErrors::isTransportError)
                                .doBeforeRetry(signal -> log.warn(
                                        "Retrying commit enrichment after transport error: repo={}, attempt={}, error={}",
                                        nameWithOwner,
                                        signal.totalRetries() + 1,
                                        signal.failure().getMessage())))
                        .block(GRAPHQL_TIMEOUT);

                // Classify GraphQL errors
                if (response == null || !response.isValid()) {
                    ClassificationResult classification = graphQlSyncCoordinator.classifyGraphQlErrors(response);
                    if (classification != null) {
                        if (graphQlSyncCoordinator.handleGraphQlClassification(new GraphQlClassificationContext(
                                classification,
                                retryAttempt,
                                MAX_RETRY_ATTEMPTS,
                                "commit author enrichment",
                                "repo",
                                nameWithOwner,
                                log))) {
                            retryAttempt++;
                            continue;
                        }
                        // Non-retryable — abort this batch
                        log.warn(
                                "Aborting commit enrichment batch: repo={}, error={}",
                                nameWithOwner,
                                classification.message());
                        break;
                    }
                    log.warn(
                            "Invalid GraphQL response for commit enrichment: repo={}, errors={}",
                            nameWithOwner,
                            response != null ? response.getErrors() : "null");
                    break;
                }

                // Track rate limit
                graphQlClientProvider.trackRateLimit(scopeId, response);
                graphQlClientProvider.recordSuccess();

                // Extract results from aliased fields
                extractLoginsFromResponse(response, batch, shaToEmail, emailToLogin, usersToUpsert);
                break; // Success — exit retry loop
            } catch (Exception e) {
                graphQlClientProvider.recordFailure(e);

                ClassificationResult classification = exceptionClassifier.classifyWithDetails(e);
                if (graphQlSyncCoordinator.handleGraphQlClassification(new GraphQlClassificationContext(
                        classification,
                        retryAttempt,
                        MAX_RETRY_ATTEMPTS,
                        "commit author enrichment",
                        "repo",
                        nameWithOwner,
                        log))) {
                    retryAttempt++;
                    continue;
                }
                // Non-retryable — abort this batch
                break;
            }
        }

        return emailToLogin;
    }

    /**
     * Builds a dynamic GraphQL query with alias-based batching for commit lookups.
     * <p>
     * SHAs are baked directly into the query string because GraphQL aliases are
     * syntactic (cannot use variables for alias names). SHA validation is performed
     * before calling this method to prevent injection.
     */
    private String buildBatchQuery(String owner, String repoName, List<String> shas) {
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
                    .append("\") {\n");
            sb.append("      ... on Commit {\n");
            sb.append("        oid\n");
            sb.append("        author { user { login databaseId name email avatarUrl url createdAt updatedAt } }\n");
            sb.append("        committer { user { login databaseId name email avatarUrl url createdAt updatedAt } }\n");
            sb.append("      }\n");
            sb.append("    }\n");
        }

        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Extracts author/committer logins from a batched GraphQL response.
     * <p>
     * Each aliased field ({@code commit0}, {@code commit1}, ...) maps to a commit
     * object with optional {@code author.user.login} and {@code committer.user.login}.
     * The {@code user} field is nullable — null when GitHub can't match the commit
     * email to a GitHub account.
     */
    private void extractLoginsFromResponse(
            ClientGraphQlResponse response,
            List<String> batch,
            Map<String, String> shaToEmail,
            Map<String, String> emailToLogin,
            Map<Long, GitHubUserDTO> usersToUpsert) {
        for (int i = 0; i < batch.size(); i++) {
            String sha = batch.get(i);
            String fieldPath = "repository.commit" + i;
            String email = shaToEmail.get(sha);

            try {
                ClientResponseField field = response.field(fieldPath);
                if (field.getValue() == null) {
                    log.debug("Commit not found on GitHub: sha={}", sha);
                    continue;
                }

                Map<String, Object> commitData = field.toEntity(MAP_TYPE_REF);
                if (commitData == null) {
                    continue;
                }

                UserSnapshot authorSnapshot = extractUserSnapshot(commitData, "author");
                if (authorSnapshot != null && email != null) {
                    emailToLogin.putIfAbsent(email, authorSnapshot.login());
                    if (authorSnapshot.user() != null) {
                        usersToUpsert.putIfAbsent(authorSnapshot.user().getDatabaseId(), authorSnapshot.user());
                    }
                }

                UserSnapshot committerSnapshot = extractUserSnapshot(commitData, "committer");
                if (committerSnapshot != null && email != null) {
                    emailToLogin.putIfAbsent(email, committerSnapshot.login());
                    if (committerSnapshot.user() != null) {
                        usersToUpsert.putIfAbsent(committerSnapshot.user().getDatabaseId(), committerSnapshot.user());
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to extract login for commit: sha={}, error={}", sha, e.getMessage());
            }
        }
    }

    /**
     * Extracts user info from a nested path like {@code author.user} or
     * {@code committer.user} in the GraphQL commit data.
     *
     * @param commitData the deserialized commit map from GraphQL
     * @param role       "author" or "committer"
     * @return the user snapshot, or null if not present
     */
    @Nullable
    private UserSnapshot extractUserSnapshot(Map<String, Object> commitData, String role) {
        try {
            Object roleObj = commitData.get(role);
            if (!(roleObj instanceof Map<?, ?> roleMap)) {
                return null;
            }
            Object userObj = roleMap.get("user");
            if (!(userObj instanceof Map<?, ?> userMap)) {
                return null;
            }
            String login = normalizeString(userMap.get("login"));
            if (login == null) {
                return null;
            }

            Long databaseId = toLong(userMap.get("databaseId"));
            GitHubUserDTO user = null;
            if (databaseId != null) {
                user = new GitHubUserDTO(
                        null,
                        databaseId,
                        login,
                        normalizeString(userMap.get("avatarUrl")),
                        normalizeString(userMap.get("url")),
                        normalizeString(userMap.get("name")),
                        normalizeString(userMap.get("email")),
                        inferUserType(login));
            }

            return new UserSnapshot(login, user);
        } catch (Exception e) {
            log.debug("Failed to extract {} login: error={}", role, e.getMessage());
            return null;
        }
    }

    private void upsertUsers(Map<Long, GitHubUserDTO> usersToUpsert, String nameWithOwner, Long providerId) {
        if (usersToUpsert.isEmpty()) {
            return;
        }
        int processed = 0;
        for (GitHubUserDTO dto : usersToUpsert.values()) {
            if (dto == null || dto.getDatabaseId() == null || dto.login() == null) {
                continue;
            }
            try {
                userProcessor.ensureExists(dto, providerId);
                processed++;
            } catch (Exception e) {
                log.debug(
                        "Failed to upsert user from commit enrichment: repo={}, login={}, error={}",
                        nameWithOwner,
                        dto.login(),
                        e.getMessage());
            }
        }
        log.debug("Upserted {} users from commit enrichment: repo={}", processed, nameWithOwner);
    }

    private static @Nullable String normalizeString(@Nullable Object value) {
        return CommitUtils.normalizeString(value);
    }

    private static @Nullable Long toLong(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static User.Type inferUserType(String login) {
        if (login != null && login.toLowerCase().endsWith("[bot]")) {
            return User.Type.BOT;
        }
        return User.Type.USER;
    }

    private record UserSnapshot(String login, @Nullable GitHubUserDTO user) {}
}
