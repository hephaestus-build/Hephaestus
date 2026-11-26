package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Observation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Result;
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

    private final RepositoryObservationStore store;

    public GitLabProjectProcessor(RepositoryObservationStore store) {
        this.store = store;
    }

    /**
     * Processes a GitLab project GraphQL response into a Repository entity.
     *
     * @param project      the GitLab project GraphQL response
     * @param organizationId the parent organization (nullable for user-owned projects)
     * @param read the snapshot taken before asking GitLab
     * @return the persisted Repository entity, or null if the response is invalid
     */
    @Transactional
    @Nullable
    public Result processGraphQlResponse(
            @Nullable GitLabProjectResponse project,
            @Nullable Long organizationId,
            IdentityProvider provider,
            ReadersRead read) {
        Objects.requireNonNull(read);
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

        long providerId = Objects.requireNonNull(provider.getId());
        Repository.Visibility visibility = mapVisibility(project.visibility());
        if (nativeId != read.repositoryNativeId() || providerId != read.providerId()) {
            return null;
        }
        var patch = RepositoryObservationStore.path(
                project.fullPath(), Objects.requireNonNullElse(project.name(), project.fullPath()), project.webUrl());
        patch.put("description", project.description());
        patch.put("organization_id", organizationId);
        patch.put("is_archived", Boolean.TRUE.equals(project.archived()));
        patch.put("is_disabled", false);
        var branch = project.repository() == null ? null : project.repository().rootRef();
        patch.put("default_branch", Objects.requireNonNullElse(branch, "main"));
        var created = parseTimestamp(project.createdAt());
        if (created != null) patch.put("created_at", created.toString());
        var latest = parseTimestamp(project.lastActivityAt());
        if (latest != null) {
            patch.put("pushed_at", latest.toString());
            patch.put("updated_at", latest.toString());
        }
        return store.observe(read, Observation.read(read, visibility, patch));
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
