package de.tum.cit.aet.hephaestus.integration.slack.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadCandidate;
import de.tum.cit.aet.hephaestus.agent.job.ConversationReviewSubmitter;
import de.tum.cit.aet.hephaestus.agent.job.conversation.ConversationThreadTriggerScheduler;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationProjector;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.node.ObjectNode;

/**
 * Conversation-detection integration tests (Testcontainers — the candidate scan rides a real Postgres
 * {@code bigint[]} column, the growth count rides lexicographic Slack-{@code ts} comparison, and the watermark
 * advance is a real UPDATE). The fan-out is mocked so the sweep's decision is observable without seeding an
 * agent-config graph; the raw SQL the scheduler owns, and the signal-ledger row it writes before deciding
 * anything, are exercised for real.
 *
 * <p>Channel ingestion is off by default (a deliberate, privacy-sensitive parked capability), so this test
 * enables it via {@code hephaestus.integration.slack.conversation-ingest.enabled}.
 */
@TestPropertySource(properties = "hephaestus.integration.slack.conversation-ingest.enabled=true")
class ConversationThreadDetectionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConversationThreadTriggerScheduler scheduler;

    @Autowired
    private SlackConversationProjector projector;

    @Autowired
    private JdbcTemplate jdbc;

    /** Mocked here; its own unit test covers what it does with the participants it is handed. */
    @MockitoSpyBean
    private ConversationReviewSubmitter conversationReviewSubmitter;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private static final AtomicLong WS_SEQ = new AtomicLong();

    /**
     * A real {@code workspace} row per test. The Slack tables carry no foreign key to one, but the signal
     * ledger the sweep writes to does, which is what lets an occurrence be joined to the workspace whose
     * budget paid for it.
     */
    private long newWorkspace() {
        Workspace workspace = WorkspaceTestFixtures.activeWorkspace("conv-detect-" + WS_SEQ.incrementAndGet());
        return workspaceRepository.save(workspace).getId();
    }

    private SlackConversationTestSupport support;

    @BeforeEach
    void ensureUnmappedSlackThreadColumns() {
        support = new SlackConversationTestSupport(jdbc);
        support.ensureUnmappedSlackThreadColumns();
    }

    private void seedChannel(long workspaceId, String channelId, String consentState) {
        support.seedChannel(workspaceId, channelId, consentState);
    }

    private void seedThread(
            long workspaceId,
            String channelId,
            @Nullable String threadTs,
            String lastTs,
            int messageCount,
            String participants) {
        support.seedThread(workspaceId, channelId, threadTs, lastTs, messageCount, participants);
    }

    private void seedMessage(long workspaceId, String channelId, String ts, @Nullable String threadTs) {
        support.seedMessage(workspaceId, channelId, ts, threadTs, "hi");
    }

    @Test
    @DisplayName(
            "a settled, deep, grown thread enqueues one CONVERSATION_REVIEW per participant and advances the watermark")
    void detectsSettledThreadAndEnqueuesPerParticipant() {
        long ws = newWorkspace();
        long baseSecond = Instant.now().getEpochSecond() - 1200; // 20 minutes ago → past the 10-minute quiescence
        String rootTs = baseSecond + ".000000";
        String t1 = (baseSecond + 1) + ".000000";
        String t2 = (baseSecond + 2) + ".000000";
        String lastTs = (baseSecond + 3) + ".000000";

        seedChannel(ws, "C1", "ACTIVE");
        seedThread(ws, "C1", rootTs, lastTs, 4, "{100,101}");
        seedMessage(ws, "C1", rootTs, null);
        seedMessage(ws, "C1", t1, rootTs);
        seedMessage(ws, "C1", t2, rootTs);
        seedMessage(ws, "C1", lastTs, rootTs);

        doReturn(2L).when(conversationReviewSubmitter).submitAndSettle(any(), any());

        scheduler.detectNow();

        ArgumentCaptor<ConversationThreadCandidate> captor = ArgumentCaptor.forClass(ConversationThreadCandidate.class);
        verify(conversationReviewSubmitter).submitAndSettle(captor.capture(), argThat(keyIn(ws)));
        ConversationThreadCandidate candidate = captor.getValue();
        assertThat(candidate.participantMemberIds()).containsExactlyInAnyOrder(100L, 101L);
        assertThat(candidate.channelId()).isEqualTo("C1");
        assertThat(candidate.channelName()).isEqualTo("engineering");
        assertThat(candidate.threadTs()).isEqualTo(rootTs);
        assertThat(candidate.lastTs()).isEqualTo(lastTs);

        // The occurrence is in the ledger, which is what lets the artifact trace explain a conversation
        // that nothing happened to.
        Long recorded = jdbc.queryForObject(
                "SELECT count(*) FROM artifact_signal WHERE workspace_id = ? AND artifact_kind = ?"
                        + " AND artifact_id > 0 AND signal_name = ?",
                Long.class,
                ws,
                "chat.conversation_thread",
                "chat.conversation_thread.settled");
        assertThat(recorded).isEqualTo(1L);

        // Watermark advanced to the thread's newest ts after enqueue → a no-growth re-sweep enqueues nothing.
        String watermark = jdbc.queryForObject(
                "SELECT last_reviewed_ts FROM slack_thread WHERE workspace_id = ? AND slack_channel_id = ? AND slack_thread_ts = ?",
                String.class,
                ws,
                "C1",
                rootTs);
        assertThat(watermark).isEqualTo(lastTs);
    }

    @Test
    @DisplayName("a thread that has not settled (recent last message) enqueues nothing")
    void skipsNonQuiescentThread() {
        long ws = newWorkspace();
        long recentSecond = Instant.now().getEpochSecond() - 60; // 1 minute ago → inside quiescence window
        String rootTs = recentSecond + ".000000";
        String lastTs = (recentSecond + 3) + ".000000";

        seedChannel(ws, "C1", "ACTIVE");
        seedThread(ws, "C1", rootTs, lastTs, 4, "{100}");
        seedMessage(ws, "C1", rootTs, null);
        seedMessage(ws, "C1", (recentSecond + 1) + ".000000", rootTs);
        seedMessage(ws, "C1", (recentSecond + 2) + ".000000", rootTs);
        seedMessage(ws, "C1", lastTs, rootTs);

        scheduler.detectNow();

        verify(conversationReviewSubmitter, times(0)).submitAndSettle(any(), argThat(keyIn(ws)));
    }

    @Test
    @DisplayName("buildThreadPayload materialises the non-tombstoned turns of one thread under the quarantine envelope")
    void projectsSingleThread() {
        long ws = newWorkspace();
        long baseSecond = Instant.now().getEpochSecond() - 1200;
        String rootTs = baseSecond + ".000000";
        String replyTs = (baseSecond + 1) + ".000000";
        seedChannel(ws, "C1", "ACTIVE");
        seedThread(ws, "C1", rootTs, replyTs, 2, "{100}");
        seedMessage(ws, "C1", rootTs, null);
        seedMessage(ws, "C1", replyTs, rootTs);

        ObjectNode payload = projector.buildThreadPayload(ws, "C1", rootTs);

        assertThat(payload.get("channel").asString()).isEqualTo("C1");
        assertThat(payload.get("messageCount").asInt()).isEqualTo(2);
        assertThat(payload.get("_meta").get("trustLevel").asString()).isEqualTo("UNTRUSTED_EXTERNAL");
        assertThat(payload.get("messages")).hasSize(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {100, 101})
    @DisplayName("review threads retain every permitted message without a projection cap")
    void shouldRetainEveryPermittedThreadMessage(int messageCount) {
        long ws = newWorkspace();
        long baseSecond = Instant.now().getEpochSecond() - 1200;
        String rootTs = baseSecond + ".000000";
        String lastTs = baseSecond + "." + String.format("%06d", messageCount - 1);
        seedChannel(ws, "C1", "ACTIVE");
        seedThread(ws, "C1", rootTs, lastTs, messageCount, "{100}");
        for (int i = 0; i < messageCount; i++) {
            String ts = baseSecond + "." + String.format("%06d", i);
            seedMessage(ws, "C1", ts, i == 0 ? null : rootTs);
        }

        ObjectNode payload = projector.buildThreadPayload(ws, "C1", rootTs);

        assertThat(payload.get("messages")).hasSize(messageCount);
        assertThat(payload.get("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("consent gate is atomic with the read: a revoked/paused channel yields an EMPTY detection projection")
    void projectsNothingWhenChannelConsentIsNotActive() {
        long baseSecond = Instant.now().getEpochSecond() - 1200;
        String rootTs = baseSecond + ".000000";
        String replyTs = (baseSecond + 1) + ".000000";

        // A channel whose consent was REVOKED between enqueue and execution: the settled thread is unchanged,
        // but the detection projection must not leak its messages into the LLM.
        long wsRevoked = newWorkspace();
        seedChannel(wsRevoked, "C1", "REVOKED");
        seedThread(wsRevoked, "C1", rootTs, replyTs, 2, "{100}");
        seedMessage(wsRevoked, "C1", rootTs, null);
        seedMessage(wsRevoked, "C1", replyTs, rootTs);

        ObjectNode revoked = projector.buildThreadPayload(wsRevoked, "C1", rootTs);
        assertThat(revoked.get("messageCount").asInt()).isZero();
        assertThat(revoked.get("messages")).isEmpty();

        // A PAUSED channel is likewise gated out.
        long wsPaused = newWorkspace();
        seedChannel(wsPaused, "C1", "PAUSED");
        seedThread(wsPaused, "C1", rootTs, replyTs, 2, "{100}");
        seedMessage(wsPaused, "C1", rootTs, null);
        seedMessage(wsPaused, "C1", replyTs, rootTs);
        assertThat(projector.buildThreadPayload(wsPaused, "C1", rootTs).get("messages"))
                .isEmpty();

        // Control: the SAME thread shape on an ACTIVE channel still projects both turns.
        long wsActive = newWorkspace();
        seedChannel(wsActive, "C1", "ACTIVE");
        seedThread(wsActive, "C1", rootTs, replyTs, 2, "{100}");
        seedMessage(wsActive, "C1", rootTs, null);
        seedMessage(wsActive, "C1", replyTs, rootTs);
        assertThat(projector.buildThreadPayload(wsActive, "C1", rootTs).get("messages"))
                .hasSize(2);
    }

    @Test
    @DisplayName(
            "candidate scan is workspace-pinned: another workspace's thread with the same ids is not enqueued for this one")
    void doesNotLeakAcrossWorkspaces() {
        long wsA = newWorkspace();
        long wsB = newWorkspace();
        long baseSecond = Instant.now().getEpochSecond() - 1200;
        String rootTs = baseSecond + ".000000";
        String lastTs = (baseSecond + 3) + ".000000";
        // Only wsB's channel is ACTIVE; wsA's is PENDING, so wsA must never be enqueued.
        seedChannel(wsA, "C1", "PENDING");
        seedThread(wsA, "C1", rootTs, lastTs, 4, "{100}");
        seedChannel(wsB, "C1", "ACTIVE");
        seedThread(wsB, "C1", rootTs, lastTs, 4, "{100}");
        for (long s = baseSecond; s <= baseSecond + 3; s++) {
            seedMessage(wsB, "C1", s + ".000000", s == baseSecond ? null : rootTs);
        }

        doReturn(1L).when(conversationReviewSubmitter).submitAndSettle(any(), any());

        scheduler.detectNow();

        verify(conversationReviewSubmitter, times(0)).submitAndSettle(any(), argThat(keyIn(wsA)));
        verify(conversationReviewSubmitter, times(1)).submitAndSettle(any(), argThat(keyIn(wsB)));
    }

    /**
     * The sweep is cross-workspace by design and this class shares one database, so every assertion about
     * it has to name the workspace it is about — otherwise a thread another test seeded satisfies it.
     */
    private static ArgumentMatcher<SignalKey> keyIn(long workspaceId) {
        return key -> key != null && key.workspaceId() == workspaceId;
    }
}
