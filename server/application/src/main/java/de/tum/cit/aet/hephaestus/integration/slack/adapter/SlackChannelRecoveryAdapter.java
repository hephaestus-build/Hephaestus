package de.tum.cit.aet.hephaestus.integration.slack.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredSlackChannelErasures;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelErasure;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The channel erasure that an admin's revoke and Slack's deletion run, again; it works while Slack is turned off. */
@Component
@ConditionalOnServerRole
class SlackChannelRecoveryAdapter implements RestoredSlackChannelErasures {
    private final SlackChannelErasure erasure;

    SlackChannelRecoveryAdapter(SlackChannelErasure erasure) {
        this.erasure = erasure;
    }

    @Override
    public void eraseAgain(long workspaceId, String channelId, Instant erasedAt, Instant copiesErasedThrough) {
        erasure.replay(workspaceId, channelId, erasedAt, copiesErasedThrough);
    }
}
