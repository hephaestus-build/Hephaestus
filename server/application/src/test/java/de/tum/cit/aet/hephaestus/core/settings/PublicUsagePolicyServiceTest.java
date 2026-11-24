package de.tum.cit.aet.hephaestus.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsageState;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsagePeriod;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

class PublicUsagePolicyServiceTest extends BaseUnitTest {
    @Mock
    private InstanceSettingsRepository settings;

    @Mock
    private AccountRepository accounts;

    private static final UsagePeriod SUMMER = new UsagePeriod(LocalDate.of(2026, 4, 1));
    private static final UsagePeriod WINTER = new UsagePeriod(LocalDate.of(2025, 10, 1));

    @Test
    void shouldStayOffUntilTheConfigurationOrAnAdministratorTurnsItOn() {
        assertThat(new PublicUsagePolicyService(settings, accounts, Optional.empty(), false)
                        .state()
                        .allowed())
                .isFalse();
        var configured = new PublicUsagePolicyService(settings, accounts, Optional.empty(), true);
        assertThat(configured.state().allowed()).isTrue();
        var row = new InstanceSettings();
        row.setId(InstanceSettings.SINGLETON_ID);
        when(settings.findById(InstanceSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        row.setPublicUsageAllowed(false);
        assertThat(configured.state().allowed()).isFalse();
    }

    @Test
    void shouldKeepTheLastKnownSwitchForPageLoadsWhenTheDatabaseCannotAnswer() {
        var policy = new PublicUsagePolicyService(settings, accounts, Optional.empty(), false);
        when(settings.readPublicUsageAllowed())
                .thenReturn(true)
                .thenThrow(new DataAccessResourceFailureException("database down"));

        assertThat(policy.lastKnownAllowed()).isFalse();
        policy.refreshLastKnownAllowed();
        assertThat(policy.lastKnownAllowed()).isTrue();
        policy.refreshLastKnownAllowed();
        assertThat(policy.lastKnownAllowed()).isTrue();
    }

    @Test
    void shouldRefuseAChangeWhoseActorIsNoLongerActiveBeforeItLocksTheSettings() {
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext("42", new String[0], "token"));
        try {
            var policy = new PublicUsagePolicyService(settings, accounts, Optional.empty(), false);
            // The admin's account was erased while the request was on its way.
            when(accounts.lockStatusForShare(42L)).thenReturn(Optional.of(Account.Status.DELETED.name()));

            assertThatThrownBy(() -> policy.update(false)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> policy.withdraw(SUMMER, null)).isInstanceOf(AccessDeniedException.class);
            verify(settings, never()).lockById(anyLong());
            verify(settings, never()).saveAndFlush(any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void shouldHideAWithdrawnPeriodOrFigureAndNothingElse() {
        var state = PublicUsageState.of(
                true,
                List.of(
                        PublicUsageState.entry(SUMMER, null),
                        PublicUsageState.entry(WINTER, UsageFigure.REVIEWED_PEOPLE)));

        assertThat(state.hides(SUMMER)).isTrue();
        assertThat(state.hides(SUMMER, UsageFigure.WORKSPACES)).isTrue();
        assertThat(state.hides(WINTER)).isFalse();
        assertThat(state.hides(WINTER, UsageFigure.REVIEWED_PEOPLE)).isTrue();
        assertThat(state.hides(WINTER, UsageFigure.WORKSPACES)).isFalse();
    }

    @Test
    void shouldHideEverythingWhenAStoredWithdrawalCannotBeRead() {
        var state = PublicUsageState.of(true, List.of("2026-04/DEVELOPER", "2026-05"));

        assertThat(state.unreadable()).isTrue();
        assertThat(state.hides(WINTER)).isTrue();
        assertThat(state.hides(WINTER, UsageFigure.WORKSPACES)).isTrue();
    }

    @Test
    void shouldKeepTheReadableWithdrawalsWhenAStoredEntryIsNoText() {
        var state = PublicUsageState.of(true, Arrays.asList("2026-04", null));

        assertThat(state.unreadable()).isTrue();
        assertThat(state.withdrawn()).containsExactly("2026-04");
        assertThat(state.hides(WINTER)).isTrue();
    }

    @Test
    void shouldFindAWithdrawalWhateverTheLocaleOfTheServer() {
        var state = PublicUsageState.of(true, List.of("2026-04/REVIEWED_PEOPLE"));
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertThat(SUMMER.key()).isEqualTo("2026-04");
            assertThat(state.hides(SUMMER, UsageFigure.REVIEWED_PEOPLE)).isTrue();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void shouldKeepHalfYearsApartAndReleaseEachFourteenDaysAfterItEnds() {
        assertThat(UsagePeriod.containing(Instant.parse("2026-03-31T23:59:59Z")))
                .isEqualTo(WINTER);
        assertThat(UsagePeriod.containing(Instant.parse("2026-04-01T00:00:00Z")))
                .isEqualTo(SUMMER);
        assertThat(UsagePeriod.containing(Instant.parse("2025-10-01T00:00:00Z")))
                .isEqualTo(WINTER);
        assertThat(WINTER.next()).isEqualTo(SUMMER);
        assertThat(WINTER.lastDay()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(WINTER.releaseAt()).isEqualTo(Instant.parse("2026-04-15T00:00:00Z"));
        assertThat(UsagePeriod.latestReleasable(Instant.parse("2026-04-14T23:59:59Z")))
                .isEqualTo(WINTER.previous());
        assertThat(UsagePeriod.latestReleasable(Instant.parse("2026-04-15T00:00:00Z")))
                .isEqualTo(WINTER);
        assertThat(UsagePeriod.parse("2026-04")).contains(SUMMER);
        assertThat(UsagePeriod.parse("2026-05")).isEmpty();
        assertThat(UsagePeriod.parse("2026-4")).isEmpty();
        // A period can only start on 1 April or 1 October, so two periods never overlap.
        assertThatThrownBy(() -> new UsagePeriod(LocalDate.of(2026, 5, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePeriod(LocalDate.of(2026, 4, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
