package de.tum.cit.aet.hephaestus.testconfig;

import java.io.Serial;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs every callback inline with no transaction manager, for unit tests of code that owns its own
 * transaction boundaries. {@code executeWithoutResult} routes through {@link #execute}.
 */
public final class PassThroughTransactionTemplate extends TransactionTemplate {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public <T extends @Nullable Object> T execute(TransactionCallback<T> action) {
        return action.doInTransaction(new SimpleTransactionStatus());
    }
}
