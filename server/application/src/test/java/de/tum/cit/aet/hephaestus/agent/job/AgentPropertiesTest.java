package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <p>Exercised through direct construction because {@code @Validated}'s method-level validation only
 * fires through a Spring-managed proxy, not plain {@code new AgentProperties(...)}.
 */
class AgentPropertiesTest extends BaseUnitTest {

    private static final Duration VALID_PAYLOAD_RETENTION = Duration.ofDays(14);
    private static final Duration VALID_ROW_RETENTION = Duration.ofDays(90);

    private static AgentProperties props(Duration payloadRetention, Duration rowRetention) {
        return new AgentProperties(false, Duration.ZERO, payloadRetention, rowRetention);
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of(Duration.ZERO, VALID_ROW_RETENTION, "payload-retention", "zero"),
                Arguments.of(Duration.ofDays(-1), VALID_ROW_RETENTION, "payload-retention", "negative"),
                Arguments.of(
                        Duration.ofDays(90), Duration.ofDays(14), "row-retention", "shorter than payload-retention"));
    }

    @ParameterizedTest(name = "{2}: {3}")
    @MethodSource("invalidConfigurations")
    void rejectsAnUnusableConfiguration(
            Duration payloadRetention, Duration rowRetention, String offendingProperty, String why) {
        assertThatThrownBy(() -> props(payloadRetention, rowRetention))
                .as(why)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(offendingProperty);
    }

    static Stream<Arguments> validConfigurations() {
        return Stream.of(
                Arguments.of(VALID_PAYLOAD_RETENTION, VALID_ROW_RETENTION, "the documented defaults"),
                Arguments.of(Duration.ofDays(30), Duration.ofDays(30), "row-retention equal to payload-retention"));
    }

    @ParameterizedTest(name = "accepts {2}")
    @MethodSource("validConfigurations")
    void acceptsAUsableConfiguration(Duration payloadRetention, Duration rowRetention, String why) {
        assertThatCode(() -> props(payloadRetention, rowRetention)).as(why).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the constructor preserves what it was given")
    void preservesConfiguredValues() {
        AgentProperties properties = props(VALID_PAYLOAD_RETENTION, VALID_ROW_RETENTION);

        assertThat(properties.payloadRetention()).isEqualTo(VALID_PAYLOAD_RETENTION);
        assertThat(properties.rowRetention()).isEqualTo(VALID_ROW_RETENTION);
    }
}
