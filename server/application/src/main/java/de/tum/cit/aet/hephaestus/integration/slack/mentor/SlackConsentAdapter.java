package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelActivation;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentAnnouncement;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Confirms the public announcement before the domain can enable ingestion. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class SlackConsentAdapter implements ActionAdapter {
    private final SlackPostAdapter posts;
    private final SlackChannelActivation activation;
    private final ObjectProvider<SlackMessageService> clients;
    private final ObjectMapper mapper;

    @Override
    public ActionKind kind() {
        return ActionKind.SLACK_CONSENT;
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        long workspace = Objects.requireNonNull(attempt.workspaceId());
        var request = mapper.treeToValue(attempt.payload(), SlackConsentAnnouncement.class);
        if (!activation.isCurrent(workspace, request))
            return SlackPostAdapter.unavailable(attempt, ActionStopReason.WITHDRAWN);
        var client = clients.getIfAvailable();
        if (client == null) return SlackPostAdapter.unavailable(attempt, ActionStopReason.NOT_CONFIGURED);
        var lookup = client.lookupConversationDetailed(workspace, request.channelId());
        if (lookup instanceof SlackMessageService.ConversationLookup.Unavailable)
            return attempt.writeStarted()
                    ? new ActionResult.Reread(Duration.ofMinutes(1))
                    : new ActionResult.Retry(Duration.ofMinutes(1));
        if (!(lookup instanceof SlackMessageService.ConversationLookup.Found found)
                || found.info().archived()
                || !found.info().member())
            return SlackPostAdapter.unavailable(attempt, ActionStopReason.RECIPIENT_UNAVAILABLE);
        var result = posts.postNotice(
                attempt,
                request.marker(),
                new SlackPostAdapter.Post(
                        request.channelId(),
                        null,
                        SlackConsentBlocks.activationNotice(),
                        SlackConsentBlocks.activationFallbackText(),
                        request.oldestTs(),
                        null),
                request.retry(),
                20);
        if (result instanceof ActionResult.Done done && done.readbackRef() != null) {
            var receipt = mapper.readValue(done.readbackRef(), SlackPostAdapter.Receipt.class);
            Long micros = SlackTs.toEpochMicros(receipt.ts());
            return micros == null
                    ? new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED)
                    : new ActionResult.Done(Long.toString(micros));
        }
        return result;
    }

    @Override
    public void ended(ActionEnded ended) {
        Long micros = ended.state() == ActionState.DONE && ended.readbackRef() != null
                ? Long.valueOf(ended.readbackRef())
                : null;
        activation.ended(
                Objects.requireNonNull(ended.workspaceId()),
                SlackConsentAnnouncement.requestId(ended.idempotencyKey()),
                ended.state(),
                micros);
    }
}
