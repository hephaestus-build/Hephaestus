package de.tum.cit.aet.hephaestus.core.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pins the swallow-but-meter contract: an audit-write failure must NOT break the business flow, but
 * must be observable via {@code auth.audit.write_failed} (the sequence id is already burned, so a
 * failed save is a permanent gap in the append-only trail).
 */
class AuthEventWriterTest extends BaseUnitTest {

    @Test
    void writeFailure_isSwallowedButMetered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthMetrics metrics = new AuthMetrics(registry);
        AuthEventSequence sequence = mock(AuthEventSequence.class);
        when(sequence.next()).thenReturn(42L);
        AuthEventRepository repository = mock(AuthEventRepository.class);
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("boom"));
        AuthEventWriter writer = new AuthEventWriter(
                repository, sequence, metrics, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        AuthEventData data = new AuthEventData(
                AuthEvent.EventType.LOGIN, AuthEvent.Result.SUCCESS, 1L, null, null, null, null, null, null, false);

        // The swallow invariant: the request must not see the audit failure.
        assertThatCode(() -> writer.write(data)).doesNotThrowAnyException();
        // ...but it is observable.
        assertThat(registry.get("auth.audit.write_failed").counter().count()).isEqualTo(1.0);
    }
}
