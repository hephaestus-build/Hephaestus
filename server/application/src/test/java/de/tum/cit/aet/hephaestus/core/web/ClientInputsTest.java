package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ClientInputsTest {
    @Test
    void shouldEnforceTheRetryWindowAndClockSkew() {
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        var inputs = new ClientInputs(
                mock(JdbcClient.class), mock(OwnerScope.class), JsonMapper.shared(), Clock.fixed(now, ZoneOffset.UTC));
        Instant oldest = now.minus(Duration.ofDays(3));
        Instant newest = now.plus(Duration.ofMinutes(5));

        inputs.validateAge(keyAt(oldest));
        inputs.validateAge(keyAt(newest));
        assertThatThrownBy(() -> inputs.validateAge(keyAt(oldest.minusMillis(1))))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> inputs.validateAge(keyAt(newest.plusMillis(1))))
                .isInstanceOf(InvalidInputException.class);
    }

    private static UUID keyAt(Instant time) {
        return new UUID((time.toEpochMilli() << 16) | 0x7000L, Long.MIN_VALUE);
    }

    @Test
    void shouldIncludeAttachmentHashesInTheFingerprint() {
        var inputs = new ClientInputs(
                mock(JdbcClient.class), mock(OwnerScope.class), JsonMapper.shared(), Clock.systemUTC());
        var first = inputs.fingerprint("submit", "A message", List.of("a".repeat(64)));
        assertThat(inputs.fingerprint("submit", "A message", List.of("a".repeat(64))))
                .isEqualTo(first);
        assertThat(inputs.fingerprint("submit", "A message", List.of("b".repeat(64))))
                .isNotEqualTo(first);
    }
}
