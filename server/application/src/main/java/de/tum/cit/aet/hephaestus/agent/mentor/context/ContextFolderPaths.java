package de.tum.cit.aet.hephaestus.agent.mentor.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.regex.Pattern;

/** Paths in the context folder, which is {@code /workspace} in the turn sandbox. */
final class ContextFolderPaths {

    static final String ROOT = "/workspace";

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

    /** Where {@code bash} writes the full output of a command that it cut. */
    static final String OUTPUTS = "/tmp/heph-out/";

    private static final Pattern OUTPUT = Pattern.compile("[0-9]{1,9}\\.log");

    /**
     * The absolute path that {@code read} may read: a path in the folder, or a full output of {@code bash}. Empty for
     * anything else.
     */
    static Optional<String> readable(String path) {
        String trimmed = path.strip();
        if (trimmed.startsWith(OUTPUTS)) {
            String name = trimmed.substring(OUTPUTS.length());
            return OUTPUT.matcher(name).matches() ? Optional.of(OUTPUTS + name) : Optional.empty();
        }
        return relative(trimmed).map(ContextFolderPaths::absolute);
    }

    static String absolute(String relative) {
        return relative.isEmpty() ? ROOT : ROOT + "/" + relative;
    }
}
