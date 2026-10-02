package de.tum.cit.aet.hephaestus.agent.context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

/**
 * A runtime retains this lease until it releases its prepared inputs. Erasure cannot acknowledge
 * deletion while another process can still write or read the attempt. Locks are outside the job folder
 * so removing a folder never replaces the inode on which another process holds its lease.
 * https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html
 */
final class EvidenceFolderLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private EvidenceFolderLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static Optional<EvidenceFolderLease> tryAcquire(Path store, long workspaceId, UUID jobId) {
        Path controls = store.resolve(".person-evidence-locks");
        try {
            Files.createDirectories(controls);
            var channel = FileChannel.open(
                    controls.resolve(workspaceId + "-" + jobId + ".lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return Optional.of(new EvidenceFolderLease(channel, lock));
            } catch (OverlappingFileLockException busy) {
                // FileLock covers the entire JVM as well as other processes.
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
            channel.close();
            return Optional.empty();
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
                lock.close();
            } finally {
                channel.close();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
