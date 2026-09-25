package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnRequest;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnRunner;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackStreamingMentorChannel;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackOnboardingService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Routes eligible Slack DMs into mentor turns. */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackMentorService {

    private static final Logger log = LoggerFactory.getLogger(SlackMentorService.class);

    private final SlackWorkspaceResolver workspaceResolver;
    private final MentorSlackThreadLinker threadLinker;
    private final MentorTurnRunner mentorTurnRunner;
    private final SlackMessageService slackMessageService;
    private final SlackMentorIdentityResolver identityResolver;
    private final SlackMentorInputGuard inputGuard;
    private final SlackOnboardingService onboardingService;
    private final MentorReadinessQuery mentorReadinessQuery;

    public SlackMentorService(
            SlackWorkspaceResolver workspaceResolver,
            MentorSlackThreadLinker threadLinker,
            MentorTurnRunner mentorTurnRunner,
            SlackMessageService slackMessageService,
            SlackMentorIdentityResolver identityResolver,
            SlackMentorInputGuard inputGuard,
            SlackOnboardingService onboardingService,
            MentorReadinessQuery mentorReadinessQuery) {
        this.workspaceResolver = workspaceResolver;
        this.threadLinker = threadLinker;
        this.mentorTurnRunner = mentorTurnRunner;
        this.slackMessageService = slackMessageService;
        this.identityResolver = identityResolver;
        this.inputGuard = inputGuard;
        this.onboardingService = onboardingService;
        this.mentorReadinessQuery = mentorReadinessQuery;
    }

    public void handleDm(
            String teamId, String channelId, String slackUserId, String text, String messageTs, String threadTs) {
        if (text == null || text.isBlank()) {
            return;
        }
        Optional<Long> workspaceOpt = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceOpt.isEmpty()) {
            log.warn("Slack DM for unknown/inactive team {} — no workspace connection", teamId);
            return;
        }
        long workspaceId = workspaceOpt.get();
        if (!mentorReadinessQuery.isEnabled(workspaceId)) {
            log.debug("Slack mentor disabled for workspace={}, ignoring DM", workspaceId);
            return;
        }
        SlackMentorInputGuard.Verdict verdict = inputGuard.decide(text);
        if (!verdict.allowsMentorTurn()) {
            if (verdict.responseText() != null && !verdict.responseText().isBlank()) {
                slackMessageService.sendForWorkspace(
                        workspaceId, channelId, threadTs, List.of(), verdict.responseText());
            }
            log.info("Slack DM diverted by input guard: workspace={} action={}", workspaceId, verdict.action());
            return;
        }
        Optional<Long> devOpt = identityResolver.resolveActiveMemberId(workspaceId, teamId, slackUserId);
        if (devOpt.isEmpty()) {
            slackMessageService.sendForWorkspace(
                    workspaceId,
                    channelId,
                    threadTs,
                    onboardingService.linkCtaBlocks(),
                    "Check your Hephaestus account status, Slack link, and workspace membership to use the mentor.");
            return;
        }
        long developerId = devOpt.get();
        // Link the thread transactionally before starting remote Slack I/O.
        UUID threadId =
                threadLinker.findOrCreateThread(workspaceId, teamId, channelId, threadTs, slackUserId, developerId);
        slackMessageService.setStatus(workspaceId, channelId, threadTs, "Reviewing recent feedback...");
        SlackStreamingMentorChannel channel =
                new SlackStreamingMentorChannel(slackMessageService, workspaceId, channelId, threadTs);
        mentorTurnRunner.run(
                MentorTurnRequest.slackDm(
                        workspaceId, threadId, text, deterministicSlackMessageId(teamId, channelId, messageTs)),
                channel,
                developerId);
        log.info("Accepted Slack mentor turn: workspace={} thread={} developer={}", workspaceId, threadId, developerId);
    }

    private static UUID deterministicSlackMessageId(String teamId, String channelId, String messageTs) {
        String key = "slack:" + teamId + ":" + channelId + ":" + messageTs;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
