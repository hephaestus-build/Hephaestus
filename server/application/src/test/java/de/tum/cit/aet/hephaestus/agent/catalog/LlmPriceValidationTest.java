package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LlmPriceValidationTest extends BaseUnitTest {

    @Test
    void shouldNameNoMeteredApiCostWhenAllPricedRatesAreZero() {
        assertThatThrownBy(() -> LlmPriceValidation.validate(
                        PricingMode.PRICED, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A price requires at least one rate greater than zero. "
                        + "For a model without usage charges, choose No metered API cost instead.");
    }

    @Test
    void shouldRequestAnExplanationWhenNoMeteredApiCostHasNone() {
        assertThatThrownBy(() -> LlmPriceValidation.validate(PricingMode.NO_CHARGE, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An explanation of why this model has no metered API cost (e.g. self-hosted) is required.");
    }

    @Test
    void shouldAcceptNoMeteredApiCostWhenAnExplanationIsProvided() {
        assertThatCode(() -> LlmPriceValidation.validate(PricingMode.NO_CHARGE, null, null, null, null, "Self-hosted"))
                .doesNotThrowAnyException();
    }
}
