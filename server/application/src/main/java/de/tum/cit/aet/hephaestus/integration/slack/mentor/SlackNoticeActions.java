package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Durable DM notices that have no conversation entry, such as an admission refusal or an account-link request. */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SlackNoticeActions {
    private final AgentActions actions;
    private final ObjectMapper mapper;
    private final GitProviderRegistry providers;

    @Transactional
    public void reply(long workspaceId, String teamId, String userId, @Nullable Long accountId, SlackNotice notice) {
        var recipient = new PersonIdentity(
                providers.resolveProviderId("SLACK", SlackMentorIdentityResolver.SLACK_SERVER_URL), userId, teamId);
        String key = "slack_notice:" + workspaceId + ":" + recipient.teamId() + ":" + notice.channelId() + ":"
                + notice.messageTs();
        actions.enqueue(new ActionRequest(
                ActionKind.SLACK_NOTICE,
                workspaceId,
                "slack_dm:" + workspaceId + ":" + notice.channelId(),
                key,
                mapper.valueToTree(notice),
                accountId,
                3,
                Instant.now().plus(Duration.ofDays(1)),
                recipient));
    }
}
