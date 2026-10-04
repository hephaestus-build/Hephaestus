package de.tum.cit.aet.hephaestus.integration.slack.events;

import com.slack.api.model.assistant.SuggestedPrompt;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Installs suggested prompts when a member opens the Slack agent Messages tab. */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackAssistantEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SlackAssistantEventHandler.class);

    private static final String PROMPT_TITLE = "Practice mentor";

    /** Context-safe openers: no stale "latest PR" claims before the mentor has loaded evidence. */
    static final List<SuggestedPrompt> PROMPTS = List.of(
            new SuggestedPrompt("What needs attention?", "What software project practice should I focus on next?"),
            new SuggestedPrompt("Review my recent work", "Review my recent pull requests, reviews, and issues."),
            new SuggestedPrompt("Check my reviews", "How are my code reviews and review comments trending?"),
            new SuggestedPrompt("Follow up", "What feedback or project-practice issue should I revisit?"));

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackMessageService messageService;
    private final MentorReadinessQuery mentorReadinessQuery;

    public SlackAssistantEventHandler(
            SlackWorkspaceResolver workspaceResolver,
            SlackMessageService messageService,
            MentorReadinessQuery mentorReadinessQuery) {
        this.workspaceResolver = workspaceResolver;
        this.messageService = messageService;
        this.mentorReadinessQuery = mentorReadinessQuery;
    }

    public void onMessagesOpened(String teamId, JsonNode event) {
        String channelId = event.path("channel").asString("");
        if (channelId.isEmpty()) {
            return;
        }
        Optional<Long> workspaceId = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceId.isEmpty()) {
            log.debug("slack.agent: messages tab opened for team={} with no active connection", teamId);
            return;
        }
        long workspace = workspaceId.get();
        if (!mentorReadinessQuery.isReady(workspace)) {
            log.debug("slack.agent: mentor unavailable for workspace={}", workspace);
            return;
        }
        messageService.setSuggestedPrompts(workspace, channelId, PROMPT_TITLE, PROMPTS);
    }
}
