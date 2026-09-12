package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Polymorphic request handed to {@link WorkspaceContextBuilder}. Providers narrow on the
 * variant via a sealed switch — no {@code instanceof} chains in the orchestrator.
 *
 * <p>There is no universal {@code job()} accessor: synchronous mentor chat
 * ({@link MentorChatRequest}) has no {@link AgentJob}. Per-variant accessors carry the
 * variant-specific identity instead.
 */
public sealed interface ContextRequest
        permits ContextRequest.PracticeReviewRequest,
                ContextRequest.IssueReviewRequest,
                ContextRequest.MentorChatRequest,
                ContextRequest.ConversationReviewRequest,
                ContextRequest.DocumentReviewRequest {
    /**
     * Build the materialised PR-review context: metadata, comments, diff, developer history.
     * Carries the {@link AgentJob} the practice runner will execute and the one Git preparation the
     * clone-reading sources share.
     */
    record PracticeReviewRequest(AgentJob job, ReviewPreparation preparation) implements ContextRequest {
        public PracticeReviewRequest(AgentJob job) {
            this(job, new ReviewPreparation());
        }

        public PracticeReviewRequest {
            Objects.requireNonNull(job, "job must not be null");
        }
    }

    /**
     * Build the materialised issue-detection context: issue metadata, the comment thread, and the
     * state-transition timeline — NO diff. Carries the {@link AgentJob} the practice runner executes.
     */
    record IssueReviewRequest(AgentJob job) implements ContextRequest {
        public IssueReviewRequest {
            Objects.requireNonNull(job, "job must not be null");
        }
    }

    /**
     * Build the materialised docs.document review context: one written document — its prose, its
     * collection, who wrote it and when it last moved. NO diff, NO code, NO repository clone; the
     * subject is the document itself rather than a change that happens to mention one, which is what
     * separates this from the linked-document evidence a pull-request review reads. Carries the
     * {@link AgentJob} the practice runner executes; the document is identified by
     * {@code docs_document_id} in the job metadata, which is the mirror row the signal ledger recorded.
     */
    record DocumentReviewRequest(AgentJob job) implements ContextRequest {
        public DocumentReviewRequest {
            Objects.requireNonNull(job, "job must not be null");
        }
    }

    /**
     * Build the materialised mentor-chat context: user activity, workspace context, practice
     * catalog, findings history. There is no {@link AgentJob} — mentor chat is synchronous
     * and runs against a long-lived interactive sandbox keyed by {@code (workspaceId, developerId)}.
     *
     * @param workspaceId   workspace scoping for every content source's queries
     * @param developerId the active user the content sources describe
     * @param threadId      conversation thread the request originated from (used by providers
     *                      that need per-thread cache keys)
     * @param currentUserMessageId message persisted for the active turn; history providers exclude it
     */
    record MentorChatRequest(
            long workspaceId,
            long developerId,
            UUID threadId,
            @Nullable UUID currentUserMessageId) implements ContextRequest {
        public MentorChatRequest(long workspaceId, long developerId, UUID threadId) {
            this(workspaceId, developerId, threadId, null);
        }

        public MentorChatRequest {
            threadId = Objects.requireNonNull(threadId, "threadId must not be null");
            if (workspaceId <= 0) {
                throw new IllegalArgumentException("workspaceId must be positive, got " + workspaceId);
            }
            if (developerId <= 0) {
                throw new IllegalArgumentException("developerId must be positive, got " + developerId);
            }
        }
    }

    /**
     * Build the materialised conversation-detection context: the ordered human turns of one settled
     * Slack thread ({@code inputs/context/conversation_thread.json}) plus the workspace-level project
     * inventory, aggregated across every monitored repository since a conversation isn't anchored to
     * one. NO diff, NO code, NO repository clone: providers that require a mounted worktree stay
     * {@code PracticeReviewRequest}-only. Carries the {@link AgentJob} the practice runner executes;
     * the thread is identified by {@code slack_channel_id} / {@code slack_thread_ts} in the job metadata.
     */
    record ConversationReviewRequest(AgentJob job) implements ContextRequest {
        public ConversationReviewRequest {
            Objects.requireNonNull(job, "job must not be null");
        }
    }
}
