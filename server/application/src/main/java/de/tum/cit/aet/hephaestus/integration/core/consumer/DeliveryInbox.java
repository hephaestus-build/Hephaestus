package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
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

    private record Context(
            DeliveryInbox inbox, long eventId, int leaseEpoch, Instant receivedAt, DispatchStatus status) {}

    private static final class DispatchStatus {
        private @Nullable RuntimeException rejectedWrite;
        private boolean erasedByHandler;
    }

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
        boolean claimed;
        try {
            claimed = context.inbox().store.claimStep(context.eventId(), context.leaseEpoch(), step);
        } catch (RuntimeException failure) {
            context.status().rejectedWrite = failure;
            throw failure;
        }
        return claimed;
    }

    /** A provider fallback must not log or continue after the inbox has rejected its write. */
    public static void rethrowRejectedWrite() {
        Context context = CURRENT.get();
        if (context != null) {
            var rejected = context.status().rejectedWrite;
            if (rejected != null) throw rejected;
        }
    }

    // Installed for every write transaction on the dispatch thread, including REQUIRES_NEW natural-key upserts.
    // A named step prevents deletion early while allowing renewal. Every writer checks the epoch again at commit.
    static void fenceTransaction() {
        Context context = CURRENT.get();
        if (context != null) TransactionSynchronizationManager.registerSynchronization(new WriteFence(context));
    }

    private static final class WriteFence implements TransactionSynchronization {
        private final Context context;
        private boolean fenced;

        private WriteFence(Context context) {
            this.context = context;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (!readOnly && !fenced) {
                try {
                    context.inbox().store.fenceStep(context.eventId(), context.leaseEpoch());
                } catch (RuntimeException failure) {
                    context.status().rejectedWrite = failure;
                    throw failure;
                }
            }
        }
    }

    static void erasedByHandler(List<ProviderEventRepository.ErasedRow> erased) {
        Context context = CURRENT.get();
        if (context == null) return;
        var current =
                erased.stream().filter(row -> row.getId() == context.eventId()).findFirst();
        if (current.isEmpty()) return;
        var row = current.get();
        if (!"LEASED".equals(row.getState()) || row.getLeaseEpoch() != context.leaseEpoch()) {
            var failure = new ProviderEventFenceException(ProviderEventFenceException.Reason.LEASE_LOST);
            context.status().rejectedWrite = failure;
            throw failure;
        }
        // DELETE locked and returned the same lease in this transaction. It remains fenced through commit.
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof WriteFence fence) fence.fenced = true;
        }
        TransactionCallbacks.afterCommit(() -> context.status().erasedByHandler = true);
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

    /** Runs one claimed event and returns whether its handler committed the deletion of its own event row. */
    public boolean dispatch(long eventId, int leaseEpoch, Instant receivedAt, Runnable work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An event cannot hold a transaction across handler preparation");
        }
        Context previous = CURRENT.get();
        Context context = new Context(this, eventId, leaseEpoch, receivedAt, new DispatchStatus());
        CURRENT.set(context);
        try {
            work.run();
            RuntimeException rejected = context.status().rejectedWrite;
            if (rejected != null) throw rejected;
            return context.status().erasedByHandler;
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
