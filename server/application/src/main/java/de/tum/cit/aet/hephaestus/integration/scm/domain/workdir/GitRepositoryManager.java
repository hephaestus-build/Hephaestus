package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@EnableConfigurationProperties(GitRepositoryProperties.class)
public class GitRepositoryManager {

    private static final String SCM_CONNECTOR = "scm";

    /**
     * What a tree snapshot could not stage. Every one of these makes the capture {@code PARTIAL}, and
     * each names the omission rather than the count, because the reader's question is "what could this
     * evidence not have shown me", not "how much of it is there".
     */
    public static final String TREE_LIMITATION_SYMLINK = "SYMLINK_EXCLUDED";

    /** A submodule's gitlink; its contents live in another repository this snapshot does not read. */
    public static final String TREE_LIMITATION_SUBMODULE = "SUBMODULE_EXCLUDED";

    /** A tree entry whose git mode is neither a regular nor an executable file. */
    public static final String TREE_LIMITATION_UNSUPPORTED_MODE = "UNSUPPORTED_GIT_MODE_EXCLUDED";

    /** A path that would escape the staging root; excluded rather than resolved. */
    public static final String TREE_LIMITATION_UNSAFE_PATH = "UNSAFE_PATH_EXCLUDED";

    /** One blob over {@code hephaestus.git.tree-max-file-size} was skipped; the walk continued. */
    public static final String TREE_LIMITATION_FILE_TOO_LARGE = "FILE_TOO_LARGE_EXCLUDED";

    /** The walk stopped at {@code hephaestus.git.tree-max-files}; the rest of the tree was never read. */
    public static final String TREE_LIMITATION_FILE_COUNT = "FILE_COUNT_LIMIT_REACHED";

    /** The walk stopped at {@code hephaestus.git.tree-max-total-size}; the rest was never read. */
    public static final String TREE_LIMITATION_TOTAL_SIZE = "TOTAL_SIZE_LIMIT_REACHED";

    /** A blob {@link RawText#isBinary}, the diff's own heuristic, calls binary was skipped; the walk continued. */
    public static final String TREE_LIMITATION_BINARY = "BINARY_FILE_EXCLUDED";

    private final GitRepositoryProperties properties;
    private final GitRepositoryLockManager lockManager;
    private final FabricLayout fabricLayout;

    public GitRepositoryManager(
            GitRepositoryProperties properties, GitRepositoryLockManager lockManager, FabricLayout fabricLayout) {
        this.properties = properties;
        this.lockManager = lockManager;
        this.fabricLayout = fabricLayout;

        if (properties.enabled()) {
            try {
                Files.createDirectories(fabricLayout.root());
                log.info("Git repository storage initialized under fabric root: {}", fabricLayout.root());
            } catch (IOException e) {
                log.error("Failed to create fabric storage root: {}", fabricLayout.root(), e);
                throw new IllegalStateException("Cannot initialize git storage", e);
            }
        }
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public Path getRepositoryPath(Long repositoryId) {
        return fabricLayout.source(SCM_CONNECTOR, repositoryId.toString());
    }

    public boolean isRepositoryCloned(Long repositoryId) {
        Path repoPath = getRepositoryPath(repositoryId);
        return Files.exists(repoPath.resolve(".git").resolve("HEAD"));
    }

    /**
     * Delete the local clone for a repository.
     * <p>
     * Acquires a write lock, recursively deletes the clone directory, then removes the lock
     * entry from the lock manager. Safe to call even if the clone does not exist (no-op).
     *
     * @param repositoryId the repository database ID
     */
    public void deleteClone(Long repositoryId) {
        if (!properties.enabled()) {
            return;
        }

        lockManager.withWriteLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            if (Files.exists(repoPath)) {
                try {
                    deleteRecursively(repoPath);
                    log.info("Deleted local git clone: repoId={}, path={}", repositoryId, repoPath);
                } catch (IOException e) {
                    // A partial delete that leaves .git/HEAD behind would let isRepositoryCloned() keep
                    // reporting true, so a later ensureRepository() would fetch into a half-deleted tree
                    // forever. Fall back to JGit's forced recursive delete (same as cloneRepository's
                    // stale-checkout cleanup); if even that fails, surface the failure so the purge caller
                    // can react instead of silently leaving a corrupt clone in place.
                    log.warn(
                            "Recursive delete failed, retrying with forced delete: repoId={}, path={}, error={}",
                            repositoryId,
                            repoPath,
                            e.getMessage());
                    try {
                        FileUtils.delete(repoPath.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING);
                        log.info(
                                "Deleted local git clone via forced delete: repoId={}, path={}",
                                repositoryId,
                                repoPath);
                    } catch (IOException forced) {
                        log.error(
                                "Failed to delete local git clone: repoId={}, path={}, error={}",
                                repositoryId,
                                repoPath,
                                forced.getMessage(),
                                forced);
                        GitOperationException failure =
                                new GitOperationException("Failed to delete local git clone: " + repositoryId, forced);
                        failure.addSuppressed(e);
                        throw failure;
                    }
                }
            }
        });

        lockManager.removeLock(repositoryId);
    }

    public Path ensureRepository(Long repositoryId, String cloneUrl, @Nullable String token) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Git local checkout is not enabled");
        }

        return lockManager.withWriteLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);

            try {
                if (!isRepositoryCloned(repositoryId)) {
                    cloneRepository(repoPath, cloneUrl, token);
                } else if (!hasOrigin(repoPath, cloneUrl)) {
                    // The checkout is a cache keyed by a database id, and a restore can reuse that id for
                    // a different upstream. Reclone rather than retarget so no old objects survive.
                    log.warn("Repository origin changed; rebuilding checkout: repoId={}", repositoryId);
                    cloneRepository(repoPath, cloneUrl, token);
                } else {
                    try {
                        fetchRepository(repoPath, token);
                    } catch (InvalidRemoteException e) {
                        // JGit reports two faults as "invalid remote": the checkout's own remote
                        // configuration does not parse (a URISyntaxException cause), which only a rebuild
                        // fixes, or the remote answered that the repository is not there, which a rebuild
                        // would only turn into a lost cache; a transport or authentication failure is a
                        // different exception and stays visible.
                        if (!(NestedExceptionUtils.getMostSpecificCause(e) instanceof URISyntaxException)) {
                            log.error(
                                    "Repository not found upstream; keeping checkout: repoId={}, error={}",
                                    repositoryId,
                                    e.getMessage(),
                                    e);
                            throw new GitOperationException("Repository not found upstream: " + repositoryId, e);
                        }
                        log.warn(
                                "Repository remote configuration does not parse; rebuilding checkout: repoId={}",
                                repositoryId);
                        cloneRepository(repoPath, cloneUrl, token);
                    }
                }
                return repoPath;
            } catch (GitAPIException | IOException e) {
                log.error("Failed to ensure repository: repoId={}, error={}", repositoryId, e.getMessage(), e);
                throw new GitOperationException("Failed to ensure repository: " + repositoryId, e);
            }
        });
    }

    /**
     * Whether the checkout's origin is exactly the clone URL. JGit fetches from the first {@code url}
     * value of a remote but reads the last one back, so a stale value left ahead of the current URL
     * is an origin change, not a match.
     */
    private static boolean hasOrigin(Path repoPath, String cloneUrl) throws IOException {
        try (Git git = Git.open(repoPath.toFile())) {
            String[] configuredUrls = git.getRepository().getConfig().getStringList("remote", "origin", "url");
            return configuredUrls.length == 1 && cloneUrl.equals(configuredUrls[0]);
        }
    }

    /**
     * Clone a repository as a full clone.
     */
    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    private void cloneRepository(Path repoPath, String cloneUrl, @Nullable String token)
            throws GitAPIException, IOException {
        log.info("Cloning repository: url={}, path={}", sanitizeUrl(cloneUrl), repoPath);

        // We only reach here when isRepositoryCloned() is false, so any directory already at
        // repoPath is not a usable clone (a partial/aborted clone, or a stale checkout left by a
        // reused repository id). JGit refuses to clone into a non-empty directory, so remove it
        // first for a clean checkout instead of failing the whole commit backfill.
        if (Files.exists(repoPath)) {
            log.warn("Removing stale/partial checkout before clone: path={}", repoPath);
            FileUtils.delete(repoPath.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING);
        }

        Files.createDirectories(repoPath.getParent());

        var cloneCommand = Git.cloneRepository()
                .setURI(cloneUrl)
                .setDirectory(repoPath.toFile())
                .setBare(false)
                .setCloneAllBranches(true);

        if (token != null && !token.isBlank()) {
            cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token));
        }

        try (Git ignored = cloneCommand.call()) {
            log.info("Successfully cloned repository: path={}", repoPath);
        }
    }

    /**
     * Fetch updates for an existing repository.
     */
    private void fetchRepository(Path repoPath, @Nullable String token) throws GitAPIException, IOException {
        log.debug("Fetching repository updates: path={}", repoPath);

        try (Git git = Git.open(repoPath.toFile())) {
            var fetchCommand = git.fetch().setRemote("origin").setRemoveDeletedRefs(true);

            if (token != null && !token.isBlank()) {
                fetchCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token));
            }

            fetchCommand.call();
            log.debug("Successfully fetched repository: path={}", repoPath);
        }
    }

    /**
     * Fetches a provider-owned synthetic pull/merge-request ref and verifies it resolves to
     * {@code expectedSha}. Needed for reviews submitted from forks, whose commits are absent from the
     * target repository's own branch refs.
     */
    public boolean fetchRemoteCommit(Long repositoryId, String remoteRef, String expectedSha, @Nullable String token) {
        if (!properties.enabled()) {
            return false;
        }
        if (!Repository.isValidRefName(remoteRef) || !ObjectId.isId(expectedSha)) {
            throw new IllegalArgumentException("Invalid remote review ref or expected commit SHA");
        }

        return lockManager.withWriteLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            String localRef = "refs/hephaestus/reviews/" + expectedSha.toLowerCase(java.util.Locale.ROOT);
            try (Git git = Git.open(repoPath.toFile())) {
                var fetch = git.fetch().setRemote("origin").setRefSpecs(new RefSpec("+" + remoteRef + ":" + localRef));
                if (token != null && !token.isBlank()) {
                    fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token));
                }
                fetch.call();

                ObjectId resolved = git.getRepository().resolve(localRef);
                ObjectId expected = ObjectId.fromString(expectedSha);
                if (!expected.equals(resolved)) {
                    return false;
                }
                try (RevWalk walk = new RevWalk(git.getRepository())) {
                    walk.parseCommit(resolved);
                }
                return true;
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Failed to fetch remote review commit: " + repositoryId, e);
            }
        });
    }

    @Nullable
    public String resolveDefaultBranchHead(Long repositoryId, String defaultBranch) {
        if (!properties.enabled()) {
            return null;
        }

        return lockManager.withReadLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            try (Git git = Git.open(repoPath.toFile())) {
                Repository repo = git.getRepository();

                String ref = "refs/remotes/origin/" + defaultBranch;
                ObjectId objectId = repo.resolve(ref);
                if (objectId != null) {
                    return objectId.getName();
                }

                log.warn("Cannot resolve default branch HEAD: repoId={}, branch={}", repositoryId, defaultBranch);
                return null;
            } catch (IOException e) {
                log.error(
                        "Failed to resolve default branch HEAD: repoId={}, branch={}, error={}",
                        repositoryId,
                        defaultBranch,
                        e.getMessage());
                return null;
            }
        });
    }

    /**
     * Check if a commit SHA exists in the local clone.
     *
     * @param repositoryId the repository database ID
     * @param sha          the full commit SHA hex string
     * @return true if the SHA resolves to a commit object in the local repo
     */
    public boolean commitExists(Long repositoryId, String sha) {
        if (!properties.enabled() || sha == null || sha.isBlank()) {
            return false;
        }

        return lockManager.withReadLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            try (Git git = Git.open(repoPath.toFile());
                    RevWalk walk = new RevWalk(git.getRepository())) {
                ObjectId objectId = git.getRepository().resolve(sha);
                if (objectId == null) {
                    return false;
                }
                walk.parseCommit(objectId);
                return true;
            } catch (IOException e) {
                log.debug(
                        "Cannot check commit existence: repoId={}, sha={}, error={}",
                        repositoryId,
                        sha,
                        e.getMessage());
                return false;
            }
        });
    }

    /**
     * Lightweight SHA-to-email resolution from the local git clone.
     * <p>
     * For each SHA in the input set, reads the {@link RevCommit} to extract
     * author and committer email addresses. This is very fast — only the commit
     * object is parsed (no diff computation).
     *
     * @param repositoryId the repository database ID
     * @param shas         the commit SHAs to resolve
     * @return map from SHA to {@link EmailPair} (author + committer email)
     */
    public Map<String, EmailPair> resolveCommitEmails(Long repositoryId, Set<String> shas) {
        if (!properties.enabled() || shas.isEmpty()) {
            return Map.of();
        }

        return lockManager.withReadLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            Map<String, EmailPair> result = new HashMap<>();

            try (Git git = Git.open(repoPath.toFile())) {
                Repository repo = git.getRepository();
                try (RevWalk revWalk = new RevWalk(repo)) {
                    for (String sha : shas) {
                        try {
                            ObjectId objectId = repo.resolve(sha);
                            if (objectId == null) {
                                log.debug("Cannot resolve SHA for email lookup: sha={}", sha);
                                continue;
                            }
                            RevCommit commit = revWalk.parseCommit(objectId);
                            result.put(
                                    sha,
                                    new EmailPair(
                                            commit.getAuthorIdent().getEmailAddress(),
                                            commit.getCommitterIdent().getEmailAddress()));
                            revWalk.reset();
                        } catch (IOException e) {
                            log.debug("Failed to parse commit for email: sha={}, error={}", sha, e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.error(
                        "Failed to open repo for email resolution: repoId={}, error={}", repositoryId, e.getMessage());
            }

            return result;
        });
    }

    /**
     * Author and committer email pair for a commit.
     */
    public record EmailPair(String authorEmail, String committerEmail) {}

    /**
     * Walk commits between two SHAs and extract commit info with file changes.
     *
     * @param repositoryId the repository database ID
     * @param fromSha the starting commit (exclusive), or null for initial commit
     * @param toSha the ending commit (inclusive)
     * @return list of commit info with file changes
     */
    public List<CommitInfo> walkCommits(Long repositoryId, @Nullable String fromSha, String toSha) {
        return walkCommits(repositoryId, fromSha, toSha, Integer.MAX_VALUE);
    }

    public List<CommitInfo> walkCommits(Long repositoryId, @Nullable String fromSha, String toSha, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (!properties.enabled()) {
            return List.of();
        }

        return lockManager.withReadLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            List<CommitInfo> commits = new ArrayList<>();

            try (Git git = Git.open(repoPath.toFile())) {
                Repository repo = git.getRepository();

                ObjectId toId = repo.resolve(toSha);
                if (toId == null) {
                    log.warn("Cannot resolve toSha: {}", toSha);
                    return commits;
                }

                ObjectId fromId = fromSha != null ? repo.resolve(fromSha) : null;

                try (RevWalk revWalk = new RevWalk(repo)) {
                    revWalk.markStart(revWalk.parseCommit(toId));
                    if (fromId != null) {
                        revWalk.markUninteresting(revWalk.parseCommit(fromId));
                    }

                    for (RevCommit revCommit : revWalk) {
                        CommitInfo commitInfo = extractCommitInfo(repo, revCommit);
                        commits.add(commitInfo);
                        if (commits.size() >= limit) break;
                    }
                }
            } catch (IOException e) {
                log.error("Failed to walk commits: repoId={}, error={}", repositoryId, e.getMessage(), e);
                throw new GitOperationException("Failed to walk commits for repository: " + repositoryId, e);
            }

            return commits;
        });
    }

    /**
     * Walk commits reachable from every remote-tracking branch ({@code refs/remotes/origin/*}).
     *
     * <p>Unlike {@link #walkCommits(Long, String, String)} which traverses a single ref's
     * ancestry, this method marks every branch head as a walk start point so a commit that
     * only exists on a feature branch (never merged into the default branch) is still
     * discovered. The result is deduplicated by SHA, preserving the first occurrence order
     * emitted by the walk (newest first).
     *
     * <p>When {@code fromSha} is provided, it is marked {@code uninteresting} so commits
     * reachable from that point are excluded — useful for incremental backfills.
     *
     * @param repositoryId the repository database ID
     * @param fromSha      optional exclusion point for incremental walks; pass {@code null}
     *                     on initial backfill
     * @return commit info for every unique commit reachable from any remote branch
     */
    public List<CommitInfo> walkAllBranches(Long repositoryId, @Nullable String fromSha) {
        if (!properties.enabled()) {
            return List.of();
        }

        return lockManager.withReadLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            LinkedHashMap<String, CommitInfo> uniqueCommits = new LinkedHashMap<>();

            try (Git git = Git.open(repoPath.toFile())) {
                Repository repo = git.getRepository();

                List<org.eclipse.jgit.lib.Ref> remoteRefs =
                        new ArrayList<>(repo.getRefDatabase().getRefsByPrefix("refs/remotes/origin/"));
                if (remoteRefs.isEmpty()) {
                    log.warn("No remote branches found for multi-branch walk: repoId={}", repositoryId);
                    // Return a mutable empty list, matching the normal-path return below, so callers see one
                    // consistent contract (GitLabCommitBackfillService calls subList on the result).
                    return new ArrayList<>();
                }

                ObjectId fromId = fromSha != null ? repo.resolve(fromSha) : null;

                try (RevWalk revWalk = new RevWalk(repo)) {
                    for (org.eclipse.jgit.lib.Ref ref : remoteRefs) {
                        // Skip symbolic refs like refs/remotes/origin/HEAD — they alias another branch.
                        if (ref.isSymbolic()) {
                            continue;
                        }
                        ObjectId objectId = ref.getObjectId();
                        if (objectId == null) {
                            continue;
                        }
                        try {
                            revWalk.markStart(revWalk.parseCommit(objectId));
                        } catch (IOException e) {
                            log.debug(
                                    "Skipped ref during multi-branch walk: repoId={}, ref={}, error={}",
                                    repositoryId,
                                    ref.getName(),
                                    e.getMessage());
                        }
                    }

                    if (fromId != null) {
                        try {
                            revWalk.markUninteresting(revWalk.parseCommit(fromId));
                        } catch (IOException e) {
                            log.debug(
                                    "Cannot mark fromSha uninteresting — falling back to full walk: repoId={}, fromSha={}, error={}",
                                    repositoryId,
                                    fromSha,
                                    e.getMessage());
                        }
                    }

                    for (RevCommit revCommit : revWalk) {
                        String sha = revCommit.getName();
                        if (uniqueCommits.containsKey(sha)) {
                            continue;
                        }
                        uniqueCommits.put(sha, extractCommitInfo(repo, revCommit));
                    }
                }
            } catch (IOException e) {
                log.error("Failed to walk all branches: repoId={}, error={}", repositoryId, e.getMessage(), e);
                throw new GitOperationException("Failed to walk all branches for repository: " + repositoryId, e);
            }

            return new ArrayList<>(uniqueCommits.values());
        });
    }

    /**
     * Extract detailed commit information including file changes.
     */
    private CommitInfo extractCommitInfo(Repository repo, RevCommit revCommit) throws IOException {
        PersonIdent authorIdent = revCommit.getAuthorIdent();
        PersonIdent committerIdent = revCommit.getCommitterIdent();

        List<FileChange> fileChanges = extractFileChanges(repo, revCommit);

        int totalAdditions = 0;
        int totalDeletions = 0;
        for (FileChange fc : fileChanges) {
            totalAdditions += fc.additions();
            totalDeletions += fc.deletions();
        }

        String message = revCommit.getShortMessage();
        if (message == null) {
            message = "";
        }
        String fullMessage = revCommit.getFullMessage();
        // Extract the body: everything after the first line break in the full message.
        // We cannot use getShortMessage().length() as offset because getShortMessage()
        // trims trailing whitespace, causing an off-by-one mismatch with the full message.
        String messageBody = null;
        int newlineIndex = fullMessage.indexOf('\n');
        if (newlineIndex >= 0 && newlineIndex < fullMessage.length() - 1) {
            messageBody = fullMessage.substring(newlineIndex + 1).trim();
            if (messageBody.isEmpty()) {
                messageBody = null;
            }
        }

        // Parent SHAs come directly from the commit object graph and cost nothing
        // extra to read here. Feeds git_commit.parent_count + parent_shas so
        // downstream consumers (audit, commit-topology queries) don't depend on
        // provider-specific APIs for this basic structural field.
        RevCommit[] parents = revCommit.getParents();
        List<String> parentShas = new ArrayList<>(parents != null ? parents.length : 0);
        if (parents != null) {
            for (RevCommit parent : parents) {
                if (parent != null && parent.getId() != null) {
                    parentShas.add(parent.getId().getName());
                }
            }
        }

        return new CommitInfo(
                revCommit.getName(),
                message,
                messageBody,
                authorIdent.getName(),
                authorIdent.getEmailAddress(),
                authorIdent.getWhenAsInstant(),
                committerIdent.getName(),
                committerIdent.getEmailAddress(),
                committerIdent.getWhenAsInstant(),
                totalAdditions,
                totalDeletions,
                fileChanges.size(),
                fileChanges,
                parentShas);
    }

    /**
     * Extract file changes for a commit by diffing against its parent.
     * <p>
     * Parent commits obtained via {@code RevCommit.getParent()} are stubs whose
     * tree pointers are null. We must use {@link RevWalk#parseCommit} to fully
     * load the parent before accessing its tree.
     */
    private List<FileChange> extractFileChanges(Repository repo, RevCommit commit) throws IOException {
        List<FileChange> changes = new ArrayList<>();

        try (RevWalk parentWalk = new RevWalk(repo);
                ObjectReader reader = repo.newObjectReader();
                DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repo);
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);
            diffFormatter.setDetectRenames(true);

            CanonicalTreeParser newTreeParser = new CanonicalTreeParser();
            newTreeParser.reset(reader, commit.getTree());

            List<DiffEntry> diffs;
            if (commit.getParentCount() > 0) {
                // Must fully parse the parent to populate its tree pointer
                RevCommit parent = parentWalk.parseCommit(commit.getParent(0).getId());
                CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
                oldTreeParser.reset(reader, parent.getTree());
                diffs = diffFormatter.scan(oldTreeParser, newTreeParser);
            } else {
                // Initial commit - diff against empty tree
                diffs = diffFormatter.scan(new EmptyTreeIterator(), newTreeParser);
            }

            for (DiffEntry diff : diffs) {
                FileChange change = createFileChange(diffFormatter, diff);
                changes.add(change);
            }
        }

        return changes;
    }

    /**
     * Create a FileChange from a DiffEntry, counting additions/deletions.
     * <p>
     * Uses {@link DiffFormatter#toFileHeader(DiffEntry)} with {@link Edit} regions
     * to compute line counts directly from the diff algorithm, avoiding the need to
     * generate patch text. This approach is both faster and avoids the JGit
     * {@code DiffDriver.valueOf()} NPE that occurs during {@code format()} for
     * repositories with certain {@code .gitattributes} configurations.
     */
    private FileChange createFileChange(DiffFormatter diffFormatter, DiffEntry diff) throws IOException {
        String filename = diff.getChangeType() == DiffEntry.ChangeType.DELETE ? diff.getOldPath() : diff.getNewPath();

        String previousFilename = diff.getChangeType() == DiffEntry.ChangeType.RENAME ? diff.getOldPath() : null;

        ChangeType changeType = mapChangeType(diff.getChangeType());

        // Count additions and deletions from EditList regions
        int additions = 0;
        int deletions = 0;

        try {
            FileHeader fileHeader = diffFormatter.toFileHeader(diff);
            for (Edit edit : fileHeader.toEditList()) {
                deletions += edit.getEndA() - edit.getBeginA();
                additions += edit.getEndB() - edit.getBeginB();
            }
        } catch (Exception e) {
            // Certain .gitattributes configurations can cause JGit internal errors.
            // Fall back to zero additions/deletions — we still have the filename
            // and change type from the DiffEntry itself.
            log.debug("Skipped diff stats for file: filename={}, error={}", filename, e.getMessage());
        }

        return new FileChange(filename, changeType, additions, deletions, additions + deletions, previousFilename);
    }

    private ChangeType mapChangeType(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADD -> ChangeType.ADDED;
            case MODIFY -> ChangeType.MODIFIED;
            case DELETE -> ChangeType.REMOVED;
            case RENAME -> ChangeType.RENAMED;
            case COPY -> ChangeType.COPIED;
        };
    }

    /**
     * Materialises a commit tree into a temporary directory.
     *
     * The caller owns the result and must {@link GitTreeSnapshot#close() close} it to delete the directory.
     *
     * <p>Symlinks, submodules and paths that escape the staging root are excluded rather than followed,
     * so nothing outside the commit's own tree can be written or read through the snapshot. Every
     * exclusion, and every bound in {@link GitRepositoryProperties} the walk hits, is named in
     * {@link GitTreeSnapshot#limitations()} and makes {@link GitTreeSnapshot#complete()} false, so nothing
     * downstream can claim something is absent from a repository it only partly saw.
     *
     * <p>A binary blob is skipped and not counted against the bounds: a practice review reads source, and
     * an image or an archive shows it nothing. Nothing of a rejected blob reaches disk — its size comes
     * from the object database, and the head the binary rule reads is held in memory.
     *
     * <p>Git handles are opened and closed entirely within this call rather than returned as lazy readers,
     * which would leave an {@code ObjectReader} and the repository read lock open across the staging
     * boundary.
     */
    public GitTreeSnapshot readTreeSnapshot(Long repositoryId, String commitSha) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Repository checkout is disabled; callers must check isEnabled()");
        }

        return lockManager.withReadLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            Path stagingDir;
            try {
                stagingDir = Files.createTempDirectory("tree-snapshot-");
            } catch (IOException e) {
                throw new GitOperationException("Could not create staging directory for repoId=" + repositoryId, e);
            }

            Map<String, Path> result = new LinkedHashMap<>();
            Set<String> limitations = new java.util.TreeSet<>();
            long totalBytes = 0;
            int visitedFiles = 0;
            String resolvedCommitSha;
            String treeSha;

            try (Git git = Git.open(repoPath.toFile())) {
                Repository repo = git.getRepository();

                ObjectId commitId = repo.resolve(commitSha);
                if (commitId == null) {
                    throw new IOException("Cannot resolve commit SHA: " + commitSha);
                }

                try (RevWalk revWalk = new RevWalk(repo);
                        ObjectReader reader = repo.newObjectReader()) {
                    RevCommit commit = revWalk.parseCommit(commitId);
                    resolvedCommitSha = commit.getId().getName();
                    treeSha = commit.getTree().getId().getName();

                    long maxTotalBytes = properties.treeMaxTotalSize().toBytes();
                    long maxFileBytes = properties.treeMaxFileSize().toBytes();
                    int maxFiles = properties.treeMaxFiles();

                    try (TreeWalk treeWalk = new TreeWalk(reader)) {
                        treeWalk.addTree(commit.getTree());
                        treeWalk.setRecursive(true);

                        while (treeWalk.next()) {
                            visitedFiles++;
                            String sourcePath = treeWalk.getPathString();
                            FileMode mode = treeWalk.getFileMode(0);
                            if (FileMode.SYMLINK.equals(mode)) {
                                limitations.add(TREE_LIMITATION_SYMLINK);
                                continue;
                            }
                            if (FileMode.GITLINK.equals(mode)) {
                                limitations.add(TREE_LIMITATION_SUBMODULE);
                                continue;
                            }
                            if (!FileMode.REGULAR_FILE.equals(mode) && !FileMode.EXECUTABLE_FILE.equals(mode)) {
                                limitations.add(TREE_LIMITATION_UNSUPPORTED_MODE);
                                continue;
                            }
                            if (unsafeWorkspacePath(sourcePath)) {
                                limitations.add(TREE_LIMITATION_UNSAFE_PATH);
                                continue;
                            }
                            // The count bound stops the walk rather than skipping a file: past it we no
                            // longer know what we are not reading, so "we read the tree in order up to the
                            // limit" stays a claim we can state, where "we read some of it" is not.
                            if (result.size() >= maxFiles) {
                                limitations.add(TREE_LIMITATION_FILE_COUNT);
                                log.warn(
                                        "Repository tree hit the file-count bound; snapshot is partial: repoId={}, commit={}, maxFiles={}",
                                        repositoryId,
                                        commitSha,
                                        maxFiles);
                                break;
                            }
                            ObjectId blobId = treeWalk.getObjectId(0);
                            long blobSize = reader.getObjectSize(blobId, Constants.OBJ_BLOB);
                            Path target = stagingDir.resolve(sourcePath);
                            try (InputStream blob =
                                    reader.open(blobId, Constants.OBJ_BLOB).openStream()) {
                                // Sniffed before either size bound: a skipped blob must not end the walk,
                                // and an oversized image is a binary, not missing source.
                                byte[] head = blob.readNBytes(RawText.getBufferSize());
                                if (RawText.isBinary(head, head.length, head.length >= blobSize)) {
                                    limitations.add(TREE_LIMITATION_BINARY);
                                    continue;
                                }
                                if (blobSize > maxFileBytes) {
                                    // One outsized blob does not end the walk: dropping the rest of the
                                    // tree with it would cost the review the source it came for.
                                    limitations.add(TREE_LIMITATION_FILE_TOO_LARGE);
                                    log.debug("Skipping oversized file: path={}, size={}", sourcePath, blobSize);
                                    continue;
                                }
                                if (totalBytes + blobSize > maxTotalBytes) {
                                    limitations.add(TREE_LIMITATION_TOTAL_SIZE);
                                    log.warn(
                                            "Repository tree hit the total-size bound; snapshot is partial: repoId={}, commit={}, maxTotalBytes={}",
                                            repositoryId,
                                            commitSha,
                                            maxTotalBytes);
                                    break;
                                }
                                Files.createDirectories(target.getParent());
                                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                                    out.write(head);
                                    blob.transferTo(out);
                                }
                            }
                            totalBytes += blobSize;
                            result.put(sourcePath, target);
                        }
                    }
                }
            } catch (IOException e) {
                deleteTreeQuietly(stagingDir);
                throw new GitOperationException(
                        "Failed to read files at commit: repoId=" + repositoryId + ", commit=" + commitSha, e);
            }

            return new GitTreeSnapshot(
                    stagingDir,
                    resolvedCommitSha,
                    treeSha,
                    result,
                    totalBytes,
                    visitedFiles,
                    limitations.isEmpty(),
                    limitations);
        });
    }

    static void deleteTreeQuietly(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete staged file {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not delete staging directory {}", root, e);
        }
    }

    private static boolean unsafeWorkspacePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            return true;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A commit tree materialised on disk. {@code files} maps repository-relative paths to host files
     * under {@code stagingDir}; closing deletes the whole directory.
     */
    public record GitTreeSnapshot(
            Path stagingDir,
            String commitSha,
            String treeSha,
            Map<String, Path> files,
            long totalBytes,
            int visitedFiles,
            boolean complete,
            Set<String> limitations)
            implements AutoCloseable {
        public GitTreeSnapshot {
            Objects.requireNonNull(treeSha, "treeSha");
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
            limitations = Set.copyOf(limitations);
        }

        @Override
        public void close() {
            deleteTreeQuietly(stagingDir);
        }
    }

    public String generateUnifiedDiff(Long repositoryId, String baseRef, String headRef) {
        if (!properties.enabled()) {
            return "";
        }

        return lockManager.withReadLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);

            try (Git git = Git.open(repoPath.toFile())) {
                Repository repo = git.getRepository();

                ObjectId baseId = resolveRef(repo, baseRef);
                ObjectId headId = resolveRef(repo, headRef);

                if (baseId == null) {
                    log.warn("Cannot resolve base ref for diff: ref={}, repoId={}", baseRef, repositoryId);
                    return "";
                }
                if (headId == null) {
                    log.warn("Cannot resolve head ref for diff: ref={}, repoId={}", headRef, repositoryId);
                    return "";
                }

                try (RevWalk revWalk = new RevWalk(repo);
                        ObjectReader reader = repo.newObjectReader();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        DiffFormatter formatter = new DiffFormatter(out)) {
                    RevCommit baseCommit = revWalk.parseCommit(baseId);
                    RevCommit headCommit = revWalk.parseCommit(headId);

                    CanonicalTreeParser oldTree = new CanonicalTreeParser();
                    oldTree.reset(reader, baseCommit.getTree());

                    CanonicalTreeParser newTree = new CanonicalTreeParser();
                    newTree.reset(reader, headCommit.getTree());

                    formatter.setRepository(repo);
                    formatter.setDiffComparator(RawTextComparator.DEFAULT);
                    formatter.setDetectRenames(true);
                    formatter.format(oldTree, newTree);
                    formatter.flush();

                    return out.toString(StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new GitOperationException(
                        "Failed to generate unified diff: repoId=" + repositoryId
                                + ", base="
                                + baseRef
                                + ", head="
                                + headRef,
                        e);
            }
        });
    }

    /**
     * Resolve a ref or branch name to a commit SHA string.
     * Uses the same resolution strategy as diff generation (remote tracking → local → raw SHA).
     *
     * @param repositoryId the repository database ID
     * @param ref          branch name, tag, or SHA
     * @return the full 40-char SHA, or null if the ref cannot be resolved
     */
    @Nullable
    public String resolveRefToSha(Long repositoryId, String ref) {
        if (!properties.enabled()) {
            return null;
        }
        return lockManager.withReadLock(repositoryId, () -> {
            Path repoPath = getRepositoryPath(repositoryId);
            try (Git git = Git.open(repoPath.toFile())) {
                ObjectId id = resolveRef(git.getRepository(), ref);
                return id != null ? id.getName() : null;
            } catch (IOException e) {
                throw new GitOperationException("Failed to resolve ref: " + ref + ", repoId=" + repositoryId, e);
            }
        });
    }

    /**
     * Resolve a ref string to an ObjectId, trying remote tracking, local, and raw SHA.
     */
    @Nullable
    private ObjectId resolveRef(Repository repo, String ref) throws IOException {
        // Try refs/remotes/origin/<ref> first
        ObjectId id = repo.resolve("refs/remotes/origin/" + ref);
        if (id != null) return id;

        // Try refs/heads/<ref>
        id = repo.resolve("refs/heads/" + ref);
        if (id != null) return id;

        // Try raw SHA or other ref format
        return repo.resolve(ref);
    }

    /**
     * Sanitize URL for logging (remove credentials).
     *
     * <p>Redacts the ENTIRE userinfo segment ({@code //user:secret@host} → {@code //***@host}), not just
     * the {@code x-access-token:} shape: GitLab auto-registration commonly carries credentials as
     * {@code gitlab-ci-token:<token>@} or {@code oauth2:<token>@}, so a token-specific redaction would leak
     * those forms in clear text.
     */
    private @Nullable String sanitizeUrl(@Nullable String url) {
        if (url == null) return null;
        return url.replaceAll("//[^/@]+@", "//***@");
    }

    /**
     * Recursively delete a directory and all its contents.
     * Walks the tree depth-first (files before directories).
     */
    private void deleteRecursively(Path path) throws IOException {
        List<Path> deepestFirst;
        try (var stream = Files.walk(path)) {
            // Collected rather than deleted inside the stream so Files.delete can throw IOException
            // straight out, instead of being wrapped and unwrapped through UncheckedIOException.
            deepestFirst = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : deepestFirst) {
            Files.delete(p);
        }
    }

    /**
     * Commit information extracted from git.
     */
    public record CommitInfo(
            String sha,
            String message,
            @Nullable String messageBody,
            String authorName,
            String authorEmail,
            Instant authoredAt,
            String committerName,
            String committerEmail,
            Instant committedAt,
            int additions,
            int deletions,
            int changedFiles,
            List<FileChange> fileChanges,
            List<String> parentShas) {}

    /**
     * File change information.
     */
    public record FileChange(
            String filename,
            ChangeType changeType,
            int additions,
            int deletions,
            int changes,
            @Nullable String previousFilename) {}

    /**
     * Type of file change.
     */
    public enum ChangeType {
        ADDED,
        MODIFIED,
        REMOVED,
        RENAMED,
        COPIED,
        CHANGED,
        UNKNOWN,
    }

    /**
     * Exception for git operation failures.
     */
    public static class GitOperationException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        public GitOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
