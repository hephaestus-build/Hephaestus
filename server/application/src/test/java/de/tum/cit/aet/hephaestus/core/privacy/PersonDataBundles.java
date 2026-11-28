package de.tum.cit.aet.hephaestus.core.privacy;

import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The preview's records, read in one repeatable-read snapshot, for the store tests. No route returns them (ADR 0164).
 */
public final class PersonDataBundles {
    private PersonDataBundles() {}

    public static JsonNode of(PersonDataService service, PlatformTransactionManager transactions, UUID request) {
        var read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(read.execute(status -> service.bundle(request)));
    }
}
