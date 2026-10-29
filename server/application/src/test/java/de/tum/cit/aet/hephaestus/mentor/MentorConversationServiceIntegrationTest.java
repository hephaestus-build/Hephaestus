package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class MentorConversationServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorConversationRepository conversationRepository;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private long developer;
    private long colleague;

    @BeforeEach
    void setUpConversation() {
        User developerUser = persistUser("heph-conversation-developer");
        workspace = createWorkspace("heph-conversation", "Hephaestus", "heph-org", AccountType.ORG, developerUser);
        developer = accountId(developerUser);
        colleague = accountId(persistUser("heph-conversation-colleague"));
    }

    @Test
    void shouldAnswerAnEarlierReadWithTheMarkerAConcurrentReadCommittedMeanwhile() throws Exception {
        conversations.findOrCreateMain(workspace.getId(), developer);
        Instant before = Instant.parse("2026-10-12T08:00:00Z");
        Instant earlier = Instant.parse("2026-10-12T09:00:00Z");
        Instant later = Instant.parse("2026-10-12T10:00:00Z");
        conversations.markRead(workspace.getId(), developer, before);

        CountDownLatch moved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Device B moves the marker to the later time and holds its transaction open, so its row stays locked.
        CompletableFuture<Void> deviceB = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            conversations.markRead(workspace.getId(), developer, later);
            moved.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(moved.await(20, TimeUnit.SECONDS)).isTrue();
        // Device A reads the row while B's write is uncommitted, then waits on B's lock in its UPDATE.
        CompletableFuture<@Nullable Instant> deviceA =
                CompletableFuture.supplyAsync(() -> conversations.markRead(workspace.getId(), developer, earlier));
        await().atMost(Duration.ofSeconds(20))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock')",
                        Boolean.class)));
        release.countDown();
        deviceB.get(20, TimeUnit.SECONDS);

        assertThat(deviceA.get(20, TimeUnit.SECONDS))
                .as("A answers with the marker B committed, not the one A read")
                .isEqualTo(later);
    }

    @Test
    void shouldCreateOneMainConversationPerAccountOnTheFirstWriteAndFindItAgain() {
        assertThat(conversations.findMain(workspace.getId(), developer)).isEmpty();

        MentorConversation main = conversations.findOrCreateMain(workspace.getId(), developer);
        MentorEntry question = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Hi"));
        MentorConversation thread = conversations.openThread(workspace.getId(), question.getId());
        MentorConversation again = conversations.findOrCreateMain(workspace.getId(), developer);
        MentorConversation colleagues = conversations.findOrCreateMain(workspace.getId(), colleague);

        MentorConversation stored =
                conversationRepository.findById(main.getId()).orElseThrow();
        assertThat(stored.getAccountId()).isEqualTo(developer);
        assertThat(stored.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(stored.isThread()).isFalse();
        assertThat(again.getId()).isEqualTo(main.getId());
        assertThat(conversations.findMain(workspace.getId(), developer))
                .map(MentorConversation::getId)
                .contains(main.getId());
        assertThat(conversationRepository.findById(thread.getId()).orElseThrow().getAccountId())
                .isEqualTo(developer);
        assertThat(colleagues.getId()).isNotEqualTo(main.getId());
        assertThat(colleagues.getAccountId()).isEqualTo(colleague);
    }

    @Test
    void shouldRejectAThreadWhenTheParentIsInAThreadOrNotAMessage() {
        MentorConversation main = conversations.findOrCreateMain(workspace.getId(), developer);
        MentorEntry parent = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Answer"));
        MentorConversation thread = conversations.openThread(workspace.getId(), parent.getId());
        MentorEntry inThread = entries.append(workspace.getId(), thread.getId(), MentorLedgerFixtures.heph("Reply"));
        MentorEntry summary = entries.append(
                workspace.getId(),
                main.getId(),
                new MentorEntryDraft(
                        MentorEntry.Kind.SUMMARY,
                        MentorEntry.Surface.APP,
                        "Summary",
                        MentorLedgerFixtures.compaction("c1", "Summary", MentorLedgerFixtures.piIdOf(parent)),
                        null,
                        null,
                        null));

        assertThatThrownBy(() -> conversations.openThread(workspace.getId(), inThread.getId()))
                .isInstanceOfSatisfying(
                        MentorRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(MentorRejectedException.Reason.NOT_A_THREAD_PARENT));
        assertThatThrownBy(() -> conversations.openThread(workspace.getId(), summary.getId()))
                .isInstanceOf(MentorRejectedException.class);
    }
}
