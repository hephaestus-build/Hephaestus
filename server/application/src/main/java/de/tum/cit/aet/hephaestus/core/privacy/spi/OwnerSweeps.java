package de.tum.cit.aet.hephaestus.core.privacy.spi;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Runs a cross-owner expiry through the owner function {@code person_row_sweep}. The function's branch for the rule
 * bounds the rows it changes, so a caller needs no workspace. A rule that needs a subject and a
 * {@link RowSweepAdmission} is refused here.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class OwnerSweeps {
    private final JdbcClient jdbc;

    /** Returns the number of rows that the rule changed before {@code cutoff}. */
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
