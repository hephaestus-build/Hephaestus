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
 * ({@link HephTurnRequest}) has no {@link AgentJob}. Per-variant accessors carry the
 * variant-specific identity instead.
 */
public sealed interface ContextRequest
        permits ContextRequest.PracticeReviewRequest,
                ContextRequest.IssueReviewRequest,
                ContextRequest.HephTurnRequest,
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
     * Build the materialised issue review context: issue metadata, the comment thread, and the
     * state-transition timeline — NO diff. Carries the {@link AgentJob} the practice runner executes.
     */
    record IssueReviewRequest(AgentJob job) implements ContextRequest {
        public IssueReviewRequest {
            Objects.requireNonNull(job, "job must not be null");
        }
    }

    /** Primary document review, identified by {@code docs_document_id} in the job metadata. */
    record DocumentReviewRequest(AgentJob job) implements ContextRequest {
        public DocumentReviewRequest {
            Objects.requireNonNull(job, "job must not be null");
        }
    }

    /**
     * Build the context documents of a Heph turn ({@code MentorContextKeys.ALLOWED_OUTPUT_KEYS}). There is no
     * {@link AgentJob}: a Heph turn belongs to a conversation.
     *
     * @param workspaceId    workspace scoping for every content source's queries
     * @param developerId    the active user the content sources describe
     * @param conversationId the Heph conversation the turn runs in
     * @param currentEntryId the question the turn answers; providers that read the question use its text
     */
    record HephTurnRequest(
            long workspaceId,
            long developerId,
            UUID conversationId,
            @Nullable UUID currentEntryId) implements ContextRequest {
        public HephTurnRequest(long workspaceId, long developerId, UUID conversationId) {
            this(workspaceId, developerId, conversationId, null);
        }

        public HephTurnRequest {
            Objects.requireNonNull(conversationId, "conversationId must not be null");
            if (workspaceId <= 0) {
                throw new IllegalArgumentException("workspaceId must be positive, got " + workspaceId);
            }
            if (developerId <= 0) {
                throw new IllegalArgumentException("developerId must be positive, got " + developerId);
            }
        }
    }

    /** Primary Slack thread review, identified by {@code slack_channel_id} and {@code slack_thread_ts}. */
    record ConversationReviewRequest(AgentJob job) implements ContextRequest {
        public ConversationReviewRequest {
            Objects.requireNonNull(job, "job must not be null");
        }
    }
}
