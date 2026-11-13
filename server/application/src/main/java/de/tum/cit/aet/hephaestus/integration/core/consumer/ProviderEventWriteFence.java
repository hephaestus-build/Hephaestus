package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/** Spring Boot installs this listener on the transaction manager, as it does for transaction metrics. */
@Component
@ConditionalOnServerRole
public class ProviderEventWriteFence implements TransactionExecutionListener {
    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure == null && !transaction.isReadOnly()) DeliveryInbox.fenceTransaction();
    }
}
