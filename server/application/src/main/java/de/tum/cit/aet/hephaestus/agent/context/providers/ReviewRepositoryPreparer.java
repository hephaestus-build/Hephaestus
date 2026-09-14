package de.tum.cit.aet.hephaestus.agent.context.providers;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Authorizes repository identity before provider credentials cross into trusted Git preparation. */
@Component
@RequiredArgsConstructor
public class ReviewRepositoryPreparer {
    private final GitRepositoryManager git;
    private final GitDiffOperations diffs;
    private final PullRequestRepository pullRequests;
    private final RepositoryToMonitorRepository monitors;
    private final ConnectionService connections;
    private final List<ScmTokenSource> tokenSources;

    private record AuthorizedRepository(
            RepositoryKey key,
            String cloneUrl,
            ScmTokenSource source,
            int number,
            @Nullable String head,
            @Nullable String base) {}

    public RepositoryKey authorize(AgentJob job) {
        return authorizedRepository(job).key();
    }

    private AuthorizedRepository authorizedRepository(AgentJob job) {
        var metadata = job.getMetadata();
        if (metadata == null) throw new JobPreparationException("Review job has no metadata");
        long workspaceId = job.getWorkspace().getId();
        var pullRequest = pullRequests
                .findByIdWithAuthorAndRepository(requireLong(metadata, "pull_request_id"))
                .filter(pr -> pr.getDeletedAt() == null)
                .orElseThrow(() -> new JobPreparationException("Reviewed pull request is unavailable"));
        var repository = pullRequest.getRepository();
        if (repository == null) throw new JobPreparationException("Reviewed work has no repository");
        long repositoryId = requireLong(metadata, "repository_id");
        if (repository.getId() != repositoryId
                || !monitors.existsByWorkspaceIdAndNameWithOwner(workspaceId, repository.getNameWithOwner())) {
            throw new JobPreparationException("Reviewed repository is not monitored by this workspace");
        }
        var kind = connections
                .findActiveProviderKind(workspaceId)
                .orElseThrow(() -> new JobPreparationException("Workspace has no active SCM connection"));
        var source = tokenSources.stream()
                .filter(candidate -> candidate.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new JobPreparationException("SCM Git preparation is unavailable"));
        String serverUrl = source.serverUrl(workspaceId)
                .orElseThrow(() -> new JobPreparationException("SCM server is unavailable"));
        if (repository.getProvider().kind() != kind
                || !URI.create(serverUrl)
                        .equals(URI.create(repository.getProvider().getServerUrl()))) {
            throw new JobPreparationException("Repository and workspace SCM provider do not match");
        }
        String[] segments = repository.getNameWithOwner().split("/", -1);
        if (segments.length < 2
                || Arrays.stream(segments)
                        .anyMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."))) {
            throw new JobPreparationException("Repository has an invalid provider path");
        }
        segments[segments.length - 1] += ".git";
        String cloneUrl = UriComponentsBuilder.fromUriString(serverUrl)
                .pathSegment(segments)
                .build()
                .encode()
                .toUriString();
        return new AuthorizedRepository(
                new RepositoryKey(workspaceId, repositoryId),
                cloneUrl,
                source,
                pullRequest.getNumber(),
                pullRequest.getHeadRefOid(),
                pullRequest.getBaseRefOid());
    }

    /** A pinned review range: target is the recorded diff base or the resolved merge base. */
    public record PreparedReview(RepositoryKey key, String head, String target) {}

    public PreparedReview prepare(AgentJob job) {
        var authorized = authorizedRepository(job);
        var key = authorized.key();
        var source = authorized.source();
        String cloneUrl = authorized.cloneUrl();
        String token = source.accessToken(key.workspaceId())
                .orElseThrow(() -> new JobPreparationException("SCM credentials are unavailable"));
        var metadata = job.getMetadata();
        if (metadata == null) throw new JobPreparationException("Review job has no metadata");
        String head = requireText(metadata, "commit_sha");
        git.ensureRepository(key, cloneUrl, token);
        var reviewRef = source.reviewHeadRef(authorized.number());
        if (reviewRef.isPresent()) git.fetchRemoteCommit(key, cloneUrl, reviewRef.get(), head, token);
        if (!git.commitExists(key, head)) throw new JobPreparationException("Pinned review commit is unavailable");
        String recordedBase = null;
        if (source.recordsReviewDiffBase()) {
            if (!head.equals(authorized.head())) {
                throw new JobPreparationException("Recorded merge request revision does not match the queued head");
            }
            recordedBase = authorized.base();
        }
        String target = recordedBase != null ? recordedBase : MetaJson.optString(metadata, "base_ref_oid");
        if (target == null) target = git.resolveBranchHead(key, requireText(metadata, "target_branch"));
        if (target == null || !git.commitExists(key, target)) {
            throw new JobPreparationException("Review base commit is unavailable");
        }
        if (recordedBase == null) {
            String[] range = diffs.resolveDiffRange(key, target, head);
            if (range == null) throw new JobPreparationException("The pinned review diff range is unavailable");
            target = range[0];
        }
        return new PreparedReview(key, head, target);
    }
}
