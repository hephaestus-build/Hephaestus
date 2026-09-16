package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryDiff;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Verifies quotes from the repository against the checkout the review saw: its {@code .git} holds
 * exactly the commits the review could reach, so a commit that is not in it was never captured. A
 * quote from the change is a quote from one of its two revisions, at a path the change touches.
 */
@Component
public class HistoricalGitEvidence {
    private final JobEvidenceFiles files;

    public HistoricalGitEvidence(JobEvidenceFiles files) {
        this.files = files;
    }

    public record Citation(String revision, String path, String quote, int startLine, int endLine) {}

    /** The attempt's checkout, digest-checked and pinned to {@code pinnedHead}. */
    private Repository open(AgentJob job, String headDigest, String refsDigest, String pinnedHead) throws IOException {
        Path checkout = files.repositoryForVerification(job, headDigest, refsDigest);
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(checkout.resolve(Constants.DOT_GIT).toFile())
                .setMustExist(true)
                .build();
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null || !head.getName().equals(pinnedHead)) {
            repository.close();
            throw new IllegalStateException("Captured repository does not match the pinned head");
        }
        return repository;
    }

    /**
     * Every path the reviewed change touches, read from the checkout's own history. A citation of the
     * change names one of these; the container's diff view is derived from the same objects.
     */
    public Set<String> changedPaths(
            AgentJob job, String headDigest, String refsDigest, String baseSha, String headSha) {
        try (Repository repository = open(job, headDigest, refsDigest, headSha)) {
            return RepositoryDiff.changedPaths(repository, ObjectId.fromString(baseSha), ObjectId.fromString(headSha));
        } catch (IOException exception) {
            throw new IllegalStateException("The reviewed change could not be read", exception);
        }
    }

    public Map<Citation, JobEvidenceFiles.QuoteMatch> verifyAll(
            AgentJob job, String headDigest, String refsDigest, String pinnedHead, List<Citation> submitted) {
        if (submitted.isEmpty()) return Map.of();
        Map<Citation, JobEvidenceFiles.QuoteMatch> verified = new LinkedHashMap<>();
        try (Repository repository = open(job, headDigest, refsDigest, pinnedHead);
                RevWalk walk = new RevWalk(repository)) {
            Path blob = Files.createTempFile("hephaestus-cited-blob-", "");
            try {
                for (Citation citation : submitted.stream().distinct().toList()) {
                    ObjectId object = capturedBlob(repository, walk, citation.revision(), citation.path());
                    if (object == null) {
                        verified.put(citation, JobEvidenceFiles.QuoteMatch.absent());
                        continue;
                    }
                    try (OutputStream out = Files.newOutputStream(blob)) {
                        repository.open(object, Constants.OBJ_BLOB).copyTo(out);
                    }
                    verified.put(
                            citation,
                            JobEvidenceFiles.verifyUtf8AtLines(
                                    blob, citation.quote(), citation.startLine(), citation.endLine()));
                }
            } finally {
                Files.deleteIfExists(blob);
            }
            return Map.copyOf(verified);
        } catch (IOException exception) {
            // The verifier, not the submission, failed: a 5xx the runner repeats, never a refusal.
            throw new IllegalStateException("Repository citations could not be verified", exception);
        }
    }

    /** The regular file at {@code path} in {@code revision}, or null when the checkout holds no such thing. */
    private static @Nullable ObjectId capturedBlob(Repository repository, RevWalk walk, String revision, String path)
            throws IOException {
        if (path.isEmpty()
                || path.startsWith("/")
                || path.endsWith("/")
                || !ObjectId.isId(revision)
                || !repository.getObjectDatabase().has(ObjectId.fromString(revision))) {
            return null;
        }
        RevCommit commit = walk.parseCommit(ObjectId.fromString(revision));
        try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
            if (tree == null) return null;
            FileMode mode = tree.getFileMode(0);
            boolean regular = FileMode.REGULAR_FILE.equals(mode)
                    || FileMode.EXECUTABLE_FILE.equals(mode)
                    || FileMode.SYMLINK.equals(mode);
            return regular ? tree.getObjectId(0) : null;
        }
    }
}
