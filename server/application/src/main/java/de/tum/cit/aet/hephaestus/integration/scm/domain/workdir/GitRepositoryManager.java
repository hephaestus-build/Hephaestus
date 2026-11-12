package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.integration.core.fabric.EvidenceSpool;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails.FileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import java.io.IOException;
import java.io.Serial;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

/**
 * Bare mirrors under the fabric root, one per workspace and repository, and the checkouts a review
 * reads from them. Every operation runs in this process; a role that runs Git owns its own mirrors.
 */
@Service
@EnableConfigurationProperties(GitRepositoryProperties.class)
public class GitRepositoryManager {
    public static final String TREE_LIMITATION_SUBMODULE = "SUBMODULE_EXCLUDED";
    public static final String TREE_LIMITATION_UNSAFE_PATH = "UNSAFE_PATH_EXCLUDED";

    /** Where a fetched review head lives in the mirror; content-addressed, so a refetch is idempotent. */
    public static final String REVIEW_REFS = "refs/hephaestus/reviews/";

    /** What the review container may see of the mirror's refs, with their types; the admission reads it back. */
    public static final String CAPTURED_REFS = "hephaestus-captured-refs";

    private static final String MIRRORS = "mirrors";

    /** Checkouts built again for captures of other servers, inside their mirror ({@link #copyCheckout}). */
    private static final String CHECKOUTS = "hephaestus-checkouts";

    private static final Pattern MIRROR_ID = Pattern.compile("[1-9][0-9]{0,17}");
    private static final Logger log = LoggerFactory.getLogger(GitRepositoryManager.class);
    private static final int PAGE_SIZE = 256;

    /** The block size of the file systems a node's volumes use. */
    private static final long DISK_BLOCK = 4096;

    /** A walk stops after this many pages that needed capture; the next sync cycle resumes from the captured set. */
    private static final int MAX_DETAIL_PAGES_PER_WALK = 32;

    private static final List<RefSpec> MIRROR_REFSPECS =
            List.of(new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*"));

    /** The mirror's heads become the checkout's remote branches, as in any developer's clone. */
    private static final List<RefSpec> SNAPSHOT_REFSPECS =
            List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*"), new RefSpec("+refs/tags/*:refs/tags/*"));

    private final GitRepositoryProperties properties;
    private final GitRepositoryLockManager locks;
    private final FabricLayout layout;
    private final Semaphore ingestionPermits;

    private final GuardedGitTransport transports;
    private final RepositoryRefState refs;

    public GitRepositoryManager(
            GitRepositoryProperties properties,
            GitRepositoryLockManager locks,
            FabricLayout layout,
            GuardedGitTransport transports,
            RepositoryRefState refs) {
        this.refs = refs;
        this.transports = transports;
        this.properties = properties;
        this.locks = locks;
        this.layout = layout;
        this.ingestionPermits = new Semaphore(properties.maxConcurrentIngestions());
    }

    public void invalidateRefs(long nativeId, long providerId) {
        refs.invalidate(nativeId, providerId);
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public boolean isRepositoryCloned(RepositoryKey repository) {
        return isEnabled() && Files.isRegularFile(mirror(repository).resolve(Constants.HEAD));
    }

    /** The mirrors under this process's root, whichever workspace they belong to. */
    public List<RepositoryKey> localRepositories() {
        Path root = layout.root().resolve(MIRRORS);
        if (!Files.isDirectory(root)) return List.of();
        List<RepositoryKey> keys = new ArrayList<>();
        try (Stream<Path> workspaces = Files.list(root)) {
            for (Path workspace : workspaces.toList()) {
                String workspaceId = workspace.getFileName().toString();
                if (!MIRROR_ID.matcher(workspaceId).matches() || !Files.isDirectory(workspace, NOFOLLOW_LINKS))
                    continue;
                try (Stream<Path> mirrors = Files.list(workspace)) {
                    for (Path mirror : mirrors.toList()) {
                        String name = mirror.getFileName().toString();
                        if (!name.endsWith(".git")) continue;
                        String repositoryId = name.substring(0, name.length() - ".git".length());
                        if (MIRROR_ID.matcher(repositoryId).matches() && Files.isDirectory(mirror, NOFOLLOW_LINKS))
                            keys.add(new RepositoryKey(Long.parseLong(workspaceId), Long.parseLong(repositoryId)));
                    }
                }
            }
        } catch (IOException e) {
            throw new GitOperationException("Cannot list repository mirrors", e);
        }
        return List.copyOf(keys);
    }

    /** The mirrors under this process's root, the one that a capture used longest ago first. */
    public List<RepositoryKey> localRepositoriesLeastRecentlyUsedFirst() {
        return localRepositories().stream()
                .sorted(Comparator.comparing(this::lastUsed))
                .toList();
    }

    /**
     * When a capture last fetched this mirror; the epoch for a mirror that is already gone. A read does not count: it
     * holds the read lock, which keeps the mirror while it runs.
     */
    public Instant lastUsed(RepositoryKey repository) {
        try {
            return Files.getLastModifiedTime(mirror(repository), NOFOLLOW_LINKS).toInstant();
        } catch (IOException removed) {
            return Instant.EPOCH;
        }
    }

    /** What this process keeps on its volume: mirrors, attempt folders, snapshot spools and staged archives. */
    public long cacheBytes() {
        return bytesUnder(layout.root());
    }

    public long mirrorBytes(RepositoryKey repository) {
        return bytesUnder(mirror(repository));
    }

    /**
     * What these files take on disk, in whole blocks with their directories, as the kubelet counts a volume. Counts what
     * it can read: a fetch or an attempt may remove a file under the walk.
     */
    private static long bytesUnder(Path root) {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return 0;
        var bytes = new AtomicLong();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    bytes.addAndGet(DISK_BLOCK);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    bytes.addAndGet((attributes.size() + DISK_BLOCK - 1) / DISK_BLOCK * DISK_BLOCK);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new GitOperationException("Cannot measure " + root.getFileName(), e);
        }
        return bytes.get();
    }

    /**
     * Deletes one workspace's mirror unless {@code retained} holds. The check runs under the mirror's write lock, so a
     * fetch or a read that started before it finishes first, and one that starts after it finds no mirror. A capture
     * fetch has no timeout, so this waits at most {@code wait} for the lock and then leaves the mirror for a later call.
     *
     * @return whether the mirror was deleted
     */
    public boolean deleteUnretainedRepository(RepositoryKey repository, Duration wait, BooleanSupplier retained) {
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            return locks.withWriteLock(repository, () -> System.nanoTime() - deadline > 0, () -> {
                if (retained.getAsBoolean()) return false;
                try {
                    // A fetch that just finished may still be packing in the background; a file it removes under
                    // the walk is not a failure to delete.
                    FileUtils.delete(
                            mirror(repository).toFile(),
                            FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
                } catch (IOException e) {
                    throw new GitOperationException("Cannot delete repository mirror", e);
                }
                return true;
            });
        } catch (CancellationException busy) {
            return false;
        }
    }

    public void ensureRepository(RepositoryKey repository, String cloneUrl, @Nullable String token) {
        ensure(repository, cloneUrl, token, () -> false, 0);
    }

    /**
     * How long a reader's fetch may wait for the provider in one read or write, in seconds. A capture's fetch has no
     * such bound.
     */
    public static final int READER_TRANSPORT_TIMEOUT_SECONDS = 60;

    /**
     * As {@link #ensureRepository(RepositoryKey, String, String)}, for a reader that may stop waiting: when
     * {@code abandoned} holds once the mirror's lock is taken, nothing is fetched and the call throws
     * {@link CancellationException}. Its fetch fails when the provider is silent for
     * {@link #READER_TRANSPORT_TIMEOUT_SECONDS}, so it releases the mirror's lock.
     */
    public void ensureRepository(
            RepositoryKey repository, String cloneUrl, @Nullable String token, BooleanSupplier abandoned) {
        ensure(repository, cloneUrl, token, abandoned, READER_TRANSPORT_TIMEOUT_SECONDS);
    }

    /** @param timeoutSeconds JGit's transport timeout; 0 waits as long as the provider takes */
    private void ensure(
            RepositoryKey repository,
            String cloneUrl,
            @Nullable String token,
            BooleanSupplier abandoned,
            int timeoutSeconds) {
        requireEnabled();
        locks.withWriteLock(repository, abandoned, () -> {
            if (abandoned.getAsBoolean()) throw new CancellationException("The reader stopped waiting for the mirror");
            var readId = refs.begin(repository.workspaceId(), repository.repositoryId());
            Path mirror = mirror(repository);
            try {
                if (!isRepositoryCloned(repository) || !hasOrigin(mirror, cloneUrl)) {
                    // A mirror is a cache keyed by ids a restore can reuse for another
                    // upstream: rebuild
                    // rather than retarget, so no old objects survive.
                    FileUtils.delete(mirror.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
                    Files.createDirectories(mirror.getParent());
                    try (Git git = Git.init()
                            .setBare(true)
                            .setDirectory(mirror.toFile())
                            .call()) {
                        StoredConfig config = git.getRepository().getConfig();
                        config.setString("remote", "origin", "url", cloneUrl);
                        // A redirect would carry the credential to a host the clone URL
                        // never named.
                        config.setBoolean("http", null, "followRedirects", false);
                        // A checkout may pin a commit no ref reaches any more; it is
                        // fetched from the mirror by id.
                        config.setBoolean("uploadpack", null, "allowAnySHA1InWant", true);
                        config.save();
                    }
                }
                try (Git git = Git.open(mirror.toFile())) {
                    git.fetch()
                            .setRemote("origin")
                            .setRefSpecs(MIRROR_REFSPECS)
                            .setTagOpt(TagOpt.NO_TAGS)
                            .setRemoveDeletedRefs(true)
                            .setCredentialsProvider(credentials(token))
                            .setTransportConfigCallback(transports.callback(cloneUrl))
                            .setTimeout(timeoutSeconds)
                            .call();
                    refs.record(
                            repository.workspaceId(),
                            repository.repositoryId(),
                            readId,
                            SourceFingerprint.refs(readableRefs(git.getRepository(), true)));
                }
                // A fetch that brings nothing new changes no file, and eviction reads the last use from here.
                Files.setLastModifiedTime(mirror, FileTime.from(Instant.now()));
                return null;
            } catch (InvalidRemoteException | TransportException e) {
                throw new RepositoryFetchException(e);
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Cannot fetch repository " + repository.repositoryId(), e);
            }
        });
    }

    /** Fetches the provider's review ref for a head no branch reaches, such as a fork's, and pins it. */
    public boolean fetchRemoteCommit(
            RepositoryKey repository, String cloneUrl, String remoteRef, String expectedSha, @Nullable String token) {
        return fetchRemoteCommit(repository, cloneUrl, remoteRef, expectedSha, token, 0);
    }

    /**
     * As {@link #fetchRemoteCommit(RepositoryKey, String, String, String, String)}, for a reader: the fetch fails when
     * the provider is silent for {@code timeoutSeconds} ({@link #READER_TRANSPORT_TIMEOUT_SECONDS}), so it releases
     * the mirror's lock.
     */
    public boolean fetchRemoteCommit(
            RepositoryKey repository,
            String cloneUrl,
            String remoteRef,
            String expectedSha,
            @Nullable String token,
            int timeoutSeconds) {
        requireEnabled();
        if (!Repository.isValidRefName(remoteRef) || !ObjectId.isId(expectedSha)) {
            throw new IllegalArgumentException("Invalid remote review ref or expected commit SHA");
        }
        return locks.withWriteLock(repository, () -> {
            String localRef = REVIEW_REFS + expectedSha.toLowerCase(Locale.ROOT);
            try (Git git = Git.open(mirror(repository).toFile())) {
                if (!hasOrigin(mirror(repository), cloneUrl)) throw new IOException("Mirror origin differs");
                git.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("+" + remoteRef + ":" + localRef))
                        .setTagOpt(TagOpt.NO_TAGS)
                        .setCredentialsProvider(credentials(token))
                        .setTransportConfigCallback(transports.callback(cloneUrl))
                        .setTimeout(timeoutSeconds)
                        .call();
                ObjectId resolved = git.getRepository().resolve(localRef);
                return resolved != null && resolved.getName().equals(expectedSha.toLowerCase(Locale.ROOT));
            } catch (InvalidRemoteException | TransportException e) {
                throw new RepositoryFetchException(e);
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Cannot fetch review commit " + expectedSha, e);
            }
        });
    }

    public @Nullable String resolveBranchHead(RepositoryKey repository, String branch) {
        if (!isEnabled() || !Repository.isValidRefName(Constants.R_HEADS + branch)) return null;
        return read(repository, repo -> {
            Ref ref = repo.exactRef(Constants.R_HEADS + branch);
            return ref == null || ref.getObjectId() == null
                    ? null
                    : ref.getObjectId().getName();
        });
    }

    /** The commit a tag names, peeled; null when the tag is absent or names no commit. */
    public @Nullable String resolveTag(RepositoryKey repository, String tag) {
        if (!isEnabled() || !Repository.isValidRefName(Constants.R_TAGS + tag)) return null;
        return read(repository, repo -> {
            Ref ref = repo.exactRef(Constants.R_TAGS + tag);
            if (ref == null || ref.getObjectId() == null) return null;
            try (RevWalk walk = new RevWalk(repo)) {
                return walk.parseCommit(ref.getObjectId()).getName();
            } catch (IncorrectObjectTypeException e) {
                return null;
            }
        });
    }

    public boolean commitExists(RepositoryKey repository, String sha) {
        if (!isEnabled() || !ObjectId.isId(sha)) return false;
        return Boolean.TRUE.equals(read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                walk.parseCommit(ObjectId.fromString(sha));
                return true;
            } catch (IOException e) {
                return false;
            }
        }));
    }

    /** {@link RepositoryDiff#reviewBase} on the mirror; null when the range is not a review. */
    public @Nullable String reviewBase(RepositoryKey repository, String targetSha, String headSha) {
        if (!isEnabled() || !ObjectId.isId(targetSha) || !ObjectId.isId(headSha)) return null;
        return read(repository, repo -> {
            ObjectId base =
                    RepositoryDiff.reviewBase(repo, ObjectId.fromString(targetSha), ObjectId.fromString(headSha));
            return base == null ? null : base.getName();
        });
    }

    /** {@link RepositoryDiff#changedPaths} on the mirror. */
    public Set<String> changedPaths(RepositoryKey repository, String baseSha, String headSha) {
        Set<String> paths = read(
                repository,
                repo -> RepositoryDiff.changedPaths(repo, ObjectId.fromString(baseSha), ObjectId.fromString(headSha)));
        return paths == null ? Set.of() : paths;
    }

    /** {@link RepositoryDiff#unifiedDiff} on the mirror. */
    public String unifiedDiff(RepositoryKey repository, String baseSha, String headSha) {
        String text = read(
                repository,
                repo -> RepositoryDiff.unifiedDiff(repo, ObjectId.fromString(baseSha), ObjectId.fromString(headSha)));
        return text == null ? "" : text;
    }

    public void forEachCommitInRange(
            RepositoryKey repository,
            @Nullable String fromSha,
            String toSha,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(repo.resolve(toSha)));
                if (fromSha != null) walk.markUninteresting(walk.parseCommit(repo.resolve(fromSha)));
                visit(repo, walk, captured, consumer);
            }
            return null;
        });
    }

    /**
     * Hands every commit of {@code base..head} to {@code consumer} with its file changes, in no promised order: the
     * whole range, which a staged record of a change must hold. The walk streams, so the caller's byte budget can stop
     * it at the first commit over it; each commit's message is read as it is handed on and released after, so the
     * messages of the range are never held together.
     *
     * @throws IllegalStateException when local git storage is disabled or the mirror does not hold either end, so
     *     a range that could not be read is never mistaken for an empty one
     */
    public void forEachCommitBetween(
            RepositoryKey repository, String base, String head, Consumer<CommitDetails> consumer) {
        if (!isEnabled()) {
            throw new IllegalStateException("Local git storage is disabled; the commits of a range cannot be read");
        }
        read(repository, repo -> {
            ObjectId from = repo.resolve(base);
            ObjectId to = repo.resolve(head);
            if (from == null || to == null) {
                throw new IllegalStateException("The mirror does not hold the pinned range " + base + ".." + head);
            }
            try (RevWalk walk = new RevWalk(repo)) {
                // REVERSE buffers the whole range before yielding, so the byte budget
                // cannot stop it early.
                walk.setRetainBody(false);
                walk.markStart(walk.parseCommit(to));
                walk.markUninteresting(walk.parseCommit(from));
                for (RevCommit commit : walk) {
                    checkInterrupted();
                    CommitDetails read;
                    try {
                        walk.parseBody(commit);
                        read = details(repo, commit);
                    } finally {
                        commit.disposeBody();
                    }
                    consumer.accept(read);
                }
            }
            return null;
        });
    }

    /** The full message, subject and body, of every commit in {@code base..head}. */
    public void forEachCommitMessage(RepositoryKey repository, String base, String head, Consumer<String> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(repo.resolve(head)));
                walk.markUninteresting(walk.parseCommit(repo.resolve(base)));
                for (RevCommit commit : walk) {
                    checkInterrupted();
                    consumer.accept(commit.getFullMessage());
                }
            }
            return null;
        });
    }

    /** Every commit reachable from any ref; completion markers skip individual SHAs, never ancestor ranges. */
    public void forEachMissingCommit(
            RepositoryKey repository, Function<List<String>, Set<String>> captured, Consumer<CommitDetails> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                for (Ref ref : repo.getRefDatabase().getRefs()) {
                    ObjectId id = ref.getObjectId();
                    if (id == null) continue;
                    // A tag on a tree or blob is witnessed, not walked.
                    if (walk.peel(walk.parseAny(id)) instanceof RevCommit commit) walk.markStart(commit);
                }
                visit(repo, walk, captured, consumer);
            }
            return null;
        });
    }

    private void visit(
            Repository repo,
            RevWalk walk,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer)
            throws IOException {
        List<RevCommit> page = new ArrayList<>(PAGE_SIZE);
        int capturedPages = 0;
        for (RevCommit commit : walk) {
            checkInterrupted();
            page.add(commit);
            if (page.size() == PAGE_SIZE) {
                if (capturedPages == MAX_DETAIL_PAGES_PER_WALK) {
                    log.info("Git walk paused at its page budget: repository={}", repo.getDirectory());
                    return;
                }
                if (visitPage(repo, page, captured, consumer)) capturedPages++;
                page.clear();
            }
        }
        if (!page.isEmpty() && capturedPages < MAX_DETAIL_PAGES_PER_WALK) visitPage(repo, page, captured, consumer);
    }

    /** @return whether any commit of the page still needed capture */
    private boolean visitPage(
            Repository repo,
            List<RevCommit> page,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer)
            throws IOException {
        Set<String> existing =
                captured.apply(page.stream().map(RevCommit::getName).toList());
        boolean any = false;
        for (RevCommit commit : page) {
            if (existing.contains(commit.getName())) continue;
            any = true;
            checkInterrupted();
            CommitDetails details = details(repo, commit);
            acquireIngestionPermit();
            try {
                consumer.accept(details);
            } finally {
                ingestionPermits.release();
            }
        }
        return any;
    }

    private static CommitDetails details(Repository repo, RevCommit commit) throws IOException {
        PersonIdent author = commit.getAuthorIdent();
        PersonIdent committer = commit.getCommitterIdent();
        List<FileChange> changes = fileChanges(repo, commit);
        int additions = 0;
        int deletions = 0;
        for (FileChange change : changes) {
            additions = Math.addExact(additions, change.additions());
            deletions = Math.addExact(deletions, change.deletions());
        }
        String message = commit.getFullMessage().stripTrailing();
        int newline = message.indexOf('\n');
        String body = newline < 0 ? null : message.substring(newline + 1).strip();
        return new CommitDetails(
                commit.getName(),
                newline < 0 ? message : message.substring(0, newline),
                body == null || body.isEmpty() ? null : body,
                author.getName(),
                author.getEmailAddress(),
                author.getWhenAsInstant(),
                committer.getName(),
                committer.getEmailAddress(),
                committer.getWhenAsInstant(),
                additions,
                deletions,
                changes.size(),
                changes,
                Stream.of(commit.getParents()).map(RevCommit::getName).toList(),
                commit.getFullMessage());
    }

    /** A merge's changes are those against its first parent, as {@code --diff-merges=first-parent} lists them. */
    private static List<FileChange> fileChanges(Repository repo, RevCommit commit) throws IOException {
        List<FileChange> changes = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo);
                ObjectReader reader = repo.newObjectReader();
                DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repo);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            var newTree = new CanonicalTreeParser();
            newTree.reset(reader, commit.getTree());
            List<DiffEntry> entries;
            if (commit.getParentCount() > 0) {
                var oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, walk.parseCommit(commit.getParent(0)).getTree());
                entries = formatter.scan(oldTree, newTree);
            } else {
                entries = formatter.scan(new EmptyTreeIterator(), newTree);
            }
            for (DiffEntry entry : entries) {
                int added = 0;
                int deleted = 0;
                for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
                    deleted += edit.getEndA() - edit.getBeginA();
                    added += edit.getEndB() - edit.getBeginB();
                }
                boolean renamedOrCopied = entry.getChangeType() == DiffEntry.ChangeType.RENAME
                        || entry.getChangeType() == DiffEntry.ChangeType.COPY;
                changes.add(new FileChange(
                        entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath(),
                        switch (entry.getChangeType()) {
                            case ADD -> ChangeType.ADDED;
                            case MODIFY -> ChangeType.MODIFIED;
                            case DELETE -> ChangeType.REMOVED;
                            case RENAME -> ChangeType.RENAMED;
                            case COPY -> ChangeType.COPIED;
                        },
                        added,
                        deleted,
                        Math.addExact(added, deleted),
                        renamedOrCopied ? entry.getOldPath() : null));
            }
        }
        return List.copyOf(changes);
    }

    public GitTreeSnapshot readScanSnapshot(RepositoryKey repository, String sha, Set<String> paths) {
        if (!isEnabled()) throw new IllegalStateException("Repository capture is disabled");
        if (!ObjectId.isId(sha)) throw new GitOperationException("Invalid scan commit", new IOException());
        return locks.withReadLock(repository, () -> {
            Path staging = null;
            try (Git git = Git.open(mirror(repository).toFile());
                    RevWalk walk = new RevWalk(git.getRepository());
                    ObjectReader reader = git.getRepository().newObjectReader();
                    TreeWalk tree = new TreeWalk(reader)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                Files.createDirectories(layout.root());
                staging = Files.createTempDirectory(layout.root(), EvidenceSpool.currentPrefix());
                long bytes = 0, files = 0;
                Set<String> limitations = new TreeSet<>();
                Set<String> missing = new TreeSet<>(paths);
                while (tree.next()) {
                    checkInterrupted();
                    String name = tree.getPathString();
                    if (!paths.contains(name)) continue;
                    FileMode mode = tree.getFileMode(0);
                    if (!mode.equals(FileMode.REGULAR_FILE) && !mode.equals(FileMode.EXECUTABLE_FILE)) {
                        continue;
                    }
                    Path target = staging.resolve(name).normalize();
                    if (name.contains("\\")
                            || !target.startsWith(staging)
                            || name.equals(".git")
                            || name.startsWith(".git/")) {
                        limitations.add(TREE_LIMITATION_UNSAFE_PATH);
                        continue;
                    }
                    var object = reader.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                    bytes = Math.addExact(bytes, object.getSize());
                    if (bytes > properties.maxSnapshotBytes())
                        throw new GitOperationException("Scan tree exceeds the snapshot size limit", new IOException());
                    Files.createDirectories(target.getParent());
                    try (var output = Files.newOutputStream(target)) {
                        object.copyTo(output);
                    }
                    files++;
                    missing.remove(name);
                }
                if (!missing.isEmpty()) limitations.add("SCAN_PATH_UNAVAILABLE");
                return new GitTreeSnapshot(
                        staging, sha, commit.getTree().getName(), bytes, files, limitations.isEmpty(), limitations);
            } catch (IOException | RuntimeException e) {
                if (staging != null) deleteTreeQuietly(staging);
                throw new GitOperationException("Cannot capture the pinned scan tree", e);
            }
        });
    }

    /**
     * A checkout of {@code sha} with its own {@code .git}: the mirror's branches and tags as remote
     * refs, the reviewed commit detached at HEAD, symbolic links written as files holding their target,
     * no remote configuration and no credential. Measured before anything is written; a repository over
     * the bound is refused whole. The caller closes the snapshot to delete the directory.
     */
    public GitTreeSnapshot readTreeSnapshot(RepositoryKey repository, String sha) {
        requireEnabled();
        if (!ObjectId.isId(sha)) throw new GitOperationException("Invalid commit", new IOException(sha));
        return snapshot(repository, sha, List.of(), "Cannot prepare Git snapshot");
    }

    /**
     * The checkout {@link #readTreeSnapshot} made of {@code sha}, again: measured, bounded and limited the same way,
     * but holding only the named original commits and their history from the local mirror's own objects. No remote
     * is contacted and no branch, tag or other current ref is copied, so no captured-refs record is written; the
     * caller restores the original one. A commit the mirror no longer holds refuses the snapshot rather than being
     * replaced. The caller closes the snapshot to delete it.
     */
    public GitTreeSnapshot readOriginalSnapshot(RepositoryKey repository, String sha, Collection<String> commits) {
        requireEnabled();
        var wanted = new LinkedHashSet<String>();
        wanted.add(sha);
        wanted.addAll(commits);
        for (String id : wanted) {
            if (!ObjectId.isId(id)) throw new GitOperationException("Invalid commit", new IOException(id));
        }
        return snapshot(repository, sha, List.copyOf(wanted), "Cannot restore the original Git snapshot");
    }

    /** {@code originals} empty: the mirror's current refs, as a capture takes them; else only those commits. */
    private GitTreeSnapshot snapshot(RepositoryKey repository, String sha, List<String> originals, String failure) {
        return locks.withReadLock(repository, () -> {
            Path mirror = mirror(repository);
            Path staging = null;
            try (Git source = Git.open(mirror.toFile())) {
                Repository repo = source.getRepository();
                RevCommit commit;
                try (RevWalk walk = new RevWalk(repo)) {
                    // A missing original commit fails here, before anything is staged.
                    for (String id : originals) walk.parseCommit(ObjectId.fromString(id));
                    commit = walk.parseCommit(ObjectId.fromString(sha));
                }
                Set<String> limitations = new TreeSet<>();
                long bytes = 0;
                long files = 0;
                List<String> excluded = new ArrayList<>();
                try (ObjectReader reader = repo.newObjectReader();
                        TreeWalk tree = new TreeWalk(reader)) {
                    tree.addTree(commit.getTree());
                    tree.setRecursive(true);
                    while (tree.next()) {
                        checkInterrupted();
                        FileMode mode = tree.getFileMode(0);
                        if (FileMode.GITLINK.equals(mode)) {
                            limitations.add(TREE_LIMITATION_SUBMODULE);
                            continue;
                        }
                        if (tree.getPathString().contains("\\")) {
                            limitations.add(TREE_LIMITATION_UNSAFE_PATH);
                            excluded.add(tree.getPathString());
                            continue;
                        }
                        bytes = Math.addExact(bytes, reader.getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB));
                        files++;
                    }
                }
                long history = directorySize(mirror.resolve(Constants.OBJECTS));
                if (bytes + history > properties.maxSnapshotBytes()) {
                    throw new GitOperationException(
                            "Repository snapshot exceeds hephaestus.git.max-snapshot-bytes", new IOException());
                }
                Files.createDirectories(layout.root());
                staging = Files.createTempDirectory(layout.root(), EvidenceSpool.currentPrefix());
                try (Git git = Git.init().setDirectory(staging.toFile()).call()) {
                    StoredConfig config = git.getRepository().getConfig();
                    // A link in the checkout would let the review read outside it; it is a
                    // file holding the target.
                    config.setBoolean("core", null, "symlinks", false);
                    // A disposable checkout must not have background writers when it is removed.
                    config.setBoolean("fetch", null, "autoGC", false);
                    config.save();
                    if (originals.isEmpty()) {
                        git.fetch()
                                .setRemote(mirror.toUri().toString())
                                .setTransportConfigCallback(
                                        transports.callback(mirror.toUri().toString()))
                                .setRefSpecs(SNAPSHOT_REFSPECS)
                                .setTagOpt(TagOpt.NO_TAGS)
                                .call();
                    }
                    if (!originals.isEmpty()
                            || !git.getRepository().getObjectDatabase().has(commit)) {
                        // Pinned by id only: the reviewed commit on no ref here, or each
                        // original commit.
                        git.fetch()
                                .setRemote(mirror.toUri().toString())
                                .setTransportConfigCallback(
                                        transports.callback(mirror.toUri().toString()))
                                .setRefSpecs((originals.isEmpty() ? List.of(sha) : originals)
                                        .stream()
                                                .map(id -> new RefSpec(id + ":" + REVIEW_REFS + id))
                                                .toList())
                                .setTagOpt(TagOpt.NO_TAGS)
                                .call();
                    }
                    for (String id : originals) {
                        if (!git.getRepository().getObjectDatabase().has(ObjectId.fromString(id))) {
                            throw new GitOperationException("Original commit is not available", new IOException(id));
                        }
                    }
                    git.checkout().setName(sha).call();
                    // Private pins retain objects in the mirror. The checkout exposes provider refs and detached HEAD
                    // only.
                    for (var ref : git.getRepository().getRefDatabase().getRefsByPrefix(REVIEW_REFS)) {
                        var removal = git.getRepository().updateRef(ref.getName());
                        removal.setForceUpdate(true);
                        if (removal.delete() != RefUpdate.Result.FORCED)
                            throw new GitOperationException(
                                    "Could not remove a private checkout ref", new IOException());
                    }
                    for (String path : excluded) Files.deleteIfExists(staging.resolve(path));
                    if (originals.isEmpty()) writeCapturedRefs(git.getRepository());
                }
                return new GitTreeSnapshot(
                        staging, sha, commit.getTree().getName(), bytes, files, limitations.isEmpty(), limitations);
            } catch (GitOperationException e) {
                if (staging != null) deleteTreeQuietly(staging);
                throw e;
            } catch (GitAPIException | IOException | RuntimeException e) {
                if (staging != null) deleteTreeQuietly(staging);
                throw new GitOperationException(failure, e);
            }
        });
    }

    /**
     * Copies this server's checkout of {@code commit} to {@code target}, which must not exist. The checkout is the one
     * {@link #readOriginalSnapshot} makes of the commit alone, or {@link #readScanSnapshot} of {@code paths}. It is
     * built from the mirror on the first copy and kept inside the mirror, so it goes with the mirror's removal or
     * eviction; a copy counts as a use of the mirror. The build and the copy run under one hold of the mirror's read
     * lock, so no removal or eviction takes the mirror or the checkout between them.
     *
     * @return whether the checkout was built for this copy rather than found in the cache
     * @throws TreeMismatchException when the commit's tree is not {@code tree}
     * @throws MirrorUnavailableException when the mirror is gone, as after an eviction: the caller may fetch it again
     */
    public boolean copyCheckout(
            RepositoryKey repository, String commit, String tree, @Nullable List<String> paths, Path target) {
        requireEnabled();
        if (!ObjectId.isId(commit) || !ObjectId.isId(tree))
            throw new GitOperationException("Invalid checkout identity", new IOException(commit));
        Path entry = mirror(repository).resolve(CHECKOUTS).resolve(checkoutName(commit, tree, paths));
        return locks.withReadLock(repository, () -> {
            if (!isRepositoryCloned(repository)) throw new MirrorUnavailableException();
            boolean built = false;
            if (!Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                // The snapshot takes the read lock again; a reader that holds it may always take it once more.
                try (var snapshot = paths == null
                        ? snapshot(repository, commit, List.of(commit), "Cannot build the captured checkout")
                        : readScanSnapshot(repository, commit, Set.copyOf(paths))) {
                    if (!snapshot.treeSha().equals(tree)) throw new TreeMismatchException();
                    Files.createDirectories(entry.getParent());
                    Files.move(snapshot.stagingDir(), entry, StandardCopyOption.ATOMIC_MOVE);
                    built = true;
                } catch (FileAlreadyExistsException | DirectoryNotEmptyException concurrent) {
                    // Another copy built the same checkout first.
                } catch (IOException e) {
                    throw new GitOperationException("Cannot keep the built checkout", e);
                }
            }
            try {
                FileSystemUtils.copyRecursively(entry, target);
                FileTime now = FileTime.from(Instant.now());
                Files.setLastModifiedTime(entry, now);
                Files.setLastModifiedTime(mirror(repository), now);
            } catch (IOException e) {
                throw new GitOperationException("Cannot copy the built checkout", e);
            }
            return built;
        });
    }

    /** The commit and tree name a whole checkout; a scan's partial checkout also names a digest of its paths. */
    private static String checkoutName(String commit, String tree, @Nullable List<String> paths) {
        if (paths == null) return commit + "-" + tree;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String path : paths) {
                byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
                digest.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return commit + "-" + tree + "-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** The identity of the captured checkout includes all its readable refs, including annotated tag objects. */
    public static String treeIdentity(GitTreeSnapshot snapshot) {
        try (Git git = Git.open(snapshot.stagingDir().toFile())) {
            return treeIdentity(snapshot.commitSha(), readableRefs(git.getRepository(), false));
        } catch (IOException failure) {
            throw new GitOperationException("Could not read captured Git refs", failure);
        }
    }

    private static String treeIdentity(String head, Map<String, String> refs) {
        return head + ":" + SourceFingerprint.refs(refs);
    }

    private static Map<String, String> readableRefs(Repository repo, boolean mirror) throws IOException {
        var refs = new TreeMap<String, String>();
        for (var ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
            String name = ref.getName();
            if (mirror) {
                if (name.startsWith(Constants.R_HEADS))
                    name = Constants.R_REMOTES + "origin/" + name.substring(Constants.R_HEADS.length());
                else if (!name.startsWith(Constants.R_TAGS)) continue;
            }
            if (name.startsWith(REVIEW_REFS)) continue;
            var id = ref.getObjectId();
            if (id != null) refs.put(name, id.name());
        }
        return refs;
    }

    /** Tags are peeled and every line carries its type, so a tag on a tree or blob is witnessed, not walked. */
    private static void writeCapturedRefs(Repository repo) throws IOException {
        try (RevWalk walk = new RevWalk(repo);
                Writer out = Files.newBufferedWriter(
                        repo.getDirectory().toPath().resolve(CAPTURED_REFS), StandardCharsets.UTF_8)) {
            List<Ref> refs = new ArrayList<>(repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/"));
            refs.addAll(repo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS));
            for (Ref ref : refs) {
                ObjectId id = ref.getObjectId();
                if (id == null) continue;
                Ref peeled = repo.getRefDatabase().peel(ref);
                ObjectId target = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : id;
                out.write(Constants.typeString(walk.parseAny(target).getType())
                        + " "
                        + target.getName()
                        + " "
                        + ref.getName()
                        + "\n");
            }
        }
    }

    private static long directorySize(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> paths = Files.walk(directory)) {
            long total = 0;
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) total = Math.addExact(total, Files.size(path));
            }
            return total;
        }
    }

    /** A read of the mirror's object and ref databases. */
    public interface MirrorRead<T> {
        @Nullable
        T apply(Repository repo) throws IOException;
    }

    /** Runs a read against the mirror under its read lock; a fetch cannot rewrite refs underneath it. */
    public <T> @Nullable T read(RepositoryKey repository, MirrorRead<T> operation) {
        return locks.withReadLock(repository, () -> {
            try (Git git = Git.open(mirror(repository).toFile())) {
                return operation.apply(git.getRepository());
            } catch (IOException e) {
                throw new GitOperationException("Cannot read repository " + repository.repositoryId(), e);
            }
        });
    }

    private Path mirror(RepositoryKey repository) {
        return layout.root()
                .resolve(MIRRORS)
                .resolve(Long.toString(repository.workspaceId()))
                .resolve(repository.repositoryId() + ".git");
    }

    /** JGit fetches from the first configured URL but reads back the last; a stale value ahead of the current one is a change. */
    private static boolean hasOrigin(Path mirror, String cloneUrl) throws IOException {
        try (Git git = Git.open(mirror.toFile())) {
            String[] urls = git.getRepository().getConfig().getStringList("remote", "origin", "url");
            return urls.length == 1 && cloneUrl.equals(urls[0]);
        }
    }

    private static @Nullable UsernamePasswordCredentialsProvider credentials(@Nullable String token) {
        return token == null || token.isBlank() ? null : new UsernamePasswordCredentialsProvider("oauth2", token);
    }

    private void requireEnabled() {
        if (!isEnabled()) throw new IllegalStateException("Git repository preparation is disabled");
    }

    /** Bounds the commit rows written at once, not what is read from Git. */
    private void acquireIngestionPermit() {
        try {
            ingestionPermits.acquire();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new GitOperationException("Git ingestion admission interrupted", failure);
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new GitOperationException("Git operation interrupted", new InterruptedException());
    }

    static void deleteTreeQuietly(Path root) {
        try {
            FileSystemUtils.deleteRecursively(root);
        } catch (IOException e) {
            log.warn("Could not remove Git snapshot directory {}", root, e);
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

    /** The authorized upstream could not supply Git objects. Local storage failures stay distinct. */
    public static final class RepositoryFetchException extends GitOperationException {
        @Serial
        private static final long serialVersionUID = 1L;

        public RepositoryFetchException(GitAPIException cause) {
            super("Repository Git transport is unavailable", cause);
        }
    }

    /** A built checkout whose commit has another tree than the capture recorded. */
    public static final class TreeMismatchException extends GitOperationException {
        @Serial
        private static final long serialVersionUID = 1L;

        public TreeMismatchException() {
            super("The built checkout has another tree than the capture recorded", new IOException());
        }
    }

    /** The mirror is not on this server, as after an eviction: fetching it again makes it available. */
    public static final class MirrorUnavailableException extends GitOperationException {
        @Serial
        private static final long serialVersionUID = 1L;

        public MirrorUnavailableException() {
            super("The repository mirror is not on this server", new IOException());
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
