package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class LlmModelReadinessTest extends BaseUnitTest {

    @Test
    void shouldGiveTheFirstReasonAModelCannotRun() {
        assertThat(LlmModelReadiness.of(ready(), true, PricingMode.PRICED, true))
                .isEqualTo(LlmModelReadiness.READY);
        assertThat(LlmModelReadiness.of(ready(), true, PricingMode.NO_CHARGE, true))
                .isEqualTo(LlmModelReadiness.READY);
        assertThat(LlmModelReadiness.of(ready(), true, PricingMode.UNPRICED, true))
                .isEqualTo(LlmModelReadiness.PRICE_MISSING);
        assertThat(LlmModelReadiness.of(ready(), false, PricingMode.PRICED, true))
                .isEqualTo(LlmModelReadiness.CONNECTION_OFF);
        var off = ready();
        off.setEnabled(false);
        assertThat(LlmModelReadiness.of(off, true, PricingMode.PRICED, true)).isEqualTo(LlmModelReadiness.OFF);
        var unbounded = ready();
        unbounded.setUpstreamModelId("an-unknown-model");
        unbounded.setContextWindow(null);
        unbounded.setMaxOutputTokens(null);
        assertThat(LlmModelReadiness.of(unbounded, true, PricingMode.PRICED, true))
                .isEqualTo(LlmModelReadiness.LIMITS_MISSING);
        assertThat(LlmModelReadiness.of(ready(), true, PricingMode.PRICED, false))
                .isEqualTo(LlmModelReadiness.NO_WORKSPACE_ACCESS);
    }

    @Test
    void shouldRefuseActivationForTheReasonsThatReadinessReports() {
        assertThatCode(() -> LlmModelReadiness.requireActivatable(true, PricingMode.NO_CHARGE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> LlmModelReadiness.requireActivatable(true, PricingMode.UNPRICED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LlmModelReadiness.requireActivatable(false, PricingMode.PRICED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LlmCatalogModel ready() {
        var model = new WorkspaceLlmModel();
        model.setUpstreamModelId("custom-model");
        model.setContextWindow(200_000);
        model.setMaxOutputTokens(8_192);
        model.setEnabled(true);
        return model;
    }
}
