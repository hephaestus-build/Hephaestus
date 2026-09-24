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
 * Owns the Hephaestus account-linking call-to-action: when a workspace member who has not yet linked their
 * identity opens the Hephaestus App Home, deliver a single CTA to their DM (via {@link SlackMessageService}) that deep-links
 * into the authenticated account-linking flow ({@code /auth/login?provider=slack&mode=link}). Linking there
 * attaches a {@code SLACK} identity to the signed-in account, after which {@link SlackMentorIdentityResolver}
 * can resolve the member's SCM work. The CTA is idempotently gated on "not yet linked", so an already-linked
 * member is never nudged.
 *
 * <p>This service owns only the CTA blocks and their DM delivery. The persistent Home tab — the privacy
 * disclosure and the link to account consent settings — is rendered by {@link SlackAppHomeService} via
 * {@code views.publish}, which reuses {@link #linkCtaBlocks()} to lead an unlinked member with the same CTA.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(SlackOnboardingService.class);

    /** Distinct from the interactivity action_ids: this button only opens a URL, it posts no payload. */
    private static final String LINK_ACTION_ID = "link_slack_identity";

    private static final String FALLBACK_TEXT = "Connect your account to Hephaestus";

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

    /**
     * Handle an {@code app_home_opened} event: surface the link CTA to an unlinked member. Best-effort: a
     * missing connection, an already-linked member, or a Slack send failure is logged and swallowed, never
     * thrown.
     *
     * @param teamId      the Slack {@code T…} workspace id from the verified event envelope
     * @param slackUserId the {@code U…} member who opened the App Home
     */
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
        if (identityResolver.resolveDeveloperLogin(ws, teamId, slackUserId).isPresent()) {
            log.debug("slack.onboarding: member={} already linked in workspace={} — no CTA", slackUserId, ws);
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
                section(s -> s.text(markdownText("*Link your Hephaestus account.*\n"
                        + "Connect your Slack identity to your Hephaestus profile so the mentor can find your "
                        + "repositories, reviews, and issues."))),
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
