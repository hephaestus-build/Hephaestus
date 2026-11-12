package de.tum.cit.aet.hephaestus.core;

import com.github.benmanes.caffeine.cache.AsyncCache;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Loads a Caffeine entry whose load blocks, such as a database read, a Git read or an HTTP call. Caffeine runs a
 * synchronous loader inside {@code ConcurrentHashMap.compute}, under the map bin's monitor. On Java 21 a virtual thread
 * that blocks under a monitor pins its carrier, and so does every caller of the same key that waits on that monitor.
 * Here the lock only installs a future: the caller that installed it loads the value on its own thread, outside the
 * lock, and the callers of the same key wait on the future, which unmounts a virtual thread. An entry invalidated while
 * it loads is not put back. A load that fails throws to the caller that ran it, and each caller that waited on it loads
 * the key itself, as with a synchronous cache.
 */
public final class CacheLoads {

    private CacheLoads() {}

    /** The cached value of {@code key}, or one that {@code load} returns now on this thread. */
    public static <K, V> V get(AsyncCache<K, V> cache, K key, Function<K, V> load) {
        var loading = new CompletableFuture<V>();
        var installed = new AtomicBoolean();
        CompletableFuture<V> value = cache.get(key, (unusedKey, unusedExecutor) -> {
            installed.set(true);
            return loading;
        });
        if (installed.get()) {
            try {
                V loaded = load.apply(key);
                loading.complete(loaded);
                return loaded;
            } catch (RuntimeException failure) {
                loading.completeExceptionally(failure);
                throw failure;
            }
        }
        try {
            return value.join();
        } catch (CompletionException failed) {
            cache.asMap().remove(key, value);
            return get(cache, key, load);
        }
    }
}
