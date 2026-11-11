package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import com.slack.api.model.Message;
import com.slack.api.model.block.ContextActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.MarkdownBlock;
import com.slack.api.model.block.composition.FeedbackButtonObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.FeedbackButtonsElement;
import de.tum.cit.aet.hephaestus.core.ModelLinks;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorLinkDTO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** What Hephaestus's Slack messages look like: answer blocks, notification text, feedback buttons and post metadata. */
public final class SlackMentorPosts {

    // Posted messages keep these metadata types and button action ids for readback and old buttons.
    static final String EVENT_TYPE = "heph_entry";

    static final String NOTICE_EVENT_TYPE = "heph_notice";

    static final String ENTRY_ID = "entry_id";

    /** The payload key of the turn a progress notice belongs to. */
    private static final String TURN_ID = "turn_id";

    /** The {@code action_id} of the feedback buttons under an answer in a Slack thread. */
    public static final String FEEDBACK_ACTION = "heph_feedback";

    /** Hephaestus reacts with 👀 to a top-level question while it works on it. */
    static final String WORKING_EMOJI = "eyes";

    static final String FAILED_REPLY = "Hephaestus could not finish this reply. Try again.";

    /** The most Markdown characters that Slack renders in one message. */
    private static final int MAX_MARKDOWN_CHARS = 12_000;

    /** Ends a reply that Slack shows only in part. */
    static final String CUT_NOTE = "\n\n… The full answer is in Hephaestus.";

    /** Ends the stream of an attempt that was lost; the retry streams its answer below. */
    static final String RESTART_NOTE = "\n\n… The answer had to start again. It follows below.";

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
     * Tags a Hephaestus notice in the DM, such as a refusal. Every Hephaestus message then carries metadata except a stream that
     * no one ended, so a history search never takes a notice for a stream.
     */
    public static Message.Metadata noticeMetadata() {
        return Message.Metadata.builder()
                .eventType(NOTICE_EVENT_TYPE)
                .eventPayload(Map.of("kind", "notice"))
                .build();
    }

    /**
     * Tags the progress notice of one turn, so that a server that lost its receipt, for example after a restart, finds
     * and deletes it once the reply posts ({@link #isProgressOf}).
     */
    public static Message.Metadata progressMetadata(UUID turnId) {
        return Message.Metadata.builder()
                .eventType(NOTICE_EVENT_TYPE)
                .eventPayload(Map.of("kind", "progress", TURN_ID, turnId.toString()))
                .build();
    }

    /** Whether a Slack message is the progress notice of this turn, by its metadata. */
    static boolean isProgressOf(Message message, UUID turnId) {
        Message.Metadata metadata = message.getMetadata();
        if (metadata == null || !NOTICE_EVENT_TYPE.equals(metadata.getEventType())) {
            return false;
        }
        Map<String, Object> payload = metadata.getEventPayload();
        return payload != null && turnId.toString().equals(String.valueOf(payload.get(TURN_ID)));
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

    /** Hephaestus's Markdown as one Markdown block, cut with a note when Slack cannot show all of it. */
    public static List<LayoutBlock> markdown(String text) {
        String shown = text.length() <= MAX_MARKDOWN_CHARS ? text : text.substring(0, shownLength(text)) + CUT_NOTE;
        return List.of(MarkdownBlock.builder().text(shown).build());
    }

    /** How much of {@code text} one Markdown block shows. */
    static int shownLength(String text) {
        return text.length() <= MAX_MARKDOWN_CHARS ? text.length() : MAX_MARKDOWN_CHARS - CUT_NOTE.length();
    }

    /**
     * Hephaestus's reply in Slack ({@link #reply}).
     *
     * @param shown each result, problem and piece of feedback the reply shows, with the quote that shows it
     */
    record Reply(String text, List<Shown> shown) {

        /** @param start where the quote begins in the reply's text */
        record Shown(MentorLinkDTO.Kind kind, UUID id, String quote, int start) {}

        /**
         * @param whole the feedback whose every quote Slack shows
         * @param partly the feedback of which Slack shows some words but not all
         */
        record Seen(List<UUID> whole, List<UUID> partly) {
            static final Seen NOTHING = new Seen(List.of(), List.of());
        }

        /**
         * What {@code shownText}, the part of the reply Slack shows, shows of each piece of feedback. A piece is shown
         * when each of its quotes is whole there. A piece of which Slack shows some words but not all is partly shown:
         * a quote cut inside, or one of two quotes cut off. Its receipt would hold words that Slack never showed.
         */
        Seen shownIn(String shownText) {
            Map<UUID, List<Visible>> quotes = new LinkedHashMap<>();
            for (Shown link : shown) {
                if (link.kind() == MentorLinkDTO.Kind.FEEDBACK) {
                    quotes.computeIfAbsent(link.id(), id -> new ArrayList<>()).add(visible(link, shownText));
                }
            }
            List<UUID> whole = new ArrayList<>();
            List<UUID> partly = new ArrayList<>();
            quotes.forEach((id, seen) -> {
                if (seen.stream().allMatch(Visible.WHOLE::equals)) whole.add(id);
                else if (!seen.stream().allMatch(Visible.NONE::equals)) partly.add(id);
            });
            return new Seen(whole, partly);
        }

        private enum Visible {
            WHOLE,
            PART,
            NONE,
        }

        /**
         * How much of one quote {@code shownText} shows. When Slack shows the start of the reply, a cut after the quote
         * marker and before the quote's end shows part of its words.
         */
        private Visible visible(Shown link, String shownText) {
            if (shownText.contains(link.quote())) return Visible.WHOLE;
            int marker = link.start() + QUOTE_MARKER.length();
            return text.startsWith(shownText) && shownText.length() > marker ? Visible.PART : Visible.NONE;
        }
    }

    /** Each of Hephaestus's messages as the app shows it: its text, then what it showed, quoted. */
    static Reply reply(List<MentorEntryDTO> messages, Set<String> linkHosts) {
        StringBuilder raw = new StringBuilder();
        List<MentorLinkDTO> links = new ArrayList<>();
        List<String> quotes = new ArrayList<>();
        for (MentorEntryDTO message : messages) {
            paragraph(raw, message.text());
            for (MentorLinkDTO link : message.links()) {
                String quote = quote(link);
                paragraph(raw, quote);
                links.add(link);
                quotes.add(quote);
            }
        }
        // The link rule reads the reply as Slack does: as one text, since parts can join into one block.
        String text = ModelLinks.neutralize(raw.toString(), linkHosts);
        List<Reply.Shown> shown = new ArrayList<>();
        int from = 0;
        for (int index = 0; index < links.size(); index++) {
            MentorLinkDTO link = links.get(index);
            String quote = ModelLinks.neutralize(quotes.get(index), linkHosts);
            int start = text.indexOf(quote, from);
            if (start < 0) {
                // The rule changed the quote in its context, so this reply never counts it as shown.
                shown.add(new Reply.Shown(link.kind(), link.id(), quotes.get(index), text.length()));
                continue;
            }
            shown.add(new Reply.Shown(link.kind(), link.id(), quote, start));
            from = start + quote.length();
        }
        return new Reply(text, shown);
    }

    /**
     * The part of a partial answer that a stream may show. Finished paragraphs go out under the link rule. A link never
     * spans paragraphs, so they read as the start of the finished reply. The paragraph still being written goes out up
     * to the first place where a link could start ({@code [}, {@code <}, a web address, or a last word that may grow
     * into one), so the stream never shows a link that the finished reply would not.
     */
    static String streamable(String partial, Set<String> linkHosts) {
        int paragraph = partial.lastIndexOf("\n\n");
        String done = paragraph < 0 ? "" : ModelLinks.neutralize(partial.substring(0, paragraph), linkHosts) + "\n\n";
        String writing = paragraph < 0 ? partial : partial.substring(paragraph + 2);
        int cut = writing.length();
        Matcher risky = LINK_START.matcher(writing);
        if (risky.find()) cut = risky.start();
        int word = lastWordStart(writing);
        String last = writing.substring(word).toLowerCase(Locale.ROOT);
        if (!last.isEmpty() && ("https://".startsWith(last) || "http://".startsWith(last) || "www.".startsWith(last)))
            cut = Math.min(cut, word);
        return done + writing.substring(0, cut);
    }

    private static int lastWordStart(String text) {
        int at = text.length();
        while (at > 0 && (Character.isLetterOrDigit(text.charAt(at - 1)) || ":/.".indexOf(text.charAt(at - 1)) >= 0))
            at--;
        return at;
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

    private static final String QUOTE_MARKER = "> ";

    // Where a link may begin in text that is still being written.
    private static final Pattern LINK_START =
            Pattern.compile("[\\[<]|!(?:\\[|$)|(?<![\\p{L}\\p{N}])(?i:https?:|www\\.)");

    private static String quote(MentorLinkDTO link) {
        return QUOTE_MARKER + link.text().strip().replace("\n", "\n" + QUOTE_MARKER);
    }

    /** The plain text that notifications and screen readers show: the start of the message on one line. */
    public static String notification(String text) {
        String line = text.replaceAll("[*_`#>~]", "").replaceAll("\\s+", " ").strip();
        if (line.length() <= NOTIFICATION_CHARS) {
            return line.isEmpty() ? "Hephaestus replied." : line;
        }
        int cut = line.lastIndexOf(' ', NOTIFICATION_CHARS);
        return line.substring(0, cut > 0 ? cut : NOTIFICATION_CHARS) + "…";
    }

    /**
     * Says once per DM or thread that a model writes the answers (EU AI Act Art. 50(1): at the first interaction).
     * Slack already shows Hephaestus as an agent, so later answers do not repeat it.
     */
    public static final String AI_LABEL = "Hephaestus answers with an AI model and can be wrong.";

    /** The {@link #AI_LABEL} line, in Slack's small context text. */
    public static List<LayoutBlock> aiLabel() {
        return List.of(ContextBlock.builder()
                .elements(List.of(PlainTextObject.builder().text(AI_LABEL).build()))
                .build());
    }

    /**
     * What closes an answer: the AI label on the first answer of a DM or thread, then in a thread the feedback buttons.
     */
    static List<LayoutBlock> answerFooter(UUID answerId, boolean firstAnswer, boolean inThread) {
        return Stream.concat(
                        (firstAnswer ? aiLabel() : List.<LayoutBlock>of()).stream(),
                        (inThread ? feedbackButtons(answerId) : List.<LayoutBlock>of()).stream())
                .toList();
    }

    /** Helpful and Not helpful buttons that vote on Hephaestus's answer. */
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
