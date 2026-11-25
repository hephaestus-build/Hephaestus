package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository.DueEntry;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;

class ConversationRetentionSweeperTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-24T03:45:00Z");

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

    private ConversationRetentionSweeper sweeper;

    /** How many entries redaction erased, by workspace. */
    private final Map<Long, Integer> erased = new HashMap<>();

    /** How often redaction was asked to erase entries of a failing workspace. */
    private int failedAttempts;

    private record Due(UUID getId, long getWorkspaceId, long getAccountId, Instant getDueAt) implements DueEntry {}

    @BeforeEach
    void setUp() {
        sweeper = new ConversationRetentionSweeper(
                entries,
                redaction,
                accounts,
                ownerScope,
                transactions,
                new FeedbackRetentionProperties(Duration.ofDays(400)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldEraseEachWorkspaceUpToItsOwnBudgetWhenTheirBacklogsTogetherPassIt() {
        int over = ConversationRetentionSweeper.MAX_PER_RUN + 300;
        List<DueEntry> backlog = Stream.concat(
                        due(1, 11, over, Duration.ofDays(600)), due(2, 12, 700, Duration.ofDays(500)))
                .toList();
        serve(backlog, List.of());

        long handed = sweeper.sweepNow();

        assertThat(erased)
                .containsEntry(1L, ConversationRetentionSweeper.MAX_PER_RUN)
                .containsEntry(2L, 700);
        assertThat(handed).isEqualTo(ConversationRetentionSweeper.MAX_PER_RUN + 700L);
    }

    @Test
    void shouldSkipAnOwnerWhoEnteredErasureAfterDiscovery() {
        List<DueEntry> backlog = Stream.concat(due(1, 11, 1, Duration.ofDays(600)), due(2, 12, 1, Duration.ofDays(500)))
                .toList();
        serve(backlog, List.of());
        when(accounts.holdForRetention(11)).thenReturn(false);

        assertThat(sweeper.sweepNow()).isEqualTo(1);
        assertThat(erased).containsOnlyKeys(2L);
    }

    @Test
    void shouldPassOverAWorkspaceWhoseErasureFailsAndEraseTheOnesAfterIt() {
        List<DueEntry> failing = due(1, 11, ConversationRetentionSweeper.BATCH * 3, Duration.ofDays(600))
                .toList();
        List<DueEntry> healthy = due(2, 12, 10, Duration.ofDays(450)).toList();
        serve(Stream.concat(failing.stream(), healthy.stream()).toList(), failing);

        long handed = sweeper.sweepNow();

        assertThat(erased).doesNotContainKey(1L).containsEntry(2L, 10);
        assertThat(handed).isEqualTo(10);
        assertThat(failedAttempts)
                .as("attempts on the failing workspace in one run")
                .isEqualTo(1);
        verify(ownerScope).ofJob(12);
    }

    /** Entries of one person in one workspace, one second apart, the first {@code age} before now. */
    private static Stream<DueEntry> due(long workspaceId, long accountId, int count, Duration age) {
        Instant first = NOW.minus(age);
        return IntStream.range(0, count)
                .mapToObj(i -> new Due(UUID.randomUUID(), workspaceId, accountId, first.plusSeconds(i)));
    }

    /** Pages the backlog by (due time, id) after the cursor, as the owner function does; redaction fails on failing. */
    private void serve(List<DueEntry> backlog, List<DueEntry> failing) {
        List<DueEntry> sorted = new ArrayList<>(backlog);
        sorted.sort(Comparator.comparing(DueEntry::getDueAt).thenComparing(DueEntry::getId));
        when(accounts.holdForRetention(anyLong())).thenReturn(true);
        when(entries.findDueForRetention(any(), any(), any(), anyInt())).thenAnswer(call -> {
            Instant afterDue = call.getArgument(1);
            UUID afterId = call.getArgument(2);
            int limit = call.getArgument(3);
            return sorted.stream()
                    .filter(entry -> entry.getDueAt().isAfter(afterDue)
                            || (entry.getDueAt().equals(afterDue)
                                    && entry.getId().compareTo(afterId) > 0))
                    .limit(limit)
                    .toList();
        });
        Map<UUID, Long> workspaceOf =
                backlog.stream().collect(Collectors.toMap(DueEntry::getId, DueEntry::getWorkspaceId));
        Set<UUID> failingIds = failing.stream().map(DueEntry::getId).collect(Collectors.toSet());
        doAnswer(call -> {
                    Collection<UUID> ids = call.getArgument(0);
                    if (ids.stream().anyMatch(failingIds::contains)) {
                        failedAttempts++;
                        throw new IllegalStateException("lock timeout");
                    }
                    ids.forEach(id -> erased.merge(workspaceOf.get(id), 1, Integer::sum));
                    return null;
                })
                .when(redaction)
                .redact(any());
    }
}
