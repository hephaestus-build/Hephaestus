package de.tum.cit.aet.hephaestus.mentor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Reads Pi session entries (Pi {@code docs/session-format.md}) into ledger kinds, plain text and observation links. */
final class MentorPiEntries {

    static final String LINK_OBSERVATION = "link_observation";

    /** The one tool whose result copies other people's data ({@link MentorEntryRepository#COPIES_PERSON_DATA}). */
    static final String FETCH_CONTEXT = "fetch_context";

    private MentorPiEntries() {}

    /** A {@code link_observation} call: the tool call id, the observation it shows, and Heph's words about it. */
    record Link(String callId, UUID observationId, String text) {}

    static MentorEntry.Kind kind(JsonNode piEntry) {
        String type = piEntry.path("type").asString("");
        if (type.equals("compaction")) {
            return MentorEntry.Kind.SUMMARY;
        }
        if (!type.equals("message")) {
            return MentorEntry.Kind.SYSTEM;
        }
        return switch (piEntry.path("message").path("role").asString("")) {
            case "user" -> MentorEntry.Kind.USER;
            case "assistant" -> MentorEntry.Kind.HEPH;
            case "toolResult" -> MentorEntry.Kind.TOOL_RESULT;
            default -> MentorEntry.Kind.SYSTEM;
        };
    }

    /** Whether the entry is a {@code fetch_context} result, which can copy anyone's data in its workspace. */
    static boolean copiesPersonData(@Nullable JsonNode piEntry) {
        return piEntry != null
                && kind(piEntry) == MentorEntry.Kind.TOOL_RESULT
                && FETCH_CONTEXT.equals(piEntry.path("message").path("toolName").asString(""));
    }

    /**
     * The text search and the app read: a message's text blocks (never thinking or tool calls), or a summary. A tool
     * result has none; its payload holds it.
     */
    static String text(JsonNode piEntry) {
        if (piEntry.path("type").asString("").equals("compaction")) {
            return piEntry.path("summary").asString("");
        }
        JsonNode message = piEntry.path("message");
        if ("toolResult".equals(message.path("role").asString(""))) {
            return "";
        }
        return messageText(message.path("content"));
    }

    /**
     * The text Heph wrote so far in a host's partial answer ({@code messages}: the turn's assistant messages, the last
     * one maybe unfinished), as the app shows the stored messages: each message's text a paragraph of its own. With
     * {@code toFirstFeedback}, the text ends with the first message that calls {@code link_observation}: the feedback
     * it shows follows that message, and only the stored answer carries it.
     */
    static String partialText(JsonNode partial, boolean toFirstFeedback) {
        List<String> paragraphs = new ArrayList<>();
        for (JsonNode message : partial.path("messages")) {
            String text = messageText(message.path("content"));
            if (!text.isBlank()) {
                paragraphs.add(text);
            }
            if (toFirstFeedback && !messageLinks(message).isEmpty()) {
                break;
            }
        }
        return String.join("\n\n", paragraphs);
    }

    /**
     * The {@code link_observation} calls of a Pi assistant message entry, in order. A call without an observation id or
     * without a {@code text} argument shows nothing, so it is not a link.
     */
    static List<Link> links(@Nullable JsonNode piEntry) {
        return piEntry == null ? List.of() : messageLinks(piEntry.path("message"));
    }

    private static List<Link> messageLinks(JsonNode message) {
        List<Link> links = new ArrayList<>();
        for (JsonNode block : message.path("content")) {
            JsonNode arguments = block.path("arguments");
            if (!"toolCall".equals(block.path("type").asString(""))
                    || !LINK_OBSERVATION.equals(block.path("name").asString(""))
                    || !arguments.path("text").isString()) {
                continue;
            }
            try {
                links.add(new Link(
                        block.path("id").asString(""),
                        UUID.fromString(arguments.path("observationId").asString("")),
                        arguments.path("text").asString()));
            } catch (IllegalArgumentException notAnId) {
                // The tool refuses a call without an observation id, so the call showed nothing.
            }
        }
        return links;
    }

    private static String messageText(JsonNode content) {
        if (content.isString()) {
            return content.asString();
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode block : content) {
            if (block.path("type").asString("").equals("text")) {
                texts.add(block.path("text").asString(""));
            }
        }
        return String.join("\n\n", texts);
    }
}
