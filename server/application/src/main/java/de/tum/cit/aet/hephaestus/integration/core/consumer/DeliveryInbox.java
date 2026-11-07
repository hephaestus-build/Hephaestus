package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.nats.client.Message;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Write-step claims join business transactions; the delivery itself holds no transaction across provider calls. */
@Service
@ConditionalOnServerRole
public class DeliveryInbox {
    private static final String COMPLETE = "complete";

    // The scope spans several transactions and provider calls on the dispatch thread, then is removed in finally.
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private record Context(DeliveryInbox inbox, @Nullable Long workspaceId, BrokerDelivery delivery) {}

    private final ConsumerInboxStepRepository workspaceSteps;
    private final InstallationInboxStepRepository installationSteps;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DeliveryInbox(
            ConsumerInboxStepRepository workspaceSteps,
            InstallationInboxStepRepository installationSteps,
            TransactionTemplate transactions,
            Clock clock) {
        this.workspaceSteps = workspaceSteps;
        this.installationSteps = installationSteps;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Returns false only for a step already committed by this delivery. Sync calls have no delivery to claim. */
    public static boolean claim(String step) {
        Context context = CURRENT.get();
        if (context == null) return true;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An inbox step must share its business transaction");
        }
        if (step.isBlank() || step.length() > 200 || COMPLETE.equals(step)) {
            throw new IllegalArgumentException("An inbox step needs a distinct name of at most 200 characters");
        }
        return context.inbox().claim(context.workspaceId(), context.delivery(), step);
    }

    /** Whether the current dispatch can retry an unfinished broker delivery. */
    public static boolean isDispatching() {
        return CURRENT.get() != null;
    }

    /** Handler preparation can skip an external operation whose database step already committed. */
    public static boolean completed(String step) {
        Context context = CURRENT.get();
        return context != null
                && Boolean.TRUE.equals(context.inbox()
                        .transactions
                        .execute(status -> context.inbox().contains(context.workspaceId(), context.delivery(), step)));
    }

    /** A resumed webhook step keeps the original broker arrival time. */
    public static Instant receivedAtOr(Instant fallback) {
        Context context = CURRENT.get();
        return context == null ? fallback : context.delivery().receivedAt();
    }

    /** Dispatch an admitted delivery; completion is recorded only after every step returns successfully. */
    public void dispatch(@Nullable Long workspaceId, Message message, Runnable work) {
        if (!message.isJetStream()) {
            work.run();
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A broker delivery cannot hold a transaction across handler preparation");
        }
        BrokerDelivery delivery = BrokerDelivery.from(message);
        boolean complete =
                Boolean.TRUE.equals(transactions.execute(status -> contains(workspaceId, delivery, COMPLETE)));
        if (complete) return;

        Context previous = CURRENT.get();
        CURRENT.set(new Context(this, workspaceId, delivery));
        try {
            work.run();
            transactions.executeWithoutResult(status -> claim(workspaceId, delivery, COMPLETE));
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** Removes only keys older than the broker's supported replay bound. */
    public void deleteExpired(Instant before) {
        transactions.executeWithoutResult(status -> {
            workspaceSteps.deleteExpired(before);
            installationSteps.deleteExpired(before);
        });
    }

    private boolean claim(@Nullable Long workspaceId, BrokerDelivery delivery, String step) {
        return (workspaceId == null
                        ? installationSteps.claim(delivery, step, clock.instant())
                        : workspaceSteps.claim(workspaceId, delivery, step, clock.instant()))
                == 1;
    }

    private boolean contains(@Nullable Long workspaceId, BrokerDelivery delivery, String step) {
        return workspaceId == null
                ? installationSteps.contains(delivery, step)
                : workspaceSteps.contains(workspaceId, delivery, step);
    }
}
