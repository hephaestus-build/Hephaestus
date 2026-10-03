package de.tum.cit.aet.hephaestus.agent.job;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueEvidenceRevision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ClosedIssueDiscussionBridgeTest extends BaseUnitTest {
    @Test
    void shouldReadTheDiscussionOnceAfterAllSyncCommentsAreWritten() {
        LockedIssueRow row = mock(LockedIssueRow.class);
        IssueEvidenceRevision revisions = mock(IssueEvidenceRevision.class);
        IssueObservationSuperseder superseder = mock(IssueObservationSuperseder.class);
        IssueAgentJobEventListener listener = mock(IssueAgentJobEventListener.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("listener", listener);
        ClosedIssueDiscussionBridge bridge = new ClosedIssueDiscussionBridge(
                row, revisions, superseder, beans.getBeanProvider(IssueAgentJobEventListener.class));
        Repository repository = new Repository();
        repository.setId(1L);
        repository.setNameWithOwner("org/project");
        Issue issue = new Issue();
        issue.setId(42L);
        issue.setNumber(7);
        issue.setTitle("Export");
        issue.setState(Issue.State.CLOSED);
        issue.setRepository(repository);
        var data = ScmEventPayload.IssueData.from(issue);
        var revision = ScmSignals.issueUpdatedRevision(data);
        when(row.lockAndRead(42L)).thenReturn(Optional.of(issue));
        when(revisions.of(data)).thenReturn(revision);
        EventContext context =
                EventContext.forSync(1L, new RepositoryRef(1L, "org/project", "main"), IdentityProviderType.GITHUB);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            bridge.onDeleted(new ScmDomainEvent.CommentDeleted(1L, 42L, context));
            bridge.onDeleted(new ScmDomainEvent.CommentDeleted(2L, 42L, context));
            bridge.onDeleted(new ScmDomainEvent.CommentDeleted(3L, 42L, context));
            verifyNoInteractions(row, revisions, superseder, listener);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.beforeCommit(false));
            verify(row).lockAndRead(42L);
            verify(revisions).of(data);
            verify(superseder).advance(42L, revision.value());
            verify(listener)
                    .recordUpdate(
                            new ScmDomainEvent.IssueUpdated(data, Set.of(ScmSignals.ISSUE_DISCUSSION_FIELD), context),
                            revision);
        } finally {
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.clear();
        }
    }
}
