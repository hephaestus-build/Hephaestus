package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * Issues and pull requests that an earlier version filed under a repository row that a renamed repository left
 * behind. Work that no workspace registered moves to the repository that GitHub reports. Work that a workspace
 * registered, as on the deployment where this happened, keeps its row and is skipped, because an item's parent
 * never changes.
 */
class GitHubMisfiledWorkIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubPullRequestProcessor pullRequests;

    @Autowired
    private GitHubIssueProcessor issueProcessor;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private ItemIndexService items;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ObjectMapper mapper;

    private Workspace workspace;
    private Repository renamed;
    private Repository current;

    @BeforeEach
    void setUp() {
        String owner = "misfiled-" + UUID.randomUUID().toString().substring(0, 8);
        workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(
                        ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), owner),
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        renamed = repository(owner + "/source-archive");
        current = repository(owner + "/source");
    }

    @Test
    void shouldSkipAMisfiledPullRequestThatAWorkspaceAlreadyRegistered() {
        long nativeId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        double skipped = count("pull_request", "skipped");
        var misfiled = Objects.requireNonNull(
                pullRequests.process(pullRequest(nativeId, 40, "2026-10-01T09:00:00Z"), sync(renamed)));
        assertThat(parentOf(ScmItemEvents.PULL_REQUEST, misfiled.getId())).isEqualTo(itemOf(renamed));

        assertThat(pullRequests.process(pullRequest(nativeId, 40, "2026-10-02T09:00:00Z"), sync(current)))
                .isNull();

        assertThat(repositoryOf(misfiled.getId())).isEqualTo(renamed.getId());
        assertThat(parentOf(ScmItemEvents.PULL_REQUEST, misfiled.getId())).isEqualTo(itemOf(renamed));
        assertThat(count("pull_request", "skipped")).isEqualTo(skipped + 1);
    }

    @Test
    void shouldSkipAMisfiledIssueThatAWorkspaceAlreadyRegistered() {
        long nativeId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        var misfiled = Objects.requireNonNull(
                issueProcessor.process(issue(nativeId, 41, "2026-10-01T09:00:00Z"), sync(renamed)));
        assertThat(parentOf(ScmItemEvents.ISSUE, misfiled.getId())).isEqualTo(itemOf(renamed));

        assertThat(issueProcessor.process(issue(nativeId, 41, "2026-10-02T09:00:00Z"), sync(current)))
                .isNull();

        assertThat(repositoryOf(misfiled.getId())).isEqualTo(renamed.getId());
        assertThat(parentOf(ScmItemEvents.ISSUE, misfiled.getId())).isEqualTo(itemOf(renamed));
    }

    @Test
    void shouldMoveAMisfiledIssueThatNoWorkspaceRegistered() {
        Repository unmonitored = repository(renamed.getNameWithOwner() + "-old", false);
        long nativeId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        double moved = count("issue", "moved");
        var misfiled = Objects.requireNonNull(
                issueProcessor.process(issue(nativeId, 43, "2026-10-01T09:00:00Z"), sync(unmonitored)));
        assertThat(parentOf(ScmItemEvents.ISSUE, misfiled.getId())).isNull();

        var synced = issueProcessor.process(issue(nativeId, 43, "2026-10-02T09:00:00Z"), sync(current));

        assertThat(synced).isNotNull();
        assertThat(synced.getId()).isEqualTo(misfiled.getId());
        assertThat(repositoryOf(misfiled.getId())).isEqualTo(current.getId());
        assertThat(count("issue", "moved")).isEqualTo(moved + 1);
    }

    @Test
    void shouldSkipAndCountAMisfiledIssueWhoseRepositoryAlreadyHasTheNumber() {
        long nativeId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        Repository unmonitored = repository(renamed.getNameWithOwner() + "-older", false);
        double skipped = count("issue", "skipped");
        var misfiled = Objects.requireNonNull(
                issueProcessor.process(issue(nativeId, 42, "2026-10-01T09:00:00Z"), sync(unmonitored)));
        var other = Objects.requireNonNull(issueProcessor.process(
                issue(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 42, "2026-10-01T09:00:00Z"),
                sync(current)));

        assertThat(issueProcessor.process(issue(nativeId, 42, "2026-10-02T09:00:00Z"), sync(current)))
                .isNull();

        assertThat(repositoryOf(misfiled.getId())).isEqualTo(unmonitored.getId());
        assertThat(issues.findById(other.getId()).orElseThrow().getNativeId()).isNotEqualTo(nativeId);
        assertThat(count("issue", "skipped")).isEqualTo(skipped + 1);
    }

    private Repository repository(String nameWithOwner) {
        return repository(nameWithOwner, true);
    }

    private Repository repository(String nameWithOwner, boolean monitored) {
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow());
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://github.com/" + nameWithOwner);
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(Instant.now());
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository = repositories.saveAndFlush(repository);
        if (monitored) monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, nameWithOwner));
        return repository;
    }

    private ProcessingContext sync(Repository repository) {
        return ProcessingContext.forSync(workspace.getId(), repository);
    }

    private long itemOf(Repository repository) {
        return items.upsert(new ItemRegistration(
                        workspace.getId(), ScmItemEvents.REPOSITORY, repository.getId(), null, null))
                .itemId();
    }

    private @Nullable Long parentOf(ItemKind kind, long sourceId) {
        return items.findSource(workspace.getId(), kind.value(), sourceId)
                .map(ItemRef::parentId)
                .orElse(null);
    }

    private long repositoryOf(long issueId) {
        return Objects.requireNonNull(issues.findById(issueId).orElseThrow().getRepository())
                .getId();
    }

    private double count(String type, String outcome) {
        var counter = meters.find("github.misfiled.work")
                .tag("type", type)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private GitHubPullRequestDTO pullRequest(long id, int number, String updatedAt) {
        return mapper.readValue(
                """
                {"id": %d, "number": %d, "title": "Moved work", "body": "", "state": "open",
                 "html_url": "https://github.com/%s/pull/%d", "created_at": "2026-10-01T09:00:00Z",
                 "updated_at": "%s", "draft": false, "merged": false, "locked": false, "additions": 0,
                 "deletions": 0, "changed_files": 0, "commits": 1, "comments": 0, "review_comments": 0,
                 "head": {"ref": "feature", "sha": "1111111111111111111111111111111111111111"},
                 "base": {"ref": "main", "sha": "2222222222222222222222222222222222222222"}}
                """.formatted(id, number, current.getNameWithOwner(), number, updatedAt), GitHubPullRequestDTO.class);
    }

    private GitHubIssueDTO issue(long id, int number, String updatedAt) {
        return mapper.readValue(
                """
                {"id": %d, "number": %d, "title": "Moved work", "body": "", "state": "open",
                 "html_url": "https://github.com/%s/issues/%d", "comments": 0, "locked": false,
                 "created_at": "2026-10-01T09:00:00Z", "updated_at": "%s"}
                """.formatted(id, number, current.getNameWithOwner(), number, updatedAt), GitHubIssueDTO.class);
    }
}
