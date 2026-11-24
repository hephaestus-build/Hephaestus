package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.SmallCounts;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicy;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsageState;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsagePeriod;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads the released periods of the public usage page. Each read checks the switch and the withdrawals, and computes
 * nothing: it serves the stored rows (ADR 0153 decisions 14 to 16).
 */
@Service
@RequiredArgsConstructor
class PublicUsageService {

    private final UsageReleaseRepository releases;
    private final PublicUsagePolicy policy;

    @Transactional(readOnly = true)
    public PublicUsageDTO periods() {
        var state = available();
        return new PublicUsageDTO(
                releases.findReleased().stream().map(row -> period(row, state)).toList());
    }

    @Transactional(readOnly = true)
    public PublicUsagePeriodDTO period(String key) {
        var state = available();
        return UsagePeriod.parse(key)
                .flatMap(period -> releases.findReleased(period.start()))
                .map(row -> period(row, state))
                .orElseThrow(PublicUsageService::notFound);
    }

    private PublicUsageState available() {
        var state = policy.state();
        if (!state.allowed()) {
            throw notFound();
        }
        return state;
    }

    private static PublicUsagePeriodDTO period(UsageReleaseRepository.ReleasedRow row, PublicUsageState state) {
        var period = new UsagePeriod(row.periodStart());
        var stored = UsageFigures.read(row.schemaVersion(), row.figures());
        // A withdrawal hides published figures. A period below the floors has none, and keeps saying so.
        var figures = state.hides(period) && stored.status() != PublicUsageStatus.NOT_ENOUGH_ACTIVITY
                ? UsageFigures.notPublished()
                : stored;
        return new PublicUsagePeriodDTO(
                period.key(),
                period.start(),
                period.lastDay(),
                LocalDate.ofInstant(row.computedAt(), ZoneOffset.UTC),
                figures.status(),
                new PublicUsageFiguresDTO(
                        shown(figures, period, state, UsageFigure.WORKSPACES),
                        Optional.ofNullable(shown(figures, period, state, UsageFigure.REVIEWED_PEOPLE))
                                .map(developers -> SmallCounts.count(Math.toIntExact(developers)))
                                .orElse(null),
                        shown(figures, period, state, UsageFigure.REVIEWED_WORK),
                        shown(figures, period, state, UsageFigure.PRACTICE_REVIEWS),
                        shown(figures, period, state, UsageFigure.FEEDBACK_DELIVERED)));
    }

    private static @Nullable Long shown(
            UsageFigures figures, UsagePeriod period, PublicUsageState state, UsageFigure figure) {
        return state.hides(period, figure) ? null : figures.figure(figure);
    }

    private static ResponseStatusException notFound() {
        var exception = new ResponseStatusException(HttpStatus.NOT_FOUND, "These figures are not available.");
        exception.getBody().setInstance(URI.create("/public/usage"));
        return exception;
    }
}
