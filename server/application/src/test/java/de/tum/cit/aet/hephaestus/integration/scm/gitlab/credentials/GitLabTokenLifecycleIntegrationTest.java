package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlInterceptor;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDataSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.status.GitLabConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.metrics.WorkspaceMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RecordApplicationEvents
class GitLabTokenLifecycleIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ConnectionRepository repository;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private GitLabTokenLifecycleService lifecycle;

    @Autowired
    private GitLabCredentialHealth health;

    @Autowired
    private GitLabCredentialHealthFilter filter;

    @Autowired
    private GitLabWebhookService webhooks;

    @Autowired
    private GitLabConnectionSyncStateProvider syncState;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private SyncTargetProvider syncTargets;

    @Autowired
    private GitLabDataSyncScheduler scheduler;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private GitLabTokenRotationClient rotation;

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldPauseEverySyncOfARefusedCredentialUntilItIsReplaced(CapturedOutput output) {
        var connection = connection("refused");
        long workspace = connection.getWorkspace().getId();
        assertThat(syncTargets.getSyncSession(workspace, IntegrationKind.GITLAB))
                .isPresent();
        double pausedBefore = paused();

        health.observe(workspace, "refused", true);
        health.observe(workspace, "refused", true);

        assertThat(syncTargets.getSyncSessions(IntegrationKind.GITLAB))
                .extracting(SyncSession::scopeId)
                .doesNotContain(workspace);
        assertThat(syncTargets.getSyncSession(workspace, IntegrationKind.GITLAB))
                .isEmpty();
        assertThat(syncTargets.isScopeActiveForSync(workspace)).isFalse();
        // A manual sync is refused before any GitLab call, with the reason in its message.
        assertThatThrownBy(() -> scheduler.syncWorkspaceNow(workspace, mock(), SyncJobType.RECONCILIATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused its credential");
        assertThat(paused() - pausedBefore).isEqualTo(3);
        String refused =
                "Integration credential refused, its sync waits for a reconnect: workspaceId=" + workspace + ",";
        assertThat(output.getOut().split(Pattern.quote(refused), -1)).hasSize(2);

        connections.rotateBearerToken(workspace, IntegrationKind.GITLAB, new BearerToken("replacement", null), null);

        assertThat(syncTargets.getSyncSession(workspace, IntegrationKind.GITLAB))
                .isPresent();
        assertThat(syncTargets.isScopeActiveForSync(workspace)).isTrue();
        assertThat(output).contains("Integration credential accepted again: workspaceId=" + workspace + ",");
    }

    private static GitLabTokenRotationClient.Credentials forWorkspace(long workspaceId) {
        return argThat(credentials -> credentials != null && credentials.workspaceId() == workspaceId);
    }

    private double paused() {
        var counter = meters.find(WorkspaceMetrics.WORKSPACE_SYNC_PAUSED)
                .tags("kind", "gitlab", "reason", "credential_revoked")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void shouldRotateAScheduledTokenAndKeepAnotherWorkspaceUnchanged() {
        var connection = connection("rotate");
        var other = connection("other");
        long workspace = connection.getWorkspace().getId();
        var expiry = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        var nextExpiry = LocalDate.now(ZoneOffset.UTC).plusDays(90);
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(1, "test", expiry));
        when(rotation.rotateToken(forWorkspace(workspace), any()))
                .thenReturn(new GitLabTokenRotationClient.RotatedToken("rotated", nextExpiry));
        when(rotation.getTokenInfo(forWorkspace(other.getWorkspace().getId())))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(2, "other", null));
        new GitLabTokenRotationScheduler(connections, workspaces, webhooks).rotateTokens();
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
        verify(rotation, never()).rotateToken(forWorkspace(other.getWorkspace().getId()), any());
    }

    @Test
    void shouldSendOneExpiringRevisionWhenRotationIsNotPermittedAndRecoverOnReplacement() {
        var connection = connection("expiring");
        long workspace = connection.getWorkspace().getId();
        var expiry = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(1, "test", expiry));
        when(rotation.rotateToken(forWorkspace(workspace), any()))
                .thenThrow(new WebClientResponseException(403, "Forbidden", null, null, null));
        lifecycle.check(workspace);
        lifecycle.check(workspace);
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_EXPIRING);
        assertThat(changes(connection)).hasSize(1);
        assertThat(changes(connection).getFirst().recovered()).isFalse();
        connections.rotateBearerToken(workspace, IntegrationKind.GITLAB, new BearerToken("replacement", null), null);
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
        connections.rotateBearerToken(workspace, IntegrationKind.GITLAB, new BearerToken("replacement", null), null);
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
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenThrow(new WebClientResponseException(401, "Unauthorized", null, null, null));
        lifecycle.check(workspace);
        lifecycle.check(workspace);
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_REVOKED);
        assertThat(syncState.describe(connection.toRef(), connection.getId()).vendorHealthDegraded())
                .isTrue();
        assertThat(changes(connection)).hasSize(1);
        verify(rotation, never()).rotateToken(forWorkspace(workspace), any());
        var other = connection("unavailable");
        when(rotation.getTokenInfo(forWorkspace(other.getWorkspace().getId())))
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
        stored.setConfig(config.withTokenMetadata(new ConnectionConfig.GitLabTokenMetadata(expiry, Instant.now())));
        repository.saveAndFlush(stored);
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenThrow(new WebClientResponseException(503, "Unavailable", null, null, null));
        lifecycle.check(workspace);
        lifecycle.check(workspace);
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_EXPIRING);
        assertThat(changes(connection)).hasSize(1);
        verify(rotation, never()).rotateToken(forWorkspace(workspace), any());
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
        var factory = new SilentModeGraphQlClientFactory(mock(SilentModeGraphQlInterceptor.class));
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
                                                refusal.getBytes(StandardCharsets.UTF_8)))
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

    @Test
    void shouldRefuseAnAmbientTransactionBeforeStartingTokenHttp() {
        var connection = connection("ambient");
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> assertThatThrownBy(
                                () -> lifecycle.check(connection.getWorkspace().getId()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("no active database transaction"));
        verify(rotation, never()).getTokenInfo(any());
    }

    @Test
    void shouldReleaseTheConnectionBeforeHttpAndIgnoreAnAnswerForAReplacedToken() {
        var connection = connection("inspection-old");
        long workspace = connection.getWorkspace().getId();
        when(rotation.getTokenInfo(forWorkspace(workspace))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(((GitLabTokenRotationClient.Credentials) invocation.getArgument(0)).token())
                    .isEqualTo("inspection-old");
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject(
                                "select id from connection where id = ? and workspace_id = ? for update nowait",
                                Long.class,
                                connection.getId(),
                                workspace))
                        .isEqualTo(connection.getId());
            });
            connections.rotateBearerToken(
                    workspace, IntegrationKind.GITLAB, new BearerToken("replacement", null), null);
            return new GitLabTokenRotationClient.TokenInfo(
                    1, "test", LocalDate.now(ZoneOffset.UTC).plusDays(90));
        });
        lifecycle.check(workspace);
        assertThat(connections
                        .findActiveBearerToken(workspace, IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("replacement");
        assertThat(connections.findActiveGitLabConfig(workspace).orElseThrow().tokenMetadata())
                .isNull();
        assertThat(current(connection).getAttentionProblem()).isNull();
        verify(rotation, never()).rotateToken(any(), any());
    }

    @Test
    void shouldNotOverwriteAReplacementThatCommitsDuringRotationHttp() {
        var connection = connection("rotation-old");
        long workspace = connection.getWorkspace().getId();
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(
                        1, "test", LocalDate.now(ZoneOffset.UTC).plusDays(1)));
        when(rotation.rotateToken(forWorkspace(workspace), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            connections.rotateBearerToken(
                    workspace, IntegrationKind.GITLAB, new BearerToken("replacement", null), null);
            return new GitLabTokenRotationClient.RotatedToken(
                    "stale-rotated-secret", LocalDate.now(ZoneOffset.UTC).plusDays(90));
        });
        lifecycle.check(workspace);
        assertThat(connections
                        .findActiveBearerToken(workspace, IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("replacement");
        assertThat(connections.findActiveGitLabConfig(workspace).orElseThrow().tokenMetadata())
                .isNull();
        assertThat(current(connection).getAttentionProblem()).isNull();
        assertThat(uncertainties(connection)).isEqualTo(1L);
        verify(rotation, times(1)).rotateToken(forWorkspace(workspace), any());
    }

    @Test
    void shouldStoreACompletedRotationDespiteAnUnrelatedWriteDuringThePost() {
        var connection = connection("unrelated-write-old");
        long workspace = connection.getWorkspace().getId();
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(
                        1, "test", LocalDate.now(ZoneOffset.UTC).plusDays(1)));
        when(rotation.rotateToken(forWorkspace(workspace), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> repository
                            .findByIdAndWorkspaceId(connection.getId(), workspace)
                            .orElseThrow()
                            .setDisplayName("Changed during token rotation"));
            return new GitLabTokenRotationClient.RotatedToken(
                    "unrelated-write-rotated", LocalDate.now(ZoneOffset.UTC).plusDays(90));
        });
        lifecycle.check(workspace);
        assertThat(connections
                        .findActiveBearerToken(workspace, IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("unrelated-write-rotated");
        assertThat(current(connection).getDisplayName()).isEqualTo("Changed during token rotation");
        assertThat(current(connection).getAttentionProblem()).isNull();
        assertThat(uncertainties(connection)).isZero();
        verify(rotation, times(1)).rotateToken(forWorkspace(workspace), any());
    }

    @Test
    void shouldRejectTheOldLifecycleAfterPauseAndResumeDuringThePost() {
        var connection = connection("resumed-old");
        long workspace = connection.getWorkspace().getId();
        var before = current(connection);
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(
                        1, "test", LocalDate.now(ZoneOffset.UTC).plusDays(1)));
        when(rotation.rotateToken(forWorkspace(workspace), any())).thenAnswer(invocation -> {
            var paused = connections.transition(
                    current(connection),
                    new ConnectionService.TransitionRequest(
                            IntegrationState.SUSPENDED,
                            "SUSPEND",
                            "SYSTEM",
                            null,
                            UUID.randomUUID().toString(),
                            "Pause"));
            connections.transition(
                    paused,
                    new ConnectionService.TransitionRequest(
                            IntegrationState.ACTIVE,
                            "RESUME",
                            "SYSTEM",
                            null,
                            UUID.randomUUID().toString(),
                            "Resume"));
            return new GitLabTokenRotationClient.RotatedToken(
                    "resumed-stale-result", LocalDate.now(ZoneOffset.UTC).plusDays(90));
        });
        lifecycle.check(workspace);
        var after = current(connection);
        assertThat(after.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(after.getCredentialsGeneration()).isEqualTo(before.getCredentialsGeneration());
        assertThat(after.getLifecycleGeneration()).isGreaterThan(before.getLifecycleGeneration());
        assertThat(connections
                        .findActiveBearerToken(workspace, IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("resumed-old");
        assertThat(after.getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_EXPIRING);
        assertThat(uncertainties(connection)).isEqualTo(1L);
        verify(rotation, times(1)).rotateToken(forWorkspace(workspace), any());
    }

    private long uncertainties(Connection connection) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM connection_audit WHERE workspace_id = ? AND connection_id = ? AND event_type = 'GITLAB_TOKEN_ROTATION_UNCERTAIN'",
                Long.class,
                connection.getWorkspace().getId(),
                connection.getId()));
    }

    @Test
    void shouldLockBothInitializationAndScheduledChecksWithoutHoldingATransaction() {
        var connection = connection("leased");
        long workspace = connection.getWorkspace().getId();
        when(rotation.getTokenInfo(forWorkspace(workspace))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            // The second entry point cannot rotate the same credential while the first request is in flight.
            webhooks.rotateTokenIfNeeded(workspaces.findById(workspace).orElseThrow());
            return new GitLabTokenRotationClient.TokenInfo(1, "test", null);
        });
        lifecycle.check(workspace);
        verify(rotation, times(1)).getTokenInfo(forWorkspace(workspace));
        verify(rotation, never()).rotateToken(any(), any());
        assertThat(connections.findActiveGitLabConfig(workspace).orElseThrow().tokenMetadata())
                .isNotNull();
    }

    @Test
    void shouldKeepAnotherServerFromCheckingWhileAStalledHolderIsAboutToRotate() throws Exception {
        var connection = connection("stalled-old");
        long workspace = connection.getWorkspace().getId();
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(
                        1, "test", LocalDate.now(ZoneOffset.UTC).plusDays(1)));
        when(rotation.rotateToken(forWorkspace(workspace), any())).thenAnswer(invocation -> {
            // A long stall outlives any time-based lease on this name. A check on another connection still cannot
            // start.
            jdbc.update(
                    "UPDATE shedlock SET lock_until = timezone('utc', CURRENT_TIMESTAMP) - INTERVAL '1 second' WHERE name = ?",
                    "gitlab-token-" + workspace);
            try (var successor = Executors.newSingleThreadExecutor()) {
                successor.submit(() -> lifecycle.check(workspace)).get(20, TimeUnit.SECONDS);
            }
            return new GitLabTokenRotationClient.RotatedToken(
                    "stalled-rotated", LocalDate.now(ZoneOffset.UTC).plusDays(90));
        });
        lifecycle.check(workspace);
        verify(rotation, times(1)).getTokenInfo(forWorkspace(workspace));
        verify(rotation, times(1)).rotateToken(forWorkspace(workspace), any());
        assertThat(connections
                        .findActiveBearerToken(workspace, IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("stalled-rotated");
        assertThat(uncertainties(connection)).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldRecordAnUncertainRotationWithoutRetryingOrExposingItsResponse(CapturedOutput output) {
        var connection = connection("uncertain-old");
        long workspace = connection.getWorkspace().getId();
        when(rotation.getTokenInfo(forWorkspace(workspace)))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(
                        1, "test", LocalDate.now(ZoneOffset.UTC).plusDays(1)));
        when(rotation.rotateToken(forWorkspace(workspace), any()))
                .thenThrow(new IllegalStateException("secret-rotation-response"));
        lifecycle.check(workspace);
        verify(rotation, times(1)).rotateToken(forWorkspace(workspace), any());
        assertThat(current(connection).getAttentionProblem()).isEqualTo(Problem.CREDENTIAL_EXPIRING);
        assertThat(connections.findActiveGitLabConfig(workspace).orElseThrow().tokenMetadata())
                .isNotNull()
                .satisfies(metadata -> assertThat(metadata.expiresAt())
                        .isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(1)));
        assertThat(jdbc.queryForList(
                        "select detail from connection_audit where connection_id = ? and workspace_id = ? and event_type = 'GITLAB_TOKEN_ROTATION_UNCERTAIN'",
                        String.class,
                        connection.getId(),
                        workspace))
                .singleElement()
                .satisfies(detail -> assertThat(detail)
                        .contains("Verify the token")
                        .doesNotContain("secret-rotation-response", "uncertain-old"));
        assertThat(output).doesNotContain("secret-rotation-response", "uncertain-old");
    }

    @Test
    void shouldRefuseAStaleCredentialFenceBeforeChangingTheStoredToken() {
        var connection = connection("stale-fence");
        var stored = current(connection);
        long workspace = stored.getWorkspace().getId();
        var stale = new ConnectionService.CredentialFence(
                stored.getId(), stored.getCredentialsGeneration() - 1, stored.getLifecycleGeneration());
        assertThat(connections.rotateBearerToken(
                        workspace, IntegrationKind.GITLAB, new BearerToken("late-result", null), stale))
                .isEmpty();
        assertThat(connections
                        .findActiveBearerToken(workspace, IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("stale-fence");
        assertThat(current(connection).getVersion()).isEqualTo(stored.getVersion());
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
                new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, null, Set.of(), null));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        connection = repository.save(connection);
        connections.rotateBearerToken(workspace.getId(), IntegrationKind.GITLAB, new BearerToken(token, null), null);
        return connection;
    }

    private Connection current(Connection connection) {
        return repository
                .findByIdAndWorkspaceId(
                        connection.getId(), connection.getWorkspace().getId())
                .orElseThrow();
    }

    private List<IntegrationAttentionChangedEvent> changes(Connection connection) {
        return events.stream(IntegrationAttentionChangedEvent.class)
                .filter(event -> event.connectionId() == connection.getId())
                .toList();
    }
}
