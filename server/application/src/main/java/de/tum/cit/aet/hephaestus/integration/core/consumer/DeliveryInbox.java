package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write-step claims join business transactions; the handling of an event holds no transaction across provider calls.
 * A step that an attempt committed stays committed, so a retry or a replay of the event skips it.
 */
@Service
@ConditionalOnServerRole
public class DeliveryInbox {
    private static final int MAX_STEP_LENGTH = 200;

    // The scope spans several transactions and provider calls on the dispatch thread, then is removed in finally.
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private record Context(DeliveryInbox inbox, long eventId, Instant receivedAt) {}

    private final ProviderEventStore store;
    private final TransactionTemplate transactions;

    public DeliveryInbox(ProviderEventStore store, TransactionTemplate transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    /** Returns false only for a step already committed by this event. Sync calls have no event to claim. */
    public static boolean claim(String step) {
        Context context = CURRENT.get();
        if (context == null) return true;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An inbox step must share its business transaction");
        }
        if (step.isBlank() || step.length() > MAX_STEP_LENGTH) {
            throw new IllegalArgumentException("An inbox step needs a name of at most 200 characters");
        }
        return context.inbox().store.claimStep(context.eventId(), step);
    }

    /** Whether the current thread handles a stored event, which a failure returns for a retry. */
    public static boolean isDispatching() {
        return CURRENT.get() != null;
    }

    /** Handler preparation can skip an external operation whose database step already committed. */
    public static boolean completed(String step) {
        Context context = CURRENT.get();
        return context != null
                && Boolean.TRUE.equals(context.inbox()
                        .transactions
                        .execute(status -> context.inbox().store.hasStep(context.eventId(), step)));
    }

    /** A retried or replayed event keeps the time the receiver stored it. */
    public static Instant receivedAtOr(Instant fallback) {
        Context context = CURRENT.get();
        return context == null ? fallback : context.receivedAt();
    }

    /** Runs the handling of one claimed event; its steps are recorded against the event. */
    public void dispatch(long eventId, Instant receivedAt, Runnable work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An event cannot hold a transaction across handler preparation");
        }
        Context previous = CURRENT.get();
        CURRENT.set(new Context(this, eventId, receivedAt));
        try {
            work.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
