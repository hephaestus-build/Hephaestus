package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The files one composition renders its turns from, held in memory under workspace paths: the retained capture of the
 * review, and the composition files written from the server's reads. The model reads only what a turn shows.
 */
final class ComposerFiles {
    private static final String ROOT = "/workspace";
    static final String CONTEXT_ROOT = SandboxLayout.CONTEXT_PREFIX.replaceFirst("/$", "");
    static final String REPOSITORY_ROOT = SandboxLayout.REPO_MOUNT_RELATIVE.replaceFirst("/$", "");
    static final String PRACTICE_INDEX = SandboxLayout.PRACTICES_PREFIX + "index.json";
    static final String HISTORY_ROOT = SandboxLayout.INPUTS_PREFIX + "history";
    static final String SUPPORT = "composition/in-app-support.json";
    static final String DELIVERED = "composition/history/feedback.json";
    static final String PREPARED = "composition/history/prepared.json";

    private final Map<String, byte[]> files;

    ComposerFiles(Map<String, byte[]> files) {
        this.files = new TreeMap<>(files);
    }

    static String criteria(String practiceSlug) {
        return SandboxLayout.PRACTICES_PREFIX + practiceSlug + ".md";
    }

    static String problems() {
        return HISTORY_ROOT + "/problems.json";
    }

    void put(String path, byte[] bytes) {
        files.put(relative(path), bytes);
    }

    void remove(String path) {
        files.remove(relative(path));
    }

    boolean exists(String path) {
        return files.containsKey(relative(path));
    }

    /** The bytes at a path, or none. */
    byte @Nullable [] bytes(String path) {
        return files.get(relative(path));
    }

    /** The file names directly under a folder, as {@code readdirSync} lists them. */
    List<String> list(String folder) {
        String prefix = relative(folder) + "/";
        return files.keySet().stream()
                .filter(path -> path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0)
                .map(path -> path.substring(prefix.length()))
                .toList();
    }

    /** The text at a path, decoded as UTF-8 with malformed bytes replaced, as Node's {@code readFileSync(path, "utf8")}. */
    Optional<String> text(String path) {
        byte[] bytes = bytes(path);
        return bytes == null ? Optional.empty() : Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    /** The text at a path only when it is valid UTF-8, as a fatal {@code TextDecoder}. */
    static Optional<String> strictUtf8(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString());
        } catch (CharacterCodingException invalid) {
            return Optional.empty();
        }
    }

    /** The parsed JSON at a path; empty when absent or not JSON. */
    Optional<JsonNode> json(String path, ObjectMapper mapper) {
        byte[] bytes = bytes(path);
        if (bytes == null) return Optional.empty();
        try {
            return Optional.ofNullable(mapper.readTree(bytes));
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
    }

    /** A path relative to the workspace root, whether the capture names it absolute or relative. */
    static String relative(String path) {
        String trimmed = path.startsWith(ROOT + "/") ? path.substring(ROOT.length() + 1) : path;
        while (trimmed.startsWith("./")) trimmed = trimmed.substring(2);
        return trimmed;
    }
}
