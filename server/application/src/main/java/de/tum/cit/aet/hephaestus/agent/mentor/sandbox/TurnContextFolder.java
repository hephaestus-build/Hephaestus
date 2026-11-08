package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The worker's half of Heph's context folder for one turn attempt. It serves {@code INDEX.md}, {@code FIELDS.md} and
 * the context documents from the attempt's memory, binds the attempt's tools Pod ({@link TurnSandboxes}), and prepares
 * what {@code load_context} loads. The host puts the documents and each load into the tools Pod and runs every
 * command there itself: this worker runs none. A refusal is an answer with {@code ok: false}, never an HTTP error, so
 * the host passes its {@code detail} to the model.
 */
@WorkspaceAgnostic("The turn credential binds the workspace and the developer of every call")
public class TurnContextFolder {

    private static final Logger log = LoggerFactory.getLogger(TurnContextFolder.class);

    static final int MAX_LOADS_PER_ATTEMPT = 5;
    static final int LOADS_AT_ONCE = 2;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final TurnSandboxes sandboxes;
    private final ObjectProvider<ContextLoader> loaders;
    private final Clock clock;
    private final Semaphore loading = new Semaphore(LOADS_AT_ONCE);

    /** The loads of each attempt; an attempt ends within its deadline. */
    private final Cache<TurnSandboxes.Attempt, AtomicInteger> loads =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();

    /** Loads run here, so that an attempt's end can stop waiting for one; each takes a virtual thread. */
    private final ExecutorService preparations = Executors.newVirtualThreadPerTaskExecutor();

    public TurnContextFolder(TurnSandboxes sandboxes, ObjectProvider<ContextLoader> loaders, Clock clock) {
        this.sandboxes = sandboxes;
        this.loaders = loaders;
        this.clock = clock;
    }

    /** Whether this worker serves the folder now. */
    public boolean available() {
        return sandboxes.available();
    }

    /** Each loader's line for the turn's situation, so the model can load in its first round; empty when none. */
    public String situation(TurnAttempt turn) {
        if (!available()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        loaders.orderedStream().forEach(loader -> {
            try {
                String line =
                        loader.situation(turn.workspaceId(), turn.accountId()).strip();
                if (!line.isEmpty()) {
                    lines.add(
                            line.length() > ContextLoader.SITUATION_CHARS
                                    ? line.substring(0, ContextLoader.SITUATION_CHARS)
                                    : line);
                }
            } catch (RuntimeException e) {
                // The class only: a message can quote an item.
                log.warn(
                        "A context loader could not name what it loads: loader={}, failure={}",
                        loader.prefix(),
                        e.getClass().getSimpleName());
            }
        });
        return String.join("\n", lines);
    }

    /**
     * Pi's {@code read} of one page of {@code INDEX.md}, {@code FIELDS.md} or a context document; an item document that
     * the attempt did not build is read from its source.
     */
    public ObjectNode document(
            TurnAttempt turn, TurnDocuments documents, String path, @Nullable Integer offset, @Nullable Integer limit) {
        Optional<String> relative = ContextFolderPaths.relative(path).filter(ContextFolderPaths::document);
        if (relative.isEmpty()) {
            return refusal("NOT_FOUND", "File not found: " + path);
        }
        Optional<byte[]> content = documentBytes(turn, documents, relative.get());
        if (content.isEmpty()) {
            return refusal("NOT_FOUND", "File not found: " + path);
        }
        PiText.Page page = page(path, offset, limit, content.get());
        String error = page.offsetError();
        if (error != null) {
            return refusal("OFFSET_BEYOND_END", error);
        }
        return NODES.objectNode().put("ok", true).put("text", page.text());
    }

    private Optional<byte[]> documentBytes(TurnAttempt turn, TurnDocuments documents, String relative) {
        if (relative.equals(ContextFolderIndex.FIELDS_PATH)) {
            return Optional.of(ContextFolderIndex.FIELDS);
        }
        if (relative.equals(ContextFolderIndex.PATH)) {
            return Optional.of(index(turn, documents));
        }
        byte[] built = documents.built().get(relative);
        return built != null ? Optional.of(built) : documents.item(relative);
    }

    /** {@code INDEX.md}: the documents the attempt built, when, and what each loader can load. */
    private byte[] index(TurnAttempt turn, TurnDocuments documents) {
        List<String> sections = new ArrayList<>();
        loaders.orderedStream().forEach(loader -> sections.add(section(loader, turn)));
        List<String> listed = new ArrayList<>(documents.built().keySet());
        listed.add(ContextFolderIndex.FIELDS_PATH);
        listed.add(ContextFolderIndex.PATH);
        return ContextFolderIndex.render(listed, documents.builtAt(), sections).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The attempt's built documents as one archive, rooted at {@code inputs/}: the host puts it into the tools Pod, so
     * that {@code bash} sees them too.
     */
    public byte[] documentsArchive(TurnDocuments documents) {
        String root = ContextFolderPaths.DOCUMENTS.substring(0, ContextFolderPaths.DOCUMENTS.indexOf('/') + 1);
        Map<String, byte[]> files = new LinkedHashMap<>();
        documents.built().forEach((path, bytes) -> {
            if (path.startsWith(root)) files.put(path.substring(root.length()), bytes);
        });
        return FolderArchive.files(files);
    }

    /**
     * The attempt's tools Pod: where its host dials it, and its nonce. The first call of an attempt binds one, waiting
     * while the tools quota has no room.
     */
    public ObjectNode bind(TurnAttempt turn, Instant deadline) {
        TurnSandboxes.Sandbox sandbox;
        try {
            sandbox = sandboxes.acquire(
                    attempt(turn), turn.workspaceId(), () -> !clock.instant().isBefore(deadline));
        } catch (TurnSandboxes.ContextChanged changed) {
            return refusal(
                    "CONTEXT_CHANGED", "The context folder changed. Read it again; repositories must be loaded again.");
        } catch (SandboxException failed) {
            if (!clock.instant().isBefore(deadline)) {
                return refusal("TIMEOUT", "The answer ended before the tools were ready.");
            }
            log.warn(
                    "Could not bind a tools Pod: turnId={}, failure={}",
                    turn.turnId(),
                    failed.getClass().getSimpleName());
            return refusal("UNAVAILABLE", "This worker cannot run the context folder now. Do not retry.");
        }
        ToolsPods.Started pod;
        try {
            pod = sandbox.started();
        } catch (SandboxException removed) {
            return refusal("TIMEOUT", "The answer ended before the tools were ready.");
        }
        return NODES.objectNode()
                .put("ok", true)
                .put("id", pod.id())
                .put("host", pod.host())
                .put("port", pod.port())
                .put("nonce", pod.nonce());
    }

    /**
     * After a tools Pod failed its host, the attempt's next bind gets a new one: what the old one holds may be partial.
     */
    public void discard(TurnAttempt turn, String id) {
        sandboxes.discard(attempt(turn), id);
    }

    /**
     * {@code load_context}: prepares one item through the loader that owns its path. The answer is the item as an
     * archive, with what the model reads about it and the command whose output follows that, or a refusal.
     */
    public Load load(TurnAttempt turn, Instant deadline, String path) {
        String requested = path.strip();
        Optional<ContextLoader> owner = loaders.orderedStream()
                .filter(loader -> requested.startsWith(loader.prefix()))
                .findFirst();
        if (owner.isEmpty()) {
            return new Refused(
                    refusal("INVALID_PATH", "Nothing can be loaded at " + requested + ". INDEX.md lists what can."));
        }
        TurnSandboxes.Attempt attempt = attempt(turn);
        if (Objects.requireNonNull(loads.get(attempt, key -> new AtomicInteger()))
                        .incrementAndGet()
                > MAX_LOADS_PER_ATTEMPT) {
            return new Refused(
                    refusal("BUDGET_EXHAUSTED", "This answer already loaded " + MAX_LOADS_PER_ATTEMPT + " items."));
        }
        BooleanSupplier abandoned =
                () -> sandboxes.ended(attempt) || left(deadline).isNegative();
        long started = System.nanoTime();
        Path archive = null;
        try (ContextLoader.Loaded loaded = prepare(turn, deadline, owner.get(), requested, abandoned)) {
            Optional<String> relative =
                    ContextFolderPaths.relative(loaded.path()).filter(found -> !found.isEmpty());
            if (relative.isEmpty()) {
                throw new SandboxException("A context loader named a path outside the folder");
            }
            archive = FolderArchive.directory(loaded.directory());
            ContextLoader.Version version = loaded.version();
            ObjectNode answer = NODES.objectNode().put("ok", true).put("path", relative.get());
            answer.putObject("resolved")
                    .put("ref", version.ref())
                    .put("sha", version.id())
                    .put("cite", version.cite());
            answer.put("note", loaded.note());
            ArrayNode summary = answer.putArray("summary");
            loaded.summary().forEach(summary::add);
            log.info(
                    "Turn load prepared: turnId={}, elapsedMs={}",
                    turn.turnId(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            Prepared prepared = new Prepared(answer, archive, Files.size(archive), sha256(archive));
            archive = null;
            return prepared;
        } catch (Abandoned | CancellationException ended) {
            return new Refused(refusal("TIMEOUT", "The answer ended before " + requested + " could load."));
        } catch (ContextLoadException refused) {
            return new Refused(refusal(
                    refused.code().name(),
                    Objects.toString(refused.getMessage(), refused.code().name())));
        } catch (IOException | SandboxException failed) {
            log.warn(
                    "A turn load failed: turnId={}, failure={}",
                    turn.turnId(),
                    failed.getClass().getSimpleName());
            return new Refused(refusal("UNAVAILABLE", "The item could not be prepared. Try again once."));
        } finally {
            // Set only while no answer holds the archive: a failed load leaves no file behind.
            if (archive != null) deleteArchive(archive);
        }
    }

    /** What a load answers: a refusal, or the item's archive with what the model reads about it. */
    public sealed interface Load permits Refused, Prepared {}

    public record Refused(ObjectNode answer) implements Load {}

    /**
     * The caller sends the archive and then deletes it. Its size and SHA-256 go ahead of it: the tools side checks both
     * before it extracts.
     */
    public record Prepared(ObjectNode answer, Path archive, long size, String sha256) implements Load {
        public void delete() {
            deleteArchive(archive);
        }
    }

    private static void deleteArchive(Path archive) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException e) {
            log.warn("Could not delete a load archive: {}", e.getClass().getSimpleName());
        }
    }

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("Every Java platform has SHA-256", missing);
        }
    }

    private static String section(ContextLoader loader, TurnAttempt turn) {
        try {
            return loader.index(turn.workspaceId(), turn.accountId());
        } catch (RuntimeException e) {
            log.warn(
                    "A context loader could not list what it loads: loader={}, failure={}",
                    loader.prefix(),
                    e.getClass().getSimpleName());
            return "";
        }
    }

    /** One page of a document, as Pi's {@code read} pages a file, from the document's bytes. */
    static PiText.Page page(String path, @Nullable Integer offset, @Nullable Integer limit, byte[] content) {
        long newlines = 0;
        for (byte b : content) {
            if (b == '\n') newlines++;
        }
        long first = offset == null ? 1 : Math.max(1, offset.longValue());
        long lines = Math.min(limit == null ? Long.MAX_VALUE : Math.max(1, limit.longValue()), PiText.MAX_LINES + 1L);
        int start = 0;
        for (long line = 1; line < first && start < content.length; start++) {
            if (content[start] == '\n') line++;
        }
        int end = start;
        for (long taken = 0; end < content.length && taken < lines && end - start <= PiText.MAX_BYTES; end++) {
            if (content[end] == '\n') taken++;
        }
        end = Math.min(end, start + PiText.MAX_BYTES + 1);
        int firstLineEnd = start;
        while (firstLineEnd < content.length && content[firstLineEnd] != '\n') firstLineEnd++;
        byte[] chunk = Arrays.copyOfRange(content, start, end);
        return PiText.read(path, offset, limit, newlines, firstLineEnd - start, chunk);
    }

    private ContextLoader.Loaded prepare(
            TurnAttempt turn, Instant deadline, ContextLoader loader, String path, BooleanSupplier abandoned) {
        try {
            if (!loading.tryAcquire(Math.max(0, left(deadline).toMillis()), TimeUnit.MILLISECONDS)) {
                throw new Abandoned(new TimeoutException("No load permit before the deadline"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Abandoned(interrupted);
        }
        CompletableFuture<ContextLoader.Loaded> prepared;
        try {
            prepared = CompletableFuture.supplyAsync(
                    () -> loader.load(turn.workspaceId(), turn.accountId(), path, abandoned), preparations);
        } catch (RuntimeException rejected) {
            loading.release();
            throw rejected;
        }
        var permit = prepared.whenComplete((loaded, failure) -> loading.release());
        log.trace("Load permit held until the load ends: {}", permit);
        try {
            return prepared.get(Math.max(0, left(deadline).toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException waited) {
            if (waited instanceof InterruptedException) Thread.currentThread().interrupt();
            // Its files are deleted when it finishes.
            var discarded = prepared.thenAccept(ContextLoader.Loaded::close);
            discarded.exceptionally(failure -> null);
            throw new Abandoned(waited);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) {
                cause.addSuppressed(failed);
                throw cause;
            }
            throw new CompletionException(failed);
        }
    }

    /** Loads running now, for tests: each holds a permit until it ends. */
    int loadsRunning() {
        return LOADS_AT_ONCE - loading.availablePermits();
    }

    private static TurnSandboxes.Attempt attempt(TurnAttempt turn) {
        return new TurnSandboxes.Attempt(turn.turnId(), turn.attempt());
    }

    private Duration left(Instant deadline) {
        return Duration.between(clock.instant(), deadline);
    }

    static ObjectNode refusal(String code, String detail) {
        return NODES.objectNode().put("ok", false).put("error", code).put("detail", detail);
    }

    /** The load outlived the turn or the attempt ended. */
    private static final class Abandoned extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        Abandoned(Exception waited) {
            super(null, waited, false, false);
        }
    }
}
