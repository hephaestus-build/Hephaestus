package de.tum.cit.aet.hephaestus.agent.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * A runtime retains this lease until it releases its prepared inputs. Erasure cannot acknowledge
 * deletion while another process can still write or read the attempt. Locks are outside the job folder
 * so removing a folder never replaces the inode on which another process holds its lease.
 * Erasure and the attempt-folder sweeps take it exclusively before they remove a job's folders. A runtime shares
 * its published capture's lease with admission and retirement only, which remove under its monitor one at a time.
 * https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html
 */
public final class EvidenceFolderLease implements AutoCloseable {
    /**
     * The lease this JVM holds or is acquiring on each lock file. A file lock excludes other processes only, and
     * closing any second channel on the same file may release this JVM's lock on it: no second channel is opened.
     */
    private static final ConcurrentMap<Path, EvidenceFolderLease> LOCAL = new ConcurrentHashMap<>();

    /** A removal of a job's folders, run under the lease that owns them. */
    @FunctionalInterface
    public interface Removal {
        void run() throws IOException;
    }

    private final Path file;
    private @Nullable FileChannel channel;
    private @Nullable FileLock lock;
    private boolean shared;
    private boolean closed;

    private EvidenceFolderLease(Path file) {
        this.file = file;
    }

    /** An exclusive lease, or empty while this or another process holds or acquires one for the job. */
    public static Optional<EvidenceFolderLease> tryAcquire(Path store, long workspaceId, UUID jobId) {
        Path file = lockFile(store, workspaceId, jobId);
        var lease = new EvidenceFolderLease(file);
        if (LOCAL.putIfAbsent(file, lease) != null) return Optional.empty();
        synchronized (lease) {
            boolean locked = false;
            try {
                var opened = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lease.channel = opened;
                lease.lock = opened.tryLock();
                locked = lease.lock != null;
                return locked ? Optional.of(lease) : Optional.empty();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } finally {
                if (!locked) lease.close();
            }
        }
    }

    /**
     * Runs an admission or retirement removal under this JVM's shared runtime lease for the job, or else under a
     * new exclusive lease. False when neither can be had: an exclusive owner, here or in another process, removes
     * the job's folders itself.
     */
    public static boolean removeAsOwner(Path store, long workspaceId, UUID jobId, Removal removal) throws IOException {
        var local = LOCAL.get(lockFile(store, workspaceId, jobId));
        if (local != null) return local.removeIfShared(removal);
        var acquired = tryAcquire(store, workspaceId, jobId);
        if (acquired.isEmpty()) return false;
        var lease = acquired.get();
        try (lease) {
            removal.run();
        }
        return true;
    }

    /** Called once the capture's folder is published: admission and retirement may then remove it. */
    public synchronized void shareWithRemovals() {
        if (closed || lock == null) throw new IllegalStateException("Evidence folder lease is not held");
        shared = true;
    }

    private synchronized boolean removeIfShared(Removal removal) throws IOException {
        if (!shared || closed || lock == null) return false;
        removal.run();
        return true;
    }

    private static Path lockFile(Path store, long workspaceId, UUID jobId) {
        try {
            Path controls = Files.createDirectories(store.resolve(".person-evidence-locks"));
            return controls.toRealPath().resolve(workspaceId + "-" + jobId + ".lock");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            try {
                if (lock != null) lock.close();
            } finally {
                if (channel != null) channel.close();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            LOCAL.remove(file, this);
        }
    }
}
