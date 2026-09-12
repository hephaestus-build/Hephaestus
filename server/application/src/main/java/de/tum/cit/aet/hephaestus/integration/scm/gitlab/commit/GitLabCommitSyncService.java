package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Incremental commit sync for GitLab repositories via the REST API.
 * <p>
 * Uses {@code GET /api/v4/projects/:id/repository/commits} to fetch commits
 * on the default branch. The list endpoint returns commit metadata (SHA, message,
 * author/committer info, timestamps) but not diff statistics. Statistics are
 * enriched separately via push webhooks or the enrichment service.
 * <p>
 * Supports incremental sync via the {@code since} parameter (ISO 8601 date),
 * which maps to the repository's {@code lastSyncAt} timestamp.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabCommitSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabCommitSyncService.class);
    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 50;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final CommitRepository commitRepository;
    private final CommitContributorRepository contributorRepository;
    private final CommitAuthorResolver authorResolver;
    private final GitLabTokenService tokenService;
    private final GitLabProperties gitLabProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final WebClient webClient;

    public GitLabCommitSyncService(
            CommitRepository commitRepository,
            CommitContributorRepository contributorRepository,
            CommitAuthorResolver authorResolver,
            GitLabTokenService tokenService,
            GitLabProperties gitLabProperties,
            ApplicationEventPublisher eventPublisher,
            WebClient.Builder webClientBuilder) {
        this.commitRepository = commitRepository;
        this.contributorRepository = contributorRepository;
        this.authorResolver = authorResolver;
        this.tokenService = tokenService;
        this.gitLabProperties = gitLabProperties;
        this.eventPublisher = eventPublisher;
        this.webClient = webClientBuilder.build();
    }

    /**
     * Syncs commits for a repository from the GitLab REST API.
     *
     * @param scopeId    the workspace scope ID
     * @param repository the repository to sync commits for
     * @param since      optional lower bound for incremental sync (ISO 8601)
     * @return sync result with count of commits processed
     */
    public SyncResult syncCommitsForRepository(Long scopeId, Repository repository, @Nullable OffsetDateTime since) {
        String projectPath = repository.getNameWithOwner();
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");
        String branch = repository.getDefaultBranch();

        if (branch == null || branch.isBlank()) {
            log.debug("No default branch, skipping commit sync: project={}", safeProjectPath);
            return SyncResult.completed(0);
        }

        Long providerId = Objects.requireNonNull(
                repository.getProvider() != null
                        ? Objects.requireNonNull(repository.getProvider().getId())
                        : null);
        String serverUrl = tokenService.resolveServerUrl(scopeId);
        String token = tokenService.getAccessToken(scopeId);
        long nativeId = repository.getNativeId();

        int totalSynced = 0;
        int page = 1;
        boolean hasMore = true;
        boolean errorAborted = false;

        try {
            while (hasMore && page <= MAX_PAGES) {
                List<Map<String, Object>> commits = fetchCommitPage(serverUrl, token, nativeId, branch, since, page);

                if (commits == null || commits.isEmpty()) break;

                for (Map<String, Object> commitData : commits) {
                    processCommit(commitData, repository, providerId, scopeId);
                    totalSynced++;
                }

                hasMore = commits.size() == PER_PAGE;
                page++;
            }
        } catch (WebClientResponseException e) {
            log.warn(
                    "Commit sync REST error: project={}, status={}, page={}",
                    safeProjectPath,
                    e.getStatusCode().value(),
                    page,
                    e);
            errorAborted = true;
        } catch (Exception e) {
            log.warn("Commit sync failed: project={}, page={}", safeProjectPath, page, e);
            errorAborted = true;
        }

        if (totalSynced > 0) {
            log.info("GitLab commit sync: project={}, commits={}, pages={}", safeProjectPath, totalSynced, page - 1);
        }

        return errorAborted ? SyncResult.abortedError(totalSynced) : SyncResult.completed(totalSynced);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchCommitPage(
            String serverUrl, String token, long projectId, String branch, @Nullable OffsetDateTime since, int page) {
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        String commitUrl = base + "/api/v4/projects/"
                + projectId
                + "/repository/commits"
                + "?ref_name="
                + URLEncoder.encode(branch, StandardCharsets.UTF_8)
                + "&per_page="
                + PER_PAGE
                + "&page="
                + page
                + (since != null ? "&since=" + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8) : "");

        var request = webClient.get().uri(commitUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        return (List<Map<String, Object>>) (List<?>) request.retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block(REQUEST_TIMEOUT);
    }

    private void processCommit(
            Map<String, Object> commitData, Repository repository, @Nullable Long providerId, Long scopeId) {
        String sha = (String) commitData.get("id");
        String message = (String) commitData.get("message");
        String webUrl = (String) commitData.get("web_url");
        String authorName = (String) commitData.get("author_name");
        String authorEmail = (String) commitData.get("author_email");
        String committerName = (String) commitData.get("committer_name");
        String committerEmail = (String) commitData.get("committer_email");
        String authoredDateStr = (String) commitData.get("authored_date");
        String committedDateStr = (String) commitData.get("committed_date");

        if (sha == null) return;

        boolean isNew = !commitRepository.existsByShaAndRepositoryId(sha, repository.getId());

        String headline = extractHeadline(message);
        String body = extractBody(message);
        Instant authoredAt = parseInstant(authoredDateStr);
        Instant committedAt = parseInstant(committedDateStr);

        // Resolve author and committer (backfills user.email when currently NULL)
        Long authorId = authorResolver.resolveAndBackfillByEmail(authorEmail, providerId);
        Long committerId = authorResolver.resolveAndBackfillByEmail(committerEmail, providerId);

        // Upsert commit (passes null for stats — REST list endpoint doesn't provide them)
        commitRepository.upsertCommit(
                sha,
                Objects.requireNonNullElse(headline, ""),
                body,
                webUrl,
                authoredAt,
                committedAt,
                null,
                null,
                null, // additions, deletions, changedFiles — not in REST list response
                Instant.now(),
                repository.getId(),
                authorId,
                committerId,
                authorEmail,
                committerEmail,
                null);

        // Persist parent metadata when the REST payload includes parent_ids.
        // Matches GitHub's CommitMetadataEnrichmentService which backfills the
        // same columns via GraphQL Commit.parents.
        Integer parentCount = null;
        String parentShas = null;
        Object parentIdsField = commitData.get("parent_ids");
        if (parentIdsField instanceof List<?> parentIdsList) {
            List<String> parentShasList = new ArrayList<>(parentIdsList.size());
            for (Object pid : parentIdsList) {
                if (pid instanceof String s && !s.isBlank()) {
                    parentShasList.add(s);
                }
            }
            parentCount = parentShasList.size();
            if (!parentShasList.isEmpty()) {
                parentShas = String.join(",", parentShasList);
            }
        }
        if (parentCount != null) {
            commitRepository.updateParentMetadataBySha(repository.getId(), sha, parentCount, parentShas);
        }

        // Upsert primary author contributor
        if (authorEmail != null) {
            contributorRepository.upsertContributor(
                    commitRepository
                            .findByShaAndRepositoryId(sha, repository.getId())
                            .map(c -> c.getId())
                            .orElseThrow(),
                    authorId,
                    CommitContributor.Role.AUTHOR.name(),
                    authorName,
                    authorEmail,
                    0);
        }

        // Upsert committer contributor (if different from author)
        if (committerEmail != null && !committerEmail.equals(authorEmail)) {
            contributorRepository.upsertContributor(
                    commitRepository
                            .findByShaAndRepositoryId(sha, repository.getId())
                            .map(c -> c.getId())
                            .orElseThrow(),
                    committerId,
                    CommitContributor.Role.COMMITTER.name(),
                    committerName,
                    committerEmail,
                    0);
        }

        // Publish CommitCreated event for newly created commits
        if (isNew) {
            commitRepository.findByShaAndRepositoryId(sha, repository.getId()).ifPresent(commit -> {
                ScmEventPayload.CommitData commitPayload = ScmEventPayload.CommitData.from(commit);
                EventContext context = new EventContext(
                        UUID.randomUUID(),
                        Instant.now(),
                        scopeId,
                        Objects.requireNonNull(RepositoryRef.from(repository)),
                        DataSource.REST_SYNC,
                        null,
                        UUID.randomUUID().toString(),
                        IdentityProviderType.GITLAB);
                eventPublisher.publishEvent(new ScmDomainEvent.CommitCreated(commitPayload, context));
            });
        }
    }

    @Nullable
    private static String extractHeadline(@Nullable String message) {
        if (message == null) return null;
        int nl = message.indexOf('\n');
        return nl >= 0 ? message.substring(0, nl).trim() : message.trim();
    }

    @Nullable
    private static String extractBody(@Nullable String message) {
        if (message == null) return null;
        int nl = message.indexOf('\n');
        if (nl < 0) return null;
        String rest = message.substring(nl + 1).trim();
        return rest.isEmpty() ? null : rest;
    }

    @Nullable
    private static Instant parseInstant(@Nullable String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return OffsetDateTime.parse(dateStr).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
