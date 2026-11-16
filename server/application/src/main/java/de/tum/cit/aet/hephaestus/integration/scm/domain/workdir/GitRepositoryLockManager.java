package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** One read-write lock per mirror: a fetch rewrites refs while readers walk them. */
@Component
public class GitRepositoryLockManager {
    private static final long ABANDON_POLL_MILLIS = 200;

    private final ConcurrentHashMap<RepositoryKey, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public <T> T withWriteLock(RepositoryKey repository, Supplier<T> operation) {
        var lock = lock(repository).writeLock();
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Like {@link #withWriteLock(RepositoryKey, Supplier)}, but stops waiting once {@code abandoned} holds. A fetch can
     * run for minutes while it receives data, so a reader that gave up must not wait behind it for as long as it takes.
     *
     * @throws CancellationException when {@code abandoned} holds before the lock is taken
     */
    public <T> T withWriteLock(RepositoryKey repository, BooleanSupplier abandoned, Supplier<T> operation) {
        var lock = lock(repository).writeLock();
        try {
            while (!lock.tryLock(ABANDON_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (abandoned.getAsBoolean()) {
                    throw new CancellationException("The reader stopped waiting for the mirror");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var cancelled = new CancellationException("Interrupted while waiting for the mirror");
            cancelled.initCause(interrupted);
            throw cancelled;
        }
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    public <T> T withReadLock(RepositoryKey repository, Supplier<T> operation) {
        var lock = lock(repository).readLock();
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private ReentrantReadWriteLock lock(RepositoryKey repository) {
        return locks.computeIfAbsent(repository, key -> new ReentrantReadWriteLock());
    }
}
