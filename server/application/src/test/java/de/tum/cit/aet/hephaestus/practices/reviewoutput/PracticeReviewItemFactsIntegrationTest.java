package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeReviewItemFactsIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @Autowired
    IdentityProviderRepository providers;

    @Autowired
    ConnectionRepository connections;

    @Autowired
    RepositoryRepository repositories;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    RepositoryToMonitorRepository monitors;

    @Autowired
    ItemIndexService index;

    @Autowired
    ScmItemService sources;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri(root() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Test
    void shouldListOnlyWorkspaceItemsAndApplyTheKindFilter() {
        data.scenario();
        get("/practices/items?itemKind=scm.pull_request")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(scenario.item().getId().toString());
        get("/practices/items?itemKind=scm.repository")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0)
                .jsonPath("$.content.length()")
                .isEqualTo(0);
    }

    @Test
    void itemReferenceUsesTheCanonicalId() {
        get("/practices/items/" + scenario.item().getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(scenario.item().getId().toString())
                .jsonPath("$.kind")
                .isEqualTo("scm.pull_request");
    }

    @Test
    void foreignItemCannotExposeItsFacts() {
        var other = data.scenario();
        get("/practices/items/" + other.item().getId() + "/facts")
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepFactsUnknownUntilTheRetainedPullRequestVersionIsAvailable(IdentityProviderType type) {
        String origin = type == IdentityProviderType.GITHUB ? "https://github.com" : "https://gitlab.example.test";
        var provider = providers
                .findByTypeAndServerUrl(type, origin)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(type, origin)));
        ConnectionConfig config = type == IdentityProviderType.GITHUB
                ? new ConnectionConfig.GitHubAppConfig(123L, "pilot", null, Set.of())
                : new ConnectionConfig.GitLabConfig(
                        origin, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of(), null);
        var connection = new Connection(scenario.workspace(), provider.kind(), "pilot", config);
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(scenario.item().getId());
        repository.setName("pilot");
        repository.setNameWithOwner(scenario.workspace().getWorkspaceSlug() + "/pilot");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository = repositories.saveAndFlush(repository);
        monitors.saveAndFlush(
                WorkspaceTestFixtures.repositoryMonitor(scenario.workspace(), repository.getNameWithOwner()));
        var pull = new PullRequest();
        pull.setProvider(provider);
        pull.setNativeId(scenario.item().getId());
        pull.setRepository(repository);
        pull.setNumber(7);
        pull.setTitle("Retained pilot work");
        pull.setState(Issue.State.OPEN);
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setBaseRefName("main");
        pull.setHeadRefName("topic");
        pull.setHeadRefOid("a".repeat(40));
        pull = pulls.saveAndFlush(pull);
        var parent = index.upsert(new ItemRegistration(
                scenario.workspace().getId(), ScmItemEvents.REPOSITORY, repository.getId(), null, null));
        var item = index.upsert(new ItemRegistration(
                parent.workspaceId(), ScmItemEvents.PULL_REQUEST, pull.getId(), parent.itemId(), null));
        String path = "/practices/items/" + item.itemId() + "/facts";
        get(path)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.item.id")
                .isEqualTo(Long.toString(item.itemId()))
                .jsonPath("$.facts.length()")
                .isEqualTo(0);
        assertThatThrownBy(() -> sources.pin(item, null))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        pull.setBaseRefOid("b".repeat(40));
        pulls.saveAndFlush(pull);
        get(path)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.facts[?(@.name == 'state')].value")
                .isEqualTo("OPEN")
                .jsonPath("$.facts[?(@.name == 'baseBranch')].value")
                .isEqualTo("main");
    }

    @Test
    void itemWithNoEventsReturnsAnEmptyPage() {
        get("/practices/items/" + scenario.item().getId() + "/events")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0);
    }
}
