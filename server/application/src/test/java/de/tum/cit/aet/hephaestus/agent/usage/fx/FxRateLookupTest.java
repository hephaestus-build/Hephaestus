package de.tum.cit.aet.hephaestus.agent.usage.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class FxRateLookupTest extends BaseUnitTest {

    /** A Saturday — the ECB publishes nothing on weekends, so "today" has no rate of its own. */
    private static final LocalDate SATURDAY = LocalDate.of(2026, 7, 25);

    private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 24);
    private static final Clock CLOCK =
            Clock.fixed(SATURDAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final YearMonth CURRENT_MONTH = YearMonth.of(2026, 7);
    private static final YearMonth CLOSED_MONTH = YearMonth.of(2026, 6);
    private static final YearMonth PRE_FEATURE_MONTH = YearMonth.of(2026, 1);

    @Mock
    private FxRateRepository repository;

    private static FxRate rate(LocalDate date, String usdPerEur) {
        FxRate row = new FxRate();
        row.setRateDate(date);
        row.setUsdPerEur(new BigDecimal(usdPerEur));
        row.setFetchedAt(Instant.EPOCH);
        return row;
    }

    private FxRateLookup lookup(String configuredCurrency) {
        LlmProperties properties = new LlmProperties(
                configuredCurrency,
                new LlmProperties.Egress(false, List.of(), false),
                new LlmProperties.Fx(LlmProperties.ECB_DAILY_URL));
        return new FxRateLookup(repository, CLOCK, properties);
    }

    private FxRateLookup enabledLookup() {
        return lookup("EUR");
    }

    /**
     * Unset is the only off state left: every other value {@code LlmPropertiesTest} shows the
     * instance refuses to boot with, so there is nothing here to degrade from.
     */
    @Test
    void shouldStayOffAndNeverTouchTheTableWhenUnset() {
        FxRateLookup fx = lookup("");

        assertThat(fx.isEnabled()).isFalse();
        assertThat(fx.latest()).isEmpty();
        assertThat(fx.forMonth(CURRENT_MONTH)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("should accept a lower-case currency code")
    void shouldEnableWhenCurrencyCodeIsLowerCase() {
        when(repository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(rate(FRIDAY, "1.1377")));

        FxRateLookup fx = lookup("eur");

        assertThat(fx.isEnabled()).isTrue();
        assertThat(fx.latest())
                .hasValueSatisfying(info -> assertThat(info.currencyCode()).isEqualTo("EUR"));
    }

    @Test
    @DisplayName("should resolve the month in progress to the newest stored rate")
    void shouldUseNewestRateWhenMonthIsInProgress() {
        when(repository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(rate(FRIDAY, "1.1377")));

        Optional<FxRateInfoDTO> info = enabledLookup().forMonth(CURRENT_MONTH);

        // Today is a Saturday the ECB published nothing on: the label must report Friday, the rate
        // actually used, rather than imply a Saturday rate exists.
        assertThat(info).hasValueSatisfying(fx -> {
            assertThat(fx.rateDate()).isEqualTo(FRIDAY);
            assertThat(fx.ratePerUsd()).isEqualByComparingTo("0.878966");
        });
        verify(repository, never()).findTopByOrderByRateDateAsc();
    }

    /**
     * Both halves in one test because the second is only meaningful given the first: asserting the
     * freeze without asserting what it froze to would pass on a lookup that returned nothing.
     */
    @Test
    @DisplayName("should resolve a closed month to its last in-month rate and keep it after newer rates arrive")
    void shouldFreezeClosedMonthOnItsLastInMonthRate() {
        when(repository.findTopByRateDateLessThanEqualOrderByRateDateDesc(LocalDate.of(2026, 6, 30)))
                .thenReturn(Optional.of(rate(LocalDate.of(2026, 6, 30), "1.1200")));
        when(repository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(rate(FRIDAY, "1.1377")));
        FxRateLookup fx = enabledLookup();

        FxRateInfoDTO before = fx.forMonth(CLOSED_MONTH).orElseThrow();

        assertThat(before.rateDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(before.ratePerUsd()).isEqualByComparingTo("0.892857"); // 1 / 1.1200, inverted once

        when(repository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(rate(SATURDAY, "1.4000")));
        FxRateInfoDTO after = fx.forMonth(CLOSED_MONTH).orElseThrow();

        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("should fall back to the oldest stored rate for a month that predates every rate")
    void shouldUseOldestRateWhenMonthPredatesAllStoredRates() {
        when(repository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(rate(FRIDAY, "1.1377")));
        when(repository.findTopByRateDateLessThanEqualOrderByRateDateDesc(LocalDate.of(2026, 1, 31)))
                .thenReturn(Optional.empty());
        when(repository.findTopByOrderByRateDateAsc())
                .thenReturn(Optional.of(rate(LocalDate.of(2026, 5, 4), "1.0800")));

        Optional<FxRateInfoDTO> info = enabledLookup().forMonth(PRE_FEATURE_MONTH);

        // Approximate on purpose — and honest about it: the reported date is May, not January.
        assertThat(info).hasValueSatisfying(fx -> {
            assertThat(fx.rateDate()).isEqualTo(LocalDate.of(2026, 5, 4));
            assertThat(fx.ratePerUsd()).isEqualByComparingTo("0.925926");
        });
    }

    @Test
    @DisplayName("should return empty for every month when the table is empty")
    void shouldReturnEmptyWhenNoRatesStored() {
        when(repository.findTopByOrderByRateDateDesc()).thenReturn(Optional.empty());

        FxRateLookup fx = enabledLookup();

        assertThat(fx.latest()).isEmpty();
        assertThat(fx.forMonth(CLOSED_MONTH)).isEmpty();
        verify(repository, never()).findTopByRateDateLessThanEqualOrderByRateDateDesc(any());
    }

    @Test
    @DisplayName("should omit the conversion when the newest stored rate is 8 days old")
    void shouldReturnEmptyWhenNewestRateIsEightDaysOld() {
        when(repository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(rate(SATURDAY.minusDays(8), "1.1377")));

        FxRateLookup fx = enabledLookup();

        assertThat(fx.latest()).isEmpty();
        assertThat(fx.forMonth(CURRENT_MONTH)).isEmpty();
        assertThat(fx.forMonth(CLOSED_MONTH)).isEmpty();
    }

    @Test
    @DisplayName("should still convert when the newest stored rate is exactly 7 days old")
    void shouldStillConvertWhenNewestRateIsSevenDaysOld() {
        // A long TARGET holiday run must not switch the feature off.
        when(repository.findTopByOrderByRateDateDesc()).thenReturn(Optional.of(rate(SATURDAY.minusDays(7), "1.1377")));

        assertThat(enabledLookup().latest()).isPresent();
    }
}
