package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** One read-write lock per mirror: a fetch rewrites refs while readers walk them. */
@Component
public class GitRepositoryLockManager {
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
