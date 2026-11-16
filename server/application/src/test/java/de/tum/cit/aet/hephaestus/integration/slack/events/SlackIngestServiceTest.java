package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.identity.SlackRosterIdentityResolver;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingest write-path unit tests. Deterministic: the repositories and the identity resolver are mocked, so
 * these lock the consent gate (only ACTIVE channels flow content), the author→member firewall stamp, and the
 * thread upsert bookkeeping. Content from a channel that is not ACTIVE is never persisted.
 */
class SlackIngestServiceTest extends BaseUnitTest {

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
    private SlackRosterIdentityResolver identityResolver;

    @Mock
    private ConversationFeedbackErasure conversationFeedbackErasure;

    /**
     * The start of the current monitoring period ({@code ingest_after}), well before the test messages (ts "100.1"),
     * so the live floor passes. Live ingest reads only this floor, never the reconciliation watermark.
     */
    private static final Instant INGEST_AFTER = Instant.ofEpochSecond(1);

    private SlackIngestService service;

    /** Construct the service with the channel-ingest capability flag in the given state. */
    private SlackIngestService serviceWithFlag(boolean conversationIngestEnabled) {
        return new SlackIngestService(
                workspaceResolver,
                monitoredChannelRepository,
                consentGate,
                participantConsentGate,
                messageRepository,
                threadRepository,
                identityResolver,
                conversationFeedbackErasure,
                mock(ProviderEventStore.class),
                conversationIngestEnabled,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    @BeforeEach
    void setUp() {
        // Every case drives the flag EXPLICITLY (never the @Value default): existing behavior is exercised with the
        // capability enabled here, and the disabled kill-switch is covered explicitly by flagOff_* below.
        service = serviceWithFlag(true);
    }

    @Test
    void flagOff_channelMessage_isCompletelyDormant_storesNothingAndDoesNotEvenDiscover() {
        // Capability kill-switch (fail-closed layer 1), driven explicitly OFF. Even a well-formed message on what
        // would be an ACTIVE channel must not touch any collaborator: no workspace resolution, no discovery row, no
        // consent check, no store. Remove the flag gate and this test fails (resolveWorkspaceId would be called
        // before the consent gate could apply).
        SlackIngestService disabled = serviceWithFlag(false);

        disabled.ingestChannelMessage("T1", "C1", "100.1", "99.0", "U1", "hi");

        verifyNoInteractions(
                workspaceResolver,
                monitoredChannelRepository,
                consentGate,
                participantConsentGate,
                messageRepository,
                threadRepository,
                identityResolver);
    }

    @Test
    void unknownTeam_persistsNothing() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.empty());

        service.ingestChannelMessage("T1", "C1", "100.1", null, "U1", "hi");

        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonActiveChannel_failsClosed_storesNothing() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(false);

        service.ingestChannelMessage("T1", "C1", "100.1", null, "U1", "hi");

        // The message and thread are NOT persisted until consent is ACTIVE.
        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(threadRepository, never()).upsertOnMessage(anyLong(), any(), any(), any(), any());
    }

    @Test
    void lateProviderMessageFromBeforeTheMonitoringPeriodFloorIsNotStored() {
        // A failed activation's gap, or a pause, leaves messages that predate the current monitoring period. A late
        // provider delivery of one must not enter, even though the channel is ACTIVE again.
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.of(Instant.ofEpochSecond(200)));

        service.ingestChannelMessage("T1", "C1", "150.000000", null, "U1", "written while monitoring was off");

        verifyNoInteractions(participantConsentGate);
        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void delayedReplyToOldThreadAfterTheFloorIsStoredWhileReconciliationHasAdvancedPastIt() {
        // The reconciliation watermark advances to the newest message of a synced window (here far past the reply).
        // A reply to an old thread can still arrive late with a ts between the monitoring-period floor and that
        // watermark. It was posted while monitoring was on, so it is stored. Live ingest cannot read the watermark:
        // the repository has no such read, and this test stubs only the floor.
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.of(INGEST_AFTER));
        when(participantConsentGate.ingestionAllowed(7L, "U1")).thenReturn(true);
        when(identityResolver.displayName(7L, "T1", "U1")).thenReturn(Optional.of("A person"));
        when(identityResolver.humanAccountId(7L, "T1", "U1", "A person")).thenReturn(Optional.of(42L));
        when(messageRepository.insertIfAbsent(7L, "T1", "C1", "300.000000", "100.000000", "U1", 42L, "late reply"))
                .thenReturn(1);

        service.ingestChannelMessage("T1", "C1", "300.000000", "100.000000", "U1", "late reply");

        verify(messageRepository).insertIfAbsent(7L, "T1", "C1", "300.000000", "100.000000", "U1", 42L, "late reply");
        verify(threadRepository).upsertOnMessage(7L, "C1", "100.000000", "300.000000", 42L);
    }

    @Test
    void activeChannel_authorOptedOut_isNotStored_evenOnActiveChannel() {
        // The person firewall: capability ON + channel ACTIVE, but this individual opted out of ingestion → nothing
        // is stored.
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.of(INGEST_AFTER));
        when(participantConsentGate.ingestionAllowed(7L, "U1")).thenReturn(false);

        service.ingestChannelMessage("T1", "C1", "100.1", "99.0", "U1", "hi");

        // The store does not happen; the identity resolver is never even consulted.
        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(threadRepository, never()).upsertOnMessage(anyLong(), any(), any(), any(), any());
    }

    @Test
    void activeChannel_messageBeforeIngestFloor_isNotStored_andPersonGateNotEvenConsulted() {
        // Forward-only invariant: on an ACTIVE channel, a message whose ts predates ingest_after is never stored —
        // history from before the current monitoring period stays out. The check fires BEFORE the person firewall,
        // so the participant gate is not even consulted.
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        // The floor is AFTER the message ts (100.1) → the message predates the monitoring period and must not enter.
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.of(Instant.ofEpochSecond(200)));

        service.ingestChannelMessage("T1", "C1", "100.1", null, "U1", "old");

        verifyNoInteractions(participantConsentGate);
        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void activeChannel_messageTsEqualsIngestFloor_isNotStored_forwardOnlyIsStrict() {
        // Forward-only is STRICT (ts > floor), not ts >= floor. A message posted at the exact instant of the floor
        // (ts == ingest_after) is outside the monitoring period and must NOT be stored.
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        // Floor stamped at epoch-second 100; the message ts "100.0" parses to the same fractional epoch.
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.of(Instant.ofEpochSecond(100)));

        service.ingestChannelMessage("T1", "C1", "100.0", null, "U1", "at the announcement instant");

        verifyNoInteractions(participantConsentGate);
        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void activeChannel_missingIngestFloor_failsClosed() {
        // An ACTIVE channel always has a floor from activation or resume; a missing floor is an inconsistency and must
        // fail closed (store nothing) rather than fall through to ingest unbounded history.
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.empty());

        service.ingestChannelMessage("T1", "C1", "100.1", null, "U1", "hi");

        verifyNoInteractions(participantConsentGate);
        verify(messageRepository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void activeChannel_stampsResolvedMemberIdAndUpsertsThread() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.of(INGEST_AFTER));
        when(participantConsentGate.ingestionAllowed(7L, "U1")).thenReturn(true);
        when(identityResolver.displayName(7L, "T1", "U1")).thenReturn(Optional.of("A person"));
        when(identityResolver.humanAccountId(7L, "T1", "U1", "A person")).thenReturn(Optional.of(42L));
        when(messageRepository.insertIfAbsent(7L, "T1", "C1", "100.1", "99.0", "U1", 42L, "hi"))
                .thenReturn(1);

        service.ingestChannelMessage("T1", "C1", "100.1", "99.0", "U1", "hi");

        verify(messageRepository).insertIfAbsent(7L, "T1", "C1", "100.1", "99.0", "U1", 42L, "hi");
        // thread_ts is the reply's parent (99.0); the new message's own ts (100.1) advances the window.
        verify(threadRepository).upsertOnMessage(7L, "C1", "99.0", "100.1", 42L);
    }

    @Test
    void activeChannel_rootMessageUsesOwnTsAsThreadTs() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.of(INGEST_AFTER));
        when(participantConsentGate.ingestionAllowed(7L, "U1")).thenReturn(true);
        when(identityResolver.displayName(7L, "T1", "U1")).thenReturn(Optional.of("A person"));
        when(identityResolver.humanAccountId(7L, "T1", "U1", "A person")).thenReturn(Optional.of(42L));
        when(messageRepository.insertIfAbsent(
                        eq(7L), eq("T1"), eq("C1"), eq("100.1"), isNull(), eq("U1"), eq(42L), eq("hi")))
                .thenReturn(1);

        service.ingestChannelMessage("T1", "C1", "100.1", null, "U1", "hi");

        // A root message uses its own timestamp and retains its roster account.
        verify(threadRepository).upsertOnMessage(7L, "C1", "100.1", "100.1", 42L);
    }

    @Test
    void eraseChannel_revokesConsentErasesDerivedFeedbackAndDeletesStoredContentPromptly() {
        // The channel's thread ids are collected first (the derived practice rows are keyed by slack_thread.id).
        when(threadRepository.findIdsByWorkspaceIdAndSlackChannelId(7L, "C1")).thenReturn(List.of(11L, 22L));

        service.eraseChannel(7L, "C1");

        // Consent flip stops future ingestion + drops threads out of the ACTIVE-consent projectors…
        verify(monitoredChannelRepository).revokeConsent(7L, "C1");
        // …the derived chat.thread results/feedback are hard-deleted through the practices port
        // (true erasure, not inert-by-gate)…
        verify(conversationFeedbackErasure).eraseForThreads(7L, List.of(11L, 22L));
        // …and the channel's raw content (messages) + thread aggregates are deleted now, not left for the sweep.
        verify(messageRepository).deleteByWorkspaceIdAndSlackChannelId(7L, "C1");
        verify(threadRepository).deleteByWorkspaceIdAndSlackChannelId(7L, "C1");
    }

    @Test
    void duplicateMessage_doesNotBumpThread() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(consentGate.ingestAllowed(7L, "C1")).thenReturn(true);
        when(monitoredChannelRepository.readIngestAfter(7L, "C1")).thenReturn(Optional.of(INGEST_AFTER));
        when(participantConsentGate.ingestionAllowed(7L, "U1")).thenReturn(true);
        when(identityResolver.displayName(7L, "T1", "U1")).thenReturn(Optional.of("A person"));
        when(identityResolver.humanAccountId(7L, "T1", "U1", "A person")).thenReturn(Optional.of(42L));
        when(messageRepository.insertIfAbsent(7L, "T1", "C1", "100.1", null, "U1", 42L, "hi"))
                .thenReturn(0);

        service.ingestChannelMessage("T1", "C1", "100.1", null, "U1", "hi");

        // Idempotent retry: the message already existed (0 inserted) → no double thread bookkeeping.
        verify(threadRepository, never()).upsertOnMessage(anyLong(), any(), any(), any(), any());
    }
}
