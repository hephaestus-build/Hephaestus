package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The direct interaction replies permitted by {@code docs/contributor/outbox.md#effect-boundary}. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SlackInteractiveReplyAdapter {
    private final SlackMessageService client;

    public void sendEphemeralForWorkspace(
            long workspaceId, String channelId, String actingUserId, List<LayoutBlock> blocks, String text) {
        client.sendEphemeralForWorkspace(workspaceId, channelId, actingUserId, blocks, text);
    }

    public void publishHomeView(long workspaceId, String actingUserId, View view) {
        client.publishHomeView(workspaceId, actingUserId, view);
    }
}
