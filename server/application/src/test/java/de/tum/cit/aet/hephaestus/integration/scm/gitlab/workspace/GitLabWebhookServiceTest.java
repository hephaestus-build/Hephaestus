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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.reactive.function.client.WebClient;
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
        webhookService = serviceWithSecret(SECRET);

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

    private GitLabWebhookService serviceWithSecret(String secret) {
        WebhookProperties properties = new WebhookProperties(
                EXTERNAL_URL,
                secret,
                new TokenRotation(7, 90),
                new Publish(java.time.Duration.ofSeconds(9), 5, java.time.Duration.ofMillis(200)),
                WebhookPropertiesFixture.stream(),
                new Shutdown(java.time.Duration.ofSeconds(15)),
                new Http(26_214_400L));
        return new GitLabWebhookService(
                webhookClientProvider,
                rotationClientProvider,
                tokenServiceProvider,
                properties,
                workspaceRepository,
                connectionService);
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
                    .thenReturn(Optional.of(
                            new WebhookInfo(99L, EXTERNAL_URL + "/webhooks/gitlab", "executable", false, false)));

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
                    .thenReturn(Optional.of(
                            new WebhookInfo(99L, EXTERNAL_URL + "/webhooks/gitlab", "disabled", false, false)));
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenReturn(new WebhookInfo(100L, EXTERNAL_URL + "/webhooks/gitlab", "executable", false, false));

            webhookService.checkWebhookHealth();

            // The disabled hook is deleted first so registerWebhook's adopt-by-URL step can't re-adopt it,
            // then a fresh hook is registered and its id persisted.
            verify(webhookClient).deregisterGroupWebhook(1L, 42L, 99L);
            verify(webhookClient).registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class));
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }

        /** Recreating it would drop a signature only an explicit switch may remove. */
        @Test
        void leavesADisabledHookSignedUnderALegacyConnection() {
            when(webhookClient.getGroupWebhook(1L, 42L, 99L))
                    .thenReturn(Optional.of(
                            new WebhookInfo(99L, EXTERNAL_URL + "/webhooks/gitlab", "disabled", true, false)));

            webhookService.checkWebhookHealth();

            verify(webhookClient, never()).deregisterGroupWebhook(anyLong(), anyLong(), anyLong());
            verify(webhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
        }

        @Test
        void reRegistersExternallyDeletedWebhook() {
            when(webhookClient.getGroupWebhook(1L, 42L, 99L)).thenReturn(Optional.empty());
            when(webhookClient.listGroupWebhooks(1L, 42L)).thenReturn(List.of());
            when(webhookClient.registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class)))
                    .thenReturn(new WebhookInfo(100L, EXTERNAL_URL + "/webhooks/gitlab", "executable", false, false));

            webhookService.checkWebhookHealth();

            // Nothing to delete for a row that's already gone; a fresh hook is registered.
            verify(webhookClient, never()).deregisterGroupWebhook(anyLong(), anyLong(), anyLong());
            verify(webhookClient).registerGroupWebhook(eq(1L), eq(42L), any(WebhookConfig.class));
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
        }
    }

    /**
     * The group hooks API as GitLab documents it (https://docs.gitlab.com/api/group_webhooks/): the
     * secret travels as {@code token} for a legacy hook and as {@code signing_token} (GitLab 19.0+)
     * for a signed one, and GitLab answers with {@code signing_token_present} but never the token.
     */
    @Nested
    class SigningModeWireContract {

        private static final String SIGNING_TOKEN = "whsec_Z2l0bGFiLXNpZ25pbmcta2V5LW9mLTMyLWJ5dGVzISE=";
        private static final String HOOK_URL = EXTERNAL_URL + "/webhooks/gitlab";

        private MockWebServer gitlab;

        @BeforeEach
        void startGitLab() throws IOException {
            gitlab = new MockWebServer();
            gitlab.start();
            Mockito.lenient()
                    .when(tokenService.resolveServerUrl(1L))
                    .thenReturn("http://" + gitlab.getHostName() + ":" + gitlab.getPort());
            Mockito.lenient().when(tokenService.getAccessToken(1L)).thenReturn("glpat-test-token");
            when(webhookClientProvider.getIfAvailable())
                    .thenReturn(new GitLabWebhookClient(
                            Mockito.mock(GitLabGraphQlClientProvider.class), tokenService, WebClient.builder()));
        }

        @AfterEach
        void stopGitLab() throws IOException {
            gitlab.close();
        }

        private void bindMode(ConnectionConfig.GitLabConfig.SigningMode mode) {
            bindStoredHook(mode, null);
        }

        private void bindStoredHook(ConnectionConfig.GitLabConfig.SigningMode mode, @Nullable Long webhookId) {
            bindGitLabConfig(
                    1L, new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, webhookId, mode, Set.of()));
        }

        private void respond(String json) {
            gitlab.enqueue(new MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(json)
                    .build());
        }

        private String nextRequest(String method, String target) throws InterruptedException {
            RecordedRequest request = Objects.requireNonNull(gitlab.takeRequest(5, TimeUnit.SECONDS));
            assertThat(request.getMethod() + " " + request.getTarget()).isEqualTo(method + " " + target);
            var body = request.getBody();
            return body == null ? "" : body.utf8();
        }

        private String hook(long id, boolean token, boolean signingToken) {
            return "{\"id\":" + id + ",\"url\":\"" + HOOK_URL + "\",\"alert_status\":\"executable\","
                    + "\"token_present\":" + token + ",\"signing_token_present\":" + signingToken + "}";
        }

        /** The body a GitLab before 19.0 accepted: the secret token and no signing_token field at all. */
        @Test
        void shouldRegisterALegacyHookWithTheSecretTokenOnly() throws InterruptedException {
            bindMode(ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT);
            respond("[]");
            respond("{\"id\":100,\"url\":\"" + HOOK_URL + "\"}");

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            nextRequest("GET", "/api/v4/groups/42/hooks?per_page=100");
            assertThat(nextRequest("POST", "/api/v4/groups/42/hooks"))
                    .contains("\"token\":\"" + SECRET + "\"")
                    .doesNotContain("signing_token");
        }

        @Test
        void shouldRegisterWithBothTokensThenDropTheSecretTokenOnGitLab191() throws InterruptedException {
            bindMode(ConnectionConfig.GitLabConfig.SigningMode.WHSEC);
            respond("{\"version\":\"19.1.0-ee\",\"revision\":\"abc123\"}");
            respond("[]");
            respond(hook(100, true, true));
            respond(hook(100, true, true));
            respond(hook(100, false, true));
            respond(hook(100, false, true));

            WebhookSetupResult result = serviceWithSecret(SIGNING_TOKEN).registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            nextRequest("GET", "/api/v4/version");
            nextRequest("GET", "/api/v4/groups/42/hooks?per_page=100");
            assertThat(nextRequest("POST", "/api/v4/groups/42/hooks"))
                    .contains("\"token\":\"" + SIGNING_TOKEN + "\"")
                    .contains("\"signing_token\":\"" + SIGNING_TOKEN + "\"");
            // The key is sent again beside the secret token: presence alone never shows whose key it is.
            assertThat(nextRequest("PUT", "/api/v4/groups/42/hooks/100"))
                    .contains("\"token\":\"" + SIGNING_TOKEN + "\"")
                    .contains("\"signing_token\":\"" + SIGNING_TOKEN + "\"");
            assertThat(nextRequest("PUT", "/api/v4/groups/42/hooks/100"))
                    .contains("\"token\":null")
                    .contains("\"signing_token\":\"" + SIGNING_TOKEN + "\"");
            nextRequest("GET", "/api/v4/groups/42/hooks/100");
        }

        /** 19.0 ignores signing_token while its feature flag is off, so it cannot prove the stored key. */
        @Test
        void shouldRefuseSigningTokenModeBeforeGitLab191() throws InterruptedException {
            bindMode(ConnectionConfig.GitLabConfig.SigningMode.WHSEC);
            respond("{\"version\":\"19.0.3-ee\",\"revision\":\"abc123\"}");

            WebhookSetupResult result = serviceWithSecret(SIGNING_TOKEN).registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("GitLab 19.1 or later");
            nextRequest("GET", "/api/v4/version");
            assertThat(gitlab.getRequestCount()).isEqualTo(1);
        }

        /**
         * An operator may have signed the hook by hand, with a key nothing here can confirm: it is left
         * alone but not called registered. Only an explicit switch removes it.
         */
        @Test
        void shouldReportButKeepASignedHookUnderALegacyConnection() throws InterruptedException {
            bindStoredHook(ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, 99L);
            respond(hook(99, false, true));

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("signing token this connection does not use");
            nextRequest("GET", "/api/v4/groups/42/hooks/99");
            assertThat(gitlab.getRequestCount()).isEqualTo(1);
        }

        @Test
        void shouldRewriteAStoredLegacyHookThatPointsElsewhere() throws InterruptedException {
            bindStoredHook(ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, 99L);
            respond("{\"id\":99,\"url\":\"https://old.example.com/webhooks/gitlab\"}");
            respond("{\"id\":99,\"url\":\"" + HOOK_URL + "\"}");

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            nextRequest("GET", "/api/v4/groups/42/hooks/99");
            assertThat(nextRequest("PUT", "/api/v4/groups/42/hooks/99"))
                    .contains("\"url\":\"" + HOOK_URL + "\"")
                    .contains("\"token\":\"" + SECRET + "\"")
                    .doesNotContain("signing_token");
        }

        @Test
        void shouldFailWhenGitLabKeepsTheOldUrl() {
            bindStoredHook(ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, 99L);
            respond("{\"id\":99,\"url\":\"https://old.example.com/webhooks/gitlab\"}");
            respond("{\"id\":99,\"url\":\"https://old.example.com/webhooks/gitlab\"}");

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("previous URL");
        }

        @Test
        void shouldKeepTheSecretTokenWhenGitLabIgnoresTheSigningToken() throws InterruptedException {
            bindMode(ConnectionConfig.GitLabConfig.SigningMode.WHSEC);
            respond("{\"version\":\"19.1.0\"}");
            respond("[]");
            respond(hook(100, true, false));
            respond(hook(100, true, false));

            WebhookSetupResult result = serviceWithSecret(SIGNING_TOKEN).registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("keeps its secret token");
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(100L);
            nextRequest("GET", "/api/v4/version");
            nextRequest("GET", "/api/v4/groups/42/hooks?per_page=100");
            nextRequest("POST", "/api/v4/groups/42/hooks");
            assertThat(nextRequest("PUT", "/api/v4/groups/42/hooks/100"))
                    .contains("\"token\":\"" + SIGNING_TOKEN + "\"");
            assertThat(gitlab.getRequestCount()).isEqualTo(4);
        }

        /** Reasserting the key of a signing-token-only hook never hands GitLab the secret token again. */
        @Test
        void shouldResendOnlyTheSigningTokenToASigningTokenOnlyHook() throws InterruptedException {
            bindStoredHook(ConnectionConfig.GitLabConfig.SigningMode.WHSEC, 99L);
            respond("{\"version\":\"19.1.0\"}");
            respond(hook(99, false, true));
            respond(hook(99, false, true));
            respond(hook(99, false, true));

            WebhookSetupResult result = serviceWithSecret(SIGNING_TOKEN).registerWebhook(workspace);

            assertThat(result.registered()).isTrue();
            nextRequest("GET", "/api/v4/version");
            nextRequest("GET", "/api/v4/groups/42/hooks/99");
            assertThat(nextRequest("PUT", "/api/v4/groups/42/hooks/99"))
                    .contains("\"token\":null")
                    .contains("\"signing_token\":\"" + SIGNING_TOKEN + "\"");
            nextRequest("GET", "/api/v4/groups/42/hooks/99");
            assertThat(gitlab.getRequestCount()).isEqualTo(4);
        }

        /** An interrupted switch leaves both tokens; the health check finishes it. */
        @Test
        void shouldFinishAnInterruptedSwitchFromTheHealthCheck() throws InterruptedException {
            bindStoredHook(ConnectionConfig.GitLabConfig.SigningMode.WHSEC, 99L);
            when(workspaceRepository.findByStatus(Workspace.WorkspaceStatus.ACTIVE))
                    .thenReturn(List.of(workspace));
            respond(hook(99, true, true));
            respond("{\"version\":\"19.1.0\"}");
            respond(hook(99, true, true));
            respond(hook(99, true, true));
            respond(hook(99, false, true));
            respond(hook(99, false, true));

            serviceWithSecret(SIGNING_TOKEN).checkWebhookHealth();

            nextRequest("GET", "/api/v4/groups/42/hooks/99");
            nextRequest("GET", "/api/v4/version");
            nextRequest("GET", "/api/v4/groups/42/hooks/99");
            nextRequest("PUT", "/api/v4/groups/42/hooks/99");
            assertThat(nextRequest("PUT", "/api/v4/groups/42/hooks/99")).contains("\"token\":null");
            nextRequest("GET", "/api/v4/groups/42/hooks/99");
            assertThat(currentConfig(1L).gitlabWebhookId()).isEqualTo(99L);
        }

        @Test
        void shouldRefuseSigningTokenModeWithoutAGitLabSigningToken() {
            bindMode(ConnectionConfig.GitLabConfig.SigningMode.WHSEC);

            WebhookSetupResult result = webhookService.registerWebhook(workspace);

            assertThat(result.registered()).isFalse();
            assertThat(result.failureReason()).contains("whsec_");
            assertThat(gitlab.getRequestCount()).isZero();
        }
    }
}
