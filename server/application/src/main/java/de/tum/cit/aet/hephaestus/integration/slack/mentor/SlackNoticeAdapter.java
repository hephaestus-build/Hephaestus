package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackOnboardingService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Executes DM notices with the same fenced Slack transport as completed answers. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class SlackNoticeAdapter implements ActionAdapter {
    private final SlackPostAdapter posts;
    private final ObjectProvider<SlackOnboardingService> onboarding;
    private final ConnectionService connections;
    private final ObjectMapper mapper;

    @Override
    public ActionKind kind() {
        return ActionKind.SLACK_NOTICE;
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        var recipient = attempt.nativeRecipient();
        if (recipient == null
                || connections
                        .findSlackConfig(Objects.requireNonNull(attempt.workspaceId()))
                        .filter(config -> Objects.equals(config.teamId(), recipient.teamId()))
                        .isEmpty())
            return SlackPostAdapter.unavailable(attempt, ActionStopReason.RECIPIENT_UNAVAILABLE);
        var notice = mapper.treeToValue(attempt.payload(), SlackNotice.class);
        var links = onboarding.getIfAvailable();
        if (notice.linkAccount() && links == null)
            return SlackPostAdapter.unavailable(attempt, ActionStopReason.NOT_CONFIGURED);
        List<LayoutBlock> blocks = notice.linkAccount() && links != null ? links.linkCtaBlocks() : List.of();
        return posts.postNotice(
                attempt,
                new SlackPostAdapter.Post(
                        notice.channelId(), notice.threadTs(), blocks, notice.text(), notice.messageTs(), null));
    }
}
