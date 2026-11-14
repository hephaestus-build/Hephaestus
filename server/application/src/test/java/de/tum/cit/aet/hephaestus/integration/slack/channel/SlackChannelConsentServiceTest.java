package de.tum.cit.aet.hephaestus.integration.slack.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.event.AgentContextChangedEvent;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * State-machine unit tests for {@link SlackChannelConsentService}. Deterministic (all collaborators mocked): each
 * test locks one edge of the mentoring-only machine and its side effect, and would fail if the guard or the side
 * effect were removed.
 */
class SlackChannelConsentServiceTest extends BaseUnitTest {

    private static final long WS = 7L;
    private static final String CHANNEL = "C1";

    @Mock
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private SlackChannelConsentEventRepository consentEventRepository;

    @Mock
    private SlackParticipantConsentRepository participantConsentRepository;

    @Mock
    private SlackIngestService ingestService;

    @Mock
    private SlackMessageService slackMessageService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private SlackChannelJoinActions joins;

    @Mock
    private SlackConsentActions announcements;

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @BeforeEach
    void transactionalCollaborators() {
        lenient()
                .when(workspaceResolver.whileConnected(anyLong(), anyString(), any()))
                .thenAnswer(inv -> Optional.of(((Supplier<?>) inv.getArgument(2)).get()));
        lenient().when(consentEventRepository.save(any())).thenAnswer(inv -> {
            SlackChannelConsentEvent event = inv.getArgument(0);
            ReflectionTestUtils.setField(event, "id", 42L);
            return event;
        });
    }

    private SlackChannelConsentService service() {
        return new SlackChannelConsentService(
                monitoredChannelRepository,
                consentEventRepository,
                participantConsentRepository,
                ingestService,
                slackMessageService,
                connectionService,
                inlineTransactionTemplate(),
                eventPublisher,
                joins,
                announcements,
                workspaceResolver);
    }

    /** Captures what the service publishes; the AFTER_COMMIT hop itself is Spring's, not ours, to test. */
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private static final SlackChannelConsentService.SlackChannelActivatedEvent ACTIVATION_KICK =
            new SlackChannelConsentService.SlackChannelActivatedEvent(WS, CHANNEL);

    /** A TransactionTemplate over a no-op manager: callbacks run inline so unit tests need no real tx. */
    private static TransactionTemplate inlineTransactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        });
    }

    private SlackMonitoredChannel channel(ConsentState state, @Nullable Instant announcedAt) {
        SlackMonitoredChannel c = new SlackMonitoredChannel();
        c.setId(1L);
        c.setWorkspaceId(WS);
        c.setSlackTeamId("T1");
        c.setSlackChannelId(CHANNEL);
        c.setConsentState(state);
        c.setConsentAnnouncedAt(announcedAt);
        return c;
    }

    private void stubChannel(SlackMonitoredChannel c) {
        lenient()
                .when(monitoredChannelRepository.findByWorkspaceIdAndSlackChannelId(WS, CHANNEL))
                .thenReturn(Optional.of(c));
        lenient().when(monitoredChannelRepository.findForUpdate(WS, CHANNEL)).thenReturn(Optional.of(c));
    }

    private void stubActor(long actorId) {
        var jwt = Jwt.withTokenValue("unit-test")
                .header("alg", "none")
                .subject(Long.toString(actorId))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activationQueuesAnnouncementBeforeEnablingIngestion() {
        SlackMonitoredChannel c = channel(ConsentState.PENDING, null);
        stubChannel(c);
        stubActor(5L);

        SlackMonitoredChannelDTO dto = service().transition(WS, CHANNEL, ConsentState.ACTIVE, "pilot go");

        assertThat(dto.consentState()).isEqualTo(ConsentState.ACTIVATING);
        assertThat(c.getConsentAnnouncedAt()).isNull();
        assertThat(c.getConsentAnnouncementId()).isNotNull();
        verifyNoInteractions(slackMessageService);
        verify(eventPublisher, never()).publishEvent(ACTIVATION_KICK);
        ArgumentCaptor<SlackChannelConsentEvent> audit = ArgumentCaptor.captor();
        verify(consentEventRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isEqualTo(ConsentState.PENDING);
        assertThat(audit.getValue().getToState()).isEqualTo(ConsentState.ACTIVATING);
        assertThat(audit.getValue().getActorAccountId()).isEqualTo(5L);
        assertThat(audit.getValue().getReason()).isEqualTo("pilot go");
        verify(announcements).enqueue(c, audit.getValue(), false);
        // The first activation of a pending channel is the one that may need the public-channel join.
        verify(joins).enqueue(c, 42L);
    }

    // Requesting activation cannot start history sync before the outbox confirms the announcement.
    @Test
    void requestedActivationDoesNotPublishHistorySyncKick() {
        stubChannel(channel(ConsentState.PENDING, null));
        stubActor(5L);

        service().transition(WS, CHANNEL, ConsentState.ACTIVE, null);

        verify(eventPublisher, never()).publishEvent(ACTIVATION_KICK);
    }

    /** Every edge into ACTIVE kicks, including a resume — the sync is idempotent and watermark-paced. */
    @Test
    void pausedToActive_alsoPublishesTheKick() {
        stubChannel(channel(ConsentState.PAUSED, Instant.parse("2020-01-01T00:00:00Z")));
        stubActor(5L);

        service().transition(WS, CHANNEL, ConsentState.ACTIVE, null);

        verify(eventPublisher).publishEvent(ACTIVATION_KICK);
    }

    /** No transition into ACTIVE, no kick: pausing must not schedule work against a channel it just stopped. */
    @Test
    void transitionAwayFromActive_publishesNoKick() {
        stubChannel(channel(ConsentState.ACTIVE, Instant.parse("2020-01-01T00:00:00Z")));
        stubActor(5L);

        service().transition(WS, CHANNEL, ConsentState.PAUSED, null);

        verify(eventPublisher, never()).publishEvent(ACTIVATION_KICK);
        // Running Heph turns drop the channel's threads from their context folders.
        verify(eventPublisher).publishEvent(new AgentContextChangedEvent(WS));
    }

    /** A same-state PATCH is an idempotent no-op — it must not re-fire the kick on every admin double-click. */
    @Test
    void redundantActivation_publishesNoKick() {
        stubChannel(channel(ConsentState.ACTIVE, Instant.parse("2020-01-01T00:00:00Z")));

        service().transition(WS, CHANNEL, ConsentState.ACTIVE, null);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void pausedToActive_doesNotReAnnounceOrRestamp() {
        Instant originalAnnouncedAt = Instant.parse("2020-01-01T00:00:00Z");
        SlackMonitoredChannel c = channel(ConsentState.PAUSED, originalAnnouncedAt);
        stubChannel(c);
        stubActor(5L);

        service().transition(WS, CHANNEL, ConsentState.ACTIVE, null);

        // Resuming an already-announced channel keeps the original boundary and never re-posts.
        verifyNoInteractions(slackMessageService);
        assertThat(c.getConsentAnnouncedAt()).isEqualTo(originalAnnouncedAt);
        assertThat(c.getConsentState()).isEqualTo(ConsentState.ACTIVE);
        verify(consentEventRepository).save(any());
    }

    @Test
    void activeToPaused_stopsIngestKeepsData_noEraseNoAnnounce() {
        SlackMonitoredChannel c = channel(ConsentState.ACTIVE, Instant.parse("2020-01-01T00:00:00Z"));
        stubChannel(c);
        stubActor(5L);

        SlackMonitoredChannelDTO dto = service().transition(WS, CHANNEL, ConsentState.PAUSED, null);

        assertThat(c.getConsentState()).isEqualTo(ConsentState.PAUSED);
        assertThat(dto.consentState()).isEqualTo(ConsentState.PAUSED);
        verify(monitoredChannelRepository).save(c);
        verifyNoInteractions(ingestService, slackMessageService);
    }

    private enum Outcome {
        /** {@code from == target}: idempotent no-op, no side effect, no audit. */
        NOOP,
        /** A permitted edge: performs its side effect and writes an audit row. */
        LEGAL,
        /** A forbidden edge: 409, no mutation, no audit. */
        ILLEGAL,
    }

    static Stream<Arguments> edgeMatrix() {
        return Stream.of(
                Arguments.of(ConsentState.PENDING, ConsentState.PENDING, Outcome.ILLEGAL),
                Arguments.of(ConsentState.PENDING, ConsentState.ACTIVE, Outcome.LEGAL),
                Arguments.of(ConsentState.PENDING, ConsentState.PAUSED, Outcome.ILLEGAL),
                Arguments.of(ConsentState.PENDING, ConsentState.REVOKED, Outcome.LEGAL),
                Arguments.of(ConsentState.ACTIVE, ConsentState.PENDING, Outcome.ILLEGAL),
                Arguments.of(ConsentState.ACTIVE, ConsentState.ACTIVE, Outcome.NOOP),
                Arguments.of(ConsentState.ACTIVE, ConsentState.PAUSED, Outcome.LEGAL),
                Arguments.of(ConsentState.ACTIVE, ConsentState.REVOKED, Outcome.LEGAL),
                Arguments.of(ConsentState.PAUSED, ConsentState.PENDING, Outcome.ILLEGAL),
                Arguments.of(ConsentState.PAUSED, ConsentState.ACTIVE, Outcome.LEGAL),
                Arguments.of(ConsentState.PAUSED, ConsentState.PAUSED, Outcome.NOOP),
                Arguments.of(ConsentState.PAUSED, ConsentState.REVOKED, Outcome.LEGAL),
                Arguments.of(ConsentState.REVOKED, ConsentState.PENDING, Outcome.ILLEGAL),
                Arguments.of(ConsentState.REVOKED, ConsentState.ACTIVE, Outcome.ILLEGAL),
                Arguments.of(ConsentState.REVOKED, ConsentState.PAUSED, Outcome.ILLEGAL),
                Arguments.of(ConsentState.REVOKED, ConsentState.REVOKED, Outcome.NOOP),
                Arguments.of(ConsentState.ACTIVATING, ConsentState.ACTIVE, Outcome.NOOP),
                Arguments.of(ConsentState.ACTIVATING, ConsentState.PAUSED, Outcome.ILLEGAL),
                Arguments.of(ConsentState.ACTIVATING, ConsentState.REVOKED, Outcome.LEGAL),
                Arguments.of(ConsentState.FAILED, ConsentState.PAUSED, Outcome.ILLEGAL),
                Arguments.of(ConsentState.FAILED, ConsentState.REVOKED, Outcome.LEGAL));
    }

    // Each requested outcome either changes state, is idempotent, or is refused before mutation.
    @ParameterizedTest(name = "{0} → {1} is {2}")
    @MethodSource("edgeMatrix")
    void requestedConsentTransitions(ConsentState from, ConsentState target, Outcome outcome) {
        SlackMonitoredChannel c =
                channel(from, from == ConsentState.PENDING ? null : Instant.parse("2020-01-01T00:00:00Z"));
        stubChannel(c);
        SlackChannelConsentService svc = service();

        switch (outcome) {
            case NOOP -> {
                SlackMonitoredChannelDTO dto = svc.transition(WS, CHANNEL, target, null);
                assertThat(dto.consentState()).isEqualTo(from);
                verify(monitoredChannelRepository, never()).save(any());
                verifyNoInteractions(ingestService, consentEventRepository, slackMessageService);
            }
            case ILLEGAL -> {
                assertThatThrownBy(() -> svc.transition(WS, CHANNEL, target, null))
                        .isInstanceOf(SlackChannelConsentViolationException.class);
                // Guard rejects before any mutation, side effect, or audit write.
                verify(monitoredChannelRepository, never()).save(any());
                verifyNoInteractions(ingestService, consentEventRepository);
            }
            case LEGAL -> {
                stubActor(5L);
                svc.transition(WS, CHANNEL, target, "reason");
                // Erasure fires iff the target is REVOKED.
                if (target == ConsentState.REVOKED) {
                    // Single erasure choke point drives the raw + derived deletion; a revoke never re-announces.
                    verify(ingestService).eraseChannel(WS, CHANNEL);
                    verifyNoInteractions(slackMessageService);
                } else {
                    verifyNoInteractions(ingestService);
                }
                ArgumentCaptor<SlackChannelConsentEvent> captor =
                        ArgumentCaptor.forClass(SlackChannelConsentEvent.class);
                verify(consentEventRepository).save(captor.capture());
                assertThat(captor.getValue().getFromState()).isEqualTo(from);
                assertThat(captor.getValue().getToState())
                        .isEqualTo(
                                from == ConsentState.PENDING && target == ConsentState.ACTIVE
                                        ? ConsentState.ACTIVATING
                                        : target);
            }
        }
    }

    @Test
    void failedActivationCanQueueAnExplicitRetry() {
        var c = channel(ConsentState.FAILED, null);
        var marker = UUID.randomUUID();
        c.setConsentAnnouncementId(marker);
        stubChannel(c);
        service().transition(WS, CHANNEL, ConsentState.ACTIVE, "retry");
        assertThat(c.getConsentState()).isEqualTo(ConsentState.ACTIVATING);
        assertThat(c.getConsentAnnouncementId()).isEqualTo(marker);
        verify(announcements).enqueue(eq(c), any(), eq(true));
        // A retry of a failed announcement never joins: the bot is already in the channel or was removed on purpose.
        verifyNoInteractions(joins, slackMessageService);
        assertThat(c.getIngestAfter()).isNull();
    }

    @Test
    void channelNotFound_throwsEntityNotFound() {
        when(monitoredChannelRepository.findByWorkspaceIdAndSlackChannelId(WS, CHANNEL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().transition(WS, CHANNEL, ConsentState.ACTIVE, null))
                .isInstanceOf(EntityNotFoundException.class);

        verifyNoInteractions(ingestService, slackMessageService, consentEventRepository);
    }

    @Test
    void register_new_landsInPending_created() {
        when(monitoredChannelRepository.findByWorkspaceIdAndSlackChannelId(WS, CHANNEL))
                .thenReturn(Optional.empty());
        when(connectionService.findSlackConfig(WS))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig("T1", null, null, Set.of())));
        when(monitoredChannelRepository.save(any())).thenAnswer(inv -> {
            SlackMonitoredChannel saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        SlackChannelConsentService.RegistrationOutcome outcome = service().register(WS, CHANNEL, "general");

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.channel().consentState()).isEqualTo(ConsentState.PENDING);
        verify(monitoredChannelRepository).save(any());
    }

    @Test
    void register_existing_backfillsName_returnsNotCreated() {
        // Idempotent on the natural key: a second registration returns the existing row (created=false), backfilling a
        // previously-unknown channelName.
        SlackMonitoredChannel existing = channel(ConsentState.PENDING, null);
        existing.setChannelName(null);
        stubChannel(existing);

        SlackChannelConsentService.RegistrationOutcome outcome = service().register(WS, CHANNEL, "general");

        assertThat(outcome.created()).isFalse();
        assertThat(existing.getChannelName()).isEqualTo("general");
        verify(monitoredChannelRepository).save(existing);
        // The existing branch returns before the connection lookup.
        verifyNoInteractions(connectionService);
    }

    @Test
    void register_revokedChannel_setsUpAgainAsPendingAndClearsAnnouncement() {
        @Nullable Instant announcedAt = Instant.parse("2020-01-01T00:00:00Z");
        SlackMonitoredChannel existing = channel(ConsentState.REVOKED, announcedAt);
        existing.setChannelName("old-name");
        existing.setConsentAnnouncementId(UUID.randomUUID());
        existing.setIngestAfter(announcedAt);
        stubChannel(existing);

        SlackChannelConsentService.RegistrationOutcome outcome = service().register(WS, CHANNEL, "new-name");

        assertThat(outcome.created()).isFalse();
        assertThat(existing.getConsentState()).isEqualTo(ConsentState.PENDING);
        assertThat(existing.getConsentAnnouncedAt()).isNull();
        assertThat(existing.getIngestAfter()).isNull();
        assertThat(existing.getConsentAnnouncementId()).isNull();
        assertThat(existing.getChannelName()).isEqualTo("new-name");
        assertThat(outcome.channel().consentState()).isEqualTo(ConsentState.PENDING);
        verify(monitoredChannelRepository).save(existing);
        // Re-adding a revoked channel is a fresh pending channel: it queues its join again.
        verify(joins).enqueue(existing, 42L);
    }

    @Test
    void register_noSlackConnection_throwsNotFound() {
        // A purely-admin registration of an unseen channel needs an ACTIVE Slack connection to know the team id; its
        // absence is a 404.
        when(monitoredChannelRepository.findByWorkspaceIdAndSlackChannelId(WS, CHANNEL))
                .thenReturn(Optional.empty());
        when(connectionService.findSlackConfig(WS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().register(WS, CHANNEL, "general"))
                .isInstanceOf(EntityNotFoundException.class);

        verify(monitoredChannelRepository, never()).save(any());
    }

    // Platform-event wrappers run on the inbox consumer with no actor: guard-first and no-op tolerant.

    @ParameterizedTest(name = "pauseForPlatformEvent on {0} is a no-op")
    @EnumSource(
            value = ConsentState.class,
            names = {"PENDING", "PAUSED", "REVOKED"})
    void pauseForPlatformEvent_nonActive_isANoOp(ConsentState state) {
        SlackMonitoredChannel c = channel(state, null);
        stubChannel(c);

        service().pauseForPlatformEvent(WS, CHANNEL, "bot removed from channel");

        verify(monitoredChannelRepository, never()).save(any());
        verify(consentEventRepository, never()).save(any());
    }

    @Test
    void pauseForPlatformEvent_active_pausesAndAudits() {
        SlackMonitoredChannel c = channel(ConsentState.ACTIVE, Instant.parse("2026-07-01T00:00:00Z"));
        stubChannel(c);

        service().pauseForPlatformEvent(WS, CHANNEL, "channel archived");

        assertThat(c.getConsentState()).isEqualTo(ConsentState.PAUSED);
        verify(monitoredChannelRepository).save(c);
        ArgumentCaptor<SlackChannelConsentEvent> captor = ArgumentCaptor.forClass(SlackChannelConsentEvent.class);
        verify(consentEventRepository).save(captor.capture());
        assertThat(captor.getValue().getFromState()).isEqualTo(ConsentState.ACTIVE);
        assertThat(captor.getValue().getToState()).isEqualTo(ConsentState.PAUSED);
        assertThat(captor.getValue().getReason()).isEqualTo("channel archived");
    }

    @Test
    void channelRemovalDuringActivationFailsItAndCancelsPendingAnnouncement() {
        var c = channel(ConsentState.ACTIVATING, null);
        stubChannel(c);
        service().pauseForPlatformEvent(WS, CHANNEL, "bot removed");
        assertThat(c.getConsentState()).isEqualTo(ConsentState.FAILED);
        verify(announcements).cancel(WS, CHANNEL);
        verify(eventPublisher, never()).publishEvent(ACTIVATION_KICK);
    }

    @Test
    void pauseForPlatformEvent_absentChannel_isANoOp() {
        when(monitoredChannelRepository.findForUpdate(WS, CHANNEL)).thenReturn(Optional.empty());

        service().pauseForPlatformEvent(WS, CHANNEL, "bot removed from channel");

        verify(consentEventRepository, never()).save(any());
    }

    @ParameterizedTest(name = "revokeForPlatformEvent from {0} erases and audits")
    @EnumSource(
            value = ConsentState.class,
            names = {"PENDING", "ACTIVE", "PAUSED"})
    void revokeForPlatformEvent_erasesAndAudits(ConsentState from) {
        SlackMonitoredChannel c = channel(from, null);
        stubChannel(c);

        service().revokeForPlatformEvent(WS, CHANNEL, "channel deleted in Slack");

        verify(ingestService).eraseChannel(WS, CHANNEL);
        ArgumentCaptor<SlackChannelConsentEvent> captor = ArgumentCaptor.forClass(SlackChannelConsentEvent.class);
        verify(consentEventRepository).save(captor.capture());
        assertThat(captor.getValue().getFromState()).isEqualTo(from);
        assertThat(captor.getValue().getToState()).isEqualTo(ConsentState.REVOKED);
    }

    @Test
    void revokeForPlatformEvent_alreadyRevoked_isANoOp() {
        SlackMonitoredChannel c = channel(ConsentState.REVOKED, null);
        stubChannel(c);

        service().revokeForPlatformEvent(WS, CHANNEL, "channel deleted in Slack");

        verify(ingestService, never()).eraseChannel(anyLong(), any());
        verify(consentEventRepository, never()).save(any());
    }

    @Test
    void renameChannel_updatesTheStoredName_withoutAnAuditRow() {
        service().renameChannel(WS, CHANNEL, "renamed");

        verify(monitoredChannelRepository).updateChannelName(WS, CHANNEL, "renamed");
        verify(consentEventRepository, never()).save(any());
    }

    @Test
    void renameChannel_blankName_isANoOp() {
        service().renameChannel(WS, CHANNEL, "  ");

        verify(monitoredChannelRepository, never()).updateChannelName(anyLong(), any(), any());
    }

    @Test
    void pausedToActive_startsANewMonitoringPeriodAtOneInstant_soThePausedGapIsNeverBackfilled() {
        // Messages sent while paused were written under "monitoring is off". Resume moves the live floor and the
        // reconciliation watermark to the same instant, so neither live ingest nor the nightly sync admits them.
        Instant pausedPeriodFloor = Instant.parse("2026-07-01T00:00:00Z");
        SlackMonitoredChannel c = channel(ConsentState.PAUSED, pausedPeriodFloor);
        c.setIngestAfter(pausedPeriodFloor);
        c.setLastHistorySyncedTs(SlackTs.ofInstant(Instant.parse("2026-07-02T00:00:00Z")));
        stubChannel(c);
        stubActor(5L);

        service().transition(WS, CHANNEL, ConsentState.ACTIVE, null);

        assertThat(c.getConsentState()).isEqualTo(ConsentState.ACTIVE);
        Instant ingestAfter = Objects.requireNonNull(c.getIngestAfter());
        assertThat(ingestAfter).isAfter(pausedPeriodFloor);
        assertThat(c.getLastHistorySyncedTs()).isEqualTo(SlackTs.ofInstant(ingestAfter));
        assertThat(c.getConsentAnnouncedAt()).isEqualTo(pausedPeriodFloor);
        verifyNoInteractions(joins);
    }
}
