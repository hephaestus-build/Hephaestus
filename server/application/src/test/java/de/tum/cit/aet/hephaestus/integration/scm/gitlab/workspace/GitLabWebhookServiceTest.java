package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties.Http;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties.Publish;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties.Shutdown;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties.TokenRotation;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.integration.core.connection.BearerTokenReplacement;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient.RotatedToken;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient.TokenInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.GroupInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Unit tests for {@link GitLabWebhookService}. The service reads/writes the {@code GitLabConfig} on
 * the active GitLab Connection via {@link ConnectionService}; these tests back that registry with a
 * small in-memory map stand-in to avoid an integration test container.
 */
@Tag("unit")
class GitLabWebhookServiceTest extends BaseUnitTest {

    @Mock
    private ObjectProvider<GitLabWebhookClient> webhookClientProvider;

    @Mock
    private ObjectProvider<GitLabTokenRotationClient> rotationClientProvider;

    @Mock
    private ObjectProvider<GitLabTokenService> tokenServiceProvider;

    @Mock
    private GitLabWebhookClient webhookClient;

    @Mock
    private GitLabTokenRotationClient rotationClient;

    @Mock
    private GitLabTokenService tokenService;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ConnectionService connectionService;

    private GitLabWebhookService webhookService;
    private Workspace workspace;
    private Map<Long, ConnectionConfig.GitLabConfig> gitLabConfigs;
    private Map<Long, BearerToken> gitLabBearerTokens;

    private static final String EXTERNAL_URL = "https://app.example.com";
    private static final String SECRET = "a]RkF9P2s#Lm7$xQ4wN!vB3yJ6tH0dCe";

    @BeforeEach
    void setUp() {
        WebhookProperties properties = new WebhookProperties(
                EXTERNAL_URL,
                SECRET,
                new TokenRotation(7, 90),
                new Publish(java.time.Duration.ofSeconds(9), 5, java.time.Duration.ofMillis(200)),
                WebhookPropertiesFixture.stream(),
                new Shutdown(java.time.Duration.ofSeconds(15)),
                new Http(26_214_400L));

        webhookService = new GitLabWebhookService(
                webhookClientProvider,
                rotationClientProvider,
                tokenServiceProvider,
                properties,
                workspaceRepository,
                connectionService);

        workspace = new Workspace();
        workspace.setAccountLogin("my-org");
        ReflectionTestUtils.setField(workspace, "id", 1L);

        gitLabConfigs = new HashMap<>();
        gitLabBearerTokens = new HashMap<>();

        // Default: workspace is a GitLab workspace with a token. Tests that need
        // to flip this out (non-GitLab, missing token) override per-test.
        bindGitLabConfig(
                1L,
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.com",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of()));
        gitLabBearerTokens.put(1L, new BearerToken("glpat-test-token", null));

        // lenient() — each Nested test exercises a different code path, so a shared setUp stub may go
        // unused per test, which strict-stub mode would otherwise reject.
        Mockito.lenient()
                .when(connectionService.findActiveProviderKind(anyLong()))
                .thenAnswer(inv -> {
                    long id = inv.getArgument(0);
                    return gitLabConfigs.containsKey(id) ? Optional.of(IntegrationKind.GITLAB) : Optional.empty();
                });
        Mockito.lenient()
                .when(connectionService.findActiveGitLabConfig(anyLong()))
                .thenAnswer(inv -> {
                    long id = inv.getArgument(0);
                    return Optional.ofNullable(gitLabConfigs.get(id));
                });
        Mockito.lenient()
                .when(connectionService.findActiveBearerToken(anyLong(), eq(IntegrationKind.GITLAB)))
                .thenAnswer(inv -> {
                    long id = inv.getArgument(0);
                    return Optional.ofNullable(gitLabBearerTokens.get(id));
                });
        Mockito.lenient()
                .when(connectionService.updateConfig(anyLong(), eq(IntegrationKind.GITLAB), any()))
                .thenAnswer(this::applyUpdateConfig);
        Mockito.lenient()
                .when(connectionService.rotateBearerToken(
                        anyLong(), eq(IntegrationKind.GITLAB), any(BearerToken.class)))
                .thenAnswer(inv -> {
                    long id = inv.getArgument(0);
                    BearerToken token = inv.getArgument(2);
                    gitLabBearerTokens.put(id, token);
                    // The write reports the row it stored the token on; these tests only need one to exist.
                    Connection stored = new Connection(
                            TestEntities.workspace(id),
                            IntegrationKind.GITLAB,
                            "gitlab",
                            Objects.requireNonNull(gitLabConfigs.get(id)));
                    return Optional.of(new BearerTokenReplacement(stored, true));
                });
    }

    private void bindGitLabConfig(long workspaceId, ConnectionConfig.GitLabConfig cfg) {
        gitLabConfigs.put(workspaceId, cfg);
    }

    @SuppressWarnings("unchecked")
    private Optional<Object> applyUpdateConfig(InvocationOnMock inv) {
        long id = inv.getArgument(0);
        UnaryOperator<ConnectionConfig> mutator = inv.getArgument(2);
        ConnectionConfig.GitLabConfig current = gitLabConfigs.get(id);
        if (current == null) return Optional.empty();
        ConnectionConfig.GitLabConfig next = (ConnectionConfig.GitLabConfig) mutator.apply(current);
        gitLabConfigs.put(id, next);
        return Optional.empty();
    }

    private ConnectionConfig.GitLabConfig currentConfig(long workspaceId) {
        ConnectionConfig.GitLabConfig config = gitLabConfigs.get(workspaceId);
        assertNotNull(config);
        return config;
    }

    @Nested
    class RegisterWebhook {

        @Test
        void shouldSkipForNonGitLab() {
            gitLabConfigs.remove(1L);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("Not a GitLab");
        }

        @Test
        void shouldSkipWhenClientUnavailable() {
            when(webhookClientProvider.getIfAvailable()).thenReturn(null);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("unavailable");
        }

        @Test
        void shouldRegisterNewWebhook() {
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(webhookClient.lookupGroup(1L, "my-org")).thenReturn(new GroupInfo(42L, "My Org", "my-org"));
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenReturn(new WebhookInfo(99L, EXTERNAL_URL + "/webhooks/gitlab"));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(result.webhookId()).isEqualTo(99L);
            assertThat(result.groupId()).isEqualTo(42L);
            assertThat(currentConfig(1L).gitlabGroupId()).isEqualTo(42L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
        }

        @Test
        void shouldAdoptExistingWebhook() {
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(webhookClient.lookupGroup(1L, "my-org")).thenReturn(new GroupInfo(42L, "My Org", "my-org"));
            when(webhookClient.listGroupWebhooks(1L, 42L))
                    .thenReturn(List.of(
                            new WebhookInfo(77L, EXTERNAL_URL + "/webhooks/gitlab"),
                            new WebhookInfo(78L, "https://other.com/hooks")));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(result.webhookId()).isEqualTo(77L);
            verify(webhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());
        }

        @Test
        void shouldReturnSuccessForExistingWebhook() {
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));

            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(webhookClient.getGroupWebhook(1L, 42L, 99L))
                    .thenReturn(Optional.of(new WebhookInfo(99L, EXTERNAL_URL + "/webhooks/gitlab")));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(result.webhookId()).isEqualTo(99L);
            verify(webhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());
        }

        @Test
        void shouldReRegisterDeletedWebhook() {
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));

            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(webhookClient.getGroupWebhook(1L, 42L, 99L)).thenReturn(Optional.empty()); // Deleted externally
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenReturn(new WebhookInfo(100L, EXTERNAL_URL + "/webhooks/gitlab"));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            assertThat(result.webhookId()).isEqualTo(100L);
        }

        @Test
        void shouldReportRegistrationStatusWithoutLoggingTheProviderBody() {
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(webhookClient.lookupGroup(1L, "my-org")).thenReturn(new GroupInfo(42L, "My Org", "my-org"));
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenThrow(WebClientResponseException.create(
                            500,
                            "Server Error",
                            HttpHeaders.EMPTY,
                            "private-response-body with private-webhook-secret".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8));
            Logger logger = (Logger) LoggerFactory.getLogger(GitLabWebhookService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                WebhookSetupResult result = webhookService.registerWebhook(workspace);
                assertThat(result.registered()).isFalse();
                assertThat(result.failureReason()).isEqualTo("GitLab API error: 500");
                var failures = appender.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .toList();
                assertThat(failures).hasSize(1);
                ILoggingEvent event = failures.getFirst();
                assertThat(event.getKeyValuePairs()).isNotNull();
                var fields = event.getKeyValuePairs().stream()
                        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
                assertThat(fields)
                        .containsExactlyInAnyOrderEntriesOf(Map.of(
                                "event.name",
                                "integration.webhook.registration.failed",
                                "integration.kind",
                                IntegrationKind.GITLAB,
                                "workspace.id",
                                1L,
                                "http.response.status_code",
                                500));
                assertThat(event.getFormattedMessage()).isEqualTo("GitLab webhook registration failed");
                assertThat(event.getArgumentArray()).isNullOrEmpty();
                assertThat(event.getThrowableProxy()).isNull();
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        void shouldReturnFailedOn403() {
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(webhookClient.lookupGroup(1L, "my-org")).thenReturn(new GroupInfo(42L, "My Org", "my-org"));
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenThrow(WebClientResponseException.create(
                            403, "Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("Insufficient permissions");
        }

        @Test
        void shouldSkipWhenNotConfigured() {
            WebhookProperties unconfigured = new WebhookProperties(
                    "",
                    "",
                    new TokenRotation(7, 90),
                    new Publish(java.time.Duration.ofSeconds(9), 5, java.time.Duration.ofMillis(200)),
                    WebhookPropertiesFixture.stream(),
                    new Shutdown(java.time.Duration.ofSeconds(15)),
                    new Http(26_214_400L));
            var service = new GitLabWebhookService(
                    webhookClientProvider,
                    rotationClientProvider,
                    tokenServiceProvider,
                    unconfigured,
                    workspaceRepository,
                    connectionService);

            WebhookSetupResult result = service.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("not configured");
        }
    }

    @Nested
    class RotateTokenIfNeeded {

        @Test
        void shouldSkipForNonGitLab() {
            gitLabConfigs.remove(1L);

            webhookService.rotateTokenIfNeeded(workspace);

            verify(rotationClientProvider, never()).getIfAvailable();
        }

        @Test
        void shouldSkipWhenUnavailable() {
            when(rotationClientProvider.getIfAvailable()).thenReturn(null);

            webhookService.rotateTokenIfNeeded(workspace);
        }

        @Test
        void shouldSkipNoExpiry() {
            when(rotationClientProvider.getIfAvailable()).thenReturn(rotationClient);
            when(rotationClient.getTokenInfo(1L)).thenReturn(new TokenInfo(1L, "test", null));

            webhookService.rotateTokenIfNeeded(workspace);

            verify(rotationClient, never()).rotateToken(anyLong(), any());
        }

        @Test
        void shouldSkipNotExpiringSoon() {
            when(rotationClientProvider.getIfAvailable()).thenReturn(rotationClient);
            when(rotationClient.getTokenInfo(1L))
                    .thenReturn(new TokenInfo(1L, "test", LocalDate.now().plusDays(30)));

            webhookService.rotateTokenIfNeeded(workspace);

            verify(rotationClient, never()).rotateToken(anyLong(), any());
        }

        @Test
        void shouldRotateWhenExpiringSoon() {
            when(rotationClientProvider.getIfAvailable()).thenReturn(rotationClient);
            when(tokenServiceProvider.getIfAvailable()).thenReturn(tokenService);
            when(rotationClient.getTokenInfo(1L))
                    .thenReturn(new TokenInfo(1L, "test", LocalDate.now().plusDays(3)));
            when(rotationClient.rotateToken(eq(1L), any(LocalDate.class)))
                    .thenReturn(
                            new RotatedToken("glpat-new-token", LocalDate.now().plusDays(90)));

            webhookService.rotateTokenIfNeeded(workspace);

            BearerToken token = gitLabBearerTokens.get(1L);
            assertNotNull(token);
            assertThat(token.token()).isEqualTo("glpat-new-token");
            verify(connectionService).rotateBearerToken(eq(1L), eq(IntegrationKind.GITLAB), any(BearerToken.class));
            verify(tokenService).invalidateCache(1L);
        }

        @Test
        void shouldContinueOnError() {
            when(rotationClientProvider.getIfAvailable()).thenReturn(rotationClient);
            when(rotationClient.getTokenInfo(1L)).thenThrow(new IllegalStateException("Connection refused"));

            webhookService.rotateTokenIfNeeded(workspace);
        }
    }

    @Nested
    class DeregisterWebhook {

        @Test
        void shouldSkipWhenNoWebhook() {
            webhookService.deregisterWebhook(workspace);

            verify(webhookClientProvider, never()).getIfAvailable();
        }

        @Test
        void shouldDeregisterAndClearFields() {
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));

            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);

            webhookService.deregisterWebhook(workspace);

            verify(webhookClient).deregisterGroupWebhook(1L, 42L, 99L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isNull();
            assertThat(currentConfig(1L).gitlabGroupId()).isNull();
        }

        @Test
        void shouldClearFieldsOnError() {
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));

            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);

            Mockito.doThrow(new RuntimeException("API error"))
                    .when(webhookClient)
                    .deregisterGroupWebhook(1L, 42L, 99L);

            webhookService.deregisterWebhook(workspace);

            // Best-effort: fields cleared even though the vendor call failed.
            assertThat(currentConfig(1L).gitlabWebhookId()).isNull();
            assertThat(currentConfig(1L).gitlabGroupId()).isNull();
        }

        @Test
        void shouldClearFieldsWhenClientUnavailable() {
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));

            when(webhookClientProvider.getIfAvailable()).thenReturn(null);

            webhookService.deregisterWebhook(workspace);

            assertThat(currentConfig(1L).gitlabWebhookId()).isNull();
            assertThat(currentConfig(1L).gitlabGroupId()).isNull();
        }
    }

    @Nested
    class DeregisterWebhookByWorkspaceId {

        @Test
        void shouldDeregisterForExistingWorkspace() {
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));

            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);

            webhookService.deregisterWebhookByWorkspaceId(1L);

            verify(webhookClient).deregisterGroupWebhook(1L, 42L, 99L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isNull();
            assertThat(currentConfig(1L).gitlabGroupId()).isNull();
        }

        @Test
        void shouldDoNothingWhenWorkspaceNotFound() {
            when(workspaceRepository.findById(999L)).thenReturn(Optional.empty());

            webhookService.deregisterWebhookByWorkspaceId(999L);

            verify(webhookClientProvider, never()).getIfAvailable();
        }
    }

    @Nested
    class DeregisterActiveWebhook {

        @Test
        void deletesUpstreamButDoesNotRewriteConfig() {
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);

            webhookService.deregisterActiveWebhook(1L);

            verify(webhookClient).deregisterGroupWebhook(1L, 42L, 99L);
            // Config is NOT cleared here — the disconnect txn holds the same row and saves it moments
            // later; a rewrite would optimistic-lock-fail that save.
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
            assertThat(currentConfig(1L).gitlabGroupId()).isEqualTo(42L);
            verify(connectionService, never()).updateConfig(anyLong(), any(), any());
        }

        @Test
        void skipsWhenNoWebhookStored() {
            // Default config from setUp has null webhook/group ids.
            webhookService.deregisterActiveWebhook(1L);

            verify(webhookClientProvider, never()).getIfAvailable();
        }

        @Test
        void bestEffortSwallowsClientError() {
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            Mockito.doThrow(new IllegalStateException("scope not active"))
                    .when(webhookClient)
                    .deregisterGroupWebhook(1L, 42L, 99L);

            assertThatCode(() -> webhookService.deregisterActiveWebhook(1L)).doesNotThrowAnyException();

            // The swallow must be the ONLY effect: the vendor call was really attempted, and the failure
            // left the stored ids intact for the disconnect txn that owns the row.
            verify(webhookClient).deregisterGroupWebhook(1L, 42L, 99L);
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
            verify(connectionService, never()).updateConfig(anyLong(), any(), any());
        }
    }

    @Nested
    class DeregisterWebhookForConnection {

        @Test
        void deletesByConnectionIdRegardlessOfState() {
            var connection = Mockito.mock(de.tum.cit.aet.hephaestus.integration.core.connection.Connection.class);
            when(connection.getConfig())
                    .thenReturn(new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));
            when(connectionService.findInWorkspace(1L, 7L)).thenReturn(Optional.of(connection));
            when(connectionService.findBearerToken(1L, 7L))
                    .thenReturn(Optional.of(new BearerToken("glpat-token", null)));
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);

            webhookService.deregisterWebhookForConnection(1L, 7L);

            verify(webhookClient).deregisterGroupWebhookWithCredentials("https://gitlab.com", "glpat-token", 42L, 99L);
        }

        @Test
        void bestEffortSwallowsAuthFailurePostPurge() {
            var connection = Mockito.mock(de.tum.cit.aet.hephaestus.integration.core.connection.Connection.class);
            when(connection.getConfig())
                    .thenReturn(new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));
            when(connectionService.findInWorkspace(1L, 7L)).thenReturn(Optional.of(connection));
            when(connectionService.findBearerToken(1L, 7L))
                    .thenReturn(Optional.of(new BearerToken("glpat-token", null)));
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            Mockito.doThrow(new IllegalStateException("Scope 1 is not active"))
                    .when(webhookClient)
                    .deregisterGroupWebhookWithCredentials("https://gitlab.com", "glpat-token", 42L, 99L);

            assertThatCode(() -> webhookService.deregisterWebhookForConnection(1L, 7L))
                    .doesNotThrowAnyException();

            verify(webhookClient).deregisterGroupWebhookWithCredentials("https://gitlab.com", "glpat-token", 42L, 99L);
        }

        @Test
        void skipsWhenConnectionMissing() {
            when(connectionService.findInWorkspace(1L, 7L)).thenReturn(Optional.empty());

            webhookService.deregisterWebhookForConnection(1L, 7L);

            verify(webhookClientProvider, never()).getIfAvailable();
        }
    }

    @Nested
    class CheckWebhookHealth {

        @BeforeEach
        void bindActiveHookedWorkspace() {
            // A workspace with a registered group hook (groupId=42, webhookId=99).
            bindGitLabConfig(
                    1L,
                    new ConnectionConfig.GitLabConfig(
                            "https://gitlab.com",
                            42L,
                            99L,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()));
            when(webhookClientProvider.getIfAvailable()).thenReturn(webhookClient);
            when(workspaceRepository.findByStatus(Workspace.WorkspaceStatus.ACTIVE))
                    .thenReturn(List.of(workspace));
        }

        @Test
        void leavesExecutableWebhookUntouched() {
            when(webhookClient.getGroupWebhook(1L, 42L, 99L))
                    .thenReturn(Optional.of(new WebhookInfo(99L, EXTERNAL_URL + "/webhooks/gitlab", "executable")));

            webhookService.checkWebhookHealth();

            verify(webhookClient, never()).deregisterGroupWebhook(anyLong(), anyLong(), anyLong());
            verify(webhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
        }

        @Test
        void reRegistersAutoDisabledWebhook() {
            // GitLab kept the hook row but flipped it to alert_status=disabled — an existence check
            // alone would pass forever, so this is the invisible-failure the health check must heal.
            when(webhookClient.getGroupWebhook(1L, 42L, 99L))
                    .thenReturn(Optional.of(new WebhookInfo(99L, EXTERNAL_URL + "/webhooks/gitlab", "disabled")));
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenReturn(new WebhookInfo(100L, EXTERNAL_URL + "/webhooks/gitlab", "executable"));

            webhookService.checkWebhookHealth();

            // The disabled hook is deleted first so registerWebhook's adopt-by-URL step can't re-adopt it,
            // then a fresh hook is registered and its id persisted.
            verify(webhookClient).deregisterGroupWebhook(1L, 42L, 99L);
            verify(webhookClient).registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class));
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }

        @Test
        void reRegistersExternallyDeletedWebhook() {
            when(webhookClient.getGroupWebhook(1L, 42L, 99L)).thenReturn(Optional.empty());
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenReturn(new WebhookInfo(100L, EXTERNAL_URL + "/webhooks/gitlab", "executable"));

            webhookService.checkWebhookHealth();

            // Nothing to delete for a row that's already gone; a fresh hook is registered.
            verify(webhookClient, never()).deregisterGroupWebhook(anyLong(), anyLong(), anyLong());
            verify(webhookClient).registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class));
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }
    }
}
