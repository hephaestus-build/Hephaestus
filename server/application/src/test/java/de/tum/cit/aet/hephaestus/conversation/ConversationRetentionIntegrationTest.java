package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackRetentionSweeper;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Both retention limits of a conversation on real rows: every entry goes after the conversation retention, and a tool
 * result that could have copied a channel message goes with the Slack retention.
 */
class ConversationRetentionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConversationHistoryRemoval historyRemoval;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationRetentionSweeper conversationRetention;

    @Autowired
    private SlackRetentionSweeper slackRetention;

    @Autowired
    private SlackWorkspaceContentEraser slackContent;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationTurnRepository turnRows;

    @Autowired
    private TurnRuns turnRuns;

    @Autowired
    private ErasedDigestTurns digestTurns;

    @Autowired
    private PersonDataCopyFence copies;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OwnerScope ownerScope;

    @Autowired
    private AccountDirectory accounts;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private FeedbackRetentionProperties retention;

    @Autowired
    private PrivacyJobMetrics metrics;

    /** How often a committed redaction reported each entry as erased. */
    private final Map<UUID, Integer> reported = new ConcurrentHashMap<>();

    private Workspace workspace;
    private Conversation main;
    private Conversation colleagueMain;
    private Workspace otherWorkspace;
    private Conversation otherMain;
    private long seq;

    @BeforeEach
    void setUpConversations() {
        User developer = persistUser("ask-retention-developer");
        workspace = createWorkspace("ask-retention", "Hephaestus", "ask-retention-org", AccountType.ORG, developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId(developer));
        User colleague = persistUser("ask-retention-colleague");
        ensureMembership(workspace, colleague, Membership.WorkspaceRole.MEMBER);
        colleagueMain = conversations.findOrCreateMain(workspace.getId(), accountId(colleague));
        User other = persistUser("ask-retention-other");
        otherWorkspace =
                createWorkspace("ask-retention-other", "Other", "ask-retention-other-org", AccountType.ORG, other);
        otherMain = conversations.findOrCreateMain(otherWorkspace.getId(), accountId(other));
    }

    private void connectSlack(Workspace connected) {
        connectSlack(connected, IntegrationState.ACTIVE);
    }

    private void connectSlack(Workspace connected, IntegrationState state) {
        Connection connection = connections.saveAndFlush(new Connection(
                connected,
                IntegrationKind.SLACK,
                "T-" + connected.getId(),
                new ConnectionConfig.SlackConfig("T-" + connected.getId(), null, null, Set.of())));
        connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "T-" + connected.getId(),
                        UUID.randomUUID().toString(),
                        "Slack connected"));
        if (state != IntegrationState.ACTIVE) {
            connectionService.transition(
                    connections.findById(connection.getId()).orElseThrow(),
                    new ConnectionService.TransitionRequest(
                            state,
                            "TEST_SUSPENDED",
                            "SYSTEM",
                            "T-" + connected.getId(),
                            UUID.randomUUID().toString(),
                            "Slack suspended"));
        }
    }

    /** A summary of the conversation over its entries so far, written now. */
    private UUID summary(Conversation conversation) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                          payload, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUMMARY', 'APP', 'words-summary',
                        jsonb_build_object('type', 'compaction', 'id', 'pi-' || ?, 'summary', 'words-summary',
                            'fromSeq', 1, 'toSeq', ?),
                        now())
                """,
                id,
                conversation.getWorkspaceId(),
                conversation.getId(),
                conversation.getAccountId(),
                seq + 1,
                id.toString(),
                seq);
        seq++;
        return id;
    }

    /** A summary of the conversation over its entries {@code fromSeq..toSeq}, written now. */
    private UUID summaryOver(Conversation conversation, long fromSeq, long toSeq) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                          payload, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUMMARY', 'APP', 'words-summary',
                        jsonb_build_object('type', 'compaction', 'id', 'pi-' || ?, 'summary', 'words-summary',
                            'fromSeq', ?, 'toSeq', ?),
                        now())
                """,
                id,
                conversation.getWorkspaceId(),
                conversation.getId(),
                conversation.getAccountId(),
                ++seq,
                id.toString(),
                fromSeq,
                toSeq);
        return id;
    }

    /** One entry of the conversation, written {@code age} ago; a tool result names the tool that produced it. */
    private UUID entry(Conversation conversation, String kind, String tool, Duration age, String words) {
        return entry(conversation, kind, tool, Instant.now().minus(age), words);
    }

    private UUID entry(Conversation conversation, String kind, String tool, Instant written, String words) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                          payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'APP', ?,
                        jsonb_build_object('type', 'message', 'id', 'pi-' || ?, 'message',
                            jsonb_build_object('role', CASE WHEN ? = 'TOOL_RESULT' THEN 'toolResult' ELSE 'user' END,
                                'toolName', ?, 'toolCallId', 'call-' || ?,
                                'content', jsonb_build_array(jsonb_build_object('type', 'text', 'text', ?)))),
                        ?)
                """,
                id,
                conversation.getWorkspaceId(),
                conversation.getId(),
                conversation.getAccountId(),
                ++seq,
                kind,
                kind.equals("TOOL_RESULT") ? "" : words,
                id.toString(),
                kind,
                tool,
                id.toString(),
                words,
                Timestamp.from(written));
        return id;
    }

    /**
     * {@code count} questions of the conversation, a second apart after {@code first}.
     */
    private List<UUID> backlog(Conversation conversation, int count, Instant first) {
        List<UUID> ids = jdbc
                .queryForList(
                        """
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                          payload, created_at)
                SELECT gen_random_uuid(), ?, ?, ?, ? + s, 'USER', 'APP', 'words-backlog',
                       jsonb_build_object('type', 'message', 'id', 'pi-' || gen_random_uuid(), 'message',
                           jsonb_build_object('role', 'user', 'content', jsonb_build_array(
                               jsonb_build_object('type', 'text', 'text', 'words-backlog')))),
                       CAST(? AS timestamptz) + s * interval '1 second'
                FROM generate_series(1, ?) s
                RETURNING id
                """,
                        UUID.class,
                        conversation.getWorkspaceId(),
                        conversation.getId(),
                        conversation.getAccountId(),
                        seq,
                        Timestamp.from(first),
                        count)
                .stream()
                .map(Objects::requireNonNull)
                .toList();
        seq += count;
        return ids;
    }

    /** {@code count} tool results of the conversation that read the context, a second apart after {@code first}. */
    private List<UUID> copyBacklog(Conversation conversation, int count, Instant first) {
        List<UUID> ids = jdbc
                .queryForList(
                        """
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                          payload, created_at)
                SELECT gen_random_uuid(), ?, ?, ?, ? + s, 'TOOL_RESULT', 'APP', '',
                       jsonb_build_object('type', 'message', 'id', 'pi-' || gen_random_uuid(), 'message',
                           jsonb_build_object('role', 'toolResult', 'toolName', 'read', 'toolCallId', 'call-' || s,
                               'content', jsonb_build_array(
                                   jsonb_build_object('type', 'text', 'text', 'words-channel-message')))),
                       CAST(? AS timestamptz) + s * interval '1 second'
                FROM generate_series(1, ?) s
                RETURNING id
                """,
                        UUID.class,
                        conversation.getWorkspaceId(),
                        conversation.getId(),
                        conversation.getAccountId(),
                        seq,
                        Timestamp.from(first),
                        count)
                .stream()
                .map(Objects::requireNonNull)
                .toList();
        seq += count;
        return ids;
    }

    private long unerased(Collection<UUID> ids) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_entry WHERE id = ANY(?) AND redacted_at IS NULL",
                Long.class,
                (Object) ids.toArray(UUID[]::new)));
    }

    private List<@Nullable Timestamp> erasedAt(Collection<UUID> ids) {
        return jdbc.queryForList(
                "SELECT redacted_at FROM conversation_entry WHERE id = ANY(?) ORDER BY id", Timestamp.class, (Object)
                        ids.toArray(UUID[]::new));
    }

    /** A sweeper with its own page size and page budget, whose redaction reports each committed erasure. */
    private ConversationRetentionSweeper sweeper(ConversationRedaction redaction, int batch, int pages) {
        return new ConversationRetentionSweeper(
                entryRepository,
                redaction,
                accounts,
                ownerScope,
                transactions,
                retention,
                () -> List.of(slackRetention),
                metrics,
                new SimpleMeterRegistry(),
                Clock.systemUTC(),
                batch,
                pages);
    }

    /** Redaction that counts, after commit, the entries each erasure reported as changed. */
    private ConversationRedaction reportingRedaction() {
        return new ConversationRedaction(
                copies, entryRepository, historyRemoval, turnRows, digestTurns, turnRuns, reporting(), mapper);
    }

    private ApplicationEventPublisher reporting() {
        return reporting(new ConcurrentHashMap<>());
    }

    /** Also counts, after commit, into {@code alsoInto}. */
    private ApplicationEventPublisher reporting(Map<UUID, Integer> alsoInto) {
        return event -> {
            if (event instanceof ConversationLedgerEvents.EntryChanged changed) {
                afterCommit(() -> changed.entryIds().forEach(id -> {
                    reported.merge(id, 1, Integer::sum);
                    alsoInto.merge(id, 1, Integer::sum);
                }));
            }
        };
    }

    /** Stands for the process dying: no handler catches it, and its transaction rolls back. */
    private static final class Crash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** Drains the due work with bounded runs; returns how many runs it took. */
    private int drain(ConversationRetentionSweeper sweeper, int budget) {
        for (int runs = 1; runs <= 100; runs++) {
            ConversationRetentionSweeper.Run run = sweeper.sweepNow();
            assertThat(run.erased()).as("entries one run erases").isLessThanOrEqualTo(budget);
            if (run.drained()) {
                return runs;
            }
        }
        throw new AssertionError("the due work did not drain in 100 runs");
    }

    private boolean erased(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT redacted_at IS NOT NULL AND COALESCE(payload::text, '') NOT LIKE '%words-%' FROM conversation_entry"
                        + " WHERE id = ?",
                Boolean.class, id));
    }

    @Test
    void shouldEraseEveryEntryDueByTheDatabaseClockInTheTwoWeeksBeforeTheRetentionAndKeepTheRest() {
        String week = "date_bin(interval '7 days', clock_timestamp(), timestamptz '2000-01-02 03:45:00+00')";
        Instant weekStart = Objects.requireNonNull(jdbc.queryForObject("SELECT " + week, Timestamp.class))
                .toInstant();
        Instant cutoff = weekStart.minus(retention.feedbackRetention().minus(Duration.ofDays(14)));
        UUID oldQuestion = entry(main, "USER", "", retention.feedbackRetention().plusDays(1), "words-old-question");
        UUID oldAnswer = entry(main, "ANSWER", "", retention.feedbackRetention().plusDays(1), "words-old-answer");
        UUID justDue = entry(main, "USER", "", cutoff.minusSeconds(60), "words-just-due");
        UUID justKept = entry(main, "USER", "", cutoff.plusSeconds(60), "words-just-kept");
        UUID recentQuestion =
                entry(main, "USER", "", retention.feedbackRetention().minusDays(15), "words-recent-question");

        conversationRetention.sweepNow();

        // A new retention week between the read and the run moves the cutoff by seven days.
        assumeTrue(weekStart.equals(Objects.requireNonNull(jdbc.queryForObject("SELECT " + week, Timestamp.class))
                .toInstant()));
        assertThat(erased(oldQuestion)).isTrue();
        assertThat(erased(oldAnswer)).isTrue();
        assertThat(erased(justDue))
                .as("an entry older than the retention less two weeks at the start of the retention week")
                .isTrue();
        assertThat(erased(justKept))
                .as("the due entries grow only once a week, at the start of the retention week")
                .isFalse();
        assertThat(erased(recentQuestion)).isFalse();
    }

    /**
     * A backlog of several runs: each run erases at most its page budget, the next run resumes at the oldest entry
     * still due, and the backlog drains.
     */
    @Test
    void shouldDrainABacklogLargerThanOneRunInBoundedRuns() {
        UUID beforeEpoch = entry(main, "USER", "", Instant.parse("1969-12-31T23:00:00Z"), "words-before-epoch");
        List<UUID> developer = backlog(main, 23, Instant.parse("1971-01-01T00:00:00Z"));
        List<UUID> colleague = backlog(colleagueMain, 9, Instant.parse("1971-01-02T00:00:00Z"));
        UUID recent = entry(main, "USER", "", Duration.ofDays(1), "words-recent");
        ConversationRetentionSweeper sweeper = sweeper(reportingRedaction(), 4, 2);

        ConversationRetentionSweeper.Run first = sweeper.sweepNow();

        assertThat(first.erased()).isEqualTo(8);
        assertThat(first.drained()).isFalse();
        assertThat(erased(beforeEpoch))
                .as("the first page starts at the oldest entry, whenever it was written")
                .isTrue();
        assertThat(unerased(developer)).as("the oldest eight erased first").isEqualTo(16);
        assertThat(unerased(colleague)).isEqualTo(9);

        int runs = 1 + drain(sweeper, 8);

        assertThat(runs).as("33 due entries take at least five runs of eight").isGreaterThanOrEqualTo(5);
        assertThat(unerased(developer)).isZero();
        assertThat(unerased(colleague)).isZero();
        assertThat(erased(recent)).isFalse();
        assertThat(developer).allSatisfy(id -> assertThat(reported).containsEntry(id, 1));
        assertThat(colleague).allSatisfy(id -> assertThat(reported).containsEntry(id, 1));
    }

    /**
     * A run that dies in the middle of a person's transaction keeps what it committed and loses that transaction, with
     * the copies it erased: a summary over only the third batch survives the crash. The next run starts again at the
     * oldest entry still due, erases each entry once, and the summary with it.
     */
    @Test
    void shouldResumeAfterARunThatDiesInTheMiddleOfABatch() {
        long before = seq;
        List<UUID> backlog = backlog(main, 17, Instant.parse("1971-02-01T00:00:00Z"));
        UUID thirdBatchSummary = summaryOver(main, before + 11, before + 15);
        AtomicInteger calls = new AtomicInteger();
        ConversationRedaction dying =
                new ConversationRedaction(
                        copies, entryRepository, historyRemoval, turnRows, digestTurns, turnRuns, reporting(), mapper) {
                    @Override
                    public boolean removeHistory(long workspaceId, long accountId, List<UUID> entryIds) {
                        boolean removed = super.removeHistory(workspaceId, accountId, entryIds);
                        if (calls.incrementAndGet() == 3) {
                            throw new Crash();
                        }
                        return removed;
                    }
                };

        assertThatThrownBy(() -> sweeper(dying, 5, 10).sweepNow()).isInstanceOf(Crash.class);

        assertThat(unerased(backlog))
                .as("two batches of five committed, the third rolled back with the crash")
                .isEqualTo(7);
        assertThat(erased(thirdBatchSummary))
                .as("the summary over the third batch rolled back with it")
                .isFalse();

        ConversationRetentionSweeper recovering = sweeper(reportingRedaction(), 5, 1);
        ConversationRetentionSweeper.Run resumed = recovering.sweepNow();

        assertThat(resumed.erased()).as("one bounded page").isEqualTo(5);
        assertThat(resumed.drained()).isFalse();
        assertThat(unerased(backlog))
                .as("the first run after the crash erases the batch that rolled back, and leaves the last two")
                .isEqualTo(2);
        assertThat(erased(thirdBatchSummary)).isTrue();

        drain(recovering, 5);

        assertThat(unerased(backlog)).isZero();
        assertThat(backlog).allSatisfy(id -> assertThat(reported).containsEntry(id, 1));
    }

    /**
     * One person's conversation tree is locked: the run passes over that person and still erases the due entries of
     * another person of the same workspace. Once the lock goes, the next run erases the first person's backlog and
     * leaves the entries it erased before alone.
     */
    @Test
    void shouldEraseTheOtherPeopleOfAWorkspaceWhileOnePersonsTreeIsLocked() throws Exception {
        List<UUID> locked = backlog(main, 12, Instant.parse("1971-03-01T00:00:00Z"));
        List<UUID> other = backlog(colleagueMain, 6, Instant.parse("1971-03-02T00:00:00Z"));
        ConversationRetentionSweeper sweeper = sweeper(reportingRedaction(), 5, 10);

        ConversationRetentionSweeper.Run run;
        try (java.sql.Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM conversation WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, main.getId());
                lock.executeQuery().close();
            }
            run = sweeper.sweepNow();
            holder.rollback();
        }

        assertThat(run.failedOwners()).isPositive();
        assertThat(unerased(locked))
                .as("the locked person waits for the next run")
                .isEqualTo(12);
        assertThat(unerased(other))
                .as("another person of the same workspace progresses")
                .isZero();
        List<@Nullable Timestamp> otherErasedAt = erasedAt(other);

        drain(sweeper, 50);

        assertThat(unerased(locked)).isZero();
        assertThat(erasedAt(other)).as("entries erased before stay as they are").isEqualTo(otherErasedAt);
        assertThat(locked).allSatisfy(id -> assertThat(reported).containsEntry(id, 1));
        assertThat(other).allSatisfy(id -> assertThat(reported).containsEntry(id, 1));
    }

    /**
     * Two sweepers on two replicas run at once over the same backlog, as when one outlives its scheduler lock, each with
     * a budget of one page. Both hold the same first page. The leader erases it and commits; then the follower erases its
     * stale page, finds every entry erased under the tree lock, changes nothing and commits too. The rest stays for the
     * next run, which drains it. Each entry is erased, and reported erased once.
     */
    @Test
    void shouldEraseEachEntryOnceWhenTwoSweepersHoldTheSamePage() throws Exception {
        List<UUID> developer = backlog(main, 10, Instant.parse("1971-04-01T00:00:00Z"));
        List<UUID> colleague = backlog(colleagueMain, 10, Instant.parse("1971-04-02T00:00:00Z"));
        UUID recent = entry(main, "USER", "", Duration.ofDays(1), "words-recent");
        List<UUID> oldestFive = jdbc
                .queryForList(
                        "SELECT id FROM conversation_entry WHERE id = ANY(?) ORDER BY created_at, id LIMIT 5",
                        UUID.class,
                        (Object) developer.toArray(UUID[]::new))
                .stream()
                .map(Objects::requireNonNull)
                .toList();
        CyclicBarrier samePage = new CyclicBarrier(2);
        AtomicReference<@Nullable String> leader = new AtomicReference<>();
        Map<String, Collection<UUID>> firstPage = new ConcurrentHashMap<>();
        Map<String, Boolean> firstCommitted = new ConcurrentHashMap<>();
        Map<String, Map<UUID, Integer>> reportedBy =
                Map.of("a", new ConcurrentHashMap<>(), "b", new ConcurrentHashMap<>());
        ExecutorService replicas = Executors.newFixedThreadPool(2);
        try {
            List<Future<ConversationRetentionSweeper.Run>> runs = List.of("a", "b").stream()
                    .map(replica -> replicas.submit(() -> sweeper(
                                    new ConversationRedaction(
                                            copies,
                                            entryRepository,
                                            historyRemoval,
                                            turnRows,
                                            digestTurns,
                                            turnRuns,
                                            reporting(Objects.requireNonNull(reportedBy.get(replica))),
                                            mapper) {
                                        @Override
                                        public boolean removeHistory(long workspaceId, long accountId, List<UUID> ids) {
                                            firstPage.put(replica, List.copyOf(ids));
                                            await(samePage);
                                            boolean advanced = super.removeHistory(workspaceId, accountId, ids);
                                            if (advanced) leader.set(replica);
                                            afterCommit(() -> firstCommitted.put(replica, true));
                                            return advanced;
                                        }
                                    },
                                    5,
                                    1)
                            .sweepNow()))
                    .toList();
            for (Future<ConversationRetentionSweeper.Run> run : runs) {
                assertThat(run.get(2, TimeUnit.MINUTES).erased())
                        .as("each bounded run hands only its one page to erasure")
                        .isEqualTo(5);
            }
        } finally {
            replicas.shutdownNow();
        }
        assertThat(unerased(developer))
                .as("the two overlapping runs of one page erased the same five entries")
                .isEqualTo(5);
        assertThat(unerased(colleague)).isEqualTo(10);

        drain(sweeper(reportingRedaction(), 5, 10), 50);

        String first = Objects.requireNonNull(leader.get());
        String second = first.equals("a") ? "b" : "a";
        assertThat(firstPage.get(first))
                .as("both hold the same first page")
                .isEqualTo(firstPage.get(second))
                .containsExactlyElementsOf(oldestFive);
        assertThat(firstCommitted)
                .as("both erasures of the first page committed")
                .containsKeys("a", "b");
        assertThat(oldestFive).allSatisfy(id -> {
            assertThat(reportedBy.get(first)).containsEntry(id, 1);
            assertThat(reportedBy.get(second)).doesNotContainKey(id);
        });
        assertThat(unerased(developer)).isZero();
        assertThat(unerased(colleague)).isZero();
        assertThat(erased(recent)).isFalse();
        assertThat(developer).allSatisfy(id -> assertThat(reported).containsEntry(id, 1));
        assertThat(colleague).allSatisfy(id -> assertThat(reported).containsEntry(id, 1));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void shouldEraseACopyingToolResultWithTheSlackRetentionAndKeepTheMessagesAndOtherResults() {
        connectSlack(workspace);
        UUID oldRead = entry(main, "TOOL_RESULT", "read", Duration.ofDays(24), "words-channel-message");
        UUID oldBash = entry(main, "TOOL_RESULT", "bash", Duration.ofDays(24), "words-grep-output");
        // Due when older than the Slack retention of 30 days less two weeks at the start of the retention week: 24 days
        // always, 15 days never.
        UUID recentRead = entry(main, "TOOL_RESULT", "read", Duration.ofDays(15), "words-recent-read");
        UUID oldSearch =
                entry(main, "TOOL_RESULT", "search_conversation", Duration.ofDays(24), "words-own-conversation");
        UUID oldAnswer = entry(main, "ANSWER", "", Duration.ofDays(24), "words-answer");
        UUID summary = summary(main);
        UUID otherRead = entry(otherMain, "TOOL_RESULT", "read", Duration.ofDays(24), "words-other-workspace");

        conversationRetention.sweepCopiesNow();

        assertThat(erased(oldRead)).isTrue();
        assertThat(erased(oldBash)).isTrue();
        assertThat(erased(recentRead)).isFalse();
        assertThat(erased(oldSearch))
                .as("a search result quotes the developer's own conversation and keeps its limit")
                .isFalse();
        assertThat(erased(oldAnswer)).isFalse();
        assertThat(erased(summary))
                .as("a summary over an erased copy goes with it")
                .isTrue();
        assertThat(erased(otherRead))
                .as("a workspace without Slack copies no channel message")
                .isFalse();
    }

    @Test
    void shouldEraseACopyingToolResultWhileTheSlackConnectionIsSuspended() {
        connectSlack(workspace, IntegrationState.SUSPENDED);
        UUID oldRead = entry(main, "TOOL_RESULT", "read", Duration.ofDays(24), "words-channel-message");

        conversationRetention.sweepCopiesNow();

        assertThat(erased(oldRead)).isTrue();
    }

    /**
     * A backlog of copying tool results in two Slack workspaces, larger than one run: each run hands at most its page
     * budget to erasure, oldest first across both workspaces, and the next runs drain the rest. A question of the same
     * age keeps the conversation retention.
     */
    @Test
    void shouldDrainTheCopiesOfSeveralWorkspacesInBoundedRunsOldestFirst() {
        connectSlack(workspace);
        connectSlack(otherWorkspace);
        Instant written = Instant.now().minus(Duration.ofDays(60));
        List<UUID> here = copyBacklog(main, 9, written);
        List<UUID> there = copyBacklog(otherMain, 9, written.plusMillis(500));
        UUID question = entry(main, "USER", "", written, "words-question");
        ConversationRetentionSweeper sweeper = sweeper(reportingRedaction(), 4, 1);

        ConversationRetentionSweeper.Run first = sweeper.sweepCopiesNow();

        assertThat(first.erased()).as("one page of four").isEqualTo(4);
        assertThat(first.drained()).isFalse();
        assertThat(unerased(here))
                .as("the oldest come first, in either workspace")
                .isEqualTo(7);
        assertThat(unerased(there)).isEqualTo(7);

        int runs = 1;
        ConversationRetentionSweeper.Run next;
        do {
            next = sweeper.sweepCopiesNow();
            runs++;
        } while (!next.drained() && runs < 20);

        assertThat(runs).as("18 copies in runs of four").isEqualTo(5);
        assertThat(unerased(here)).isZero();
        assertThat(unerased(there)).isZero();
        assertThat(erased(question))
                .as("a question keeps the conversation retention")
                .isFalse();
    }

    /**
     * One person's conversation tree is locked: the copy sweep passes over that person and still erases the copies of
     * another person of the same workspace and of another workspace. The next run gets back to the first person.
     */
    @Test
    void shouldEraseTheCopiesOfOtherPeopleWhileOnePersonsTreeIsLocked() throws Exception {
        connectSlack(workspace);
        connectSlack(otherWorkspace);
        Instant written = Instant.now().minus(Duration.ofDays(60));
        List<UUID> locked = copyBacklog(main, 6, written);
        List<UUID> colleague = copyBacklog(colleagueMain, 6, written.plusSeconds(60));
        List<UUID> elsewhere = copyBacklog(otherMain, 6, written.plusSeconds(120));
        ConversationRetentionSweeper sweeper = sweeper(reportingRedaction(), 5, 10);

        ConversationRetentionSweeper.Run run;
        try (java.sql.Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM conversation WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, main.getId());
                lock.executeQuery().close();
            }
            run = sweeper.sweepCopiesNow();
            holder.rollback();
        }

        assertThat(run.failedOwners()).isEqualTo(1);
        assertThat(unerased(locked)).isEqualTo(6);
        assertThat(unerased(colleague))
                .as("another person of the same workspace")
                .isZero();
        assertThat(unerased(elsewhere)).as("another workspace").isZero();

        sweeper.sweepCopiesNow();

        assertThat(unerased(locked)).isZero();
    }

    /**
     * The database clock decides which copies are due, by the week: a copy written a minute before the start of the
     * retention week less the Slack retention less two weeks is due, one written a minute after it is not.
     */
    @Test
    void shouldEraseACopyDueByTheDatabaseClockAndKeepOneJustAfterTheCutoff() {
        connectSlack(workspace);
        String week = "date_bin(interval '7 days', clock_timestamp(), timestamptz '2000-01-02 03:45:00+00')";
        Instant weekStart = Objects.requireNonNull(jdbc.queryForObject("SELECT " + week, Timestamp.class))
                .toInstant();
        Instant cutoff = weekStart.minus(Duration.ofDays(30 - 14));
        UUID justDue = entry(main, "TOOL_RESULT", "read", cutoff.minusSeconds(60), "words-just-due");
        UUID justKept = entry(main, "TOOL_RESULT", "read", cutoff.plusSeconds(60), "words-just-kept");

        conversationRetention.sweepCopiesNow();

        assumeTrue(weekStart.equals(Objects.requireNonNull(jdbc.queryForObject("SELECT " + week, Timestamp.class))
                .toInstant()));
        assertThat(erased(justDue)).isTrue();
        assertThat(erased(justKept)).isFalse();
    }

    /**
     * The copies of a person in erasure are the erasure's to remove: the copy sweep leaves them, and they do not hold the
     * copy margin below its limit, or a person in erasure would keep the alert firing.
     */
    @Test
    void shouldLeaveTheCopiesOfAPersonInErasureToTheErasureAndOutOfTheCopyMargin() {
        connectSlack(workspace);
        List<UUID> erasing = copyBacklog(colleagueMain, 2, Instant.now().minus(Duration.ofDays(60)));
        jdbc.update(
                "UPDATE account SET status = 'DELETING', deleted_at = now() WHERE id = ?",
                colleagueMain.getAccountId());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ConversationRetentionSweeper sweeper = new ConversationRetentionSweeper(
                entryRepository,
                reportingRedaction(),
                accounts,
                ownerScope,
                transactions,
                retention,
                () -> List.of(slackRetention),
                metrics,
                meters,
                Clock.systemUTC(),
                ConversationRetentionSweeper.BATCH,
                ConversationRetentionSweeper.MAX_PAGES);

        // Also drains the due copies that earlier tests left.
        ConversationRetentionSweeper.Run run = sweeper.sweepCopiesNow();
        sweeper.sampleDeadlineMargin();

        assertThat(run.drained()).isTrue();
        assertThat(run.failedOwners()).isZero();
        assertThat(unerased(erasing)).isEqualTo(2);
        assertThat(meters.get("conversation.retention.deadline.margin")
                        .tag("rule", "copies")
                        .gauge()
                        .value())
                .isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void shouldEraseEveryCopyingToolResultWhenTheSlackContentIsErased() {
        UUID read = entry(main, "TOOL_RESULT", "read", Duration.ofHours(1), "words-channel-message");
        UUID question = entry(main, "USER", "", Duration.ofHours(1), "words-app-question");

        slackContent.eraseWorkspace(workspace.getId());

        assertThat(erased(read)).isTrue();
        assertThat(erased(question)).isFalse();
    }
}
