package de.tum.cit.aet.hephaestus.integration.scm.events;

import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class ScmDomainEvent {

    private ScmDomainEvent() {}

    public sealed interface Event
            permits IssueEvent,
                    PullRequestEvent,
                    LabelEvent,
                    MilestoneEvent,
                    CommentEvent,
                    ReviewEvent,
                    ReviewCommentEvent,
                    ReviewThreadEvent,
                    TeamEvent,
                    CommitEvent,
                    DiscussionEvent,
                    DiscussionCommentEvent {}

    public interface ContextualEvent {
        EventContext context();
    }

    public sealed interface IssueEvent extends Event, ContextualEvent
            permits IssueCreated,
                    IssueUpdated,
                    IssueClosed,
                    IssueReopened,
                    IssueDeleted,
                    IssueLabeled,
                    IssueUnlabeled,
                    IssueTyped,
                    IssueUntyped {
        ScmEventPayload.@Nullable IssueData issue();
    }

    public record IssueCreated(ScmEventPayload.IssueData issue, EventContext context) implements IssueEvent {}

    public record IssueUpdated(ScmEventPayload.IssueData issue, Set<String> changedFields, EventContext context)
            implements IssueEvent {}

    public record IssueClosed(
            ScmEventPayload.IssueData issue, @Nullable String stateReason, EventContext context)
            implements IssueEvent {}

    public record IssueReopened(ScmEventPayload.IssueData issue, EventContext context) implements IssueEvent {}

    public record IssueDeleted(Long issueId, EventContext context) implements IssueEvent {
        @Override
        public ScmEventPayload.@Nullable IssueData issue() {
            return null;
        }
    }

    public record IssueLabeled(ScmEventPayload.IssueData issue, ScmEventPayload.LabelData label, EventContext context)
            implements IssueEvent {}

    public record IssueUnlabeled(ScmEventPayload.IssueData issue, ScmEventPayload.LabelData label, EventContext context)
            implements IssueEvent {}

    public record IssueTyped(
            ScmEventPayload.IssueData issue, ScmEventPayload.IssueTypeData issueType, EventContext context)
            implements IssueEvent {}

    public record IssueUntyped(
            ScmEventPayload.IssueData issue, ScmEventPayload.@Nullable IssueTypeData previousType, EventContext context)
            implements IssueEvent {}

    public sealed interface PullRequestEvent extends Event, ContextualEvent
            permits PullRequestCreated,
                    PullRequestUpdated,
                    PullRequestClosed,
                    PullRequestMerged,
                    PullRequestReopened,
                    PullRequestLabeled,
                    PullRequestUnlabeled,
                    PullRequestReady,
                    PullRequestDrafted,
                    PullRequestSynchronized,
                    PullRequestChecksCompleted {
        ScmEventPayload.PullRequestData pullRequest();
    }

    public record PullRequestCreated(ScmEventPayload.PullRequestData pullRequest, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestUpdated(
            ScmEventPayload.PullRequestData pullRequest, Set<String> changedFields, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestClosed(
            ScmEventPayload.PullRequestData pullRequest, boolean wasMerged, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestMerged(ScmEventPayload.PullRequestData pullRequest, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestReopened(ScmEventPayload.PullRequestData pullRequest, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestLabeled(
            ScmEventPayload.PullRequestData pullRequest, ScmEventPayload.LabelData label, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestUnlabeled(
            ScmEventPayload.PullRequestData pullRequest, ScmEventPayload.LabelData label, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestReady(ScmEventPayload.PullRequestData pullRequest, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestDrafted(ScmEventPayload.PullRequestData pullRequest, EventContext context)
            implements PullRequestEvent {}

    public record PullRequestSynchronized(ScmEventPayload.PullRequestData pullRequest, EventContext context)
            implements PullRequestEvent {}

    /** Every check run of the pull request's head has ended, as a read of the provider listed them. */
    public record PullRequestChecksCompleted(ScmEventPayload.PullRequestData pullRequest, EventContext context)
            implements PullRequestEvent {}

    public sealed interface LabelEvent extends Event, ContextualEvent
            permits LabelCreated, LabelUpdated, LabelDeleted {}

    public record LabelCreated(ScmEventPayload.LabelData label, EventContext context) implements LabelEvent {}

    public record LabelUpdated(ScmEventPayload.LabelData label, EventContext context) implements LabelEvent {}

    public record LabelDeleted(Long labelId, String labelName, EventContext context) implements LabelEvent {}

    public sealed interface MilestoneEvent extends Event, ContextualEvent
            permits MilestoneCreated, MilestoneUpdated, MilestoneDeleted {}

    public record MilestoneCreated(ScmEventPayload.MilestoneData milestone, EventContext context)
            implements MilestoneEvent {}

    public record MilestoneUpdated(ScmEventPayload.MilestoneData milestone, EventContext context)
            implements MilestoneEvent {}

    public record MilestoneDeleted(Long milestoneId, String title, EventContext context) implements MilestoneEvent {}

    public sealed interface CommentEvent extends Event, ContextualEvent
            permits CommentCreated, CommentUpdated, CommentDeleted {
        Long issueId();
    }

    public record CommentCreated(ScmEventPayload.CommentData comment, Long issueId, EventContext context)
            implements CommentEvent {}

    public record CommentUpdated(
            ScmEventPayload.CommentData comment, Long issueId, Set<String> changedFields, EventContext context)
            implements CommentEvent {}

    public record CommentDeleted(Long commentId, @Nullable Long issueId, EventContext context)
            implements CommentEvent {}

    public sealed interface ReviewEvent extends Event, ContextualEvent
            permits ReviewSubmitted, ReviewEdited, ReviewDismissed {
        ScmEventPayload.ReviewData review();
    }

    public record ReviewSubmitted(ScmEventPayload.ReviewData review, EventContext context) implements ReviewEvent {}

    public record ReviewEdited(ScmEventPayload.ReviewData review, Set<String> changedFields, EventContext context)
            implements ReviewEvent {}

    public record ReviewDismissed(ScmEventPayload.ReviewData review, EventContext context) implements ReviewEvent {}

    public sealed interface ReviewCommentEvent extends Event, ContextualEvent
            permits ReviewCommentCreated, ReviewCommentEdited, ReviewCommentDeleted {
        Long pullRequestId();
    }

    public record ReviewCommentCreated(
            ScmEventPayload.ReviewCommentData comment, Long pullRequestId, EventContext context)
            implements ReviewCommentEvent {}

    public record ReviewCommentEdited(
            ScmEventPayload.ReviewCommentData comment,
            Long pullRequestId,
            Set<String> changedFields,
            EventContext context)
            implements ReviewCommentEvent {}

    public record ReviewCommentDeleted(Long commentId, Long pullRequestId, EventContext context)
            implements ReviewCommentEvent {}

    public sealed interface ReviewThreadEvent extends Event, ContextualEvent
            permits ReviewThreadResolved, ReviewThreadUnresolved {
        ScmEventPayload.ReviewThreadData thread();
    }

    public record ReviewThreadResolved(ScmEventPayload.ReviewThreadData thread, EventContext context)
            implements ReviewThreadEvent {}

    public record ReviewThreadUnresolved(ScmEventPayload.ReviewThreadData thread, EventContext context)
            implements ReviewThreadEvent {}

    public sealed interface TeamEvent extends Event, ContextualEvent permits TeamCreated, TeamUpdated, TeamDeleted {
        Long teamId();
    }

    public record TeamCreated(ScmEventPayload.TeamData team, EventContext context) implements TeamEvent {
        @Override
        public Long teamId() {
            return team.id();
        }
    }

    public record TeamUpdated(ScmEventPayload.TeamData team, Set<String> changedFields, EventContext context)
            implements TeamEvent {
        @Override
        public Long teamId() {
            return team.id();
        }
    }

    public record TeamDeleted(Long teamId, String teamName, EventContext context) implements TeamEvent {
        @Override
        public Long teamId() {
            return teamId;
        }
    }

    public sealed interface CommitEvent extends Event, ContextualEvent permits CommitCreated, CommitAuthorsReconciled {
        ScmEventPayload.@Nullable CommitData commit();
    }

    public record CommitCreated(ScmEventPayload.CommitData commit, EventContext context) implements CommitEvent {}

    /** Repository-wide author resolution permits ledger attribution to be checked again without per-commit events. */
    public record CommitAuthorsReconciled(@Nullable Long repositoryId, EventContext context) implements CommitEvent {
        @Override
        public ScmEventPayload.@Nullable CommitData commit() {
            return null;
        }
    }

    public sealed interface DiscussionEvent extends Event, ContextualEvent
            permits DiscussionCreated,
                    DiscussionUpdated,
                    DiscussionClosed,
                    DiscussionReopened,
                    DiscussionAnswered,
                    DiscussionDeleted {
        ScmEventPayload.@Nullable DiscussionData discussion();
    }

    public record DiscussionCreated(ScmEventPayload.DiscussionData discussion, EventContext context)
            implements DiscussionEvent {}

    public record DiscussionUpdated(
            ScmEventPayload.DiscussionData discussion, Set<String> changedFields, EventContext context)
            implements DiscussionEvent {}

    public record DiscussionClosed(
            ScmEventPayload.DiscussionData discussion,
            @Nullable String stateReason,
            EventContext context) implements DiscussionEvent {}

    public record DiscussionReopened(ScmEventPayload.DiscussionData discussion, EventContext context)
            implements DiscussionEvent {}

    public record DiscussionAnswered(
            ScmEventPayload.DiscussionData discussion, Long answerCommentId, EventContext context)
            implements DiscussionEvent {}

    public record DiscussionDeleted(Long discussionId, EventContext context) implements DiscussionEvent {
        @Override
        public ScmEventPayload.@Nullable DiscussionData discussion() {
            return null;
        }
    }

    public sealed interface DiscussionCommentEvent extends Event, ContextualEvent
            permits DiscussionCommentCreated, DiscussionCommentEdited, DiscussionCommentDeleted {
        Long discussionId();
    }

    public record DiscussionCommentCreated(
            ScmEventPayload.DiscussionCommentData comment, Long discussionId, EventContext context)
            implements DiscussionCommentEvent {}

    public record DiscussionCommentEdited(
            ScmEventPayload.DiscussionCommentData comment,
            Long discussionId,
            Set<String> changedFields,
            EventContext context)
            implements DiscussionCommentEvent {}

    public record DiscussionCommentDeleted(
            Long commentId, @Nullable Long discussionId, EventContext context) implements DiscussionCommentEvent {}
}
