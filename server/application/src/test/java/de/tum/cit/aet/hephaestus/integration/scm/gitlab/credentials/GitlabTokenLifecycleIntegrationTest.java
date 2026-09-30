package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RecordApplicationEvents
class GitlabTokenLifecycleIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ConnectionRepository repository;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private GitlabTokenLifecycleService lifecycle;

    @Autowired
    private GitlabCredentialHealth health;

    @Autowired
    private GitlabCredentialHealthFilter filter;

    @Autowired
    private GitLabWebhookService webhooks;

    @Autowired
    private de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.status.GitlabConnectionSyncStateProvider syncState;

    @Autowired
    private ApplicationEvents events;

    @MockitoBean
    private GitLabTokenRotationClient rotation;

    @Test
    void shouldRotateAScheduledTokenAndKeepAnotherWorkspaceUnchanged() {
        var connection = connection("rotate");
        var other = connection("other");
        long workspace = connection.getWorkspace().getId();
        var expiry = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        var nextExpiry = LocalDate.now(ZoneOffset.UTC).plusDays(90);
        when(rotation.getTokenInfo(workspace)).thenReturn(new GitLabTokenRotationClient.TokenInfo(1, "test", expiry));
        when(rotation.rotateToken(eq(workspace), any()))
                .thenReturn(new GitLabTokenRotationClient.RotatedToken("rotated", nextExpiry));
        when(rotation.getTokenInfo(other.getWorkspace().getId()))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(2, "other", null));
        new GitlabTokenRotationScheduler(connections, workspaces, webhooks).rotateTokens();
        assertThat(connections
                        .findActiveBearerToken(workspace, IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("rotated");
        assertThat(connections.findActiveGitLabConfig(workspace).orElseThrow().tokenMetadata())
                .isNotNull()
                .extracting(ConnectionConfig.GitLabTokenMetadata::expiresAt)
                .isEqualTo(nextExpiry);
        assertThat(connections
                        .findActiveBearerToken(other.getWorkspace().getId(), IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("other");
        assertThat(current(other).getAttentionProblem()).isNull();
        verify(rotation, never()).rotateToken(eq(other.getWorkspace().getId()), any());
    }

    @Test
    void shouldSendOneExpiringRevisionWhenRotationIsNotPermittedAndRecoverOnReplacement() {
        var connection = connection("expiring");
        long workspace = connection.getWorkspace().getId();
        var expiry = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        when(rotation.getTokenInfo(workspace)).thenReturn(new GitLabTokenRotationClient.TokenInfo(1, "test", expiry));
        when(rotation.rotateToken(eq(workspace), any()))
                .thenThrow(new WebClientResponseException(403, "Forbidden", null, null, null));
        lifecycle.check(workspace);
        lifecycle.check(workspace);
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_EXPIRING);
        assertThat(changes(connection)).hasSize(1);
        assertThat(changes(connection).getFirst().recovered()).isFalse();
        connections.rotateBearerToken(workspace, IntegrationKind.GITLAB, new BearerToken("replacement", null));
        assertThat(current(connection).getAttentionProblem()).isNull();
        assertThat(changes(connection)).hasSize(2);
        assertThat(changes(connection).getLast().recovered()).isTrue();
        assertThat(connections.findActiveGitLabConfig(workspace).orElseThrow().tokenMetadata())
                .isNull();
    }

    @Test
    void shouldDegradeOnRest401RecoverOnSuccessfulCallAndIgnoreOldInFlightCredentials() {
        var connection = connection("refused");
        var other = connection("other");
        long workspace = connection.getWorkspace().getId();
        var refused = client(HttpStatus.UNAUTHORIZED);
        refused.get()
                .uri("https://gitlab.com/api/v4/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer refused")
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, workspace)
                .exchangeToMono(response -> response.releaseBody())
                .block();
        health.observe(workspace, "refused", true);
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_REVOKED);
        assertThat(syncState.describe(connection.toRef(), connection.getId()).vendorHealthDegraded())
                .isTrue();
        assertThat(changes(connection)).hasSize(1);
        assertThat(current(other).getAttentionProblem()).isNull();
        var success = client(HttpStatus.OK);
        success.get()
                .uri("https://gitlab.com/api/v4/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer refused")
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, workspace)
                .exchangeToMono(response -> response.releaseBody())
                .block();
        assertThat(current(connection).getAttentionProblem()).isNull();
        health.observe(workspace, "refused", true);
        connections.rotateBearerToken(workspace, IntegrationKind.GITLAB, new BearerToken("replacement", null));
        health.observe(workspace, "refused", true);
        assertThat(current(connection).getAttentionProblem()).isNull();
        assertThat(syncState.describe(connection.toRef(), connection.getId()).vendorHealthDegraded())
                .isFalse();
        assertThat(current(other).getAttentionRevision()).isZero();
    }

    @Test
    void shouldRecordAnInspection401ButNotAMissingProviderResponseAsExpiry() {
        var connection = connection("refused");
        long workspace = connection.getWorkspace().getId();
        when(rotation.getTokenInfo(workspace))
                .thenThrow(new WebClientResponseException(401, "Unauthorized", null, null, null));
        lifecycle.check(workspace);
        lifecycle.check(workspace);
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_REVOKED);
        assertThat(syncState.describe(connection.toRef(), connection.getId()).vendorHealthDegraded())
                .isTrue();
        assertThat(changes(connection)).hasSize(1);
        verify(rotation, never()).rotateToken(eq(workspace), any());
        var other = connection("unavailable");
        when(rotation.getTokenInfo(other.getWorkspace().getId()))
                .thenThrow(new WebClientResponseException(503, "Unavailable", null, null, null));
        lifecycle.check(other.getWorkspace().getId());
        assertThat(current(other).getAttentionProblem()).isNull();
    }

    @Test
    void shouldWarnWhenInspectionFailsForAKnownExpiringToken() {
        var connection = connection("known-expiry");
        long workspace = connection.getWorkspace().getId();
        var expiry = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        var stored = current(connection);
        var config = (ConnectionConfig.GitLabConfig) stored.getConfig();
        stored.setConfig(
                config.withTokenMetadata(new ConnectionConfig.GitLabTokenMetadata(expiry, java.time.Instant.now())));
        repository.saveAndFlush(stored);
        when(rotation.getTokenInfo(workspace))
                .thenThrow(new WebClientResponseException(503, "Unavailable", null, null, null));
        lifecycle.check(workspace);
        lifecycle.check(workspace);
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_EXPIRING);
        assertThat(changes(connection)).hasSize(1);
        verify(rotation, never()).rotateToken(eq(workspace), any());
    }

    @Test
    void shouldDegradeOnGraphQlAuthenticationErrorsAndRecoverOnlyOnACleanGraphQlCall() {
        var connection = connection("graphql");
        long workspace = connection.getWorkspace().getId();
        var body = new AtomicReference<>(
                "{\"errors\":[{\"message\":\"Invalid token\",\"extensions\":{\"type\":\"UNAUTHORIZED\"}}]}");
        var http = WebClient.builder()
                .filter(filter.filter())
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body.get())
                        .build()))
                .build();
        var base = HttpGraphQlClient.builder(http).build();
        var tokens = mock(GitLabTokenService.class);
        when(tokens.getAccessToken(workspace)).thenReturn("graphql");
        when(tokens.resolveServerUrl(workspace)).thenReturn("https://gitlab.com");
        var factory = mock(SilentModeGraphQlClientFactory.class);
        when(factory.withBearerTokenAndAttribute(
                        base,
                        "https://gitlab.com/api/graphql",
                        "graphql",
                        GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE,
                        workspace))
                .thenReturn(base.mutate()
                        .url("https://gitlab.com/api/graphql")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer graphql")
                        .build());
        var provider = new GitLabGraphQlClientProvider(
                base,
                tokens,
                CircuitBreaker.ofDefaults("gitlab-health"),
                mock(GitLabRateLimitTracker.class),
                factory,
                filter);
        provider.forScope(workspace)
                .document("{ currentUser { id } }")
                .execute()
                .block();
        assertThat(syncState.describe(connection.toRef(), connection.getId()).vendorHealthDegraded())
                .isTrue();
        body.set("{\"errors\":[{\"message\":\"Repository unavailable\",\"extensions\":{\"code\":\"NOT_FOUND\"}}]}");
        provider.forScope(workspace)
                .document("{ currentUser { id } }")
                .execute()
                .block();
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_REVOKED);
        body.set("{\"data\":{\"currentUser\":{\"id\":\"gid://gitlab/User/1\"}}}");
        provider.forScope(workspace)
                .document("{ currentUser { id } }")
                .execute()
                .block();
        assertThat(syncState.describe(connection.toRef(), connection.getId()).vendorHealthDegraded())
                .isFalse();
        assertThat(changes(connection)).hasSize(2);
    }

    @Test
    void shouldNotRevokeAValidTokenWhenGitLabRefusesSelfApproval() {
        var connection = connection("author");
        health.observe(connection.getWorkspace().getId(), "author", true);
        var bodyConsumed = new AtomicBoolean();
        var probeAfterConsumption = new AtomicBoolean();
        String refusal = "{\"message\":\"An author cannot approve their own merge request\"}";
        var client = WebClient.builder()
                .filter(filter.filter())
                .exchangeFunction(request -> {
                    if (request.url().getPath().endsWith("/approve")) {
                        return Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED)
                                .body(Flux.<DataBuffer>just(DefaultDataBufferFactory.sharedInstance.wrap(
                                                refusal.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                        .doOnComplete(() -> bodyConsumed.set(true)))
                                .build());
                    }
                    probeAfterConsumption.set(bodyConsumed.get());
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                })
                .build();
        String returnedBody = client.post()
                .uri("https://gitlab.com/api/v4/projects/1/merge_requests/1/approve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer author")
                .attribute(
                        GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE,
                        connection.getWorkspace().getId())
                .exchangeToMono(response -> response.bodyToMono(String.class))
                .block();
        assertThat(returnedBody).isEqualTo(refusal);
        assertThat(probeAfterConsumption).isTrue();
        assertThat(current(connection).getAttentionProblem()).isNull();
        assertThat(changes(connection)).hasSize(2);
        assertThat(changes(connection).getLast().recovered()).isTrue();
    }

    private WebClient client(HttpStatus status) {
        return WebClient.builder()
                .filter(filter.filter())
                .exchangeFunction(
                        request -> Mono.just(ClientResponse.create(status).build()))
                .build();
    }

    private Connection connection(String token) {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("token-" + UUID.randomUUID()));
        var connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "https://gitlab.com",
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.com",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of(),
                        null));
        connection.setState(IntegrationState.ACTIVE);
        connection = repository.save(connection);
        connections.rotateBearerToken(workspace.getId(), IntegrationKind.GITLAB, new BearerToken(token, null));
        return connection;
    }

    private Connection current(Connection connection) {
        return repository
                .findByIdAndWorkspaceId(
                        connection.getId(), connection.getWorkspace().getId())
                .orElseThrow();
    }

    private java.util.List<IntegrationAttentionChangedEvent> changes(Connection connection) {
        return events.stream(IntegrationAttentionChangedEvent.class)
                .filter(event -> event.connectionId() == connection.getId())
                .toList();
    }
}
