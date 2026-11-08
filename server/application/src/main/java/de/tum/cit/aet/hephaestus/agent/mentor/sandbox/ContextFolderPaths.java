package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/** Paths in the context folder: the tools workspace of the turn's tools Pod, where its host puts what it loads. */
final class ContextFolderPaths {

    static final String ROOT = "/tmp/tools/workspace";

    /** Where the context documents are, as {@code ContentSource#OUTPUT_PREFIX} names them. */
    static final String DOCUMENTS = "inputs/context/";

    private ContextFolderPaths() {}

    /**
     * The path relative to the folder: a path relative to it, or absolute under it. A path that leaves the folder, or
     * names something outside it, has none. The empty path is the folder itself.
     */
    static Optional<String> relative(String path) {
        String trimmed = path.strip();
        if (trimmed.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        String rest;
        if (trimmed.equals(ROOT) || trimmed.startsWith(ROOT + "/")) {
            rest = trimmed.substring(ROOT.length());
        } else if (trimmed.startsWith("/") || trimmed.startsWith("~")) {
            return Optional.empty();
        } else {
            rest = trimmed;
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : rest.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return Optional.empty();
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        return Optional.of(String.join("/", segments));
    }

    /** Whether the worker serves the path from the turn's memory: {@code INDEX.md}, {@code FIELDS.md} and the documents. */
    static boolean document(String relative) {
        return relative.equals(ContextFolderIndex.PATH)
                || relative.equals(ContextFolderIndex.FIELDS_PATH)
                || relative.startsWith(DOCUMENTS);
    }
}
