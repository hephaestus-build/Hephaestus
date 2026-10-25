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

    private static final Duration VALID_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration VALID_HEARTBEAT_INTERVAL = Duration.ofSeconds(25);
    private static final Duration VALID_PAYLOAD_RETENTION = Duration.ofDays(14);
    private static final Duration VALID_ROW_RETENTION = Duration.ofDays(90);

    private static AgentProperties props(
            Duration pollInterval,
            int maxRetries,
            Duration heartbeatInterval,
            Duration payloadRetention,
            Duration rowRetention) {
        return new AgentProperties(
                false, pollInterval, 5, maxRetries, heartbeatInterval, payloadRetention, rowRetention, 16);
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of(
                        Duration.ofMillis(99),
                        VALID_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "poll-interval",
                        "just below the floor"),
                Arguments.of(
                        Duration.ZERO,
                        VALID_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "poll-interval",
                        "zero would busy-spin the poll loop"),
                Arguments.of(
                        Duration.ofSeconds(-1),
                        VALID_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "poll-interval",
                        "negative"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        Duration.ofMillis(500),
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "heartbeat-interval",
                        "sub-second"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        AgentProperties.WORKER_LEASE_TTL.plusSeconds(1),
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "heartbeat-interval",
                        "slower than the worker lease, so every worker would orphan its own running jobs"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        VALID_HEARTBEAT_INTERVAL,
                        Duration.ZERO,
                        VALID_ROW_RETENTION,
                        "payload-retention",
                        "zero"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        VALID_HEARTBEAT_INTERVAL,
                        Duration.ofDays(-1),
                        VALID_ROW_RETENTION,
                        "payload-retention",
                        "negative"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        VALID_HEARTBEAT_INTERVAL,
                        Duration.ofDays(90),
                        Duration.ofDays(14),
                        "row-retention",
                        "shorter than payload-retention"));
    }

    @ParameterizedTest(name = "{4}: {5}")
    @MethodSource("invalidConfigurations")
    void rejectsAnUnusableConfiguration(
            Duration pollInterval,
            Duration heartbeatInterval,
            Duration payloadRetention,
            Duration rowRetention,
            String offendingProperty,
            String why) {
        assertThatThrownBy(() -> props(pollInterval, 5, heartbeatInterval, payloadRetention, rowRetention))
                .as(why)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(offendingProperty);
    }

    static Stream<Arguments> validConfigurations() {
        return Stream.of(
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        5,
                        VALID_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "the documented defaults"),
                Arguments.of(
                        AgentProperties.MIN_POLL_INTERVAL,
                        5,
                        VALID_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "the poll-interval floor exactly"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        5,
                        AgentProperties.MIN_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "the heartbeat-interval floor exactly"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        5,
                        AgentProperties.MAX_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "the heartbeat-interval ceiling exactly"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        5,
                        VALID_HEARTBEAT_INTERVAL,
                        Duration.ofDays(30),
                        Duration.ofDays(30),
                        "row-retention equal to payload-retention"),
                Arguments.of(
                        VALID_POLL_INTERVAL,
                        0,
                        VALID_HEARTBEAT_INTERVAL,
                        VALID_PAYLOAD_RETENTION,
                        VALID_ROW_RETENTION,
                        "zero max-retries, a valid 'no retries' policy"));
    }

    @ParameterizedTest(name = "accepts {5}")
    @MethodSource("validConfigurations")
    void acceptsAUsableConfiguration(
            Duration pollInterval,
            int maxRetries,
            Duration heartbeatInterval,
            Duration payloadRetention,
            Duration rowRetention,
            String why) {
        assertThatCode(() -> props(pollInterval, maxRetries, heartbeatInterval, payloadRetention, rowRetention))
                .as(why)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the constructor preserves what it was given")
    void preservesConfiguredValues() {
        AgentProperties properties = props(
                AgentProperties.MIN_POLL_INTERVAL,
                0,
                AgentProperties.MIN_HEARTBEAT_INTERVAL,
                VALID_PAYLOAD_RETENTION,
                VALID_ROW_RETENTION);

        assertThat(properties.pollInterval()).isEqualTo(AgentProperties.MIN_POLL_INTERVAL);
        assertThat(properties.heartbeatInterval()).isEqualTo(AgentProperties.MIN_HEARTBEAT_INTERVAL);
        assertThat(properties.maxRetries()).isZero();
        assertThat(properties.payloadRetention()).isEqualTo(VALID_PAYLOAD_RETENTION);
        assertThat(properties.rowRetention()).isEqualTo(VALID_ROW_RETENTION);
    }
}
