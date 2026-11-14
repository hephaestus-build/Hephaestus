package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions of a review request, which its pin and source reads run between. A refusal commits what came
 * before it and leaves a caller's transaction usable: the event listener settles the refusal in that transaction. A
 * {@code TransactionTemplate} cannot declare that.
 */
@Component
@ConditionalOnServerRole
public class ReviewRequestTransactions {
    @Transactional(readOnly = true, noRollbackFor = ReviewRequestRejected.class)
    public <T> T read(Supplier<T> unit) {
        return unit.get();
    }

    @Transactional(noRollbackFor = ReviewRequestRejected.class)
    public <T> T write(Supplier<T> unit) {
        return unit.get();
    }
}
