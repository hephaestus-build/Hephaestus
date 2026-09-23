package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.SlackConversationInfo;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write-side authority for the per-channel Slack consent lifecycle (read side: {@code SlackChannelConsentGate}).
 * Owns the guarded state machine, posts the in-channel consent announcement on activation, stamps
 * {@code consent_announced_at} (the forward-only ingestion boundary), erases the channel's raw + derived data on
 * revocation, and writes an immutable audit row for every transition.
 *
 * <pre>
 *   PENDING ──activate──▶ ACTIVE ⇄ PAUSED
 *      │                    │        │
 *      └────────── any → REVOKED (stop + ERASE; register again → PENDING) ──────────┘
 * </pre>
 * <ul>
 *   <li>{@code PENDING → ACTIVE}: post the announcement ({@link SlackConsentBlocks}) and stamp
 *       {@code consent_announced_at = now()} — {@code SlackIngestService} only stores messages whose {@code ts} is
 *       strictly after this stamp.</li>
 *   <li>{@code ACTIVE ⇄ PAUSED}: stop / resume ingestion, keeping stored data. Resuming an already-announced
 *       channel does NOT re-announce or re-stamp.</li>
 *   <li>{@code * → REVOKED}: stops ingestion and erases raw messages, thread aggregates, and derived
 *       {@code chat.conversation_thread} observations/feedback ({@link SlackIngestService#eraseChannel}). A later
 *       registration starts a fresh {@code PENDING} setup with a new announcement boundary.</li>
 * </ul>
 * A same-state PATCH is an idempotent no-op (no side effect, no audit row). Every other edge not drawn above is a
 * violation → {@link SlackChannelConsentViolationException} (409).
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackChannelConsentService {

    private static final Logger log = LoggerFactory.getLogger(SlackChannelConsentService.class);

    private final SlackMonitoredChannelRepository monitoredChannelRepository;
    private final SlackChannelConsentEventRepository consentEventRepository;
    private final SlackParticipantConsentRepository participantConsentRepository;
    private final SlackIngestService ingestService;
    private final SlackMessageService slackMessageService;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;
    private final SlackHephaestusUiLinks uiLinks;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * A channel just reached {@code ACTIVE}. Signals that its history sync may now run — every other
     * integration kicks a sync when a resource is added, and without this Slack's would wait for the nightly
     * cron. Delivered {@code AFTER_COMMIT} so the consumer reads the ACTIVE row rather than the old one.
     */
    public record SlackChannelActivatedEvent(long workspaceId, String slackChannelId) {}

    public SlackChannelConsentService(
            SlackMonitoredChannelRepository monitoredChannelRepository,
            SlackChannelConsentEventRepository consentEventRepository,
            SlackParticipantConsentRepository participantConsentRepository,
            SlackIngestService ingestService,
            SlackMessageService slackMessageService,
            ConnectionService connectionService,
            UserRepository userRepository,
            SlackHephaestusUiLinks uiLinks,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher eventPublisher) {
        this.monitoredChannelRepository = monitoredChannelRepository;
        this.consentEventRepository = consentEventRepository;
        this.participantConsentRepository = participantConsentRepository;
        this.ingestService = ingestService;
        this.slackMessageService = slackMessageService;
        this.connectionService = connectionService;
        this.userRepository = userRepository;
        this.uiLinks = uiLinks;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
    }

    /** All allow-listed channels for the workspace with their consent state + the workspace-wide opt-out count. */
    @Transactional(readOnly = true)
    public List<SlackMonitoredChannelDTO> listChannels(long workspaceId) {
        long optedOutMemberCount = participantConsentRepository.countByWorkspaceIdAndIngestionOptedOutTrue(workspaceId);
        return monitoredChannelRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(channel -> SlackMonitoredChannelDTO.from(channel, optedOutMemberCount))
                .toList();
    }

    /** The immutable consent-transition audit trail of one channel (oldest first). */
    @Transactional(readOnly = true)
    public List<SlackChannelConsentEventDTO> listConsentEvents(long workspaceId, String slackChannelId) {
        // 404 if the channel is not allow-listed in this workspace (also enforces workspace isolation).
        monitoredChannelRepository
                .findByWorkspaceIdAndSlackChannelId(workspaceId, slackChannelId)
                .orElseThrow(() -> new EntityNotFoundException("Slack channel", slackChannelId));
        return consentEventRepository
                .findByWorkspaceIdAndSlackChannelIdOrderByCreatedAtAscIdAsc(workspaceId, slackChannelId)
                .stream()
                .map(SlackChannelConsentEventDTO::from)
                .toList();
    }

    /**
     * Allow-list a channel (idempotent on the natural key). Creates a {@code PENDING} row on first registration,
     * refreshes the channel name on repeated registration, and turns a previously {@code REVOKED} row back into
     * {@code PENDING} so the admin can set it up again after erasure.
     *
     * <p>The remote Slack lookups (name resolution, public-channel join) run <em>before</em> the transaction so no
     * pooled connection is held across rate-limited network I/O; the DB writes then commit in one short transaction.
     *
     * @return the registration outcome, carrying whether a new row was created (201) or an existing one returned (200)
     */
    public RegistrationOutcome register(long workspaceId, String slackChannelId, @Nullable String channelName) {
        var existing = monitoredChannelRepository.findByWorkspaceIdAndSlackChannelId(workspaceId, slackChannelId);
        if (existing.isPresent()) {
            String resolvedName = resolveChannelName(workspaceId, slackChannelId, channelName);
            return applyExistingRegistration(workspaceId, slackChannelId, resolvedName);
        }

        String teamId = connectionService
                .findSlackNotificationConfig(workspaceId)
                .map(ConnectionConfig.SlackConfig::teamId)
                .filter(t -> t != null && !t.isBlank())
                // Registration needs an ACTIVE Slack connection to know which Slack team this channel belongs to.
                .orElseThrow(() -> new EntityNotFoundException("Slack connection", Long.toString(workspaceId)));

        var lookup = slackMessageService.lookupConversation(workspaceId, slackChannelId);
        SlackConversationInfo info = lookup == null ? null : lookup.orElse(null);
        if (info != null && !info.privateChannel() && !info.member() && !info.archived()) {
            slackMessageService.joinPublicChannel(workspaceId, slackChannelId);
        }
        String resolvedName = resolveName(channelName, info);

        return inTx(() -> {
            // Re-check inside the tx: a concurrent registration (e.g. the bot's own member_joined_channel from the
            // join above) may have created the row since the pre-tx read.
            if (monitoredChannelRepository
                    .findByWorkspaceIdAndSlackChannelId(workspaceId, slackChannelId)
                    .isPresent()) {
                return applyExistingRegistrationInTx(workspaceId, slackChannelId, resolvedName);
            }
            SlackMonitoredChannel channel = new SlackMonitoredChannel();
            channel.setWorkspaceId(workspaceId);
            channel.setSlackTeamId(teamId);
            channel.setSlackChannelId(slackChannelId);
            channel.setChannelName(resolvedName);
            channel.setConsentState(ConsentState.PENDING);
            SlackMonitoredChannel saved = monitoredChannelRepository.save(channel);
            return new RegistrationOutcome(toDTO(workspaceId, saved), true);
        });
    }

    private RegistrationOutcome applyExistingRegistration(
            long workspaceId, String slackChannelId, @Nullable String resolvedName) {
        return inTx(() -> applyExistingRegistrationInTx(workspaceId, slackChannelId, resolvedName));
    }

    /** DB-only tail of a registration that found an existing row; must run inside the caller's transaction. */
    private RegistrationOutcome applyExistingRegistrationInTx(
            long workspaceId, String slackChannelId, @Nullable String resolvedName) {
        SlackMonitoredChannel channel = monitoredChannelRepository
                .findByWorkspaceIdAndSlackChannelId(workspaceId, slackChannelId)
                .orElseThrow(() -> new EntityNotFoundException("Slack channel", slackChannelId));
        boolean changed = updateChannelName(channel, resolvedName);
        if (channel.getConsentState() == ConsentState.REVOKED) {
            ConsentState from = channel.getConsentState();
            channel.setConsentState(ConsentState.PENDING);
            channel.setConsentAnnouncedAt(null);
            channel.setLastSyncError(null);
            monitoredChannelRepository.save(channel);
            recordAudit(workspaceId, channel.getSlackChannelId(), from, ConsentState.PENDING, "channel re-added");
            return new RegistrationOutcome(toDTO(workspaceId, channel), false);
        }
        if (changed) {
            monitoredChannelRepository.save(channel);
        }
        return new RegistrationOutcome(toDTO(workspaceId, channel), false);
    }

    /**
     * Transition a channel to {@code target}, running the guarded state machine: validate the edge, perform the side
     * effect (announce on activate; erase on revoke), and append the immutable audit row. A same-state request is an
     * idempotent no-op.
     *
     * @return the channel's DTO after the transition
     * @throws EntityNotFoundException            if the channel is not allow-listed in this workspace (404)
     * @throws SlackChannelConsentViolationException if the edge is not permitted (409)
     */
    public SlackMonitoredChannelDTO transition(
            long workspaceId, String slackChannelId, ConsentState target, @Nullable String reason) {
        SlackMonitoredChannel preRead = monitoredChannelRepository
                .findByWorkspaceIdAndSlackChannelId(workspaceId, slackChannelId)
                .orElseThrow(() -> new EntityNotFoundException("Slack channel", slackChannelId));

        if (preRead.getConsentState() == target) {
            return toDTO(workspaceId, preRead);
        }
        requireAllowed(preRead.getConsentState(), target, slackChannelId);

        // First activation posts the in-channel announcement BEFORE the transaction (network I/O never holds a
        // pooled connection) and before ACTIVE is reachable: if the post fails, the exception propagates and the
        // channel stays non-ACTIVE (fails closed — an ACTIVE channel always has an announcement). If the tx below
        // fails instead, the announcement stands but the channel never activated; a retry re-announces (benign).
        Instant announcedAt = (target == ConsentState.ACTIVE && preRead.getConsentAnnouncedAt() == null)
                ? postAnnouncementAndStamp(workspaceId, slackChannelId)
                : null;

        return inTx(() -> {
            SlackMonitoredChannel channel = monitoredChannelRepository
                    .findByWorkspaceIdAndSlackChannelId(workspaceId, slackChannelId)
                    .orElseThrow(() -> new EntityNotFoundException("Slack channel", slackChannelId));
            ConsentState from = channel.getConsentState();
            if (from == target) {
                return toDTO(workspaceId, channel); // raced with an identical transition
            }
            requireAllowed(from, target, slackChannelId);

            switch (target) {
                case ACTIVE -> {
                    if (channel.getConsentAnnouncedAt() == null && announcedAt != null) {
                        channel.setConsentAnnouncedAt(announcedAt);
                    }
                    if (from == ConsentState.PAUSED) {
                        // The paused gap was written under "monitoring is off" and must never be backfilled: move
                        // the reconciliation watermark to the resume instant so the nightly history sync's fetch
                        // floor excludes everything sent while paused.
                        channel.setLastHistorySyncedTs(SlackTs.ofInstant(Instant.now()));
                    }
                    channel.setConsentState(ConsentState.ACTIVE);
                    monitoredChannelRepository.save(channel);
                    // Published, not called: the kick's async pass must read the committed ACTIVE row, and
                    // the admin's PATCH must not block on Slack round trips. Consumed after commit by
                    // SlackDataSyncScheduler#onChannelConsentActivated.
                    eventPublisher.publishEvent(new SlackChannelActivatedEvent(workspaceId, slackChannelId));
                }
                case PAUSED -> {
                    channel.setConsentState(ConsentState.PAUSED);
                    monitoredChannelRepository.save(channel);
                }
                case REVOKED -> {
                    // eraseChannel persists REVOKED via bulk UPDATE; reflect it on the loaded entity for the DTO.
                    ingestService.eraseChannel(workspaceId, slackChannelId);
                    channel.setConsentState(ConsentState.REVOKED);
                    channel.setLastSyncError(null);
                }
                case PENDING ->
                    throw new SlackChannelConsentViolationException(
                            "PENDING is only reachable via discovery/registration, not a consent transition.");
            }

            recordAudit(workspaceId, slackChannelId, from, target, reason);
            return toDTO(workspaceId, channel);
        });
    }

    /** Post the activation announcement (remote, outside any tx) and return the boundary stamp for it. */
    private Instant postAnnouncementAndStamp(long workspaceId, String slackChannelId) {
        postAnnouncement(workspaceId, slackChannelId);
        return Instant.now();
    }

    /** Run {@code work} in one short DB-only transaction (remote Slack calls happen before it, never inside). */
    private <T> T inTx(Supplier<T> work) {
        T result = transactionTemplate.execute(status -> work.get());
        if (result == null) {
            throw new IllegalStateException("Transactional Slack consent operation returned no result");
        }
        return result;
    }

    private void postAnnouncement(long workspaceId, String channelId) {
        try {
            slackMessageService.sendForWorkspace(
                    workspaceId,
                    channelId,
                    SlackConsentBlocks.activationNotice(),
                    SlackConsentBlocks.activationFallbackText());
        } catch (SlackSendException e) {
            log.warn(
                    "Slack consent announcement failed to post: workspaceId={}, channelId={}, error={}",
                    workspaceId,
                    channelId,
                    e.slackError());
            throw e;
        }
    }

    private void recordAudit(
            long workspaceId, String slackChannelId, ConsentState from, ConsentState to, @Nullable String reason) {
        Long actorUserId = userRepository.getCurrentUser().map(User::getId).orElse(null);
        consentEventRepository.save(
                new SlackChannelConsentEvent(workspaceId, slackChannelId, from, to, actorUserId, reason));
    }

    /** Transition guard. The same-state case ({@code from == target}) is already handled as an idempotent no-op by callers. */
    private static void requireAllowed(ConsentState from, ConsentState target, String slackChannelId) {
        boolean allowed =
                switch (from) {
                    case PENDING -> target == ConsentState.ACTIVE || target == ConsentState.REVOKED;
                    case ACTIVE -> target == ConsentState.PAUSED || target == ConsentState.REVOKED;
                    case PAUSED -> target == ConsentState.ACTIVE || target == ConsentState.REVOKED;
                    case REVOKED -> false; // only register() may start a fresh setup
                };
        if (!allowed) {
            throw new SlackChannelConsentViolationException("Illegal Slack channel consent transition " + from + " → "
                    + target + " for channel " + slackChannelId);
        }
    }

    private @Nullable String resolveChannelName(
            long workspaceId, String slackChannelId, @Nullable String fallbackName) {
        var info = slackMessageService.lookupConversation(workspaceId, slackChannelId);
        return resolveName(fallbackName, info == null ? null : info.orElse(null));
    }

    private static @Nullable String resolveName(@Nullable String fallbackName, @Nullable SlackConversationInfo info) {
        if (info != null && info.channelName() != null && !info.channelName().isBlank()) {
            return info.channelName();
        }
        return fallbackName == null || fallbackName.isBlank() ? null : fallbackName;
    }

    private static boolean updateChannelName(SlackMonitoredChannel channel, @Nullable String channelName) {
        if (channelName == null || channelName.isBlank() || channelName.equals(channel.getChannelName())) {
            return false;
        }
        channel.setChannelName(channelName);
        return true;
    }

    private SlackMonitoredChannelDTO toDTO(long workspaceId, SlackMonitoredChannel channel) {
        long optedOutMemberCount = participantConsentRepository.countByWorkspaceIdAndIngestionOptedOutTrue(workspaceId);
        return SlackMonitoredChannelDTO.from(channel, optedOutMemberCount);
    }

    /** Whether {@link #register} created a new allow-list row (201) or returned an existing one (200). */
    public record RegistrationOutcome(SlackMonitoredChannelDTO channel, boolean created) {}

    // --- platform-event wrappers (Slack channel-lifecycle handlers) ---
    //
    // Unlike transition(), these are guard-first and NEVER throw: a Slack channel-lifecycle event (the bot removed,
    // a channel archived/deleted/renamed) arrives asynchronously off the NATS consumer with no admin actor and no
    // opportunity to surface a 404/409 to anyone. Each wrapper re-reads the row inside its own short transaction,
    // no-ops on an absent row or an edge that does not apply, and otherwise performs the same side effect + audit
    // write as the corresponding transition() edge would.

    /**
     * {@code channel_archive} / {@code channel_left} (and the {@code group_*} equivalents): stop ingestion but
     * keep stored data, exactly like {@code ACTIVE → PAUSED} via {@link #transition}. No-op unless the channel is
     * currently {@code ACTIVE}.
     */
    public void pauseForPlatformEvent(long workspaceId, String slackChannelId, String reason) {
        runInTx(() -> {
            SlackMonitoredChannel channel = monitoredChannelRepository
                    .findByWorkspaceIdAndSlackChannelId(workspaceId, slackChannelId)
                    .orElse(null);
            if (channel == null || channel.getConsentState() != ConsentState.ACTIVE) {
                return;
            }
            channel.setConsentState(ConsentState.PAUSED);
            monitoredChannelRepository.save(channel);
            recordAudit(workspaceId, slackChannelId, ConsentState.ACTIVE, ConsentState.PAUSED, reason);
        });
    }

    /**
     * {@code channel_deleted}: the channel and its Slack-side data are gone, so erase our copy too — the same
     * single choke point ({@link SlackIngestService#eraseChannel}) as {@code * → REVOKED} via {@link #transition}.
     * No-op if the channel was never allow-listed or is already {@code REVOKED}.
     */
    public void revokeForPlatformEvent(long workspaceId, String slackChannelId, String reason) {
        runInTx(() -> {
            SlackMonitoredChannel channel = monitoredChannelRepository
                    .findByWorkspaceIdAndSlackChannelId(workspaceId, slackChannelId)
                    .orElse(null);
            if (channel == null || channel.getConsentState() == ConsentState.REVOKED) {
                return;
            }
            ConsentState from = channel.getConsentState();
            ingestService.eraseChannel(workspaceId, slackChannelId);
            recordAudit(workspaceId, slackChannelId, from, ConsentState.REVOKED, reason);
        });
    }

    /**
     * {@code channel_rename} / {@code group_rename}: heal the stale {@code channel_name} so the admin UI and audit
     * trail stop showing a name Slack no longer uses. Not a consent transition — no audit row. No-op for a blank
     * name or a channel that is not allow-listed (the bulk update simply touches 0 rows).
     */
    public void renameChannel(long workspaceId, String slackChannelId, @Nullable String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return;
        }
        monitoredChannelRepository.updateChannelName(workspaceId, slackChannelId, channelName);
    }

    /** Run a DB-only side effect in one short transaction; never returns a value (platform-event wrappers only). */
    private void runInTx(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }
}
