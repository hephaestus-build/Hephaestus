package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewContextControllerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @Autowired
    ReviewLookupData lookup;

    @Autowired
    ProductReferenceQuery references;

    @Autowired
    SyncTargetProvider syncTargets;

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

    @ParameterizedTest
    @ValueSource(strings = {"/files", "?tab=checks", "/files?tab=checks#diff"})
    void shouldResolveGitHubPullRequestWhenAddressShowsATabOrQuery(String suffix) {
        var item = lookup.work(scenario.workspace(), IdentityProviderType.GITHUB, true);
        assertLookup(url(item) + suffix, item);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issues", "work_items"})
    void shouldResolveGitLabIssueWhenAddressUsesAnIssueOrWorkItemRoute(String route) {
        var item = lookup.work(scenario.workspace(), IdentityProviderType.GITLAB, false);
        assertLookup(url(item).replace("/issues/", "/" + route + "/"), item);
    }

    @Test
    void shouldResolveGitLabMergeRequestWhenAddressShowsDiffs() {
        var item = lookup.work(scenario.workspace(), IdentityProviderType.GITLAB, true);
        assertLookup(url(item) + "/diffs", item);
    }

    @Test
    void shouldResolveAMirroredGitLabMergeRequestWhenTheNormalSyncReconcilesItsMonitor() {
        var mirrored = lookup.mirror(scenario.workspace(), IdentityProviderType.GITLAB, true);
        context(mirrored.url()).expectStatus().isNotFound().expectBody(Void.class);

        syncTargets.reconcileSyncTargetIdentity(
                mirrored.monitorId(),
                mirrored.repository().getNativeId(),
                mirrored.repository().getNameWithOwner());

        context(mirrored.url() + "/diffs")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.item.kind")
                .isEqualTo("scm.pull_request");
    }

    @Test
    void shouldRejectAnAddressWhenOriginDiffersFromTheConnection() {
        var item = lookup.work(scenario.workspace(), IdentityProviderType.GITHUB, true);
        context(url(item).replace("https://github.com", "https://github.com.example.test"))
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    private String url(ItemRef item) {
        return Objects.requireNonNull(Objects.requireNonNull(references
                        .items(item.workspaceId(), List.of(item.itemId()))
                        .get(item.itemId()))
                .url());
    }

    private void assertLookup(String url, ItemRef item) {
        context(url)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.item.id")
                .isEqualTo(Long.toString(item.itemId()))
                .jsonPath("$.item.kind")
                .isEqualTo(item.kind().value());
    }

    private WebTestClient.ResponseSpec context(String url) {
        return http.get()
                .uri(b -> b.path(root() + "/practices/review-context")
                        .queryParam("url", url)
                        .build())
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Test
    void absoluteHttpsAddressIsRequired() {
        for (String url : List.of("/relative", "http://example.test/item", "https://user@example.test/item"))
            http.get()
                    .uri(b -> b.path(root() + "/practices/review-context")
                            .queryParam("url", url)
                            .build())
                    .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
    }

    @Test
    void anUnknownConnectedPageDoesNotResolveByItsName() {
        http.get()
                .uri(b -> b.path(root() + "/practices/review-context")
                        .queryParam("url", "https://github.com/unknown/project/pull/1")
                        .build())
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void lookupRequiresAuthentication() {
        http.get()
                .uri(b -> b.path(root() + "/practices/review-context")
                        .queryParam("url", "https://github.com/owner/project/pull/1")
                        .build())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }
}
