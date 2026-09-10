package de.tum.cit.aet.hephaestus.integration.core.fabric;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Immutable, deduplicated SHA-256 blob store. Blobs use two-character fan-out below
 * {@link FabricLayout#casRoot()}, and live job manifests protect referenced blobs from collection.
 */
@Component
public class ContentAddressedStore {

    private static final Logger log = LoggerFactory.getLogger(ContentAddressedStore.class);

    private static final int LOCK_STRIPES = 64;

    private final FabricLayout layout;
    private final ReentrantLock[] locks;

    public ContentAddressedStore(FabricLayout layout) {
        this.layout = layout;
        this.locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.locks[i] = new ReentrantLock();
        }
    }

    /**
     * Stores the contents of {@code source} atomically and returns its SHA-256 digest, streaming the
     * file twice — once to digest, once to copy — so a blob of any size costs one buffer of memory.
     */
    public String put(Path source) {
        String sha = sha256(source);
        Path blob = pathFor(sha);
        try (BlobLock ignored = lockBlob(sha)) {
            if (Files.exists(blob)) {
                Files.setLastModifiedTime(blob, FileTime.from(Instant.now()));
                return sha;
            }
            Files.createDirectories(blob.getParent());
            Path temp = Files.createTempFile(blob.getParent(), "incoming-", ".tmp");
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, blob, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            return sha;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store blob from " + source, e);
        }
    }

    private static String sha256(Path source) {
        try (var in = Files.newInputStream(source)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to digest " + source, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Stores {@code content} atomically and returns its SHA-256 digest. Reusing a blob refreshes its retention age.
     */
    public String put(byte[] content) {
        String sha = sha256(content);
        Path blob = pathFor(sha);
        Path temp = null;
        try (BlobLock ignored = lockBlob(sha)) {
            if (Files.exists(blob)) {
                Files.setLastModifiedTime(blob, FileTime.from(Instant.now()));
                return sha;
            }
            Files.createDirectories(blob.getParent());
            try {
                temp = Files.createTempFile(blob.getParent(), ".tmp-", ".blob");
            } catch (NoSuchFileException vanished) {
                Files.createDirectories(blob.getParent());
                temp = Files.createTempFile(blob.getParent(), ".tmp-", ".blob");
            }
            Files.write(temp, content);
            moveAtomically(temp, blob);
            temp = null;
            return sha;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CAS blob " + sha, e);
        } finally {
            // A failed write leaves a `.tmp-*.blob` that sweep() never reclaims (isShaHex filters it),
            // so delete it here rather than leak an un-GC'able orphan on every failed write.
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanup) {
                    log.warn("CAS could not delete orphaned temp {}: {}", temp, cleanup.getMessage());
                }
            }
        }
    }

    /** Read a blob by its sha-256, or empty if it is not (or no longer) present. */
    public Optional<byte[]> get(String sha) {
        Path blob = pathFor(sha);
        // No exists()-then-read pre-check: sweep() runs concurrently with reads, so a blob can vanish
        // between the check and the read. Reading directly and treating a missing file as empty closes
        // that TOCTOU gap and makes the documented "no longer present" branch actually hold.
        try {
            byte[] content = Files.readAllBytes(blob);
            if (!sha.equals(sha256(content))) {
                throw new IllegalStateException("CAS blob digest mismatch: " + sha);
            }
            return Optional.of(content);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read CAS blob " + sha, e);
        }
    }

    /** Verifies a blob with bounded memory before a caller streams it from disk. */
    public Optional<Path> verifiedPath(String sha) {
        Path blob = pathFor(sha);
        try {
            if (!sha.equals(sha256(blob))) {
                throw new IllegalStateException("CAS blob digest mismatch: " + sha);
            }
            return Optional.of(blob);
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof NoSuchFileException) return Optional.empty();
            throw e;
        }
    }

    public boolean exists(String sha) {
        return Files.exists(pathFor(sha));
    }

    public Path pathFor(String sha) {
        validateSha(sha);
        return layout.casRoot().resolve("sha256").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
    }

    /**
     * Mark-and-sweep GC: delete every stored blob whose sha is NOT in {@code liveShas}. Returns the
     * number of blobs removed. Best-effort — a blob that fails to delete is logged and skipped.
     */
    public int sweep(Set<String> liveShas) {
        return sweep(liveShas, Instant.MAX);
    }

    public int sweep(Set<String> liveShas, Instant createdBefore) {
        Path casRoot = layout.casRoot();
        if (!Files.isDirectory(casRoot)) {
            return 0;
        }
        int[] removed = {0};
        try (Stream<Path> blobs = Files.walk(casRoot)) {
            blobs.filter(Files::isRegularFile).forEach(blob -> {
                Path parent = Objects.requireNonNull(blob.getParent());
                String candidate = parent.getFileName() + blob.getFileName().toString();
                if (isShaHex(candidate) && !liveShas.contains(candidate)) {
                    try (BlobLock ignored = lockBlob(candidate)) {
                        if (lastModifiedBefore(blob, createdBefore)) {
                            if (Files.deleteIfExists(blob)) removed[0]++;
                        }
                    } catch (IOException | UncheckedIOException e) {
                        log.warn("CAS sweep could not delete {}: {}", blob, e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            log.warn("CAS sweep failed to walk {}: {}", casRoot, e.getMessage());
        }
        if (removed[0] > 0) {
            log.info("CAS sweep removed {} unreferenced blob(s)", removed[0]);
        }
        pruneEmptyFanoutDirs(casRoot);
        return removed[0];
    }

    private static boolean lastModifiedBefore(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            log.warn("CAS sweep could not inspect {}: {}", path, e.getMessage());
            return false;
        }
    }

    private void pruneEmptyFanoutDirs(Path casRoot) {
        Path sha256Root = casRoot.resolve("sha256");
        if (!Files.isDirectory(sha256Root)) {
            return;
        }
        try (Stream<Path> fanout = Files.list(sha256Root)) {
            fanout.filter(Files::isDirectory).forEach(dir -> {
                try (Stream<Path> entries = Files.list(dir)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(dir);
                    }
                } catch (IOException e) {
                    log.debug("CAS sweep left fan-out dir {}: {}", dir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("CAS sweep could not list {}: {}", sha256Root, e.getMessage());
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private BlobLock lockBlob(String sha) {
        ReentrantLock processLock = locks[Math.floorMod(Integer.parseInt(sha.substring(0, 2), 16), LOCK_STRIPES)];
        processLock.lock();
        try {
            Path lockPath = layout.casRoot().resolve("locks").resolve(sha.substring(0, 2) + ".lock");
            Files.createDirectories(lockPath.getParent());
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                return new BlobLock(processLock, channel, channel.lock());
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        } catch (IOException e) {
            processLock.unlock();
            throw new UncheckedIOException("Failed to lock CAS blob " + sha, e);
        } catch (RuntimeException e) {
            processLock.unlock();
            throw e;
        }
    }

    private record BlobLock(ReentrantLock processLock, FileChannel channel, FileLock fileLock)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                fileLock.release();
            } finally {
                try {
                    channel.close();
                } finally {
                    processLock.unlock();
                }
            }
        }
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void validateSha(String sha) {
        if (!isShaHex(sha)) {
            throw new IllegalArgumentException("Not a sha-256 hex digest: " + sha);
        }
    }

    /** True iff {@code s} is a 64-character lowercase-hex string (a sha-256 digest). */
    private static boolean isShaHex(String s) {
        return (s != null
                && s.length() == 64
                && s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')));
    }
}
