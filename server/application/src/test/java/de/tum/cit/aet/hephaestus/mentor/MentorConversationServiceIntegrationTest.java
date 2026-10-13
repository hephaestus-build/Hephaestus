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
import java.util.Set;
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
    private User gitlabActor;
    private User githubActor;

    @BeforeEach
    void setUpConversation() {
        gitlabActor = persistUser("heph-gitlab-actor");
        githubActor = persistUser("heph-github-actor");
        workspace = createWorkspace("heph-conversation", "Heph", "heph-org", AccountType.ORG, gitlabActor);
    }

    @Test
    void shouldAnswerAnEarlierReadWithTheMarkerAConcurrentReadCommittedMeanwhile() throws Exception {
        Set<Long> account = Set.of(gitlabActor.getId());
        conversations.findOrCreateMain(workspace.getId(), gitlabActor.getId(), account);
        Instant before = Instant.parse("2026-10-12T08:00:00Z");
        Instant earlier = Instant.parse("2026-10-12T09:00:00Z");
        Instant later = Instant.parse("2026-10-12T10:00:00Z");
        conversations.markRead(workspace.getId(), account, before);

        CountDownLatch moved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Device B moves the marker to the later time and holds its transaction open, so its row stays locked.
        CompletableFuture<Void> deviceB = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            conversations.markRead(workspace.getId(), account, later);
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
                CompletableFuture.supplyAsync(() -> conversations.markRead(workspace.getId(), account, earlier));
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
    void shouldCreateTheMainConversationUnderTheSelectedActorWhenTheAccountHasNone() {
        MentorConversation main = conversations.findOrCreateMain(
                workspace.getId(), gitlabActor.getId(), Set.of(gitlabActor.getId(), githubActor.getId()));

        MentorConversation stored =
                conversationRepository.findById(main.getId()).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(gitlabActor.getId());
        assertThat(stored.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(stored.isThread()).isFalse();
    }

    @Test
    void shouldFindTheSameConversationAndMoveItWithItsThreadsWhenAnotherActorOfTheAccountWrites() {
        Set<Long> account = Set.of(gitlabActor.getId(), githubActor.getId());
        MentorConversation main = conversations.findOrCreateMain(workspace.getId(), gitlabActor.getId(), account);
        MentorEntry question = entries.append(workspace.getId(), main.getId(), MentorLedgerFixtures.heph("Hi"));
        MentorConversation thread = conversations.openThread(workspace.getId(), question.getId());

        MentorConversation again = conversations.findOrCreateMain(workspace.getId(), githubActor.getId(), account);

        assertThat(again.getId()).isEqualTo(main.getId());
        assertThat(conversationRepository.findById(main.getId()).orElseThrow().getUserId())
                .isEqualTo(githubActor.getId());
        assertThat(conversationRepository.findById(thread.getId()).orElseThrow().getUserId())
                .isEqualTo(githubActor.getId());
    }

    @Test
    void shouldRejectAThreadWhenTheParentIsInAThreadOrNotAMessage() {
        MentorConversation main =
                conversations.findOrCreateMain(workspace.getId(), gitlabActor.getId(), Set.of(gitlabActor.getId()));
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
