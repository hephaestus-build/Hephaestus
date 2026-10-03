package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueEvidenceRevision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns a change to a closed issue's discussion into the issue update it is for that issue's review, so the
 * existing update path keys, coalesces and supersedes it.
 *
 * <p>Synchronous and inside the comment's transaction, so the update's before-commit listeners are registered
 * with that transaction rather than published from within another listener's commit callback. Whether anything
 * moved is decided by the evidence revision of the locked, re-read issue, not by the comment's text, so an edit or a
 * deletion is weighed the same way as a new comment, and Hephaestus's own feedback, which the revision ignores, moves
 * nothing.
 */
@Component
@ConditionalOnServerRole
public class ClosedIssueDiscussionBridge {

    private final LockedIssueRow lockedRow;
    private final IssueEvidenceRevision revisions;
    private final ApplicationEventPublisher events;

    ClosedIssueDiscussionBridge(
            LockedIssueRow lockedRow, IssueEvidenceRevision revisions, ApplicationEventPublisher events) {
        this.lockedRow = lockedRow;
        this.revisions = revisions;
        this.events = events;
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
        // Without a transaction no before-commit listener would run for the update either.
        if (issueId == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Issue issue = lockedRow.lockAndRead(issueId).orElse(null);
        if (issue == null
                || issue.isPullRequest()
                || issue.getRepository() == null
                || issue.getDeletedAt() != null
                || issue.getState() != Issue.State.CLOSED) {
            return;
        }
        ScmEventPayload.IssueData current = ScmEventPayload.IssueData.from(issue);
        if (Objects.equals(revisions.of(current).value(), issue.getReviewSnapshotDigest())) {
            return;
        }
        events.publishEvent(
                new ScmDomainEvent.IssueUpdated(current, Set.of(ScmSignals.ISSUE_DISCUSSION_FIELD), context));
    }
}
