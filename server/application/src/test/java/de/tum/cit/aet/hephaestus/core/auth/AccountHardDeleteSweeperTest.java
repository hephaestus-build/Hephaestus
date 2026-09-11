package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class AccountHardDeleteSweeperTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountPurger accountPurger;

    @Mock
    private AuthProperties authProperties;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PrivacyJobMetrics metrics = new PrivacyJobMetrics(registry);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldContinuePurgingWhenOneAccountFails() {
        Duration cooldown = Duration.ofHours(48);
        when(authProperties.deleteCooldown()).thenReturn(cooldown);
        when(accountRepository.findDeletingPastCooldown(eq(NOW.minus(cooldown)), any()))
                .thenReturn(List.of(1L, 2L))
                .thenReturn(List.of(2L));
        doAnswer(invocation -> {
                    if (invocation.<Long>getArgument(0) == 2L) {
                        throw new RuntimeException("purge failed");
                    }
                    return null;
                })
                .when(accountPurger)
                .purge(anyLong());
        AccountHardDeleteSweeper sweeper =
                new AccountHardDeleteSweeper(accountRepository, accountPurger, authProperties, clock, metrics);

        assertThat(sweeper.sweepNow()).isEqualTo(1);
        verify(accountPurger).purge(1L);
        verify(accountPurger, times(2)).purge(2L);
    }

    @Test
    void shouldRecordSuccessfulEmptySweep() {
        when(authProperties.deleteCooldown()).thenReturn(Duration.ofHours(48));
        when(accountRepository.findDeletingPastCooldown(any(), any())).thenReturn(List.of());
        AccountHardDeleteSweeper sweeper =
                new AccountHardDeleteSweeper(accountRepository, accountPurger, authProperties, clock, metrics);

        sweeper.sweep();

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "account_erasure", "outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("privacy.job.affected")
                        .tag("job", "account_erasure")
                        .counter()
                        .count())
                .isEqualTo(0);
    }

    @Test
    void shouldRecordSweepQueryFailure() {
        when(authProperties.deleteCooldown()).thenReturn(Duration.ofHours(48));
        when(accountRepository.findDeletingPastCooldown(any(), any())).thenThrow(new IllegalStateException("db down"));
        AccountHardDeleteSweeper sweeper =
                new AccountHardDeleteSweeper(accountRepository, accountPurger, authProperties, clock, metrics);

        assertThatThrownBy(sweeper::sweep).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "account_erasure", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
