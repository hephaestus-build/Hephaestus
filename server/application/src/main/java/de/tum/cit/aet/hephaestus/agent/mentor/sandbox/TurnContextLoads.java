package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Prepares context loads on the server. Tools read the archives in their own sandbox. */
@WorkspaceAgnostic("The turn credential binds the workspace and the developer of every call")
public class TurnContextLoads implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TurnContextLoads.class);

    static final int MAX_LOADS_PER_ATTEMPT = 5;
    static final int LOADS_AT_ONCE = 2;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** How often a running load reads whether its attempt still holds its lease. */
    private static final Duration LEASE_CHECK = Duration.ofSeconds(2);

    private final MentorTurnRepository turns;
    private final ObjectProvider<ContextLoader> loaders;
    private final Clock clock;
    private final PersonDataCopyFence copies;
    private final FabricLayout layout;
    private final Semaphore loading = new Semaphore(LOADS_AT_ONCE);

    /** Loads run here, so that an attempt's end can stop waiting for one; each takes a virtual thread. */
    private final ExecutorService preparations = Executors.newVirtualThreadPerTaskExecutor();

    public TurnContextLoads(
            MentorTurnRepository turns,
            ObjectProvider<ContextLoader> loaders,
            Clock clock,
            PersonDataCopyFence copies,
            FabricLayout layout) {
        this.turns = turns;
        this.loaders = loaders;
        this.clock = clock;
        this.copies = copies;
        this.layout = layout;
    }

    @Override
    public void close() {
        preparations.shutdownNow();
    }

    /** Each loader's line for the turn's situation, so the model can load in its first round; empty when none. */
    public String situation(TurnAttempt turn) {
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

    /** The same build supplies the model's receipt and every file extracted in the sandbox. */
    public byte[] documentsArchive(TurnAttempt turn, Map<String, byte[]> documents, String builtAt) {
        Map<String, byte[]> files = new LinkedHashMap<>(documents);
        List<String> sections = new ArrayList<>();
        loaders.orderedStream().forEach(loader -> sections.add(section(loader, turn)));
        files.put(ContextFolderIndex.FIELDS_PATH, ContextFolderIndex.FIELDS);
        files.put(
                ContextFolderIndex.PATH,
                ContextFolderIndex.render(files.keySet(), builtAt, sections).getBytes(StandardCharsets.UTF_8));
        return FolderArchive.files(files);
    }

    /** Counts a context load in the transaction that checks the sandbox, run and turn fences. */
    public Optional<Refused> admit(TurnAttempt turn) {
        if (turns.countToolsLoad(turn.turnId(), turn.workspaceId(), turn.attempt(), turn.runId(), MAX_LOADS_PER_ATTEMPT)
                != 0) return Optional.empty();
        return Optional.of(new Refused(
                turns.hasLiveLease(turn.turnId(), turn.workspaceId(), turn.attempt(), turn.runId())
                        ? refusal("BUDGET_EXHAUSTED", "This answer already loaded " + MAX_LOADS_PER_ATTEMPT + " items.")
                        : refusal("TIMEOUT", "The answer ended before the item could load.")));
    }

    /**
     * {@code load_context}: prepares one item through the loader that owns its path. The answer is the item as an
     * archive, with what the model reads about it and the command whose output follows that, or a refusal.
     */
    public Load prepare(TurnAttempt turn, Instant deadline, String path) {
        String requested = path.strip();
        Optional<ContextLoader> owner = loaders.orderedStream()
                .filter(loader -> requested.startsWith(loader.prefix()))
                .findFirst();
        if (owner.isEmpty()) {
            return new Refused(
                    refusal("INVALID_PATH", "Nothing can be loaded at " + requested + ". INDEX.md lists what can."));
        }
        try {
            return prepare(turn, deadline, owner.get(), requested);
        } catch (Abandoned | CancellationException ended) {
            return new Refused(refusal("TIMEOUT", "The answer ended before " + requested + " could load."));
        }
    }

    private Load archive(TurnAttempt turn, Instant deadline, ContextLoader owner, String requested) {
        BooleanSupplier abandoned = abandoned(turn, deadline);
        long started = System.nanoTime();
        ContextLoader.Loaded loaded = null;
        boolean transferred = false;
        var admission = layout.spool().capture(turn.workspaceId(), copies::capture);
        try (admission) {
            if (abandoned.getAsBoolean()) throw new CancellationException();
            loaded = owner.load(turn.workspaceId(), turn.accountId(), requested, abandoned);
            if (abandoned.getAsBoolean()) throw new CancellationException();
            Optional<String> relative =
                    ContextFolderPaths.relative(loaded.path()).filter(found -> !found.isEmpty());
            if (relative.isEmpty()) throw new SandboxException("A context loader named a path outside the folder");
            ContextLoader.Version version = loaded.version();
            ObjectNode answer = NODES.objectNode().put("ok", true).put("path", relative.get());
            answer.putObject("resolved")
                    .put("ref", version.ref())
                    .put("sha", version.id())
                    .put("cite", version.cite());
            answer.put("note", loaded.note());
            ArrayNode summary = answer.putArray("summary");
            loaded.summary().forEach(summary::add);
            MessageDigest digest = sha256();
            // Counting and hashing discard the bytes. No extra server archive can survive a restart.
            long size = FolderArchive.directory(
                    loaded.directory(), new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            Prepared prepared =
                    new Prepared(answer, loaded, size, HexFormat.of().formatHex(digest.digest()));
            transferred = true;
            log.info(
                    "Turn load prepared: turnId={}, elapsedMs={}",
                    turn.turnId(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            return prepared;
        } catch (CancellationException ended) {
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
            if (!transferred && loaded != null) closeSource(loaded);
        }
    }

    /** What a load answers: a refusal, or the item retained until its archive has been streamed. */
    public sealed interface Load permits Refused, Prepared {}

    public record Refused(ObjectNode answer) implements Load {}

    /**
     * The retained source and its capture admission. Tar bytes are reproduced directly into the HTTP response,
     * without a duplicate archive on the server. The source is immutable until closed.
     */
    public record Prepared(ObjectNode answer, ContextLoader.Loaded source, long size, String sha256)
            implements Load, AutoCloseable {
        public void writeTo(OutputStream out) throws IOException {
            FolderArchive.directory(source.directory(), out);
        }

        @Override
        public void close() {
            closeSource(source);
        }
    }

    private static void closeSource(ContextLoader.Loaded source) {
        Path directory = source.directory();
        source.close();
        if (!Files.notExists(directory))
            throw new UncheckedIOException(
                    "A context loader left its files after closing", new IOException(directory.toString()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
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

    private Load prepare(TurnAttempt turn, Instant deadline, ContextLoader loader, String path) {
        try {
            if (!loading.tryAcquire(Math.max(0, left(deadline).toMillis()), TimeUnit.MILLISECONDS)) {
                throw new Abandoned(new TimeoutException("No load permit before the deadline"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Abandoned(interrupted);
        }
        CompletableFuture<Load> prepared;
        try {
            prepared = CompletableFuture.supplyAsync(() -> archive(turn, deadline, loader, path), preparations);
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
            // The preparation owns capture admission until its late archive and source directory are removed.
            var discarded = prepared.thenAccept(load -> {
                if (load instanceof Prepared archive) archive.close();
            });
            discarded.exceptionally(failure -> {
                log.warn(
                        "Could not remove an abandoned load archive: {}",
                        failure.getClass().getSimpleName());
                return null;
            });
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

    /** Whether a load outlived its turn's deadline or its attempt's lease; reads the lease at most every 2 s. */
    private BooleanSupplier abandoned(TurnAttempt turn, Instant deadline) {
        var checked = new AtomicReference<Instant>(clock.instant());
        var lost = new AtomicBoolean();
        return () -> {
            if (left(deadline).isNegative() || lost.get()) return true;
            Instant now = clock.instant();
            Instant last = checked.get();
            if (Duration.between(last, now).compareTo(LEASE_CHECK) >= 0 && checked.compareAndSet(last, now)) {
                lost.set(!turns.hasLiveLease(turn.turnId(), turn.workspaceId(), turn.attempt(), turn.runId()));
            }
            return lost.get();
        };
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
