package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The short transactions of a review request and of the event review that asks for one. Their fetches run between. */
@Component
@ConditionalOnServerRole
public class ReviewRequestTransactions {
    @Transactional(readOnly = true)
    public <T> T read(Supplier<T> unit) {
        return unit.get();
    }

    @Transactional
    public <T> T write(Supplier<T> unit) {
        return unit.get();
    }

    @Transactional
    public void run(Runnable unit) {
        unit.run();
    }
}
