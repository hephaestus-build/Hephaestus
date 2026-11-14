package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short read-only transactions of an SCM item read, which its provider fetch runs between. A refused read leaves
 * a caller's transaction usable: callers handle the refusal and go on. A {@code TransactionTemplate} cannot declare
 * that.
 */
@Component
@ConditionalOnServerRole
public class ScmItemTransactions {
    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    public <T> T read(Supplier<T> unit) {
        return unit.get();
    }

    /** {@link #read} in one snapshot of the database, so that a capture reads every row as of one moment. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = ItemCaptureException.class)
    public <T> T snapshot(Supplier<T> unit) {
        return unit.get();
    }
}
