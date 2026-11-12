package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Materialises a pinned worktree and sanitized Git history without exposing the host clone or its
 * credentials. Excluded worktree entries make the reported capture partial.
 */
@Component
@ConditionalOnServerRole
@Order(1_000)
@RequiredArgsConstructor
public class RepositoryTreeContentSource {

    public static final SourceKind KIND = new SourceKind("scm.repository.tree");

    private final GitRepositoryManager gitRepositoryManager;
    private final ReviewRepositoryPreparer repositoryPreparer;
    private final PersonDataCopyRecorder personCopies;

    public CaptureContribution capture(ScmCaptureContext request, Set<SourceKind> selectedKinds) {
        if (!selectedKinds.contains(KIND)) {
            return CaptureContribution.empty();
        }
        if (gitRepositoryManager.isEnabled() && request.pullRequest())
            request.preparation().prepare(repositoryPreparer, request);
        var unavailable = request.preparation().unavailableReason();
        if (unavailable != null) return CaptureContribution.unavailable(Set.of(KIND), unavailable);
        SourceCaptureState absence = absenceOrNull(request);
        if (absence != null) {
            return absent(absence);
        }
        if (request.pullRequest()) {
            var metadata = Objects.requireNonNull(request.work());
            personCopies.recordRepository(metadata.repositoryId());
        }
        GitRepositoryManager.GitTreeSnapshot snapshot = snapshot(request);
        Map<String, Path> onDisk = Map.of(
                "repos/reviewed/.git/HEAD", snapshot.stagingDir().resolve(".git/HEAD"),
                "repos/reviewed/.git/hephaestus-captured-refs",
                        snapshot.stagingDir().resolve(".git/hephaestus-captured-refs"));
        // Excluded entries must not license an absence claim about the complete repository.
        SourceCompleteness completeness =
                snapshot.complete() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL;
        List<CitableText> citable;
        String identity;
        try {
            identity = GitRepositoryManager.treeIdentity(snapshot);
            citable = ScmCitableTexts.tree(snapshot.stagingDir(), snapshot.commitSha());
        } catch (RuntimeException failure) {
            snapshot.close();
            throw failure;
        }
        return new CaptureContribution(
                Map.of(),
                Map.of(KIND, completeness),
                Map.of(KIND, identity),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                onDisk,
                snapshot,
                snapshot.limitations().isEmpty() ? Map.of() : Map.of(KIND, List.copyOf(snapshot.limitations())),
                List.of(new EvidenceDirectory(
                        "repos/reviewed/",
                        snapshot.stagingDir(),
                        new CheckoutKey(
                                request.work().repositoryId(),
                                snapshot.commitSha(),
                                snapshot.treeSha(),
                                request.work().number(),
                                null))),
                List.of(),
                citable);
    }

    private static CaptureContribution absent(SourceCaptureState state) {
        return new CaptureContribution(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(KIND, state),
                Map.of(),
                null,
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }

    @Nullable
    private SourceCaptureState absenceOrNull(ScmCaptureContext request) {
        if (!gitRepositoryManager.isEnabled()) {
            return new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED);
        }
        if (!request.pullRequest()) {
            return null;
        }
        ScmCapturedWork metadata = request.work();
        if (metadata.repositoryId() <= 0) {
            return null;
        }
        if (gitRepositoryManager.isRepositoryCloned(
                new RepositoryKey(request.workspaceId(), metadata.repositoryId()))) {
            return null;
        }
        return new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_WORKING_COPY);
    }

    private GitRepositoryManager.GitTreeSnapshot snapshot(ScmCaptureContext request) {
        if (!request.pullRequest()) {
            throw new IllegalStateException("Repository-tree capture requires a pull request review");
        }
        ScmCapturedWork metadata = request.work();
        if (metadata.repositoryId() <= 0) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Pull request job has no repository_id: jobId=" + request.jobId());
        }
        String commitSha = metadata.head();
        if (commitSha == null || commitSha.isBlank()) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Pull request job has no commit_sha: jobId=" + request.jobId());
        }
        long repositoryId = metadata.repositoryId();
        if (!gitRepositoryManager.isRepositoryCloned(new RepositoryKey(request.workspaceId(), repositoryId))) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED,
                    "Repository not cloned: repoId=" + repositoryId + ", jobId=" + request.jobId());
        }
        try {
            return gitRepositoryManager.readTreeSnapshot(
                    new RepositoryKey(request.workspaceId(), repositoryId), commitSha);
        } catch (GitRepositoryManager.GitOperationException e) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Could not capture repository tree at " + commitSha, e);
        }
    }
}
