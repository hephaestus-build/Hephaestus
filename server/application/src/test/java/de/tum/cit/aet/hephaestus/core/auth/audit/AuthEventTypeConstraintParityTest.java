package de.tum.cit.aet.hephaestus.core.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.LiquibaseCheckConstraints;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code auth_event.event_type} is constrained at the database by {@code ck_auth_event_event_type},
 * whose admitted values are spelled out in a Liquibase changelog. Adding a constant to
 * {@link AuthEvent.EventType} without widening that CHECK produces a defect no other test tier can
 * see: the suite builds its schema with {@code ddl-auto: create} and so never applies the constraint,
 * and the audit write runs in its own transaction — so in production the insert is rejected, the
 * failure is logged rather than propagated, and the audited action commits with no audit row. An
 * audit trail that silently develops holes is exactly what the {@code @Audited} rule exists to
 * prevent, so the two lists are pinned to each other here.
 */
@Tag("unit")
class AuthEventTypeConstraintParityTest {

    @Test
    void everyEventTypeConstantIsAdmittedByTheDatabaseConstraint() throws IOException {
        Set<String> admitted = LiquibaseCheckConstraints.admittedValues("ck_auth_event_event_type", "event_type");
        Set<String> declared = Arrays.stream(AuthEvent.EventType.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(admitted)
                .as("no changelog defines ck_auth_event_event_type — has the constraint been renamed?")
                .isNotEmpty();
        assertThat(declared)
                .as("these AuthEvent.EventType constants are not admitted by ck_auth_event_event_type, so writing "
                        + "one would be rejected by Postgres and the audited action would commit unaudited — widen the "
                        + "CHECK in a new changelog")
                .isSubsetOf(admitted);
    }
}
