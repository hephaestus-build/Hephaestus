package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitCloneUrl;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Authorizes the exact provider repository before Git credentials cross its boundary. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewRepositoryPreparer {
    private final GitRepositoryManager git;
    private final PullRequestRepository pullRequests;
    private final ConnectionService connections;
    private final List<ScmTokenSource> tokenSources;
    private final RepositoryRepository repositories;

    /** The reviewed repository of pinned work, authorized for the workspace: what {@link #fetch} syncs. */
    public record Remote(RepositoryKey key, String cloneUrl, ScmTokenSource source, ScmCapturedWork work) {}

    public record PreparedReview(RepositoryKey key, String head, String target) {}

    @Transactional(readOnly = true)
    public RepositoryKey authorize(ScmCaptureContext capture) {
        return authorized(capture.pinned(), capture.work()).key();
    }

    @Transactional(readOnly = true)
    public Remote remote(ItemSnapshot pinned, ScmCapturedWork work) {
        return authorized(pinned, work);
    }

    @Transactional(readOnly = true)
    public List<Repository> monitoredRepositories(long workspaceId) {
        return repositories.findAllByWorkspaceMonitors(workspaceId);
    }

    @Transactional(readOnly = true)
    public List<Repository> permittedRepositories(long workspaceId) {
        var source = activeTokenSource(workspaceId).orElse(null);
        if (source == null) return List.of();
        var kind = source.kind();
        var origin = source.serverUrl(workspaceId).flatMap(ScmOrigin::of);
        if (origin.isEmpty()) return List.of();
        return repositories.findAllByWorkspaceMonitors(workspaceId).stream()
                .filter(repository -> repository.getProvider().kind() == kind
                        && ScmOrigin.of(repository.getProvider().getServerUrl()).equals(origin))
                .toList();
    }

    /** The credential source of the workspace's active SCM connection. */
    Optional<ScmTokenSource> activeTokenSource(long workspaceId) {
        return connections
                .findActiveProviderKind(workspaceId)
                .flatMap(kind -> tokenSources.stream()
                        .filter(candidate -> candidate.kind() == kind)
                        .findFirst());
    }

    private Remote authorized(ItemSnapshot pinned, ScmCapturedWork work) {
        long workspaceId = pinned.item().workspaceId();
        var pullRequest = pullRequests
                .findByIdWithAuthorAndRepository(work.sourceId())
                .filter(pr -> pr.getDeletedAt() == null)
                .orElseThrow(() ->
                        new ItemCaptureException(ItemCaptureCode.ITEM_REMOVED, "Reviewed pull request is unavailable"));
        var repository = pullRequest.getRepository();
        if (repository == null
                || repository.getId() != work.repositoryId()
                || !repository.getNameWithOwner().equals(work.repositoryPath())
                || !repositories.isMonitored(workspaceId, repository.getId()))
            throw new ItemCaptureException(
                    ItemCaptureCode.ITEM_NOT_VISIBLE, "Workspace does not monitor the pinned repository");
        var integration = pinned.integration();
        var connection = connections
                .findActive(workspaceId, integration.kind())
                .orElseThrow(() ->
                        new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "SCM connection is unavailable"));
        if (integration.workspaceId() != workspaceId
                || (integration.connectionId() != null
                        && !integration.connectionId().equals(connection.getId())))
            throw new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "SCM connection changed");
        var source = tokenSources.stream()
                .filter(candidate -> candidate.kind() == integration.kind())
                .findFirst()
                .orElseThrow(() -> new ItemCaptureException(
                        ItemCaptureCode.SOURCE_NOT_READY, "SCM credential source is unavailable"));
        String serverUrl = source.serverUrl(workspaceId)
                .orElseThrow(
                        () -> new ItemCaptureException(ItemCaptureCode.SOURCE_NOT_READY, "SCM server is unavailable"));
        if (repository.getProvider().kind() != integration.kind()
                || !ScmOrigin.of(serverUrl)
                        .equals(ScmOrigin.of(repository.getProvider().getServerUrl())))
            throw new ItemCaptureException(
                    ItemCaptureCode.ITEM_NOT_VISIBLE, "Repository belongs to another provider instance");
        String cloneUrl = GitCloneUrl.of(serverUrl, work.repositoryPath())
                .orElseThrow(() -> new ItemCaptureException(
                        ItemCaptureCode.CAPTURE_FAILED, "Repository has an invalid provider path"));
        return new Remote(new RepositoryKey(workspaceId, work.repositoryId()), cloneUrl, source, work);
    }

    /**
     * Mints the credential and fetches the mirror and, for a head that no branch reaches, the provider's review ref.
     * Neither call has a time bound, so a transaction that waited on them would hold its connection and row locks for as
     * long as the provider takes.
     */
    public void fetch(Remote remote) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("A reviewed repository fetches only outside a database transaction");
        var range = range(remote.work());
        var key = remote.key();
        String token = remote.source()
                .accessToken(key.workspaceId())
                .orElseThrow(() ->
                        new ItemCaptureException(ItemCaptureCode.SOURCE_NOT_READY, "SCM credentials are unavailable"));
        git.ensureRepository(key, remote.cloneUrl(), token);
        if (!git.commitExists(key, range.head()))
            remote.source()
                    .reviewHeadRef(remote.work().number())
                    .ifPresent(ref -> git.fetchRemoteCommit(key, remote.cloneUrl(), ref, range.head(), token));
    }

    /**
     * The pinned range in the mirror that {@link #fetch} synced; it reads only the database and the mirror. A mirror that
     * is not on this server, as after an eviction, does not hold the range.
     */
    public PreparedReview prepare(ScmCaptureContext capture) {
        var key = authorized(capture.pinned(), capture.work()).key();
        var range = range(capture.work());
        if (!git.isRepositoryCloned(key)
                || !git.commitExists(key, range.head())
                || !git.commitExists(key, range.target()))
            throw new ItemCaptureException(
                    ItemCaptureCode.VERSION_UNAVAILABLE, "Pinned review diff range is unavailable");
        return new PreparedReview(key, range.head(), range.target());
    }

    private record Range(String head, String target) {}

    private static Range range(ScmCapturedWork work) {
        String head = Objects.requireNonNull(work.head(), "Pinned SCM work has no head");
        String base = Objects.requireNonNull(work.base(), "Pinned SCM work has no base");
        if (!head.matches("[0-9a-f]{40}|[0-9a-f]{64}") || !base.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
            throw new ItemCaptureException(
                    ItemCaptureCode.VERSION_UNAVAILABLE, "Pinned SCM version has invalid Git object ids");
        return new Range(head, base);
    }
}
