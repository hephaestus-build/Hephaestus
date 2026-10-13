package de.tum.cit.aet.hephaestus.integration.slack.onboarding;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;

import com.slack.api.model.block.LayoutBlock;
import java.util.List;
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

    /** Distinct from the interactivity action_ids: this button only opens a URL, it posts no payload. */
    private static final String LINK_ACTION_ID = "link_slack_identity";

    private final String hostUrl;
    private final String authBasePath;

    public SlackOnboardingService(
            @Value("${hephaestus.webapp.url:}") String hostUrl,
            @Value("${hephaestus.auth.api-base-path:}") String authBasePath) {
        this.hostUrl = hostUrl;
        this.authBasePath = authBasePath;
    }

    public List<LayoutBlock> linkCtaBlocks() {
        return asBlocks(
                section(s -> s.text(markdownText("*Check your Hephaestus account access.*\n"
                        + "Link Slack to an active Hephaestus account with a project identity in this workspace. "
                        + "If you have already linked your account, ask a workspace admin to check your account "
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
