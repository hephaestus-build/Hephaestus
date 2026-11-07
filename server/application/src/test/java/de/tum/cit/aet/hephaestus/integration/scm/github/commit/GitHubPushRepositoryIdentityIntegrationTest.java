package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewLookupData;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GitHubPushRepositoryIdentityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubPushMessageHandler handler;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private GitRepositoryManager gitRepositoryManager;

    private IdentityProvider github;
    private IdentityProvider gitlab;
    private String path;
    private long nativeId;

    @BeforeEach
    void setUp() {
        assertThat(gitRepositoryManager.isEnabled()).isFalse();
        github = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow();
        gitlab = providers.saveAndFlush(new IdentityProvider(
                IdentityProviderType.GITLAB, "https://" + UUID.randomUUID() + ".gitlab.example.test"));
        path = "p-" + UUID.randomUUID() + "/target";
        nativeId = 100_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()) % 900_000_000L;
    }

    @Autowired
    RepositoryRefState refs;

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewLookupData lookup;

    @Autowired
    IssueRepository issues;

    @Autowired
    JsonMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"refs/heads/deleted-branch", "refs/tags/v1"})
    void shouldInvalidateStoredRefsBeforeSkippingADeletedOrEmptyPush(String refName) {
        var scenario = data.scenario();
        var item = lookup.work(scenario.workspace(), IdentityProviderType.GITHUB, true);
        var repositoryId = Objects.requireNonNull(issues.findById(Objects.requireNonNull(item.sourceId()))
                        .orElseThrow()
                        .getRepository())
                .getId();
        var repository = repositories.findByIdWithOrganization(repositoryId).orElseThrow();
        path = repository.getNameWithOwner();
        var read = refs.begin(item.workspaceId(), repositoryId);
        refs.record(item.workspaceId(), repositoryId, read, "a".repeat(64));
        ObjectNode payload = mapper.valueToTree(push(repository.getNativeId(), commitSha()));
        payload.put("ref", refName).put("deleted", refName.startsWith("refs/heads/"));
        payload.set("commits", mapper.createArrayNode());
        handler.handleEvent(mapper.treeToValue(payload, GitHubPushEventDTO.class));
        assertThat(refs.current(item.workspaceId(), repositoryId)).isEmpty();
        refs.record(item.workspaceId(), repositoryId, read, "a".repeat(64));
        assertThat(refs.current(item.workspaceId(), repositoryId)).isEmpty();
    }

    @Test
    void shouldPersistPushWhenGitHubNativeIdDiffersFromGeneratedRepositoryId() {
        var organization = new Organization();
        organization.setProvider(github);
        organization.setNativeId(nativeId);
        organization.setLogin(path.substring(0, path.indexOf('/')));
        organization.setName("Synthetic organization");
        organization.setAvatarUrl("");
        organization.setHtmlUrl("https://github.com/" + organization.getLogin());
        var correct = repository(github, nativeId);
        correct.setOrganization(organizations.saveAndFlush(organization));
        correct = repositories.saveAndFlush(correct);
        long localId = Objects.requireNonNull(correct.getId());
        assertThat(localId).isNotEqualTo(nativeId);
        assertThat(repositories.findById(nativeId)).isEmpty();
        String sha = commitSha();

        handler.handleEvent(push(nativeId, sha));

        assertThat(persistedRepositoryIds(sha, List.of(correct))).containsExactly(localId);
    }

    @Test
    void shouldNotPersistGitHubPushOnForeignRepositoryWhoseGeneratedIdMatchesNativeId() {
        var foreign = repository(gitlab, nativeId);
        long incomingNativeId = Objects.requireNonNull(foreign.getId());
        assertThat(incomingNativeId).isBetween(1L, (long) Integer.MAX_VALUE);
        assertThat(repositories.findByNativeIdAndProviderId(incomingNativeId, Objects.requireNonNull(github.getId())))
                .isEmpty();
        String sha = commitSha();

        handler.handleEvent(push(incomingNativeId, sha));

        assertThat(persistedRepositoryIds(sha, List.of(foreign))).isEmpty();
    }

    @Test
    void shouldPersistOnlyOnGitHubWhenItsNativeIdMatchesForeignGeneratedId() {
        var foreign = repository(gitlab, nativeId);
        long incomingNativeId = Objects.requireNonNull(foreign.getId());
        assertThat(incomingNativeId).isBetween(1L, (long) Integer.MAX_VALUE);
        var correct = repository(github, incomingNativeId);
        long correctLocalId = Objects.requireNonNull(correct.getId());
        assertThat(correctLocalId).isNotEqualTo(incomingNativeId);
        String sha = commitSha();

        handler.handleEvent(push(incomingNativeId, sha));

        assertThat(persistedRepositoryIds(sha, List.of(foreign, correct))).containsExactly(correctLocalId);
    }

    private Repository repository(IdentityProvider provider, long providerNativeId) {
        var repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(providerNativeId);
        repository.setName("target");
        repository.setNameWithOwner(path);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + path);
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setDefaultBranch("main");
        repository.setPushedAt(Instant.parse("2026-10-01T09:00:00Z"));
        assertThat(repository.getId()).isNull();
        var saved = repositories.saveAndFlush(repository);
        assertThat(saved.getId()).isPositive();
        return saved;
    }

    private List<Long> persistedRepositoryIds(String sha, List<Repository> candidates) {
        var persisted = new ArrayList<Long>();
        for (var candidate : candidates) {
            Long localId = Objects.requireNonNull(candidate.getId());
            commits.findByShaAndRepositoryId(sha, localId).ifPresent(commit -> {
                assertThat(commit.getMessage()).isEqualTo("Synthetic push identity proof");
                assertThat(commit.getChangedFiles()).isEqualTo(1);
                persisted.add(localId);
            });
        }
        return persisted;
    }

    private String commitSha() {
        return UUID.randomUUID().toString().replace("-", "") + "1234abcd";
    }

    private GitHubPushEventDTO push(long providerNativeId, String sha) {
        var commit = new GitHubPushEventDTO.PushCommit(
                sha,
                "a".repeat(40),
                "Synthetic push identity proof",
                Instant.parse("2026-10-01T09:00:00Z"),
                "https://api.github.com/repos/" + path + "/commits/" + sha,
                new GitHubPushEventDTO.CommitUser("Synthetic author", "author@example.test", null),
                null,
                List.of("README.md"),
                List.of(),
                List.of(),
                true);
        return new GitHubPushEventDTO(
                "refs/heads/main",
                "0".repeat(40),
                sha,
                true,
                false,
                false,
                null,
                List.of(commit),
                commit,
                new GitHubRepositoryRefDTO(
                        providerNativeId,
                        "synthetic-node",
                        "target",
                        path,
                        false,
                        "https://github.com/" + path,
                        "main"),
                new GitHubPushEventDTO.Pusher("Synthetic author", "author@example.test"),
                null,
                null);
    }
}
