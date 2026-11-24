package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a person's Slack consent decision (the write side of {@link SlackParticipantConsentGate}). Keyed by
 * {@code (workspace, slack_user_id)} and member-optional by design: an unlinked user's opt-out is still stored so it
 * applies once they later link.
 *
 * <p>Channel-message controls change ingestion consent only. Research participation belongs to the
 * native-account consent ledger. The opt-out and erasure of collected channel data commit together.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackParticipantConsentService {

    private final SlackParticipantConsentRepository participantConsentRepository;

    private final PrivacyJournal journal;
    private final SlackPersonErasureService erasure;
    private final PersonDataWriteFence writeFence;
    private final SlackConversationIdentityResolver identities;

    public SlackParticipantConsentService(
            SlackParticipantConsentRepository participantConsentRepository,
            PrivacyJournal journal,
            SlackPersonErasureService erasure,
            PersonDataWriteFence writeFence,
            SlackConversationIdentityResolver identities) {
        this.participantConsentRepository = participantConsentRepository;
        this.journal = journal;
        this.erasure = erasure;
        this.writeFence = writeFence;
        this.identities = identities;
    }

    /**
     * Persist an ingestion-only opt-out made at {@code decidedAt}. This excludes the person's monitored-channel
     * messages but deliberately does not change research participation.
     *
     * @return whether this action stored an opt-out and erased the associated data
     */
    @Transactional
    public boolean recordChannelMessageOptOut(
            long workspaceId, String slackTeamId, String slackUserId, @Nullable Long accountId, Instant decidedAt) {
        return optOut(workspaceId, slackTeamId, slackUserId, accountId, decidedAt, null);
    }

    /** Commits the opt-out and queues its confirmation for the provider's short transport acknowledgement. */
    @Transactional
    public void requestChannelMessageOptOut(
            long workspaceId,
            String slackTeamId,
            String slackUserId,
            @Nullable Long accountId,
            Instant decidedAt,
            Consumer<UUID> queueConfirmation) {
        optOut(workspaceId, slackTeamId, slackUserId, accountId, decidedAt, queueConfirmation);
    }

    private boolean optOut(
            long workspaceId,
            String slackTeamId,
            String slackUserId,
            @Nullable Long accountId,
            Instant decidedAt,
            @Nullable Consumer<UUID> queueConfirmation) {
        boolean recorded = record(workspaceId, slackTeamId, slackUserId, true, decidedAt);
        if (queueConfirmation == null && slackUserId != null && !slackUserId.isBlank()) {
            journal.confirmPending(
                    PrivacyJournalKind.SLACK_PARTICIPANT_WITHDRAWN,
                    Map.of(
                            "workspaceId", workspaceId,
                            "slackTeamId", slackTeamId,
                            "slackUserId", slackUserId,
                            "scope", "MONITORED_CHANNELS"));
        }
        if (!recorded) return false;

        // Ingestion may have linked the author's account before it released the consent lock.
        Long targetAccount = accountId == null
                ? identities
                        .resolveAccountId(workspaceId, slackTeamId, slackUserId)
                        .orElse(null)
                : accountId;
        erasure.erasePerson(workspaceId, targetAccount, slackUserId);
        Map<String, Object> references = Map.of(
                "workspaceId", workspaceId,
                "slackTeamId", slackTeamId,
                "slackUserId", slackUserId,
                "scope", "MONITORED_CHANNELS",
                "decidedAt", decidedAt);
        if (queueConfirmation == null) {
            journal.record(PrivacyJournalKind.SLACK_PARTICIPANT_WITHDRAWN, references);
        } else {
            UUID entryId = UUID.randomUUID();
            journal.recordDeferred(entryId, PrivacyJournalKind.SLACK_PARTICIPANT_WITHDRAWN, references);
            queueConfirmation.accept(entryId);
        }
        return true;
    }

    /** @return whether this action stored an opt-in */
    @Transactional
    public boolean recordChannelMessageOptIn(
            long workspaceId, String slackTeamId, String slackUserId, Instant decidedAt) {
        return record(workspaceId, slackTeamId, slackUserId, false, decidedAt);
    }

    /** Whether the stored choice lets this person's channel messages in; no choice lets them in. */
    @Transactional(readOnly = true)
    public boolean channelMessagesAllowed(long workspaceId, String slackUserId) {
        return !participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                workspaceId, slackUserId);
    }

    @Transactional(readOnly = true)
    public boolean isCurrentWithdrawal(long workspaceId, String userId, Instant decidedAt) {
        return !participantConsentRepository.isPersonProcessingSuppressed(workspaceId, userId)
                && participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndDecidedAtAndIngestionOptedOutTrue(
                        workspaceId, userId, decidedAt);
    }

    private boolean record(long workspaceId, String teamId, String slackUserId, boolean optedOut, Instant decidedAt) {
        if (slackUserId == null || slackUserId.isBlank()) {
            return false;
        }
        // Ingestion takes the same decision lock before it can resolve and write a native identity.
        participantConsentRepository.lockDecision(workspaceId, slackUserId);
        if (!writeFence.holdForWrite(List.of(identities.nativeSubject(teamId, slackUserId)))) return false;
        return participantConsentRepository.decide(workspaceId, slackUserId, optedOut, decidedAt) > 0;
    }
}
