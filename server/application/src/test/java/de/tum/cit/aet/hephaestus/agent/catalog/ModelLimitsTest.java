package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class ModelLimitsTest extends BaseUnitTest {

    @Test
    void shouldPreferTheAdminsValueOverTheProvidersForEachLimit() {
        var limits = ModelLimits.of("self-hosted", 32_000, 4_000, 131_072, 65_536);

        assertThat(limits).isEqualTo(new ModelLimits(32_000, LlmLimitSource.ADMIN, 4_000, LlmLimitSource.ADMIN));
    }

    @Test
    void shouldUseTheProvidersValueWhereTheAdminSetNone() {
        var limits = ModelLimits.of("self-hosted", null, 4_000, 131_072, null);

        assertThat(limits).isEqualTo(new ModelLimits(131_072, LlmLimitSource.DISCOVERED, 4_000, LlmLimitSource.ADMIN));
    }

    @Test
    void shouldDeriveAQuarterOfTheContextWindowAtMostTheCap() {
        assertThat(ModelLimits.of("self-hosted", null, null, 32_768, null))
                .isEqualTo(new ModelLimits(32_768, LlmLimitSource.DISCOVERED, 8_192, LlmLimitSource.DERIVED));
        assertThat(ModelLimits.of("self-hosted", 262_144, null, null, null))
                .isEqualTo(new ModelLimits(
                        262_144, LlmLimitSource.ADMIN, ModelLimits.DERIVED_OUTPUT_CAP, LlmLimitSource.DERIVED));
    }

    /** An admin who sets only the window caps the output that the provider or the catalog reports. */
    @Test
    void shouldDeriveTheOutputFromAnAdminsWindowOverAReportedOutput() {
        assertThat(ModelLimits.of("self-hosted", 50_000, null, null, 128_000))
                .isEqualTo(new ModelLimits(50_000, LlmLimitSource.ADMIN, 12_500, LlmLimitSource.DERIVED));
        assertThat(ModelLimits.of("gpt-6.1-sol", 50_000, null, null, null).maxOutputTokens())
                .isEqualTo(12_500);
    }

    @Test
    void shouldDeriveTheOutputWhenAReportedOutputDoesNotFitTheWindow() {
        assertThat(ModelLimits.of("gpt-6.1-sol", null, null, 32_768, null))
                .isEqualTo(new ModelLimits(32_768, LlmLimitSource.DISCOVERED, 8_192, LlmLimitSource.DERIVED));
    }

    @Test
    void shouldUseTheCatalogForAModelWhoseProviderListsNoLimits() {
        assertThat(ModelLimits.of("gpt-6.1-sol", null, null, null, null))
                .isEqualTo(new ModelLimits(1_050_000, LlmLimitSource.CATALOG, 128_000, LlmLimitSource.CATALOG));
        assertThat(ModelLimits.of("gpt-6.1-sol", null, 8_000, null, null).maxOutputTokensSource())
                .isEqualTo(LlmLimitSource.ADMIN);
    }

    @Test
    void shouldLeaveAModelUnboundedWhenNoLimitIsKnown() {
        var limits = ModelLimits.of("self-hosted", null, null, null, null);

        assertThat(limits).isEqualTo(ModelLimits.NONE);
        assertThat(limits.bounded()).isFalse();
    }
}
