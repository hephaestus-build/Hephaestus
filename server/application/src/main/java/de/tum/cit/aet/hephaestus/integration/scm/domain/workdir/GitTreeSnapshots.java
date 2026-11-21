package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitCommitWalks.checkInterrupted;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager.CAPTURED_REFS;
import static de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager.REVIEW_REFS;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.EvidenceSpool;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager.GitOperationException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager.MirrorUnavailableException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager.TreeMismatchException;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

/**
 * The tree snapshots that a review or a scan reads from the bare mirrors of {@link GitRepositoryManager}: a scan's
 * pinned paths, a pull request's checkout with its own {@code .git}, that checkout again from the mirror's own
 * objects, and the checkouts kept inside a mirror for the captures of other servers. Each one is written under the
 * mirror's read lock. A scan's or a review's snapshot is also written inside the person-data copy fence.
 * {@link #copyCheckout} does not enter the fence: the checkout it caches belongs to the mirror, and it must not open
 * a second capture session.
 */
@Service
public class GitTreeSnapshots {
    public static final String TREE_LIMITATION_SUBMODULE = "SUBMODULE_EXCLUDED";
    public static final String TREE_LIMITATION_UNSAFE_PATH = "UNSAFE_PATH_EXCLUDED";

    /** Checkouts built again for captures of other servers, inside their mirror ({@link #copyCheckout}). */
    private static final String CHECKOUTS = "hephaestus-checkouts";

    private static final Logger log = LoggerFactory.getLogger(GitTreeSnapshots.class);

    private final GitRepositoryManager mirrors;
    private final GitRepositoryProperties properties;
    private final GitRepositoryLockManager locks;
    private final FabricLayout layout;
    private final GuardedGitTransport transports;
    private final PersonDataCopyFence copyFence;

    public GitTreeSnapshots(
            GitRepositoryManager mirrors,
            GitRepositoryProperties properties,
            GitRepositoryLockManager locks,
            FabricLayout layout,
            GuardedGitTransport transports,
            PersonDataCopyFence copyFence) {
        this.mirrors = mirrors;
        this.properties = properties;
        this.locks = locks;
        this.layout = layout;
        this.transports = transports;
        this.copyFence = copyFence;
    }

    public GitTreeSnapshot readScanSnapshot(RepositoryKey repository, String sha, Set<String> paths) {
        var admission = layout.spool().capture(repository.workspaceId(), copyFence::capture);
        try (admission) {
            mirrors.requireEnabled();
            if (!ObjectId.isId(sha)) throw new GitOperationException("Invalid scan commit", new IOException());
            return locks.withReadLock(repository, () -> {
                Path staging = null;
                try {
                    staging = layout.spool().directory(repository.workspaceId()).path();
                    return writeScanSnapshot(repository, sha, paths, staging);
                } catch (IOException | RuntimeException failure) {
                    if (staging != null) deleteTreeQuietly(staging);
                    throw new GitOperationException("Cannot capture the pinned scan tree", failure);
                }
            });
        }
    }

    /** The caller owns the destination and holds the mirror read lock. */
    private GitTreeSnapshot writeScanSnapshot(RepositoryKey repository, String sha, Set<String> paths, Path staging)
            throws IOException {
        try (Git git = Git.open(mirrors.mirror(repository).toFile());
                RevWalk walk = new RevWalk(git.getRepository());
                ObjectReader reader = git.getRepository().newObjectReader();
                TreeWalk tree = new TreeWalk(reader)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
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
        }
    }

    /**
     * A checkout of the reviewed {@code sha} with its own {@code .git}: that commit detached at HEAD, and the objects
     * of {@code base} and of both histories. It holds no branch and no tag: the review reads its change, never the
     * rest of the repository, so its {@linkplain #treeIdentity tree identity} is the two commits alone, and a push to
     * another branch changes nothing that it can read. Symbolic links are written as files holding their target. It
     * has no remote configuration and no credential. Measured before anything is written; a repository over the bound
     * is refused whole. The caller closes the snapshot to delete the directory.
     */
    public GitTreeSnapshot readTreeSnapshot(RepositoryKey repository, String sha, String base) {
        mirrors.requireEnabled();
        for (String id : List.of(sha, base))
            if (!ObjectId.isId(id)) throw new GitOperationException("Invalid commit", new IOException(id));
        return snapshot(
                repository,
                sha,
                List.copyOf(new LinkedHashSet<>(List.of(sha, base))),
                true,
                "Cannot prepare Git snapshot");
    }

    /**
     * The checkout {@link #readTreeSnapshot} made of {@code sha}, again: measured, bounded and limited the same way,
     * holding the named original commits and their history from the local mirror's own objects. No remote is
     * contacted, and no captured-refs record is written: the caller restores the original one. A commit the mirror no
     * longer holds refuses the snapshot rather than being replaced. The caller closes the snapshot to delete it.
     */
    public GitTreeSnapshot readOriginalSnapshot(RepositoryKey repository, String sha, Collection<String> commits) {
        mirrors.requireEnabled();
        var wanted = new LinkedHashSet<String>();
        wanted.add(sha);
        wanted.addAll(commits);
        for (String id : wanted) {
            if (!ObjectId.isId(id)) throw new GitOperationException("Invalid commit", new IOException(id));
        }
        return snapshot(repository, sha, List.copyOf(wanted), false, "Cannot restore the original Git snapshot");
    }

    /** Only {@code commits} and their history; {@code captured} writes the captured-refs record of a new capture. */
    private GitTreeSnapshot snapshot(
            RepositoryKey repository, String sha, List<String> commits, boolean captured, String failure) {
        var admission = layout.spool().capture(repository.workspaceId(), copyFence::capture);
        try (admission) {
            return locks.withReadLock(repository, () -> {
                Path staging = null;
                try {
                    staging = layout.spool().directory(repository.workspaceId()).path();
                    return writeSnapshot(repository, sha, commits, captured, staging);
                } catch (GitOperationException e) {
                    if (staging != null) deleteTreeQuietly(staging);
                    throw e;
                } catch (GitAPIException | IOException | RuntimeException e) {
                    if (staging != null) deleteTreeQuietly(staging);
                    throw new GitOperationException(failure, e);
                }
            });
        }
    }

    /** The caller owns the destination and holds the mirror read lock. */
    private GitTreeSnapshot writeSnapshot(
            RepositoryKey repository, String sha, List<String> commits, boolean captured, Path staging)
            throws GitAPIException, IOException {
        Path mirror = mirrors.mirror(repository);
        try (Git source = Git.open(mirror.toFile())) {
            Repository repo = source.getRepository();
            RevCommit commit;
            try (RevWalk walk = new RevWalk(repo)) {
                // A missing commit fails here, before any source content is written.
                for (String id : commits) walk.parseCommit(ObjectId.fromString(id));
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
            try (Git git = Git.init().setDirectory(staging.toFile()).call()) {
                StoredConfig config = git.getRepository().getConfig();
                // A link in the checkout would let the review read outside it; it is a
                // file holding the target.
                config.setBoolean("core", null, "symlinks", false);
                // A disposable checkout must not have background writers when it is removed.
                config.setBoolean("fetch", null, "autoGC", false);
                config.save();
                // Fetched by id only: the checkout holds these commits and their history, and no branch or tag.
                git.fetch()
                        .setRemote(mirror.toUri().toString())
                        .setTransportConfigCallback(
                                transports.callback(mirror.toUri().toString()))
                        .setRefSpecs(commits.stream()
                                .map(id -> new RefSpec(id + ":" + REVIEW_REFS + id))
                                .toList())
                        .setTagOpt(TagOpt.NO_TAGS)
                        .call();
                for (String id : commits) {
                    if (!git.getRepository().getObjectDatabase().has(ObjectId.fromString(id))) {
                        throw new GitOperationException("A commit is not available", new IOException(id));
                    }
                }
                git.checkout().setName(sha).call();
                // The pins only fetched the objects. The checkout exposes the detached HEAD and no ref.
                for (var ref : git.getRepository().getRefDatabase().getRefsByPrefix(REVIEW_REFS)) {
                    var removal = git.getRepository().updateRef(ref.getName());
                    removal.setForceUpdate(true);
                    if (removal.delete() != RefUpdate.Result.FORCED)
                        throw new GitOperationException("Could not remove a private checkout ref", new IOException());
                }
                for (String path : excluded) Files.deleteIfExists(staging.resolve(path));
                if (captured) writeCapturedRefs(git.getRepository());
            }
            return new GitTreeSnapshot(
                    staging, sha, commit.getTree().getName(), bytes, files, limitations.isEmpty(), limitations);
        }
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
        mirrors.requireEnabled();
        if (!ObjectId.isId(commit) || !ObjectId.isId(tree))
            throw new GitOperationException("Invalid checkout identity", new IOException(commit));
        Path entry = mirrors.mirror(repository).resolve(CHECKOUTS).resolve(checkoutName(commit, tree, paths));
        return locks.withReadLock(repository, () -> {
            if (!mirrors.isRepositoryCloned(repository)) throw new MirrorUnavailableException();
            boolean built = false;
            if (!Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectories(entry.getParent());
                    Path staging = Files.createTempDirectory(entry.getParent(), ".preparing-");
                    Closeable cleanup = () -> FileSystemUtils.deleteRecursively(staging);
                    try (cleanup) {
                        // Cache construction belongs to the mirror, including an interrupted staging directory.
                        // It must not acquire a second capture session inside an admitting transaction.
                        var snapshot = paths == null
                                ? writeSnapshot(repository, commit, List.of(commit), false, staging)
                                : writeScanSnapshot(repository, commit, Set.copyOf(paths), staging);
                        if (!snapshot.treeSha().equals(tree)) throw new TreeMismatchException();
                        Files.move(staging, entry, StandardCopyOption.ATOMIC_MOVE);
                        built = true;
                    }
                } catch (FileAlreadyExistsException | DirectoryNotEmptyException concurrent) {
                    // Another copy built the same checkout first.
                } catch (GitOperationException e) {
                    throw e;
                } catch (GitAPIException | IOException | RuntimeException e) {
                    throw new GitOperationException("Cannot keep the built checkout", e);
                }
            }
            try {
                FileSystemUtils.copyRecursively(entry, target);
                FileTime now = FileTime.from(Instant.now());
                Files.setLastModifiedTime(entry, now);
                Files.setLastModifiedTime(mirrors.mirror(repository), now);
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

    /**
     * The identity of a pull request's captured checkout ({@link #readTreeSnapshot}): its two commits, which address
     * everything it holds. The server computes the same value from the pull request's version, with no checkout.
     */
    public static String treeIdentity(String head, String base) {
        return head + ":" + base;
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

    static void deleteTreeQuietly(Path root) {
        try {
            EvidenceSpool.close(root);
        } catch (IOException e) {
            log.atWarn()
                    .addKeyValue("event.name", "evidence.spool.cleanup.failed")
                    .addKeyValue("evidence.path", root.toString())
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Could not remove Git snapshot directory");
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
            try {
                EvidenceSpool.close(stagingDir);
            } catch (IOException failed) {
                throw new GitOperationException("Could not remove the captured Git snapshot", failed);
            }
        }
    }
}
