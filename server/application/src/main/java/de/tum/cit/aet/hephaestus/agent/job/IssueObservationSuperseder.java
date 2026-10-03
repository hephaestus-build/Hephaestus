package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueEvidenceRevision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Retires issue claims with the mirror change, not with a later review that may fail. */
@Component
@ConditionalOnServerRole
public class IssueObservationSuperseder {

    private final IssueRepository issues;
    private final ObservationRepository observations;
    private final IssueEvidenceRevision revisions;
    private final LockedIssueRow lockedRow;

    IssueObservationSuperseder(
            IssueRepository issues,
            ObservationRepository observations,
            IssueEvidenceRevision revisions,
            LockedIssueRow lockedRow) {
        this.issues = issues;
        this.observations = observations;
        this.revisions = revisions;
        this.lockedRow = lockedRow;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCreated(ScmDomainEvent.IssueCreated event) {
        advance(event.issue());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onUpdated(ScmDomainEvent.IssueUpdated event) {
        if (!Collections.disjoint(event.changedFields(), ScmSignals.REVIEWABLE_ISSUE_FIELDS)) {
            advance(event.issue());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onClosed(ScmDomainEvent.IssueClosed event) {
        advance(event.issue());
    }

    /** Records the issue as it now stands, which may have moved since the event that occasioned this was built. */
    private void advance(ScmEventPayload.IssueData event) {
        if (event.isPullRequest()) {
            return;
        }
        Issue issue = lockedRow.lockAndRead(event.id()).orElse(null);
        if (issue == null || issue.isPullRequest()) {
            return;
        }
        String digest = revisions.of(ScmEventPayload.IssueData.from(issue)).value();
        if (issues.advanceReviewSnapshot(issue.getId(), UUID.randomUUID(), digest) != 1) {
            return;
        }
        observations.supersedeIssueObservations(issue.getId(), Instant.now());
    }
}
