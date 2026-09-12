package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Materialises a pinned worktree and sanitized Git history without exposing the host clone or its
 * credentials. Excluded worktree entries make the reported capture partial.
 */
@Component
@Order(1_000)
@RequiredArgsConstructor
public class RepositoryTreeContentSource implements EvidenceSource {

    private static final SourceKind KIND = new SourceKind("scm.repository.tree");

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    private final GitRepositoryManager gitRepositoryManager;
    private final ReviewRepositoryPreparer repositoryPreparer;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.PracticeReviewRequest;
    }

    @Override
    public boolean ownsPath(String path) {
        return path.startsWith(SandboxLayout.REPO_MOUNT_RELATIVE);
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        // Staged by reference, not byte[] — a caller here would have to read the whole tree into heap.
        throw new UnsupportedOperationException("Repository-tree capture stages files from disk; use capture()");
    }

    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        if (!selectedKinds.contains(KIND)) {
            return new EvidenceContribution(Map.of(), Map.of());
        }
        if (gitRepositoryManager.isEnabled() && request instanceof ContextRequest.PracticeReviewRequest review)
            review.preparation().prepare(repositoryPreparer, review.job());
        SourceCaptureState absence = absenceOrNull(request);
        if (absence != null) {
            return absent(absence);
        }
        GitRepositoryManager.GitTreeSnapshot snapshot = snapshot(request);
        Map<String, java.nio.file.Path> onDisk = Map.of(
                SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD",
                        snapshot.stagingDir().resolve(".git/HEAD"),
                SandboxLayout.REPO_MOUNT_RELATIVE + ".git/hephaestus-captured-refs",
                        snapshot.stagingDir().resolve(".git/hephaestus-captured-refs"));
        // Excluded entries must not license an absence claim about the complete repository.
        SourceCompleteness completeness =
                snapshot.complete() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL;
        return new EvidenceContribution(
                Map.of(),
                Map.of(KIND, completeness),
                Map.of(KIND, snapshot.commitSha() + ":" + snapshot.treeSha()),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                onDisk,
                snapshot,
                snapshot.limitations().isEmpty() ? Map.of() : Map.of(KIND, List.copyOf(snapshot.limitations())),
                List.of(new EvidenceDirectory(SandboxLayout.REPO_MOUNT_RELATIVE, snapshot.stagingDir())));
    }

    private static EvidenceContribution absent(SourceCaptureState state) {
        return new EvidenceContribution(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(KIND, state));
    }

    @Nullable
    private SourceCaptureState absenceOrNull(ContextRequest request) {
        if (!gitRepositoryManager.isEnabled()) {
            return new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED);
        }
        if (!(request instanceof ContextRequest.PracticeReviewRequest review)) {
            return null;
        }
        JsonNode metadata = review.job().getMetadata();
        if (metadata == null || !metadata.path("repository_id").isNumber()) {
            return null;
        }
        if (gitRepositoryManager.isRepositoryCloned(new RepositoryKey(
                review.job().getWorkspace().getId(),
                metadata.path("repository_id").asLong()))) {
            return null;
        }
        return new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_WORKING_COPY);
    }

    private GitRepositoryManager.GitTreeSnapshot snapshot(ContextRequest request) {
        if (!(request instanceof ContextRequest.PracticeReviewRequest review)) {
            throw new IllegalStateException("Repository-tree capture requires a pull request review");
        }
        JsonNode metadata = review.job().getMetadata();
        if (metadata == null || !metadata.path("repository_id").isNumber()) {
            throw new JobPreparationException("Pull request job has no repository_id: jobId="
                    + review.job().getId());
        }
        String commitSha = metadata.path("commit_sha").asString();
        if (commitSha == null || commitSha.isBlank()) {
            throw new JobPreparationException(
                    "Pull request job has no commit_sha: jobId=" + review.job().getId());
        }
        long repositoryId = metadata.path("repository_id").asLong();
        if (!gitRepositoryManager.isRepositoryCloned(
                new RepositoryKey(review.job().getWorkspace().getId(), repositoryId))) {
            throw new JobPreparationException("Repository not cloned: repoId=" + repositoryId + ", jobId="
                    + review.job().getId());
        }
        try {
            return gitRepositoryManager.readTreeSnapshot(
                    new RepositoryKey(review.job().getWorkspace().getId(), repositoryId), commitSha);
        } catch (GitRepositoryManager.GitOperationException e) {
            throw new EvidenceCollectionException("Could not capture repository tree at " + commitSha, e);
        }
    }
}
