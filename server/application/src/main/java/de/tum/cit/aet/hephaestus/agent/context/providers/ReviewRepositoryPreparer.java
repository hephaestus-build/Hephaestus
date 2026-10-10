package de.tum.cit.aet.hephaestus.agent.context.providers;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmReviewRangeSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/** Authorizes repository identity before provider credentials cross into trusted Git preparation. */
@Component
@RequiredArgsConstructor
public class ReviewRepositoryPreparer {
    private final GitRepositoryManager git;
    private final PullRequestRepository pullRequests;
    private final RepositoryToMonitorRepository monitors;
    private final ConnectionService connections;
    private final List<ScmTokenSource> tokenSources;
    private final RepositoryRepository repositories;
    private final List<ScmReviewRangeSource> rangeSources;
    private final PlatformTransactionManager transactionManager;

    private record AuthorizedRepository(
            RepositoryKey key,
            String cloneUrl,
            ScmTokenSource source,
            int number,
            String repositoryPath,
            ReviewIdentity identity,
            @Nullable String head,
            @Nullable String base) {}

    private record ReviewIdentity(
            long pullRequestId,
            long repositoryNativeId,
            long pullRequestNativeId,
            @Nullable Instant updatedAt) {}

    public RepositoryKey authorize(AgentJob job) {
        return authorizedRepository(job).key();
    }

    public List<Repository> monitoredRepositories(long workspaceId) {
        return repositories.findAllByWorkspaceMonitors(workspaceId);
    }

    /** Uses the same active connection and provider-origin gate as pinned reviewed-work preparation. */
    public List<Repository> permittedRepositories(long workspaceId) {
        var kind = connections.findActiveProviderKind(workspaceId).orElse(null);
        if (kind == null) return List.of();
        var source = tokenSources.stream()
                .filter(candidate -> candidate.kind() == kind)
                .findFirst()
                .orElse(null);
        if (source == null) return List.of();
        var serverUrl = source.serverUrl(workspaceId).orElse(null);
        if (serverUrl == null) return List.of();
        return monitoredRepositories(workspaceId).stream()
                .filter(repository -> matchesOrigin(repository, kind, serverUrl))
                .toList();
    }

    private static boolean matchesOrigin(Repository repository, IntegrationKind kind, String serverUrl) {
        return repository.getProvider().kind() == kind
                && URI.create(serverUrl)
                        .equals(URI.create(repository.getProvider().getServerUrl()));
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
            throw new JobPreparationException("This workspace does not monitor the reviewed repository.");
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
        if (!matchesOrigin(repository, kind, serverUrl)) {
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
                repository.getNameWithOwner(),
                new ReviewIdentity(
                        pullRequest.getId(),
                        repository.getNativeId(),
                        pullRequest.getNativeId(),
                        pullRequest.getUpdatedAt()),
                pullRequest.getHeadRefOid(),
                pullRequest.getBaseRefOid());
    }

    private long activeConnectionId(AgentJob job, IntegrationKind kind) {
        return connections
                .findActive(job.getWorkspace().getId(), kind)
                .map(connection -> connection.getId())
                .orElseThrow(() -> new JobPreparationException("Workspace has no active SCM connection"));
    }

    private AuthorizedRepository hydrateReviewRange(AgentJob job, AuthorizedRepository original, String head) {
        var rangeSource = rangeSources.stream()
                .filter(candidate -> candidate.kind() == original.source().kind())
                .findFirst()
                .orElseThrow(() -> new JobPreparationException("SCM review range preparation is unavailable"));
        long connectionId = activeConnectionId(job, original.source().kind());
        var read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var range = Objects.requireNonNull(read.execute(status ->
                        rangeSource.read(original.key().workspaceId(), original.repositoryPath(), original.number())))
                .orElseThrow(() ->
                        new ReviewSourceNotReadyException("GitLab has not prepared the merge request diff range"));
        if (range.repositoryNativeId() != original.identity().repositoryNativeId()
                || range.pullRequestNativeId() != original.identity().pullRequestNativeId()
                || !head.equals(range.head())
                || range.base().isBlank()) {
            throw new JobPreparationException("Provider review range does not match the queued merge request");
        }
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Objects.requireNonNull(transaction.execute(status -> {
            // Lock before re-reading: JPA must not hand back a snapshot loaded before a concurrent hook committed.
            var locked = pullRequests
                    .findForUpdateByRepositoryIdAndNumber(original.key().repositoryId(), original.number())
                    .orElseThrow(() -> new JobPreparationException("Reviewed pull request is unavailable"));
            var current = authorizedRepository(job);
            if (original.identity().pullRequestId() != current.identity().pullRequestId()
                    || original.identity().repositoryNativeId()
                            != current.identity().repositoryNativeId()
                    || original.identity().pullRequestNativeId()
                            != current.identity().pullRequestNativeId()
                    || !Objects.equals(head, current.head())
                    || !original.key().equals(current.key())
                    || !original.cloneUrl().equals(current.cloneUrl())
                    || original.source().kind() != current.source().kind()
                    || connectionId != activeConnectionId(job, current.source().kind())) {
                throw new JobPreparationException("Review source no longer matches the queued merge request");
            }
            if (!Objects.equals(
                    original.identity().updatedAt(), current.identity().updatedAt())) {
                throw new ReviewSourceNotReadyException(
                        "Review source changed while its diff range was being prepared");
            }
            if (locked.getBaseRefOid() == null || locked.getBaseRefOid().isBlank()) {
                locked.setBaseRefOid(range.base());
                pullRequests.save(locked);
            }
            // Return the locked snapshot, not the suspended capture transaction's older JPA entity.
            return authorizedRepository(job);
        }));
    }

    /**
     * The diff base retained with the queued head, or null. Only a JSON boolean {@code true} and a nonblank textual base
     * retain a range; older jobs remain unknown. This records original identity, not provider provenance. The push
     * coalescer decides replacement before capture.
     */
    private static @Nullable String admittedDiffBase(JsonNode metadata) {
        JsonNode retained = metadata.get(PullRequestReviewHandler.RETAINED_RANGE_METADATA_KEY);
        if (retained == null || !retained.isBoolean() || !retained.asBoolean()) return null;
        return MetaJson.optString(metadata, "base_ref_oid");
    }

    /** A pinned review range: target is the recorded diff base or the resolved merge base. */
    public record PreparedReview(RepositoryKey key, String head, String target) {}

    public PreparedReview prepare(AgentJob job) {
        var authorized = authorizedRepository(job);
        var metadata = job.getMetadata();
        if (metadata == null) throw new JobPreparationException("Review job has no metadata");
        String head = requireText(metadata, "commit_sha");
        String recordedBase = null;
        if (authorized.source().recordsReviewDiffBase()) {
            // An admitted pair is the range this occasion was requested for. The mirror and the provider may since hold
            // another pair, for a newer head or for the same head after the target moved, so neither stands in for it.
            recordedBase = admittedDiffBase(metadata);
            if (recordedBase == null) {
                if (!head.equals(authorized.head())) {
                    throw new JobPreparationException("Recorded merge request revision does not match the queued head");
                }
                recordedBase = authorized.base();
                if (recordedBase == null || recordedBase.isBlank()) {
                    authorized = hydrateReviewRange(job, authorized, head);
                    if (!head.equals(authorized.head())) {
                        throw new JobPreparationException(
                                "Recorded merge request revision does not match the queued head");
                    }
                    recordedBase = authorized.base();
                    if (recordedBase == null || recordedBase.isBlank()) {
                        throw new ReviewSourceNotReadyException("Recorded merge request base commit is not ready");
                    }
                }
            }
        }
        var key = authorized.key();
        var source = authorized.source();
        String cloneUrl = authorized.cloneUrl();
        String token = source.accessToken(key.workspaceId())
                .orElseThrow(() -> new JobPreparationException("SCM credentials are unavailable"));
        git.ensureRepository(key, cloneUrl, token);
        // The branch fetch usually carries the head already; the provider's review ref is for one it
        // does not reach, such as a fork's or a force-pushed branch's.
        if (!git.commitExists(key, head)) {
            var reviewRef = source.reviewHeadRef(authorized.number());
            if (reviewRef.isPresent()) git.fetchRemoteCommit(key, cloneUrl, reviewRef.get(), head, token);
            if (!git.commitExists(key, head)) throw new JobPreparationException("Pinned review commit is unavailable");
        }
        String target = recordedBase != null ? recordedBase : MetaJson.optString(metadata, "base_ref_oid");
        if (target == null) target = git.resolveBranchHead(key, requireText(metadata, "target_branch"));
        if (target == null || !git.commitExists(key, target)) {
            throw new JobPreparationException("Review base commit is unavailable");
        }
        if (recordedBase == null) {
            String base = git.reviewBase(key, target, head);
            if (base == null) throw new JobPreparationException("The pinned review diff range is unavailable");
            target = base;
        }
        return new PreparedReview(key, head, target);
    }
}
