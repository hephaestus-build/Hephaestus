package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitLab projects, mapping them to {@link Repository} entities.
 * <p>
 * Handles two data sources:
 * <ul>
 *   <li>GraphQL responses from sync services (full project metadata)</li>
 *   <li>Webhook push events (minimal project info embedded in payload)</li>
 * </ul>
 * <p>
 * <b>Key mapping decisions:</b>
 * <ul>
 *   <li>{@code fullPath} → {@code nameWithOwner} (unique identifier)</li>
 *   <li>Numeric ID extracted from GID → {@code nativeId} (provider-scoped native ID)</li>
 *   <li>{@code webUrl} → {@code htmlUrl}</li>
 *   <li>{@code visibility} string → {@code Repository.Visibility} enum</li>
 *   <li>{@code repository.rootRef} → {@code defaultBranch} (fallback: {@code "main"})</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabProjectProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabProjectProcessor.class);

    private final RepositoryRepository repositoryRepository;

    public GitLabProjectProcessor(RepositoryRepository repositoryRepository) {
        this.repositoryRepository = repositoryRepository;
    }

    /**
     * Processes a GitLab project GraphQL response into a Repository entity.
     *
     * @param project      the GitLab project GraphQL response
     * @param organization the parent organization (nullable for user-owned projects)
     * @return the persisted Repository entity, or null if the response is invalid
     */
    @Transactional
    @Nullable
    public Repository processGraphQlResponse(
            @Nullable GitLabProjectResponse project, @Nullable Organization organization, IdentityProvider provider) {
        if (project == null || project.id() == null || project.fullPath() == null || project.webUrl() == null) {
            log.warn("Skipped project processing: reason=nullOrMissingFields");
            return null;
        }

        long nativeId;
        try {
            nativeId = GitLabSyncConstants.extractEntityId(project.id());
        } catch (IllegalArgumentException e) {
            log.warn("Skipped project processing: reason=invalidGlobalId, gid={}", sanitizeForLog(project.id()));
            return null;
        }

        Repository repository = repositoryRepository
                .findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(provider.getId()))
                .orElseGet(Repository::new);

        repository.setNativeId(nativeId);
        repository.setProvider(provider);
        repository.setName(project.name());
        repository.setNameWithOwner(project.fullPath());
        repository.setHtmlUrl(project.webUrl());
        repository.setDescription(project.description());
        repository.setVisibility(mapVisibility(project.visibility()));
        repository.setPrivate("private".equalsIgnoreCase(project.visibility()));
        repository.setArchived(Boolean.TRUE.equals(project.archived()));
        repository.setDisabled(false); // GitLab has no "disabled" concept
        repository.setOrganization(organization);

        // Default branch from repository metadata
        if (project.repository() != null && project.repository().rootRef() != null) {
            repository.setDefaultBranch(project.repository().rootRef());
        } else {
            repository.setDefaultBranch("main");
        }

        // Timestamps — only set createdAt if we have a valid parsed value;
        // never overwrite existing createdAt with null from a bad timestamp
        if (project.createdAt() != null) {
            Instant parsed = parseTimestamp(project.createdAt());
            if (parsed != null) {
                repository.setCreatedAt(parsed);
            }
        }
        if (project.lastActivityAt() != null) {
            Instant lastActivity = parseTimestamp(project.lastActivityAt());
            if (lastActivity != null) {
                repository.setPushedAt(lastActivity);
                repository.setUpdatedAt(lastActivity);
            }
        }
        // pushedAt is @NonNull — use creation time or now as fallback
        if (repository.getPushedAt() == null) {
            repository.setPushedAt(repository.getCreatedAt() != null ? repository.getCreatedAt() : Instant.now());
        }

        // Do NOT set lastSyncAt here — it is set after issue sync completes
        // in WorkspaceActivationService. Setting it here would cause the first
        // issue sync to use an updatedAfter filter of "just now", skipping all
        // historical issues.

        return repositoryRepository.save(repository);
    }

    /**
     * Maps a GitLab visibility string (from GraphQL) to the Repository.Visibility enum.
     */
    static Repository.Visibility mapVisibility(@Nullable String visibility) {
        if (visibility == null) {
            return Repository.Visibility.UNKNOWN;
        }
        return switch (visibility.toLowerCase(Locale.ROOT)) {
            case "public" -> Repository.Visibility.PUBLIC;
            case "private" -> Repository.Visibility.PRIVATE;
            case "internal" -> Repository.Visibility.INTERNAL;
            default -> Repository.Visibility.UNKNOWN;
        };
    }

    @Nullable
    private static Instant parseTimestamp(@Nullable String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("Could not parse timestamp: value={}", sanitizeForLog(timestamp));
            return null;
        }
    }
}
