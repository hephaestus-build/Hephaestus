package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a person's Slack consent decision (the write side of {@link SlackParticipantConsentGate}). Keyed by
 * {@code (workspace, slack_user_id)} and member-optional by design: an unlinked user's opt-out is still stored so it
 * applies once they later link.
 *
 * <p>Channel-message controls change ingestion consent only. Research participation belongs to the
 * native-account consent ledger. Erasure of collected channel data is performed by the caller through
 * {@code SlackPersonErasureService}.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackParticipantConsentService {

    private final SlackParticipantConsentRepository participantConsentRepository;

    private final PrivacyJournal journal;

    public SlackParticipantConsentService(
            SlackParticipantConsentRepository participantConsentRepository, PrivacyJournal journal) {
        this.participantConsentRepository = participantConsentRepository;
        this.journal = journal;
    }

    /**
     * Persist an ingestion-only opt-out made at {@code decidedAt}. This excludes the person's monitored-channel
     * messages but deliberately does not change research participation.
     *
     * @return whether the opt-out is the stored choice; false when a later choice is stored or the user is blank
     */
    @Transactional
    public boolean recordChannelMessageOptOut(
            long workspaceId, String slackTeamId, String slackUserId, Instant decidedAt) {
        if (!record(workspaceId, slackUserId, true, decidedAt)) return false;
        journal.record(
                PrivacyJournalKind.SLACK_PARTICIPANT_WITHDRAWN,
                Map.of(
                        "workspaceId",
                        workspaceId,
                        "slackTeamId",
                        slackTeamId,
                        "slackUserId",
                        slackUserId,
                        "scope",
                        "MONITORED_CHANNELS",
                        "decidedAt",
                        decidedAt));
        return true;
    }

    /** @return whether the opt-in is the stored choice; false when a later choice is stored or the user is blank */
    @Transactional
    public boolean recordChannelMessageOptIn(long workspaceId, String slackUserId, Instant decidedAt) {
        return record(workspaceId, slackUserId, false, decidedAt);
    }

    /** Whether the stored choice lets this person's channel messages in; no choice lets them in. */
    @Transactional(readOnly = true)
    public boolean channelMessagesAllowed(long workspaceId, String slackUserId) {
        return !participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                workspaceId, slackUserId);
    }

    private boolean record(long workspaceId, String slackUserId, boolean optedOut, Instant decidedAt) {
        if (slackUserId == null || slackUserId.isBlank()) {
            return false;
        }
        return participantConsentRepository.decide(workspaceId, slackUserId, optedOut, decidedAt) > 0;
    }
}
