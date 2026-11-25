package de.tum.cit.aet.hephaestus.core.privacy.spi;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a cross-owner expiry through the owner function {@code person_row_sweep}. The function's branch for the rule
 * bounds the rows it changes, so a caller needs no workspace. A rule that needs a subject and a
 * {@link RowSweepAdmission} is refused here. Each call commits on its own, so a job that calls again keeps the batches
 * before a failure.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class OwnerSweeps {
    /** The longest one call's transaction may take. A statement that runs longer is cancelled and rolls back. */
    public static final int BATCH_TIMEOUT_SECONDS = 30;

    private final JdbcClient jdbc;

    /** Returns the number of rows that the rule changed before {@code cutoff}. */
    @Transactional(timeout = BATCH_TIMEOUT_SECONDS)
    public int expire(RowSweepRule rule, Instant cutoff) {
        if (rule.requiresLifecycleAdmission()) {
            throw new IllegalArgumentException(rule + " needs a subject and a RowSweepAdmission");
        }
        return jdbc.sql("SELECT public.person_row_sweep(:rule, CAST(NULL AS bigint), CAST(NULL AS bigint), :cutoff)")
                .param("rule", rule.name())
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }
}
