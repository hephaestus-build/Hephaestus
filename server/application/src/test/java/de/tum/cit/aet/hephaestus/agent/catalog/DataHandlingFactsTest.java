package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DataHandlingFactsTest extends BaseUnitTest {

    @ParameterizedTest
    @CsvSource({
        "OWN_ORGANISATION, IN_HOUSE",
        "PROVIDER, CLOUD",
    })
    void shouldDeriveTheTierFromWhoOperatesTheSystems(LlmDataOperator operatedBy, DataHandlingTier expected) {
        assertThat(DataHandlingFacts.of(operatedBy, null).tier()).isEqualTo(expected);
    }

    @Test
    void shouldBeUndeclaredWhenNoOperatorIsDeclared() {
        assertThat(new DataHandlingFacts().tier()).isEqualTo(DataHandlingTier.UNDECLARED);
        assertThat(DataHandlingFacts.of(null, "note kept for admins").tier()).isEqualTo(DataHandlingTier.UNDECLARED);
    }

    @Test
    void shouldStoreABlankNoteAsAbsent() {
        assertThat(DataHandlingFacts.of(null, "  ").getNote()).isNull();
        assertThat(DataHandlingFacts.of(LlmDataOperator.PROVIDER, "  ").getNote())
                .isNull();
    }

    @Test
    void shouldOrderTiersStrictestFirstAndKeepUndeclaredOutsideEveryCeiling() {
        assertThat(DataHandlingTier.IN_HOUSE.isWithin(DataHandlingTier.IN_HOUSE))
                .isTrue();
        assertThat(DataHandlingTier.IN_HOUSE.isWithin(DataHandlingTier.CLOUD)).isTrue();
        assertThat(DataHandlingTier.CLOUD.isWithin(DataHandlingTier.IN_HOUSE)).isFalse();
        assertThat(DataHandlingTier.UNDECLARED.isWithin(DataHandlingTier.CLOUD)).isFalse();
        assertThat(DataHandlingTier.UNDECLARED.isWithin(DataHandlingTier.UNDECLARED))
                .isFalse();
    }
}
