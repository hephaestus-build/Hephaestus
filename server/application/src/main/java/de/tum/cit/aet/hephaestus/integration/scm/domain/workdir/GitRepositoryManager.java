package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails.FileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Operation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Request;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.input.BoundedInputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

@Service
@EnableConfigurationProperties(GitRepositoryProperties.class)
public class GitRepositoryManager {
    public static final String TREE_LIMITATION_SUBMODULE = "SUBMODULE_EXCLUDED";
    public static final String TREE_LIMITATION_UNSAFE_PATH = "UNSAFE_PATH_EXCLUDED";
    private static final Logger log = LoggerFactory.getLogger(GitRepositoryManager.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(15);
    private static final int DETAIL_FRAME_BYTES = 16 * 1024 * 1024;
    private static final int PAGE_SIZE = 256;
    /** Each page of details is one container; a walk stops here and the next cycle resumes from the captured set. */
    private static final int MAX_DETAIL_PAGES_PER_WALK = 32;

    private final GitRepositoryProperties properties;
    private final @Nullable NativeGitExecutor executor;
    private final FabricLayout layout;
    private final Semaphore ingestionPermits;

    /** A runtime role without a Git executor — the webhook receiver — has Git disabled whatever is configured. */
    public GitRepositoryManager(
            GitRepositoryProperties properties, Optional<NativeGitExecutor> executor, FabricLayout layout) {
        this.properties = properties;
        this.executor = executor.orElse(null);
        this.layout = layout;
        this.ingestionPermits = new Semaphore(properties.maxConcurrentIngestions());
    }

    public boolean isEnabled() {
        return properties.enabled() && executor != null;
    }

    public boolean isRepositoryCloned(RepositoryKey repository) {
        if (!isEnabled()) return false;
        String status = scalar(repository, Operation.STATUS, List.of());
        if (!status.equals("true") && !status.equals("false"))
            throw new GitOperationException("Invalid Git repository status", new IOException("Invalid status"));
        return status.equals("true");
    }

    public void deleteOrphanedRepository(long repositoryId) {
        if (executor != null) executor.deleteRepository(repositoryId);
    }

    public void ensureRepository(RepositoryKey repository, String cloneUrl, @Nullable String token) {
        execute(repository, new Request(Operation.FETCH, List.of(), cloneUrl, token), OutputStream.nullOutputStream());
    }

    public boolean fetchRemoteCommit(
            RepositoryKey repository, String cloneUrl, String remoteRef, String expectedSha, @Nullable String token) {
        execute(
                repository,
                new Request(Operation.FETCH_COMMIT, List.of(remoteRef, expectedSha), cloneUrl, token),
                OutputStream.nullOutputStream());
        return commitExists(repository, expectedSha);
    }

    public @Nullable String resolveBranchHead(RepositoryKey repository, String branch) {
        if (!isEnabled()) return null;
        String head = scalar(repository, Operation.RESOLVE, List.of("refs/remotes/origin/" + branch));
        return head.isEmpty() ? null : head;
    }

    public boolean commitExists(RepositoryKey repository, String sha) {
        if (!isEnabled()) return false;
        return sha.equals(scalar(repository, Operation.RESOLVE, List.of(sha)));
    }

    public void forEachCommitInRange(
            RepositoryKey repository,
            @Nullable String fromSha,
            String toSha,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer) {
        if (!isEnabled()) return;
        List<String> revisions = fromSha == null ? List.of(toSha) : List.of(fromSha, toSha);
        visitCommitIds(repository, Operation.COMMIT_RANGE, revisions, captured, consumer);
    }

    public void forEachCommitSubject(RepositoryKey repository, String base, String head, Consumer<String> consumer) {
        if (!isEnabled()) return;
        Path subjects = spool(repository, Operation.COMMIT_SUBJECTS, List.of(base, head));
        try (InputStream input = new BufferedInputStream(Files.newInputStream(subjects))) {
            while (true) {
                acquireIngestionPermit();
                try {
                    String subject = nulField(input);
                    if (subject == null) break;
                    consumer.accept(subject);
                } finally {
                    ingestionPermits.release();
                }
            }
        } catch (IOException failure) {
            throw new GitOperationException("Cannot read Git commit subjects", failure);
        } finally {
            deleteFile(subjects);
        }
    }

    private void acquireIngestionPermit() {
        try {
            ingestionPermits.acquire();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new GitOperationException("Git ingestion admission interrupted", failure);
        }
    }

    /** Completion markers skip individual SHAs, never entire ancestor ranges. */
    public void forEachMissingCommit(
            RepositoryKey repository, Function<List<String>, Set<String>> captured, Consumer<CommitDetails> consumer) {
        visitCommitIds(repository, Operation.COMMIT_IDS, List.of(), captured, consumer);
    }

    private void visitCommitIds(
            RepositoryKey repository,
            Operation operation,
            List<String> revisions,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer) {
        Path ids = spool(repository, operation, revisions);
        try (var lines = Files.newBufferedReader(ids)) {
            List<String> page = new ArrayList<>(PAGE_SIZE);
            int spooled = 0;
            String sha;
            while ((sha = lines.readLine()) != null) {
                checkInterrupted();
                page.add(sha);
                if (page.size() == PAGE_SIZE) {
                    if (spooled == MAX_DETAIL_PAGES_PER_WALK) {
                        log.info("Git walk paused at its page budget: repositoryId={}", repository.repositoryId());
                        return;
                    }
                    if (visitDetails(repository, page, captured, consumer)) spooled++;
                    page.clear();
                }
            }
            if (!page.isEmpty() && spooled < MAX_DETAIL_PAGES_PER_WALK)
                visitDetails(repository, page, captured, consumer);
        } catch (IOException e) {
            throw new GitOperationException("Cannot read Git commit stream", e);
        } finally {
            deleteFile(ids);
        }
    }

    /** @return whether a details container ran for this page */
    private boolean visitDetails(
            RepositoryKey repository,
            List<String> page,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer) {
        Set<String> existing = captured.apply(List.copyOf(page));
        List<String> missing =
                page.stream().filter(sha -> !existing.contains(sha)).toList();
        if (missing.isEmpty()) return false;
        Path details = spool(repository, Operation.COMMIT_DETAILS, missing);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(details)))) {
            for (String sha : missing) {
                checkInterrupted();
                acquireIngestionPermit();
                try {
                    long metadataLength = input.readLong();
                    if (metadataLength < 0 || metadataLength > DETAIL_FRAME_BYTES)
                        throw new IOException("Git metadata exceeds the ingestion frame budget");
                    byte[] metadata = input.readNBytes((int) metadataLength);
                    if (metadata.length != metadataLength) throw new IOException("Incomplete Git metadata frame");
                    long changesLength = input.readLong();
                    if (changesLength < 0 || changesLength > DETAIL_FRAME_BYTES - metadataLength)
                        throw new IOException("Git commit details exceed the ingestion frame budget");
                    List<FileChange> changes;
                    try (var bounded = BoundedInputStream.builder()
                            .setInputStream(input)
                            .setMaxCount(changesLength)
                            .setPropagateClose(false)
                            .get()) {
                        changes = parseFileChanges(bounded);
                        if (bounded.getCount() != changesLength)
                            throw new IOException("Incomplete Git file-change frame");
                    }
                    consumer.accept(commitDetails(sha, metadata, changes));
                } finally {
                    ingestionPermits.release();
                }
            }
            if (input.read() != -1) throw new IOException("Unexpected Git stream trailer");
            return true;
        } catch (IOException e) {
            throw new GitOperationException("Cannot decode Git commit details", e);
        } finally {
            deleteFile(details);
        }
    }

    private static CommitDetails commitDetails(String expectedSha, byte[] metadata, List<FileChange> changes)
            throws IOException {
        String[] fields = new String(metadata, StandardCharsets.UTF_8).split("\0", 9);
        if (fields.length != 9 || !fields[0].equals(expectedSha)) throw new IOException("Git commit identity mismatch");
        String message = fields[8].stripTrailing();
        int newline = message.indexOf('\n');
        int additions = 0;
        int deletions = 0;
        for (FileChange change : changes) {
            additions = Math.addExact(additions, change.additions());
            deletions = Math.addExact(deletions, change.deletions());
        }
        return new CommitDetails(
                expectedSha,
                newline < 0 ? message : message.substring(0, newline),
                newline < 0 ? null : message.substring(newline + 1).strip(),
                fields[1],
                fields[2],
                Instant.parse(fields[3]),
                fields[4],
                fields[5],
                Instant.parse(fields[6]),
                additions,
                deletions,
                changes.size(),
                changes,
                fields[7].isEmpty() ? List.of() : List.of(fields[7].split(" ")));
    }

    private static List<FileChange> parseFileChanges(InputStream input) throws IOException {
        Map<String, FileChange> changes = new LinkedHashMap<>();
        Set<String> counted = new HashSet<>();
        String field;
        while ((field = nulField(input)) != null) {
            if (field.startsWith(":")) {
                String[] header = field.split(" ");
                if (header.length != 5) throw new IOException("Invalid Git raw diff header");
                char status = header[4].charAt(0);
                String oldPath = Objects.requireNonNull(nulField(input));
                String path = status == 'R' || status == 'C' ? Objects.requireNonNull(nulField(input)) : oldPath;
                ChangeType type =
                        switch (status) {
                            case 'A' -> ChangeType.ADDED;
                            case 'D' -> ChangeType.REMOVED;
                            case 'R' -> ChangeType.RENAMED;
                            case 'C' -> ChangeType.COPIED;
                            case 'M', 'T' -> ChangeType.MODIFIED;
                            default -> throw new IOException("Unsupported Git change type");
                        };
                changes.put(path, new FileChange(path, type, 0, 0, 0, status == 'R' || status == 'C' ? oldPath : null));
            } else {
                String[] stats = field.split("\t", 3);
                if (stats.length != 3) throw new IOException("Invalid Git numstat record");
                String path = stats[2];
                if (path.isEmpty()) {
                    nulField(input);
                    path = Objects.requireNonNull(nulField(input));
                }
                FileChange change = changes.get(path);
                if (change == null || !counted.add(path))
                    throw new IOException("Git numstat has no unique matching change");
                int added = stats[0].equals("-") ? 0 : Integer.parseInt(stats[0]);
                int deleted = stats[1].equals("-") ? 0 : Integer.parseInt(stats[1]);
                changes.put(
                        path,
                        new FileChange(
                                path,
                                change.changeType(),
                                added,
                                deleted,
                                Math.addExact(added, deleted),
                                change.previousFilename()));
            }
        }
        if (counted.size() != changes.size()) throw new IOException("Git change statistics are incomplete");
        return List.copyOf(changes.values());
    }

    private static @Nullable String nulField(InputStream input) throws IOException {
        ByteArrayOutputStream field = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == 0)
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(field.toByteArray()))
                        .toString();
            if (field.size() >= DETAIL_FRAME_BYTES)
                throw new IOException("Git metadata field exceeds ingestion budget");
            field.write(value);
        }
        if (field.size() != 0) throw new IOException("Incomplete Git field");
        return null;
    }

    public GitTreeSnapshot readTreeSnapshot(RepositoryKey repository, String sha) {
        String resolved = scalar(repository, Operation.RESOLVE, List.of(sha));
        String tree = scalar(repository, Operation.TREE_ID, List.of(resolved));
        Path archive = spool(repository, Operation.SNAPSHOT, List.of(resolved));
        Path directory;
        try {
            directory = Files.createTempDirectory(layout.root(), "git-snapshot-");
        } catch (IOException e) {
            deleteFile(archive);
            throw new GitOperationException("Cannot create snapshot directory", e);
        }
        Set<String> limitations = new TreeSet<>();
        long bytes = 0;
        long visited = 0;
        try (var tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                checkInterrupted();
                String name = entry.getName();
                if (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty()) continue;
                Path target = directory.resolve(name).normalize();
                if (!target.startsWith(directory) || name.startsWith("/") || name.indexOf('\0') >= 0)
                    throw new IOException("Native snapshot contains an unsafe archive path");
                if (name.contains("\\")) {
                    limitations.add(TREE_LIMITATION_UNSAFE_PATH);
                    continue;
                }
                if (entry.isSymbolicLink() || entry.isLink())
                    throw new IOException("Native snapshot contains an unexpected filesystem link");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                // isFile() is true for FIFO and device entries too; each is asked for by name.
                if (!entry.isFile() || entry.isFIFO() || entry.isCharacterDevice() || entry.isBlockDevice())
                    throw new IOException("Native snapshot contains an unsupported archive entry");
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    tar.transferTo(out);
                }
                if ((entry.getMode() & 0111) != 0 && !target.toFile().setExecutable(true, false))
                    throw new IOException("Cannot preserve executable file mode");
                bytes = Math.addExact(bytes, Files.size(target));
                if (bytes > properties.maxSnapshotBytes())
                    throw new IOException("Repository snapshot exceeds hephaestus.git.max-snapshot-bytes");
                if (!name.startsWith(".git/")) visited++;
            }
            Path entries = spool(repository, Operation.TREE_ENTRIES, List.of(resolved));
            try (InputStream input = new BufferedInputStream(Files.newInputStream(entries))) {
                String treeEntry;
                while ((treeEntry = nulField(input)) != null)
                    if (treeEntry.startsWith("160000 ")) limitations.add(TREE_LIMITATION_SUBMODULE);
            } finally {
                deleteFile(entries);
            }
            return new GitTreeSnapshot(directory, resolved, tree, bytes, visited, limitations.isEmpty(), limitations);
        } catch (IOException | RuntimeException e) {
            deleteTreeQuietly(directory);
            throw new GitOperationException("Cannot prepare Git snapshot", e);
        } finally {
            deleteFile(archive);
        }
    }

    private String scalar(RepositoryKey repository, Operation operation, List<String> revisions) {
        Path result = spool(repository, operation, revisions);
        try {
            if (Files.size(result) > 256) throw new IOException("Invalid Git identity response");
            return Files.readString(result).strip();
        } catch (IOException e) {
            throw new GitOperationException("Cannot resolve Git identity", e);
        } finally {
            deleteFile(result);
        }
    }

    private Path spool(RepositoryKey repository, Operation operation, List<String> revisions) {
        Path file = temporary("git-output-");
        try (OutputStream output = Files.newOutputStream(file)) {
            execute(repository, new Request(operation, revisions, null, null), output);
            return file;
        } catch (IOException | RuntimeException e) {
            deleteFile(file);
            throw new GitOperationException("Git operation failed", e);
        }
    }

    private Path temporary(String prefix) {
        try {
            Files.createDirectories(layout.root());
            return Files.createTempFile(layout.root(), prefix, ".tmp");
        } catch (IOException e) {
            throw new GitOperationException("Cannot create Git operation output", e);
        }
    }

    private void execute(RepositoryKey repository, Request request, OutputStream output) {
        if (executor == null || !properties.enabled())
            throw new IllegalStateException("Git repository preparation is disabled");
        checkInterrupted();
        try {
            executor.execute(repository, request, OPERATION_TIMEOUT, output);
        } catch (RuntimeException e) {
            throw new GitOperationException("Native Git operation failed: " + request.operation(), e);
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new GitOperationException("Git operation interrupted", new InterruptedException());
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new GitOperationException("Cannot remove Git operation output", e);
        }
    }

    static void deleteTreeQuietly(Path root) {
        try {
            FileSystemUtils.deleteRecursively(root);
        } catch (IOException ignored) {
        }
    }

    public record GitTreeSnapshot(
            Path stagingDir,
            String commitSha,
            String treeSha,
            long totalBytes,
            long visitedFiles,
            boolean complete,
            Set<String> limitations)
            implements AutoCloseable {
        public GitTreeSnapshot {
            limitations = Set.copyOf(limitations);
        }

        @Override
        public void close() {
            deleteTreeQuietly(stagingDir);
        }
    }

    public static class GitOperationException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public GitOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
