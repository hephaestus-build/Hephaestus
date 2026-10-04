package de.tum.cit.aet.hephaestus.integration.slack.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.ButtonElement;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Copy/rendering guards for the shared consent notice. The notice must carry the one-click opt-out button with the
 * stable {@code participant_opt_out} action id (the interactivity router binds it to the erase path) and a plain,
 * buzzword-free fallback — a regression on either would silently break the transparency + control contract.
 */
class SlackConsentBlocksTest extends BaseUnitTest {

    @Test
    void consentNotice_carriesTheOneClickOptOutButton() {
        List<LayoutBlock> blocks = SlackConsentBlocks.consentNotice();

        assertThat(blocks).isNotEmpty();
        ButtonElement optOut = blocks.stream()
                .filter(ActionsBlock.class::isInstance)
                .map(ActionsBlock.class::cast)
                .flatMap(a -> a.getElements().stream())
                .filter(ButtonElement.class::isInstance)
                .map(ButtonElement.class::cast)
                .filter(b -> SlackConsentBlocks.ACTION_PARTICIPANT_OPT_OUT.equals(b.getActionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("consent notice is missing the participant_opt_out button"));

        // Destructive action → danger style + a confirm dialog before the irreversible erase.
        assertThat(optOut.getText().getText()).isEqualTo("Do not use my channel messages");
        assertThat(optOut.getStyle()).isEqualTo("danger");
        assertThat(optOut.getConfirm()).isNotNull();
    }

    @Test
    void fallbackText_isPlainLanguage_noAiBuzzwords() {
        // Plain-language transparency: say what is read in concrete terms, not "AI-powered" marketing copy.
        assertThat(SlackConsentBlocks.fallbackText())
                .isNotBlank()
                .contains(
                        "From now on",
                        "new messages and thread replies",
                        "mentor each person privately about software practices",
                        "Hephaestus does not read earlier history",
                        "It will not reply in this channel",
                        "deletes the channel-message data that Hephaestus already collected from you",
                        "Do not use my channel messages")
                .doesNotContain("AI-powered", "Opt me out", "Exclude my channel messages", "—", ";");
    }

    @Test
    void activationNotice_doesNotLinkToWorkspaceDashboard() {
        assertThat(SlackConsentBlocks.activationNotice().toString())
                .doesNotContain("Open Hephaestus", "workspace dashboard", "https://");
        assertThat(SlackConsentBlocks.activationFallbackText()).doesNotContain("Open Hephaestus", "https://");
    }

    @Test
    void lateJoinText_isSpecificToLateJoiner() {
        assertThat(SlackConsentBlocks.lateJoinFallbackText())
                .contains(
                        "You joined a channel that Hephaestus monitors",
                        "From now on",
                        "You can manage this at any time in App Home")
                .doesNotContain("Starting now");
    }
}
