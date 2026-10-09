package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueEvidenceRevision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.ResourceHolderSupport;
import org.springframework.transaction.support.ResourceHolderSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Updates issue evidence in the comment transaction, once per issue for a sync batch. */
@Component
@ConditionalOnServerRole
public class IssueDiscussionBridge {

    private final LockedIssueRow lockedRow;
    private final IssueEvidenceRevision revisions;
    private final IssueObservationSuperseder superseder;
    private final ObjectProvider<IssueAgentJobEventListener> listener;

    IssueDiscussionBridge(
            LockedIssueRow lockedRow,
            IssueEvidenceRevision revisions,
            IssueObservationSuperseder superseder,
            ObjectProvider<IssueAgentJobEventListener> listener) {
        this.lockedRow = lockedRow;
        this.revisions = revisions;
        this.superseder = superseder;
        this.listener = listener;
    }

    @EventListener
    public void onCreated(ScmDomainEvent.CommentCreated event) {
        translate(event.issueId(), event.context());
    }

    @EventListener
    public void onUpdated(ScmDomainEvent.CommentUpdated event) {
        translate(event.issueId(), event.context());
    }

    @EventListener
    public void onDeleted(ScmDomainEvent.CommentDeleted event) {
        translate(event.issueId(), event.context());
    }

    private void translate(@Nullable Long issueId, EventContext context) {
        // Snapshot and signal writes must commit with the comment.
        if (issueId == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        if (context.isSync()) {
            enqueue(issueId, context);
        } else {
            update(issueId, context);
        }
    }

    private void enqueue(long issueId, EventContext context) {
        PendingBatch batch = (PendingBatch) TransactionSynchronizationManager.getResource(this);
        if (batch == null) {
            batch = new PendingBatch();
            TransactionSynchronizationManager.bindResource(this, batch);
            PendingBatch pending = batch;
            TransactionSynchronizationManager.registerSynchronization(
                    new ResourceHolderSynchronization<PendingBatch, IssueDiscussionBridge>(batch, this) {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            pending.issues.forEach(IssueDiscussionBridge.this::update);
                        }
                    });
        }
        batch.issues.put(issueId, context);
    }

    private static final class PendingBatch extends ResourceHolderSupport {
        private final Map<Long, EventContext> issues = new TreeMap<>();
    }

    private void update(long issueId, EventContext context) {
        Issue issue = lockedRow.lockAndRead(issueId).orElse(null);
        if (issue == null || issue.isPullRequest() || issue.getRepository() == null || issue.getDeletedAt() != null) {
            return;
        }
        ScmEventPayload.IssueData current = ScmEventPayload.IssueData.from(issue);
        var revision = revisions.of(current);
        if (Objects.equals(revision.value(), issue.getReviewSnapshotDigest())) {
            return;
        }
        superseder.advance(issueId, revision.value());
        listener.ifAvailable(value -> value.recordUpdate(
                new ScmDomainEvent.IssueUpdated(current, Set.of(ScmSignals.ISSUE_DISCUSSION_FIELD), context),
                revision));
    }
}
