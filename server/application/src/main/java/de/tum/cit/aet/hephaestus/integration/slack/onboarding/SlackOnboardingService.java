package de.tum.cit.aet.hephaestus.integration.slack.onboarding;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Account-linking guidance shared by App Home and mentor DMs. A link alone is insufficient:
 * mentoring also requires an active account and a project identity in the workspace.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(SlackOnboardingService.class);

    /** Distinct from the interactivity action_ids: this button only opens a URL, it posts no payload. */
    private static final String LINK_ACTION_ID = "link_slack_identity";

    private static final String FALLBACK_TEXT = "Check your Hephaestus account access";

    private final SlackWorkspaceResolver workspaceResolver;
    private final SlackMentorIdentityResolver identityResolver;
    private final SlackMessageService messageService;
    private final String hostUrl;
    private final String authBasePath;

    public SlackOnboardingService(
            SlackWorkspaceResolver workspaceResolver,
            SlackMentorIdentityResolver identityResolver,
            SlackMessageService messageService,
            @Value("${hephaestus.webapp.url:}") String hostUrl,
            @Value("${hephaestus.auth.api-base-path:}") String authBasePath) {
        this.workspaceResolver = workspaceResolver;
        this.identityResolver = identityResolver;
        this.messageService = messageService;
        this.hostUrl = hostUrl;
        this.authBasePath = authBasePath;
    }

    public void onHomeOpened(String teamId, String slackUserId) {
        if (teamId == null || teamId.isBlank() || slackUserId == null || slackUserId.isBlank()) {
            return;
        }
        Optional<Long> workspaceId = workspaceResolver.resolveWorkspaceId(teamId);
        if (workspaceId.isEmpty()) {
            log.debug("slack.onboarding: app_home_opened for team={} with no active connection — skipping CTA", teamId);
            return;
        }
        long ws = workspaceId.get();
        if (identityResolver.resolveDeveloper(ws, teamId, slackUserId).isPresent()) {
            log.debug("slack.onboarding: member={} has account access in workspace={} — no CTA", slackUserId, ws);
            return;
        }
        try {
            messageService.sendForWorkspace(ws, slackUserId, linkCtaBlocks(), FALLBACK_TEXT);
        } catch (SlackSendException e) {
            log.warn(
                    "slack.onboarding: failed to surface link CTA for workspace={}, slackError={}", ws, e.slackError());
        }
    }

    public List<LayoutBlock> linkCtaBlocks() {
        return asBlocks(
                section(s -> s.text(markdownText("*Check your Hephaestus account access.*\n"
                        + "Link Slack to an active Hephaestus account with a project identity in this workspace. "
                        + "If you have already linked your account, ask an administrator to check your account "
                        + "status and workspace membership."))),
                actions(a -> a.elements(asElements(button(b -> b.text(plainText("Link Hephaestus account"))
                        .url(linkUrl())
                        .actionId(LINK_ACTION_ID)
                        .style("primary"))))));
    }

    /** The authenticated link-mode deep link. Slack opens it in the browser where the session cookie lives. */
    String linkUrl() {
        String base = hostUrl == null ? "" : hostUrl.trim().replaceAll("/+$", "");
        String prefix = authBasePath == null
                ? ""
                : authBasePath.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        String apiPrefix = prefix.isBlank() ? "" : "/" + prefix;
        return base + apiPrefix + "/auth/login?provider=slack&mode=link&returnTo=/settings";
    }
}
