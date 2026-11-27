package de.tum.cit.aet.hephaestus.core;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Values computed once per read-only REPEATABLE READ transaction. Every statement of such a transaction reads the same
 * snapshot, so a computation that only reads returns the same value each time it runs on the same key in it.
 *
 * <p>Anywhere else it computes every time. In a READ COMMITTED transaction, or with none, a later statement can see a
 * commit that an earlier one did not. In a transaction that writes, a later statement sees its own writes. A
 * transaction that starts inside one (REQUIRES_NEW) reads its own snapshot: Spring suspends the outer transaction's
 * synchronizations, and with them its values.
 *
 * <p>Only a value is kept. A computation that throws runs again on the next call, and throws as it did.
 */
public final class SnapshotMemo<K, V> {
    private final ThreadLocal<@Nullable Values> current = new ThreadLocal<>();

    public V get(K key, Supplier<V> compute) {
        if (!inReadOnlySnapshot()) return compute.get();
        var values = current.get();
        if (values == null) {
            values = new Values();
            TransactionSynchronizationManager.registerSynchronization(values);
            current.set(values);
        }
        var known = values.byKey.get(key);
        if (known != null) return known;
        var value = Objects.requireNonNull(compute.get());
        values.byKey.put(key, value);
        return value;
    }

    private static boolean inReadOnlySnapshot() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_REPEATABLE_READ);
    }

    /** One transaction's values. It is this thread's current one only while its transaction is. */
    private final class Values implements TransactionSynchronization {
        private final Map<K, V> byKey = new HashMap<>();

        @Override
        public void suspend() {
            current.remove();
        }

        @Override
        public void resume() {
            current.set(this);
        }

        @Override
        public void afterCompletion(int status) {
            current.remove();
        }
    }
}
