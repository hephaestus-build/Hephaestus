package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves account ids to the name and contact an instance administrator sees next to a record the
 * account produced. A module that stores {@code account_id} columns (feedback, survey responses)
 * batches one lookup per page instead of importing the {@code Account} entity.
 */
public interface AccountSummaryQuery {
    /** The accounts that still exist, keyed by id; an erased or unknown id is simply absent. */
    Map<Long, AccountSummary> findAllByIds(Collection<Long> accountIds);

    record AccountSummary(
            long id, String displayName, @Nullable String email) {}
}
