package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.identity.SlackRosterIdentityResolver;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Ingest only after active source consent and the announcement boundary; person opt-outs still apply.
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackIngestService {

    private static final Logger log = LoggerFactory.getLogger(SlackIngestService.class);

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackMonitoredChannelRepository monitoredChannelRepository;
    private final SlackChannelConsentGate consentGate;
    private final SlackParticipantConsentGate participantConsentGate;
    private final SlackMessageRepository messageRepository;
    private final SlackThreadRepository threadRepository;
    private final SlackRosterIdentityResolver identityResolver;
    private final ConversationFeedbackErasure conversationFeedbackErasure;

    /**
     * Fleet-wide capability flag, available by default, bound from
     * {@code hephaestus.integration.slack.conversation-ingest.enabled}. The first fail-closed layer in front of the
     * per-channel consent gate and the per-person firewall: while {@code false}, channel/group messages are never
     * ingested at all and the conversation review and feedback downstream stays completely dormant.
     */
    private final boolean conversationIngestEnabled;

    public SlackIngestService(
            SlackWorkspaceResolver workspaceResolver,
            SlackMonitoredChannelRepository monitoredChannelRepository,
            SlackChannelConsentGate consentGate,
            SlackParticipantConsentGate participantConsentGate,
            SlackMessageRepository messageRepository,
            SlackThreadRepository threadRepository,
            SlackRosterIdentityResolver identityResolver,
            ConversationFeedbackErasure conversationFeedbackErasure,
            @Value("${hephaestus.integration.slack.conversation-ingest.enabled:true}")
                    boolean conversationIngestEnabled) {
        this.workspaceResolver = workspaceResolver;
        this.monitoredChannelRepository = monitoredChannelRepository;
        this.consentGate = consentGate;
        this.participantConsentGate = participantConsentGate;
        this.messageRepository = messageRepository;
        this.threadRepository = threadRepository;
        this.identityResolver = identityResolver;
        this.conversationFeedbackErasure = conversationFeedbackErasure;
        this.conversationIngestEnabled = conversationIngestEnabled;
    }

    @Transactional
    public void ingestChannelMessage(
            String teamId,
            String channelId,
            String ts,
            @Nullable String threadTs,
            @Nullable String authorSlackUserId,
            @Nullable String text) {
        ingestChannelMessageInternal(teamId, channelId, ts, threadTs, authorSlackUserId, text);
    }

    private void ingestChannelMessageInternal(
            String teamId,
            String channelId,
            String ts,
            @Nullable String threadTs,
            @Nullable String authorSlackUserId,
            @Nullable String text) {
        if (!conversationIngestEnabled) {
            return;
        }
        if (channelId.isEmpty() || ts.isEmpty()) {
            return;
        }
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            return;
        }
        long workspaceId = workspaceOpt.get();

        // Consent gate (single authority): only ACTIVE channels flow content. Fails closed on an absent row.
        // Channels enter the allow-list via bot member_joined_channel registration or the admin directory —
        // never as a side effect of message traffic.
        if (!consentGate.ingestAllowed(workspaceId, channelId)) {
            return;
        }

        // Forward-only invariant: on an ACTIVE channel, ingestion is bounded to messages that arrived strictly after
        // the consent announcement was posted (consent_announced_at). Pre-announcement history never enters — a
        // channel activated today does not retroactively ingest the backlog people wrote before they were told. An
        // ACTIVE channel is always stamped at activation, so a missing stamp fails closed (store nothing).
        Instant announcedAt = monitoredChannelRepository
                .findConsentAnnouncedAt(workspaceId, channelId)
                .orElse(null);
        if (announcedAt == null || !isAfterAnnouncement(ts, announcedAt)) {
            return;
        }

        // Person firewall: an opted-out individual is never stored, even on an ACTIVE channel with the capability on.
        // Deny-if-opted-out / allow-if-absent, keyed on the author's Slack id (an unauthored/blank sender has no
        // person to gate, so it proceeds — it stamps no member id and unions nothing into participant_account_ids).
        if (authorSlackUserId != null
                && !authorSlackUserId.isBlank()
                && !participantConsentGate.ingestionAllowed(workspaceId, authorSlackUserId)) {
            return;
        }

        // Firewall stamp: an account in this workspace. Native human lookup failure refuses the message.
        Long authorAccountId = (authorSlackUserId == null || authorSlackUserId.isBlank())
                ? null
                : identityResolver
                        .humanAccountId(workspaceId, teamId, authorSlackUserId)
                        .orElse(null);

        if (authorSlackUserId != null && !authorSlackUserId.isBlank() && authorAccountId == null) return;

        int inserted = messageRepository.insertIfAbsent(
                workspaceId, teamId, channelId, ts, threadTs, authorSlackUserId, authorAccountId, text);
        if (inserted > 0) {
            // thread_ts := the reply's parent, or the message's own ts for a thread root.
            String aggregateThreadTs = (threadTs == null || threadTs.isBlank()) ? ts : threadTs;
            threadRepository.upsertOnMessage(workspaceId, channelId, aggregateThreadTs, ts, authorAccountId);
            log.debug("Ingested Slack message workspace={} channel={} ts={}", workspaceId, channelId, ts);
        }
    }

    /** Forward-only comparison: only a message strictly after the announcement enters. Unparseable fails closed. */
    private static boolean isAfterAnnouncement(String ts, Instant announcedAt) {
        return SlackTs.isAfter(ts, announcedAt);
    }

    /**
     * Slack {@code message_deleted}: tombstone the stored message (stamp {@code deleted_at}, null its text). Keyed
     * on the deleted message's {@code ts} (the caller passes {@code deleted_ts}, falling back to
     * {@code previous_message.ts}) — NOT the tombstone event's own {@code ts}.
     */
    @Transactional
    public void tombstoneMessage(String teamId, String channelId, String deletedTs) {
        if (!conversationIngestEnabled || channelId.isEmpty() || deletedTs.isEmpty()) {
            return;
        }
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            return;
        }
        long workspaceId = workspaceOpt.get();
        // Deliberately NOT gated on ACTIVE consent: a message stored while the channel was ACTIVE still exists
        // during PAUSED, and the author's deletion must erase our copy in every state. The forward-only window
        // below still applies (a pre-announcement message was never stored), and the durable upsert both
        // tombstones an ingested row AND blocks a later out-of-order base insert from resurrecting the content.
        // No participant-firewall check: a tombstone is contentless (text NULL).
        Instant announcedAt = monitoredChannelRepository
                .findConsentAnnouncedAt(workspaceId, channelId)
                .orElse(null);
        if (announcedAt == null || !isAfterAnnouncement(deletedTs, announcedAt)) {
            return;
        }
        messageRepository.tombstone(workspaceId, teamId, channelId, deletedTs, Instant.now());
    }

    /**
     * Slack {@code message_changed}: replace the stored text with the edited body ({@code event.message}) and
     * stamp {@code edited_at}. Durable against out-of-order delivery, at parity with the tombstone: when the base
     * insert has not yet arrived (a NAK-redelivered reorder), the edited body is routed back through the fully-gated
     * {@link #ingestChannelMessage} so it is stored durably AND consent-safely (consent + forward-only watermark +
     * participant firewall + identity stamp), rather than lost. A no-op when the row is tombstoned (never
     * resurrected) or holds a later edit. Honors the channel-ingest kill switch, like ingest and tombstone.
     */
    @Transactional
    public void editMessage(
            String teamId,
            String channelId,
            String ts,
            @Nullable String threadTs,
            @Nullable String authorSlackUserId,
            @Nullable String text,
            @Nullable String editedTs) {
        // Without the edit's own time a change cannot be ordered: Slack also sends message_changed for a new reply
        // or an unfurl, which edit nothing.
        Long editedMicros = SlackTs.toEpochMicros(editedTs);
        if (!conversationIngestEnabled || channelId.isEmpty() || ts.isEmpty() || editedMicros == null) {
            return;
        }
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            return;
        }
        long workspaceId = workspaceOpt.get();
        Instant editedAt = Instant.ofEpochSecond(editedMicros / 1_000_000L, (editedMicros % 1_000_000L) * 1_000L);

        // Normal case: the base row exists -> scoped UPDATE swaps in the edited text. The deleted_at IS NULL guard in
        // applyEdit means a delete-then-edit reorder no-ops (updated == 0, row present) and never resurrects a
        // tombstone.
        int updated = messageRepository.applyEdit(workspaceId, channelId, ts, text, editedAt);
        if (updated > 0) {
            return;
        }
        // Edit raced ahead of a NAK-redelivered base insert (row absent). existsBy distinguishes a TOMBSTONED row
        // (updated == 0 but present -> skip, no resurrection) from a genuinely-absent one (re-ingest).
        if (!messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, channelId, ts)) {
            ingestChannelMessageInternal(teamId, channelId, ts, threadTs, authorSlackUserId, text);
            // Stamp edited_at (insertIfAbsent does not): a row born from message_changed genuinely is an edited
            // message,
            // so the projector's `edited` flag reads true. Idempotent second write on the now-present row.
            messageRepository.applyEdit(workspaceId, channelId, ts, text, editedAt);
        }
    }

    /**
     * Channel erasure: flip the channel to {@code REVOKED} so ingestion stops immediately, and promptly delete the
     * channel's ingested content — its {@code slack_message} rows and its {@code slack_thread} aggregates (which
     * hold the {@code participant_account_ids} personal data) — rather than waiting for the 180-day retention sweep,
     * which covers messages only. Transactional and idempotent (an unknown or already-erased channel deletes 0 rows).
     *
     * <p>Derived CONVERSATION feedback is hard-deleted too (GDPR Art. 17 for the derived copies, not inert-by-gate):
     * The port resolves chat.thread items by their workspace and source id before removing product rows.
     * Results for other item kinds and workspaces remain intact.
     */
    @Transactional
    public void eraseChannel(long workspaceId, String channelId) {
        List<Long> threadIds = threadRepository.findIdsByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
        monitoredChannelRepository.revokeConsent(workspaceId, channelId);
        conversationFeedbackErasure.eraseForThreads(workspaceId, threadIds);
        messageRepository.deleteByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
        threadRepository.deleteByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
    }
}
