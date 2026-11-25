package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository.DueEntry;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
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

    /** The people whose erasure fails, as (workspace, account). */
    private final Set<List<Long>> failing = new HashSet<>();

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
                .findDueForRetention(anyLong(), any(), any(), any(), any(), anyInt());

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
                .findDueForRetention(anyLong(), any(), any(), any(), any(), anyInt());

        assertThatThrownBy(sweeper::sweepNow).isInstanceOf(IllegalStateException.class);
        assertThat(counter("failure")).isEqualTo(1);
    }

    @Test
    void shouldAskForTheEntriesOlderThanTheRetentionLessTwoWeeks() {
        sweeper.sweepNow();

        verify(entries)
                .findDueForRetention(Duration.ofDays(386).toSeconds(), null, null, new Long[0], new Long[0], BATCH);
    }

    @Test
    void shouldPublishHowLongTheOldestDueEntryHasLeftByTheDatabaseClockAndWhenItWasRead() {
        now = NOW.plusSeconds(30);
        sweeper.sampleDeadlineMargin();
        assertThat(margin()).as("no entry is due").isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(lastRead()).as("an empty read is a read").isEqualTo((double)
                NOW.plusSeconds(30).getEpochSecond());

        add(due(1, 11, 3, 0));
        now = NOW.plusSeconds(90);
        sweeper.sampleDeadlineMargin();
        double left =
                Duration.between(READ_AT, WRITTEN.plus(Duration.ofDays(400))).toSeconds();
        assertThat(margin()).as("by the database clock of the read").isEqualTo(left);
        assertThat(lastRead()).isEqualTo((double) NOW.plusSeconds(90).getEpochSecond());

        doThrow(new IllegalStateException("database down"))
                .when(entries)
                .findDueForRetention(anyLong(), any(), any(), any(), any(), anyInt());
        now = NOW.plusSeconds(150);
        sweeper.sampleDeadlineMargin();

        assertThat(margin()).as("a failed read keeps the last margin").isEqualTo(left);
        assertThat(lastRead())
                .as("and the time of the last good read, which tells the alert how old it is")
                .isEqualTo((double) NOW.plusSeconds(90).getEpochSecond());
        assertThat(meters.get("conversation.retention.sampler.failures")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    private double lastRead() {
        return meters.get("conversation.retention.margin.read.timestamp")
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
        return meters.get("conversation.retention.deadline.margin").gauge().value();
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

    /**
     * Pages the due entries by (due time, id) after the cursor and without the passed-over people, as the owner
     * function does; redaction erases an entry, so it is no longer due, and fails for a failing person.
     */
    private void serve() {
        lenient().when(accounts.holdForRetention(anyLong())).thenReturn(true);
        // Lenient: a test that reads no due entry never calls redaction.
        lenient()
                .when(entries.findDueForRetention(anyLong(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(call -> {
                    pagesRead++;
                    @Nullable Instant afterDue = call.getArgument(1);
                    @Nullable UUID afterId = call.getArgument(2);
                    Long[] skipWorkspaces = call.getArgument(3);
                    Long[] skipAccounts = call.getArgument(4);
                    int limit = call.getArgument(5);
                    Set<List<Long>> skip = new HashSet<>();
                    for (int i = 0; i < skipWorkspaces.length; i++) {
                        skip.add(List.of(skipWorkspaces[i], skipAccounts[i]));
                    }
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
        lenient()
                .doAnswer(call -> {
                    Collection<UUID> ids = call.getArgument(0);
                    List<DueEntry> batch = due.stream()
                            .filter(entry -> ids.contains(entry.getId()))
                            .toList();
                    if (batch.stream()
                            .anyMatch(
                                    entry -> failing.contains(List.of(entry.getWorkspaceId(), entry.getAccountId())))) {
                        failedAttempts++;
                        now = now.plus(perFailure);
                        throw new IllegalStateException("lock timeout");
                    }
                    batch.forEach(entry -> erased.merge(entry.getAccountId(), 1, Integer::sum));
                    now = now.plus(perRedaction);
                    due.removeAll(batch);
                    return null;
                })
                .when(redaction)
                .redact(any());
    }
}
