package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class TransactionDurationsTest extends BaseUnitTest {

    @Test
    void shouldNameADeclarativeBoundaryByItsClassAndMethodWhenTheNameIsQualified() {
        assertThat(TransactionDurations.boundary("de.tum.cit.aet.hephaestus.integration.SyncJobService.completeJob"))
                .isEqualTo("SyncJobService.completeJob");
    }

    @Test
    void shouldKeepTheNestedClassWhenTheBoundaryIsDeclaredOnANestedClass() {
        assertThat(TransactionDurations.boundary("de.tum.cit.aet.hephaestus.Outer$Inner.run"))
                .isEqualTo("Outer$Inner.run");
    }

    @Test
    void shouldKeepAnUnqualifiedNameWhenItHasNoPackage() {
        assertThat(TransactionDurations.boundary("probe")).isEqualTo("probe");
    }

    @Test
    void shouldGroupUnnamedTemplatesWhenTheTransactionHasNoName() {
        assertThat(TransactionDurations.boundary(null)).isEqualTo(TransactionDurations.UNNAMED);
        assertThat(TransactionDurations.boundary("")).isEqualTo(TransactionDurations.UNNAMED);
    }
}
