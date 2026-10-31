package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Heph's context folder: what {@code read}, {@code bash} and {@code load_context} of one turn attempt act on. The first
 * call of an attempt takes a sandbox ({@link TurnSandboxes}) and stages {@code INDEX.md} and the attempt's context
 * documents into it. Every command runs in the sandbox as its unprivileged user; this worker receives at most one page
 * of a file or one tail of an output ({@link PiText}). A refusal is an answer with {@code ok: false}, never an HTTP
 * error, so the host passes its {@code detail} to the model.
 */
@WorkspaceAgnostic("The turn credential binds the workspace and the developer of every call")
public class TurnContextFolder {

    private static final Logger log = LoggerFactory.getLogger(TurnContextFolder.class);

    static final Duration MAX_COMMAND_TIME = Duration.ofSeconds(60);
    static final int MAX_LOADS_PER_ATTEMPT = 5;
    static final int LOADS_AT_ONCE = 2;

    /** How long staging may take at most; the turn's deadline bounds it further. */
    private static final Duration STAGE_TIME = Duration.ofSeconds(60);

    /** The command's own timeout, then its kill after two seconds, then this margin before the worker cancels it. */
    private static final Duration KILL_MARGIN = Duration.ofSeconds(5);

    private static final Duration READ_TIME = Duration.ofSeconds(30);

    /** How long a loader's summary of a staged item may take, and how much of it the note keeps. */
    private static final Duration SUMMARY_TIME = Duration.ofSeconds(10);

    private static final int SUMMARY_LIMIT = 8 * 1024;

    /** A page or a tail, its header line, and room. */
    private static final int OUTPUT_LIMIT = PiText.MAX_BYTES + 4096;

    /** Arguments: the file, the first line, the line count. Prints the newlines, the first line's bytes, the page. */
    static final String READ_SCRIPT = """
            f=$1; s=$2; n=$3
            if [ -d "$f" ]; then exit 4; fi
            if [ ! -e "$f" ] || [ ! -r "$f" ]; then exit 3; fi
            export LC_ALL=C
            l=$(awk -v s="$s" 'NR==s{print length($0); exit}' "$f")
            printf '%s %s\\n' "$(wc -l < "$f")" "${l:-0}"
            tail -n "+$s" -- "$f" | head -n "$n" | head -c 51201
            """;

    /**
     * Arguments: the output number, the timeout in seconds, the command. Prints the exit code, the output's newlines,
     * bytes and last line's bytes, then its tail.
     */
    static final String EXEC_SCRIPT = """
            mkdir -p /tmp/heph-out
            o=/tmp/heph-out/$1.log
            # The folder belongs to root, so Git would refuse its repositories as owned by another user.
            export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0='*'
            timeout --kill-after=2s "$2" bash -c "$3" > "$o" 2>&1 < /dev/null
            c=$?
            export LC_ALL=C
            printf '%s %s %s %s\\n' "$c" "$(wc -l < "$o")" "$(wc -c < "$o")" "$(tail -n 1 "$o" | wc -c)"
            tail -c 51202 "$o"
            """;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final TurnSandboxes sandboxes;
    private final ObjectProvider<ContextLoader> loaders;
    private final Clock clock;
    private final Semaphore loading = new Semaphore(LOADS_AT_ONCE);

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
                // The class only, as in every sandbox log here: a message can quote an item or the daemon's answer.
                log.warn(
                        "A context loader could not name what it loads: loader={}, failure={}",
                        loader.prefix(),
                        e.getClass().getSimpleName());
            }
        });
        return String.join("\n", lines);
    }

    /**
     * Pi's {@code read} of one file page; an unstaged item document is read from its source first.
     *
     * @param deadline when the attempt's turn ends, as the dispatcher holds it
     */
    public ObjectNode read(
            TurnAttempt turn,
            Instant deadline,
            TurnDocuments documents,
            String path,
            @Nullable Integer offset,
            @Nullable Integer limit) {
        Optional<String> file = ContextFolderPaths.readable(path);
        if (file.isEmpty()) {
            return refusal("NOT_FOUND", "File not found: " + path);
        }
        Optional<String> item =
                ContextFolderPaths.relative(path).filter(relative -> relative.startsWith(ContextFolderPaths.DOCUMENTS));
        return inFolder(turn, deadline, documents, sandbox -> {
            ExecSandboxes.Execution page =
                    reading(sandbox, deadline, () -> page(sandbox, file.get(), offset, limit, deadline));
            Integer missing = page.exitCode();
            if (missing != null && missing == 3 && item.isPresent()) {
                Optional<byte[]> document = documents.item(item.get());
                if (document.isPresent()) {
                    changing(turn, sandbox, deadline, () -> add(sandbox, Map.of(item.get(), document.get()), deadline));
                    page = reading(sandbox, deadline, () -> page(sandbox, file.get(), offset, limit, deadline));
                }
            }
            Integer exit = page.exitCode();
            if (exit == null) {
                return refusal("UNAVAILABLE", "Reading " + path + " took too long. Try again.");
            }
            return switch (exit) {
                case 0 -> page(path, offset, limit, page.stdout());
                case 3 -> refusal("NOT_FOUND", "File not found: " + path);
                case 4 -> refusal("IS_DIRECTORY", path + " is a directory. Use bash: ls " + path);
                default -> refusal("UNAVAILABLE", "Could not read " + path + ".");
            };
        });
    }

    /** Pi's {@code bash}: one command in the sandbox, bounded by a minute and by the turn's deadline. */
    public ObjectNode exec(
            TurnAttempt turn, Instant deadline, TurnDocuments documents, String command, @Nullable Integer timeoutMs) {
        return inFolder(turn, deadline, documents, sandbox -> {
            Duration bound = timeoutMs == null ? MAX_COMMAND_TIME : Duration.ofMillis(Math.max(1, timeoutMs));
            int number = sandbox.commands.incrementAndGet();
            long started = System.nanoTime();
            // The budget is computed once the folder is free: a wait for a load comes off the turn's time.
            Ran ran = reading(sandbox, deadline, () -> {
                Duration time = shortest(bound, MAX_COMMAND_TIME, left(deadline));
                if (time.isZero() || time.isNegative()) {
                    return new Ran(null);
                }
                String seconds = String.format(Locale.ROOT, "%.3f", time.toMillis() / 1000.0);
                return new Ran(sandboxes
                        .runtime()
                        .exec(
                                sandbox.containerId(),
                                new ExecSandboxes.Command(
                                        List.of(
                                                "sh",
                                                "-c",
                                                EXEC_SCRIPT,
                                                "exec",
                                                Integer.toString(number),
                                                seconds,
                                                command),
                                        time.plus(KILL_MARGIN),
                                        OUTPUT_LIMIT)));
            });
            ExecSandboxes.Execution run = ran.run();
            if (run == null) {
                return result(null, "", false, true, null);
            }
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (run.timedOut()) {
                cancelCommands(sandbox);
                log.info("Turn sandbox command killed: turnId={}, elapsedMs={}", turn.turnId(), elapsedMs);
                return result(null, "", false, true, null);
            }
            Header header = Header.of(run.stdout(), 4);
            if (header == null) {
                return refusal("UNAVAILABLE", "The command could not run in the sandbox.");
            }
            int exitCode = (int) header.value(0);
            // `timeout` exits 124 at its limit, and 137 when it had to kill.
            boolean timedOut = exitCode == SandboxLayout.EXIT_WORK_TIMEOUT || exitCode == SandboxLayout.EXIT_KILLED;
            if (timedOut) {
                // A child that left the command's process group, with setsid for example, outlives timeout.
                cancelCommands(sandbox);
            }
            String fullOutput = "/tmp/heph-out/" + number + ".log";
            PiText.Tail tail = PiText.bash(
                    header.value(1),
                    header.value(2),
                    lastLineBytes(header.value(3), header.rest()),
                    header.rest(),
                    fullOutput);
            log.debug(
                    "Turn sandbox command ran: turnId={}, elapsedMs={}, exitCode={}",
                    turn.turnId(),
                    elapsedMs,
                    exitCode);
            return result(exitCode, tail.text(), tail.truncated(), timedOut, tail.truncated() ? fullOutput : null);
        });
    }

    /** {@code load_context}: stages one item through the loader that owns its path. */
    public ObjectNode load(TurnAttempt turn, Instant deadline, TurnDocuments documents, String path) {
        String requested = path.strip();
        Optional<ContextLoader> owner = loaders.orderedStream()
                .filter(loader -> requested.startsWith(loader.prefix()))
                .findFirst();
        if (owner.isEmpty()) {
            return refusal("INVALID_PATH", "Nothing can be loaded at " + requested + ". INDEX.md lists what can.");
        }
        return inFolder(turn, deadline, documents, sandbox -> {
            if (sandbox.loads.incrementAndGet() > MAX_LOADS_PER_ATTEMPT) {
                return refusal("BUDGET_EXHAUSTED", "This answer already loaded " + MAX_LOADS_PER_ATTEMPT + " items.");
            }
            TurnSandboxes.Attempt attempt = attempt(turn);
            BooleanSupplier abandoned =
                    () -> !sandboxes.holds(attempt, sandbox) || left(deadline).isNegative();
            long started = System.nanoTime();
            try (ContextLoader.Loaded loaded = prepare(turn, deadline, owner.get(), requested, abandoned)) {
                // The summary describes what was staged: no other load may stage in between.
                StringBuilder summary = new StringBuilder();
                changing(turn, sandbox, deadline, () -> {
                    stageLoaded(sandbox, loaded, deadline);
                    summary.append(summary(sandbox, loaded, deadline));
                });
                ContextLoader.Version version = loaded.version();
                log.info(
                        "Turn sandbox load: turnId={}, elapsedMs={}",
                        turn.turnId(),
                        Duration.ofNanos(System.nanoTime() - started).toMillis());
                ObjectNode answer = NODES.objectNode().put("ok", true).put("path", loaded.path());
                answer.putObject("resolved")
                        .put("ref", version.ref())
                        .put("sha", version.id())
                        .put("cite", version.cite());
                return answer.put("note", summary.isEmpty() ? loaded.note() : loaded.note() + "\n" + summary);
            } catch (Abandoned | CancellationException ended) {
                return refusal("TIMEOUT", "The answer ended before " + requested + " could load.");
            } catch (ContextLoadException refused) {
                return refusal(
                        refused.code().name(),
                        Objects.toString(refused.getMessage(), refused.code().name()));
            }
        });
    }

    /** What one call does in the attempt's staged sandbox. */
    @FunctionalInterface
    private interface InFolder {
        ObjectNode apply(TurnSandboxes.Sandbox sandbox) throws IOException;
    }

    /** A step that runs in the sandbox. */
    @FunctionalInterface
    private interface Step<T> {
        T run() throws IOException;
    }

    /** A step that changes the folder. */
    @FunctionalInterface
    private interface Change {
        void run() throws IOException;
    }

    private ObjectNode inFolder(TurnAttempt turn, Instant deadline, TurnDocuments documents, InFolder call) {
        TurnSandboxes.Sandbox sandbox;
        try {
            sandbox = folder(turn, deadline, documents);
        } catch (Late late) {
            return refusal("TIMEOUT", "The answer ended before the context folder was free.");
        } catch (TurnSandboxes.ContextChanged changed) {
            return refusal(
                    "CONTEXT_CHANGED", "The context folder changed. Read it again; repositories must be loaded again.");
        } catch (SandboxException | IOException failed) {
            log.warn(
                    "Could not prepare a turn sandbox: turnId={}, failure={}",
                    turn.turnId(),
                    failed.getClass().getSimpleName());
            return refusal("UNAVAILABLE", "This worker cannot run the context folder now. Do not retry.");
        }
        try {
            return call.apply(sandbox);
        } catch (Late late) {
            return refusal("TIMEOUT", "The answer ended before the context folder was free.");
        } catch (Staging failed) {
            Throwable cause = failed.getCause();
            log.warn(
                    "Staging into a turn sandbox failed: turnId={}, failure={}",
                    turn.turnId(),
                    cause == null
                            ? failed.getClass().getSimpleName()
                            : cause.getClass().getSimpleName());
            return refusal("UNAVAILABLE", "The context folder could not be prepared. Try again once.");
        } catch (SandboxException failed) {
            if (sandbox.removed()) {
                return refusal("TIMEOUT", "The answer ended while the sandbox ran.");
            }
            // A command whose run or cancellation failed may still start, or still run, in the sandbox: the next
            // command gets a new one, which nothing of this one can reach.
            sandboxes.discard(attempt(turn), sandbox);
            log.warn(
                    "A turn sandbox call failed: turnId={}, failure={}",
                    turn.turnId(),
                    failed.getClass().getSimpleName());
            return refusal("UNAVAILABLE", "The sandbox failed. Try again once.");
        } catch (IOException failed) {
            if (sandbox.removed()) {
                return refusal("TIMEOUT", "The answer ended while the sandbox ran.");
            }
            log.warn(
                    "A turn sandbox call failed: turnId={}, failure={}",
                    turn.turnId(),
                    failed.getClass().getSimpleName());
            return refusal("UNAVAILABLE", "The sandbox failed. Try again once.");
        }
    }

    /** A step that reads the folder: it runs beside other readers, never during a change. */
    private <T> T reading(TurnSandboxes.Sandbox sandbox, Instant deadline, Step<T> step) throws IOException {
        lock(sandbox.lock.readLock(), deadline);
        try {
            return step.run();
        } finally {
            sandbox.lock.readLock().unlock();
        }
    }

    /**
     * A step that changes the folder: it runs alone. When it fails, the folder may hold part of a change, so the
     * sandbox is removed; the attempt's next call gets a new one and stages again.
     */
    private void changing(TurnAttempt turn, TurnSandboxes.Sandbox sandbox, Instant deadline, Change step)
            throws IOException {
        lock(sandbox.lock.writeLock(), deadline);
        try {
            step.run();
        } catch (SandboxException | IOException | UncheckedIOException failed) {
            sandboxes.discard(attempt(turn), sandbox);
            throw new Staging(failed);
        } finally {
            sandbox.lock.writeLock().unlock();
        }
    }

    /** Takes the lock, waiting at most until the turn's deadline. */
    private void lock(Lock lock, Instant deadline) {
        try {
            if (!lock.tryLock(Math.max(0, left(deadline).toMillis()), TimeUnit.MILLISECONDS)) {
                throw new Late();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Late late = new Late();
            late.initCause(interrupted);
            throw late;
        }
    }

    /** One command's run in the sandbox; none when no time was left. */
    private record Ran(ExecSandboxes.@Nullable Execution run) {}

    /** The attempt's sandbox, staged with {@code INDEX.md}, {@code FIELDS.md} and the context documents on first use. */
    private TurnSandboxes.Sandbox folder(TurnAttempt turn, Instant deadline, TurnDocuments documents)
            throws IOException {
        long started = System.nanoTime();
        TurnSandboxes.Sandbox sandbox = sandboxes.acquire(
                attempt(turn), turn.workspaceId(), () -> !clock.instant().isBefore(deadline));
        if (sandbox.staged) {
            return sandbox;
        }
        lock(sandbox.lock.writeLock(), deadline);
        try {
            if (!sandbox.staged) {
                long acquired = System.nanoTime();
                Map<String, byte[]> files = new LinkedHashMap<>(documents.built());
                List<String> sections = new ArrayList<>();
                loaders.orderedStream().forEach(loader -> sections.add(section(loader, turn)));
                files.put(ContextFolderIndex.FIELDS_PATH, ContextFolderIndex.FIELDS);
                files.put(
                        ContextFolderIndex.PATH,
                        ContextFolderIndex.render(files.keySet(), documents.builtAt(), sections)
                                .getBytes(StandardCharsets.UTF_8));
                try {
                    add(sandbox, files, deadline);
                } catch (SandboxException | IOException | UncheckedIOException failed) {
                    // A sandbox that is half staged is never used: the attempt gets a new one on its next call.
                    sandboxes.discard(attempt(turn), sandbox);
                    throw failed;
                }
                sandbox.staged = true;
                log.info(
                        "Turn sandbox staged: turnId={}, acquireMs={}, stageMs={}, files={}",
                        turn.turnId(),
                        Duration.ofNanos(acquired - started).toMillis(),
                        Duration.ofNanos(System.nanoTime() - acquired).toMillis(),
                        files.size());
            }
        } finally {
            sandbox.lock.writeLock().unlock();
        }
        return sandbox;
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

    /** @param file an absolute path in the sandbox ({@link ContextFolderPaths#readable}) */
    private ExecSandboxes.Execution page(
            TurnSandboxes.Sandbox sandbox,
            String file,
            @Nullable Integer offset,
            @Nullable Integer limit,
            Instant deadline) {
        long first = offset == null ? 1 : Math.max(1, offset.longValue());
        long lines = Math.min(limit == null ? Long.MAX_VALUE : Math.max(1, limit.longValue()), PiText.MAX_LINES + 1L);
        return sandboxes
                .runtime()
                .exec(
                        sandbox.containerId(),
                        new ExecSandboxes.Command(
                                List.of(
                                        "sh",
                                        "-c",
                                        READ_SCRIPT,
                                        "read",
                                        file,
                                        Long.toString(first),
                                        Long.toString(lines)),
                                shortest(READ_TIME, left(deadline)),
                                OUTPUT_LIMIT));
    }

    private static ObjectNode page(String path, @Nullable Integer offset, @Nullable Integer limit, byte[] stdout) {
        Header header = Header.of(stdout, 2);
        if (header == null) {
            return refusal("UNAVAILABLE", "Could not read " + path + ".");
        }
        PiText.Page page = PiText.read(path, offset, limit, header.value(0), header.value(1), header.rest());
        String error = page.offsetError();
        if (error != null) {
            return refusal("OFFSET_BEYOND_END", error);
        }
        return NODES.objectNode().put("ok", true).put("text", page.text());
    }

    /** Adds files to the folder, which is read-only for the model again after it. */
    private void add(TurnSandboxes.Sandbox sandbox, Map<String, byte[]> files, Instant deadline) throws IOException {
        byte[] archive = FolderArchive.files(files);
        List<String> names = files.keySet().stream()
                .map(path -> path.contains("/") ? path.substring(0, path.indexOf('/')) : path)
                .distinct()
                .toList();
        try (InputStream in = new ByteArrayInputStream(archive)) {
            sandboxes
                    .runtime()
                    .stage(
                            sandbox.containerId(),
                            new ExecSandboxes.Add(names, in, archive.length, shortest(STAGE_TIME, left(deadline))));
        }
    }

    /** Replaces the loaded item's directory in the folder, which is read-only for the model again after it. */
    private void stageLoaded(TurnSandboxes.Sandbox sandbox, ContextLoader.Loaded loaded, Instant deadline)
            throws IOException {
        String relative = ContextFolderPaths.relative(loaded.path())
                .filter(path -> !path.isEmpty())
                .orElseThrow(() -> new SandboxException("A context loader named a path outside the folder"));
        Path archive = FolderArchive.directory(loaded.directory());
        try (InputStream in = Files.newInputStream(archive)) {
            sandboxes
                    .runtime()
                    .stage(
                            sandbox.containerId(),
                            new ExecSandboxes.Replace(
                                    relative, in, Files.size(archive), shortest(STAGE_TIME, left(deadline))));
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /** The loader's summary of the staged item, run in its directory as the sandbox user; empty when none or late. */
    private String summary(TurnSandboxes.Sandbox sandbox, ContextLoader.Loaded loaded, Instant deadline) {
        List<String> command = loaded.summary();
        Duration time = shortest(SUMMARY_TIME, left(deadline));
        if (command.isEmpty() || time.isNegative() || time.isZero()) {
            return "";
        }
        String directory = ContextFolderPaths.relative(loaded.path())
                .map(ContextFolderPaths::absolute)
                .orElse(ContextFolderPaths.ROOT);
        List<String> argv =
                new ArrayList<>(List.of("sh", "-c", "cd \"$1\" && shift && exec \"$@\"", "summary", directory));
        argv.addAll(command);
        // A failed summary may still start in the sandbox: its failure fails the change, which removes the sandbox.
        ExecSandboxes.Execution run =
                sandboxes.runtime().exec(sandbox.containerId(), new ExecSandboxes.Command(argv, time, SUMMARY_LIMIT));
        if (run.timedOut()) {
            // The sandbox stays usable: the cancellation ends the sandbox user's processes only.
            cancelCommands(sandbox);
            return "";
        }
        return new String(run.stdout(), StandardCharsets.UTF_8).strip();
    }

    /**
     * Ends every process of the sandbox's user, a detached one too; the sandbox stays in use. A cancellation that the
     * runtime cannot confirm throws, and the caller's failure removes the sandbox: a late kill must reach no later
     * command.
     */
    private void cancelCommands(TurnSandboxes.Sandbox sandbox) {
        sandboxes.runtime().cancelCommands(sandbox.containerId());
    }

    /**
     * The load, bounded by what remains of the turn and abandoned when the attempt ends. A load holds one of the
     * worker's {@link #LOADS_AT_ONCE} permits until it ends, also when the turn stopped waiting for it: a fetch has no
     * interruption of its own, so its permit is what keeps abandoned fetches from piling up.
     */
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

    private static Duration shortest(Duration first, Duration... others) {
        Duration shortest = first;
        for (Duration other : others) {
            if (other.compareTo(shortest) < 0) shortest = other;
        }
        return shortest;
    }

    private static long lastLineBytes(long withNewline, byte[] tail) {
        boolean endsWithNewline = tail.length > 0 && tail[tail.length - 1] == '\n';
        return endsWithNewline ? Math.max(0, withNewline - 1) : withNewline;
    }

    private static ObjectNode result(
            @Nullable Integer exitCode,
            String output,
            boolean truncated,
            boolean timedOut,
            @Nullable String fullOutput) {
        ObjectNode answer = NODES.objectNode().put("ok", true);
        if (exitCode == null) {
            answer.putNull("exitCode");
        } else {
            answer.put("exitCode", exitCode);
        }
        answer.put("output", output).put("truncated", truncated).put("timedOut", timedOut);
        if (fullOutput == null) {
            answer.putNull("fullOutputPath");
        } else {
            answer.put("fullOutputPath", fullOutput);
        }
        return answer;
    }

    static ObjectNode refusal(String code, String detail) {
        return NODES.objectNode().put("ok", false).put("error", code).put("detail", detail);
    }

    /** The first line of a script's output, as numbers, and the bytes after it. */
    static final class Header {
        private final long[] values;
        private final byte[] rest;

        private Header(long[] values, byte[] rest) {
            this.values = values;
            this.rest = rest;
        }

        long value(int index) {
            return values[index];
        }

        byte[] rest() {
            return rest;
        }

        static @Nullable Header of(byte[] output, int count) {
            int newline = -1;
            for (int index = 0; index < output.length; index++) {
                if (output[index] == '\n') {
                    newline = index;
                    break;
                }
            }
            if (newline < 0) return null;
            String[] fields = new String(output, 0, newline, StandardCharsets.US_ASCII)
                    .trim()
                    .split("\\s+", -1);
            if (fields.length < count) return null;
            long[] values = new long[count];
            try {
                for (int index = 0; index < count; index++) {
                    values[index] = Long.parseLong(fields[index]);
                }
            } catch (NumberFormatException malformed) {
                return null;
            }
            return new Header(values, Arrays.copyOfRange(output, newline + 1, output.length));
        }
    }

    /** The folder was not free before the turn's deadline. */
    private static final class Late extends SandboxException {
        @Serial
        private static final long serialVersionUID = 1L;

        Late() {
            super("The context folder was not free before the deadline");
        }
    }

    /** A change of the folder failed; its sandbox is removed. */
    private static final class Staging extends SandboxException {
        @Serial
        private static final long serialVersionUID = 1L;

        Staging(Exception cause) {
            super("Staging into the turn sandbox failed", cause);
        }
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
