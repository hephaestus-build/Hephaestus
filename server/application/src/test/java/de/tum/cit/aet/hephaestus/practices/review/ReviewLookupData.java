package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@TestComponent
@RequiredArgsConstructor
public class ReviewLookupData {
    private final ConnectionRepository connections;
    private final IdentityProviderRepository providers;
    private final RepositoryRepository repositories;
    private final RepositoryToMonitorRepository monitors;
    private final IssueRepository issues;
    private final ItemIndexService index;

    @Transactional
    public ItemRef work(Workspace workspace, IdentityProviderType type, boolean pullRequest) {
        var mirrored = mirror(workspace, type, pullRequest);
        var parent = index.upsert(new ItemRegistration(
                workspace.getId(),
                ScmItemEvents.REPOSITORY,
                mirrored.repository().getId(),
                null,
                null));
        return index.upsert(new ItemRegistration(
                workspace.getId(),
                pullRequest ? ScmItemEvents.PULL_REQUEST : ScmItemEvents.ISSUE,
                mirrored.workId(),
                parent.itemId(),
                null));
    }

    /** Monitored, mirrored work that no event has indexed yet, as a sync leaves work another workspace mirrored. */
    @Transactional
    public Mirrored mirror(Workspace workspace, IdentityProviderType type, boolean pullRequest) {
        String server = type == IdentityProviderType.GITHUB ? "https://github.com" : "https://gitlab.example.test";
        var kind = IntegrationKind.valueOf(type.name());
        ConnectionConfig config = type == IdentityProviderType.GITHUB
                ? new ConnectionConfig.GitHubPatConfig(null, server, Set.of())
                : new ConnectionConfig.GitLabConfig(server, null, null, Set.of(), null);
        var connection = new Connection(workspace, kind, server, config);
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var provider = providers
                .findByTypeAndServerUrl(type, server)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(type, server)));
        var repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setName("project");
        repository.setNameWithOwner(workspace.getWorkspaceSlug() + "/project");
        repository.setHtmlUrl(server + "/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository = repositories.saveAndFlush(repository);
        var monitor = monitors.saveAndFlush(
                WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner()));
        Issue work;
        if (pullRequest) {
            var pull = new PullRequest();
            pull.setBaseRefOid("a".repeat(40));
            pull.setHeadRefOid("b".repeat(40));
            pull.setBaseRefName("main");
            pull.setHeadRefName("topic");
            work = pull;
        } else work = new Issue();
        work.setProvider(provider);
        work.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        work.setRepository(repository);
        work.setNumber(5);
        work.setTitle("Reviewed work");
        work.setBody("Description");
        work.setState(Issue.State.OPEN);
        work.setCreatedAt(Instant.now());
        work.setUpdatedAt(Instant.now());
        String route = type == IdentityProviderType.GITHUB
                ? (pullRequest ? "/pull/" : "/issues/")
                : (pullRequest ? "/-/merge_requests/" : "/-/issues/");
        String url = repository.getHtmlUrl() + route + work.getNumber();
        work.setHtmlUrl(url);
        work = issues.saveAndFlush(work);
        return new Mirrored(monitor.getId(), repository, work.getId(), url);
    }

    public record Mirrored(long monitorId, Repository repository, long workId, String url) {}
}
