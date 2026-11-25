package de.tum.cit.aet.hephaestus.practices.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import de.tum.cit.aet.hephaestus.core.settings.spi.UsagePeriod;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The release of the public usage page against the migrated schema (ADR 0153). It fails when a figure below a floor or
 * one that a workspace dominates is stored, when a trial counts, or when a period is computed or released twice.
 */
class UsageReleaseIntegrationTest extends BaseIntegrationTest {

    private static final UsagePeriod PERIOD = new UsagePeriod(LocalDate.of(2025, 4, 1));
    private static final Instant DURING = Instant.parse("2025-06-01T10:00:00Z");
    private static final Instant RELEASED = Instant.parse("2025-10-15T00:00:00Z");

    @Autowired
    private UsageReleaser service;

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UsageReleaseRepository releases;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanDatabase() {
        databaseTestUtils.cleanDatabase();
    }

    /** Ten workspaces with two developers each, every developer reviewed once and given one delivered feedback. */
    private List<Scenario> tenBusyWorkspaces() {
        var developers = new ArrayList<Scenario>();
        for (int i = 0; i < 10; i++) {
            var first = data.scenario();
            var second = new Scenario(
                    first.workspace(),
                    data.member(first.workspace().getId()),
                    first.practice(),
                    data.onOtherWork(first).item());
            for (var developer : List.of(first, second)) {
                reviewed(developer, DURING);
                developers.add(developer);
            }
        }
        return developers;
    }

    private void reviewed(Scenario developer, Instant at) {
        var result = data.result(developer, ResultOutcome.MET, at);
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,"
                        + "delivered_at) VALUES (?,?,?,?,?,'ON_WORK','DELIVERED','Fixture feedback',?,?)",
                UUID.randomUUID(),
                developer.workspace().getId(),
                result.getReviewId(),
                developer.item().getId(),
                developer.account().getId(),
                Timestamp.from(at),
                Timestamp.from(at));
    }

    private String stored(UsagePeriod period) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT figures::text FROM usage_release WHERE period_start = ?", String.class, period.start()));
    }

    private UsageFigures figures(UsagePeriod period) {
        return UsageFigures.read(UsageFigures.VERSION, stored(period));
    }

    @Test
    void shouldStoreOnlyRoundedFiguresWhenBothFloorsHold() {
        tenBusyWorkspaces();

        assertThat(releaseAt(RELEASED)).containsExactly(PERIOD);

        var figures = figures(PERIOD);
        assertThat(figures.status()).isEqualTo(PublicUsageStatus.PUBLISHED);
        assertThat(figures.figure(UsageFigure.WORKSPACES)).isEqualTo(10L);
        assertThat(figures.figure(UsageFigure.REVIEWED_PEOPLE)).isEqualTo(20L);
        assertThat(figures.figure(UsageFigure.REVIEWED_WORK)).isEqualTo(20L);
        assertThat(figures.figure(UsageFigure.PRACTICE_REVIEWS)).isEqualTo(20L);
        assertThat(figures.figure(UsageFigure.FEEDBACK_DELIVERED)).isEqualTo(20L);
    }

    @Test
    void shouldCountADeveloperOfManyWorkspacesOnce() {
        var shared = data.scenario();
        reviewed(shared, DURING);
        reviewed(
                new Scenario(
                        shared.workspace(),
                        data.member(shared.workspace().getId()),
                        shared.practice(),
                        data.onOtherWork(shared).item()),
                DURING);
        for (int i = 0; i < 9; i++) {
            var own = data.scenario();
            reviewed(own, DURING);
            jdbc.update(
                    "INSERT INTO membership(workspace_id,account_id,role,hidden,review_selected,public_activity_seen,"
                            + "joined_at) VALUES (?,?,'MEMBER',false,false,false,?)",
                    own.workspace().getId(),
                    shared.account().getId(),
                    Timestamp.from(DURING));
            reviewed(
                    new Scenario(
                            own.workspace(),
                            shared.account(),
                            own.practice(),
                            data.onOtherWork(own).item()),
                    DURING);
        }

        releaseAt(RELEASED);

        // 11 people, not the 20 that the workspaces count one by one.
        assertThat(figures(PERIOD).figure(UsageFigure.REVIEWED_PEOPLE)).isEqualTo(10L);
        assertThat(figures(PERIOD).figure(UsageFigure.PRACTICE_REVIEWS)).isEqualTo(20L);
    }

    @Test
    void shouldHideTheFiguresThatOneWorkspaceDominates() {
        var developers = tenBusyWorkspaces();
        var busiest = developers.getFirst();
        for (int i = 0; i < 15; i++) {
            data.result(data.onOtherWork(busiest), ResultOutcome.MET, DURING);
        }

        releaseAt(RELEASED);

        var figures = figures(PERIOD);
        assertThat(figures.figure(UsageFigure.REVIEWED_WORK))
                .as("17 of 35 from one workspace")
                .isNull();
        assertThat(figures.figure(UsageFigure.PRACTICE_REVIEWS)).isNull();
        assertThat(figures.figure(UsageFigure.WORKSPACES)).isEqualTo(10L);
        assertThat(figures.figure(UsageFigure.REVIEWED_PEOPLE)).isEqualTo(20L);
        assertThat(stored(PERIOD)).doesNotContain("35").doesNotContain("17");
    }

    @Test
    void shouldPublishNothingBelowTheFloorAndNotCountTrials() {
        for (int i = 0; i < 4; i++) {
            var first = data.scenario();
            reviewed(first, DURING);
            reviewed(
                    new Scenario(
                            first.workspace(),
                            data.member(first.workspace().getId()),
                            first.practice(),
                            data.onOtherWork(first).item()),
                    DURING);
        }
        var trialOnly = data.scenario();
        UUID trial = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,requested_by_id,status,ordered_at,"
                        + "created_at) VALUES (?,?,?,'ITEM','v1','TRIAL',?,'RUNNING',?,?)",
                trial,
                trialOnly.workspace().getId(),
                trialOnly.item().getId(),
                trialOnly.account().getId(),
                Timestamp.from(DURING),
                Timestamp.from(DURING));
        jdbc.update(
                "INSERT INTO review_practice(review_id,practice_id,workspace_id,status) VALUES (?,?,?,'READY')",
                trial,
                trialOnly.practice().getId(),
                trialOnly.workspace().getId());

        releaseAt(RELEASED);

        var figures = figures(PERIOD);
        assertThat(figures.status())
                .as("four workspaces; the trial does not make a fifth")
                .isEqualTo(PublicUsageStatus.NOT_ENOUGH_ACTIVITY);
        assertThat(figures.figures()).isEmpty();
    }

    @Test
    void shouldReleaseAPeriodOnceAndNeverComputeItAgain() {
        tenBusyWorkspaces();
        releaseAt(RELEASED);
        String first = stored(PERIOD);

        // Work that arrives later, with an event time in the period, is not counted.
        for (int i = 0; i < 30; i++) {
            reviewed(data.scenario(), DURING);
        }

        assertThat(releaseAt(RELEASED.plusSeconds(3600))).isEmpty();
        assertThat(stored(PERIOD)).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "SELECT computed_at FROM usage_release WHERE period_start = ?", Instant.class, PERIOD.start()))
                .isEqualTo(RELEASED);
        assertThatThrownBy(() -> jdbc.update("UPDATE usage_release SET figures = '{}'::jsonb"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM usage_release")).isInstanceOf(DataAccessException.class);
        assertThat(stored(PERIOD)).isEqualTo(first);
    }

    @Test
    void shouldNotComputeAStoredPeriodAgainWhenTheJobRunsAgain() {
        tenBusyWorkspaces();
        turnOn();
        var due = PERIOD.releaseAt();

        assertThat(service.releaseDue(due)).contains(PERIOD);
        String first = stored(PERIOD);
        for (int i = 0; i < 30; i++) {
            reviewed(data.scenario(), DURING);
        }

        assertThat(service.releaseDue(due.plusSeconds(3600))).isEmpty();
        assertThat(stored(PERIOD)).isEqualTo(first);
    }

    private void turnOn() {
        jdbc.update("INSERT INTO instance_settings (id, silent_mode_engaged, version, public_usage_allowed)"
                + " VALUES (1, true, 0, true) ON CONFLICT (id) DO UPDATE SET public_usage_allowed = true");
    }

    /**
     * A token at the start of the count query. The activity view keeps only the first kilobyte of a query, so a token
     * near its end would never match.
     */
    private static final String COUNT_QUERY = "generate_series(CAST(";

    /** How many sessions of this test database wait for a lock while running a statement that names this. */
    private long waitingOn(String statement) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                        + " AND wait_event_type = 'Lock' AND query LIKE ?",
                Long.class,
                "%" + statement + "%"));
    }

    /** Waits until a session of this test database waits for a lock while running a statement that names this. */
    private void awaitLockWait(String statement) {
        await().atMost(Duration.ofSeconds(30))
                .alias("a session waits for a lock in a statement with " + statement)
                .until(
                        () -> jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                                        + " AND wait_event_type = 'Lock' AND query LIKE ?",
                                Long.class,
                                "%" + statement + "%"),
                        waiting -> waiting != null && waiting > 0);
    }

    /** How many of these half-years are stored; a test asserts only on the half-years it works with. */
    private long storedAmong(UsagePeriod... periods) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM usage_release WHERE to_char(period_start, 'YYYY-MM') = ANY (?)",
                Long.class,
                (Object) Arrays.stream(periods).map(UsagePeriod::key).toArray(String[]::new)));
    }

    /** Turns the page on and runs the release as the hourly job does. */
    private List<UsagePeriod> releaseAt(Instant now) {
        turnOn();
        return service.releaseDue(now);
    }

    /** An admin's switch change, committed at once; like every settings change, it raises the row's version. */
    private void switchPublicUsage(boolean on) {
        jdbc.update("UPDATE instance_settings SET public_usage_allowed = ?, version = version + 1 WHERE id = 1", on);
    }

    /**
     * Holds a table that the count reads, so that a release stops in its count while it holds the release lock. The
     * lock lasts until the connection's transaction ends.
     */
    private static Connection holdFeedback(DataSource source) throws SQLException {
        Connection holder = source.getConnection();
        try (var lock = holder.createStatement()) {
            holder.setAutoCommit(false);
            lock.execute("LOCK TABLE feedback IN ACCESS EXCLUSIVE MODE");
            return holder;
        } catch (SQLException | RuntimeException failed) {
            holder.close();
            throw failed;
        }
    }

    @Test
    void shouldMakeASecondReplicaWaitForARunningReleaseAndThenCountNothing() throws Exception {
        tenBusyWorkspaces();
        turnOn();
        var due = PERIOD.releaseAt();
        var replicas = Executors.newFixedThreadPool(3);
        try (Connection firstHold = holdFeedback(dataSource)) {
            // The first replica took the release lock and now counts.
            var first = replicas.submit(() -> service.releaseDue(due));
            awaitLockWait(COUNT_QUERY);
            // The second replica waits for the release lock instead of counting the same period.
            var second = replicas.submit(() -> service.releaseDue(due));
            awaitLockWait("pg_advisory_xact_lock");

            // Once the first replica has counted, the table is held again: a count by the second would wait on it.
            var holding = new CountDownLatch(1);
            var letGo = new CountDownLatch(1);
            var secondHold = replicas.submit(() -> {
                try (Connection held = holdFeedback(dataSource)) {
                    holding.countDown();
                    try {
                        assertThat(letGo.await(60, TimeUnit.SECONDS)).isTrue();
                    } finally {
                        held.rollback();
                    }
                }
                return null;
            });
            try {
                awaitLockWait("LOCK TABLE feedback");
                firstHold.rollback();

                assertThat(first.get(60, TimeUnit.SECONDS)).containsExactly(PERIOD);
                assertThat(holding.await(60, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(30, TimeUnit.SECONDS))
                        .as("the second replica reads the stored period and counts nothing")
                        .isEmpty();
            } finally {
                letGo.countDown();
            }
            secondHold.get(60, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM usage_release WHERE period_start = ?", Long.class, PERIOD.start()))
                    .isOne();
        } finally {
            replicas.shutdownNow();
        }
    }

    @Test
    void shouldStoreNothingWhenThePageGoesOffAndOnAgainWhileTheReleaseCounts() throws Exception {
        tenBusyWorkspaces();
        turnOn();
        var release = Executors.newSingleThreadExecutor();
        try (Connection hold = holdFeedback(dataSource)) {
            var released = release.submit(() -> service.releaseDue(PERIOD.releaseAt()));
            // The release found the page on and now counts.
            awaitLockWait(COUNT_QUERY);

            // Meanwhile an admin turns the page off, and on again; each change commits at once.
            switchPublicUsage(false);
            switchPublicUsage(true);
            hold.rollback();

            assertThatThrownBy(() -> released.get(60, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(ReleaseDiscarded.class);
            assertThat(storedAmong(PERIOD)).isZero();
        } finally {
            release.shutdownNow();
        }

        // The next run counts again and stores the period.
        assertThat(releaseAt(PERIOD.releaseAt())).containsExactly(PERIOD);
    }

    @Test
    void shouldStoreNoPeriodOfARunThatFailsAfterItsFirstInsert() throws Exception {
        reviewed(data.scenario(), DURING);
        var later = PERIOD.next();
        try (Connection other = dataSource.getConnection()) {
            // Another transaction holds the second period's row: the run's second insert waits for it until the
            // lock timeout of the application's connections ends it.
            other.setAutoCommit(false);
            try (var hold = other.prepareStatement(
                    "INSERT INTO usage_release (period_start, period_end, computed_at, schema_version, figures)"
                            + " VALUES (?, ?, now(), 1, '{}'::jsonb)")) {
                hold.setObject(1, later.start());
                hold.setObject(2, later.end());
                hold.executeUpdate();
            }

            var run = Executors.newFixedThreadPool(2);
            try {
                turnOn();
                var released = run.submit(() -> service.releaseDue(later.releaseAt()));
                awaitLockWait("INSERT INTO usage_release");

                // While the run waits on its insert, the brake's row and the switch take writes at once: the run holds
                // no lock on the settings row before its final check.
                var writes = run.submit(() -> {
                    jdbc.update("UPDATE instance_settings SET silent_mode_reason = silent_mode_reason,"
                            + " version = version + 1 WHERE id = 1");
                    switchPublicUsage(false);
                    return null;
                });
                writes.get(8, TimeUnit.SECONDS);
                // The same run still waits on its insert: the writes did not wait for its lock timeout to end it.
                assertThat(waitingOn("INSERT INTO usage_release"))
                        .as("the release still waits on its second insert")
                        .isPositive();
                assertThat(released).isNotDone();

                assertThatThrownBy(() -> released.get(60, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                                .isInstanceOfSatisfying(
                                        SQLException.class,
                                        timeout -> assertThat(timeout.getSQLState())
                                                .as("lock_not_available")
                                                .isEqualTo("55P03")));
            } finally {
                other.rollback();
                run.shutdownNow();
            }
        }

        // The run is one transaction: the first period, inserted before the failure, is not stored either.
        assertThat(storedAmong(PERIOD, later)).isZero();
        assertThat(releaseAt(later.releaseAt())).containsExactly(PERIOD, later);
    }

    @Test
    void shouldCatchUpEveryPeriodThatBecameDueWhileNoReleaseRan() {
        tenBusyWorkspaces();
        turnOn();
        assertThat(service.releaseDue(PERIOD.releaseAt())).containsExactly(PERIOD);

        // The server was down for a year: the next run releases each missed half-year in order, and nothing twice.
        var later = PERIOD.next().next();
        assertThat(service.releaseDue(later.releaseAt())).containsExactly(PERIOD.next(), later);
        assertThat(figures(PERIOD.next()).status()).isEqualTo(PublicUsageStatus.NOT_ENOUGH_ACTIVITY);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM usage_release WHERE period_start BETWEEN ? AND ?",
                        Long.class,
                        PERIOD.start(),
                        later.start()))
                .isEqualTo(3L);
    }

    @Test
    void shouldRefuseAPeriodThatOverlapsAnother() {
        for (var start : List.of(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 4, 2), LocalDate.of(2025, 1, 1))) {
            assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO usage_release(period_start,period_end,computed_at,schema_version,figures)"
                                    + " VALUES (?, ?, now(), 1, '{\"status\":\"NOT_ENOUGH_ACTIVITY\",\"figures\":{}}')",
                            start,
                            start.plusMonths(6)))
                    .as(start.toString())
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO usage_release(period_start,period_end,computed_at,schema_version,figures)"
                                + " VALUES ('2025-04-01', '2026-04-01', now(), 1,"
                                + " '{\"status\":\"NOT_ENOUGH_ACTIVITY\",\"figures\":{}}')"))
                .as("a period lasts six months")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void shouldStoreNothingWithoutAPracticeReviewThatIsNotATrial() {
        var trialOnly = data.scenario();
        UUID trial = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,requested_by_id,status,ordered_at,"
                        + "created_at) VALUES (?,?,?,'ITEM','v1','TRIAL',?,'RUNNING',?,?)",
                trial,
                trialOnly.workspace().getId(),
                trialOnly.item().getId(),
                trialOnly.account().getId(),
                Timestamp.from(DURING),
                Timestamp.from(DURING));
        jdbc.update(
                "INSERT INTO review_practice(review_id,practice_id,workspace_id,status) VALUES (?,?,?,'READY')",
                trial,
                trialOnly.practice().getId(),
                trialOnly.workspace().getId());

        assertThat(releaseAt(PERIOD.next().releaseAt())).isEmpty();
        assertThat(storedAmong(PERIOD.previous(), PERIOD, PERIOD.next())).isZero();
    }

    @Test
    void shouldPutEachRecordInTheHalfYearOfItsOwnEventTimeInUtc() {
        var developer = data.scenario();
        // The review starts in summer 2025, its result is recorded in winter, and its feedback is delivered in 2026.
        var review = Instant.parse("2025-09-30T23:30:00Z");
        var recorded = Instant.parse("2025-10-01T00:30:00Z");
        var delivered = Instant.parse("2026-04-01T00:30:00Z");
        var result = data.result(developer, ResultOutcome.MET, review, recorded);
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,person_account_id,destination,state,body,created_at,"
                        + "delivered_at) VALUES (?,?,?,?,?,'ON_WORK','DELIVERED','Fixture feedback',?,?)",
                UUID.randomUUID(),
                developer.workspace().getId(),
                result.getReviewId(),
                developer.item().getId(),
                developer.account().getId(),
                Timestamp.from(recorded),
                Timestamp.from(delivered));

        // A session in another time zone must not move the boundary.
        var counts = Objects.requireNonNull(transactions.execute(status -> {
            jdbc.execute("SET LOCAL TIME ZONE 'Pacific/Auckland'");
            return releases.count(
                    PERIOD.start().atStartOfDay(), PERIOD.next().next().start().atStartOfDay());
        }));

        assertThat(counts)
                .filteredOn(count -> count.total() > 0)
                .extracting(UsageCount::period, UsageCount::figure)
                .containsExactlyInAnyOrder(
                        tuple("2025-04", "PRACTICE_REVIEWS"),
                        tuple("2025-04", "REVIEWED_WORK"),
                        tuple("2025-10", "REVIEWED_PEOPLE"),
                        tuple("2025-10", UsageCount.DISTINCT_PEOPLE),
                        tuple("2026-04", "FEEDBACK_DELIVERED"));
    }

    @Test
    void shouldStartTheFirstReleaseAtTheFirstPracticeReview() {
        reviewed(data.scenario(), DURING);
        var later = PERIOD.next();

        assertThat(releaseAt(later.releaseAt())).containsExactly(PERIOD, later);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM usage_release WHERE period_start < ?", Long.class, PERIOD.start()))
                .isZero();
        assertThat(figures(later).status()).isEqualTo(PublicUsageStatus.NOT_ENOUGH_ACTIVITY);
    }
}
