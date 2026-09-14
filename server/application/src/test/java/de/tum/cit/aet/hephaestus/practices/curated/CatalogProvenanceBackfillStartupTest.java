package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.health.contributor.Status;

class CatalogProvenanceBackfillStartupTest extends BaseUnitTest {

    @Test
    void shouldReportOutOfServiceWhenRepairFails() {
        CatalogProvenanceBackfill backfill = mock(CatalogProvenanceBackfill.class);
        doThrow(new IllegalStateException("broken catalog")).when(backfill).run();
        var startup = new CatalogProvenanceBackfillStartup(backfill);

        startup.run(new DefaultApplicationArguments());

        assertThat(startup.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(startup.health().getDetails()).containsEntry("reason", "CATALOG_PROVENANCE_REPAIR_FAILED");
    }
}
