package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import com.slack.api.model.Message;
import com.slack.api.model.block.ContextActionsBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.MarkdownBlock;
import com.slack.api.model.block.composition.FeedbackButtonObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.FeedbackButtonsElement;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorObservationLinkDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What Heph's Slack messages look like: answer blocks, notification text, feedback buttons and post metadata. */
public final class SlackMentorPosts {

    static final String EVENT_TYPE = "heph_entry";

    static final String NOTICE_EVENT_TYPE = "heph_notice";

    static final String ENTRY_ID = "entry_id";

    /** The {@code action_id} of the feedback buttons under an answer in a Slack thread. */
    public static final String FEEDBACK_ACTION = "heph_feedback";

    /** Heph reacts with 👀 to a top-level question while it works on it. */
    static final String WORKING_EMOJI = "eyes";

    static final String FAILED_REPLY = "Heph could not finish this reply. Try again.";

    /** The most Markdown characters that Slack renders in one message. */
    private static final int MAX_MARKDOWN_CHARS = 12_000;

    /** Ends a reply that Slack shows only in part. */
    static final String CUT_NOTE = "\n\n… The full answer is in Hephaestus.";

    /** Ends the stream of an attempt that was lost; the retry streams its answer below. */
    static final String RESTART_NOTE = "\n\n… Heph had to start again. The answer follows below.";

    /** Notifications show the start of the message text. */
    private static final int NOTIFICATION_CHARS = 280;

    private static final char FEEDBACK_SEPARATOR = ':';

    private SlackMentorPosts() {}

    /** Tags a post with the ledger entry it carries, so the reconciler can find it in the Slack history. */
    public static Message.Metadata metadata(UUID entryId) {
        return Message.Metadata.builder()
                .eventType(EVENT_TYPE)
                .eventPayload(Map.of(ENTRY_ID, entryId.toString()))
                .build();
    }

    /**
     * Tags a Heph notice in the DM, such as a refusal. Every Heph message then carries metadata except a stream that
     * no one ended, so a history search never takes a notice for a stream.
     */
    public static Message.Metadata noticeMetadata() {
        return Message.Metadata.builder()
                .eventType(NOTICE_EVENT_TYPE)
                .eventPayload(Map.of("kind", "notice"))
                .build();
    }

    /** Whether a Slack message is the post of this entry, by its metadata. */
    static boolean isPostOf(Message message, UUID entryId) {
        Message.Metadata metadata = message.getMetadata();
        if (metadata == null || !EVENT_TYPE.equals(metadata.getEventType())) {
            return false;
        }
        Map<String, Object> payload = metadata.getEventPayload();
        return payload != null && entryId.toString().equals(String.valueOf(payload.get(ENTRY_ID)));
    }

    /** Heph's Markdown as one Markdown block, cut with a note when Slack cannot show all of it. */
    public static List<LayoutBlock> markdown(String text) {
        String shown = text.length() <= MAX_MARKDOWN_CHARS ? text : text.substring(0, shownLength(text)) + CUT_NOTE;
        return List.of(MarkdownBlock.builder().text(shown).build());
    }

    /** How much of {@code text} one Markdown block shows. */
    static int shownLength(String text) {
        return text.length() <= MAX_MARKDOWN_CHARS ? text.length() : MAX_MARKDOWN_CHARS - CUT_NOTE.length();
    }

    /**
     * Heph's reply in Slack ({@link #reply}).
     *
     * @param feedback each piece of feedback the reply shows, with the quote that shows it
     */
    record Reply(String text, List<Shown> feedback) {

        record Shown(UUID observationId, String quote) {}

        /** The observations whose whole quote is in {@code shownText}, the part of the reply Slack shows. */
        List<UUID> shownIn(String shownText) {
            return feedback.stream()
                    .filter(shown -> shownText.contains(shown.quote()))
                    .map(Shown::observationId)
                    .toList();
        }
    }

    /**
     * Each of Heph's messages as the app shows it: its text, then the feedback it showed, quoted with its practice and
     * the work it was seen on.
     */
    static Reply reply(List<MentorEntryDTO> messages) {
        StringBuilder text = new StringBuilder();
        List<Reply.Shown> feedback = new ArrayList<>();
        for (MentorEntryDTO message : messages) {
            paragraph(text, message.text());
            for (MentorObservationLinkDTO observation : message.observations()) {
                String quote = quote(observation);
                paragraph(text, quote);
                feedback.add(new Reply.Shown(observation.observationId(), quote));
            }
        }
        return new Reply(text.toString(), feedback);
    }

    private static void paragraph(StringBuilder text, String part) {
        if (part.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        text.append(part);
    }

    private static String quote(MentorObservationLinkDTO observation) {
        StringBuilder feedback = new StringBuilder();
        String practice = observation.practiceName();
        if (practice != null && !practice.isBlank()) {
            feedback.append("**").append(practice.strip()).append("**\n");
        }
        feedback.append(observation.text().strip());
        ReviewedWorkRefDTO work = observation.work();
        if (work != null) {
            String title = work.title();
            String name = (work.label() + (title == null || title.isBlank() ? "" : " " + title.strip()))
                    .replace("[", "\\[")
                    .replace("]", "\\]");
            String url = work.url();
            feedback.append("\nSeen on ").append(url == null ? name : "[" + name + "](" + url + ")");
        }
        return "> " + feedback.toString().replace("\n", "\n> ");
    }

    /** The plain text that notifications and screen readers show: the start of the message on one line. */
    public static String notification(String text) {
        String line = text.replaceAll("[*_`#>~]", "").replaceAll("\\s+", " ").strip();
        if (line.length() <= NOTIFICATION_CHARS) {
            return line.isEmpty() ? "Heph replied." : line;
        }
        int cut = line.lastIndexOf(' ', NOTIFICATION_CHARS);
        return line.substring(0, cut > 0 ? cut : NOTIFICATION_CHARS) + "…";
    }

    /** Helpful and Not helpful buttons that vote on Heph's answer. */
    static List<LayoutBlock> feedbackButtons(UUID answerId) {
        FeedbackButtonsElement buttons = FeedbackButtonsElement.builder()
                .actionId(FEEDBACK_ACTION)
                .positiveButton(button("Helpful", MentorEntry.Vote.HELPFUL, answerId, "Mark this answer as helpful"))
                .negativeButton(button(
                        "Not helpful", MentorEntry.Vote.NOT_HELPFUL, answerId, "Mark this answer as not helpful"))
                .build();
        return List.of(ContextActionsBlock.builder().elements(List.of(buttons)).build());
    }

    private static FeedbackButtonObject button(String label, MentorEntry.Vote vote, UUID answerId, String a11y) {
        return FeedbackButtonObject.builder()
                .text(PlainTextObject.builder().text(label).build())
                .value(vote.name() + FEEDBACK_SEPARATOR + answerId)
                .accessibilityLabel(a11y)
                .build();
    }

    /** A vote that a feedback button carries. */
    public record Feedback(UUID entryId, MentorEntry.Vote vote) {}

    /** Reads the value of a clicked feedback button. */
    public static Optional<Feedback> feedback(String value) {
        int separator = value.indexOf(FEEDBACK_SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Feedback(
                    UUID.fromString(value.substring(separator + 1)),
                    MentorEntry.Vote.valueOf(value.substring(0, separator))));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** The reply to a turn that failed: its reason when it has one. */
    static String failedReply(@Nullable String error) {
        return error == null || error.isBlank() ? FAILED_REPLY : error;
    }
}
