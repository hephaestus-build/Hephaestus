package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.event.AccountDeletionRequestedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class AccountHardDeleteSweeperTest extends BaseUnitTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountPurger accountPurger;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PrivacyJobMetrics metrics = new PrivacyJobMetrics(registry);

    @Test
    void shouldContinuePurgingWhenOneAccountFails() {
        when(accountRepository.findDeleting(eq(0L), any())).thenReturn(List.of(1L, 2L));
        when(accountRepository.findDeleting(eq(2L), any())).thenReturn(List.of());
        doAnswer(invocation -> {
                    if (invocation.<Long>getArgument(0) == 2L) {
                        throw new RuntimeException("purge failed");
                    }
                    return null;
                })
                .when(accountPurger)
                .purge(anyLong());
        AccountHardDeleteSweeper sweeper = new AccountHardDeleteSweeper(accountRepository, accountPurger, metrics);

        assertThat(sweeper.sweepNow()).isEqualTo(1);
        verify(accountPurger).purge(1L);
        verify(accountPurger).purge(2L);
    }

    @Test
    void shouldRecordSuccessfulEmptySweep() {
        when(accountRepository.findDeleting(anyLong(), any())).thenReturn(List.of());
        AccountHardDeleteSweeper sweeper = new AccountHardDeleteSweeper(accountRepository, accountPurger, metrics);

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
        when(accountRepository.findDeleting(anyLong(), any())).thenThrow(new IllegalStateException("db down"));
        AccountHardDeleteSweeper sweeper = new AccountHardDeleteSweeper(accountRepository, accountPurger, metrics);

        assertThatThrownBy(sweeper::sweep).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "account_erasure", "outcome", "failure")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldVisitEveryDeletingAccountOnceEvenWhenTheFirstPageCannotBePurged() {
        when(accountRepository.findDeleting(eq(0L), any())).thenReturn(List.of(1L, 2L));
        when(accountRepository.findDeleting(eq(2L), any())).thenReturn(List.of(3L));
        when(accountRepository.findDeleting(eq(3L), any())).thenReturn(List.of());
        doAnswer(invocation -> {
                    if (invocation.<Long>getArgument(0) != 3L) {
                        throw new IllegalStateException("external copies remain");
                    }
                    return null;
                })
                .when(accountPurger)
                .purge(anyLong());
        AccountHardDeleteSweeper sweeper = new AccountHardDeleteSweeper(accountRepository, accountPurger, metrics);

        assertThat(sweeper.sweepNow()).isEqualTo(1);
        verify(accountPurger).purge(3L);
    }

    @Test
    void shouldPurgeTheAccountWhenItsDeletionCommits() {
        AccountHardDeleteSweeper sweeper = new AccountHardDeleteSweeper(accountRepository, accountPurger, metrics);

        sweeper.on(new AccountDeletionRequestedEvent(7L));

        verify(accountPurger).purge(7L);
    }

    @Test
    void shouldLeaveAnUnfinishedPurgeToTheHourlySweepWithoutCountingARun() {
        doAnswer(invocation -> {
                    throw new IllegalStateException("external copies remain");
                })
                .when(accountPurger)
                .purge(7L);
        AccountHardDeleteSweeper sweeper = new AccountHardDeleteSweeper(accountRepository, accountPurger, metrics);

        sweeper.on(new AccountDeletionRequestedEvent(7L));

        // The sweep's runs are the liveness signal; a purge on deletion must not stand in for them.
        assertThat(registry.get("privacy.job.completed")
                        .tags("job", "account_erasure", "outcome", "failure")
                        .counter()
                        .count())
                .isZero();
    }
}
