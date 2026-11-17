package de.tum.cit.aet.hephaestus.integration.slack.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredSlackMessageChanges;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Uses the repository directly, so erasure recovery works while Slack is turned off. */
@Component
class SlackMessageRecoveryAdapter implements RestoredSlackMessageChanges {
    private final SlackMessageRepository messages;

    SlackMessageRecoveryAdapter(SlackMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    @Transactional
    public boolean eraseAgain(long workspaceId, long messageId, boolean deleted) {
        return (deleted
                        ? messages.tombstoneAgain(workspaceId, messageId, Instant.now())
                        : messages.eraseEditedText(workspaceId, messageId))
                > 0;
    }
}
