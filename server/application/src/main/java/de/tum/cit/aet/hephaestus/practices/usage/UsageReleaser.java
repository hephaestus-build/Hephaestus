package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicy;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsagePeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases the half-years of the public usage page (ADR 0153 decisions 4 to 7), from one statement over one snapshot.
 * Each period is stored once with only its disclosed figures; a stored period is never computed again.
 */
@ConditionalOnServerRole
@Component
@RequiredArgsConstructor
class UsageReleaser {

    /** The first period that the first release looks at: earlier than any practice review. */
    static final UsagePeriod BEGINNING = new UsagePeriod(LocalDate.of(2000, 4, 1));

    private final UsageReleaseRepository releases;
    private final PublicUsagePolicy policy;

    /**
     * Releases every period whose release time has come and that is not stored yet. With no period stored, the first
     * release starts at the earliest period with a practice review that is not a trial. After that, each run releases
     * the periods after the latest stored one.
     *
     * <p>The advisory lock serializes releases across replicas, so a second replica waits, reads the periods that the
     * first stored, and counts nothing. Switch changes and withdrawals do not take it, so a long count never holds an
     * admin back. The release counts, stages its inserts, and only then checks under a share lock on the settings row
     * that the page is still on and the row unchanged since it began. So the share lock lasts only to the commit, and
     * a page that went off, or off and on again, while the run counted discards the whole batch. The transaction is
     * bounded to 10 minutes, so a stalled run rolls back and stores nothing; the next hourly run tries again.
     *
     * @return the periods that this run stored; logged by the caller once they are committed
     * @throws ReleaseDiscarded when the settings changed during the run, which rolls back everything it staged
     */
    @Transactional(timeout = 600)
    public List<UsagePeriod> releaseDue(Instant now) {
        var gate = policy.lockForRelease();
        if (!gate.allowed()) {
            return List.of();
        }
        UsagePeriod through = UsagePeriod.latestReleasable(now);
        var latest = releases.findLatestPeriodStart().map(UsagePeriod::new);
        if (latest.isPresent() && latest.get().compareTo(through) >= 0) {
            return List.of();
        }
        var released = latest.map(stored -> store(stored.next(), through, false, now))
                .orElseGet(() -> store(BEGINNING, through, true, now));
        if (!released.isEmpty() && !policy.unchangedUntilCommit(gate)) {
            throw new ReleaseDiscarded();
        }
        return released;
    }

    /**
     * Counts each period from {@code from} through {@code through} and stages the ones not stored yet.
     *
     * @param fromFirstReview whether to skip the leading periods without a practice review, as the first release does
     */
    private List<UsagePeriod> store(
            UsagePeriod from, UsagePeriod through, boolean fromFirstReview, Instant releasedAt) {
        Map<String, List<UsageCount>> counts =
                releases.count(from.start().atStartOfDay(), through.start().atStartOfDay()).stream()
                        .collect(Collectors.groupingBy(UsageCount::period));
        var released = new ArrayList<UsagePeriod>();
        boolean started = !fromFirstReview;
        for (var period = from; period.compareTo(through) <= 0; period = period.next()) {
            var periodCounts = counts.getOrDefault(period.key(), List.of());
            started = started || periodCounts.stream().anyMatch(UsageReleaser::hasPracticeReviews);
            if (started) {
                var figures = UsageDisclosure.disclose(periodCounts);
                if (releases.insertOnce(
                                period.start(), period.end(), releasedAt, UsageFigures.VERSION, figures.toJson())
                        == 1) {
                    released.add(period);
                }
            }
        }
        return released;
    }

    private static boolean hasPracticeReviews(UsageCount count) {
        return count.figure().equals(UsageFigure.PRACTICE_REVIEWS.name()) && count.total() > 0;
    }
}
