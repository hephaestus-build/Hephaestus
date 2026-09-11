package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DataHandlingFactsTest extends BaseUnitTest {

    @ParameterizedTest
    @CsvSource({
        "OWN_ORGANISATION, NONE, IN_HOUSE",
        "OWN_ORGANISATION, FOR_SAFETY_CHECKS, IN_HOUSE",
        "PROVIDER, NONE, PROVIDER_NOT_KEPT",
        "PROVIDER, FOR_SAFETY_CHECKS, PROVIDER_KEPT",
    })
    void shouldDeriveTheTierFromTheTwoFacts(
            LlmDataOperator operatedBy, LlmDataRetention keptAfterReply, DataHandlingTier expected) {
        assertThat(DataHandlingFacts.of(operatedBy, keptAfterReply, null).tier())
                .isEqualTo(expected);
    }

    @Test
    void shouldBeUndeclaredWhenNeitherFactIsSet() {
        assertThat(new DataHandlingFacts().tier()).isEqualTo(DataHandlingTier.UNDECLARED);
        assertThat(DataHandlingFacts.of(null, null, "note kept for admins").tier())
                .isEqualTo(DataHandlingTier.UNDECLARED);
    }

    @Test
    void shouldBeUndeclaredWhenOnlyOneFactIsSetOnAStoredRow() {
        var facts = new DataHandlingFacts();
        facts.setOperatedBy(LlmDataOperator.PROVIDER);
        assertThat(facts.tier()).isEqualTo(DataHandlingTier.UNDECLARED);
        facts.setOperatedBy(null);
        facts.setKeptAfterReply(LlmDataRetention.NONE);
        assertThat(facts.tier()).isEqualTo(DataHandlingTier.UNDECLARED);
    }

    @Test
    void shouldRejectADeclarationWithOnlyOneFact() {
        assertThatThrownBy(() -> DataHandlingFacts.of(LlmDataOperator.PROVIDER, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Declare both facts or neither");
        assertThatThrownBy(() -> DataHandlingFacts.of(null, LlmDataRetention.NONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Declare both facts or neither");
    }

    @Test
    void shouldStoreABlankNoteAsAbsent() {
        assertThat(DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.NONE, "  ")
                        .getNote())
                .isNull();
    }

    @Test
    void shouldOrderTiersStrictestFirstAndKeepUndeclaredOutsideEveryCeiling() {
        assertThat(DataHandlingTier.IN_HOUSE.isWithin(DataHandlingTier.IN_HOUSE))
                .isTrue();
        assertThat(DataHandlingTier.IN_HOUSE.isWithin(DataHandlingTier.PROVIDER_KEPT))
                .isTrue();
        assertThat(DataHandlingTier.PROVIDER_KEPT.isWithin(DataHandlingTier.PROVIDER_NOT_KEPT))
                .isFalse();
        assertThat(DataHandlingTier.UNDECLARED.isWithin(DataHandlingTier.PROVIDER_KEPT))
                .isFalse();
        assertThat(DataHandlingTier.UNDECLARED.isWithin(DataHandlingTier.UNDECLARED))
                .isFalse();
    }
}
