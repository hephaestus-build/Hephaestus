package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Revokes a workspace's tools Pods in the transaction that changes what its turns may read, on whichever process makes
 * the change. The revocation is a row, so a server that misses the change's notification, as one that starts does,
 * misses nothing: its listener only deletes the revoked Pods' Jobs sooner than the reaper ({@link
 * TurnContextRevocations}).
 */
@Component
public class ConversationToolsPodRevocations {

    private final ConversationToolsPodRepository rows;
    private final TransactionTemplate transactions;

    ConversationToolsPodRevocations(ConversationToolsPodRepository rows, PlatformTransactionManager transactions) {
        this.rows = rows;
        this.transactions = new TransactionTemplate(transactions);
    }

    // Before the commit, as the notification: a Pod bound after the change keeps serving, and every server that hears
    // the change finds its rows revoked.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void contextChanged(MentorLedgerEvents.ContextChanged change) {
        transactions.executeWithoutResult(status -> rows.revokeWorkspace(change.workspaceId()));
    }
}
