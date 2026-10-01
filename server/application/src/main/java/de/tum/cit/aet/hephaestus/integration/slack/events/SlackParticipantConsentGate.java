package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authority on whether an <em>individual</em> may have their Slack messages ingested — the person layer
 * of the two-layer consent model. Built once so the person-firewall rule lives in exactly one place and cannot
 * diverge between the ingest write-path and any later projector.
 *
 * <p><strong>Deny-if-opted-out, allow-if-absent.</strong> A person who explicitly opted out
 * ({@code ingestion_opted_out = true}) is never ingested; a person with no consent row has made no opt-out and is
 * allowed unless a permanent person-erasure control denies processing. This is the correct default for the mentoring purpose (legitimate interest with an individual opt-out),
 * and it composes with the two fail-closed layers above it in {@link SlackIngestService}: ingestion happens iff the
 * capability flag is on AND the channel is {@code ACTIVE} AND the author is NOT ingestion-opted-out.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackParticipantConsentGate {

    private final SlackParticipantConsentRepository participantConsentRepository;

    public SlackParticipantConsentGate(SlackParticipantConsentRepository participantConsentRepository) {
        this.participantConsentRepository = participantConsentRepository;
    }

    /**
     * @return {@code true} iff this Slack user is allowed to be ingested in this workspace — i.e. has NOT opted out.
     *     An absent consent row means no opt-out; a permanent person-erasure control still denies ingestion.
     */
    @Transactional(readOnly = true)
    public boolean ingestionAllowed(long workspaceId, String slackUserId) {
        return !participantConsentRepository.isPersonProcessingSuppressed(workspaceId, slackUserId)
                && !participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, slackUserId);
    }
}
