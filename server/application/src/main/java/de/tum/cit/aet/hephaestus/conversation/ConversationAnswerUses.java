package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.ContextDocument;
import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.Kind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Derives What Hephaestus used for one answer from the tool calls that its turn stored, and from the outcomes of those
 * calls (ADR 0157 decision 8). It reads only the calls' arguments and whether each result was an error, never the
 * content of a result. It counts sources and names only the context documents, whose names are the product's own. It
 * never claims to list everything, because the turn also read the conversation, which the app says in a fixed line.
 */
final class ConversationAnswerUses {

    /** The tools whose calls open a source. The link tools are what the answer cited, so they stay out. */
    static final Set<String> OPENING_TOOLS = Set.of("read", "bash", "load_context", "search_conversation");

    /** Where the turn's working directory is, as the host names paths in it. */
    private static final String ROOT = "/workspace/work/";

    private static final String DOCUMENTS = "inputs/context/";

    private ConversationAnswerUses() {}

    /** One stored tool call: its id, its tool and its {@code path} argument, if it has one. */
    record ToolUse(String callId, String tool, @Nullable String path) {}

    /** What one call's result was: an error or not. */
    record Outcome(String callId, String tool, boolean error) {}

    /**
     * What the answer used. A call whose result the turn did not store is listed under could not check, because nothing
     * shows that it opened its source.
     *
     * @param recorded whether the turn stored its entries with their tool calls
     * @param uses the turn's calls of {@link #OPENING_TOOLS}, in call order
     * @param outcomes the outcomes of those calls, paired with a call by its id and tool
     * @param cited whether a message of the answer links an observation, a problem or feedback
     * @param askedFromPage whether the person asked from a page of the app
     */
    static ConversationAnswerUseDTO of(
            boolean recorded, List<ToolUse> uses, List<Outcome> outcomes, boolean cited, boolean askedFromPage) {
        if (!recorded) return ConversationAnswerUseDTO.unrecorded(cited, askedFromPage);
        Map<String, Boolean> failed = new LinkedHashMap<>();
        for (Outcome outcome : outcomes) failed.put(outcome.callId() + ":" + outcome.tool(), outcome.error());
        Sources read = new Sources();
        Sources couldNot = new Sources();
        for (ToolUse use : uses) {
            Boolean error = failed.get(use.callId() + ":" + use.tool());
            Sources into = error == null || error ? couldNot : read;
            switch (use.tool()) {
                case "load_context" -> into.add(Kind.REPOSITORY, null, repository(use.path()));
                case "read" -> {
                    String path = path(use.path());
                    if (path.startsWith(DOCUMENTS)) {
                        into.add(
                                Kind.CONTEXT_DOCUMENT,
                                ContextDocument.ofFile(path.substring(DOCUMENTS.length()))
                                        .orElse(null),
                                path);
                    } else {
                        into.add(Kind.FILES, null, path);
                    }
                }
                case "bash" -> into.add(Kind.COMMAND, null, use.callId());
                case "search_conversation" -> into.add(Kind.CONVERSATION_SEARCH, null, use.callId());
                default -> {}
            }
        }
        return new ConversationAnswerUseDTO(true, read.list(), couldNot.list(), cited, askedFromPage);
    }

    /** The repository that a {@code load_context} call named, without its {@code @ref}, only to count it once. */
    private static String repository(@Nullable String argument) {
        String path = argument == null ? "" : argument.strip();
        int at = path.indexOf('@');
        return path(at < 0 ? path : path.substring(0, at));
    }

    /**
     * A call's path, relative to the working directory and resolved as the sandbox resolves it, only to count each file
     * once: {@code INDEX.md} and {@code inputs/../INDEX.md} are one file.
     */
    private static String path(@Nullable String argument) {
        String path = argument == null ? "" : argument.strip();
        if (path.startsWith(ROOT)) path = path.substring(ROOT.length());
        Deque<String> parts = new ArrayDeque<>();
        // The limit keeps empty parts, which the loop skips, so a trailing slash cannot change the result.
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    /**
     * Sources in the order of their first use. A document counts once; repositories, files, commands and searches count
     * once for each distinct key, so two reads of one file are one file.
     */
    private static final class Sources {
        private final Map<String, Set<String>> keys = new LinkedHashMap<>();
        private final Map<String, ConversationUsedSourceDTO> byGroup = new LinkedHashMap<>();

        void add(Kind kind, @Nullable ContextDocument document, String key) {
            String group = kind == Kind.CONTEXT_DOCUMENT ? kind + ":" + document : kind.name();
            Set<String> seen = keys.computeIfAbsent(group, ignored -> new LinkedHashSet<>());
            seen.add(kind == Kind.CONTEXT_DOCUMENT && document != null ? "" : key);
            byGroup.put(group, new ConversationUsedSourceDTO(kind, document, seen.size()));
        }

        List<ConversationUsedSourceDTO> list() {
            return new ArrayList<>(byGroup.values());
        }
    }
}
