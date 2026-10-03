package de.tum.cit.aet.hephaestus.integration.scm.github.discussion;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.Discussion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionCategory;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionCategoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.BaseGitHubProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussion.dto.GitHubDiscussionCategoryDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussion.dto.GitHubDiscussionDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitHub Discussions.
 * <p>
 * This service handles the conversion of GitHubDiscussionDTO to Discussion entities,
 * persists them, and manages related entities (categories, labels).
 * <p>
 * <b>Design Principles:</b>
 * <ul>
 * <li>Single processing path for all data sources (sync and webhooks)</li>
 * <li>Idempotent operations via upsert pattern</li>
 * <li>Categories are resolved or created before the discussion</li>
 * </ul>
 */
@Slf4j
@Service
public class GitHubDiscussionProcessor extends BaseGitHubProcessor {

    private final DiscussionRepository discussionRepository;
    private final DiscussionCategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubDiscussionProcessor(
            UserRepository userRepository,
            LabelRepository labelRepository,
            MilestoneRepository milestoneRepository,
            GitHubUserProcessor gitHubUserProcessor,
            DiscussionRepository discussionRepository,
            DiscussionCategoryRepository categoryRepository,
            ApplicationEventPublisher eventPublisher) {
        super(userRepository, labelRepository, milestoneRepository, gitHubUserProcessor);
        this.discussionRepository = discussionRepository;
        this.categoryRepository = categoryRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process a GitHub discussion DTO and persist it as a Discussion entity.
     * <p>
     * Uses atomic upsert to prevent race conditions when concurrent threads
     * (e.g., sync and webhook) process the same discussion.
     *
     * @param dto     the discussion DTO
     * @param context the processing context with repository information
     * @return the created or updated Discussion entity, or null if processing failed
     */
    @Transactional
    public @Nullable Discussion process(GitHubDiscussionDTO dto, ProcessingContext context) {
        return processInternal(dto, context);
    }

    private @Nullable Discussion processInternal(GitHubDiscussionDTO dto, ProcessingContext context) {
        Long dbId = dto.getDatabaseId();
        if (dbId == null) {
            log.warn("Skipped discussion processing: reason=missingDatabaseId, discussionNumber={}", dto.number());
            return null;
        }

        Repository repository =
                Objects.requireNonNull(context.repository(), "Discussion processing requires a repository");
        Long providerId = Objects.requireNonNull(context.providerId(), "Discussion processing requires a provider");

        // Check if this is an update and apply stale-data protection
        Optional<Discussion> existingOpt =
                discussionRepository.findByRepositoryIdAndNumber(repository.getId(), dto.number());
        boolean isNew = existingOpt.isEmpty();

        // Skip update if existing data is newer (prevents stale webhooks from overwriting)
        if (!isNew) {
            Discussion existing = existingOpt.get();
            if (existing.getUpdatedAt() != null
                    && dto.updatedAt() != null
                    && !dto.updatedAt().isAfter(existing.getUpdatedAt())) {
                log.debug(
                        "Skipped stale discussion update: discussionId={}, existingUpdatedAt={}, dtoUpdatedAt={}",
                        existing.getId(),
                        existing.getUpdatedAt(),
                        dto.updatedAt());
                return existing;
            }
        }

        // Resolve related entities BEFORE the upsert
        User author = dto.author() != null ? findOrCreateUser(dto.author(), providerId) : null;
        User answerChosenBy = dto.answerChosenBy() != null ? findOrCreateUser(dto.answerChosenBy(), providerId) : null;
        DiscussionCategory category = dto.category() != null ? findOrCreateCategory(dto.category(), repository) : null;

        // Use atomic upsert to handle concurrent inserts
        // This uses ON CONFLICT (repository_id, number) DO UPDATE
        Instant now = Instant.now();
        Discussion.State state = dto.isClosed() ? Discussion.State.CLOSED : Discussion.State.OPEN;
        Discussion.StateReason stateReason = convertStateReason(dto.stateReason());
        Discussion.LockReason lockReason = convertLockReason(dto.activeLockReason());

        discussionRepository.upsertCore(
                dbId,
                providerId,
                repository.getId(),
                dto.number(),
                Objects.requireNonNullElse(sanitize(dto.title()), ""),
                sanitize(dto.body()),
                dto.htmlUrl(),
                state.name(),
                stateReason != null ? stateReason.name() : null,
                dto.locked(),
                lockReason != null ? lockReason.name() : null,
                dto.closedAt(),
                dto.answerChosenAt(),
                dto.commentsCount(),
                dto.upvoteCount(),
                now,
                dto.createdAt(),
                dto.updatedAt(),
                author != null ? author.getId() : null,
                category != null ? category.getId() : null,
                answerChosenBy != null ? answerChosenBy.getId() : null);

        // Fetch the discussion to get a managed entity and handle relationships
        Discussion discussion = discussionRepository
                .findByRepositoryIdAndNumber(repository.getId(), dto.number())
                .orElseThrow(() -> new IllegalStateException("Discussion not found after upsert: repositoryId="
                        + repository.getId() + ", number=" + dto.number()));

        // Handle ManyToMany relationships (labels) - these can't be done in the upsert
        boolean labelsChanged =
                updateLabels(Objects.requireNonNullElse(dto.labels(), List.of()), discussion.getLabels(), repository);

        // Save relationship changes
        if (labelsChanged) {
            discussion = discussionRepository.save(discussion);
        }

        // Note: answerComment is set separately after comments are processed

        // Publish domain events
        EventContext eventContext = EventContext.from(context);
        ScmEventPayload.DiscussionData discussionData = ScmEventPayload.DiscussionData.from(discussion);

        if (isNew) {
            log.debug("Created discussion: discussionId={}, discussionNumber={}", dbId, dto.number());
            eventPublisher.publishEvent(new ScmDomainEvent.DiscussionCreated(discussionData, eventContext));
        } else {
            Set<String> changedFields = computeChangedFields(existingOpt.get(), discussion);
            if (labelsChanged) {
                changedFields.add("labels");
            }
            if (!changedFields.isEmpty()) {
                eventPublisher.publishEvent(
                        new ScmDomainEvent.DiscussionUpdated(discussionData, changedFields, eventContext));
                log.debug("Updated discussion: discussionId={}, changedFields={}", dbId, changedFields);
            }
        }

        return discussion;
    }

    /**
     * Process a deleted discussion event.
     * Removes the discussion from the database and publishes a DiscussionDeleted domain event.
     *
     * @param dto     the discussion DTO (used to locate the discussion)
     * @param context the processing context
     */
    @Transactional
    public void processDeleted(GitHubDiscussionDTO dto, ProcessingContext context) {
        EventContext eventContext = EventContext.from(context);

        // With synthetic PKs, we cannot use deleteById(nativeId) because the PK is
        // auto-generated and differs from the native provider ID. Look up by natural key instead.
        discussionRepository
                .findByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository(), "Discussion deletion requires a repository")
                                .getId(),
                        dto.number())
                .ifPresent(discussion -> {
                    Long discussionId = discussion.getId();
                    discussionRepository.delete(discussion);
                    eventPublisher.publishEvent(new ScmDomainEvent.DiscussionDeleted(discussionId, eventContext));
                    log.info("Deleted discussion: discussionId={}, discussionNumber={}", discussionId, dto.number());
                });
    }

    /**
     * Process a closed discussion event.
     * Publishes a DiscussionClosed domain event in addition to the standard update.
     *
     * @param dto     the discussion DTO
     * @param context the processing context
     * @return the updated Discussion entity, or null if processing failed
     */
    @Transactional
    public @Nullable Discussion processClosed(GitHubDiscussionDTO dto, ProcessingContext context) {
        Discussion discussion = processInternal(dto, context);
        if (discussion != null) {
            String stateReason = dto.stateReason() != null ? dto.stateReason() : null;
            eventPublisher.publishEvent(new ScmDomainEvent.DiscussionClosed(
                    ScmEventPayload.DiscussionData.from(discussion), stateReason, EventContext.from(context)));
            log.debug("Closed discussion: discussionId={}, stateReason={}", discussion.getId(), stateReason);
        }
        return discussion;
    }

    /**
     * Process a reopened discussion event.
     * Publishes a DiscussionReopened domain event in addition to the standard update.
     *
     * @param dto     the discussion DTO
     * @param context the processing context
     * @return the updated Discussion entity, or null if processing failed
     */
    @Transactional
    public @Nullable Discussion processReopened(GitHubDiscussionDTO dto, ProcessingContext context) {
        Discussion discussion = processInternal(dto, context);
        if (discussion != null) {
            eventPublisher.publishEvent(new ScmDomainEvent.DiscussionReopened(
                    ScmEventPayload.DiscussionData.from(discussion), EventContext.from(context)));
            log.debug("Reopened discussion: discussionId={}", discussion.getId());
        }
        return discussion;
    }

    /**
     * Process an answered discussion event.
     * Publishes a DiscussionAnswered domain event in addition to the standard update.
     *
     * @param dto     the discussion DTO
     * @param context the processing context
     * @return the updated Discussion entity, or null if processing failed
     */
    @Transactional
    public @Nullable Discussion processAnswered(GitHubDiscussionDTO dto, ProcessingContext context) {
        Discussion discussion = processInternal(dto, context);
        if (discussion != null) {
            // The answer comment's database ID, if available from the DTO
            Long answerCommentId =
                    dto.answerComment() != null ? dto.answerComment().getDatabaseId() : null;
            eventPublisher.publishEvent(new ScmDomainEvent.DiscussionAnswered(
                    ScmEventPayload.DiscussionData.from(discussion),
                    answerCommentId != null ? answerCommentId : 0L,
                    EventContext.from(context)));
            log.debug("Answered discussion: discussionId={}, answerCommentId={}", discussion.getId(), answerCommentId);
        }
        return discussion;
    }

    /**
     * Find or create a discussion category using atomic upsert.
     * <p>
     * Categories use the node ID (String) as the primary key since GitHub's GraphQL
     * API doesn't expose databaseId for DiscussionCategory.
     * <p>
     * Uses atomic upsert to prevent race conditions when concurrent threads
     * (e.g., sync and webhook) try to create the same category simultaneously.
     */
    @Nullable
    private DiscussionCategory findOrCreateCategory(GitHubDiscussionCategoryDTO dto, Repository repository) {
        if (dto == null || dto.nodeId() == null) {
            return null;
        }

        String slug = dto.slug() != null
                ? dto.slug()
                : dto.name().toLowerCase(Locale.ROOT).replace(' ', '-');

        // Use atomic upsert to handle concurrent inserts safely
        categoryRepository.upsertCategory(
                dto.nodeId(),
                dto.name(),
                slug,
                dto.emoji(),
                dto.description(),
                dto.isAnswerable(),
                repository.getId(),
                dto.createdAt(),
                dto.updatedAt());

        // Fetch the managed entity after upsert
        return categoryRepository.findById(dto.nodeId()).orElse(null);
    }

    /**
     * Computes which fields changed between the old and new discussion state.
     */
    private Set<String> computeChangedFields(Discussion oldDiscussion, Discussion newDiscussion) {
        Set<String> changedFields = new HashSet<>();

        if (!Objects.equals(oldDiscussion.getTitle(), newDiscussion.getTitle())) {
            changedFields.add("title");
        }
        if (!Objects.equals(oldDiscussion.getBody(), newDiscussion.getBody())) {
            changedFields.add("body");
        }
        if (oldDiscussion.getState() != newDiscussion.getState()) {
            changedFields.add("state");
        }
        if (!Objects.equals(oldDiscussion.getStateReason(), newDiscussion.getStateReason())) {
            changedFields.add("stateReason");
        }
        if (oldDiscussion.isLocked() != newDiscussion.isLocked()) {
            changedFields.add("locked");
        }
        if (!Objects.equals(oldDiscussion.getActiveLockReason(), newDiscussion.getActiveLockReason())) {
            changedFields.add("activeLockReason");
        }
        if (!Objects.equals(oldDiscussion.getClosedAt(), newDiscussion.getClosedAt())) {
            changedFields.add("closedAt");
        }
        if (!Objects.equals(oldDiscussion.getAnswerChosenAt(), newDiscussion.getAnswerChosenAt())) {
            changedFields.add("answerChosenAt");
        }
        if (oldDiscussion.getCommentsCount() != newDiscussion.getCommentsCount()) {
            changedFields.add("commentsCount");
        }
        if (oldDiscussion.getUpvoteCount() != newDiscussion.getUpvoteCount()) {
            changedFields.add("upvoteCount");
        }
        if (!Objects.equals(oldDiscussion.getCategory(), newDiscussion.getCategory())) {
            changedFields.add("category");
        }

        return changedFields;
    }

    /**
     * Convert state reason string to Discussion.StateReason enum.
     */
    private Discussion.@Nullable StateReason convertStateReason(@Nullable String stateReason) {
        if (stateReason == null) {
            return null;
        }
        return switch (stateReason.toUpperCase(Locale.ROOT)) {
            case "RESOLVED" -> Discussion.StateReason.RESOLVED;
            case "OUTDATED" -> Discussion.StateReason.OUTDATED;
            case "DUPLICATE" -> Discussion.StateReason.DUPLICATE;
            case "REOPENED" -> Discussion.StateReason.REOPENED;
            default -> Discussion.StateReason.UNKNOWN;
        };
    }

    /**
     * Convert lock reason string to Discussion.LockReason enum.
     */
    private Discussion.@Nullable LockReason convertLockReason(@Nullable String lockReason) {
        if (lockReason == null) {
            return null;
        }
        return switch (lockReason.toUpperCase(Locale.ROOT)) {
            case "OFF_TOPIC" -> Discussion.LockReason.OFF_TOPIC;
            case "RESOLVED" -> Discussion.LockReason.RESOLVED;
            case "SPAM" -> Discussion.LockReason.SPAM;
            case "TOO_HEATED" -> Discussion.LockReason.TOO_HEATED;
            default -> null;
        };
    }
}
