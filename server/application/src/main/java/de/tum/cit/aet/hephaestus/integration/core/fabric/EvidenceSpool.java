package de.tum.cit.aet.hephaestus.integration.core.fabric;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.util.FileSystemUtils;

/**
 * Ephemeral copies in the evidence home. Their workspace and lease exist before any content is written. Failed closes
 * keep their owner and lease for reconciliation; a dead process leaves an unlocked copy that startup removes.
 */
public final class EvidenceSpool {
    /** Names written by the previous process-only spool, removed at the stopped-schema upgrade or restart. */
    public static final String PREFIX = "git-snapshot-";

    private static final String LEASE = ".lease";
    private static final Logger log = LoggerFactory.getLogger(EvidenceSpool.class);
    private static final Map<Path, Copy> COPIES = new ConcurrentHashMap<>();
    private static final Set<Path> HOMES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();

    private final Path fabricRoot;
    private final Path root;

    EvidenceSpool(Path fabricRoot) {
        this.fabricRoot = fabricRoot;
        this.root = fabricRoot.resolve(".evidence-spool");
        if (reconcile() != 0) {
            throw new IllegalStateException("Orphaned evidence spool cleanup must succeed before this server starts");
        }
        HOMES.add(root);
    }

    /** Nested operations borrow the same admission session; the supplier is called only for the outer capture. */
    public Capture capture(long workspaceId, Supplier<PersonDataCopyFence.Lease> admission) {
        if (workspaceId <= 0) throw new IllegalArgumentException("An evidence copy needs a workspace");
        Capture previous = ACTIVE.get();
        CaptureGroup group;
        if (previous == null) {
            group = new CaptureGroup(this, workspaceId, admission.get());
        } else {
            group = previous.group;
            if (!group.owner.root.equals(root) || group.workspaceId != workspaceId)
                throw new IllegalStateException("A capture cannot copy another workspace's evidence");
        }
        group.open();
        var capture = new Capture(group, previous);
        ACTIVE.set(capture);
        return capture;
    }

    public Copy directory(long workspaceId) throws IOException {
        Capture current = ACTIVE.get();
        if (current == null || current.closed)
            throw new IllegalStateException("An evidence copy needs an active capture scope");
        CaptureGroup capture = current.group;
        if (!capture.owner.root.equals(root) || capture.workspaceId != workspaceId)
            throw new IllegalStateException("A capture cannot copy another evidence home's or workspace's evidence");
        synchronized (COPIES) {
            return allocate(workspaceId, capture);
        }
    }

    private Copy allocate(long workspaceId, CaptureGroup capture) throws IOException {
        Path workspace = root.resolve(Long.toString(workspaceId));
        Files.createDirectories(workspace);
        Path entry = Files.createTempDirectory(workspace, "copy-");
        FileChannel channel =
                FileChannel.open(entry.resolve(LEASE), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FileLock lease = null;
        boolean registered = false;
        try {
            lease = channel.lock();
            // A sweep may remove the empty entry before this writer takes its lease. It then writes no content.
            if (!Files.exists(entry.resolve(LEASE), LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException(entry.toString());
            }
            Path path = entry.resolve("content");
            var copy = new Copy(this, entry, path, channel, lease, capture);
            COPIES.put(path, copy);
            registered = true;
            capture.add(copy);
            try {
                Files.createDirectory(path);
                return copy;
            } catch (IOException | RuntimeException failure) {
                try {
                    copy.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            if (!registered) {
                if (lease != null) lease.close();
                channel.close();
            }
            throw failure;
        }
    }

    /** A closed copy may be closed again. Unknown existing paths are never a cleanup success. */
    public static void close(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Copy copy = COPIES.get(normalized);
        if (copy != null) {
            copy.close();
            return;
        }
        for (Path home : HOMES) {
            if (!normalized.startsWith(home)) continue;
            Path relative = home.relativize(normalized);
            if (relative.getNameCount() != 3
                    || !relative.getName(2).toString().equals("content")
                    || !relative.getName(1).toString().startsWith("copy-")) continue;
            try {
                if (Long.parseLong(relative.getName(0).toString()) > 0) {
                    Path entry = home.resolve(relative.subpath(0, 2));
                    Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                }
            } catch (NoSuchFileException removed) {
                return;
            } catch (NumberFormatException invalidWorkspace) {
                // A foreign path is not an owned copy, even when its name resembles one.
            }
        }
        throw new IOException("The evidence spool path has no registered owner");
    }

    /** Number of copies whose removal failed. Live leases remain with their current owner. */
    public int reconcile() {
        int remaining = 0;
        for (Copy copy : COPIES.values()) {
            if (!copy.owner.root.equals(root) || copy.state != Copy.State.REMOVING) continue;
            try {
                copy.close();
            } catch (IOException failure) {
                remaining++;
            }
        }
        try {
            if (directoryExists(root))
                try (var workspaces = Files.list(root)) {
                    for (Path workspace : workspaces.toList()) {
                        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
                            if (!remove(workspace)) remaining++;
                            continue;
                        }
                        try (var copies = Files.list(workspace)) {
                            for (Path entry : copies.toList()) if (!removeOrphan(entry)) remaining++;
                        } catch (IOException failure) {
                            log.atWarn()
                                    .addKeyValue("event.name", "evidence.spool.cleanup.failed")
                                    .addKeyValue("evidence.path", workspace.toString())
                                    .addKeyValue(
                                            "error.type", failure.getClass().getName())
                                    .log("Could not reconcile an evidence spool workspace");
                            remaining++;
                        }
                    }
                }
        } catch (IOException failure) {
            log.atWarn()
                    .addKeyValue("event.name", "evidence.spool.cleanup.failed")
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Could not list the evidence spool");
            remaining++;
        }
        try {
            if (directoryExists(fabricRoot))
                try (var entries = Files.list(fabricRoot)) {
                    for (Path entry : entries.toList()) {
                        if (entry.getFileName().toString().startsWith(PREFIX) && !remove(entry)) remaining++;
                    }
                }
        } catch (IOException failure) {
            log.atWarn()
                    .addKeyValue("event.name", "evidence.spool.cleanup.failed")
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Could not list the legacy evidence spool");
            remaining++;
        }
        return remaining;
    }

    private static boolean directoryExists(Path path) throws IOException {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) throw new IOException("The evidence home path is not a directory");
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    private static boolean removeOrphan(Path entry) {
        synchronized (COPIES) {
            // Closing a second channel can release this JVM's existing lease on some platforms.
            // Allocation and this lookup share the registry lock, before either opens the lease file.
            if (COPIES.containsKey(entry.resolve("content"))) return true;
            return reclaimOrphan(entry);
        }
    }

    private static boolean reclaimOrphan(Path entry) {
        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) return remove(entry);
        Path marker = entry.resolve(LEASE);
        try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lease;
            try {
                lease = channel.tryLock();
            } catch (OverlappingFileLockException active) {
                return true;
            }
            if (lease == null) return true;
            try (lease) {
                return remove(entry);
            }
        } catch (NoSuchFileException absent) {
            // No content can be copied before the lease exists. This also covers an interrupted allocation.
            return remove(entry);
        } catch (IOException failure) {
            log.atWarn()
                    .addKeyValue("event.name", "evidence.spool.cleanup.failed")
                    .addKeyValue("evidence.path", entry.toString())
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Could not lease an orphaned evidence copy");
            return false;
        }
    }

    private static boolean remove(Path path) {
        try {
            FileSystemUtils.deleteRecursively(path);
            return true;
        } catch (IOException failure) {
            log.atWarn()
                    .addKeyValue("event.name", "evidence.spool.cleanup.failed")
                    .addKeyValue("evidence.path", path.toString())
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Could not remove an ephemeral evidence copy");
            return false;
        }
    }

    public static final class Capture implements PersonDataCopyFence.Lease {
        private final CaptureGroup group;
        private final @Nullable Capture previous;
        private volatile boolean closed;

        private Capture(CaptureGroup group, @Nullable Capture previous) {
            this.group = group;
            this.previous = previous;
        }

        @Override
        public JdbcOperations jdbc() {
            if (closed) throw new IllegalStateException("The evidence capture scope is closed");
            return group.admission.jdbc();
        }

        /** Detaches the creating thread; copies retain admission until their last physical removal. */
        @Override
        public synchronized void close() {
            if (closed) return;
            if (ACTIVE.get() != this)
                throw new IllegalStateException("Capture scopes must close in their nesting order");
            closed = true;
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            group.closeScope();
        }
    }

    private static final class CaptureGroup {
        private final EvidenceSpool owner;
        private final long workspaceId;
        private final PersonDataCopyFence.Lease admission;
        private final Set<Copy> copies = new HashSet<>();
        private int scopes;
        private boolean released;

        private CaptureGroup(EvidenceSpool owner, long workspaceId, PersonDataCopyFence.Lease admission) {
            this.owner = owner;
            this.workspaceId = workspaceId;
            this.admission = admission;
        }

        synchronized void open() {
            scopes++;
        }

        synchronized void add(Copy copy) {
            copies.add(copy);
        }

        synchronized void removed(Copy copy) {
            copies.remove(copy);
            releaseIfEmpty();
        }

        synchronized void closeScope() {
            scopes--;
            releaseIfEmpty();
        }

        private void releaseIfEmpty() {
            if (!released && scopes == 0 && copies.isEmpty()) {
                released = true;
                admission.close();
            }
        }
    }

    public static final class Copy implements AutoCloseable {
        private final EvidenceSpool owner;
        private final Path entry;
        private final Path path;
        private final FileChannel channel;
        private final FileLock lease;
        private final CaptureGroup capture;

        private enum State {
            OPEN,
            REMOVING,
            CLOSED
        }

        private volatile State state = State.OPEN;

        private Copy(
                EvidenceSpool owner, Path entry, Path path, FileChannel channel, FileLock lease, CaptureGroup capture) {
            this.owner = owner;
            this.entry = entry;
            this.path = path;
            this.channel = channel;
            this.lease = lease;
            this.capture = capture;
        }

        public Path path() {
            return path;
        }

        @Override
        public synchronized void close() throws IOException {
            if (state == State.CLOSED) return;
            state = State.REMOVING;
            try {
                FileSystemUtils.deleteRecursively(path);
                // The lease is released only after the payload is physically removed.
                Files.deleteIfExists(entry.resolve(LEASE));
                Files.deleteIfExists(entry);
                if (lease.isValid()) lease.close();
                channel.close();
                capture.removed(this);
                state = State.CLOSED;
                COPIES.remove(path, this);
            } catch (IOException failure) {
                recordRemovalFailure(failure);
                throw failure;
            } catch (RuntimeException failure) {
                recordRemovalFailure(failure);
                throw new IOException("Could not release evidence-copy custody", failure);
            }
        }

        private void recordRemovalFailure(Exception failure) {
            log.atWarn()
                    .addKeyValue("event.name", "evidence.spool.cleanup.failed")
                    .addKeyValue("evidence.path", path.toString())
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Evidence copy removal will be retried");
        }
    }
}
