package de.tum.cit.aet.hephaestus.agent.practice.live;

import static de.tum.cit.aet.hephaestus.agent.practice.live.LiveModelSettings.CONTEXT_WINDOW_PROPERTY;
import static de.tum.cit.aet.hephaestus.agent.practice.live.LiveModelSettings.MAX_OUTPUT_TOKENS_PROPERTY;
import static de.tum.cit.aet.hephaestus.agent.practice.live.LiveModelSettings.REASONING_EFFORT_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class LiveModelSettingsTest {

    @Test
    void shouldKeepTheHarnessDefaultsWhenNoSettingIsGiven() {
        LiveModelSettings settings = LiveModelSettings.from(Map.<String, String>of()::get);

        assertThat(settings).isEqualTo(LiveModelSettings.DEFAULTS);
    }

    @Test
    void shouldUseEachGivenSettingAndKeepTheOthersWhenSomeAreGiven() {
        LiveModelSettings settings = LiveModelSettings.from(
                Map.of(MAX_OUTPUT_TOKENS_PROPERTY, "32768", REASONING_EFFORT_PROPERTY, "HIGH")::get);

        assertThat(settings).isEqualTo(new LiveModelSettings(131_072, 32_768, ReasoningEffort.HIGH));
    }

    @Test
    void shouldNameThePropertyWhenATokenCountIsNotAPositiveInteger() {
        assertThatThrownBy(() -> LiveModelSettings.from(Map.of(CONTEXT_WINDOW_PROPERTY, "0")::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CONTEXT_WINDOW_PROPERTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8192", "16384"})
    void shouldRefuseTheOutputAllowanceWhenItDoesNotFitTheContextWindow(String maxOutputTokens) {
        assertThatThrownBy(() -> LiveModelSettings.from(
                        Map.of(CONTEXT_WINDOW_PROPERTY, "8192", MAX_OUTPUT_TOKENS_PROPERTY, maxOutputTokens)::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MAX_OUTPUT_TOKENS_PROPERTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"high", "", "EXTREME"})
    void shouldRefuseAReasoningEffortWhenItIsNotACatalogName(String value) {
        assertThatThrownBy(() -> LiveModelSettings.from(Map.of(REASONING_EFFORT_PROPERTY, value)::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(REASONING_EFFORT_PROPERTY);
    }
}
