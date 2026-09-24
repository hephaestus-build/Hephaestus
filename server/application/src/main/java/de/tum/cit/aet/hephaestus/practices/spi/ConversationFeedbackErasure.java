package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;

/**
 * Practices-owned erasure port (dependency inversion). Hard-deletes the {@code chat.conversation_thread}-derived
 * observations and feedback that were composed over a set of Slack threads, so a source module that owns those
 * threads (today {@code integration.slack}) can complete a GDPR Art. 17 erasure of the DERIVED practice rows —
 * not just the raw source content — without importing any {@code practices} domain type.
 *
 * <p><strong>Why this exists (no module cycle).</strong> The thread → derived-feedback link is a one-way
 * {@code integration.slack → practices::spi} dependency. The implementation lives INSIDE {@code practices} (an
 * adapter on the practices repositories); the caller injects only this interface. No reverse edge is
 * introduced, so no Spring Modulith cycle forms even though practices never depends on Slack.
 *
 * <p><strong>Scope (the no-regression contract).</strong> An implementation MUST delete ONLY rows where
 * {@code artifact_kind = chat.conversation_thread} AND {@code artifact_id} is one of {@code slackThreadIds} AND that
 * belong to {@code workspaceId}. PR/ISSUE observations and feedback, and rows of any other workspace, MUST be
 * left untouched — the artifact-type, artifact-id, and workspace predicates are all load-bearing.
 */
public interface ConversationFeedbackErasure {
    /**
     * Hard-delete every {@code chat.conversation_thread} observation and feedback unit for {@code workspaceId} whose
     * {@code artifact_id} (the {@code slack_thread} id) is one of {@code slackThreadIds}, cascading to the bound
     * {@code feedback_observation} / {@code feedback_placement} / {@code feedback_reaction} rows via their DB-level
     * {@code ON DELETE CASCADE}. Transactional, idempotent (a thread with no derived rows deletes nothing), and
     * workspace-scoped. An empty collection is a no-op.
     *
     * @param workspaceId    the tenant the derived rows must belong to
     * @param slackThreadIds the {@code slack_thread} ids whose derived rows are being erased
     * @return the number of observation + feedback rows deleted
     */
    int eraseForThreads(long workspaceId, Collection<Long> slackThreadIds);

    /**
     * Hard-delete <em>every</em> {@code chat.conversation_thread} observation and feedback unit for {@code workspaceId},
     * regardless of which {@code slack_thread} the row is anchored to — the whole-tenant erasure a source module
     * invokes when the entire Slack surface is being torn down (app uninstall, workspace purge). Feedback is deleted
     * first so its DB-level {@code ON DELETE CASCADE} clears the bound {@code feedback_observation} /
     * {@code feedback_placement} / {@code feedback_reaction} rows, then the observations. Transactional, idempotent (a
     * workspace with no conversation-derived rows deletes nothing), and strictly scoped to
     * {@code artifact_kind = chat.conversation_thread} + {@code workspaceId} — PR/ISSUE rows and other tenants' rows are
     * never touched (the artifact-type and workspace predicates are both load-bearing).
     *
     * @param workspaceId the tenant whose conversation-derived rows are being erased
     * @return the number of observation + feedback rows deleted
     */
    int eraseAllConversationForWorkspace(long workspaceId);

    /**
     * Hard-delete the {@code chat.conversation_thread} observations and feedback that a single person is the <em>subject</em>
     * of within {@code workspaceId} — the derived-content half of a person opt-out or account hard-delete. Feedback is
     * matched on {@code feedback.about_user_id = aboutUserId} and observations on
     * {@code observation.about_user_id = aboutUserId} (ADR 0022: the about-user is who the row is filed against, not
     * who it is delivered to). Feedback is deleted first so its {@code ON DELETE CASCADE} clears the join/placement/
     * reaction children, then the observations. Transactional, idempotent, and strictly scoped to
     * {@code artifact_kind = chat.conversation_thread} + {@code workspaceId} + {@code about_user_id = aboutUserId} — another
     * person's rows, PR/ISSUE rows, and other tenants' rows are all left intact (all three predicates are load-bearing).
     *
     * @param workspaceId the tenant the derived rows must belong to
     * @param aboutUserId the subject ({@code about_user_id}) whose conversation-derived rows are being erased
     * @return the number of observation + feedback rows deleted
     */
    int eraseConversationFeedbackAboutUser(long workspaceId, long aboutUserId);
}
