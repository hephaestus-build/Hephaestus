package de.tum.cit.aet.hephaestus.agent.usage.fx;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the one exchange rate a given month's USD figures are displayed with: the month in progress
 * tracks the newest stored rate, while a closed month uses the last rate published on or before its
 * final day, so its figure is frozen once the month ends.
 *
 * <p>When the newest stored rate is more than {@link #MAX_RATE_AGE_DAYS} old every lookup returns empty
 * and the UI falls back to USD-only: a conversion quietly drifting from reality is worse than none.
 */
@Service
public class FxRateLookup {

    /**
     * Clears the longest normal publication gap — the ECB skips weekends and TARGET holidays — while
     * still catching a fetcher that has died.
     */
    public static final int MAX_RATE_AGE_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(FxRateLookup.class);

    private final FxRateRepository fxRateRepository;
    private final Clock clock;

    private final Optional<String> displayCurrency;

    public FxRateLookup(FxRateRepository fxRateRepository, Clock clock, LlmProperties llmProperties) {
        this.fxRateRepository = fxRateRepository;
        this.clock = clock;
        this.displayCurrency = resolveCurrency(llmProperties.displayCurrency());
    }

    public boolean isEnabled() {
        return displayCurrency.isPresent();
    }

    @Transactional(readOnly = true)
    public Optional<FxRateInfoDTO> latest() {
        return freshLatest().map(this::toInfo);
    }

    @Transactional(readOnly = true)
    public Optional<FxRateInfoDTO> forMonth(YearMonth month) {
        Optional<FxRate> newest = freshLatest();
        if (newest.isEmpty()) {
            return Optional.empty();
        }
        YearMonth currentMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        if (!month.isBefore(currentMonth)) {
            return newest.map(this::toInfo);
        }
        return fxRateRepository
                .findTopByRateDateLessThanEqualOrderByRateDateDesc(month.atEndOfMonth())
                // The month predates every rate we hold; the reported rateDate makes the approximation visible.
                .or(fxRateRepository::findTopByOrderByRateDateAsc)
                .map(this::toInfo);
    }

    private Optional<FxRate> freshLatest() {
        if (displayCurrency.isEmpty()) {
            return Optional.empty();
        }
        Optional<FxRate> newest = fxRateRepository.findTopByOrderByRateDateDesc();
        if (newest.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate newestDate = newest.get().getRateDate();
        if (newestDate.isBefore(today.minusDays(MAX_RATE_AGE_DAYS))) {
            log.warn(
                    "fx: newest stored rate is {} (older than {} days) — omitting display-currency conversion",
                    newestDate,
                    MAX_RATE_AGE_DAYS);
            return Optional.empty();
        }
        return newest;
    }

    private FxRateInfoDTO toInfo(FxRate rate) {
        return FxRateInfoDTO.fromEcbRate(displayCurrency.orElseThrow(), rate.getUsdPerEur(), rate.getRateDate());
    }

    /** Unset is the only way to reach this feature's off state; anything else has failed startup. */
    private static Optional<String> resolveCurrency(String configured) {
        String code = configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
        return code.isEmpty() ? Optional.empty() : Optional.of(code);
    }
}
