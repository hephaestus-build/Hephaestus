package de.tum.cit.aet.hephaestus.integration.core.signal;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SignalStateReasonTest extends BaseUnitTest {

    /**
     * The sentence reaches the developer whose work it is as well as an admin, so it is written in the product's
     * words: the work, a moment, review settings — never the pipeline's.
     */
    @ParameterizedTest
    @EnumSource(SignalStateReason.class)
    void shouldDescribeEveryReasonInTheProductsWords(SignalStateReason reason) {
        assertThat(reason.describe())
                .endsWith(".")
                .doesNotContain(
                        "artifact",
                        "ledger",
                        "signal",
                        "binding",
                        "gate",
                        "re-offer",
                        "occurrence",
                        "_",
                        reason.name());
    }
}
