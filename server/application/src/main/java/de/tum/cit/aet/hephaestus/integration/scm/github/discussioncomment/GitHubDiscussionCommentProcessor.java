package de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.Discussion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussioncomment.DiscussionComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussioncomment.DiscussionCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.BaseGitHubProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment.dto.GitHubDiscussionCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.time.Instant;
import java.util.HashSet;
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
 * Processor for GitHub Discussion Comments.
 * <p>
 * This service handles the conversion of GitHubDiscussionCommentDTO to DiscussionComment entities.
 * <p>
 * Discussion comments can be top-level comments or replies to other comments.
 * Reply threading is handled via the parentComment relationship, resolved by node ID.
 */
@Slf4j
@Service
public class GitHubDiscussionCommentProcessor extends BaseGitHubProcessor {

    private final DiscussionCommentRepository commentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubDiscussionCommentProcessor(
            UserRepository userRepository,
            LabelRepository labelRepository,
            MilestoneRepository milestoneRepository,
            GitHubUserProcessor gitHubUserProcessor,
            DiscussionCommentRepository commentRepository,
            ApplicationEventPublisher eventPublisher) {
        super(userRepository, labelRepository, milestoneRepository, gitHubUserProcessor);
        this.commentRepository = commentRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process a GitHub discussion comment DTO and persist it as a DiscussionComment entity.
     *
     * @param dto        the discussion comment DTO
     * @param discussion the parent discussion entity
     * @param context    the processing context
     * @return the created or updated DiscussionComment entity, or null if processing failed
     */
    @Transactional
    public @Nullable DiscussionComment process(
            GitHubDiscussionCommentDTO dto, Discussion discussion, ProcessingContext context) {
        Long dbId = dto.getDatabaseId();
        if (dbId == null) {
            log.warn("Skipped discussion comment processing: reason=missingDatabaseId");
            return null;
        }

        // Check if this is an update
        Long providerId = Objects.requireNonNull(context.providerId());
        Optional<DiscussionComment> existingOpt = commentRepository.findByNativeIdAndProviderId(dbId, providerId);
        boolean isNew = existingOpt.isEmpty();

        // Resolve related entities
        User author = dto.author() != null ? findOrCreateUser(dto.author(), providerId) : null;

        // Get or create the comment
        DiscussionComment comment = existingOpt.orElseGet(() -> {
            DiscussionComment c = new DiscussionComment();
            c.setNativeId(dbId);
            c.setProvider(Objects.requireNonNull(context.provider()));
            return c;
        });

        // Track changed fields for edit events
        Set<String> changedFields = new HashSet<>();
        if (!isNew) {
            if (dto.body() != null && !dto.body().equals(comment.getBody())) {
                changedFields.add("body");
            }
            if (dto.isAnswer() != comment.isAnswer()) {
                changedFields.add("isAnswer");
            }
            if (dto.isMinimized() != comment.isMinimized()) {
                changedFields.add("isMinimized");
            }
        }

        // Update fields
        comment.setBody(sanitize(dto.body()));
        comment.setHtmlUrl(dto.htmlUrl());
        comment.setAnswer(dto.isAnswer());
        comment.setMinimized(dto.isMinimized());
        comment.setMinimizedReason(dto.minimizedReason());
        comment.setAuthorAssociation(convertAuthorAssociation(dto.authorAssociation()));
        comment.setCreatedAt(dto.createdAt());
        comment.setUpdatedAt(dto.updatedAt());
        comment.setLastSyncAt(Instant.now());

        comment.setDiscussion(discussion);
        comment.setAuthor(author);

        // Note: Parent comment resolution is handled in a second pass
        // since the parent may not exist yet during initial sync

        // Save the comment
        comment = commentRepository.save(comment);

        // Publish domain events
        Long discussionId = Objects.requireNonNull(discussion.getId());
        if (isNew) {
            eventPublisher.publishEvent(new ScmDomainEvent.DiscussionCommentCreated(
                    ScmEventPayload.DiscussionCommentData.from(comment), discussionId, EventContext.from(context)));
            log.debug("Created discussion comment: commentId={}", dbId);
        } else if (!changedFields.isEmpty()) {
            eventPublisher.publishEvent(new ScmDomainEvent.DiscussionCommentEdited(
                    ScmEventPayload.DiscussionCommentData.from(comment),
                    discussionId,
                    changedFields,
                    EventContext.from(context)));
            log.debug("Updated discussion comment: commentId={}, changedFields={}", dbId, changedFields);
        }

        return comment;
    }

    /**
     * Delete a discussion comment by its database ID.
     * <p>
     * This method is the single entry point for discussion comment deletion,
     * following the pattern where all data mutations are handled by processors.
     *
     * @param commentDto the comment DTO containing the ID to delete
     * @param context    the processing context for event publishing
     */
    @Transactional
    public void processDeleted(GitHubDiscussionCommentDTO commentDto, ProcessingContext context) {
        Long dbId = commentDto.getDatabaseId();
        if (dbId == null) {
            log.warn("Cannot delete discussion comment: reason=missingDatabaseId");
            return;
        }

        commentRepository
                .findByNativeIdAndProviderId(dbId, Objects.requireNonNull(context.providerId()))
                .ifPresent(comment -> {
                    Long discussionId = comment.getDiscussion() != null
                            ? comment.getDiscussion().getId()
                            : null;

                    // CRITICAL: Remove from parent Discussion's collection BEFORE deleting
                    // to prevent TransientObjectException with orphanRemoval=true
                    Discussion parentDiscussion = comment.getDiscussion();
                    if (parentDiscussion != null) {
                        parentDiscussion.getComments().remove(comment);
                    }

                    commentRepository.delete(comment);
                    eventPublisher.publishEvent(new ScmDomainEvent.DiscussionCommentDeleted(
                            dbId, discussionId, EventContext.from(context)));
                    log.info("Deleted discussion comment: commentId={}", dbId);
                });
    }

    /**
     * Resolve reply threading after all comments have been created.
     * <p>
     * This method should be called after processing all comments to establish
     * parent-child relationships based on the replyToNodeId.
     *
     * @param comment       the comment to update
     * @param parentComment the parent comment (if this is a reply)
     */
    @Transactional
    public void resolveParentComment(DiscussionComment comment, @Nullable DiscussionComment parentComment) {
        if (parentComment != null) {
            comment.setParentComment(parentComment);
            commentRepository.save(comment);
            log.debug("Resolved parent comment: commentId={}, parentId={}", comment.getId(), parentComment.getId());
        }
    }

    /**
     * Convert author association string to AuthorAssociation enum.
     */
    @Nullable
    private AuthorAssociation convertAuthorAssociation(@Nullable String association) {
        if (association == null) {
            return null;
        }
        return switch (association.toUpperCase(Locale.ROOT)) {
            case "COLLABORATOR" -> AuthorAssociation.COLLABORATOR;
            case "CONTRIBUTOR" -> AuthorAssociation.CONTRIBUTOR;
            case "FIRST_TIME_CONTRIBUTOR" -> AuthorAssociation.FIRST_TIME_CONTRIBUTOR;
            case "FIRST_TIMER" -> AuthorAssociation.FIRST_TIMER;
            case "MANNEQUIN" -> AuthorAssociation.MANNEQUIN;
            case "MEMBER" -> AuthorAssociation.MEMBER;
            case "OWNER" -> AuthorAssociation.OWNER;
            case "NONE" -> AuthorAssociation.NONE;
            default -> AuthorAssociation.NONE;
        };
    }
}
