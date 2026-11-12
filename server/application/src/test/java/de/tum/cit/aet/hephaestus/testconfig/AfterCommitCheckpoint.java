package de.tum.cit.aet.hephaestus.testconfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds a committed transaction before Spring invokes its AFTER_COMMIT event listeners in afterCompletion. */
public final class AfterCommitCheckpoint {
    private AfterCommitCheckpoint() {}

    public static void hold(PlatformTransactionManager transactions, Runnable change, Runnable assertions)
            throws Exception {
        var committed = new CompletableFuture<Boolean>();
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> {
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                committed.complete(true);
                                try {
                                    if (!release.await(30, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("The committed transaction was not released");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                            }
                        });
                        change.run();
                    });
                } catch (RuntimeException | Error failure) {
                    committed.completeExceptionally(failure);
                    throw failure;
                }
            });
            try {
                committed.get(30, TimeUnit.SECONDS);
                assertions.run();
            } finally {
                release.countDown();
                result.get(30, TimeUnit.SECONDS);
            }
        }
    }
}
