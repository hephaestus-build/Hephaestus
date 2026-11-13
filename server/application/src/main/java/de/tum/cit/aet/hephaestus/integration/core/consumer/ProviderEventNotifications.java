package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * Wakes this server's idle claimers when a transaction that stored or queued an event commits. The server that receives
 * a delivery also claims it, so no connection listens for the commits of other servers: their claimers find an event
 * at their next poll. A {@code LISTEN} connection would sit outside the pool that the chart budgets.
 */
@Component
@ConditionalOnServerRole
public class ProviderEventNotifications {

    private final List<Runnable> claimers = new CopyOnWriteArrayList<>();

    /** Calls {@code queued} after each commit that queued an event on this server. */
    public void subscribe(Runnable queued) {
        claimers.add(queued);
    }

    /** Wakes the subscribers once the caller's transaction commits. */
    void queuedAfterCommit() {
        TransactionCallbacks.afterCommit(() -> claimers.forEach(Runnable::run));
    }
}
