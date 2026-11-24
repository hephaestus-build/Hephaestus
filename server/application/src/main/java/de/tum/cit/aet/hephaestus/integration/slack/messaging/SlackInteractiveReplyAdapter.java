package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.EphemeralMessage;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The direct interaction replies permitted by {@code docs/contributor/outbox.mdx#effect-boundary}. */
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

    public void sendEphemeralForTeam(
            long workspaceId,
            String teamId,
            String channelId,
            String actingUserId,
            EphemeralMessage message,
            BooleanSupplier eligible) {
        client.sendEphemeralForTeam(workspaceId, teamId, channelId, actingUserId, message, eligible);
    }

    public void publishHomeView(
            long workspaceId,
            String teamId,
            String actingUserId,
            View view,
            @Nullable String homeViewHash,
            BooleanSupplier eligible) {
        client.publishHomeView(workspaceId, teamId, actingUserId, view, homeViewHash, eligible);
    }
}
