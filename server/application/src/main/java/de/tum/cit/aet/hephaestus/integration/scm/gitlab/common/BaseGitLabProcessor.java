package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.LabelIdUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.PostgresStringUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookLabel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for GitLab entity processors with shared helper methods.
 * <p>
 * Provides common functionality for finding or creating related entities
 * (users, labels) that is shared across GitLab Issue, MR, and Note processors.
 * <p>
 * GitLab entity IDs are stored as {@code nativeId} values alongside a
 * {@code provider_id} FK to the git_provider table, preventing cross-provider
 * collisions. Synthetic label IDs use negative deterministic hashes to avoid
 * colliding with real provider-assigned IDs.
 */
public abstract class BaseGitLabProcessor {

    private static final Logger log = LoggerFactory.getLogger(BaseGitLabProcessor.class);

    /**
     * Formatter for GitLab webhook timestamps: {@code "yyyy-MM-dd HH:mm:ss Z"}.
     * <p>
     * GitLab webhooks use a non-ISO timestamp format. Example: {@code "2026-01-31 19:03:35 +0100"}.
     * The GraphQL API uses standard ISO-8601. This formatter handles the webhook format
     * while {@link OffsetDateTime#parse} handles the ISO-8601 format.
     */
    private static final DateTimeFormatter GITLAB_WEBHOOK_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendPattern(" ")
            .appendOffset("+HHmm", "+0000")
            .optionalEnd()
            .parseDefaulting(ChronoField.OFFSET_SECONDS, 0)
            .toFormatter();

    protected final GitLabUserService gitLabUserService;
    protected final UserRepository userRepository;
    protected final LabelRepository labelRepository;
    protected final RepositoryRepository repositoryRepository;
    protected final ScopeIdResolver scopeIdResolver;
    protected final RepositoryScopeFilter repositoryScopeFilter;
    protected final GitLabProperties gitLabProperties;

    protected BaseGitLabProcessor(
            GitLabUserService gitLabUserService,
            UserRepository userRepository,
            LabelRepository labelRepository,
            RepositoryRepository repositoryRepository,
            ScopeIdResolver scopeIdResolver,
            RepositoryScopeFilter repositoryScopeFilter,
            GitLabProperties gitLabProperties) {
        this.gitLabUserService = gitLabUserService;
        this.userRepository = userRepository;
        this.labelRepository = labelRepository;
        this.repositoryRepository = repositoryRepository;
        this.scopeIdResolver = scopeIdResolver;
        this.repositoryScopeFilter = repositoryScopeFilter;
        this.gitLabProperties = gitLabProperties;
    }

    // User Resolution

    /**
     * Finds or creates a user from webhook data.
     * <p>
     * Delegates to {@link GitLabUserService#findOrCreateUser(GitLabWebhookUser, Long)}.
     */
    @Nullable
    protected User findOrCreateUser(@Nullable GitLabWebhookUser dto, Long providerId) {
        return gitLabUserService.findOrCreateUser(dto, providerId);
    }

    /**
     * Finds or creates a user from GraphQL data wrapped in a {@link GitLabUserLookup}.
     * <p>
     * Callers with access to the {@code GitLabUserFields} GraphQL fragment should populate
     * {@link GitLabUserLookup#publicEmail()} so downstream commit-author resolution can
     * match the user by email.
     */
    @Nullable
    public User findOrCreateUser(GitLabUserLookup lookup, Long providerId) {
        return gitLabUserService.findOrCreateUser(lookup, providerId);
    }

    // Label Resolution

    /**
     * Finds or creates a label from webhook data.
     */
    @Nullable
    protected Label findOrCreateLabel(@Nullable GitLabWebhookLabel dto, Repository repository) {
        if (dto == null || dto.title() == null || dto.title().isBlank()) {
            return null;
        }

        Optional<Label> existing = labelRepository.findByRepositoryIdAndName(repository.getId(), dto.title());
        if (existing.isPresent()) {
            return existing.get();
        }

        long nativeId = dto.id() != null
                ? GitLabSyncConstants.toEntityId(dto.id())
                : generateDeterministicLabelId(repository.getId(), dto.title());
        Long providerId = Objects.requireNonNull(repository.getProvider().getId());

        labelRepository.insertIfAbsent(
                nativeId, Objects.requireNonNull(providerId), dto.title(), dto.color(), repository.getId());
        return labelRepository
                .findByRepositoryIdAndName(repository.getId(), dto.title())
                .orElse(null);
    }

    /**
     * Finds or creates a label from GraphQL data (title, color).
     * <p>
     * Public because sync services need to resolve labels from GraphQL response data.
     * Uses deterministic composite IDs based on (repositoryId, labelName) rather than
     * GitLab global IDs. GitLab group-level labels share the same global ID across all
     * projects, but labels are stored per-repository in the database. Using the GitLab
     * global ID would cause primary key collisions when the same label appears in
     * multiple projects.
     */
    @Nullable
    public Label findOrCreateLabel(@Nullable String title, @Nullable String color, Repository repository) {
        if (title == null || title.isBlank()) {
            return null;
        }

        Optional<Label> existing = labelRepository.findByRepositoryIdAndName(repository.getId(), title);
        if (existing.isPresent()) {
            return existing.get();
        }

        long nativeId = generateDeterministicLabelId(repository.getId(), title);
        Long providerId = Objects.requireNonNull(repository.getProvider().getId());

        labelRepository.insertIfAbsent(nativeId, Objects.requireNonNull(providerId), title, color, repository.getId());
        return labelRepository
                .findByRepositoryIdAndName(repository.getId(), title)
                .orElse(null);
    }

    /** Produces a negative deterministic ID from (repositoryId, labelName) to avoid collisions with real label IDs. */
    private Long generateDeterministicLabelId(Long repositoryId, String labelName) {
        return LabelIdUtils.generateDeterministicId(repositoryId, labelName);
    }

    // Relationship Updates

    /**
     * Updates assignees collection from webhook user list.
     */
    protected boolean updateAssignees(
            @Nullable List<GitLabWebhookUser> assigneeDtos, Set<User> currentAssignees, Long providerId) {
        if (assigneeDtos == null) {
            return false;
        }

        Set<User> newAssignees = new HashSet<>();
        for (GitLabWebhookUser dto : assigneeDtos) {
            User assignee = findOrCreateUser(dto, providerId);
            if (assignee != null) {
                newAssignees.add(assignee);
            }
        }

        if (!currentAssignees.equals(newAssignees)) {
            currentAssignees.clear();
            currentAssignees.addAll(newAssignees);
            return true;
        }
        return false;
    }

    /**
     * Updates labels collection from webhook label list.
     */
    protected boolean updateLabels(
            @Nullable List<GitLabWebhookLabel> labelDtos, Collection<Label> currentLabels, Repository repository) {
        if (labelDtos == null) {
            return false;
        }

        Set<Label> newLabels = new HashSet<>();
        for (GitLabWebhookLabel dto : labelDtos) {
            Label label = findOrCreateLabel(dto, repository);
            if (label != null) {
                newLabels.add(label);
            }
        }

        if (!new HashSet<>(currentLabels).equals(newLabels)) {
            currentLabels.clear();
            currentLabels.addAll(newLabels);
            return true;
        }
        return false;
    }

    // Timestamp Parsing

    /**
     * Parses a GitLab timestamp string to an Instant.
     * <p>
     * Handles both webhook format ({@code "2026-01-31 19:03:35 +0100"}) and
     * GraphQL ISO-8601 format ({@code "2026-01-31T19:03:35Z"}).
     */
    @Nullable
    public static Instant parseGitLabTimestamp(@Nullable String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            // Try ISO-8601 first (GraphQL format)
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                // Try webhook format
                return OffsetDateTime.parse(timestamp, GITLAB_WEBHOOK_TIMESTAMP).toInstant();
            } catch (DateTimeParseException e2) {
                log.warn("Could not parse GitLab timestamp: value={}", timestamp);
                return null;
            }
        }
    }

    // Context Resolution

    /**
     * Resolves a ProcessingContext from a project's pathWithNamespace for webhook events.
     */
    @Nullable
    protected ProcessingContext resolveContext(@Nullable String pathWithNamespace, @Nullable String action) {
        if (pathWithNamespace == null || pathWithNamespace.isBlank() || action == null) {
            return null;
        }

        if (!repositoryScopeFilter.isRepositoryAllowed(pathWithNamespace)) {
            log.debug("Skipped event: reason=repositoryFiltered, repoName={}", pathWithNamespace);
            return null;
        }

        Repository repository = repositoryRepository
                .findByNameWithOwnerWithOrganization(pathWithNamespace)
                .orElse(null);
        if (repository == null) {
            log.debug("Skipped event: reason=repositoryNotFound, repoName={}", pathWithNamespace);
            return null;
        }

        Long scopeId = resolveScopeId(repository);
        return ProcessingContext.forWebhook(scopeId, repository, action);
    }

    private @Nullable Long resolveScopeId(Repository repository) {
        if (repository.getOrganization() != null) {
            String orgLogin = repository.getOrganization().getLogin();
            Long scopeId = scopeIdResolver.findScopeIdByOrgLogin(orgLogin).orElse(null);
            if (scopeId != null) {
                return scopeId;
            }
        }
        return scopeIdResolver
                .findScopeIdByRepositoryName(repository.getNameWithOwner())
                .orElse(null);
    }

    // Sanitization

    /** Strips null bytes and other characters that are invalid in PostgreSQL text columns. */
    @Nullable
    protected String sanitize(@Nullable String input) {
        return PostgresStringUtils.sanitize(input);
    }
}
