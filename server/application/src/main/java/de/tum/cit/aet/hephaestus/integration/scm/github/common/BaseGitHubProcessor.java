package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.LabelIdUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.PostgresStringUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Base class for GitHub entity processors with shared helper methods.
 * <p>
 * Provides common functionality for finding or creating related entities
 * (users, labels, milestones) that is shared between Issue and PullRequest
 * processors.
 * <p>
 * <b>Design rationale:</b> These operations are identical across processors,
 * extracting them eliminates duplication and ensures consistent behavior.
 */
@Slf4j
public abstract class BaseGitHubProcessor {

    protected final UserRepository userRepository;
    protected final @Nullable LabelRepository labelRepository;
    protected final @Nullable MilestoneRepository milestoneRepository;
    private final GitHubUserProcessor gitHubUserProcessor;

    protected BaseGitHubProcessor(
            UserRepository userRepository,
            @Nullable LabelRepository labelRepository,
            @Nullable MilestoneRepository milestoneRepository,
            GitHubUserProcessor gitHubUserProcessor) {
        this.userRepository = userRepository;
        this.labelRepository = labelRepository;
        this.milestoneRepository = milestoneRepository;
        this.gitHubUserProcessor = gitHubUserProcessor;
    }

    /**
     * Find an existing user or create a new one from the DTO.
     * <p>
     * Delegates to {@link GitHubUserProcessor#findOrCreate(GitHubUserDTO, Long)} which handles
     * login conflicts (uk_user_login constraint) gracefully via INSERT ON CONFLICT DO NOTHING
     * and automatic login conflict resolution.
     *
     * @param dto the GitHub user DTO
     * @param providerId the git provider ID for multi-provider scoping
     */
    @Nullable
    protected User findOrCreateUser(GitHubUserDTO dto, Long providerId) {
        return gitHubUserProcessor.findOrCreate(dto, providerId);
    }

    /**
     * Find an existing label or create a new one from the DTO.
     * <p>
     * CRITICAL: Always looks up by repository + name FIRST because this is the unique
     * constraint (uq_label_repository_name). This handles the case where GraphQL sync
     * created a label with a deterministic ID, then a webhook arrives with the actual
     * GitHub databaseId. Looking up by the webhook's ID would miss the existing entity.
     * <p>
     * Uses atomic insertIfAbsent to prevent race conditions when concurrent threads
     * try to create the same label simultaneously.
     * <p>
     * NEVER changes the ID of an existing (managed) entity - Hibernate will throw
     * "identifier of an instance was altered" exception.
     */
    @Nullable
    protected Label findOrCreateLabel(GitHubLabelDTO dto, Repository repository) {
        if (dto == null || dto.name() == null || dto.name().isBlank()) {
            return null;
        }
        LabelRepository labels = Objects.requireNonNull(labelRepository);

        // ALWAYS check by unique key (repository_id + name) FIRST - this is the constraint we enforce.
        Optional<Label> existingOpt = labels.findByRepositoryIdAndName(repository.getId(), dto.name());

        // Fall back to nativeId lookup if name lookup didn't find it (handles label renames)
        if (existingOpt.isEmpty() && dto.id() != null) {
            Long providerId = Objects.requireNonNull(repository.getProvider().getId());
            existingOpt = labels.findByNativeIdAndProviderId(dto.id(), providerId);
        }

        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        // Use atomic insert to prevent race conditions.
        // If another thread inserted first, this returns 0 and we fetch the winner.
        long nativeId =
                dto.id() != null ? dto.id() : LabelIdUtils.generateDeterministicId(repository.getId(), dto.name());
        Long providerId = Objects.requireNonNull(repository.getProvider().getId());
        labels.insertIfAbsent(nativeId, providerId, dto.name(), dto.color(), repository.getId());

        // Always fetch by business key — the PK is auto-generated and unknown here.
        return labels.findByRepositoryIdAndName(repository.getId(), dto.name()).orElse(null);
    }

    /**
     * Find an existing milestone or create a new one from the DTO.
     * <p>
     * CRITICAL: Always looks up by repository + number FIRST because this is the unique
     * constraint (uk_milestone_number_repository). This handles the case where GraphQL sync
     * created a milestone with a deterministic ID, then a webhook arrives with the actual
     * GitHub databaseId. Looking up by the webhook's ID would miss the existing entity.
     * <p>
     * Uses atomic insertIfAbsent to prevent race conditions when concurrent threads
     * try to create the same milestone simultaneously.
     * <p>
     * For GraphQL responses that don't include databaseId (id is null), we generate a
     * deterministic negative ID to avoid collision with real GitHub IDs.
     * <p>
     * NEVER changes the ID of an existing (managed) entity - Hibernate will throw
     * "identifier of an instance was altered" exception.
     */
    @Nullable
    protected Milestone findOrCreateMilestone(GitHubMilestoneDTO dto, Repository repository) {
        if (dto == null || dto.number() <= 0) {
            return null;
        }
        MilestoneRepository milestones = Objects.requireNonNull(milestoneRepository);

        // ALWAYS check by unique key (repository_id + number) FIRST - this is the constraint we enforce.
        Optional<Milestone> existingOpt = milestones.findByNumberAndRepositoryId(dto.number(), repository.getId());

        // Fall back to ID lookup if number lookup didn't find it (handles edge cases)
        if (existingOpt.isEmpty() && dto.id() != null) {
            existingOpt = milestones.findById(dto.id());
        }

        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        // Use atomic insert to prevent race conditions.
        // If another thread inserted first, this returns 0 and we fetch the winner.
        Long milestoneId =
                dto.id() != null ? dto.id() : generateDeterministicMilestoneId(repository.getId(), dto.number());

        String title = dto.title() != null ? dto.title() : "Milestone " + dto.number();
        String htmlUrl = dto.htmlUrl() != null ? dto.htmlUrl() : "";
        String state =
                parseMilestoneState(dto.state() == null ? "open" : dto.state()).name();
        int openIssuesCount = dto.openIssuesCount() != null ? dto.openIssuesCount() : 0;
        int closedIssuesCount = dto.closedIssuesCount() != null ? dto.closedIssuesCount() : 0;

        Long providerId = Objects.requireNonNull(repository.getProvider().getId());
        int inserted = milestones.insertIfAbsent(
                milestoneId,
                providerId,
                dto.number(),
                title,
                dto.description(),
                state,
                htmlUrl,
                dto.dueOn(),
                openIssuesCount,
                closedIssuesCount,
                repository.getId(),
                dto.createdAt(),
                dto.updatedAt());

        if (inserted == 0) {
            // Another thread inserted first - fetch the winner
            return milestones
                    .findByNumberAndRepositoryId(dto.number(), repository.getId())
                    .orElse(null);
        }

        // We inserted - fetch the entity to return a managed instance.
        // Must look up by natural key (number + repository), not by milestoneId,
        // because the table uses auto-generated synthetic PKs (the milestoneId here
        // is the native provider ID stored in native_id, not the synthetic PK).
        return milestones
                .findByNumberAndRepositoryId(dto.number(), repository.getId())
                .orElse(null);
    }

    /**
     * Generate a deterministic negative ID for milestones created from GraphQL data.
     * <p>
     * Uses bit shifting to combine repo ID and milestone number without collision.
     * Uses negative values to avoid collision with real GitHub milestone IDs which are
     * always positive.
     */
    private Long generateDeterministicMilestoneId(Long repositoryId, int milestoneNumber) {
        long combined = (repositoryId << 32) | (milestoneNumber & 0xFFFFFFFFL);
        return -combined;
    }

    /**
     * Converts a GitHub API milestone state string to Milestone.State enum.
     */
    private Milestone.State parseMilestoneState(String state) {
        if (state == null) {
            log.warn("Milestone state is null, defaulting to OPEN. "
                    + "This may indicate missing data in webhook or GraphQL response.");
            return Milestone.State.OPEN;
        }
        return switch (state.toUpperCase()) {
            case "OPEN" -> Milestone.State.OPEN;
            case "CLOSED" -> Milestone.State.CLOSED;
            default -> {
                log.warn("Unknown milestone state '{}', defaulting to OPEN", state);
                yield Milestone.State.OPEN;
            }
        };
    }

    /**
     * Sanitize string for PostgreSQL storage (removes null characters).
     * Delegates to {@link PostgresStringUtils#sanitize(String)}.
     */
    @Nullable
    protected String sanitize(@Nullable String input) {
        return PostgresStringUtils.sanitize(input);
    }

    // Shared relationship update helpers (used by Issue and PR processors)

    /**
     * Updates assignees collection from DTO list.
     * Shared between Issue and PullRequest processors to avoid code duplication.
     *
     * @param assigneeDtos the assignee DTOs from GitHub (null means don't update)
     * @param currentAssignees the current assignee set to update (modified in place)
     * @return true if assignments changed, false otherwise
     */
    protected boolean updateAssignees(
            @Nullable List<GitHubUserDTO> assigneeDtos, Set<User> currentAssignees, Long providerId) {
        if (assigneeDtos == null) {
            return false;
        }

        Set<User> newAssignees = new HashSet<>();
        for (GitHubUserDTO assigneeDto : assigneeDtos) {
            User assignee = findOrCreateUser(assigneeDto, providerId);
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
     * Updates labels collection from DTO list.
     * Shared between Issue and PullRequest processors to avoid code duplication.
     *
     * @param labelDtos the label DTOs from GitHub (null means don't update)
     * @param currentLabels the current label collection to update (modified in place)
     * @param repository the repository context for label lookup/creation
     * @return true if labels changed, false otherwise
     */
    protected boolean updateLabels(
            @Nullable List<GitHubLabelDTO> labelDtos, Collection<Label> currentLabels, Repository repository) {
        if (labelDtos == null) {
            return false;
        }

        Set<Label> newLabels = new HashSet<>();
        for (GitHubLabelDTO labelDto : labelDtos) {
            Label label = findOrCreateLabel(labelDto, repository);
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

    /**
     * Replaces the pull request's requested reviewers with GitHub's list. GitHub lists only the reviewers it
     * is waiting for, so a listed reviewer carries no review state. A bot asked to review is left out, as the
     * sync leaves it out, so the two never disagree about the row.
     *
     * @param reviewerDtos the reviewer DTOs from GitHub (null means don't update)
     * @return true if reviewers changed, false otherwise
     */
    protected boolean updateRequestedReviewers(
            @Nullable List<GitHubUserDTO> reviewerDtos, PullRequest pullRequest, Long providerId) {
        if (reviewerDtos == null) {
            return false;
        }
        Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
        for (GitHubUserDTO reviewerDto : reviewerDtos) {
            if (reviewerDto.type() == User.Type.BOT) {
                continue;
            }
            User reviewer = findOrCreateUser(reviewerDto, providerId);
            if (reviewer != null) {
                reviewers.put(reviewer, null);
            }
        }
        return pullRequest.replaceRequestedReviewers(reviewers, null);
    }
}
