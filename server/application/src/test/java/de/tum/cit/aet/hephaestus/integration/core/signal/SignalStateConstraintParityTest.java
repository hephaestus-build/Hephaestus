package de.tum.cit.aet.hephaestus.integration.core.signal;

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
 * {@code artifact_signal.state} is constrained at the database by {@code ck_artifact_signal_state},
 * and a {@link SignalState} the constraint does not admit is rejected on write. The consumer retries
 * the message until it gives up and drops it, so the occurrence it carried is never reviewed and the
 * ledger keeps no record that it arrived. Every other tier builds its schema with
 * {@code ddl-auto: create} and applies no changelog, so none of them sees the constraint at all;
 * {@link SignalStateConstraintIntegrationTest} migrates a real database and asserts the same parity
 * against what the chain actually produces, and this one answers in the unit suite instead of the
 * database job.
 */
@Tag("unit")
class SignalStateConstraintParityTest {

    @Test
    void everySignalStateIsAdmittedByTheDatabaseConstraint() throws IOException {
        Set<String> admitted = LiquibaseCheckConstraints.admittedValues("ck_artifact_signal_state", "state");
        Set<String> declared = Arrays.stream(SignalState.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(admitted)
                .as("no changelog defines ck_artifact_signal_state — has the constraint been renamed?")
                .isNotEmpty();
        assertThat(declared)
                .as("these SignalState constants are not admitted by ck_artifact_signal_state, so a signal in one "
                        + "would be refused by Postgres and its occurrence lost — widen the CHECK in a new changelog")
                .isSubsetOf(admitted);
    }
}
