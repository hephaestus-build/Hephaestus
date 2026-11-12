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

    private static final Duration VALID_HEARTBEAT_INTERVAL = Duration.ofSeconds(25);
    private static final Duration VALID_PAYLOAD_RETENTION = Duration.ofDays(14);
    private static final Duration VALID_ROW_RETENTION = Duration.ofDays(90);

    private static AgentProperties props(Duration heartbeatInterval, Duration payloadRetention, Duration rowRetention) {
        return new AgentProperties(false, heartbeatInterval, payloadRetention, rowRetention);
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of(
                        Duration.ofMillis(500),
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "heartbeat-interval",
                        "sub-second"),
                Arguments.of(
                        AgentProperties.WORKER_LEASE_TTL.plusSeconds(1),
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "heartbeat-interval",
                        "slower than the worker liveness window, so every worker would count as gone"),
                Arguments.of(VALID_HEARTBEAT_INTERVAL, Duration.ZERO, VALID_ROW_RETENTION, "payload-retention", "zero"),
                Arguments.of(
                        VALID_HEARTBEAT_INTERVAL,
                        Duration.ofDays(-1),
                        VALID_ROW_RETENTION,
                        "payload-retention",
                        "negative"),
                Arguments.of(
                        VALID_HEARTBEAT_INTERVAL,
                        Duration.ofDays(90),
                        Duration.ofDays(14),
                        "row-retention",
                        "shorter than payload-retention"));
    }

    @ParameterizedTest(name = "{3}: {4}")
    @MethodSource("invalidConfigurations")
    void rejectsAnUnusableConfiguration(
            Duration heartbeatInterval,
            Duration payloadRetention,
            Duration rowRetention,
            String offendingProperty,
            String why) {
        assertThatThrownBy(() -> props(heartbeatInterval, payloadRetention, rowRetention))
                .as(why)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(offendingProperty);
    }

    static Stream<Arguments> validConfigurations() {
        return Stream.of(
                Arguments.of(
                        VALID_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "the documented defaults"),
                Arguments.of(
                        AgentProperties.MIN_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "the heartbeat-interval floor exactly"),
                Arguments.of(
                        AgentProperties.MAX_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "the heartbeat-interval ceiling exactly"),
                Arguments.of(
                        VALID_HEARTBEAT_INTERVAL,
                        Duration.ofDays(30),
                        Duration.ofDays(30),
                        "row-retention equal to payload-retention"));
    }

    @ParameterizedTest(name = "accepts {3}")
    @MethodSource("validConfigurations")
    void acceptsAUsableConfiguration(
            Duration heartbeatInterval, Duration payloadRetention, Duration rowRetention, String why) {
        assertThatCode(() -> props(heartbeatInterval, payloadRetention, rowRetention))
                .as(why)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the constructor preserves what it was given")
    void preservesConfiguredValues() {
        AgentProperties properties =
                props(AgentProperties.MIN_HEARTBEAT_INTERVAL, VALID_PAYLOAD_RETENTION, VALID_ROW_RETENTION);

        assertThat(properties.heartbeatInterval()).isEqualTo(AgentProperties.MIN_HEARTBEAT_INTERVAL);
        assertThat(properties.payloadRetention()).isEqualTo(VALID_PAYLOAD_RETENTION);
        assertThat(properties.rowRetention()).isEqualTo(VALID_ROW_RETENTION);
    }
}
