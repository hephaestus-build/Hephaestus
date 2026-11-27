package de.tum.cit.aet.hephaestus.core;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Values computed once per read-only REPEATABLE READ transaction. Every statement of such a transaction reads the same
 * snapshot, so a computation that only reads returns the same value each time it runs on the same key in it.
 *
 * <p>Anywhere else it computes every time. In a READ COMMITTED transaction, or with none, a later statement can see a
 * commit that an earlier one did not. In a transaction that writes, a later statement sees its own writes.
 *
 * <p>The values belong to the transaction: they are registered with it as a synchronization and end with it. A
 * transaction that starts inside one (REQUIRES_NEW) reads its own snapshot and does not see them, because Spring
 * suspends the outer transaction's synchronizations. Only a value is kept. A computation that throws runs again on the
 * next call, and throws as it did.
 */
public final class SnapshotMemo<K, V> {
    private final Class<V> type;

    public SnapshotMemo(Class<V> type) {
        this.type = type;
    }

    public V get(K key, Supplier<V> compute) {
        if (!inReadOnlySnapshot()) return compute.get();
        var values = values();
        var known = values.get(key);
        if (known != null) return Objects.requireNonNull(type.cast(known));
        var value = Objects.requireNonNull(compute.get());
        values.put(key, value);
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

    /** This memo's values in the current transaction, registered with it on first use. */
    private Map<Object, Object> values() {
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations())
            if (synchronization instanceof Values values && values.memo == this) return values.byKey;
        var values = new Values(this);
        TransactionSynchronizationManager.registerSynchronization(values);
        return values.byKey;
    }

    private static final class Values implements TransactionSynchronization {
        private final SnapshotMemo<?, ?> memo;
        private final Map<Object, Object> byKey = new HashMap<>();

        Values(SnapshotMemo<?, ?> memo) {
            this.memo = memo;
        }
    }
}
