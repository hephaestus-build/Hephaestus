package de.tum.cit.aet.hephaestus.integration.slack.channel;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.ConfirmationDialogObject;
import java.util.List;

/**
 * The single source of truth for the in-channel Slack consent notice — its copy and its Block Kit rendering.
 * Rendered in two places so a member always gets the same transparency message and one-click control:
 *
 * <ul>
 *   <li>the one-time <strong>activation announcement</strong> posted to the whole channel on
 *       {@code PENDING → ACTIVE} ({@code SlackChannelConsentService});</li>
 *   <li>the <strong>just-in-time ephemeral notice</strong> shown only to a member who joins an already-active
 *       channel afterwards ({@code SlackChannelJoinNoticeHandler}).</li>
 * </ul>
 *
 * <p>The opt-out button carries a Slack {@code confirm} dialog and the stable
 * {@link #ACTION_PARTICIPANT_OPT_OUT} action id the interactivity router binds to the channel-message exclusion +
 * erase path. A plain-text fallback rides on the surrounding message for clients that cannot render the actions.
 */
public final class SlackConsentBlocks {

    /**
     * In-message one-click channel-message exclusion. Bound by {@code SlackInteractivityHandler} to person ingestion
     * opt-out + erase already-collected channel data, keyed on the acting Slack user id.
     */
    public static final String ACTION_PARTICIPANT_OPT_OUT = "participant_opt_out";

    private static final String ACTIVATION_LINE_1 = "*Hephaestus is now active in this channel.*";

    private static final String ACTIVATION_LINE_2 =
            "From now on, Hephaestus may use new messages and thread replies in this channel as context. "
                    + "This context helps Hephaestus mentor each person privately about software practices. "
                    + "Examples are code reviews, tests, issues, questions, and collaboration.";

    private static final String SHARED_LINE =
            "Hephaestus does not read earlier history. It will not reply in this channel. "
                    + "You can tell Hephaestus to stop using your own channel messages at any time. "
                    + "This also deletes the channel-message data that Hephaestus already collected from you.";

    private static final String LATE_JOIN_LINE_1 = "*You joined a channel where Hephaestus is active.*";

    private static final String LATE_JOIN_LINE_2 =
            "From now on, Hephaestus may use your new messages and thread replies in this channel as context. "
                    + "This context helps Hephaestus mentor you privately about software practices. "
                    + "Hephaestus does not read earlier history or reply in this channel. "
                    + "You can manage this at any time in App Home.";

    /** Plain-text fallback for the notice (no mrkdwn), shown in notifications + by accessibility tools. */
    private static final String FALLBACK_TEXT =
            "Hephaestus is now active in this channel. From now on, Hephaestus may use new messages and thread "
                    + "replies in this channel as context. This context helps Hephaestus mentor each person privately about "
                    + "software practices. Examples are code reviews, tests, issues, questions, and collaboration. "
                    + "Hephaestus does not read earlier history. It will not reply in this channel. "
                    + "To stop the use of your own channel messages, select Do not use my channel messages. "
                    + "This also deletes the channel-message data that Hephaestus already collected from you.";

    private static final String LATE_JOIN_FALLBACK_TEXT =
            "You joined a channel that Hephaestus monitors. From now on, Hephaestus may use your new messages and "
                    + "thread replies in this channel as context. This context helps Hephaestus mentor you privately about "
                    + "software practices. Hephaestus does not read earlier history or reply in this channel. "
                    + "You can manage this at any time in App Home.";

    /** Ephemeral confirmation shown to a member right after they opt out via the in-message button. */
    private static final String CONFIRMATION_TEXT = "Done. Hephaestus will not use your channel messages. "
            + "Hephaestus deleted the channel-message data that it already collected from you.";

    private SlackConsentBlocks() {}

    /** The consent notice: copy plus a destructive button with a confirm dialog. Freshly built per call. */
    public static List<LayoutBlock> consentNotice() {
        return activationNotice();
    }

    public static List<LayoutBlock> activationNotice() {
        return List.of(noticeText(ACTIVATION_LINE_1, ACTIVATION_LINE_2), optOutAction());
    }

    public static List<LayoutBlock> lateJoinNotice() {
        return List.of(noticeText(LATE_JOIN_LINE_1, LATE_JOIN_LINE_2), optOutAction());
    }

    private static LayoutBlock noticeText(String line1, String line2) {
        return section(s -> s.text(markdownText(line1 + "\n\n" + line2 + "\n\n" + SHARED_LINE)));
    }

    private static LayoutBlock optOutAction() {
        return actions(a -> a.elements(asElements(button(b -> b.text(plainText("Do not use my channel messages"))
                .actionId(ACTION_PARTICIPANT_OPT_OUT)
                .style("danger")
                .confirm(channelMessageOptOutConfirm())))));
    }

    public static String fallbackText() {
        return activationFallbackText();
    }

    public static String activationFallbackText() {
        return FALLBACK_TEXT;
    }

    public static String lateJoinFallbackText() {
        return LATE_JOIN_FALLBACK_TEXT;
    }

    /** The ephemeral opt-out confirmation shown to the member who just excluded their channel messages. */
    public static List<LayoutBlock> optOutConfirmation() {
        return List.of(section(s -> s.text(markdownText(CONFIRMATION_TEXT))));
    }

    public static String confirmationText() {
        return CONFIRMATION_TEXT;
    }

    /** The confirm dialog on the opt-out button — spells out the consequence before the irreversible erase. */
    public static ConfirmationDialogObject channelMessageOptOutConfirm() {
        return ConfirmationDialogObject.builder()
                .title(plainText("Stop using your channel messages?"))
                .text(plainText("Hephaestus stops using your messages in monitored channels. "
                        + "It also deletes the channel message data that it already collected from you. "
                        + "This does not change mentor DMs."))
                .confirm(plainText("Do not use my messages"))
                .deny(plainText("Cancel"))
                .style("danger")
                .build();
    }
}
