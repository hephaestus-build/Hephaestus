package de.tum.cit.aet.hephaestus.integration.slack.adapter;

import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorSourceEvidence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the Slack store's tombstone of a mentor question. The store tombstones a message only in a monitored channel
 * after its consent announcement, so a message that it never stored proves nothing either way.
 */
@Component
class SlackMentorSourceEvidence implements MentorSourceEvidence {
    private final SlackMessageRepository messages;

    SlackMentorSourceEvidence(SlackMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deleted(long workspaceId, String containerId, String nativeMessageId) {
        return messages.shareTombstoned(workspaceId, containerId, nativeMessageId)
                .orElse(false);
    }
}
