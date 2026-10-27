package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Transactional
class GitHubWebhookRepositoryIdentityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProcessingContextFactory contexts;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private ObjectMapper mapper;

    private IdentityProvider github;
    private IdentityProvider gitlab;
    private String owner;
    private long nativeId;

    @BeforeEach
    void setUp() {
        nativeId = 100_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()) % 900_000_000L;
        owner = "s-" + UUID.randomUUID();
        // The migration's seed row does not survive a class that cleans the database before this one.
        github = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        gitlab = providers.save(new IdentityProvider(
                IdentityProviderType.GITLAB, "https://" + UUID.randomUUID() + ".gitlab.example.test"));
        var workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces, connections, WorkspaceTestFixtures.installationWorkspace(nativeId, owner), nativeId);
        monitors.save(WorkspaceTestFixtures.repositoryMonitor(workspace, owner + "/target"));
        monitors.save(WorkspaceTestFixtures.repositoryMonitor(workspace, owner + "/sibling"));
    }

    @Test
    void shouldSelectTheGitHubSiblingByItsPersistedIdentity() {
        repository(gitlab, nativeId, owner + "/target");
        var sibling = repository(github, nativeId + 1, owner + "/sibling");

        var context = contexts.forWebhookEvent(event(sibling.getNativeId(), sibling.getNameWithOwner()));

        assertThat(context).hasValueSatisfying(selected -> {
            var repository = Objects.requireNonNull(selected.repository());
            assertThat(repository.getId()).isEqualTo(sibling.getId());
            assertThat(repository.getNativeId()).isEqualTo(sibling.getNativeId());
            assertThat(selected.providerId()).isEqualTo(github.getId());
        });
    }

    @Test
    void shouldIgnoreAnotherProvidersPathWhenTheGitHubRepositoryIsNotStored() {
        var foreign = repository(gitlab, nativeId, owner + "/target");
        assertThat(repositories.findByNameWithOwnerAndProviderId(
                        foreign.getNameWithOwner(), Objects.requireNonNull(github.getId())))
                .isEmpty();

        var context = contexts.forWebhookEvent(event(nativeId, foreign.getNameWithOwner()));

        assertThat(context)
                .as("a GitHub delivery must not select the GitLab row at its path")
                .isEmpty();
    }

    @Test
    void shouldSelectGitHubWhenAnotherProviderStoresTheSamePathAndNativeId() {
        var foreign = repository(gitlab, nativeId, owner + "/target");
        var correct = repository(github, nativeId, foreign.getNameWithOwner());
        assertThat(correct.getId()).isNotEqualTo(foreign.getId());

        var context = contexts.forWebhookEvent(event(correct.getNativeId(), correct.getNameWithOwner()));

        assertThat(context).hasValueSatisfying(selected -> {
            assertThat(Objects.requireNonNull(selected.repository()).getId()).isEqualTo(correct.getId());
            assertThat(selected.providerId()).isEqualTo(github.getId());
        });
    }

    @Test
    void shouldSelectTheRepositoryByItsGitHubIdWhenItsStoredNameIsStale() {
        var renamed = repository(github, nativeId, owner + "/sibling");

        var context = contexts.forWebhookEvent(event(renamed.getNativeId(), owner + "/target"));

        assertThat(context)
                .hasValueSatisfying(selected -> assertThat(
                                Objects.requireNonNull(selected.repository()).getId())
                        .isEqualTo(renamed.getId()));
    }

    @Test
    void shouldIgnoreTheRepositoryThatHeldTheNameBeforeARenameWhenAnotherRepositoryTakesIt() {
        var renamedAway = repository(github, nativeId, owner + "/target");

        var context = contexts.forWebhookEvent(event(nativeId + 1, owner + "/target"));

        assertThat(context)
                .as("a delivery for the new repository must not select row %s", renamedAway.getId())
                .isEmpty();
    }

    private Repository repository(IdentityProvider provider, long providerNativeId, String path) {
        var repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(providerNativeId);
        repository.setName(path.substring(path.lastIndexOf('/') + 1));
        repository.setNameWithOwner(path);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + path);
        repository.setPushedAt(Instant.parse("2026-10-01T09:00:00Z"));
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        assertThat(repository.getId()).isNull();
        var saved = repositories.saveAndFlush(repository);
        assertThat(saved.getId()).isPositive().isNotEqualTo(providerNativeId);
        return saved;
    }

    private GitHubIssueEventDTO event(long providerNativeId, String path) {
        return mapper.readValue(
                """
                {
                  "action": "opened",
                  "repository": {
                    "id": %d,
                    "name": "%s",
                    "full_name": "%s",
                    "html_url": "https://github.com/%s",
                    "default_branch": "main",
                    "private": false
                  },
                  "issue": {
                    "id": %d,
                    "number": 17,
                    "title": "Synthetic provider identity proof",
                    "state": "open",
                    "html_url": "https://github.com/%s/issues/17",
                    "created_at": "2026-10-01T09:00:00Z",
                    "updated_at": "2026-10-01T09:00:00Z",
                    "comments": 0,
                    "locked": false
                  }
                }
                """.formatted(
                        providerNativeId, path.substring(path.lastIndexOf('/') + 1), path, path, nativeId + 5, path),
                GitHubIssueEventDTO.class);
    }
}
