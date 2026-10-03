package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer-side handler tests. A REAL {@link SlackIngestService} is wired over mocked repositories/gates so the
 * fail-closed consent gates, forward-only watermark, participant firewall, tenant threading, and idempotency are
 * exercised end-to-end THROUGH {@link SlackChannelMessageHandler#onMessage}, over a real JSON deserialization of the
 * NATS body — i.e. the exact durable path the Slack events endpoint publishes onto.
 */
class SlackChannelMessageHandlerTest extends BaseUnitTest {

    private static final long WORKSPACE = 42L;
    /** Consent announced well before the test ts "100.1" (epoch ~100s), so the forward-only gate passes by default. */
    private static final Instant ANNOUNCED_BEFORE = Instant.ofEpochSecond(50);

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private SlackChannelConsentGate consentGate;

    @Mock
    private SlackParticipantConsentGate participantConsentGate;

    @Mock
    private SlackMessageRepository messageRepository;

    @Mock
    private SlackThreadRepository threadRepository;

    @Mock
    private SlackMentorIdentityResolver identityResolver;

    @Mock
    private ConversationFeedbackErasure conversationFeedbackErasure;

    private SlackChannelMessageHandler handler;

    @BeforeEach
    void setUp() {
        SlackIngestService ingestService = new SlackIngestService(
                workspaceResolver,
                monitoredChannelRepository,
                consentGate,
                participantConsentGate,
                messageRepository,
                threadRepository,
                identityResolver,
                conversationFeedbackErasure,
                true // conversation-ingest capability on
                );
        NatsMessageDeserializer deserializer =
                new NatsMessageDeserializer(JsonMapper.builder().build());
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        lenient()
                .doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        handler = new SlackChannelMessageHandler(ingestService, deserializer, transactionTemplate);
    }

    /** A handler whose ingest service has the conversation-ingest kill switch OFF (fail-closed layer 1). */
    private SlackChannelMessageHandler handlerWithIngestDisabled() {
        SlackIngestService ingestService = new SlackIngestService(
                workspaceResolver,
                monitoredChannelRepository,
                consentGate,
                participantConsentGate,
                messageRepository,
                threadRepository,
                identityResolver,
                conversationFeedbackErasure,
                false // conversation-ingest capability OFF
                );
        NatsMessageDeserializer deserializer =
                new NatsMessageDeserializer(JsonMapper.builder().build());
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        lenient()
                .doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        return new SlackChannelMessageHandler(ingestService, deserializer, transactionTemplate);
    }

    private static Message natsMessage(String body) {
        Message m = mock(Message.class);
        when(m.getSubject()).thenReturn("slack.T1.C1.message");
        when(m.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    private static final String PLAIN_MESSAGE = """
        {"type":"event_callback","team_id":"T1","event":{
          "type":"message","channel_type":"channel","channel":"C1","user":"U1","ts":"100.1","thread_ts":"100.0","text":"hello"}}
        """;

    private void stubActiveConsentedChannel() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(WORKSPACE));
        when(consentGate.ingestAllowed(WORKSPACE, "C1")).thenReturn(true);
        when(monitoredChannelRepository.findConsentAnnouncedAt(WORKSPACE, "C1"))
                .thenReturn(Optional.of(ANNOUNCED_BEFORE));
        when(participantConsentGate.ingestionAllowed(WORKSPACE, "U1")).thenReturn(true);
        when(identityResolver.resolveMemberId(WORKSPACE, "T1", "U1")).thenReturn(Optional.of(7L));
    }

    @Test
    void plainChannelMessage_isStored_underTheResolvedWorkspace() {
        stubActiveConsentedChannel();
        when(messageRepository.insertIfAbsent(eq(WORKSPACE), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        handler.onMessage(natsMessage(PLAIN_MESSAGE));

        // Tenant isolation: the write carries the resolved workspace id, and the thread projection fires once.
        verify(messageRepository).insertIfAbsent(WORKSPACE, "T1", "C1", "100.1", "100.0", "U1", 7L, "hello");
        verify(threadRepository).upsertOnMessage(WORKSPACE, "C1", "100.0", "100.1", 7L);
    }

    @Test
    void reDelivery_isIdempotent_noDoubleEffect() {
        stubActiveConsentedChannel();
        // insertIfAbsent is ON CONFLICT DO NOTHING: 1 row the first delivery, 0 on the redelivered duplicate.
        when(messageRepository.insertIfAbsent(eq(WORKSPACE), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1)
                .thenReturn(0);

        handler.onMessage(natsMessage(PLAIN_MESSAGE));
        handler.onMessage(natsMessage(PLAIN_MESSAGE));

        // Both deliveries attempt the insert, but the committed effect (thread projection) runs EXACTLY once.
        verify(messageRepository, times(2))
                .insertIfAbsent(eq(WORKSPACE), any(), any(), any(), any(), any(), any(), any());
        verify(threadRepository, times(1)).upsertOnMessage(anyLong(), any(), any(), any(), any());
    }

    @Test
    void transientFailureThenSuccess_appliesExactlyOnce_withNoLoss() {
        stubActiveConsentedChannel();
        // First delivery: a transient DB error on insert. The handler must PROPAGATE it so the consumer NAKs and
        // JetStream redelivers; a claim recorded before the effect would lose the message here. Second delivery:
        // success.
        when(messageRepository.insertIfAbsent(eq(WORKSPACE), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("transient db error"))
                .thenReturn(1);

        assertThatThrownBy(() -> handler.onMessage(natsMessage(PLAIN_MESSAGE)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("transient");
        // No projection on the failed attempt.
        verify(threadRepository, never()).upsertOnMessage(anyLong(), any(), any(), any(), any());

        // Redelivery succeeds; the effect applies exactly once.
        handler.onMessage(natsMessage(PLAIN_MESSAGE));
        verify(threadRepository, times(1)).upsertOnMessage(WORKSPACE, "C1", "100.0", "100.1", 7L);
    }

    @Test
    void nonActiveChannel_failsClosed_storesNothing() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(WORKSPACE));
        when(consentGate.ingestAllowed(WORKSPACE, "C1")).thenReturn(false); // PENDING/PAUSED/REVOKED

        handler.onMessage(natsMessage(PLAIN_MESSAGE));

        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void botMessage_isIgnored() {
        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","channel_type":"channel","channel":"C1","bot_id":"B1","ts":"100.1","text":"beep"}}
                """));

        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void threadBroadcast_isIngestedLikeAPlainMessage() {
        stubActiveConsentedChannel();
        when(messageRepository.insertIfAbsent(eq(WORKSPACE), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // A thread reply also broadcast to the channel arrives with subtype "thread_broadcast" but carries a real
        // author + ts + text, so it must ingest — not be dropped by the non-content subtype no-op.
        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"thread_broadcast","channel_type":"channel","channel":"C1",
                  "user":"U1","ts":"100.1","thread_ts":"100.0","text":"broadcast reply"}}
                """));

        verify(messageRepository).insertIfAbsent(WORKSPACE, "T1", "C1", "100.1", "100.0", "U1", 7L, "broadcast reply");
        verify(threadRepository).upsertOnMessage(WORKSPACE, "C1", "100.0", "100.1", 7L);
    }

    @Test
    void messageChanged_editsStoredText() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(WORKSPACE));
        // Row-exists happy path: the scoped UPDATE touches 1 row, so editMessage returns without re-ingesting.
        when(messageRepository.applyEdit(eq(WORKSPACE), eq("C1"), eq("100.1"), eq("edited body"), any(Instant.class)))
                .thenReturn(1);

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_changed","channel":"C1","ts":"200.9",
                  "message":{"ts":"100.1","user":"U1","text":"edited body"}}}
                """));

        verify(messageRepository)
                .applyEdit(eq(WORKSPACE), eq("C1"), eq("100.1"), eq("edited body"), any(Instant.class));
        // Row existed → no re-ingest.
        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void messageChanged_beforeBaseInsert_isDurable() {
        stubActiveConsentedChannel();
        // The base insert has not arrived: the scoped UPDATE touches 0 rows and the row is genuinely absent, so the
        // edited body is routed back through the full consent stack (re-ingest), storing the EDITED text durably.
        when(messageRepository.applyEdit(eq(WORKSPACE), eq("C1"), eq("100.1"), eq("edited body"), any(Instant.class)))
                .thenReturn(0);
        when(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(WORKSPACE, "C1", "100.1"))
                .thenReturn(false);
        when(messageRepository.insertIfAbsent(eq(WORKSPACE), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_changed","channel":"C1","ts":"200.9",
                  "message":{"ts":"100.1","user":"U1","thread_ts":"100.0","text":"edited body"}}}
                """));

        // Re-ingest ran through the gated path and persisted the EDITED text under the resolved workspace + member id.
        verify(messageRepository).insertIfAbsent(WORKSPACE, "T1", "C1", "100.1", "100.0", "U1", 7L, "edited body");
    }

    @Test
    void messageChanged_afterTombstone_staysTombstoned() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(WORKSPACE));
        // The row exists but is tombstoned: applyEdit no-ops (deleted_at IS NULL guard) AND existsBy is true, so the
        // edit must NOT re-ingest — a tombstone is never resurrected by a late edit.
        when(messageRepository.applyEdit(eq(WORKSPACE), eq("C1"), eq("100.1"), any(), any(Instant.class)))
                .thenReturn(0);
        when(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(WORKSPACE, "C1", "100.1"))
                .thenReturn(true);

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_changed","channel":"C1","ts":"200.9",
                  "message":{"ts":"100.1","user":"U1","text":"edited body"}}}
                """));

        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void flagOff_editMessage_doesNothing() {
        handler = handlerWithIngestDisabled();

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_changed","channel":"C1","ts":"200.9",
                  "message":{"ts":"100.1","user":"U1","text":"edited body"}}}
                """));

        // Kill switch off: the edit path short-circuits before resolving the tenant or touching persistence.
        verify(workspaceResolver, never()).resolveWorkspaceId(any());
        verify(messageRepository, never()).applyEdit(anyLong(), any(), any(), any(), any(Instant.class));
        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void flagOff_tombstone_doesNothing() {
        handler = handlerWithIngestDisabled();

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_deleted","channel":"C1","ts":"200.9","deleted_ts":"100.1"}}
                """));

        verify(workspaceResolver, never()).resolveWorkspaceId(any());
        verify(messageRepository, never()).tombstone(anyLong(), any(), any(), any(), any(Instant.class));
    }

    /**
     * The tombstone path applies in every consent state (a delete must erase a copy stored while ACTIVE even if the
     * channel is now PAUSED); only the forward-only announcement window gates it. No participant gate.
     */
    private void stubAnnouncedChannelForTombstone() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(WORKSPACE));
        when(monitoredChannelRepository.findConsentAnnouncedAt(WORKSPACE, "C1"))
                .thenReturn(Optional.of(ANNOUNCED_BEFORE));
    }

    @Test
    void messageDeleted_tombstonesOnDeletedTs_notEventTs() {
        stubAnnouncedChannelForTombstone();

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_deleted","channel":"C1","ts":"200.9","deleted_ts":"100.1"}}
                """));

        // Keyed on the deleted message's ts (100.1), carrying the team id for the durable upsert.
        verify(messageRepository).tombstone(eq(WORKSPACE), eq("T1"), eq("C1"), eq("100.1"), any(Instant.class));
    }

    @Test
    void messageDeleted_fallsBackToPreviousMessageTs() {
        stubAnnouncedChannelForTombstone();

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_deleted","channel":"C1","ts":"200.9","previous_message":{"ts":"150.2"}}}
                """));

        verify(messageRepository).tombstone(eq(WORKSPACE), eq("T1"), eq("C1"), eq("150.2"), any(Instant.class));
    }

    @Test
    void messageDeleted_onPausedChannel_stillTombstones() {
        // PAUSED retains rows stored while the channel was ACTIVE; the author's delete must erase our copy anyway,
        // so the tombstone deliberately ignores the consent state (the gate mock is never consulted).
        stubAnnouncedChannelForTombstone();

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_deleted","channel":"C1","ts":"200.9","deleted_ts":"100.1"}}
                """));

        verify(messageRepository).tombstone(eq(WORKSPACE), eq("T1"), eq("C1"), eq("100.1"), any(Instant.class));
    }

    @Test
    void messageDeleted_onNeverAnnouncedChannel_tombstonesNothing() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(WORKSPACE));
        when(monitoredChannelRepository.findConsentAnnouncedAt(WORKSPACE, "C1")).thenReturn(Optional.empty());

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"message_deleted","channel":"C1","ts":"200.9","deleted_ts":"100.1"}}
                """));

        // Nothing was ever stored on a never-announced channel; no contentless tombstone row is created.
        verify(messageRepository, never()).tombstone(anyLong(), any(), any(), any(), any(Instant.class));
    }

    @Test
    void fileShareWithComment_isStored() {
        // The upload's accompanying text is conversational content; the file itself is never persisted (only the
        // text column exists).
        stubActiveConsentedChannel();

        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"file_share","channel_type":"channel","channel":"C1","user":"U1","ts":"100.1",
                  "text":"here is the failing trace - race?","files":[{"id":"F1"}]}}
                """));

        verify(messageRepository)
                .insertIfAbsent(
                        eq(WORKSPACE),
                        eq("T1"),
                        eq("C1"),
                        eq("100.1"),
                        eq(null),
                        eq("U1"),
                        eq(7L),
                        eq("here is the failing trace - race?"));
    }

    @Test
    void fileShareWithoutComment_storesNothing() {
        handler.onMessage(natsMessage("""
                {"type":"event_callback","team_id":"T1","event":{
                  "type":"message","subtype":"file_share","channel_type":"channel","channel":"C1","user":"U1","ts":"100.1",
                  "text":"","files":[{"id":"F1"}]}}
                """));

        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }
}
