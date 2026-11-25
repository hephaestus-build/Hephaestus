package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository.DueEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository.DuePage;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.CopyingSourceRetention;
import de.tum.cit.aet.hephaestus.core.privacy.spi.DueWorkKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.PlatformTransactionManager;

class ConversationRetentionSweeperTest extends BaseUnitTest {

    private static final Instant WRITTEN = Instant.parse("2025-10-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    /** The database clock when the due entries were read, apart from the application clock. */
    private static final Instant READ_AT = NOW.plusSeconds(1000);

    private static final int BATCH = 10;
    private static final int PAGES = 3;

    @Mock
    private ConversationEntryRepository entries;

    @Mock
    private ConversationRedaction redaction;

    @Mock
    private AccountDirectory accounts;

    @Mock
    private OwnerScope ownerScope;

    @Mock
    private PlatformTransactionManager transactions;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ConversationRetentionSweeper sweeper;

    /** The due entries not erased yet, oldest first, as the owner function sees them. */
    private final List<DueEntry> due = new ArrayList<>();

    /** The sources whose words a tool result can copy, with their retention by workspace. */
    private final List<CopyingSourceRetention> copyingSources = new ArrayList<>();

    /** The people whose erasure fails, as (workspace, account). */
    private final Set<List<Long>> failing = new HashSet<>();

    /** The workspaces whose due copies cannot be read. */
    private final Set<Long> unreadable = new HashSet<>();

    /** How many entries redaction erased, by account. */
    private final Map<Long, Integer> erased = new HashMap<>();

    /** How often redaction was asked to erase entries of a failing person. */
    private int failedAttempts;

    /** How many pages each run read. */
    private int pagesRead;

    /** The time a test clock reads; each erasure takes {@link #perRedaction}. */
    private Instant now = NOW;

    private Duration perRedaction = Duration.ZERO;

    private Duration perFailure = Duration.ZERO;

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private record Due(UUID getId, long getWorkspaceId, long getAccountId, Instant getDueAt, Instant getReadAt)
            implements DueEntry {}

    @BeforeEach
    void setUp() {
        sweeper = new ConversationRetentionSweeper(
                entries,
                redaction,
                accounts,
                ownerScope,
                transactions,
                new FeedbackRetentionProperties(Duration.ofDays(400)),
                () -> copyingSources,
                new PrivacyJobMetrics(meters),
                meters,
                clock,
                BATCH,
                PAGES);
        serve();
    }

    @Test
    void shouldReadAtMostItsPageBudgetInARunAndLeaveTheRestToTheNextRuns() {
        add(due(1, 11, 40, 0));
        add(due(1, 12, 25, 1));
        add(due(2, 13, 10, 2));

        List<ConversationRetentionSweeper.Run> runs = new ArrayList<>();
        do {
            pagesRead = 0;
            runs.add(sweeper.sweepNow());
            assertThat(pagesRead).as("pages one run reads").isLessThanOrEqualTo(PAGES);
        } while (!runs.getLast().drained() && runs.size() < 10);

        assertThat(runs).extracting(ConversationRetentionSweeper.Run::erased).containsExactly(30L, 30L, 15L);
        assertThat(due).isEmpty();
        assertThat(erased).containsEntry(11L, 40).containsEntry(12L, 25).containsEntry(13L, 10);
        assertThat(counter("success")).isEqualTo(3);
    }

    @Test
    void shouldPassOverAPersonWhoseErasureFailsAndEraseTheOtherPeopleOfTheWorkspace() {
        add(due(1, 11, BATCH * PAGES * 2, 0));
        add(due(1, 12, 15, 1));
        failing.add(List.of(1L, 11L));

        ConversationRetentionSweeper.Run run = sweeper.sweepNow();

        assertThat(erased).doesNotContainKey(11L).containsEntry(12L, 15);
        assertThat(run.failedOwners()).isEqualTo(1);
        assertThat(run.drained())
                .as("the failing person's entries do not use up the page budget")
                .isTrue();
        assertThat(failedAttempts)
                .as("attempts on the failing person in one run")
                .isEqualTo(1);
        verify(ownerScope, times(2)).ofJob(12); // the colleague's 15 entries fill two pages
        assertThat(counter("incomplete")).isEqualTo(1);

        failing.clear();
        for (int runs = 0; runs < 10 && !due.isEmpty(); runs++) {
            sweeper.sweepNow();
        }

        assertThat(erased).containsEntry(11L, BATCH * PAGES * 2).containsEntry(12L, 15);
        assertThat(due).isEmpty();
    }

    @Test
    void shouldStartNoErasureAfterTheRunBudgetAndLeaveTheRestToTheNextRun() {
        for (long account = 1; account <= 6; account++) {
            add(due(1, account, 1, (int) account));
        }
        perRedaction = Duration.ofMinutes(3);

        ConversationRetentionSweeper.Run first = sweeper.sweepNow();

        assertThat(first.erased())
                .as("erasures that start at 0, 3, 6 and 9 minutes of a 10-minute budget")
                .isEqualTo(4);
        assertThat(first.drained()).isFalse();

        ConversationRetentionSweeper.Run second = sweeper.sweepNow();

        assertThat(second.erased()).isEqualTo(2);
        assertThat(second.drained()).isTrue();
        assertThat(due).isEmpty();
    }

    @Test
    void shouldCountTheCommittedErasuresWhenALaterPageCannotBeRead() {
        add(due(1, 11, BATCH, 0));
        doReturn(List.copyOf(due))
                .doThrow(new IllegalStateException("database down"))
                .when(entries)
                .findDueForRetention(anyLong(), any(), any(), any(), anyInt());

        assertThatThrownBy(sweeper::sweepNow).isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("privacy.job.affected")
                        .tag("job", "conversation_retention")
                        .counter()
                        .count())
                .isEqualTo(BATCH);
        assertThat(counter("failure")).isEqualTo(1);
    }

    @Test
    void shouldPassOverAPersonWhoEnteredErasureAfterTheirEntriesWereFound() {
        add(due(1, 11, 1, 0));
        add(due(2, 12, 1, 1));
        when(accounts.holdForRetention(11)).thenReturn(false);

        ConversationRetentionSweeper.Run run = sweeper.sweepNow();

        assertThat(erased).containsOnlyKeys(12L);
        assertThat(run.erased()).isEqualTo(1);
        assertThat(run.failedOwners())
                .as("erasure removes those entries itself")
                .isZero();
    }

    @Test
    void shouldRecordAFailureWhenTheDueWorkCannotBeRead() {
        doThrow(new IllegalStateException("database down"))
                .when(entries)
                .findDueForRetention(anyLong(), any(), any(), any(), anyInt());

        assertThatThrownBy(sweeper::sweepNow).isInstanceOf(IllegalStateException.class);
        assertThat(counter("failure")).isEqualTo(1);
    }

    @Test
    void shouldAskForTheEntriesOlderThanTheRetentionLessTwoWeeks() {
        sweeper.sweepNow();

        verify(entries)
                .findDueForRetention(
                        Duration.ofDays(386).toSeconds(),
                        DueWorkKind.ENTRY_RETENTION,
                        null,
                        new DuePage(null, null, List.of()),
                        BATCH);
    }

    @Test
    void shouldPublishHowLongTheOldestDueEntryHasLeftByTheDatabaseClockAndWhenItWasRead() {
        now = NOW.plusSeconds(30);
        sweeper.sampleDeadlineMargin();
        assertThat(margin()).as("no entry is due").isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(lastRead()).as("an empty read is a read").isEqualTo((double)
                NOW.plusSeconds(30).getEpochSecond());
        verify(entries)
                .findDueForRetention(
                        Duration.ofDays(386).toSeconds(),
                        DueWorkKind.ENTRY_RETENTION_DEADLINE,
                        null,
                        new DuePage(null, null, List.of()),
                        1);

        add(due(1, 11, 3, 0));
        now = NOW.plusSeconds(90);
        sweeper.sampleDeadlineMargin();
        double left =
                Duration.between(READ_AT, WRITTEN.plus(Duration.ofDays(400))).toSeconds();
        assertThat(margin()).as("by the database clock of the read").isEqualTo(left);
        assertThat(lastRead()).isEqualTo((double) NOW.plusSeconds(90).getEpochSecond());

        doThrow(new IllegalStateException("database down"))
                .when(entries)
                .findDueForRetention(anyLong(), any(), any(), any(), anyInt());
        now = NOW.plusSeconds(150);
        sweeper.sampleDeadlineMargin();

        assertThat(margin()).as("a failed read keeps the last margin").isEqualTo(left);
        assertThat(lastRead())
                .as("and the time of the last good read, which tells the alert how old it is")
                .isEqualTo((double) NOW.plusSeconds(90).getEpochSecond());
        assertThat(meters.get("conversation.retention.sampler.failures")
                        .tag("rule", "entries")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    private double lastRead() {
        return meters.get("conversation.retention.margin.read.timestamp")
                .tag("rule", "entries")
                .gauge()
                .value();
    }

    /**
     * Four people whose conversations stay in use come first and spend a run's whole budget. The next run tries them
     * after everyone else, so the person behind them is erased, and they are still retried.
     */
    @Test
    void shouldTryThePeopleWhoFailedInTheLastRunAfterEveryoneElse() {
        for (long account = 1; account <= 4; account++) {
            add(due(1, account, 1, (int) account));
        }
        add(due(1, 9, 1, 9));
        for (long account = 1; account <= 4; account++) {
            failing.add(List.of(1L, account));
        }
        perFailure = Duration.ofMinutes(3);

        ConversationRetentionSweeper.Run first = sweeper.sweepNow();

        assertThat(first.failedOwners()).isEqualTo(4);
        assertThat(erased).as("the budget ended before the last person").doesNotContainKey(9L);
        assertThat(failedAttempts).isEqualTo(4);

        ConversationRetentionSweeper.Run second = sweeper.sweepNow();

        assertThat(erased)
                .as("the person behind them goes first in the next run")
                .containsEntry(9L, 1);
        assertThat(failedAttempts)
                .as("the people who failed are tried again after that person")
                .isGreaterThan(4);
        assertThat(second.failedOwners()).isPositive();
    }

    /**
     * A backlog larger than a run keeps a run from getting back to the person who failed. That person stays behind
     * everyone else in the runs after it, so the others' backlog keeps the full budget.
     */
    @Test
    void shouldKeepThePeopleWhoFailedBehindEveryoneElseUntilARunGetsBackToThem() {
        add(due(1, 1, 1, 0));
        failing.add(List.of(1L, 1L));
        sweeper.sweepNow();
        assertThat(failedAttempts).isEqualTo(1);

        add(due(1, 2, BATCH * PAGES * 2, 1));
        sweeper.sweepNow();
        sweeper.sweepNow();

        assertThat(failedAttempts)
                .as("no run got back to the person who failed while the others' backlog filled every run")
                .isEqualTo(1);
        assertThat(erased).containsEntry(2L, BATCH * PAGES * 2);

        sweeper.sweepNow();

        assertThat(failedAttempts)
                .as("the run after the backlog gets back to that person")
                .isEqualTo(2);
    }

    private double margin() {
        return meters.get("conversation.retention.deadline.margin")
                .tag("rule", "entries")
                .gauge()
                .value();
    }

    /**
     * Two workspaces' copies written at the same time: the page takes the one that PostgreSQL orders first. Java orders
     * a {@code uuid} as two signed numbers, PostgreSQL as unsigned bytes, so a cursor in Java's order would skip a copy.
     */
    @Test
    void shouldMergeTheCopiesOfSeveralWorkspacesInTheOrderOfTheDatabase() {
        copyingSources.add(() -> Map.of(1L, Duration.ofDays(30), 2L, Duration.ofDays(30)));
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        due.add(new Due(high, 1, 11, WRITTEN, READ_AT));
        due.add(new Due(low, 2, 12, WRITTEN, READ_AT));
        ConversationRetentionSweeper onePage = onePageSweeper();

        onePage.sweepCopiesNow();

        assertThat(erased).as("the copy that PostgreSQL orders first").containsOnlyKeys(12L);
        onePage.sweepCopiesNow();
        assertThat(erased).containsOnlyKeys(11L, 12L);
    }

    /**
     * Copies merge by when they reach their workspace's limit, not by when they were written: a younger copy with a
     * shorter limit has less time left and comes first.
     */
    @Test
    void shouldEraseFirstTheCopyWithTheLeastTimeLeftWhateverItsWorkspacesLimit() {
        copyingSources.add(() -> Map.of(1L, Duration.ofDays(60), 2L, Duration.ofDays(20)));
        due.add(new Due(UUID.randomUUID(), 1, 11, WRITTEN, READ_AT));
        due.add(new Due(UUID.randomUUID(), 2, 12, WRITTEN.plus(Duration.ofDays(10)), READ_AT));
        ConversationRetentionSweeper onePage = onePageSweeper();

        onePage.sweepCopiesNow();

        assertThat(erased)
                .as("the copy with 30 days to its limit before the one with 60")
                .containsOnlyKeys(12L);
        onePage.sweepCopiesNow();
        assertThat(erased).containsOnlyKeys(11L, 12L);
    }

    /** A workspace whose copies cannot be read, for example past a statement timeout, holds back no other workspace. */
    @Test
    void shouldPassOverAWorkspaceWhoseCopiesCannotBeReadAndEraseTheOthers() {
        copyingSources.add(() -> Map.of(1L, Duration.ofDays(30), 2L, Duration.ofDays(30)));
        due.add(new Due(UUID.randomUUID(), 1, 11, WRITTEN, READ_AT));
        due.add(new Due(UUID.randomUUID(), 2, 12, WRITTEN.plusSeconds(60), READ_AT));
        unreadable.add(1L);

        ConversationRetentionSweeper.Run run = sweeper.sweepCopiesNow();

        assertThat(erased).containsOnlyKeys(12L);
        assertThat(run.failedWorkspaces()).isEqualTo(1);
        assertThat(meters.get("privacy.job.completed")
                        .tag("job", "conversation_copy_retention")
                        .tag("outcome", "incomplete")
                        .counter()
                        .count())
                .isEqualTo(1);

        unreadable.clear();
        assertThat(sweeper.sweepCopiesNow().failedWorkspaces())
                .as("the next run reads it again")
                .isZero();
        assertThat(erased).containsOnlyKeys(11L, 12L);
    }

    /**
     * The copy margin of the readable workspaces is published while one workspace's read fails, which counts as a
     * failed read. When no workspace can be read, the margin keeps its last read: {@code +Inf} would claim that no copy
     * is due.
     */
    @Test
    void shouldPublishTheCopyMarginOfTheReadableWorkspacesAndCountTheOthersAsFailedReads() {
        copyingSources.add(() -> Map.of(1L, Duration.ofDays(20), 2L, Duration.ofDays(30)));
        due.add(new Due(UUID.randomUUID(), 1, 11, WRITTEN, READ_AT));
        due.add(new Due(UUID.randomUUID(), 2, 12, WRITTEN, READ_AT));
        unreadable.add(1L);

        sweeper.sampleDeadlineMargin();

        double readable =
                Duration.between(READ_AT, WRITTEN.plus(Duration.ofDays(30))).toSeconds();
        assertThat(copiesGauge("conversation.retention.deadline.margin")).isEqualTo(readable);
        assertThat(copySamplerFailures()).isEqualTo(1);

        unreadable.add(2L);
        now = now.plusSeconds(60);
        sweeper.sampleDeadlineMargin();

        assertThat(copiesGauge("conversation.retention.deadline.margin"))
                .as("the last read, not +Inf")
                .isEqualTo(readable);
        assertThat(copiesGauge("conversation.retention.margin.read.timestamp"))
                .as("the time of the last good read")
                .isEqualTo((double) now.minusSeconds(60).getEpochSecond());
        assertThat(copySamplerFailures()).isEqualTo(2);
    }

    private double copiesGauge(String name) {
        return meters.get(name).tag("rule", "copies").gauge().value();
    }

    private double copySamplerFailures() {
        return meters.get("conversation.retention.sampler.failures")
                .tag("rule", "copies")
                .counter()
                .count();
    }

    /**
     * A workspace's copies go at the shortest retention of a source that it has, and the copy margin is the least of all
     * workspaces. Entries without a copying source keep the conversation retention.
     */
    @Test
    void shouldUseTheShortestRetentionOfAWorkspacesSourcesForItsCopies() {
        copyingSources.add(() -> Map.of(1L, Duration.ofDays(30), 2L, Duration.ofDays(60)));
        copyingSources.add(() -> Map.of(1L, Duration.ofDays(20)));
        due.add(new Due(UUID.randomUUID(), 1, 11, WRITTEN, READ_AT));
        due.add(new Due(UUID.randomUUID(), 2, 12, WRITTEN, READ_AT));

        sweeper.sampleDeadlineMargin();

        verify(entries, atLeastOnce())
                .findDueForRetention(
                        eq(Duration.ofDays(6).toSeconds()),
                        eq(DueWorkKind.COPY_RETENTION_DEADLINE),
                        eq(1L),
                        any(),
                        eq(1));
        verify(entries, atLeastOnce())
                .findDueForRetention(
                        eq(Duration.ofDays(46).toSeconds()),
                        eq(DueWorkKind.COPY_RETENTION_DEADLINE),
                        eq(2L),
                        any(),
                        eq(1));
        assertThat(meters.get("conversation.retention.deadline.margin")
                        .tag("rule", "copies")
                        .gauge()
                        .value())
                .as("the workspace with the shortest retention")
                .isEqualTo((double) Duration.between(READ_AT, WRITTEN.plus(Duration.ofDays(20)))
                        .toSeconds());

        sweeper.sweepCopiesNow();

        assertThat(erased).containsOnlyKeys(11L, 12L);
        assertThat(meters.get("privacy.job.completed")
                        .tag("job", "conversation_copy_retention")
                        .tag("outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    /**
     * The copy margin is read every minute, and a source answers by scanning its table: the margin reuses a read for a
     * run's interval. A run reads the sources afresh, so a new source counts from the next run.
     */
    @Test
    void shouldReadTheCopyingSourcesForTheMarginOnlyOnceARunsInterval() {
        AtomicInteger reads = new AtomicInteger();
        copyingSources.add(() -> {
            reads.incrementAndGet();
            return Map.of(1L, Duration.ofDays(30));
        });

        sweeper.sampleDeadlineMargin();
        now = now.plus(ConversationRetentionSweeper.COPY_LIMITS_FOR).minusSeconds(1);
        sweeper.sampleDeadlineMargin();
        assertThat(reads).hasValue(1);

        now = now.plusSeconds(1);
        sweeper.sampleDeadlineMargin();
        assertThat(reads).hasValue(2);

        sweeper.sweepCopiesNow();
        assertThat(reads).as("a run reads afresh").hasValue(3);
        sweeper.sampleDeadlineMargin();
        assertThat(reads).as("and the margin reuses that read").hasValue(3);
    }

    @Test
    void shouldFindNoDueCopyWithoutACopyingSource() {
        due.add(new Due(UUID.randomUUID(), 1, 11, WRITTEN, READ_AT));

        ConversationRetentionSweeper.Run run = sweeper.sweepCopiesNow();
        sweeper.sampleDeadlineMargin();

        assertThat(run.drained()).isTrue();
        assertThat(erased).isEmpty();
        assertThat(meters.get("conversation.retention.deadline.margin")
                        .tag("rule", "copies")
                        .gauge()
                        .value())
                .isEqualTo(Double.POSITIVE_INFINITY);
    }

    /** A sweeper that reads one page of one entry a run. */
    private ConversationRetentionSweeper onePageSweeper() {
        return new ConversationRetentionSweeper(
                entries,
                redaction,
                accounts,
                ownerScope,
                transactions,
                new FeedbackRetentionProperties(Duration.ofDays(400)),
                () -> copyingSources,
                new PrivacyJobMetrics(new SimpleMeterRegistry()),
                new SimpleMeterRegistry(),
                clock,
                1,
                1);
    }

    /** PostgreSQL's order of {@code uuid}: its bytes, unsigned. */
    private static int postgresOrder(UUID a, @Nullable UUID b) {
        UUID other = Objects.requireNonNull(b);
        int high = Long.compareUnsigned(a.getMostSignificantBits(), other.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), other.getLeastSignificantBits());
    }

    /** Entries of one person in one workspace, one second apart, the first {@code offset} hours after the others. */
    private static Stream<DueEntry> due(long workspaceId, long accountId, int count, int offset) {
        Instant first = WRITTEN.plus(Duration.ofHours(offset));
        return IntStream.range(0, count)
                .mapToObj(i -> new Due(UUID.randomUUID(), workspaceId, accountId, first.plusSeconds(i * 60L), READ_AT));
    }

    private void add(Stream<DueEntry> entries) {
        entries.forEach(due::add);
        due.sort(Comparator.comparing(DueEntry::getDueAt).thenComparing(DueEntry::getId));
    }

    private double counter(String outcome) {
        return meters.get("privacy.job.completed")
                .tag("job", "conversation_retention")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    /** One workspace's due copies after the cursor, in PostgreSQL's order, as the owner function pages them. */
    private List<DueEntry> copies(long workspaceId, DuePage page, int limit) {
        return due.stream()
                .filter(entry -> entry.getWorkspaceId() == workspaceId)
                .filter(entry -> page.afterDue() == null
                        || entry.getDueAt().isAfter(page.afterDue())
                        || (entry.getDueAt().equals(page.afterDue())
                                && postgresOrder(entry.getId(), page.afterId()) > 0))
                .filter(entry -> page.skip().stream()
                        .noneMatch(owner -> owner.workspaceId() == entry.getWorkspaceId()
                                && owner.accountId() == entry.getAccountId()))
                .sorted(Comparator.comparing(DueEntry::getDueAt)
                        .thenComparing(DueEntry::getId, ConversationRetentionSweeperTest::postgresOrder))
                .limit(limit)
                .toList();
    }

    /**
     * Pages the due entries by (due time, id) after the cursor and without the passed-over people, as the owner
     * function does; redaction erases an entry, so it is no longer due, and fails for a failing person.
     */
    private void serve() {
        lenient().when(accounts.holdForRetention(anyLong())).thenReturn(true);
        // Lenient: a test that reads no due entry never calls redaction.
        lenient()
                .when(entries.findDueForRetention(anyLong(), any(), any(), any(), anyInt()))
                .thenAnswer(call -> {
                    DueWorkKind kind = call.getArgument(1);
                    if (kind == DueWorkKind.COPY_RETENTION || kind == DueWorkKind.COPY_RETENTION_DEADLINE) {
                        if (unreadable.contains(call.<Long>getArgument(2))) {
                            throw new IllegalStateException("statement timeout");
                        }
                        return copies(call.getArgument(2), call.getArgument(3), call.getArgument(4));
                    }
                    pagesRead++;
                    DuePage page = call.getArgument(3);
                    @Nullable Instant afterDue = page.afterDue();
                    @Nullable UUID afterId = page.afterId();
                    int limit = call.getArgument(4);
                    Set<List<Long>> skip = new HashSet<>();
                    page.skip().forEach(owner -> skip.add(List.of(owner.workspaceId(), owner.accountId())));
                    return due.stream()
                            .filter(entry -> afterDue == null
                                    || afterId == null
                                    || entry.getDueAt().isAfter(afterDue)
                                    || (entry.getDueAt().equals(afterDue)
                                            && entry.getId().compareTo(afterId) > 0))
                            .filter(entry -> !skip.contains(List.of(entry.getWorkspaceId(), entry.getAccountId())))
                            .limit(limit)
                            .toList();
                });
        // Message retention removes through removeDue, copy retention through redact: the ids are one argument of each.
        IntFunction<Answer<Void>> erase = idsAt -> call -> {
            Collection<UUID> ids = call.getArgument(idsAt);
            List<DueEntry> batch =
                    due.stream().filter(entry -> ids.contains(entry.getId())).toList();
            if (batch.stream()
                    .anyMatch(entry -> failing.contains(List.of(entry.getWorkspaceId(), entry.getAccountId())))) {
                failedAttempts++;
                now = now.plus(perFailure);
                throw new IllegalStateException("lock timeout");
            }
            batch.forEach(entry -> erased.merge(entry.getAccountId(), 1, Integer::sum));
            now = now.plus(perRedaction);
            due.removeAll(batch);
            return null;
        };
        lenient().doAnswer(erase.apply(2)).when(redaction).removeDue(anyLong(), anyLong(), any());
        lenient().doAnswer(erase.apply(0)).when(redaction).redact(any());
    }
}
