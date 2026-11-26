package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.ContextDocument;
import de.tum.cit.aet.hephaestus.conversation.ConversationUsedSourceDTO.Kind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Derives What Hephaestus used for one answer from the tool calls that its turn stored, and from the outcomes of those
 * calls (ADR 0157 decision 8). It reads only the calls' arguments and whether each result was an error, never the
 * content of a result. It lists what the turn opened. It never claims to list everything, because the turn also read
 * the conversation, which the app says in a fixed line.
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
     * What the answer used.
     *
     * @param recorded whether the turn stored its entries with their tool calls
     * @param uses the turn's calls of {@link #OPENING_TOOLS}, in call order
     * @param outcomes the outcomes of those calls; a call without one did not finish and is left out
     */
    static ConversationAnswerUseDTO of(boolean recorded, List<ToolUse> uses, List<Outcome> outcomes) {
        if (!recorded) return new ConversationAnswerUseDTO(false, List.of(), List.of());
        Map<String, Boolean> failed = new LinkedHashMap<>();
        for (Outcome outcome : outcomes) failed.put(outcome.callId(), outcome.error());
        List<String> repositories = uses.stream()
                .filter(use -> use.tool().equals("load_context"))
                .map(use -> repository(use.path()))
                .flatMap(Optional::stream)
                .toList();
        Sources read = new Sources();
        Sources couldNot = new Sources();
        for (ToolUse use : uses) {
            Boolean error = failed.get(use.callId());
            if (error == null) continue;
            Sources into = error ? couldNot : read;
            switch (use.tool()) {
                case "load_context" ->
                    repository(use.path()).ifPresent(repository -> into.add(Kind.REPOSITORY, null, repository));
                case "read" ->
                    path(use.path()).ifPresent(path -> {
                        if (path.startsWith(DOCUMENTS)) {
                            ContextDocument.ofFile(path.substring(DOCUMENTS.length()))
                                    .ifPresent(document -> into.add(Kind.CONTEXT_DOCUMENT, document, null));
                        } else {
                            into.add(Kind.REPOSITORY_FILES, null, owner(path, repositories));
                        }
                    });
                case "bash" -> into.add(Kind.CONTEXT_SEARCH, null, null);
                case "search_conversation" -> into.add(Kind.CONVERSATION_SEARCH, null, null);
                default -> {}
            }
        }
        return new ConversationAnswerUseDTO(true, read.list(), couldNot.list());
    }

    /** The repository that a {@code load_context} call named, without its {@code @ref}. */
    private static Optional<String> repository(@Nullable String argument) {
        String path = argument == null ? "" : argument.strip();
        int at = path.indexOf('@');
        String repository = at < 0 ? path : path.substring(0, at);
        repository = trimSlashes(repository.startsWith(ROOT) ? repository.substring(ROOT.length()) : repository);
        return repository.isEmpty() ? Optional.empty() : Optional.of(repository);
    }

    /** A {@code read} call's path, relative to the working directory. */
    private static Optional<String> path(@Nullable String argument) {
        String path = argument == null ? "" : argument.strip();
        if (path.startsWith(ROOT)) path = path.substring(ROOT.length());
        while (path.startsWith("./")) path = path.substring(2);
        return path.isEmpty() ? Optional.empty() : Optional.of(path);
    }

    /** The loaded repository that holds a file, or none when the file is in no loaded repository. */
    private static @Nullable String owner(String path, List<String> repositories) {
        return repositories.stream()
                .filter(repository -> path.startsWith(repository + "/"))
                .findFirst()
                .orElse(null);
    }

    private static String trimSlashes(String text) {
        String trimmed = text;
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    /** Sources in the order of their first use, each counted once, with the files and searches counted. */
    private static final class Sources {
        private final Map<String, ConversationUsedSourceDTO> byKey = new LinkedHashMap<>();

        void add(Kind kind, @Nullable ContextDocument document, @Nullable String repository) {
            String key = kind + ":" + document + ":" + repository;
            ConversationUsedSourceDTO known = byKey.get(key);
            boolean counted =
                    kind == Kind.REPOSITORY_FILES || kind == Kind.CONTEXT_SEARCH || kind == Kind.CONVERSATION_SEARCH;
            int count = known == null ? 1 : counted ? known.count() + 1 : known.count();
            byKey.put(key, new ConversationUsedSourceDTO(kind, document, repository, count));
        }

        List<ConversationUsedSourceDTO> list() {
            return new ArrayList<>(byKey.values());
        }
    }
}
