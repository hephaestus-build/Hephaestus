package de.tum.cit.aet.hephaestus.core.settings.spi;

import org.jspecify.annotations.Nullable;

/**
 * Port for the public usage page (ADR 0153): whether it is on, and what an instance admin withdrew. Each public read
 * asks again, uncached, so turning the page off or a withdrawal applies to the next read.
 */
public interface PublicUsagePolicy {

    PublicUsageState state();

    /**
     * Takes the lock that serializes releases across replicas, held until the caller's transaction ends, and reads
     * the gate of the release. Switch changes and withdrawals do not take the lock, so a release checks the gate again
     * before it commits ({@link #unchangedUntilCommit(ReleaseGate)}).
     */
    ReleaseGate lockForRelease();

    /**
     * Whether the page was on at the last background read, at most 30 seconds ago, for the footer link of each page
     * load. It never reads the database, so a page load never waits for it. Until the first read, and while the
     * database cannot answer, it keeps the last value it knew, starting from off.
     */
    boolean lastKnownAllowed();

    /**
     * Whether the page is on and the settings row is unchanged since the gate was read, read fresh under a share lock
     * on the settings row that the caller's transaction holds until it ends. A change that is in progress is waited
     * for, and a change that starts later waits for the caller's commit. So nothing that the caller stored before
     * this read commits after the page went off, or after it went off and on again.
     */
    boolean unchangedUntilCommit(ReleaseGate gate);

    /**
     * Withdraws a released period, or one of its figures, for good, and records the audit event in the caller's
     * transaction. A second withdrawal of the same changes nothing. The caller checks that the period is released.
     */
    PublicUsageState withdraw(UsagePeriod period, @Nullable UsageFigure figure);
}
