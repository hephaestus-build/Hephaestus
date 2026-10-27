package de.tum.cit.aet.hephaestus.integration.core.webhook;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.StorageType;
import java.io.IOException;
import java.io.Serial;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A set of keys that the server projects into a NATS key-value bucket and the receiver reads, so the receiver can
 * decide without a database (ADR 0066). A key holds no secret and no content; its presence is the fact.
 *
 * <p>Any failure drops the cached bucket handle, so the next call looks the bucket up again, and the writer's next
 * {@link #replaceAll} creates it again when NATS lost it.
 */
public final class ProjectedKeys {

    private final Connection nats;
    private final String bucket;
    private volatile @Nullable KeyValue handle;

    public ProjectedKeys(Connection nats, String bucket) {
        this.nats = nats;
        this.bucket = bucket;
    }

    /** Whether {@code key} is in the set. A bucket that is missing or unreadable is {@link UnavailableException}. */
    public boolean contains(String key) {
        try {
            return kv(false).get(key) != null;
        } catch (IOException | JetStreamApiException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    public void add(String key) {
        try {
            kv(true).put(key, new byte[0]);
        } catch (IOException | JetStreamApiException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    public void remove(String key) {
        try {
            kv(true).delete(key);
        } catch (IOException | JetStreamApiException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    /**
     * Makes the set exactly {@code keys}, creating the bucket when it is missing. A handle to a bucket that NATS lost
     * fails once; the second try looks the bucket up again and creates it.
     */
    public void replaceAll(Set<String> keys) {
        for (int attempt = 1; ; attempt++) {
            try {
                replaceAll(kv(true), keys);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unavailable(e);
            } catch (IOException | JetStreamApiException | RuntimeException e) {
                if (attempt == 2) {
                    throw unavailable(e);
                }
                handle = null;
            }
        }
    }

    private static void replaceAll(KeyValue kv, Set<String> keys)
            throws IOException, JetStreamApiException, InterruptedException {
        List<String> stored = kv.keys();
        for (String key : stored) {
            if (!keys.contains(key)) {
                kv.delete(key);
            }
        }
        for (String key : keys) {
            if (!stored.contains(key)) {
                kv.put(key, new byte[0]);
            }
        }
    }

    private KeyValue kv(boolean create) throws IOException, JetStreamApiException {
        KeyValue current = handle;
        if (current != null) {
            return current;
        }
        if (create) {
            KeyValueManagement management = nats.keyValueManagement();
            if (!management.getBucketNames().contains(bucket)) {
                management.create(KeyValueConfiguration.builder()
                        .name(bucket)
                        .storageType(StorageType.File)
                        .maxHistoryPerKey(1)
                        .build());
            }
        } else {
            // Looked up, never created: the reader must not make an empty set that reads as "nothing allowed" forever.
            nats.keyValueManagement().getStatus(bucket);
        }
        current = nats.keyValue(bucket);
        handle = current;
        return current;
    }

    private UnavailableException unavailable(Exception cause) {
        handle = null;
        return new UnavailableException(bucket, cause);
    }

    /** The bucket could not be read or written; the caller answers as if the fact were unknown. */
    public static final class UnavailableException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        UnavailableException(String bucket, Throwable cause) {
            super("NATS key-value bucket " + bucket + " is unavailable", cause);
        }
    }
}
