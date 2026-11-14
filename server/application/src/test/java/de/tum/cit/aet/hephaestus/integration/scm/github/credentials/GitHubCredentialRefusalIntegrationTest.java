package de.tum.cit.aet.hephaestus.integration.scm.github.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitObservationSink;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

class GitHubCredentialRefusalIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ConnectionRepository repository;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private GitHubGraphQlClientProvider clients;

    @Autowired
    private RateLimitObservationSink rateLimits;

    @Autowired
    private SyncTargetProvider syncTargets;

    @Test
    void shouldPauseTheSyncOfAStoredTokenThatGitHubRefusesUntilItIsReplaced() {
        long workspace = workspaceWithToken("ghp-refused");

        // A 403 (an organization policy, a missing scope) refuses one call, not the token.
        call(clients.forScope(workspace), HttpStatus.FORBIDDEN);
        assertThat(syncTargets.isScopeActiveForSync(workspace)).isTrue();
        assertThat(attention(workspace)).isNull();

        call(clients.forScope(workspace), HttpStatus.UNAUTHORIZED);

        assertThat(attention(workspace)).isEqualTo(Problem.CREDENTIAL_REVOKED);
        assertThat(syncTargets.getSyncSession(workspace, IntegrationKind.GITHUB))
                .isEmpty();
        assertThat(syncTargets.isScopeActiveForSync(workspace)).isFalse();

        connections.rotateBearerToken(
                workspace, IntegrationKind.GITHUB, new BearerToken("ghp-replacement", null), null);

        assertThat(attention(workspace)).isNull();
        assertThat(syncTargets.getSyncSession(workspace, IntegrationKind.GITHUB))
                .isPresent();
    }

    @Test
    void shouldKeepAReplacementTokenWhenGitHubRefusesTheTokenItReplaced() {
        long workspace = workspaceWithToken("ghp-old");
        HttpGraphQlClient inFlight = clients.forScope(workspace);
        connections.rotateBearerToken(workspace, IntegrationKind.GITHUB, new BearerToken("ghp-new", null), null);

        call(inFlight, HttpStatus.UNAUTHORIZED);

        assertThat(attention(workspace)).isNull();
        assertThat(syncTargets.isScopeActiveForSync(workspace)).isTrue();
    }

    private long workspaceWithToken(String token) {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("github-pat-" + UUID.randomUUID()));
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "https://github.com",
                new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        repository.save(connection);
        connections.rotateBearerToken(workspace.getId(), IntegrationKind.GITHUB, new BearerToken(token, null), null);
        // An observed rate limit keeps the client from probing the real GitHub REST API.
        rateLimits.updateFromRestRateLimit(workspace.getId(), 5000, 5000, null, Instant.now());
        return workspace.getId();
    }

    /** Sends the call through the client's own filters. GitHub's answer is stubbed at the transport. */
    private static void call(HttpGraphQlClient client, HttpStatus status) {
        client.mutate()
                .webClient(builder -> builder.exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"message\":\"Bad credentials\"}")
                        .build())))
                .build()
                .document("query { viewer { login } }")
                .execute()
                .onErrorResume(error -> Mono.empty())
                .block();
    }

    private @Nullable Problem attention(long workspace) {
        return connections
                .findActive(workspace, IntegrationKind.GITHUB)
                .orElseThrow()
                .getAttentionProblem();
    }
}
