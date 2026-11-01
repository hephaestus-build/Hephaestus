package de.tum.cit.aet.hephaestus.agent.mentor.context;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Captured documents for a turn. The gateway serves them to the conversation loop on each read. */
@Service
@ConditionalOnWorkerRole
public class ConversationContextDocuments {

    /** The largest item one load gives a tools Job, whose workspace holds 512 MiB. */
    static final long MAX_LOAD_BYTES = 384L * 1024 * 1024;

    private final ObjectProvider<ContextLoader> loaders;

    public ConversationContextDocuments(ObjectProvider<ContextLoader> loaders) {
        this.loaders = loaders;
    }

    public Map<String, byte[]> fixed(TurnAttempt turn, TurnDocuments documents) {
        Map<String, byte[]> files = new LinkedHashMap<>(documents.built());
        List<String> sections = loaders.orderedStream()
                .map(loader -> loader.index(turn.workspaceId(), turn.accountId()))
                .toList();
        files.put(ContextFolderIndex.FIELDS_PATH, ContextFolderIndex.FIELDS);
        files.put(
                ContextFolderIndex.PATH,
                ContextFolderIndex.render(files.keySet(), documents.builtAt(), sections)
                        .getBytes(StandardCharsets.UTF_8));
        return Map.copyOf(files);
    }

    public Optional<String> document(TurnAttempt turn, TurnDocuments documents, String requested) {
        Optional<String> relative = ContextFolderPaths.relative(requested);
        if (relative.isEmpty()) return Optional.empty();
        String path = relative.get();
        byte[] fixed = fixed(turn, documents).get(path);
        if (fixed != null) return Optional.of(new String(fixed, StandardCharsets.UTF_8));
        if (!path.startsWith(ContextFolderPaths.DOCUMENTS)) return Optional.empty();
        return documents.item(path).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    public byte[] archive(TurnAttempt turn, TurnDocuments documents) {
        List<String> destinations = loaders.orderedStream()
                .flatMap(loader -> loader.destinations(turn.workspaceId(), turn.accountId()).stream())
                .toList();
        return FolderArchive.files(fixed(turn, documents), destinations);
    }

    /** What a {@code load_context} of the turn gave: the item as a tar, or why it could not load. */
    public sealed interface Load {
        /**
         * @param archive a temporary tar file that the caller streams and then deletes
         * @param sha256 the tar's digest, which the loop checks as it receives it
         */
        record Loaded(
                String path,
                Path archive,
                long size,
                String sha256,
                ContextLoader.Version version,
                String note,
                List<String> summary)
                implements Load {}

        record Refused(String error, String detail) implements Load {}
    }

    /**
     * Loads the item at {@code requested} for the turn's developer, through the loader whose prefix it starts with,
     * and packs it as a tar in a temporary file, at most {@link #MAX_LOAD_BYTES}: the worker never holds a checkout in
     * memory.
     *
     * @param abandoned once it holds, the loader starts no further work
     */
    public Load load(TurnAttempt turn, String requested, BooleanSupplier abandoned) {
        Optional<ContextLoader> owner = loaders.orderedStream()
                .filter(loader -> requested.startsWith(loader.prefix()))
                .findFirst();
        if (owner.isEmpty()) {
            return new Load.Refused(
                    "INVALID_PATH", "Nothing can be loaded at " + requested + ". INDEX.md lists what can.");
        }
        try (ContextLoader.Loaded loaded =
                owner.get().load(turn.workspaceId(), turn.accountId(), requested, abandoned)) {
            Path archive = FolderArchive.directory(loaded.directory());
            try {
                long size = Files.size(archive);
                if (size > MAX_LOAD_BYTES) {
                    Files.deleteIfExists(archive);
                    return new Load.Refused(
                            "TOO_LARGE", requested + " is larger than the context folder holds. Load a smaller ref.");
                }
                return new Load.Loaded(
                        loaded.path(),
                        archive,
                        size,
                        sha256(archive),
                        loaded.version(),
                        loaded.note(),
                        loaded.summary());
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(archive);
                throw failed;
            }
        } catch (CancellationException ended) {
            return new Load.Refused("TIMEOUT", "The answer ended before " + requested + " could load.");
        } catch (ContextLoadException refused) {
            return new Load.Refused(
                    refused.code().name(),
                    Objects.toString(refused.getMessage(), refused.code().name()));
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not pack a loaded item", failed);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public String situation(TurnAttempt turn) {
        List<String> lines = new ArrayList<>();
        loaders.orderedStream().forEach(loader -> {
            String line = loader.situation(turn.workspaceId(), turn.accountId()).strip();
            if (!line.isEmpty()) lines.add(line.substring(0, Math.min(line.length(), ContextLoader.SITUATION_CHARS)));
        });
        return String.join("\n", lines);
    }
}
