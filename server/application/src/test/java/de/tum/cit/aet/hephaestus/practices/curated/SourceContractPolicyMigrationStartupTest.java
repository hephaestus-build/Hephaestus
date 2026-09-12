package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SourceContractPolicyMigrationStartupTest {
    @Test
    void shouldPropagateTheFailureWhenAnInstalledPolicyCannotBeUpgraded() {
        var migration = mock(SourceContractPolicyMigration.class);
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(migration).run();
        var startup = new SourceContractPolicyMigrationStartup(migration);

        assertThatThrownBy(startup::afterSingletonsInstantiated).isSameAs(failure);
    }
}
