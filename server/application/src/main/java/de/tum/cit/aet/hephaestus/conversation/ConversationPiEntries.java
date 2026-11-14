package de.tum.cit.aet.hephaestus.conversation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Reads Pi session entries (Pi {@code docs/session-format.md}) into ledger kinds, plain text and links. */
final class ConversationPiEntries {

    /** Hephaestus's link tools (main's conversation protocol): what they show, by the id argument each one names. */
    enum LinkKind {
        RESULT("link_result", "resultId"),
        PROBLEM("link_problem", "problemId"),
        FEEDBACK("show_feedback", "feedbackId");

        final String tool;
        final String idArgument;

        LinkKind(String tool, String idArgument) {
            this.tool = tool;
            this.idArgument = idArgument;
        }

        static @Nullable LinkKind of(String tool) {
            for (LinkKind kind : values()) {
                if (kind.tool.equals(tool)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * The tools whose results copy other people's data ({@link ConversationEntryRepository#COPIES_PERSON_DATA}): the context
     * folder's {@code read}, {@code bash} and {@code load_context}, and {@code fetch_context}, which earlier turns
     * stored.
     */
    static final List<String> COPYING_TOOLS = List.of("read", "bash", "load_context", "fetch_context");

    /**
     * A well-formed id in any letter case. {@link UUID#fromString} alone takes shorter groups too. The database
     * function {@code conversation_receipt_words} matches the same form, so a receipt and the timeline read the same
     * calls.
     */
    static final Pattern CANONICAL_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ConversationPiEntries() {}

    /**
     * A link tool call: the tool call id, what it shows (a result, a problem or prepared feedback), its id, and Hephaestus's
     * words about it, which are shown to the developer.
     */
    record Link(String callId, LinkKind kind, UUID id, String text) {

        /** This call as {@code turnId} made it. */
        ShownCall in(UUID turnId) {
            return new ShownCall(turnId, callId, kind.tool);
        }
    }

    /**
     * A link tool call that succeeded: the turn that made it, its tool call id and its tool. A call id is unique only
     * in its turn, so a call shows only with a result of its own turn and tool.
     */
    record ShownCall(UUID turnId, String callId, String tool) {}

    static ConversationEntry.Kind kind(JsonNode piEntry) {
        String type = piEntry.path("type").asString("");
        if (type.equals("compaction")) {
            return ConversationEntry.Kind.SUMMARY;
        }
        if (!type.equals("message")) {
            return ConversationEntry.Kind.SYSTEM;
        }
        return switch (piEntry.path("message").path("role").asString("")) {
            case "user" -> ConversationEntry.Kind.USER;
            case "assistant" -> ConversationEntry.Kind.ANSWER;
            case "toolResult" -> ConversationEntry.Kind.TOOL_RESULT;
            default -> ConversationEntry.Kind.SYSTEM;
        };
    }

    /** Whether the entry is a result of a {@link #COPYING_TOOLS} tool, which can copy anyone's data in its workspace. */
    static boolean copiesPersonData(@Nullable JsonNode piEntry) {
        return piEntry != null
                && kind(piEntry) == ConversationEntry.Kind.TOOL_RESULT
                && COPYING_TOOLS.contains(
                        piEntry.path("message").path("toolName").asString(""));
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
     * The text Hephaestus wrote so far in a host's partial answer ({@code messages}: the turn's assistant messages, the last
     * one maybe unfinished), as the app shows the stored messages: each message's text a paragraph of its own. With
     * {@code toFirstFeedback}, the text ends with the first message that calls a link tool: what it shows follows that
     * message, and only the stored answer carries it.
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
     * The link tool calls in {@code appended}, one turn's entries, whose tool result is no error: the calls whose words the app
     * shows ({@link ConversationEntryRepository#SUCCEEDED_LINK_RESULT} reads the same results). A turn's end appends a call
     * and its result together.
     */
    static Set<ShownCall> succeededLinkCalls(UUID turnId, List<JsonNode> appended) {
        Set<ShownCall> succeeded = new HashSet<>();
        for (JsonNode piEntry : appended) {
            JsonNode message = piEntry.path("message");
            String tool = message.path("toolName").asString("");
            if ("toolResult".equals(message.path("role").asString(""))
                    && LinkKind.of(tool) != null
                    && !message.path("isError").asBoolean(false)) {
                succeeded.add(new ShownCall(turnId, message.path("toolCallId").asString(""), tool));
            }
        }
        return succeeded;
    }

    /**
     * The words the app shows for a Hephaestus message: its text, then the {@code text} of each link call in
     * {@code succeeded}, in call order. Thinking, other tool calls and tool results stay out.
     */
    static String searchText(JsonNode piEntry, UUID turnId, Set<ShownCall> succeeded) {
        List<String> words = new ArrayList<>();
        String text = text(piEntry);
        if (!text.isEmpty()) {
            words.add(text);
        }
        links(piEntry).stream()
                .filter(link -> succeeded.contains(link.in(turnId)))
                .forEach(link -> words.add(link.text()));
        return String.join("\n\n", words);
    }

    /**
     * The link tool calls of a Pi assistant message entry, in order. A call without a valid id or without a
     * {@code text} argument shows nothing, so it is not a link.
     */
    static List<Link> links(@Nullable JsonNode piEntry) {
        return piEntry == null ? List.of() : messageLinks(piEntry.path("message"));
    }

    /**
     * The feedback the host may have shown in an attempt: the {@code show_feedback} calls of the entries it appended
     * and of its last partial answer, whatever their results. A stop or a failure can cut a call off after its words
     * were on screen.
     */
    static Set<UUID> feedbackCalled(List<JsonNode> appended, @Nullable JsonNode partial) {
        Set<UUID> called = new HashSet<>();
        for (JsonNode piEntry : appended) {
            links(piEntry).stream()
                    .filter(link -> link.kind() == LinkKind.FEEDBACK)
                    .forEach(link -> called.add(link.id()));
        }
        if (partial != null) {
            for (JsonNode message : partial.path("messages")) {
                messageLinks(message).stream()
                        .filter(link -> link.kind() == LinkKind.FEEDBACK)
                        .forEach(link -> called.add(link.id()));
            }
        }
        return called;
    }

    private static List<Link> messageLinks(JsonNode message) {
        List<Link> links = new ArrayList<>();
        for (JsonNode block : message.path("content")) {
            JsonNode arguments = block.path("arguments");
            LinkKind kind = "toolCall".equals(block.path("type").asString(""))
                    ? LinkKind.of(block.path("name").asString(""))
                    : null;
            if (kind == null || !arguments.path("text").isString()) {
                continue;
            }
            String id = arguments.path(kind.idArgument).asString("");
            // The tool refuses a call without a valid id, so the call showed nothing.
            if (CANONICAL_ID.matcher(id).matches()) {
                links.add(new Link(
                        block.path("id").asString(""),
                        kind,
                        UUID.fromString(id),
                        arguments.path("text").asString()));
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
