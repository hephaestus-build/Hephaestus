package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.PassThroughTransactionTemplate;
import java.time.Instant;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs an adapter inside dispatch with a step that the inbox rejects. No database or Spring context is started. */
public final class RejectedInboxWrite {
    private RejectedInboxWrite() {}

    public static void during(RuntimeException rejected, Runnable handler) {
        var store = mock(ProviderEventStore.class);
        when(store.claimStep(anyLong(), anyInt(), anyString())).thenThrow(rejected);
        var inbox = new DeliveryInbox(store, new PassThroughTransactionTemplate());
        inbox.dispatch(1, 1, Instant.EPOCH, () -> {
            boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                handler.run();
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(previous);
            }
        });
    }
}
