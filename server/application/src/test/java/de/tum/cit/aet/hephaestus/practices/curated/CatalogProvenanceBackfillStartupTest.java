package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.health.contributor.Status;

class CatalogProvenanceBackfillStartupTest extends BaseUnitTest {

    @Mock
    private SourceContractPolicyMigration policyMigration;

    @Mock
    private CatalogProvenanceBackfill backfill;

    @Test
    void shouldUpgradeInstalledPoliciesBeforeRepairingProvenance() {
        var startup = new CatalogProvenanceBackfillStartup(policyMigration, backfill);

        startup.run(new DefaultApplicationArguments());

        InOrder order = Mockito.inOrder(policyMigration, backfill);
        order.verify(policyMigration).run();
        order.verify(backfill).run();
        assertThat(startup.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void shouldReportOutOfServiceWhenRepairFails() {
        doThrow(new IllegalStateException("broken catalog")).when(backfill).run();
        var startup = new CatalogProvenanceBackfillStartup(policyMigration, backfill);

        startup.run(new DefaultApplicationArguments());

        assertThat(startup.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(startup.health().getDetails()).containsEntry("reason", "CATALOG_PROVENANCE_REPAIR_FAILED");
    }

    @Test
    void shouldPropagateTheFailureWhenAnInstalledPolicyCannotBeUpgraded() {
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(policyMigration).run();
        var startup = new CatalogProvenanceBackfillStartup(policyMigration, backfill);

        assertThatThrownBy(() -> startup.run(new DefaultApplicationArguments())).isSameAs(failure);
        verifyNoInteractions(backfill);
    }
}
